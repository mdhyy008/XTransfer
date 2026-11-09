package cn.mdhyy.xtransfer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import cn.mdhyy.xtransfer.data.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val ALL = "全部"
private val CATEGORY_ORDER = listOf("图片", "视频", "音频", "文档", "PDF", "压缩包", "安装包", "文件", "文件夹")
private val shanghaiZone: ZoneId = ZoneId.of("Asia/Shanghai")

private fun categoryOf(file: File): String =
    if (file.isDirectory) "文件夹" else fileKindLabel(file.name)

// 重命名只改主名，后缀固定不动。
private fun baseNameOf(file: File): String {
    if (file.isDirectory) return file.name
    val dot = file.name.lastIndexOf('.')
    return if (dot <= 0) file.name else file.name.substring(0, dot)
}

private fun extensionSuffixOf(file: File): String {
    if (file.isDirectory) return ""
    val dot = file.name.lastIndexOf('.')
    return if (dot <= 0) "" else file.name.substring(dot)
}

private fun dayKey(ms: Long): LocalDate =
    Instant.ofEpochMilli(ms).atZone(shanghaiZone).toLocalDate()

private fun dayLabel(date: LocalDate): String {
    val today = LocalDate.now(shanghaiZone)
    return when {
        date == today -> "今天"
        date == today.minusDays(1) -> "昨天"
        date.year == today.year -> "${date.monthValue}月${date.dayOfMonth}日"
        else -> "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
    }
}

private fun previewMessage(file: File): MessageEntity = MessageEntity(
    id = file.absolutePath,
    conversationId = "",
    direction = "in",
    kind = if (file.isDirectory) "folder" else "file",
    channel = "dm",
    fileName = file.name,
    fileSize = file.length(),
    localPath = file.absolutePath,
    status = "done",
    createdAt = file.lastModified() / 1000,
    updatedAt = file.lastModified() / 1000
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileManagerScreen(
    recvDir: String,
    refreshKey: Int,
    quickPreview: Boolean,
    onOpenMedia: (String, String) -> Unit
) {
    val context = LocalContext.current
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(ALL) }
    var previewFile by remember { mutableStateOf<File?>(null) }
    var apkFile by remember { mutableStateOf<File?>(null) }
    var menuFile by remember { mutableStateOf<File?>(null) }
    var renameFile by remember { mutableStateOf<File?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<File?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(recvDir, refreshKey, tick) {
        if (tick > 0) delay(200)
        files = withContext(Dispatchers.IO) {
            File(recvDir).listFiles()
                ?.filter { !it.name.startsWith(".") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        }
    }

    // 接收目录被外部改动时主动重扫，改名、删除、解压出来的新文件都能立刻出现。
    DisposableEffect(recvDir) {
        val handler = Handler(Looper.getMainLooper())
        val observer = if (recvDir.isBlank()) {
            null
        } else {
            object : FileObserver(
                recvDir,
                CREATE or DELETE or MOVED_FROM or MOVED_TO or MODIFY or ATTRIB or CLOSE_WRITE
            ) {
                override fun onEvent(event: Int, path: String?) {
                    handler.post { tick++ }
                }
            }
        }
        observer?.startWatching()
        onDispose { observer?.stopWatching() }
    }

    val categories = remember(files) {
        val present = files.map(::categoryOf).toSet()
        listOf(ALL) + CATEGORY_ORDER.filter { it in present }
    }
    val active = if (category in categories) category else ALL
    val visible = remember(files, active, query) {
        val keyword = query.trim()
        files.filter { file ->
            (active == ALL || categoryOf(file) == active) &&
                (keyword.isEmpty() || file.name.contains(keyword, ignoreCase = true))
        }
    }
    val groups = remember(visible) {
        visible.groupBy { dayKey(it.lastModified()) }
    }

    fun open(file: File): Unit {
        val kind = if (file.isDirectory) PreviewKind.NONE else previewKindOf(file.name)
        when {
            file.isDirectory ->
                openFileExternally(context, file.absolutePath, file.name, folder = true)
            !quickPreview ->
                openFileExternally(context, file.absolutePath, file.name)
            kind == PreviewKind.TEXT -> previewFile = file
            kind == PreviewKind.IMAGE || kind == PreviewKind.VIDEO || kind == PreviewKind.AUDIO ->
                onOpenMedia(file.absolutePath, file.name)
            file.name.endsWith(".apk", ignoreCase = true) -> apkFile = file
            else -> openFileExternally(context, file.absolutePath, file.name)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Text(
            text = "文件管理",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 10.dp)
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            categories.forEach { name ->
                FilterChip(
                    selected = name == active,
                    onClick = { category = name },
                    label = { Text(name) }
                )
            }
        }
        if (groups.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                EmptyState(icon = Icons.Rounded.Inbox, text = "暂无文件")
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                groups.forEach { (date, list) ->
                    stickyHeader(key = date.toString()) {
                        Text(
                            text = dayLabel(date),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background)
                                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 4.dp)
                        )
                    }
                    items(list, key = { it.absolutePath }) { file ->
                        FileManagerRow(
                            file = file,
                            onOpen = { open(file) },
                            onLongPress = { menuFile = file },
                            menuOpen = menuFile?.absolutePath == file.absolutePath,
                            onMenuDismiss = { menuFile = null },
                            onRename = {
                                menuFile = null
                                renameDraft = baseNameOf(file)
                                renameFile = file
                            },
                            onDelete = {
                                menuFile = null
                                deleteTarget = file
                            }
                        )
                    }
                }
            }
        }
    }

    apkFile?.let { file ->
        ApkPreviewDialog(
            path = file.absolutePath,
            name = file.name,
            onDismiss = { apkFile = null }
        )
    }

    previewFile?.let { file ->
        FilePreviewDialog(
            message = previewMessage(file),
            onDismiss = { previewFile = null },
            onOpenExternal = {
                previewFile = null
                openFileExternally(context, file.absolutePath, file.name)
            }
        )
    }

    val renaming = renameFile
    if (renaming != null) {
        AlertDialog(
            onDismissRequest = { renameFile = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    singleLine = true,
                    suffix = {
                        val suffix = extensionSuffixOf(renaming)
                        if (suffix.isNotEmpty()) Text(suffix)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = renameDraft.trim() + extensionSuffixOf(renaming)
                        if (renameDraft.trim().isNotEmpty()) {
                            val dest = File(renaming.parentFile, name)
                            val ok = dest != renaming && !dest.exists() &&
                                renaming.renameTo(dest)
                            if (!ok) context.showError("重命名失败")
                        }
                        renameFile = null
                        tick++
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameFile = null }) { Text("取消") }
            }
        )
    }

    val deleting = deleteTarget
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除") },
            text = { Text(deleting.name) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!deleting.deleteRecursively()) context.showError("删除失败")
                        deleteTarget = null
                        tick++
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileManagerRow(
    file: File,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    menuOpen: Boolean,
    onMenuDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val isApk = !file.isDirectory && file.name.endsWith(".apk", ignoreCase = true)
    val thumb = if (file.isDirectory) null else rememberUriThumbnail(file.absolutePath, file.name)
    val apkMeta = if (isApk) rememberApkMeta(file.absolutePath, file.lastModified()) else null
    val image = thumb ?: apkMeta?.icon
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = if (file.isDirectory) Icons.Rounded.Folder else fileTypeIcon(file.name),
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(colors.surfaceContainerHighest)
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = categoryOf(file),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                    val version = apkMeta?.version
                    if (!version.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(colors.surfaceContainerHighest)
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = version,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                    if (!file.isDirectory) {
                        val size = formatSize(file.length())
                        if (size.isNotEmpty()) {
                            Text(
                                text = size,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        if (menuOpen) {
            ModalBottomSheet(
                onDismissRequest = onMenuDismiss,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = 8.dp)
                ) {
                    FileMenuRow(Icons.Rounded.OpenInNew, "打开") {
                        onMenuDismiss()
                        onOpen()
                    }
                    FileMenuDivider()
                    FileMenuRow(Icons.Rounded.ContentCopy, "复制路径") {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("path", file.absolutePath))
                        context.toast("已复制路径")
                        onMenuDismiss()
                    }
                    FileMenuDivider()
                    FileMenuRow(Icons.Rounded.DriveFileRenameOutline, "重命名") {
                        onMenuDismiss()
                        onRename()
                    }
                    FileMenuDivider()
                    FileMenuRow(Icons.Rounded.Delete, "删除", colors.error) {
                        onMenuDismiss()
                        onDelete()
                    }
                }
            }
        }
    }
}

