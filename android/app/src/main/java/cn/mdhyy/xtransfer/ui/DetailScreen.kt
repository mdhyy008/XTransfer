package cn.mdhyy.xtransfer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.mdhyy.xtransfer.XlanApp
import cn.mdhyy.xtransfer.data.BLE_CONVERSATION_PREFIX
import cn.mdhyy.xtransfer.data.BucketItem
import cn.mdhyy.xtransfer.data.ConversationUi
import cn.mdhyy.xtransfer.data.MessageEntity
import cn.mdhyy.xtransfer.data.PUBLIC_CONVERSATION_ID
import cn.mdhyy.xtransfer.data.Peer
import cn.mdhyy.xtransfer.data.PUBLIC_CONVERSATION_NAME
import cn.mdhyy.xtransfer.data.Repository
import cn.mdhyy.xtransfer.data.ServiceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

private const val TIME_GAP_SECONDS = 5 * 60L

private val BUCKET_TYPE_ORDER =
    listOf("图片", "视频", "音频", "文档", "PDF", "压缩包", "安装包", "文件", "文件夹")

@Composable
fun DetailScreen(
    repo: Repository,
    convId: String,
    conv: ConversationUi?,
    messages: List<MessageEntity>,
    service: ServiceStatus,
    peer: Peer?,
    publicNotify: Boolean,
    quickPreview: Boolean,
    enterToSend: Boolean,
    bucketItems: List<BucketItem>,
    offline: Boolean,
    lanOnline: Boolean,
    scope: CoroutineScope,
    onBack: () -> Unit,
    onSetTrust: (Boolean) -> Unit,
    onSetPublicNotify: (Boolean) -> Unit,
    alias: String,
    onSetAlias: (String) -> Unit,
    clipboardOn: Boolean,
    onSetClipboard: (Boolean) -> Unit,
    muted: Boolean,
    onSetMuted: (Boolean) -> Unit,
    onOpenBucket: () -> Unit,
    onSendBucket: () -> Unit,
    onOpenMedia: (MessageEntity) -> Unit,
    onOpenApps: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val isPublic = convId == PUBLIC_CONVERSATION_ID
    val isBle = convId.startsWith(BLE_CONVERSATION_PREFIX)
    val bleManager = (context.applicationContext as XlanApp).ble
    val bleState by bleManager.state.collectAsStateWithLifecycle()
    val blePeerId = convId.removePrefix(BLE_CONVERSATION_PREFIX)
    val bleDeviceName = bleState.devices.firstOrNull { it.id == blePeerId }
        ?.name?.takeIf { it.isNotBlank() }
    val bleConnected = bleState.devices.firstOrNull { it.id == blePeerId }?.connected == true
    val bleConnecting = bleState.connecting == blePeerId
    val storedName = conv?.name?.takeIf { it.isNotBlank() && it != convId && it != blePeerId }
    val titleName = if (isBle) {
        storedName ?: bleDeviceName ?: blePeerId
    } else {
        conv?.name ?: if (isPublic) PUBLIC_CONVERSATION_NAME else convId
    }
    val canSend = if (isBle) bleConnected else service.running
    val bucketParts = remember(bucketItems) {
        val counts = HashMap<String, Int>()
        for (item in bucketItems) {
            val label = if (item.kind == "folder") "文件夹" else fileKindLabel(item.name)
            counts[label] = (counts[label] ?: 0) + 1
        }
        BUCKET_TYPE_ORDER.mapNotNull { label ->
            counts[label]?.let { count -> "${count}个$label" }
        }
    }
    val listState = rememberLazyListState()
    // 倒序 + reverseLayout：列表天生贴着底部，进页面不用把整段历史从上往下滚一遍。
    val ordered = remember(messages) { messages.asReversed() }
    val pendingIncoming = remember(ordered) {
        ordered.filter { it.status == "pending" && it.kind != "text" && it.direction == "in" }
            .map { it.id }
    }
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var sending by remember { mutableStateOf(false) }
    var aliasOpen by remember { mutableStateOf(false) }
    var aliasDraft by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var reconnectBusy by remember { mutableStateOf(false) }
    var bleOffHint by remember { mutableStateOf(false) }
    val keyword = searchQuery.trim()
    val visible = remember(ordered, keyword) {
        if (keyword.isEmpty()) {
            ordered
        } else {
            ordered.filter {
                it.body.orEmpty().contains(keyword, ignoreCase = true) ||
                    it.fileName.orEmpty().contains(keyword, ignoreCase = true)
            }
        }
    }

    LaunchedEffect(convId) {
        repo.ensureConversation(convId, titleName)
        if (isPublic) repo.syncPublicHistory()
        if (isBle && !bleManager.isConnected(blePeerId)) bleManager.connect(blePeerId)
        searchOpen = false
        searchQuery = ""
        aliasOpen = false
    }
    // 之前误把会话 id 写成了名字的，扫到设备名后纠正回来。
    LaunchedEffect(convId, bleDeviceName) {
        if (isBle && bleDeviceName != null) repo.renameBleConversation(convId, bleDeviceName)
    }
    LaunchedEffect(bleConnected) {
        if (bleConnected) reconnectBusy = false
    }
    // 点重连后最多等 10 秒；一直没连上就说明没扫到设备，告诉用户去查对方有没有开广播。
    LaunchedEffect(reconnectBusy) {
        if (!reconnectBusy) return@LaunchedEffect
        delay(10_000)
        if (reconnectBusy) {
            reconnectBusy = false
            context.toast("未发现该设备，请确认对方已开启蓝牙广播")
        }
    }
    var shownCount by remember(convId) { mutableIntStateOf(messages.size) }
    val newestIsFile = ordered.firstOrNull()?.let { it.kind != "text" } == true
    LaunchedEffect(convId, messages.size) {
        repo.markRead(convId)
        // 翻历史时不打断；但收发文件要直接跟到底部。
        if (messages.size > shownCount &&
            (newestIsFile || listState.firstVisibleItemIndex <= 1)
        ) {
            listState.animateScrollToItem(0)
        }
        shownCount = messages.size
    }

    val submit: () -> Unit = {
        val text = input.text.trim()
        if (text.isNotEmpty() && !sending && canSend) {
            sending = true
            // 立刻清空输入框，气泡在同一时刻乐观插入；失败再把文字放回去。
            input = TextFieldValue("")
            scope.launch {
                runCatching {
                    repo.sendText(if (isPublic) "" else convId, text)
                }.onFailure {
                    if (input.text.isBlank()) input = TextFieldValue(text)
                    context.showError(it.message ?: "发送失败")
                }
                sending = false
            }
        }
    }

    var attachOpen by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var previewTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var apkTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var infoTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var saveTarget by remember { mutableStateOf<MessageEntity?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val target = saveTarget
        saveTarget = null
        val source = target?.localPath
        if (uri != null && !source.isNullOrBlank()) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val input = if (source.startsWith("content://")) {
                            context.contentResolver.openInputStream(Uri.parse(source))
                        } else {
                            File(source).inputStream()
                        } ?: error("文件不存在")
                        input.use { ins ->
                            val output = context.contentResolver.openOutputStream(uri)
                                ?: error("无法写入")
                            output.use { outs -> ins.copyTo(outs) }
                        }
                    }
                }.onSuccess { context.toast("已保存") }
                    .onFailure { context.showError(it.message ?: "保存失败") }
            }
        }
    }

    val saveAs: (MessageEntity) -> Unit = { message ->
        saveTarget = message
        val name = message.fileName?.takeIf { it.isNotBlank() }
            ?: message.localPath?.let { File(it).name }.orEmpty().ifBlank { "文件" }
        saveLauncher.launch(name)
    }

    val copyPath: (MessageEntity) -> Unit = { message ->
        val path = message.localPath
        if (!path.isNullOrBlank()) {
            val copied = runCatching {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("path", path)) != null
            }.getOrDefault(false)
            if (copied) context.toast("已复制") else context.showError("复制失败")
        }
    }

    val openMessage: (MessageEntity) -> Unit = { message ->
        val path = message.localPath
        val name = message.fileName ?: path?.let { File(it).name }.orEmpty()
        when {
            path.isNullOrBlank() -> context.showError("文件不存在")
            message.status != "done" && message.status != "duplicate" -> Unit
            message.kind == "folder" -> openFileExternally(context, path, name, folder = true)
            quickPreview && extensionOf(name) == "apk" -> apkTarget = message
            quickPreview && previewKindOf(name) in setOf(
                PreviewKind.IMAGE,
                PreviewKind.VIDEO,
                PreviewKind.AUDIO
            ) -> onOpenMedia(message)
            quickPreview && previewKindOf(name) == PreviewKind.TEXT -> previewTarget = message
            quickPreview -> context.showError("不支持快速预览")
            else -> openFileExternally(context, path, name)
        }
    }

    fun sendPicked(items: List<BucketItem>) {
        if (items.isEmpty()) {
            context.showError("无法读取所选项目")
            return
        }
        if (!context.ensureStorageAccess()) return
        if (!repo.sendBucket(convId, items)) {
            context.showError("设备不在线")
        }
    }

    fun keepAccess(uris: List<Uri>) {
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) sendPicked(uris.mapNotNull { repo.makeFileItem(it) })
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            keepAccess(uris)
            sendPicked(uris.mapNotNull { repo.makeFileItem(it) })
        }
    }

    val pickFolder = rememberDirectoryPicker(
        initialPath = Environment.getExternalStorageDirectory().absolutePath
    ) { path ->
        sendPicked(listOfNotNull(repo.makeFolderItem(File(path))))
    }

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val uri = cameraUri
        if (ok && uri != null) sendPicked(listOfNotNull(repo.makeFileItem(uri)))
    }

    fun launchCamera() {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: return context.showError("无法打开相机")
        cameraUri = uri
        runCatching { takePicture.launch(uri) }
            .onFailure { context.showError("无法打开相机") }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                    }
                )
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = buildAnnotatedString {
                        append(titleName)
                        conv?.originalName?.takeIf { it.isNotBlank() }?.let { original ->
                            append(" ")
                            withStyle(
                                SpanStyle(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                )
                            ) { append("（$original）") }
                        }
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val online = if (isBle) bleConnected else conv?.online == true
                    if (isBle && bleConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(8.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    if (if (isPublic) lanOnline else online) LocalXtColors.current.success
                                    else MaterialTheme.colorScheme.outline
                                )
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = when {
                            isPublic -> if (lanOnline) "已连接" else "未连接"
                            isBle -> when {
                                bleConnected -> "已连接"
                                bleConnecting -> "正在连接"
                                else -> "未连接"
                            }
                            conv?.online == true -> "在线"
                            else -> "离线"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(5.dp))
                    val active = if (isPublic) lanOnline else online
                    val reconnect = isBle && !bleConnected && !bleConnecting
                    Row(
                        modifier = Modifier
                            .height(20.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (active) LocalXtColors.current.success
                                else MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                            .then(
                                if (reconnect && !reconnectBusy) Modifier.clickable {
                                    if (!bleState.on) {
                                        bleOffHint = true
                                    } else {
                                        reconnectBusy = true
                                        bleManager.connect(blePeerId)
                                    }
                                } else Modifier
                            )
                            .padding(horizontal = if (reconnect) 5.dp else 0.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isBle) Icons.Rounded.Bluetooth else Icons.Rounded.Wifi,
                                contentDescription = null,
                                tint = if (active) Color.White
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                        if (reconnect) {
                            Text(
                                text = if (reconnectBusy) "连接中" else "重连",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            if (!isBle && !isPublic) {
                IconButton(onClick = onOpenBucket) {
                    Icon(Icons.Rounded.Inventory2, contentDescription = "暂存桶")
                }
            }
            if (isPublic || isBle || peer != null) {
                val target = peer
                Box {
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        if (isPublic) {
                            DropdownMenuItem(
                                text = { Text("允许通知") },
                                // 开关项点文字、点开关都只切换状态，都不关菜单。
                                onClick = { onSetPublicNotify(!publicNotify) },
                                trailingIcon = {
                                    Switch(
                                        checked = publicNotify,
                                        onCheckedChange = { onSetPublicNotify(it) }
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("清空聊天记录") },
                                onClick = {
                                    menuOpen = false
                                    confirmClear = true
                                }
                            )
                        } else if (isBle) {
                            DropdownMenuItem(
                                text = { Text("免打扰") },
                                // 开关项点文字、点开关都只切换状态，都不关菜单。
                                onClick = { onSetMuted(!muted) },
                                trailingIcon = {
                                    Switch(
                                        checked = muted,
                                        onCheckedChange = { onSetMuted(it) }
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("修改备注") },
                                onClick = {
                                    menuOpen = false
                                    aliasDraft = alias
                                    aliasOpen = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("搜索历史记录") },
                                onClick = {
                                    menuOpen = false
                                    searchQuery = ""
                                    searchOpen = true
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("清空聊天记录") },
                                onClick = {
                                    menuOpen = false
                                    confirmClear = true
                                }
                            )
                        } else if (target != null) {
                            DropdownMenuItem(
                                text = { Text("接收跨设备复制") },
                                // 开关项点文字、点开关都只切换状态，都不关菜单。
                                onClick = { onSetClipboard(!clipboardOn) },
                                trailingIcon = {
                                    Switch(
                                        checked = clipboardOn,
                                        onCheckedChange = { onSetClipboard(it) }
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("自动接收文件") },
                                onClick = { onSetTrust(target.trusted != true) },
                                trailingIcon = {
                                    Switch(
                                        checked = target.trusted,
                                        onCheckedChange = { onSetTrust(it) }
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("免打扰") },
                                onClick = { onSetMuted(!muted) },
                                trailingIcon = {
                                    Switch(
                                        checked = muted,
                                        onCheckedChange = { onSetMuted(it) }
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("修改备注") },
                                onClick = {
                                    menuOpen = false
                                    aliasDraft = alias
                                    aliasOpen = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("搜索历史记录") },
                                onClick = {
                                    menuOpen = false
                                    searchQuery = ""
                                    searchOpen = true
                                }
                            )
                        }
                    }
                }
            }
        }

        if (searchOpen) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索聊天记录") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            searchOpen = false
                            searchQuery = ""
                        }
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "关闭")
                    }
                }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        )

        LanOfflineBox(
            offline = offline && !isBle,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
        Column(modifier = Modifier.fillMaxSize()) {
        if (visible.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (keyword.isNotEmpty()) "无匹配结果" else "暂无消息",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 12.dp
                )
            ) {
                itemsIndexed(visible, key = { _, message -> message.id }) { index, message ->
                    val previous = visible.getOrNull(index + 1)
                    val showTime = message.createdAt > 0 &&
                        (previous == null || message.createdAt - previous.createdAt >= TIME_GAP_SECONDS)
                    // reverseLayout 会反转同一项里多个并列组件的摆放顺序，包一层 Column 才能让时间待在气泡上方。
                    Column(modifier = Modifier.fillMaxWidth()) {
                    if (showTime) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = formatTime(
                                    message.createdAt,
                                    withDate = System.currentTimeMillis() / 1000 - message.createdAt > 86400
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    MessageRow(
                        message = message,
                        showName = isPublic,
                        onDecide = { id, action ->
                            scope.launch {
                                runCatching { repo.decide(id, action) }
                                    .onFailure { context.showError(it.message ?: "操作失败") }
                            }
                        },
                        // 只有最后一条待接收（列表最下面那条）才带「全部接收」。
                        onAcceptAll = if (message.id == pendingIncoming.firstOrNull()) {
                            {
                                scope.launch {
                                    pendingIncoming.forEach { id ->
                                        runCatching { repo.decide(id, "accept") }
                                            .onFailure { context.showError(it.message ?: "操作失败") }
                                    }
                                }
                            }
                        } else {
                            null
                        },
                        onRetry = { message ->
                            scope.launch {
                                runCatching { repo.retrySend(message) }
                                    .onFailure { context.showError(it.message ?: "重试失败") }
                            }
                        },
                        onCancel = { id -> repo.cancelSend(id) },
                        onOpen = openMessage,
                        onCopyPath = copyPath,
                        onShowInfo = { infoTarget = it },
                        onSaveAs = saveAs,
                        onDelete = { id ->
                            scope.launch {
                                runCatching { repo.deleteMessage(id) }
                                    .onSuccess { context.toast("已删除") }
                                    .onFailure { context.showError(it.message ?: "删除失败") }
                            }
                        },
                        onDeleteFile = { target ->
                            scope.launch {
                                runCatching {
                                    val path = target.localPath
                                    if (!path.isNullOrBlank()) {
                                        if (path.startsWith("content://")) {
                                            context.contentResolver.delete(Uri.parse(path), null, null)
                                        } else {
                                            File(path).deleteRecursively()
                                        }
                                    }
                                    repo.deleteMessage(target.id)
                                }
                                    .onSuccess { context.toast("已删除文件") }
                                    .onFailure { context.showError(it.message ?: "删除失败") }
                            }
                        }
                    )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .navigationBarsPadding()
                .imePadding()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            )
            if (!isPublic && !isBle && bucketParts.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable(onClick = onOpenBucket)
                            .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "桶中 ${bucketParts.joinToString("，")}",
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Button(
                            onClick = onSendBucket,
                            enabled = service.running,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("发送", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isPublic && !isBle) {
                    IconButton(onClick = { if (context.ensureStorageAccess()) attachOpen = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = "发送")
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .weight(1f)
                        .onPreviewKeyEvent { event ->
                            // 硬件键盘：Ctrl+Enter 在开启回车发送时换行，关闭时发送，同桌面端。
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            if (event.key != Key.Enter || !event.isCtrlPressed) return@onPreviewKeyEvent false
                            if (!enterToSend) {
                                submit()
                            } else {
                                val text = input.text
                                val start = input.selection.min
                                val end = input.selection.max
                                input = TextFieldValue(
                                    text.substring(0, start) + "\n" + text.substring(end),
                                    TextRange(start + 1)
                                )
                            }
                            true
                        },
                    placeholder = {
                        Text(if (isBle && !bleConnected) "蓝牙未连接" else "输入消息")
                    },
                    minLines = 1,
                    maxLines = 3,
                    shape = RoundedCornerShape(22.dp),
                    keyboardOptions = KeyboardOptions(
                        imeAction = if (enterToSend) ImeAction.Send else ImeAction.Default
                    ),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Transparent,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                )
                IconButton(
                    onClick = submit,
                    enabled = canSend && input.text.trim().isNotEmpty() && !sending
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "发送")
                }
            }
        }
        }
        }
    }

    if (attachOpen) {
        ModalBottomSheet(
            onDismissRequest = { attachOpen = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 8.dp, end = 8.dp, bottom = 16.dp)
            ) {
                AttachOption(Icons.Rounded.PhotoCamera, "拍照") {
                    attachOpen = false
                    launchCamera()
                }
                AttachOption(Icons.Rounded.PhotoLibrary, "相册") {
                    attachOpen = false
                    galleryPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    )
                }
                AttachOption(Icons.Rounded.Description, "文件") {
                    attachOpen = false
                    filePicker.launch(arrayOf("*/*"))
                }
                AttachOption(Icons.Rounded.Folder, "文件夹") {
                    attachOpen = false
                    pickFolder()
                }
                AttachOption(Icons.Rounded.Apps, "应用软件包") {
                    attachOpen = false
                    onOpenApps()
                }
            }
        }
    }

    val apk = apkTarget
    if (apk != null) {
        ApkPreviewDialog(
            path = apk.localPath.orEmpty(),
            name = apk.fileName ?: File(apk.localPath.orEmpty()).name,
            onDismiss = { apkTarget = null }
        )
    }

    val preview = previewTarget
    if (preview != null) {
        FilePreviewDialog(
            message = preview,
            onDismiss = { previewTarget = null },
            onOpenExternal = {
                val path = preview.localPath
                if (!path.isNullOrBlank()) {
                    openFileExternally(context, path, preview.fileName)
                }
                previewTarget = null
            }
        )
    }

    if (bleOffHint) {
        AlertDialog(
            onDismissRequest = { bleOffHint = false },
            title = { Text("蓝牙广播未开启") },
            text = { Text("蓝牙广播关着，无法连接这个设备。开启后会立即尝试连接。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        bleOffHint = false
                        reconnectBusy = true
                        bleManager.enable()
                        bleManager.connect(blePeerId)
                    }
                ) { Text("开启并连接") }
            },
            dismissButton = {
                TextButton(onClick = { bleOffHint = false }) { Text("取消") }
            }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空聊天记录") },
            text = { Text("确定要清空 ${titleName} 的聊天记录吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        scope.launch {
                            runCatching { repo.clearConversation(convId) }
                                .onSuccess { context.toast("已清空") }
                                .onFailure { context.showError(it.message ?: "清空失败") }
                        }
                    }
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }

    if (aliasOpen) {
        AlertDialog(
            onDismissRequest = { aliasOpen = false },
            title = { Text("修改备注") },
            text = {
                OutlinedTextField(
                    value = aliasDraft,
                    onValueChange = { if (it.length <= 15) aliasDraft = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSetAlias(aliasDraft.trim().take(15))
                        aliasOpen = false
                        context.toast(if (aliasDraft.isBlank()) "已清除备注名" else "已保存")
                    }
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { aliasOpen = false }) { Text("取消") }
            }
        )
    }

    val fileInfo = infoTarget
    if (fileInfo != null) {
        var folderSize by remember(fileInfo.id) { mutableStateOf<Long?>(null) }
        LaunchedEffect(fileInfo.id) {
            val path = fileInfo.localPath
            if (fileInfo.kind == "folder" && !path.isNullOrBlank()) {
                folderSize = withContext(Dispatchers.IO) {
                    runCatching {
                        File(path).walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    }.getOrDefault(0L)
                }
            }
        }
        AlertDialog(
            onDismissRequest = { infoTarget = null },
            title = { Text("文件信息") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FileInfoRow("文件名", fileInfo.fileName ?: "未命名")
                    FileInfoRow(
                        "类型",
                        if (fileInfo.kind == "folder") "文件夹"
                        else fileKindLabel(fileInfo.fileName ?: fileInfo.localPath ?: "")
                    )
                    FileInfoRow(
                        "大小",
                        if (fileInfo.kind == "folder") {
                            folderSize?.let { formatSize(it).ifBlank { "0 B" } } ?: "—"
                        } else {
                            formatSize(fileInfo.fileSize).ifBlank { "—" }
                        }
                    )
                    FileInfoRow("路径", fileInfo.localPath ?: "—")
                    FileInfoRow("时间", formatTime(fileInfo.createdAt, withDate = true))
                    FileInfoRow("状态", messageStatusLabel(fileInfo.status))
                }
            },
            confirmButton = {
                TextButton(onClick = { infoTarget = null }) { Text("关闭") }
            }
        )
    }
}

@Composable
private fun FileInfoRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun messageStatusLabel(status: String): String = when (status) {
    "pending" -> "待接收"
    "queued" -> "正在队列中"
    "sending" -> "等待确认"
    "accepted", "active" -> "传输中"
    "failed" -> "失败"
    "canceled" -> "已取消"
    "peer_canceled" -> "对方已取消"
    "rejected" -> "已拒绝"
    "duplicate" -> "已有相同文件，未传输"
    else -> "已完成"
}

@Composable
private fun AttachOption(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun MessageRow(
    message: MessageEntity,
    showName: Boolean,
    onDecide: (String, String) -> Unit,
    onAcceptAll: (() -> Unit)?,
    onRetry: (MessageEntity) -> Unit,
    onCancel: (String) -> Unit,
    onOpen: (MessageEntity) -> Unit,
    onCopyPath: (MessageEntity) -> Unit,
    onShowInfo: (MessageEntity) -> Unit,
    onSaveAs: (MessageEntity) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteFile: (MessageEntity) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val mine = message.direction == "out"
    val pending = message.status == "pending" && message.kind != "text" && !mine
    val missing = message.kind != "text" && isMissingFile(message)
    var menuOpen by remember(message.id) { mutableStateOf(false) }
    var confirmDelete by remember(message.id) { mutableStateOf(false) }
    val openMenuAt: (DpOffset) -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        menuOpen = true
    }
    val menuContent: @Composable ColumnScope.() -> Unit = {
        MessageMenuItems(
            message = message,
            mine = mine,
            missing = missing,
            context = context,
            onClose = { menuOpen = false },
            onCancel = onCancel,
            onRetry = onRetry,
            onCopyPath = onCopyPath,
            onShowInfo = onShowInfo,
            onSaveAs = onSaveAs,
            onDelete = {
                menuOpen = false
                confirmDelete = true
            }
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.78f),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start
        ) {
            if (showName && !mine) {
                Text(
                    text = message.fromName ?: message.fromId,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
            if (message.kind == "text") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (mine && message.status == "sending") {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Box {
                        Surface(
                            modifier = Modifier.pointerInput(message.id) {
                                detectTapGestures(
                                    onLongPress = { point ->
                                        openMenuAt(DpOffset(point.x.toDp(), point.y.toDp()))
                                    }
                                )
                            },
                            shape = if (mine) {
                                RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
                            } else {
                                RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
                            },
                            color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        ) {
                            Text(
                                text = message.body.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                            )
                        }
                        MessageMenuSheet(
                            expanded = menuOpen,
                            onDismiss = { menuOpen = false },
                            content = menuContent
                        )
                    }
                }
            } else {
                Box {
                    FileCard(
                        message = message,
                        mine = mine,
                        missing = missing,
                        onLongPress = openMenuAt,
                        onTap = if (missing || (message.status != "done" && message.status != "duplicate")) {
                            null
                        } else {
                            { onOpen(message) }
                        },
                        onRetry = { onRetry(message) },
                        onCancel = {
                            if (mine) onCancel(message.id) else onDecide(message.id, "cancel")
                        }
                    )
                    MessageMenuSheet(
                        expanded = menuOpen,
                        onDismiss = { menuOpen = false },
                        content = menuContent
                    )
                }
            }
            if (pending) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    IconButton(onClick = { onDecide(message.id, "accept") }) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = "接受",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = { onDecide(message.id, "reject") }) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "拒绝",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (onAcceptAll != null) {
                        IconButton(onClick = onAcceptAll) {
                            Icon(
                                Icons.Rounded.DoneAll,
                                contentDescription = "全部接收",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            if (mine && message.kind == "text" && message.status == "failed") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Text(
                        text = "发送失败",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(
                        onClick = { onRetry(message) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("重试", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除") },
            text = {
                Column {
                    Text(
                        text = if (message.kind == "text") {
                            "确定要删除这条消息吗？"
                        } else {
                            "确定要删除「${message.fileName ?: "文件"}」吗？"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    val path = message.localPath
                    if (message.kind != "text" && !path.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            confirmButton = {
                if (message.kind != "text" && !missing && !mine) {
                    TextButton(
                        onClick = {
                            confirmDelete = false
                            onDeleteFile(message)
                        }
                    ) { Text("删除文件") }
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            confirmDelete = false
                            onDelete(message.id)
                        }
                    ) { Text("删除记录") }
                    TextButton(onClick = { confirmDelete = false }) { Text("取消") }
                }
            }
        )
    }
}

@Composable
private fun MessageMenuSheet(
    expanded: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    if (!expanded) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 8.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun MessageMenuItems(
    message: MessageEntity,
    mine: Boolean,
    missing: Boolean,
    context: Context,
    onClose: () -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (MessageEntity) -> Unit,
    onCopyPath: (MessageEntity) -> Unit,
    onShowInfo: (MessageEntity) -> Unit,
    onSaveAs: (MessageEntity) -> Unit,
    onDelete: () -> Unit
) {
    val body = message.body
    val items = buildList<MessageMenuAction> {
        if (message.kind == "text" && !body.isNullOrEmpty()) {
            add(
                MessageMenuAction("复制文本", Icons.Rounded.ContentCopy) {
                    val copied = runCatching {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("text", body)) != null
                    }.getOrDefault(false)
                    if (copied) context.toast("已复制") else context.showError("复制失败")
                }
            )
        }
        if (message.kind != "text") {
            if (!message.localPath.isNullOrBlank()) {
                add(MessageMenuAction("复制文件路径", Icons.Rounded.ContentCopy) { onCopyPath(message) })
            }
            add(MessageMenuAction("查看文件信息", Icons.Rounded.Info) { onShowInfo(message) })
            if (message.kind == "file" && !missing && !message.localPath.isNullOrBlank()) {
                add(MessageMenuAction("另存为…", Icons.Rounded.SaveAlt) { onSaveAs(message) })
            }
            if (!missing && !message.localPath.isNullOrBlank()) {
                add(MessageMenuAction("用其他应用打开", Icons.Rounded.OpenInNew) {
                    openFileExternally(
                        context,
                        message.localPath.orEmpty(),
                        message.fileName,
                        folder = message.kind == "folder"
                    )
                })
            }
        }
        if (mine && message.kind != "text" &&
            (message.status == "queued" || message.status == "sending" ||
                message.status == "active" || message.status == "accepted")
        ) {
            add(MessageMenuAction("取消发送", Icons.Rounded.Cancel) { onCancel(message.id) })
        }
        if (mine && !missing && message.kind != "text" && message.status == "failed" && message.localPath != null) {
            add(MessageMenuAction("重试", Icons.Rounded.Replay) { onRetry(message) })
        }
        add(MessageMenuAction("删除", Icons.Rounded.Delete, onDelete))
    }
    Column {
        items.forEachIndexed { index, item ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                )
            }
            DropdownMenuItem(
                contentPadding = PaddingValues(start = 24.dp, end = 16.dp),
                leadingIcon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                text = {
                    Text(
                        text = item.label,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                onClick = {
                    onClose()
                    item.action()
                }
            )
        }
    }
}

private class MessageMenuAction(
    val label: String,
    val icon: ImageVector,
    val action: () -> Unit
)

private fun isMissingFile(message: MessageEntity): Boolean {
    val path = message.localPath ?: return false
    if (path.startsWith("content://")) return false
    return !File(path).exists()
}

@Composable
private fun FileCard(
    message: MessageEntity,
    mine: Boolean,
    missing: Boolean,
    onLongPress: ((DpOffset) -> Unit)?,
    onTap: (() -> Unit)?,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val active = message.status == "active" || message.status == "accepted"
    val dimmed = missing || message.status == "failed" ||
        message.status == "canceled" || message.status == "peer_canceled" ||
        message.status == "rejected"
    val total = if (message.total > 0) message.total else (message.fileSize ?: 0)
    val percent = if (active && total > 0) min(100, ((message.received * 100.0) / total).roundToInt()) else 0
    val canCancel = !missing && (
        if (mine) message.status == "queued" || message.status == "sending" || active else active
        )
    val longPress = onLongPress
    val thumb = rememberFileThumbnail(message)
    val displayName = message.fileName ?: message.localPath ?: ""
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (mine) colors.primary else colors.surfaceContainerLow,
        contentColor = if (mine) colors.onPrimary else colors.onSurface,
        border = if (mine) null else BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.45f else 1f)
            .then(
                if (longPress != null || onTap != null) {
                    Modifier.pointerInput(message.id, onTap) {
                        detectTapGestures(
                            onTap = { onTap?.invoke() },
                            onLongPress = { point ->
                                longPress?.invoke(DpOffset(point.x.toDp(), point.y.toDp()))
                            }
                        )
                    }
                } else {
                    Modifier
                }
            )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (mine) colors.onPrimary.copy(alpha = 0.18f) else colors.surfaceContainerHigh),
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
                            imageVector = if (message.kind == "folder") Icons.Rounded.Folder else fileTypeIcon(displayName),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = message.fileName ?: "未命名",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    StatusLine(message = message, mine = mine, missing = missing)
                }
                if (canCancel) {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Rounded.StopCircle, contentDescription = "取消", modifier = Modifier.size(20.dp))
                    }
                } else if (mine && !missing && message.status == "failed" && message.localPath != null) {
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Rounded.Replay, contentDescription = "重试", modifier = Modifier.size(20.dp))
                    }
                }
            }
            if (active) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape),
                    color = if (mine) colors.onPrimary else colors.primary,
                    trackColor = if (mine) colors.onPrimary.copy(alpha = 0.24f) else colors.surfaceContainerHigh
                )
            }
        }
    }
}

