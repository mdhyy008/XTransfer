package cn.mdhyy.xtransfer.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.provider.Settings
import android.util.LruCache
import android.webkit.MimeTypeMap
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image as ImageIcon
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import cn.mdhyy.xtransfer.data.MediaPublisher
import cn.mdhyy.xtransfer.data.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

enum class PreviewKind { TEXT, AUDIO, VIDEO, IMAGE, NONE }

private val TEXT_EXTS = setOf(
    "txt", "md", "markdown", "log", "json", "csv", "tsv", "xml", "yaml", "yml", "ini",
    "conf", "toml", "js", "mjs", "cjs", "ts", "tsx", "jsx", "html", "htm", "css", "scss",
    "less", "py", "java", "kt", "kts", "go", "c", "cpp", "cc", "h", "hpp", "cs", "rs",
    "rb", "php", "swift", "lua", "sh", "bash", "zsh", "bat", "cmd", "ps1", "sql",
    "gradle", "properties", "env"
)
private val AUDIO_EXTS = setOf("mp3", "wav", "m4a", "aac", "flac", "ogg", "oga", "opus", "wma")
private val VIDEO_EXTS = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "wmv", "flv", "ts", "mpg", "mpeg", "3gp")
private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")

fun extensionOf(name: String): String =
    name.substringAfterLast('.', "").lowercase()

fun previewKindOf(name: String): PreviewKind {
    return when (extensionOf(name)) {
        in TEXT_EXTS -> PreviewKind.TEXT
        in AUDIO_EXTS -> PreviewKind.AUDIO
        in VIDEO_EXTS -> PreviewKind.VIDEO
        in IMAGE_EXTS -> PreviewKind.IMAGE
        else -> PreviewKind.NONE
    }
}

fun isImageName(name: String): Boolean = extensionOf(name) in IMAGE_EXTS

fun mimeOfFile(name: String): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(extensionOf(name)) ?: "*/*"

fun uriFor(context: Context, path: String): Uri? {
    val file = if (path.startsWith("content://")) {
        MediaPublisher.realFile(context, Uri.parse(path))
    } else {
        File(path).takeIf { it.exists() }
    }
    if (file == null) {
        return if (path.startsWith("content://")) Uri.parse(path) else null
    }
    return runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
}

fun openFileExternally(context: Context, path: String, name: String?, folder: Boolean = false) {
    val label = name ?: File(path).name
    val apk = !folder && label.endsWith(".apk", ignoreCase = true)
    if (apk && !context.packageManager.canRequestPackageInstalls()) {
        val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        runCatching { context.startActivity(settings) }
            .onFailure { context.showError("请到系统设置中允许安装应用") }
        return
    }
    val uri = uriFor(context, path)
    if (uri == null) {
        context.showError("文件不存在")
        return
    }
    val mime = when {
        folder -> "resource/folder"
        apk -> "application/vnd.android.package-archive"
        label.isEmpty() || File(label).extension.isEmpty() -> "*/*"
        else -> mimeOfFile(label)
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { context.showError("没有可打开该文件的应用") }
}

private fun sampleSize(width: Int, height: Int, maxSize: Int): Int {
    var sample = 1
    while (width / sample > maxSize || height / sample > maxSize) {
        sample *= 2
    }
    return sample
}

suspend fun loadMediaImage(
    context: Context,
    path: String,
    maxSize: Int = 2400
): android.graphics.Bitmap? =
    if (path.startsWith("content://")) {
        loadContentPreview(context, Uri.parse(path), maxSize)
    } else {
        loadImagePreview(path, maxSize)
    }

suspend fun loadImagePreview(path: String, maxSize: Int = 1600): android.graphics.Bitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSize)
                }
            )
        }.getOrNull()
    }

suspend fun loadContentPreview(
    context: Context,
    uri: Uri,
    maxSize: Int
): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSize)
        }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }.getOrNull()
}

private fun scaleDown(bitmap: android.graphics.Bitmap, maxSize: Int): android.graphics.Bitmap {
    val longest = maxOf(bitmap.width, bitmap.height)
    if (longest <= maxSize) return bitmap
    val ratio = maxSize.toFloat() / longest
    return android.graphics.Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * ratio).toInt().coerceAtLeast(1),
        (bitmap.height * ratio).toInt().coerceAtLeast(1),
        true
    )
}

