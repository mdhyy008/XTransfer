package cn.mdhyy.xtransfer.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import cn.mdhyy.xtransfer.data.MediaPublisher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private data class ApkInfo(
    val label: String,
    val packageName: String,
    val version: String,
    val size: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val icon: ImageBitmap?
)

private fun apkFileOf(context: Context, path: String): File? {
    val file = if (path.startsWith("content://")) {
        MediaPublisher.realFile(context, Uri.parse(path))
    } else {
        File(path)
    }
    return file?.takeIf { it.exists() && it.canRead() }
}

private fun readApkInfo(context: Context, path: String): ApkInfo? = runCatching {
    val file = apkFileOf(context, path) ?: return@runCatching null
    val pm = context.packageManager
    val info = pm.getPackageArchiveInfo(
        file.absolutePath,
        PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA
    ) ?: return@runCatching null
    val app = info.applicationInfo ?: return@runCatching null
    app.sourceDir = file.absolutePath
    app.publicSourceDir = file.absolutePath
    val label = runCatching { pm.getApplicationLabel(app).toString() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: file.name.removeSuffix(".apk")
    val icon = runCatching { pm.getApplicationIcon(app).toBitmap() }
        .getOrNull()
        ?.asImageBitmap()
    val versionCode =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()
    val version = info.versionName?.takeIf { it.isNotBlank() }
        ?.let { "$it ($versionCode)" }
        ?: versionCode.toString()
    ApkInfo(
        label = label,
        packageName = info.packageName.orEmpty(),
        version = version,
        size = file.length(),
        minSdk = app.minSdkVersion,
        targetSdk = app.targetSdkVersion,
        icon = icon
    )
}.getOrNull()

private fun androidVersionLabel(sdk: Int): String = when {
    sdk >= 36 -> "16"
    sdk >= 35 -> "15"
    sdk >= 34 -> "14"
    sdk >= 33 -> "13"
    sdk >= 32 -> "12L"
    sdk >= 31 -> "12"
    sdk >= 30 -> "11"
    sdk >= 29 -> "10"
    sdk >= 28 -> "9"
    sdk >= 27 -> "8.1"
    sdk >= 26 -> "8.0"
    sdk >= 25 -> "7.1"
    sdk >= 24 -> "7.0"
    sdk >= 23 -> "6.0"
    sdk >= 21 -> "5.0"
    else -> "API $sdk"
}

fun installApk(context: Context, path: String) {
    val file = apkFileOf(context, path)
    if (file == null) {
        context.showError("安装包不存在或无法读取")
        return
    }
    val pm = context.packageManager
    if (pm.getPackageArchiveInfo(file.absolutePath, 0) == null) {
        context.showError("安装包已损坏")
        return
    }
    // 每次安装都重新检查，用户可能随时在系统设置里关掉这个授权。
    if (!pm.canRequestPackageInstalls()) {
        context.showError("请允许安装未知来源应用")
        val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        runCatching { context.startActivity(settings) }
            .onFailure { context.showError("请到系统设置中允许安装应用") }
        return
    }
    val uri = uriFor(context, file.absolutePath)
    if (uri == null) {
        context.showError("安装包不存在或无法读取")
        return
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { context.showError("没有可用的安装程序") }
}

@Composable
fun ApkPreviewDialog(path: String, name: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var info by remember(path) { mutableStateOf<ApkInfo?>(null) }
    var loading by remember(path) { mutableStateOf(true) }

    LaunchedEffect(path) {
        info = withContext(Dispatchers.IO) { readApkInfo(context, path) }
        loading = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val icon = info?.icon
                    if (icon != null) {
                        Image(
                            bitmap = icon,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(16.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Android,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = info?.label ?: name.removeSuffix(".apk"),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = info?.packageName ?: "正在读取…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                val loaded = info
                if (loaded == null && !loading) {
                    Text(
                        text = "无法读取安装包",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (loaded != null) {
                    ApkInfoRow("版本", loaded.version)
                    ApkInfoRow("大小", formatSize(loaded.size))
                    ApkInfoRow("系统要求", "Android ${androidVersionLabel(loaded.minSdk)} 及以上")
                    ApkInfoRow("目标版本", "Android ${androidVersionLabel(loaded.targetSdk)}")
                }
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            installApk(context, path)
                            onDismiss()
                        },
                        enabled = loaded != null
                    ) {
                        Text("安装")
                    }
                }
            }
        }
    }
}

@Composable
private fun ApkInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
