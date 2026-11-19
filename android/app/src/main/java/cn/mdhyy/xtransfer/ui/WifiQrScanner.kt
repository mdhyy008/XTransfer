package cn.mdhyy.xtransfer.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.NoPhotography
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.os.SystemClock
import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.Result
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.Decoder
import com.journeyapps.barcodescanner.DecoderFactory
import com.journeyapps.barcodescanner.camera.CenterCropStrategy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 自绘的二维码扫描页：相机预览铺满全屏，中间挖圆角取景框，四角描边加一条上下扫的光线。
// 回调用返回 true 表示已处理（父级会关闭扫描页），返回 false 则提示后继续扫。
@Composable
fun WifiQrScannerOverlay(onClose: () -> Unit, onResult: (String) -> Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestResult = rememberUpdatedState(onResult)
    val accent = MaterialTheme.colorScheme.primary
    val appDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var torch by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val handling = remember { mutableStateOf(false) }
    val hasFlash = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val barcodeView = remember {
        BarcodeView(context).apply {
            // TextureView 才能让 Compose 的遮罩画在预览上面。
            setUseTextureView(true)
            setPreviewScalingStrategy(CenterCropStrategy())
            decoderFactory = ThrottledQrDecoderFactory
            decodeContinuous(object : BarcodeCallback {
                override fun barcodeResult(result: BarcodeResult) {
                    val text = result.text ?: return
                    if (text.isBlank() || handling.value) return
                    handling.value = true
                    vibrate(context)
                    if (latestResult.value(text)) return
                    notice = "不是热点二维码"
                    scope.launch {
                        delay(1500)
                        notice = null
                        handling.value = false
                    }
                }
            })
        }
    }

    BackHandler(onBack = onClose)

    // 扫描页是黑底，状态栏图标临时切成浅色，退出时按主题恢复。
    val view = LocalView.current
    DisposableEffect(Unit) {
        val controller = findActivity(view.context)?.window?.let {
            WindowCompat.getInsetsController(it, view)
        }
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            controller?.isAppearanceLightStatusBars = !appDark
            controller?.isAppearanceLightNavigationBars = !appDark
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, granted) {
        if (!granted) return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> runCatching { barcodeView.resume() }
                Lifecycle.Event.ON_PAUSE -> runCatching { barcodeView.pauseAndWait() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { barcodeView.pause() }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            AndroidView(
                factory = {
                    barcodeView.apply {
                        addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(v: android.view.View) {
                                runCatching { resume() }
                            }

                            override fun onViewDetachedFromWindow(v: android.view.View) {
                                runCatching { pause() }
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            ScannerMask(accent = accent)
            TopControls(
                torchOn = torch,
                showTorch = hasFlash,
                onTorch = {
                    torch = !torch
                    runCatching { barcodeView.setTorch(torch) }
                },
                onClose = onClose
            )
        } else {
            PermissionPrompt(
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onClose = onClose
            )
        }

        AnimatedVisibility(
            visible = notice != null,
            enter = fadeIn(tween(160)) + slideInVertically(tween(220)) { it / 3 },
            exit = fadeOut(tween(160)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 72.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.Black.copy(alpha = 0.6f)
            ) {
                Text(
                    text = notice.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun ScannerMask(accent: Color) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val side = minOf(maxWidth, maxHeight) * 0.64f
        val left = (maxWidth - side) / 2
        val top = (maxHeight - side) / 2 - 20.dp
        val inset = 18.dp

        // 遮罩只在尺寸变化时画一次，不跟扫描线一起逐帧重绘。
        Canvas(modifier = Modifier.fillMaxSize()) {
            val l = left.toPx()
            val t = top.toPx()
            val s = side.toPx()
            val radius = 32.dp.toPx()
            val mask = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(0f, 0f, size.width, size.height))
                addRoundRect(RoundRect(l, t, l + s, t + s, CornerRadius(radius)))
            }
            drawPath(mask, Color.Black.copy(alpha = 0.58f))
            drawRoundRect(
                color = Color.White.copy(alpha = 0.16f),
                topLeft = Offset(l, t),
                size = Size(s, s),
                cornerRadius = CornerRadius(radius),
                style = Stroke(width = 1.dp.toPx())
            )

            val bracket = 26.dp.toPx()
            val stroke = 4.dp.toPx()
            fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                drawLine(accent, Offset(x + dx * bracket, y), Offset(x, y), stroke, StrokeCap.Round)
                drawLine(accent, Offset(x, y), Offset(x, y + dy * bracket), stroke, StrokeCap.Round)
            }
            corner(l, t, 1f, 1f)
            corner(l + s, t, -1f, 1f)
            corner(l, t + s, 1f, -1f)
            corner(l + s, t + s, -1f, -1f)
        }

        // 扫描线只改图层位移，不再触发整页重绘。
        val transition = rememberInfiniteTransition(label = "scan")
        val progress = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "scanLine"
        )
        val density = LocalDensity.current
        val travel = with(density) { (side - inset * 2).toPx() }
        Box(
            modifier = Modifier
                .offset(x = left + inset, y = top + inset)
                .size(width = side - inset * 2, height = 2.dp)
                .graphicsLayer { translationY = progress.value * travel }
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, accent, Color.Transparent)
                    )
                )
        )
    }
}

@Composable
private fun TopControls(
    torchOn: Boolean,
    showTorch: Boolean,
    onTorch: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolButton(Icons.Rounded.Close, "关闭", onClose)
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(
                text = "扫描热点二维码",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
        }
        if (showTorch) {
            ToolButton(
                icon = if (torchOn) Icons.Rounded.FlashOff else Icons.Rounded.FlashOn,
                description = "手电筒",
                onClick = onTorch
            )
        } else {
            Spacer(Modifier.size(44.dp))
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun PermissionPrompt(onRequest: () -> Unit, onClose: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            ToolButton(
                icon = Icons.Rounded.Close,
                description = "关闭",
                onClick = onClose
            )
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.NoPhotography,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.size(16.dp))
            Text(
                text = "相机权限",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
            Spacer(Modifier.size(20.dp))
            Button(onClick = onRequest) { Text("授权") }
        }
    }
}

private tailrec fun findActivity(context: Context): Activity? = when (context) {
    is Activity -> context
    is ContextWrapper -> findActivity(context.baseContext)
    else -> null
}

private fun vibrate(context: Context) {
    runCatching {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(android.os.Vibrator::class.java)
        }
        vibrator?.vibrate(
            VibrationEffect.createOneShot(28, VibrationEffect.DEFAULT_AMPLITUDE)
        )
    }
}

// 相机每来一帧都跑一次整帧解码太吃 CPU，低端机上会拖垮界面帧率。
// 只保留二维码格式并按 120ms 的间隔抽样解码，对齐二维码完全够用。
private const val DECODE_INTERVAL_MS = 120L

private object ThrottledQrDecoderFactory : DecoderFactory {
    override fun createDecoder(hints: Map<DecodeHintType, *>): Decoder {
        val reader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }
        return object : Decoder(reader) {
            private var last = 0L

            override fun decode(source: LuminanceSource): Result? {
                val now = SystemClock.elapsedRealtime()
                if (now - last < DECODE_INTERVAL_MS) {
                    throw NotFoundException.getNotFoundInstance()
                }
                last = now
                return super.decode(source)
            }
        }
    }
}