private suspend fun loadVideoFrame(
    context: Context,
    path: String,
    maxSize: Int
): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            if (path.startsWith("content://")) {
                retriever.setDataSource(context, Uri.parse(path))
            } else {
                retriever.setDataSource(path)
            }
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?.let { scaleDown(it, maxSize) }
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrNull()
}

private val thumbMemory = object : LruCache<String, android.graphics.Bitmap>(32 * 1024 * 1024) {
    override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.byteCount
}

// 内存 LRU + cacheDir 磁盘缓存，避免每次进聊天页都重新解码图片、重新抽视频首帧。
private suspend fun loadThumbnail(
    context: Context,
    path: String,
    name: String,
    fileSize: Long?,
    maxSize: Int
): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
    // 版本号进 key：调整缩略图尺寸后旧缓存自动失效。
    val key = "v2|$path|${fileSize ?: 0L}"
    thumbMemory.get(key)?.let { return@withContext it }
    val ext = extensionOf(name)
    val isVideo = ext in VIDEO_EXTS
    if (ext !in IMAGE_EXTS && !isVideo) return@withContext null
    val cacheDir = File(context.cacheDir, "thumbs")
    val cacheFile = File(cacheDir, Integer.toHexString(key.hashCode()) + if (isVideo) ".jpg" else ".png")
    val fromDisk = runCatching {
        if (cacheFile.length() > 0) BitmapFactory.decodeFile(cacheFile.absolutePath) else null
    }.getOrNull()
    if (fromDisk != null) {
        thumbMemory.put(key, fromDisk)
        return@withContext fromDisk
    }
    val bitmap = (
        if (isVideo) loadVideoFrame(context, path, maxSize)
        else if (path.startsWith("content://")) loadContentPreview(context, Uri.parse(path), maxSize)
        else loadImagePreview(path, maxSize)
    ) ?: return@withContext null
    thumbMemory.put(key, bitmap)
    runCatching {
        cacheDir.mkdirs()
        cacheFile.outputStream().use {
            bitmap.compress(
                if (isVideo) android.graphics.Bitmap.CompressFormat.JPEG
                else android.graphics.Bitmap.CompressFormat.PNG,
                if (isVideo) 78 else 100,
                it
            )
        }
    }
    bitmap
}

// 暂存桶里的候选文件还没落到收件目录，按 uri + 文件名直接抽缩略图。
@Composable
fun rememberUriThumbnail(uri: String, name: String, maxSize: Int = 96): ImageBitmap? {
    val context = LocalContext.current
    var bitmap by remember(uri, name) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri, name) {
        bitmap = loadThumbnail(context, uri, name, null, maxSize)?.asImageBitmap()
    }
    return bitmap
}

@Composable
fun rememberFileThumbnail(message: MessageEntity, maxSize: Int = 192): ImageBitmap? {
    val context = LocalContext.current
    val path = message.localPath
    val name = message.fileName ?: path ?: ""
    var bitmap by remember(path, name) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path, name) {
        if (path.isNullOrBlank() || message.kind == "folder") {
            bitmap = null
            return@LaunchedEffect
        }
        bitmap = loadThumbnail(context, path, name, message.fileSize, maxSize)?.asImageBitmap()
    }
    return bitmap
}

fun fileKindLabel(name: String): String = when (extensionOf(name)) {
    in IMAGE_EXTS -> "图片"
    in AUDIO_EXTS -> "音频"
    in VIDEO_EXTS -> "视频"
    in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz") -> "压缩包"
    "pdf" -> "PDF"
    "apk" -> "安装包"
    in setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "rtf", "csv") -> "文档"
    else -> "文件"
}

fun fileTypeIcon(name: String): androidx.compose.ui.graphics.vector.ImageVector = when {
    isImageName(name) -> Icons.Rounded.ImageIcon
    extensionOf(name) in AUDIO_EXTS -> Icons.Rounded.Audiotrack
    extensionOf(name) in VIDEO_EXTS -> Icons.Rounded.Movie
    extensionOf(name) in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz") -> Icons.Rounded.FolderZip
    extensionOf(name) == "pdf" -> Icons.Rounded.PictureAsPdf
    extensionOf(name) == "apk" -> Icons.Rounded.Android
    extensionOf(name) in setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "rtf", "csv") ->
        Icons.Rounded.Description
    else -> Icons.Rounded.InsertDriveFile
}

