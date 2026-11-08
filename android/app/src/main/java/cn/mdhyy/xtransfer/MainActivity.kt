package cn.mdhyy.xtransfer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.mdhyy.xtransfer.data.PermissionPrompts
import cn.mdhyy.xtransfer.data.StorageAccess
import cn.mdhyy.xtransfer.service.XlanService
import cn.mdhyy.xtransfer.ui.AppRoot
import cn.mdhyy.xtransfer.ui.ShareNotice
import cn.mdhyy.xtransfer.ui.XTransferTheme
import cn.mdhyy.xtransfer.widget.DevicesWidgetProvider
import cn.mdhyy.xtransfer.widget.FilesWidgetProvider
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private var pendingConv by mutableStateOf<String?>(null)
    private var pendingPage by mutableStateOf<String?>(null)
    private var pendingLocalFile by mutableStateOf<String?>(null)
    private var shareNotice by mutableStateOf<ShareNotice?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        XlanService.start(this)
        requestNotifications()
        requestStorage()
        consumeShare(intent)
        pendingConv = intent?.getStringExtra("conv")
        pendingPage = intent?.getStringExtra("page")
        pendingLocalFile = intent?.getStringExtra("open_file")
        setContent {
            val repo = (application as XlanApp).repo
            val settings by repo.settings.collectAsStateWithLifecycle()
            val service by repo.service.collectAsStateWithLifecycle()
            var minElapsed by remember { mutableStateOf(false) }
            var splashDone by remember { mutableStateOf(false) }
            // 先让首屏在启动页底下合成好，服务就绪后淡出，打开过程不闪白屏。
            LaunchedEffect(Unit) {
                delay(500)
                minElapsed = true
            }
            LaunchedEffect(minElapsed, service.running) {
                if (minElapsed && service.running) splashDone = true
            }
            LaunchedEffect(Unit) {
                // 服务迟迟起不来也别一直挡着。
                delay(3000)
                splashDone = true
            }
            XTransferTheme(mode = settings.theme, seed = settings.seed, misans = settings.misans) {
                Box(Modifier.fillMaxSize()) {
                    AppRoot(
                        repo = repo,
                        pendingConv = pendingConv,
                        onConsumePending = { pendingConv = null },
                        pendingPage = pendingPage,
                        onConsumePage = { pendingPage = null },
                        pendingLocalFile = pendingLocalFile,
                        onConsumeLocalFile = { pendingLocalFile = null },
                        shareNotice = shareNotice,
                        onConsumeShareNotice = { shareNotice = null }
                    )
                    AnimatedVisibility(
                        visible = !splashDone,
                        exit = fadeOut(tween(320))
                    ) {
                        LaunchScreen()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("conv")?.let { pendingConv = it }
        intent.getStringExtra("page")?.let { pendingPage = it }
        intent.getStringExtra("open_file")?.let { pendingLocalFile = it }
        consumeShare(intent)
    }

    private fun consumeShare(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { listOf(it) } ?: return
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?: return
            else -> return
        }
        if (uris.isEmpty()) return
        val repo = (application as XlanApp).repo
        Thread {
            val items = uris.mapNotNull { repo.makeFileItem(it) }
            if (items.isEmpty()) return@Thread
            repo.addBucket(items)
            val notice = ShareNotice(
                files = items.count { it.kind != "folder" },
                folders = items.count { it.kind == "folder" }
            )
            runOnUiThread { shareNotice = notice }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        XlanApp.foreground = true
        (application as XlanApp).wifiDirect.startIfEnabled()
        (application as XlanApp).ble.startIfEnabled()
        FilesWidgetProvider.refresh(this)
        DevicesWidgetProvider.refresh(this)
    }

    override fun onPause() {
        super.onPause()
        XlanApp.foreground = false
        (application as XlanApp).wifiDirect.stop()
        (application as XlanApp).ble.pauseScanning()
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        PermissionPrompts.mark(this, PermissionPrompts.NOTIFICATIONS)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun requestStorage() {
        if (StorageAccess.granted(this)) return
        PermissionPrompts.mark(this, PermissionPrompts.STORAGE)
        StorageAccess.request(this)
    }
}

@Composable
private fun LaunchScreen() {
    var shown by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.88f,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "launch-scale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "launch-alpha"
    )
    LaunchedEffect(Unit) { shown = true }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .scale(scale)
                .alpha(alpha)
        ) {
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF57BDEC), Color(0xFF0070D3))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = "X互传",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}
