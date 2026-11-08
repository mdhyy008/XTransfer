package cn.mdhyy.xtransfer.ui

import android.media.MediaPlayer
import android.widget.VideoView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cn.mdhyy.xtransfer.data.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun MediaScreen(message: MessageEntity?, onBack: () -> Unit) {
    val path = message?.localPath.orEmpty()
    val name = message?.fileName ?: path.substringAfterLast('/')
    val kind = message?.let { previewKindOf(it.fileName ?: it.localPath ?: "") }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when (kind) {
            PreviewKind.IMAGE -> MediaImage(path)
            PreviewKind.VIDEO -> MediaVideo(path)
            PreviewKind.AUDIO -> MediaAudio(path, name)
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "无法打开",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White
                )
            }
            Text(
                text = name,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            )
        }
    }
}

@Composable
private fun MediaImage(path: String) {
    val context = LocalContext.current
    var bitmap by remember(path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) { loadMediaImage(context, path) }
    }
    val image = bitmap
    if (image == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator(color = Color.White)
        }
        return
    }
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val viewportW = with(density) { maxWidth.toPx() }
        val viewportH = with(density) { maxHeight.toPx() }
        val transformState = rememberTransformableState { zoomChange, panChange, _ ->
            val nextScale = (scale * zoomChange).coerceIn(1f, 5f)
            val maxX = viewportW * (nextScale - 1f) / 2f
            val maxY = viewportH * (nextScale - 1f) / 2f
            scale = nextScale
            offset = Offset(
                (offset.x + panChange.x).coerceIn(-maxX, maxX),
                (offset.y + panChange.y).coerceIn(-maxY, maxY)
            )
        }
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
                .transformable(transformState)
        )
    }
}