@Composable
fun FilePreviewDialog(
    message: MessageEntity,
    onDismiss: () -> Unit,
    onOpenExternal: () -> Unit
) {
    val context = LocalContext.current
    val path = message.localPath.orEmpty()
    val name = message.fileName ?: File(path).name
    val kind = previewKindOf(name)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "关闭")
                    }
                }
                when (kind) {
                    PreviewKind.TEXT -> TextPreview(context, path)
                    PreviewKind.AUDIO -> AudioPreview(context, path, name)
                    PreviewKind.VIDEO -> VideoPreview(context, path)
                    PreviewKind.IMAGE -> ImagePreview(context, message)
                    PreviewKind.NONE -> UnsupportedPreview(onOpenExternal)
                }
            }
        }
    }
}

@Composable
private fun TextPreview(context: Context, path: String) {
    var content by remember(path) { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        content = withContext(Dispatchers.IO) {
            runCatching {
                val text = if (path.startsWith("content://")) {
                    context.contentResolver.openInputStream(Uri.parse(path))
                        ?.bufferedReader()?.use { it.readText() }
                } else {
                    File(path).bufferedReader().use { it.readText() }
                }.orEmpty()
                if (text.length > 400_000) text.take(400_000) + "\n\n（已截断）" else text
            }.getOrElse { "无法读取文件" }
        }
    }
    val text = content
    if (text == null) {
        Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
            CircularProgress()
        }
        return
    }
    SelectionContainer {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                lineHeight = 20.sp
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
        )
    }
}

@Composable
private fun CircularProgress() {
    androidx.compose.material3.CircularProgressIndicator(
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(28.dp)
    )
}

@Composable
private fun AudioPreview(context: Context, path: String, name: String) {
    val uri = remember(path) { uriFor(context, path) }
    var ready by remember(path) { mutableStateOf(false) }
    var playing by remember(path) { mutableStateOf(false) }
    var duration by remember(path) { mutableIntStateOf(0) }
    var position by remember(path) { mutableIntStateOf(0) }
    val player = remember(path) { MediaPlayer() }

    DisposableEffect(uri) {
        if (uri != null) {
            runCatching {
                player.setDataSource(context, uri)
                player.setOnPreparedListener {
                    duration = it.duration.coerceAtLeast(0)
                    ready = true
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

    LaunchedEffect(ready, playing) {
        while (ready && playing) {
            position = runCatching { player.currentPosition }.getOrDefault(0)
            delay(300)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Spacer(Modifier.height(24.dp))
        IconButton(
            onClick = {
                if (!ready) return@IconButton
                if (playing) {
                    runCatching { player.pause() }
                    playing = false
                } else {
                    runCatching {
                        if (player.currentPosition >= duration) player.seekTo(0)
                        player.start()
                    }
                    playing = true
                }
            },
            modifier = Modifier
                .size(64.dp)
                .background(MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(50))
        ) {
            Icon(
                imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (playing) "暂停" else "播放",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(Modifier.height(24.dp))
        LinearProgressIndicator(
            progress = {
                if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            },
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .height(4.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(0.72f)) {
            Text(
                text = formatDuration(position),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = formatDuration(duration),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

fun formatDuration(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val minutes = total / 60
    val seconds = total % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
private fun VideoPreview(context: Context, path: String) {
    val uri = remember(path) { uriFor(context, path) }
    if (uri == null) {
        UnsupportedPreview {}
        return
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            .aspectRatio(16f / 9f)
            .background(androidx.compose.ui.graphics.Color.Black, RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                    setVideoURI(uri)
                    setOnPreparedListener { it.start() }
                }
            },
            onRelease = { it.stopPlayback() },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun ImagePreview(context: Context, message: MessageEntity) {
    val path = message.localPath.orEmpty()
    var bitmap by remember(path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = if (path.startsWith("content://")) {
            loadContentPreview(context, Uri.parse(path), 1600)
        } else {
            loadImagePreview(path)
        }
    }
    val image = bitmap
    if (image == null) {
        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
            CircularProgress()
        }
        return
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        val ratio = image.width.toFloat() / image.height.coerceAtLeast(1)
        val maxHeight = 440.dp
        var width = maxWidth
        var height = maxWidth / ratio
        if (height > maxHeight) {
            height = maxHeight
            width = maxHeight * ratio
        }
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = message.fileName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(width, height)
                .clip(RoundedCornerShape(18.dp))
        )
    }
}

@Composable
private fun UnsupportedPreview(onOpenExternal: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "无法预览",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        androidx.compose.material3.Button(onClick = onOpenExternal) { Text("使用系统应用打开") }
    }
}
