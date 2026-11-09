package cn.mdhyy.xtransfer.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.mdhyy.xtransfer.data.ConversationUi
import cn.mdhyy.xtransfer.data.MessageEntity
import cn.mdhyy.xtransfer.data.Peer
import cn.mdhyy.xtransfer.data.PUBLIC_CONVERSATION_ID
import cn.mdhyy.xtransfer.data.PUBLIC_CONVERSATION_NAME
import cn.mdhyy.xtransfer.data.BLE_CONVERSATION_PREFIX
import cn.mdhyy.xtransfer.data.BleDevice
import cn.mdhyy.xtransfer.data.BleManager
import cn.mdhyy.xtransfer.data.HotspotState
import cn.mdhyy.xtransfer.data.LanInfo
import cn.mdhyy.xtransfer.data.LocalHotspot
import cn.mdhyy.xtransfer.data.Repository
import cn.mdhyy.xtransfer.data.WifiDirectDevice
import cn.mdhyy.xtransfer.data.WifiDirectManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class DeviceInfoTarget(
    val id: String,
    val name: String,
    val platform: String?,
    val online: Boolean,
    val addr: String?,
    val port: Int,
    val trusted: Boolean?,
    val lastSeen: Long,
    val isBle: Boolean = false,
    val bleAddress: String? = null,
    val rssi: Int? = null
)