@Composable
private fun FileMenuRow(
    icon: ImageVector,
    label: String,
    tint: Color = Color.Unspecified,
    onClick: () -> Unit
) {
    val custom = tint != Color.Unspecified
    DropdownMenuItem(
        contentPadding = PaddingValues(start = 24.dp, end = 16.dp),
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (custom) tint else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        text = {
            Text(
                text = label,
                fontSize = 16.sp,
                color = if (custom) tint else MaterialTheme.colorScheme.onSurface
            )
        },
        onClick = onClick
    )
}

@Composable
private fun FileMenuDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    )
}

private data class ApkMeta(val icon: ImageBitmap?, val version: String?)@Composable
private fun rememberApkMeta(path: String, mtime: Long): ApkMeta {
    val context = LocalContext.current
    var meta by remember(path, mtime) { mutableStateOf(ApkMeta(null, null)) }
    LaunchedEffect(path, mtime) {
        meta = withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                val info = pm.getPackageArchiveInfo(path, 0)
                    ?: return@runCatching ApkMeta(null, null)
                info.applicationInfo?.apply {
                    sourceDir = path
                    publicSourceDir = path
                }
                val icon = info.applicationInfo?.let { application ->
                    runCatching { pm.getApplicationIcon(application).toBitmap() }.getOrNull()
                }?.asImageBitmap()
                ApkMeta(icon, info.versionName)
            }.getOrDefault(ApkMeta(null, null))
        }
    }
    return meta
}