@Composable
private fun StatusLine(message: MessageEntity, mine: Boolean, missing: Boolean) {
    val colors = MaterialTheme.colorScheme
    val secondary = if (mine) colors.onPrimary.copy(alpha = 0.82f) else colors.onSurfaceVariant
    val error = if (mine) LocalXtColors.current.errorOnPrimary else colors.error
    val kindLabel = if (message.kind == "folder") {
        "文件夹"
    } else {
        fileKindLabel(message.fileName ?: message.localPath ?: "")
    }
    if (missing) {
        Text(text = "$kindLabel - 文件不存在", style = MaterialTheme.typography.bodySmall, color = secondary)
        return
    }
    val text: String = when (message.status) {
        "pending" -> {
            val waited = System.currentTimeMillis() / 1000 - message.createdAt
            "$kindLabel - 待接收" + if (waited > 60) " · 可能已超时" else ""
        }
        "queued" -> "$kindLabel - 正在队列中"
        "sending" -> "$kindLabel - 等待确认"
        "active", "accepted" -> {
            val total = if (message.total > 0) message.total else (message.fileSize ?: 0)
            val base = if (total > 0) "${formatSize(message.received)} / ${formatSize(total)}" else "传输中"
            val speed = formatSpeed(message.speedBps)
            "$kindLabel - " + if (speed.isNotEmpty()) "$base · $speed" else base
        }
        "failed" -> "$kindLabel - 失败"
        "canceled" -> "$kindLabel - 已取消"
        "peer_canceled" -> "$kindLabel - 对方已取消"
        "rejected" -> "$kindLabel - 已拒绝"
        else -> "$kindLabel - 传输已完成"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (message.status == "failed") error else secondary
    )
}