@Composable
private fun MediaVideo(path: String) {
    val context = LocalContext.current
    val uri = remember(path) { uriFor(context, path) }
    var playing by remember(path) { mutableStateOf(false) }
    var position by remember(path) { mutableIntStateOf(0) }
    var duration by remember(path) { mutableIntStateOf(0) }
    var muted by remember(path) { mutableStateOf(true) }
    var player by remember(path) { mutableStateOf<MediaPlayer?>(null) }
    var video by remember(path) { mutableStateOf<VideoView?>(null) }

    if (uri == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("文件不存在", color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    DisposableEffect(uri) {
        onDispose {
            runCatching { video?.stopPlayback() }
            player = null
        }
    }

    LaunchedEffect(playing) {
        while (playing) {
            position = runCatching { video?.currentPosition ?: 0 }.getOrDefault(0)
            delay(250)
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    setVideoURI(uri)
                    setOnPreparedListener { mp ->
                        player = mp
                        mp.setVolume(0f, 0f)
                        duration = mp.duration.coerceAtLeast(0)
                        mp.isLooping = false
                        start()
                        playing = true
                    }
                    setOnCompletionListener {
                        playing = false
                        position = duration
                    }
                    video = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(76.dp)
                .background(Color.Black.copy(alpha = 0.32f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            PlayPauseButton(
                playing = playing,
                onClick = {
                    val view = video
                    if (view != null) {
                        if (playing) {
                            view.pause()
                            playing = false
                        } else {
                            if (position >= duration) {
                                view.seekTo(0)
                                position = 0
                            }
                            view.start()
                            playing = true
                        }
                    }
                }
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 12.dp)
        ) {
            PlaybackBar(
                positionMs = position,
                durationMs = duration,
                onSeek = { fraction ->
                    val view = video
                    if (view != null) {
                        val target = (duration * fraction).toInt()
                        view.seekTo(target)
                        position = target
                    }
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = formatDuration(position),
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = formatDuration(duration),
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = {
                        muted = !muted
                        player?.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
                    }
                ) {
                    Icon(
                        imageVector = if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                        contentDescription = if (muted) "取消静音" else "静音",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaAudio(path: String, name: String) {
    val context = LocalContext.current
    val uri = remember(path) { uriFor(context, path) }
    var playing by remember(path) { mutableStateOf(false) }
    var position by remember(path) { mutableIntStateOf(0) }
    var duration by remember(path) { mutableIntStateOf(0) }
    val player = remember(path) { MediaPlayer() }

    DisposableEffect(uri) {
        if (uri != null) {
            runCatching {
                player.setDataSource(context, uri)
                player.setOnPreparedListener {
                    duration = it.duration.coerceAtLeast(0)
                    it.start()
                    playing = true
                }
                player.setOnCompletionListener {
                    playing = false
                    position = duration
                }
                player.prepareAsync()
            }
        }
        onDispose { runCatching { player.release() } }
    }

    LaunchedEffect(playing) {
        while (playing) {
            position = runCatching { player.currentPosition }.getOrDefault(0)
            delay(250)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = name,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(36.dp))
        PlayPauseButton(
            playing = playing,
            size = 80.dp,
            onClick = {
                if (playing) {
                    runCatching { player.pause() }
                    playing = false
                } else {
                    runCatching {
                        if (position >= duration) player.seekTo(0)
                        player.start()
                    }
                    playing = true
                }
            }
        )
        Spacer(Modifier.height(36.dp))
        PlaybackBar(
            positionMs = position,
            durationMs = duration,
            onSeek = { fraction ->
                val target = (duration * fraction).toInt()
                runCatching { player.seekTo(target) }
                position = target
            }
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = formatDuration(position),
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatDuration(duration),
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun PlayPauseButton(
    playing: Boolean,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 64.dp
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, label = "playScale")
    Canvas(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        drawCircle(Color.White.copy(alpha = 0.96f))
        val iconColor = Color(0xFF101014)
        if (playing) {
            val barWidth = this.size.width * 0.11f
            val barHeight = this.size.height * 0.38f
            val gap = this.size.width * 0.11f
            val top = (this.size.height - barHeight) / 2f
            val radius = CornerRadius(barWidth / 2f)
            drawRoundRect(
                color = iconColor,
                topLeft = Offset(this.size.width / 2f - gap - barWidth / 2f, top),
                size = Size(barWidth, barHeight),
                cornerRadius = radius
            )
            drawRoundRect(
                color = iconColor,
                topLeft = Offset(this.size.width / 2f + gap - barWidth / 2f, top),
                size = Size(barWidth, barHeight),
                cornerRadius = radius
            )
        } else {
            val h = this.size.height * 0.42f
            val w = this.size.width * 0.36f
            val left = this.size.width / 2f - w / 2f + this.size.width * 0.04f
            val top = (this.size.height - h) / 2f
            val path = Path().apply {
                moveTo(left, top)
                lineTo(left + w, top + h / 2f)
                lineTo(left, top + h)
                close()
            }
            drawPath(path, iconColor)
        }
    }
}

@Composable
private fun PlaybackBar(
    positionMs: Int,
    durationMs: Int,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val fraction = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(26.dp)
            .pointerInput(durationMs) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (size.width > 0 && durationMs > 0) {
                        onSeek((down.position.x / size.width).coerceIn(0f, 1f))
                        drag(down.id) { change ->
                            onSeek((change.position.x / size.width).coerceIn(0f, 1f))
                            change.consume()
                        }
                    }
                }
            }
    ) {
        val trackHeight = 4.dp.toPx()
        val centerY = size.height / 2f
        val radius = CornerRadius(trackHeight / 2f)
        drawRoundRect(
            color = Color.White.copy(alpha = 0.28f),
            topLeft = Offset(0f, centerY - trackHeight / 2f),
            size = Size(size.width, trackHeight),
            cornerRadius = radius
        )
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(0f, centerY - trackHeight / 2f),
            size = Size(size.width * fraction, trackHeight),
            cornerRadius = radius
        )
        val thumbRadius = 6.dp.toPx()
        val thumbX = (size.width * fraction)
            .coerceIn(thumbRadius, (size.width - thumbRadius).coerceAtLeast(thumbRadius))
        drawCircle(
            color = Color.White,
            radius = thumbRadius,
            center = Offset(thumbX, centerY)
        )
    }
}
