package cn.mdhyy.xtransfer.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import cn.mdhyy.xtransfer.data.MessageEntity
import cn.mdhyy.xtransfer.data.StorageAccess
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

private val shanghai: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

fun formatSize(bytes: Long?): String {
    if (bytes == null || bytes <= 0) return ""
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024
        unit += 1
    }
    val text = if (value >= 10 || unit == 0) value.roundToInt().toString() else String.format(Locale.CHINA, "%.1f", value)
    return "$text ${units[unit]}"
}

fun formatSpeed(bytesPerSec: Long): String =
    if (bytesPerSec <= 0) "" else formatSize(bytesPerSec) + "/s"

fun formatTime(sec: Long, withDate: Boolean = false): String {
    if (sec <= 0) return ""
    val pattern = if (withDate) "MM-dd HH:mm" else "HH:mm"
    return SimpleDateFormat(pattern, Locale.CHINA).apply { timeZone = shanghai }.format(Date(sec * 1000))
}

fun formatWhen(sec: Long): String {
    if (sec <= 0) return ""
    val now = System.currentTimeMillis() / 1000
    val pattern = if (now - sec < 24 * 3600) "HH:mm" else "MM-dd"
    return SimpleDateFormat(pattern, Locale.CHINA).apply { timeZone = shanghai }.format(Date(sec * 1000))
}

fun previewOf(message: MessageEntity?): String {
    if (message == null) return ""
    val prefix = if (message.direction == "out") "我：" else ""
    return if (message.kind == "text") {
        prefix + (message.body ?: "")
    } else if (message.kind == "folder") {
        "${prefix}[文件夹] ${message.fileName ?: ""}"
    } else {
        "${prefix}[文件] ${message.fileName ?: ""}"
    }
}

fun Context.toast(text: String) {
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}

/** 报错统一走全屏弹窗，带复制按钮；成功提示仍用 toast。 */
object ErrorDialogState {
    val message = MutableStateFlow<String?>(null)

    fun show(text: String) {
        if (text.isNotBlank()) message.value = text
    }

    fun dismiss() {
        message.value = null
    }
}

fun Context.showError(text: String) {
    ErrorDialogState.show(text)
}

@Composable
fun ErrorDialogHost() {
    val message by ErrorDialogState.message.collectAsStateWithLifecycle()
    val text = message ?: return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { ErrorDialogState.dismiss() },
        confirmButton = {
            TextButton(onClick = {
                runCatching {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("error", text))
                }
                ErrorDialogState.dismiss()
            }) { Text("复制") }
        },
        dismissButton = {
            TextButton(onClick = { ErrorDialogState.dismiss() }) { Text("关闭") }
        },
        text = { Text(text) }
    )
}

// 收发文件前确认存储权限；未授权时拉起系统授权流程并返回 false。
fun Context.ensureStorageAccess(): Boolean {
    val activity = this as? Activity ?: return true
    if (StorageAccess.request(activity)) return true
    showError("请先允许访问文件")
    return false
}

/** 以按压点为零尺寸锚点弹出菜单，默认出现在手指上方。 */
@Composable
fun PressPointMenu(
    expanded: Boolean,
    point: DpOffset,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .offset(x = point.x, y = point.y)
            .size(0.dp)
    ) {
        if (expanded) {
            Popup(
                popupPositionProvider = AboveFingerPositionProvider,
                onDismissRequest = onDismissRequest,
                properties = PopupProperties(focusable = true)
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 3.dp,
                    shadowElevation = 3.dp
                ) {
                    Column(
                        modifier = Modifier
                            .width(IntrinsicSize.Max)
                            .padding(vertical = 8.dp)
                    ) {
                        content()
                    }
                }
            }
        }
    }
}

private object AboveFingerPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = (anchorBounds.left - popupContentSize.width / 2).coerceIn(0, maxX)
        val above = anchorBounds.top - popupContentSize.height - 8
        val below = anchorBounds.bottom + 8
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        val y = (if (above >= 0) above else below).coerceIn(0, maxY)
        return IntOffset(x, y)
    }
}

/** 断网时把内容糊掉并盖一层提示；标题栏/顶栏放在这个容器外面，保持清晰。 */
@Composable
fun LanOfflineBox(
    offline: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (offline) Modifier.blur(12.dp) else Modifier),
            content = content
        )
        if (offline) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (Build.VERSION.SDK_INT >= 31) Color.Transparent
                        else MaterialTheme.colorScheme.background.copy(alpha = 0.55f)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(96.dp)
                    )
                    Text(
                        text = "未连接到局域网",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }
    }
}

@Composable
fun AvatarIcon(icon: ImageVector, online: Boolean, orPublic: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Box(modifier = Modifier.size(44.dp)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(if (orPublic) colors.primaryContainer else colors.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (orPublic) colors.onPrimaryContainer else colors.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
        if (online) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(colors.background)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(LocalXtColors.current.success)
                )
            }
        }
    }
}

fun platformIcon(platform: String?): ImageVector = when {
    platform == null -> Icons.Rounded.Devices
    platform.contains("public", true) -> Icons.Rounded.Public
    platform.contains("android", true) -> Icons.Rounded.Smartphone
    platform.contains("windows", true) || platform.contains("win32", true) ||
        platform.contains("desktop", true) -> Icons.Rounded.Computer
    platform.contains("darwin", true) || platform.contains("mac", true) ||
        platform.contains("ios", true) -> Icons.Rounded.LaptopMac
    platform.contains("linux", true) -> Icons.Rounded.Computer
    else -> Icons.Rounded.Devices
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 2.dp)
    )
}
