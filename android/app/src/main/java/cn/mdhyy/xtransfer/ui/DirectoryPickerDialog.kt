// 直接遍历外部存储目录，需要 READ_EXTERNAL_STORAGE（Android 12 及以下）
// 和 MANAGE_EXTERNAL_STORAGE（Android 11+ 全盘访问），否则 listFiles() 可能返回 null。
package cn.mdhyy.xtransfer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 返回一个触发函数：调用时若没有全盘访问权限，先引导去授权，回到前台后再弹目录选择框。
 */
@Composable
fun rememberDirectoryPicker(
    initialPath: String = Environment.getExternalStorageDirectory().absolutePath,
    onSelected: (String) -> Unit
): () -> Unit {
    val context = LocalContext.current
    var visible by remember { mutableStateOf(false) }
    var awaitAccess by remember { mutableStateOf(false) }
    var startPath by remember { mutableStateOf(initialPath) }

    fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) visible = true else context.showError("需要存储权限")
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && awaitAccess) {
                awaitAccess = false
                if (hasAccess()) visible = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (visible) {
        DirectoryPickerDialog(
            initialPath = startPath,
            onDismissRequest = { visible = false },
            onSelected = { path ->
                visible = false
                onSelected(path)
            }
        )
    }

    return {
        startPath = initialPath
        if (hasAccess()) {
            visible = true
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            awaitAccess = true
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }.onFailure {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        } else {
            storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}

data class DirectoryPickerStrings(
    val title: String = "选择文件夹",
    val empty: String = "此文件夹为空",
    val parent: String = "上一级",
    val cancel: String = "取消",
    val select: String = "选择此文件夹",
    val retry: String = "重试",
    val readError: String = "无法读取目录",
)

@Composable
fun DirectoryPickerDialog(
    initialPath: String,
    rootPath: String = "/storage/emulated/0",
    strings: DirectoryPickerStrings = DirectoryPickerStrings(),
    onDismissRequest: () -> Unit,
    onSelected: (String) -> Unit,
) {
    var currentDir by remember { mutableStateOf(File(initialPath)) }
    var subDirs by remember { mutableStateOf<List<File>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableStateOf(0) }

    LaunchedEffect(currentDir, reloadTick) {
        loading = true
        errorText = null
        try {
            subDirs = withContext(Dispatchers.IO) {
                val list = currentDir.listFiles { f: File ->
                    f.isDirectory && !f.name.startsWith(".")
                } ?: throw IllegalStateException("listFiles returned null")
                list.sortedBy { it.name.lowercase() }
            }
        } catch (e: Exception) {
            subDirs = emptyList()
            errorText = strings.readError
        } finally {
            loading = false
        }
    }

    val root = remember(rootPath) { rootPath.trimEnd('/').ifEmpty { "/" } }
    val parent = currentDir.parentFile
    val canGoUp = parent != null &&
        parent.absolutePath != currentDir.absolutePath &&
        (root == "/" || parent.absolutePath == root || parent.absolutePath.startsWith("$root/"))

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 480.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text(
                    strings.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))

                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        currentDir.absolutePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(8.dp))

                Box(Modifier.fillMaxWidth().height(300.dp)) {
                    val err = errorText
                    when {
                        loading -> CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.primary,
                        )

                        err != null -> Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                Icons.Rounded.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(36.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                err,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = { reloadTick++ }) {
                                Text(strings.retry)
                            }
                        }

                        subDirs.isEmpty() -> Text(
                            strings.empty,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )

                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(subDirs, key = { it.absolutePath }) { dir ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { currentDir = dir }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Rounded.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        dir.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Icon(
                                        Icons.Rounded.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(start = 46.dp)
                                        .height(1.dp)
                                        .background(
                                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                        )
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { if (parent != null) currentDir = parent },
                        enabled = canGoUp,
                    ) {
                        Text(strings.parent)
                    }
                    Row {
                        TextButton(onClick = onDismissRequest) {
                            Text(strings.cancel)
                        }
                        TextButton(onClick = { onSelected(currentDir.absolutePath) }) {
                            Text(strings.select)
                        }
                    }
                }
            }
        }
    }
}
