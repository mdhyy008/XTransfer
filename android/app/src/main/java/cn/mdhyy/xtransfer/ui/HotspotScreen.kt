package cn.mdhyy.xtransfer.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.mdhyy.xtransfer.XlanApp
import cn.mdhyy.xtransfer.data.HotspotState
import cn.mdhyy.xtransfer.data.wifiQrPayload
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotspotScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val hotspot = remember { (context.applicationContext as XlanApp).hotspot }
    val state by hotspot.state.collectAsStateWithLifecycle()
    var denied by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        if (granted) hotspot.start()
    }

    fun start() {
        if (hotspot.granted()) hotspot.start() else launcher.launch(hotspot.permission)
    }

    var pendingWifi by remember { mutableStateOf<Pair<String, String>?>(null) }
    val wifiPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val target = pendingWifi
        pendingWifi = null
        if (target == null) return@rememberLauncherForActivityResult
        if (granted.values.all { it }) {
            connectWifi(context, target.first, target.second)
        } else {
            context.showError("没有连接 Wi-Fi 的权限")
        }
    }
    var scanning by remember { mutableStateOf(false) }

    fun handleScan(contents: String): Boolean {
        val wifi = parseWifiQr(contents) ?: return false
        scanning = false
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val granted = perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) {
            connectWifi(context, wifi.first, wifi.second)
        } else {
            pendingWifi = wifi
            wifiPermissionLauncher.launch(perms)
        }
        return true
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("热点") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))
            when (val current = state) {
                is HotspotState.Running -> {
                    // 热点网卡拿到地址可能晚于热点开启，取不到就继续等。
                    val link by produceState<String?>(null, current.startedAt) {
                        while (value == null) {
                            value = hotspot.linkAddress()?.let { "${it.first}/${it.second}" }
                            if (value == null) delay(500)
                        }
                    }
                    RunningContent(current, link, onStop = { hotspot.stop() })
                }
                else -> {
                    val starting = current is HotspotState.Starting
                    val failure = (current as? HotspotState.Failed)?.message
                        ?: if (denied) "没有热点权限" else null
                    HotspotBadge(starting = starting, failed = failure != null)
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = if (starting) "正在开启" else "未开启",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (failure != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = failure,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(Modifier.height(28.dp))
                    Button(onClick = { start() }, enabled = !starting) { Text("开启热点") }
                    if (failure != null) {
                        TextButton(onClick = { openTetherSettings(context) }) {
                            Text("系统热点设置")
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        Text(
                            text = "or",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                        HorizontalDivider(
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    FilledTonalButton(onClick = { scanning = true }) {
                        Text("扫码连接热点")
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    AnimatedVisibility(
        visible = scanning,
        enter = slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(200)),
        exit = slideOutVertically(tween(280, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(200))
    ) {
        WifiQrScannerOverlay(
            onClose = { scanning = false },
            onResult = { handleScan(it) }
        )
    }
    }
}

@Composable
private fun HotspotBadge(starting: Boolean, failed: Boolean) {
    val transition = rememberInfiniteTransition(label = "hotspot")
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val accent = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier.size(148.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(148.dp * pulse)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.1f))
        )
        Box(
            modifier = Modifier
                .size(92.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            if (starting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(30.dp),
                    strokeWidth = 3.dp,
                    color = accent
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.WifiTethering,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
    }
}

@Composable
private fun RunningContent(
    state: HotspotState.Running,
    address: String?,
    onStop: () -> Unit
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val payload = remember(state.ssid, state.password) {
        wifiQrPayload(state.ssid, state.password)
    }
    val qr = remember(payload) { qrBitmap(payload, 720) }
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = ComposeColor.White,
        shadowElevation = 6.dp
    ) {
        Box(
            modifier = Modifier
                .padding(18.dp)
                .size(236.dp),
            contentAlignment = Alignment.Center
        ) {
            if (qr != null) {
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = "热点二维码",
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = "二维码生成失败",
                    color = ComposeColor.Black
                )
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            CredentialRow("名称", state.ssid)
            CredentialDivider()
            CredentialRow("密码", state.password)
            CredentialDivider()
            CredentialRow("开启时间", clockText(state.startedAt))
            CredentialDivider()
            CredentialRow("已开启", elapsedText(now - state.startedAt))
            if (address != null) {
                CredentialDivider()
                CredentialRow("本机地址", address)
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    OutlinedButton(onClick = onStop) { Text("关闭热点") }
}

@Composable
private fun CredentialDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    )
}

private fun clockText(timestamp: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.CHINA).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }.format(Date(timestamp))

private fun elapsedText(millis: Long): String {
    val total = (millis.coerceAtLeast(0) / 1000).toInt()
    return String.format(
        Locale.CHINA,
        "%02d:%02d:%02d",
        total / 3600,
        total % 3600 / 60,
        total % 60
    )
}

@Composable
private fun CredentialRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

internal fun qrBitmap(content: String, size: Int): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(matrix.width * matrix.height)
    for (y in 0 until matrix.height) {
        val offset = y * matrix.width
        for (x in 0 until matrix.width) {
            pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
        }
    }
    Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
    }
}.getOrNull()

// 解析 WIFI:T:WPA;S:ssid;P:pass;; 这一格式，字段里的 \; \, \\ \: 要还原。
private fun parseWifiQr(text: String): Pair<String, String>? {
    if (!text.startsWith("WIFI:", ignoreCase = true)) return null
    val fields = HashMap<String, String>()
    val current = StringBuilder()
    var key: String? = null
    var escaped = false
    for (ch in text.substring(5)) {
        when {
            escaped -> {
                current.append(ch)
                escaped = false
            }
            ch == '\\' -> escaped = true
            ch == ';' -> {
                key?.let { fields[it] = current.toString() }
                current.setLength(0)
                key = null
            }
            ch == ':' && key == null -> {
                key = current.toString().trim().uppercase()
                current.setLength(0)
            }
            else -> current.append(ch)
        }
    }
    val ssid = fields["S"]?.takeIf { it.isNotBlank() } ?: return null
    return ssid to fields["P"].orEmpty()
}

private fun connectWifi(context: Context, ssid: String, password: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        runCatching {
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("wifi", password))
            context.startActivity(
                Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { context.showError("无法打开 Wi-Fi 设置") }
        context.toast("密码已复制")
        return
    }
    val specifier = WifiNetworkSpecifier.Builder()
        .setSsid(ssid)
        .apply { if (password.isNotEmpty()) setWpa2Passphrase(password) }
        .build()
    val request = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .setNetworkSpecifier(specifier)
        .build()
    val manager = context.getSystemService(ConnectivityManager::class.java)
    if (manager == null) {
        context.showError("无法连接 Wi-Fi")
        return
    }
    manager.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            context.toast("已连接 $ssid")
        }

        override fun onUnavailable() {
            context.showError("连接失败，请在系统 Wi-Fi 设置中连接")
        }
    })
}

private fun openTetherSettings(context: Context) {
    val candidates = listOf(
        Intent("android.settings.TETHER_SETTINGS"),
        Intent(Settings.ACTION_WIRELESS_SETTINGS)
    )
    for (intent in candidates) {
        val opened = runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (opened) return
    }
}