@Composable
fun ListScreen(
    repo: Repository,
    conversations: List<ConversationUi>,
    latest: Map<String, MessageEntity>,
    peers: List<Peer>,
    lanInfo: LanInfo?,
    bucketCount: Int,
    blocked: Set<String>,
    aliases: Map<String, String>,
    muted: Set<String>,
    pinned: Set<String>,
    peakSpeeds: Map<String, Long>,
    lanOffline: Boolean,
    scope: CoroutineScope,
    onOpenConv: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBucket: () -> Unit,
    hotspot: LocalHotspot,
    wifiDirect: WifiDirectManager,
    ble: BleManager,
    onOpenHotspot: () -> Unit,
    onOpenWifiDirect: () -> Unit
) {
    val direct by wifiDirect.state.collectAsStateWithLifecycle()
    val bleState by ble.state.collectAsStateWithLifecycle()
    val hotspotState by hotspot.state.collectAsStateWithLifecycle()
    val hotspotSupported = hotspot.supported
    val activeLinks = buildList {
        if (hotspotState is HotspotState.Running) add(Icons.Rounded.WifiTethering)
        if (direct.connected) add(Icons.Rounded.Devices)
        if (bleState.on) add(Icons.Rounded.Bluetooth)
    }
    var connectionsOpen by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf("") }
    var flashText by remember { mutableStateOf("") }
    var flashOpen by remember { mutableStateOf(false) }
    var flashTick by remember { mutableIntStateOf(0) }
    var pendingClear by remember { mutableStateOf<ConversationUi?>(null) }
    var pendingDelete by remember { mutableStateOf<ConversationUi?>(null) }
    var infoTarget by remember { mutableStateOf<DeviceInfoTarget?>(null) }
    var aliasTarget by remember { mutableStateOf<ConversationUi?>(null) }
    var aliasDraft by remember { mutableStateOf("") }
    val context = LocalContext.current
    val bleEnable = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }
    fun startBle() {
        ble.enable()
        if (!ble.adapterOn) {
            runCatching {
                bleEnable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
        }
    }
    val blePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            startBle()
        } else {
            context.showError("需要蓝牙权限")
        }
    }

    fun toggleBle() {
        when {
            bleState.on -> ble.disable()
            !ble.supported -> context.showError("此设备不支持蓝牙")
            !ble.granted() -> blePermission.launch(ble.permissions.toTypedArray())
            else -> startBle()
        }
    }

    LaunchedEffect(flashTick) {
        if (flashTick == 0) return@LaunchedEffect
        flashOpen = true
        delay(2000)
        flashOpen = false
    }

    // 组网成功后 xlan 要在 P2P 网段上重新发现对端。
    LaunchedEffect(direct.connected) {
        if (direct.connected) runCatching { repo.scan() }
    }

    fun showInfo(conv: ConversationUi) {
        val peer = peers.firstOrNull { it.id == conv.id }
        val isBle = conv.id.startsWith(BLE_CONVERSATION_PREFIX)
        val bleDevice = if (isBle) {
            bleState.devices.firstOrNull { BLE_CONVERSATION_PREFIX + it.id == conv.id }
        } else {
            null
        }
        infoTarget = DeviceInfoTarget(
            id = if (isBle) conv.id.removePrefix(BLE_CONVERSATION_PREFIX) else conv.id,
            name = bleDevice?.name?.takeIf { it.isNotBlank() }
                ?: peer?.name?.takeIf { it.isNotBlank() }
                ?: conv.name,
            platform = bleDevice?.platform ?: peer?.platform ?: conv.platform,
            online = bleDevice?.connected ?: conv.online,
            addr = peer?.addr,
            port = peer?.port ?: 0,
            trusted = peer?.trusted,
            lastSeen = bleDevice?.lastSeen ?: peer?.lastSeen ?: 0,
            isBle = isBle,
            bleAddress = bleDevice?.address,
            rssi = bleDevice?.rssi
        )
    }

    val publicConv = (conversations.firstOrNull { it.id == PUBLIC_CONVERSATION_ID }
        ?: ConversationUi(PUBLIC_CONVERSATION_ID, PUBLIC_CONVERSATION_NAME, null, true, 0, 0, 0, 0))
        .copy(online = lanInfo != null)
    val bleConvs = conversations.filter { it.id.startsWith(BLE_CONVERSATION_PREFIX) }
    val blePending = bleConvs.filter { conv ->
        val peerId = conv.id.removePrefix(BLE_CONVERSATION_PREFIX)
        bleState.devices.none { it.id == peerId }
    }
    val devices = conversations.filter {
        it.id != PUBLIC_CONVERSATION_ID && !it.id.startsWith(BLE_CONVERSATION_PREFIX)
    }
    val pinnedDevices = devices
        .filter { it.id in pinned }
        .sortedWith(compareByDescending<ConversationUi> { it.lastMessageAt }.thenBy { it.name })
    val rest = devices.filter { it.id !in pinned }
    val online = rest
        .filter { it.online }
        .sortedWith(compareByDescending<ConversationUi> { it.lastMessageAt }.thenBy { it.name })
    val offline = rest
        .filter { !it.online }
        .sortedWith(compareByDescending<ConversationUi> { it.lastMessageAt }.thenBy { it.name })

    fun bleConvFor(device: BleDevice): ConversationUi {
        val convId = BLE_CONVERSATION_PREFIX + device.id
        val stored = bleConvs.firstOrNull { it.id == convId }?.copy(online = device.connected)
        return (stored ?: ConversationUi(
            id = convId,
            name = device.name,
            platform = device.platform,
            online = device.connected,
            lastMessageAt = 0,
            lastReadAt = 0,
            unread = 0,
            createdAt = 0
        )).let {
            if (it.name == convId || it.name == device.id) it.copy(name = device.name) else it
        }
    }

    // 蓝牙行也要吃置顶：置顶的进「置顶」分组，其余留在「蓝牙设备」。
    val bleDeviceRows = bleState.devices.map { bleConvFor(it) to it }
    val pinnedBleDeviceRows = bleDeviceRows.filter { it.first.id in pinned }
    val normalBleDeviceRows = bleDeviceRows.filter { it.first.id !in pinned }
    val pinnedBlePending = blePending.filter { it.id in pinned }
    val normalBlePending = blePending.filter { it.id !in pinned }
    // 公共频道不固定第一位，和局域网设备一起按在线、最近消息排。
    val lanConvs = (listOf(publicConv) + online + offline)
        .sortedWith(
            compareByDescending<ConversationUi> { it.online }
                .thenByDescending { it.lastMessageAt }
        )

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "X互传",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = lanInfo?.let { "${it.name} ${it.cidr}" } ?: "未连接到局域网",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (activeLinks.isNotEmpty() ||
                        (lanInfo == null &&
                            (hotspotSupported || wifiDirect.supported || ble.supported))
                    ) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .clickable { connectionsOpen = true }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (activeLinks.isNotEmpty()) {
                                    activeLinks.forEach { icon ->
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.MoreHoriz,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = "更多连接方式",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
            IconButton(onClick = onOpenBucket) {
                BadgedBox(
                    badge = {
                        if (bucketCount > 0) Badge { Text(bucketCount.toString()) }
                    }
                ) {
                    Icon(Icons.Rounded.Inventory2, contentDescription = "暂存桶")
                }
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Rounded.Settings, contentDescription = "设置")
            }
        }

        if (scanError.isNotEmpty()) {
            Text(
                text = scanError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            )
        }

        LanOfflineBox(
            offline = lanOffline,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
        PullToRefreshBox(
            isRefreshing = scanning,
            onRefresh = {
                if (!scanning) {
                    scanning = true
                    scanError = ""
                    if (bleState.on) ble.rescan()
                    scope.launch {
                        runCatching { repo.scan() }
                            .onSuccess {
                                flashText = "刷新完成"
                                flashTick++
                            }
                            .onFailure { scanError = it.message ?: "刷新失败" }
                        scanning = false
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
            val hasPinned = pinnedDevices.isNotEmpty() ||
                pinnedBleDeviceRows.isNotEmpty() || pinnedBlePending.isNotEmpty()
            if (hasPinned) {
            item(key = "pinned-label") {
                SectionLabel(
                    text = "置顶",
                )
                Spacer(Modifier.height(1.dp))
            }
            items(pinnedDevices, key = { "pin-${it.id}" }) { conv ->
                ConversationRow(
                    conv = conv,
                    preview = previewOf(latest[conv.id]).ifBlank { if (conv.online) "在线" else "离线" },
                    isPublic = false,
                    blocked = false,
                    muted = conv.id in muted,
                    pinned = true,
                    onClick = { onOpenConv(conv.id) },
                    onToggleBlock = {
                        scope.launch {
                            runCatching { repo.setBlocked(conv.id, conv.id !in blocked) }
                        }
                    },
                    onToggleMute = {
                        scope.launch { repo.setMuted(conv.id, conv.id !in muted) }
                    },
                    onTogglePin = { scope.launch { repo.setPinned(conv.id, false) } },
                    onSetAlias = {
                        aliasDraft = aliases[conv.id].orEmpty()
                        aliasTarget = conv
                    },
                    onClear = { pendingClear = conv },
                    onDelete = { pendingDelete = conv },
                    onShowInfo = { showInfo(conv) }
                )
            }
            items(pinnedBleDeviceRows, key = { "pin-ble-${it.second.id}" }) { (conv, device) ->
                ConversationRow(
                    conv = conv,
                    preview = previewOf(latest[conv.id]).ifBlank {
                        when {
                            bleState.connecting == device.id -> "正在连接"
                            device.connected -> "已连接"
                            else -> "未连接"
                        }
                    },
                    isPublic = false,
                    blocked = conv.id in blocked,
                    muted = conv.id in muted,
                    pinned = true,
                    onClick = {
                        ble.connect(device.id)
                        onOpenConv(conv.id)
                    },
                    onToggleBlock = {
                        scope.launch {
                            runCatching { repo.setBlocked(conv.id, conv.id !in blocked) }
                        }
                    },
                    onToggleMute = {
                        scope.launch { repo.setMuted(conv.id, conv.id !in muted) }
                    },
                    onTogglePin = { scope.launch { repo.setPinned(conv.id, false) } },
                    onSetAlias = {
                        aliasDraft = aliases[conv.id].orEmpty()
                        aliasTarget = conv
                    },
                    onClear = { pendingClear = conv },
                    onDelete = { pendingDelete = conv },
                    onShowInfo = { showInfo(conv) }
                )
            }
            items(pinnedBlePending, key = { "pin-blec-${it.id}" }) { conv ->
                ConversationRow(
                    conv = conv,
                    preview = previewOf(latest[conv.id]).ifBlank { "未连接" },
                    isPublic = false,
                    blocked = conv.id in blocked,
                    muted = conv.id in muted,
                    pinned = true,
                    onClick = { onOpenConv(conv.id) },
                    onToggleBlock = {
                        scope.launch {
                            runCatching { repo.setBlocked(conv.id, conv.id !in blocked) }
                        }
                    },
                    onToggleMute = {
                        scope.launch { repo.setMuted(conv.id, conv.id !in muted) }
                    },
                    onTogglePin = { scope.launch { repo.setPinned(conv.id, false) } },
                    onSetAlias = {
                        aliasDraft = aliases[conv.id].orEmpty()
                        aliasTarget = conv
                    },
                    onClear = { pendingClear = conv },
                    onDelete = { pendingDelete = conv },
                    onShowInfo = { showInfo(conv) }
                )
            }
            }
            if (direct.devices.isNotEmpty() || direct.connected) {
                item(key = "direct-label") {
                    SectionLabel(
                        text = "Wi-Fi Direct 设备",
                    )
                    Spacer(Modifier.height(1.dp))
                }
                items(direct.devices, key = { "direct-${it.address}" }) { device ->
                    WifiDirectRow(
                        device = device,
                        connecting = direct.connectingAddress == device.address,
                        connected = direct.connected && direct.peerAddress == device.address,
                        onClick = { wifiDirect.connect(device.address) }
                    )
                }
            }
            if (normalBleDeviceRows.isNotEmpty() || normalBlePending.isNotEmpty()) {
                item(key = "ble-label") {
                    SectionLabel(
                        text = "蓝牙设备",
                    )
                    Spacer(Modifier.height(1.dp))
                }
                items(normalBleDeviceRows, key = { "ble-${it.second.id}" }) { (conv, device) ->
                    ConversationRow(
                        conv = conv,
                        preview = previewOf(latest[conv.id]).ifBlank {
                            when {
                                bleState.connecting == device.id -> "正在连接"
                                device.connected -> "已连接"
                                else -> "未连接"
                            }
                        },
                        isPublic = false,
                        blocked = conv.id in blocked,
                        muted = conv.id in muted,
                        pinned = false,
                        onClick = {
                            ble.connect(device.id)
                            onOpenConv(conv.id)
                        },
                        onToggleBlock = {
                            scope.launch {
                                runCatching { repo.setBlocked(conv.id, conv.id !in blocked) }
                            }
                        },
                        onToggleMute = {
                            scope.launch { repo.setMuted(conv.id, conv.id !in muted) }
                        },
                        onTogglePin = {
                            scope.launch { repo.setPinned(conv.id, conv.id !in pinned) }
                        },
                        onSetAlias = {
                            aliasDraft = aliases[conv.id].orEmpty()
                            aliasTarget = conv
                        },
                        onClear = { pendingClear = conv },
                        onDelete = { pendingDelete = conv },
                        onShowInfo = { showInfo(conv) }
                    )
                }
                items(normalBlePending, key = { "blec-${it.id}" }) { conv ->
                    ConversationRow(
                        conv = conv,
                        preview = previewOf(latest[conv.id]).ifBlank { "未连接" },
                        isPublic = false,
                        blocked = conv.id in blocked,
                        muted = conv.id in muted,
                        pinned = false,
                        onClick = { onOpenConv(conv.id) },
                        onToggleBlock = {
                            scope.launch {
                                runCatching { repo.setBlocked(conv.id, conv.id !in blocked) }
                            }
                        },
                        onToggleMute = {
                            scope.launch { repo.setMuted(conv.id, conv.id !in muted) }
                        },
                        onTogglePin = {
                            scope.launch { repo.setPinned(conv.id, conv.id !in pinned) }
                        },
                        onSetAlias = {
                            aliasDraft = aliases[conv.id].orEmpty()
                            aliasTarget = conv
                        },
                        onClear = { pendingClear = conv },
                        onDelete = { pendingDelete = conv },
                        onShowInfo = { showInfo(conv) }
                    )
                }
            }
            item(key = "lan-label") {
                SectionLabel(
                    text = "局域网",
                )
                Spacer(Modifier.height(1.dp))
            }
            items(lanConvs, key = { "lan-${it.id}" }) { conv ->
                val isPublicConv = conv.id == PUBLIC_CONVERSATION_ID
                ConversationRow(
                    conv = conv,
                    preview = if (isPublicConv) {
                        previewOf(latest[PUBLIC_CONVERSATION_ID]).ifBlank { "公共频道" }
                    } else {
                        previewOf(latest[conv.id]).ifBlank { if (conv.online) "在线" else "离线" }
                    },
                    isPublic = isPublicConv,
                    blocked = !isPublicConv && conv.id in blocked,
                    muted = !isPublicConv && conv.id in muted,
                    pinned = false,
                    onClick = { onOpenConv(conv.id) },
                    onToggleBlock = {
                        if (!isPublicConv) scope.launch {
                            runCatching { repo.setBlocked(conv.id, conv.id !in blocked) }
                        }
                    },
                    onToggleMute = {
                        if (!isPublicConv) scope.launch {
                            runCatching { repo.setMuted(conv.id, conv.id !in muted) }
                        }
                    },
                    onTogglePin = {
                        if (!isPublicConv) scope.launch {
                            runCatching { repo.setPinned(conv.id, conv.id !in pinned) }
                        }
                    },
                    onSetAlias = {
                        if (!isPublicConv) {
                            aliasDraft = aliases[conv.id].orEmpty()
                            aliasTarget = conv
                        }
                    },
                    onClear = { pendingClear = conv },
                    onDelete = {
                        if (!isPublicConv) pendingDelete = conv
                    },
                    onShowInfo = {
                        if (!isPublicConv) showInfo(conv)
                    }
                )
            }
            if (online.isEmpty() && offline.isEmpty() && pinnedDevices.isEmpty() &&
                direct.devices.isEmpty() && !direct.connected &&
                bleState.devices.isEmpty() && blePending.isEmpty()
            ) {
                item(key = "empty") {
                    EmptyDevices()
                }
            }
            }
        }
        }
    }

    AnimatedVisibility(
        visible = flashOpen,
        enter = fadeIn(tween(160)) +
            expandVertically(
                expandFrom = Alignment.Top,
                animationSpec = tween(260, easing = FastOutSlowInEasing)
            ) +
            expandHorizontally(
                expandFrom = Alignment.CenterHorizontally,
                animationSpec = tween(260, easing = FastOutSlowInEasing)
            ),
        exit = fadeOut(tween(180)) +
            shrinkVertically(
                shrinkTowards = Alignment.Top,
                animationSpec = tween(220, easing = FastOutSlowInEasing)
            ) +
            shrinkHorizontally(
                shrinkTowards = Alignment.CenterHorizontally,
                animationSpec = tween(220, easing = FastOutSlowInEasing)
            ),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(top = 10.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp
        ) {
            Text(
                text = flashText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 9.dp)
            )
        }
    }
    }

    val target = pendingClear
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingClear = null },
            title = { Text("清空聊天记录") },
            text = {
                Text(
                    text = if (target.id == PUBLIC_CONVERSATION_ID) {
                        "确定要清空公共频道的聊天记录吗？"
                    } else {
                        "确定要清空和 ${target.name} 的聊天记录吗？"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingClear = null
                        scope.launch {
                            runCatching { repo.clearConversation(target.id) }
                                .onSuccess { context.toast("已清空") }
                                .onFailure { context.showError(it.message ?: "清空失败") }
                        }
                    }
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { pendingClear = null }) { Text("取消") }
            }
        )
    }

    val deleting = pendingDelete
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除此设备") },
            text = {
                Text(
                    text = "确定要删除 ${deleting.name} 吗？聊天记录会一并清除。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            runCatching { repo.deleteConversation(deleting.id) }
                                .onSuccess { context.toast("已删除") }
                                .onFailure { context.showError(it.message ?: "删除失败") }
                        }
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }

    val info = infoTarget
    if (info != null) {
        DeviceInfoDialog(
            target = info,
            repo = repo,
            historyPeak = peakSpeeds[info.id] ?: 0L,
            onDismiss = { infoTarget = null }
        )
    }

    val aliasConv = aliasTarget
    if (aliasConv != null) {
        AlertDialog(
            onDismissRequest = { aliasTarget = null },
            title = { Text("备注名") },
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
                        repo.setAlias(aliasConv.id, aliasDraft)
                        aliasTarget = null
                        context.toast(if (aliasDraft.isBlank()) "已清除备注名" else "已保存")
                    }
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { aliasTarget = null }) { Text("取消") }
            }
        )
    }

    if (connectionsOpen) {
        ModalBottomSheet(
            onDismissRequest = { connectionsOpen = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 8.dp)
            ) {
                if (hotspotSupported) {
                    ConvMenuItem(
                        icon = Icons.Rounded.WifiTethering,
                        label = "热点共享",
                        onClick = {
                            connectionsOpen = false
                            onOpenHotspot()
                        }
                    )
                    MenuDivider()
                }
                ConvMenuItem(
                    icon = Icons.Rounded.Devices,
                    label = "Wi-Fi Direct",
                    onClick = {
                        connectionsOpen = false
                        onOpenWifiDirect()
                    }
                )
                MenuDivider()
                ConvMenuItem(
                    icon = Icons.Rounded.Bluetooth,
                    label = "蓝牙广播",
                    onClick = { toggleBle() },
                    trailing = {
                        Switch(
                            checked = bleState.on,
                            onCheckedChange = { toggleBle() }
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun WifiDirectRow(
    device: WifiDirectDevice,
    connecting: Boolean,
    connected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !connecting && !connected, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarIcon(icon = Icons.Rounded.Devices, online = connected)
        Spacer(Modifier.width(12.dp))
        Text(
            text = device.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        when {
            connecting -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp
            )
            connected -> Text(
                text = "已连接",
                style = MaterialTheme.typography.labelMedium,
                color = LocalXtColors.current.success
            )
            else -> Text(
                text = "连接",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 72.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    )
}

@Composable
private fun DeviceInfoDialog(
    target: DeviceInfoTarget,
    repo: Repository,
    historyPeak: Long,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var testing by remember { mutableStateOf(false) }
    var live by remember { mutableLongStateOf(0L) }
    var testAvg by remember { mutableLongStateOf(0L) }
    var tested by remember { mutableStateOf(false) }

    val peak = when {
        testing -> live
        tested -> testAvg
        else -> historyPeak
    }
    val peakText = when {
        testing && live == 0L -> "测试中"
        peak > 0 -> "${formatSize(peak)}/s"
        else -> "未进行测试"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设备信息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoRow("名称", target.name)
                InfoRow("设备 ID", target.id)
                target.platform?.takeIf { it.isNotBlank() }?.let { InfoRow("平台", it) }
                InfoRow(
                    "状态",
                    if (target.isBle) {
                        if (target.online) "已连接" else "未连接"
                    } else {
                        if (target.online) "在线" else "离线"
                    }
                )
                if (!target.addr.isNullOrBlank() && target.port > 0) {
                    InfoRow("地址", "${target.addr}:${target.port}")
                }
                target.bleAddress?.takeIf { it.isNotBlank() }?.let { InfoRow("蓝牙地址", it) }
                target.rssi?.let { InfoRow("信号强度", "$it dBm") }
                target.trusted?.let { InfoRow("自动接受", if (it) "已开启" else "未开启") }
                if (!target.isBle) InfoRow("峰值传输速度", peakText)
                if (target.lastSeen > 0) InfoRow("最近发现", formatTime(target.lastSeen, withDate = true))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = if (target.isBle) {
            null
        } else {
            {
                TextButton(
                    enabled = !testing && target.online && !target.addr.isNullOrBlank() && target.port > 0,
                    onClick = {
                        testing = true
                        live = 0L
                        scope.launch {
                            try {
                                val result = repo.speedTest(target.id) { sample -> live = sample }
                                testAvg = result.peak
                                tested = true
                            } catch (error: Exception) {
                                context.showError(error.message ?: "测试失败")
                            } finally {
                                testing = false
                            }
                        }
                    }
                ) {
                    Text(if (testing) "测试中" else "测试")
                }
            }
        }
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun IpBadge(addr: String?) {
    val segment = addr?.substringAfterLast('.')?.takeIf { it.isNotBlank() } ?: return
    TextBadge("#$segment")
}

@Composable
private fun TextBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ConversationRow(
    conv: ConversationUi,
    preview: String,
    isPublic: Boolean,
    blocked: Boolean,
    muted: Boolean,
    pinned: Boolean,
    onClick: () -> Unit,
    onToggleBlock: () -> Unit,
    onToggleMute: () -> Unit,
    onTogglePin: () -> Unit,
    onSetAlias: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit,
    onShowInfo: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }

    Box {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(conv.id, isPublic) {
                detectTapGestures(
                    onTap = { onClick() },
                    // 局域网广播不弹长按菜单，操作都在聊天页右上角。
                    onLongPress = if (isPublic) null else { _ ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    }
                )
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarIcon(
            icon = when {
                isPublic -> Icons.Rounded.Public
                conv.id.startsWith(BLE_CONVERSATION_PREFIX) -> Icons.Rounded.Bluetooth
                else -> platformIcon(conv.platform)
            },
            online = conv.online,
            orPublic = isPublic
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = buildAnnotatedString {
                    append(conv.name)
                    conv.originalName?.takeIf { it.isNotBlank() }?.let { original ->
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
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
            if (conv.id.startsWith(BLE_CONVERSATION_PREFIX)) {
                TextBadge("BLE")
            } else {
                IpBadge(conv.addr)
            }
            if (conv.unread > 0) {
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (conv.unread > 99) "99+" else conv.unread.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
        if (menuOpen) {
        ModalBottomSheet(
            onDismissRequest = { menuOpen = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 8.dp)
        ) {
            ConvMenuItem(
                icon = Icons.Rounded.Info,
                label = "查看设备信息",
                onClick = {
                    menuOpen = false
                    onShowInfo()
                }
            )
            MenuDivider()
            ConvMenuItem(
                icon = Icons.Rounded.Edit,
                label = "备注名",
                onClick = {
                    menuOpen = false
                    onSetAlias()
                }
            )
            MenuDivider()
            ConvMenuItem(
                icon = Icons.Rounded.Block,
                label = if (blocked) "移出黑名单" else "添加黑名单",
                onClick = {
                    menuOpen = false
                    onToggleBlock()
                }
            )
            MenuDivider()
            ConvMenuItem(
                icon = Icons.Rounded.NotificationsOff,
                label = "免打扰",
                // 开关项点文字、点开关都只切换状态，都不关菜单。
                onClick = { onToggleMute() },
                trailing = { Switch(checked = muted, onCheckedChange = { onToggleMute() }) }
            )
            MenuDivider()
            ConvMenuItem(
                icon = Icons.Rounded.PushPin,
                label = "置顶会话",
                // 开关项点文字、点开关都只切换状态，都不关菜单。
                onClick = { onTogglePin() },
                trailing = { Switch(checked = pinned, onCheckedChange = { onTogglePin() }) }
            )
            MenuDivider()
            ConvMenuItem(
                icon = Icons.Rounded.DeleteSweep,
                label = "清空聊天记录",
                onClick = {
                    menuOpen = false
                    onClear()
                }
            )
            if (!conv.id.startsWith(BLE_CONVERSATION_PREFIX)) {
                MenuDivider()
                ConvMenuItem(
                    icon = Icons.Rounded.DeleteForever,
                    label = "删除此设备",
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
        }
        }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 72.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    )
}

@Composable
private fun ConvMenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    DropdownMenuItem(
        contentPadding = PaddingValues(start = 24.dp, end = 16.dp),
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        text = {
            Text(
                text = label,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        trailingIcon = trailing,
        onClick = onClick
    )
}

@Composable
private fun MenuDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    )
}

@Composable
private fun EmptyDevices() {
    val colors = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "scan")
    val rings = List(3) { index ->
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2400, easing = LinearEasing),
                initialStartOffset = StartOffset(index * 800)
            ),
            label = "ring$index"
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(180.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val maxRadius = size.minDimension / 2
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(colors.primary.copy(alpha = 0.16f), Color.Transparent),
                        center = center,
                        radius = maxRadius * 0.8f
                    ),
                    radius = maxRadius * 0.8f
                )
                rings.forEach { ring ->
                    val p = ring.value
                    drawCircle(
                        color = colors.primary.copy(alpha = (1f - p) * 0.3f),
                        radius = maxRadius * (0.4f + p * 0.58f),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.WifiTethering,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(42.dp)
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "暂无设备",
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurfaceVariant
        )
    }
}
