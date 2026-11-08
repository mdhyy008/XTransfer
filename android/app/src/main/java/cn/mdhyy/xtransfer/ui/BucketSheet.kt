package cn.mdhyy.xtransfer.ui

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import cn.mdhyy.xtransfer.data.BucketItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// 列表固定显示五行，删文件时弹窗高度不跟着变。
private val BUCKET_ROW_HEIGHT = 56.dp
private const val BUCKET_VISIBLE_ROWS = 5
private val BUCKET_ICON_SIZE = 24.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Suppress("DEPRECATION")
@Composable
fun BucketSheet(
    items: List<BucketItem>,
    failed: Set<String>,
    onDismiss: () -> Unit,
    onAddFiles: () -> Unit,
    onAddFolder: () -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 列表滚到头之后剩下的位移全吃掉，弹窗只认小横条和点外部，不再跟着内容滑。
    val blockSheetDrag = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset = available
        }
    }
    val missing by produceState<Set<String>>(emptySet(), items) {
        value = withContext(Dispatchers.IO) {
            items.filter { !exists(context, it) }.map { it.uri }.toSet()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        // 关掉列表的拉伸回弹，上下滑动时不出现阻尼效果。
        CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .nestedScroll(blockSheetDrag)
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "暂存桶",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (items.isNotEmpty()) {
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = "清空",
                            modifier = Modifier.size(BUCKET_ICON_SIZE)
                        )
                    }
                }
                IconButton(onClick = onAddFiles) {
                    Icon(
                        Icons.Rounded.NoteAdd,
                        contentDescription = "添加文件",
                        modifier = Modifier.size(BUCKET_ICON_SIZE)
                    )
                }
                IconButton(onClick = onAddFolder) {
                    Icon(
                        Icons.Rounded.CreateNewFolder,
                        contentDescription = "添加文件夹",
                        modifier = Modifier.size(BUCKET_ICON_SIZE)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            val listHeight = BUCKET_ROW_HEIGHT * BUCKET_VISIBLE_ROWS
            if (items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(listHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Inventory2,
                        contentDescription = "暂存桶为空",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.size(72.dp)
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.height(listHeight)) {
                    items(items, key = { it.uri }) { item ->
                        BucketRow(
                            item = item,
                            failed = item.uri in failed,
                            missing = item.uri in missing,
                            onRemove = { onRemove(item.uri) }
                        )
                    }
                }
            }
        }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空暂存桶") },
            text = { Text("确定要清空暂存桶里的 ${items.size} 项吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClear()
                    }
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun BucketRow(
    item: BucketItem,
    failed: Boolean,
    missing: Boolean,
    onRemove: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val error = failed || missing
    val thumb = if (item.kind == "folder" || error) null else rememberUriThumbnail(item.uri, item.name)
    val size by produceState<Long?>(
        initialValue = if (item.kind == "folder") null else item.size,
        item.uri,
        item.kind,
        item.exists
    ) {
        value = if (item.kind == "folder") {
            if (!item.exists) null
            else withContext(Dispatchers.IO) { folderSizeOf(context, item) }
        } else {
            item.size
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(BUCKET_ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = if (item.kind == "folder") Icons.Rounded.Folder else Icons.Rounded.Description,
                    contentDescription = null,
                    tint = if (error) colors.error else colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (error) colors.error else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val typeLabel = if (item.kind == "folder") "文件夹" else fileKindLabel(item.name)
            val sub = when {
                failed -> "发送失败"
                missing -> "不可用"
                size != null -> formatSize(size ?: 0L)
                else -> ""
            }
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
                        text = typeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (error) colors.error else colors.onSurfaceVariant
                    )
                }
                if (sub.isNotEmpty()) {
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (error) colors.error else colors.onSurfaceVariant
                    )
                }
            }
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "移除",
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private fun folderSizeOf(context: android.content.Context, item: BucketItem): Long = runCatching {
    if (item.uri.startsWith("/")) {
        File(item.uri).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } else {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(item.uri)) ?: return@runCatching 0L
        var total = 0L
        val stack = ArrayDeque<DocumentFile>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            for (child in dir.listFiles()) {
                if (child.isDirectory) stack.add(child) else total += child.length()
            }
        }
        total
    }
}.getOrDefault(0L)

private fun exists(context: android.content.Context, item: BucketItem): Boolean = runCatching {
    if (item.uri.startsWith("/")) {
        val file = File(item.uri)
        if (item.kind == "folder") file.isDirectory else file.isFile
    } else {
        val uri = Uri.parse(item.uri)
        if (item.kind == "folder") {
            DocumentFile.fromTreeUri(context, uri)?.exists() == true
        } else {
            DocumentFile.fromSingleUri(context, uri)?.exists() == true
        }
    }
}.getOrDefault(false)
