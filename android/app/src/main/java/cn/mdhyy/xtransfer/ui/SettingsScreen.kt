package cn.mdhyy.xtransfer.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Feedback
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.mdhyy.xtransfer.XlanApp
import cn.mdhyy.xtransfer.data.AppSettings
import cn.mdhyy.xtransfer.data.DeviceInfo
import cn.mdhyy.xtransfer.data.ONLINE_WINDOW_SEC
import cn.mdhyy.xtransfer.data.PermissionPrompts
import cn.mdhyy.xtransfer.data.Repository
import cn.mdhyy.xtransfer.data.ServiceStatus
import cn.mdhyy.xtransfer.data.SettingsStore
import cn.mdhyy.xtransfer.data.StorageAccess
import cn.mdhyy.xtransfer.service.XlanService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class ConfirmAction { CACHE, OFFLINE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repo: Repository,
    settings: AppSettings,
    store: SettingsStore,
    info: DeviceInfo?,
    service: ServiceStatus,
    offline: Boolean,
    sentFiles: Int,
    receivedFiles: Int,
    deviceCount: Int,
    onBack: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val context = LocalContext.current
    val pageScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var permTick by remember { mutableIntStateOf(0) }
    val refreshPerms: () -> Unit = { permTick++ }
    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshPerms() }
    val notifyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPerms() }
    val nearbyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPerms() }
    // 授权页回来时刷新一下状态，系统设置里改的权限也能及时反映。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshPerms()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val requestStorage: () -> Unit = {
        PermissionPrompts.mark(context, PermissionPrompts.STORAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
            storageLauncher.launch(
                arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                )
            )
        }
    }

    var nameDraft by remember { mutableStateOf("") }
    var showRename by remember { mutableStateOf(false) }
    var savingName by remember { mutableStateOf(false) }
    var cleaning by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ConfirmAction?>(null) }
    var showBlocked by remember { mutableStateOf(false) }
    var showClearChats by remember { mutableStateOf(false) }
    var showFeedback by remember { mutableStateOf(false) }
    var showPermissions by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var checks by remember { mutableStateOf<List<SelfCheck>?>(null) }
    var chatSelected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var clearingChats by remember { mutableStateOf(false) }
    var cacheBytes by remember { mutableStateOf(0L) }

    val notify: (String) -> Unit = { message ->
        pageScope.launch { snackbarHostState.showSnackbar(message) }
    }

    val ble = (context.applicationContext as XlanApp).ble
    val bleState by ble.state.collectAsStateWithLifecycle()
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
    val bleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        refreshPerms()
        if (result.values.all { it }) startBle() else notify("需要蓝牙权限")
    }
    val toggleBle: () -> Unit = {
        when {
            bleState.on -> ble.disable()
            !ble.supported -> notify("此设备不支持蓝牙")
            !ble.granted() -> bleLauncher.launch(ble.permissions.toTypedArray())
            else -> startBle()
        }
    }

    val requestNotify: () -> Unit = {
        PermissionPrompts.mark(context, PermissionPrompts.NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            }.onFailure { notify("无法打开通知设置") }
        }
    }
    val requestNearby: () -> Unit = {
        PermissionPrompts.mark(context, PermissionPrompts.NEARBY)
        nearbyLauncher.launch(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.NEARBY_WIFI_DEVICES
            } else {
                Manifest.permission.ACCESS_FINE_LOCATION
            }
        )
    }
    val requestBle: () -> Unit = {
        PermissionPrompts.mark(context, PermissionPrompts.BLUETOOTH)
        bleLauncher.launch(ble.permissions.toTypedArray())
    }
    val requestInstall: () -> Unit = {
        PermissionPrompts.mark(context, PermissionPrompts.INSTALL)
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${context.packageName}"))
            )
        }.onFailure { notify("请到系统设置中允许安装应用") }
    }
    val storageGranted = remember(permTick) { StorageAccess.granted(context) }
    val notifyGranted = remember(permTick) { notificationPermissionGranted(context) }
    val nearbyGranted = remember(permTick) { nearbyPermissionGranted(context) }
    val bleGranted = remember(permTick) { ble.granted() }
    val installGranted = remember(permTick) { installPermissionGranted(context) }
    val openPermissionPage: (Intent) -> Unit = { intent ->
        runCatching { context.startActivity(intent) }.onFailure { notify("无法打开系统设置") }
    }
    val openStorageSettings: () -> Unit = {
        openPermissionPage(storageSettingsIntent(context))
    }
    val openNotifySettings: () -> Unit = {
        openPermissionPage(notifySettingsIntent(context))
    }
    val openNearbySettings: () -> Unit = {
        openPermissionPage(nearbySettingsIntent(context))
    }
    val openBleSettings: () -> Unit = {
        openPermissionPage(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
        )
    }
    val openInstallSettings: () -> Unit = {
        openPermissionPage(installSettingsIntent(context))
    }
    val storageDenied = remember(permTick) {
        !storageGranted && PermissionPrompts.asked(context, PermissionPrompts.STORAGE)
    }
    val notifyDenied = remember(permTick) {
        !notifyGranted && PermissionPrompts.asked(context, PermissionPrompts.NOTIFICATIONS)
    }
    val nearbyDenied = remember(permTick) {
        !nearbyGranted && PermissionPrompts.asked(context, PermissionPrompts.NEARBY)
    }
    val bleDenied = remember(permTick) {
        !bleGranted && PermissionPrompts.asked(context, PermissionPrompts.BLUETOOTH)
    }
    val installDenied = remember(permTick) {
        !installGranted && PermissionPrompts.asked(context, PermissionPrompts.INSTALL)
    }
    val missingPerms = listOf(storageGranted, notifyGranted, nearbyGranted, bleGranted, installGranted)
        .count { !it }

    val recvDir by repo.recvDirPath.collectAsStateWithLifecycle()
    val peers by repo.peers.collectAsStateWithLifecycle()
    val restarting by repo.restarting.collectAsStateWithLifecycle()
    LaunchedEffect(recvDir) {
        cacheBytes = withContext(Dispatchers.IO) { calcCacheBytes(context, recvDir) }
    }

    fun runSelfCheck(): List<SelfCheck> = buildList {
        val lanInfo = repo.lan.value.info
        add(
            SelfCheck(
                "局域网",
                lanInfo != null,
                lanInfo?.let { "${it.name} ${it.cidr}" } ?: "没有局域网连接"
            )
        )
        add(
            SelfCheck(
                "服务",
                service.running,
                if (service.running) service.base else (service.error ?: "未运行")
            )
        )
        val storage = StorageAccess.granted(context)
        add(SelfCheck("存储权限", storage, if (storage) "已授权" else "未授权"))
        val recvError = writeTest(File(recvDir))
        add(SelfCheck("收件目录", recvError == null, recvError ?: recvDir))
        val dataError = writeTest(context.filesDir)
        add(SelfCheck("数据目录", dataError == null, dataError ?: context.filesDir.absolutePath))
        // 服务端保存的是发现过的全部设备，只有最近 12 秒还在广播的才算在线。
        val nowSec = System.currentTimeMillis() / 1000
        val online = peers.count {
            it.id != info?.id && it.addr != null && nowSec - it.lastSeen <= ONLINE_WINDOW_SEC
        }
        add(SelfCheck("在线设备", true, "$online 台"))
    }

    val conversations by repo.conversations.collectAsStateWithLifecycle()
    val allMessages by repo.messages.collectAsStateWithLifecycle()
    // 只有真正存过消息的对话才有记录可清。
    val chatConversations = remember(conversations, allMessages) {
        val ids = allMessages.mapTo(HashSet()) { it.conversationId }
        conversations.filter { it.id in ids }
    }
    val pickRecvDir = rememberDirectoryPicker(initialPath = recvDir) { path ->
        pageScope.launch {
            runCatching { repo.setRecvDir(path) }
                .onSuccess { notify("已保存") }
                .onFailure { notify(it.message ?: "设置失败") }
        }
    }

    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.theme) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            MediumTopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LanOfflineBox(offline = offline, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 4.dp,
                bottom = innerPadding.calculateBottomPadding() + 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                ProfileCard(
                    name = info?.name ?: "未命名",
                    onClick = {
                        nameDraft = info?.name.orEmpty()
                        showRename = true
                    }
                )
            }

            item {
                TransferStatsCard(
                    sent = sentFiles,
                    received = receivedFiles,
                    devices = deviceCount
                )
            }

            item { SectionTitle("连接") }
            item {
                SettingsGroup {
                    ServiceStatusRow(
                        running = service.running,
                        restarting = restarting,
                        detail = if (service.running) null else service.error,
                        checking = checking,
                        onRestart = {
                            XlanService.restart(context)
                            notify("正在重启服务")
                        },
                        onCheck = {
                            if (checking) return@ServiceStatusRow
                            checking = true
                            pageScope.launch {
                                checks = withContext(Dispatchers.IO) { runSelfCheck() }
                                checking = false
                            }
                        }
                    )
                    SettingDivider()
                    SettingRow(
                        icon = Icons.Rounded.Bluetooth,
                        title = "蓝牙广播",
                        description = "蓝牙 ${bleState.version.ifBlank { "未知" }} · ${if (bleState.address.isBlank()) "MAC地址获取失败" else "MAC ${bleState.address}"}",
                        onClick = toggleBle,
                        trailing = {
                            SettingSwitch(
                                checked = bleState.on,
                                onCheckedChange = { toggleBle() }
                            )
                        }
                    )
                }
            }

            item { SectionTitle("外观") }
            item {
                SettingsGroup {
                    ThemeModeRow(
                        mode = settings.theme,
                        onModeChange = { store.update(theme = it) }
                    )
                    SettingDivider()
                    SeedColorRow(
                        seed = settings.seed,
                        dark = dark,
                        onSelect = { store.update(seed = it) }
                    )
                    SettingDivider()
                    SettingRow(
                        icon = Icons.Rounded.FontDownload,
                        title = "MiSans 字体",
                        singleRow = true,
                        trailing = {
                            SettingSwitch(
                                checked = settings.misans,
                                onCheckedChange = { store.update(misans = it) }
                            )
                        }
                    )
                }
            }

            item { SectionTitle("通用") }
            item {
                SettingsGroup {
                    SettingRow(
                        icon = Icons.Rounded.Notifications,
                        title = "消息通知",
                        singleRow = true,
                        trailing = {
                            SettingSwitch(
                                checked = settings.notifications,
                                onCheckedChange = { store.update(notifications = it) }
                            )
                        }
                    )
                    SettingDivider()
                    SettingRow(
                        icon = Icons.AutoMirrored.Rounded.Send,
                        title = "回车发送",
                        description = "按回车键发送消息",
                        singleRow = true,
                        trailing = {
                            SettingSwitch(
                                checked = settings.enterToSend,
                                onCheckedChange = { store.update(enterToSend = it) }
                            )
                        }
                    )
                    SettingDivider()
                    SettingRow(
                        icon = Icons.Rounded.Visibility,
                        title = "快速预览",
                        description = "点击文件先在应用内预览",
                        singleRow = true,
                        trailing = {
                            SettingSwitch(
                                checked = settings.quickPreview,
                                onCheckedChange = { store.update(quickPreview = it) }
                            )
                        }
                    )
                }
            }

            item { SectionTitle("数据") }
            item {
                SettingsGroup {
                    SettingRow(
                        icon = Icons.Rounded.LockOpen,
                        title = "所需权限",
                        singleRow = true,
                        onClick = { showPermissions = true },
                        trailing = {
                            StatusPill(
                                text = if (missingPerms == 0) "完整权限" else "未授权${missingPerms}项",
                                color = if (missingPerms == 0) LocalXtColors.current.success
                                else MaterialTheme.colorScheme.error
                            )
                        }
                    )
                    SettingDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Download,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(
                                text = "接收目录",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        pageScope.launch {
                                            runCatching { repo.resetRecvDir() }
                                                .onSuccess { notify("已恢复默认") }
                                                .onFailure { notify(it.message ?: "操作失败") }
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Restore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "恢复默认",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { pickRecvDir() }
                                .padding(start = 40.dp, end = 8.dp, top = 4.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = recvDir,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    SettingDivider()
                    SettingRow(
                        icon = Icons.Rounded.Block,
                        title = "设备黑名单",
                        singleRow = true,
                        trailingText = if (settings.blocked.isEmpty()) "无" else settings.blocked.size.toString(),
                        showChevron = true,
                        onClick = { showBlocked = true }
                    )
                    SettingDivider()
                    SettingRow(
                        icon = Icons.Rounded.DeleteSweep,
                        title = "清理缓存",
                        description = "当前缓存 ${formatSize(cacheBytes).ifBlank { "0 B" }}",
                        trailing = {
                            if (cleaning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        },
                        onClick = { confirm = ConfirmAction.CACHE }
                    )
                    SettingDivider()
                    SettingRow(
                        icon = Icons.Rounded.Forum,
                        title = "清理聊天记录",
                        showChevron = true,
                        onClick = {
                            chatSelected = emptySet()
                            showClearChats = true
                        }
                    )
                    SettingDivider()
                    SettingRow(
                        icon = Icons.Rounded.Devices,
                        title = "清理离线设备",
                        showChevron = true,
                        onClick = { confirm = ConfirmAction.OFFLINE }
                    )
                }
            }

            item { SectionTitle("其他") }
            item {
                SettingsGroup {
                    SettingRow(
                        icon = Icons.Rounded.BatterySaver,
                        title = "电池优化",
                        description = "避免后台被系统清理",
                        showChevron = true,
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }.onFailure { notify("无法打开设置") }
                        }
                    )
                    SettingDivider()
                    SettingRow(
                        icon = Icons.Rounded.Feedback,
                        title = "建议与反馈",
                        showChevron = true,
                        onClick = { showFeedback = true }
                    )
                    SettingDivider()
                    SettingRow(
                        icon = Icons.Rounded.Info,
                        title = "关于",
                        showChevron = true,
                        onClick = onOpenAbout
                    )
                }
            }
        }
        }
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { if (!savingName) showRename = false },
            title = { Text("设备名") },
            text = {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { if (it.length <= 15) nameDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !savingName && nameDraft.trim().isNotEmpty(),
                    onClick = {
                        savingName = true
                        pageScope.launch {
                            runCatching { repo.setDeviceName(nameDraft) }
                                .onSuccess {
                                    showRename = false
                                    notify("已保存")
                                }
                                .onFailure { notify(it.message ?: "保存失败") }
                            savingName = false
                        }
                    }
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(
                    enabled = !savingName,
                    onClick = { showRename = false }
                ) { Text("取消") }
            }
        )
    }

    val pending = confirm
    if (pending != null) {
        val title = when (pending) {
            ConfirmAction.CACHE -> "清理缓存"
            ConfirmAction.OFFLINE -> "清理离线设备"
        }
        val body = when (pending) {
            ConfirmAction.CACHE -> "将清除已接收的临时文件。"
            ConfirmAction.OFFLINE -> "将删除所有离线设备的会话和聊天记录。"
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(
                    enabled = !cleaning,
                    onClick = {
                        confirm = null
                        when (pending) {
                            ConfirmAction.CACHE -> {
                                cleaning = true
                                pageScope.launch {
                                    runCatching { repo.cleanCache() }
                                        .onSuccess {
                                            // 缩略图、相机临时文件也一并清掉。
                                            runCatching {
                                                context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                                            }
                                            cacheBytes = withContext(Dispatchers.IO) { calcCacheBytes(context, recvDir) }
                                            notify("已清理 ${formatSize(it.freedBytes).ifBlank { "0 B" }}")
                                        }
                                        .onFailure { notify(it.message ?: "清理失败") }
                                    cleaning = false
                                }
                            }
                            ConfirmAction.OFFLINE -> {
                                cleaning = true
                                pageScope.launch {
                                    runCatching { repo.deleteOfflineDevices() }
                                        .onSuccess { count ->
                                            notify(if (count > 0) "已清理 $count 个离线设备" else "没有离线设备")
                                        }
                                        .onFailure { notify(it.message ?: "清理失败") }
                                    cleaning = false
                                }
                            }
                        }
                    }
                ) { Text("清理") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text("取消") }
            }
        )
    }

    if (showBlocked) {
        val blockedList = settings.blocked.toList()
        AlertDialog(
            onDismissRequest = { showBlocked = false },
            title = { Text("设备黑名单") },
            text = {
                if (blockedList.isEmpty()) {
                    Text("暂无")
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        blockedList.forEachIndexed { index, id ->
                            val peer = peers.firstOrNull { it.id == id }
                            if (index > 0) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            Row(
                                modifier = Modifier.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                AvatarIcon(icon = platformIcon(peer?.platform), online = false)
                                Text(
                                    text = peer?.name?.takeIf { it.isNotBlank() } ?: id,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                TextButton(
                                    onClick = {
                                        pageScope.launch { repo.setBlocked(id, false) }
                                    }
                                ) { Text("移除") }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBlocked = false }) { Text("关闭") }
            }
        )
    }

    if (showClearChats) {
        AlertDialog(
            onDismissRequest = { if (!clearingChats) showClearChats = false },
            title = { Text("清理聊天记录") },
            text = {
                if (chatConversations.isEmpty()) {
                    Text("暂无聊天记录")
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        chatConversations.forEach { conv ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        chatSelected = if (conv.id in chatSelected) {
                                            chatSelected - conv.id
                                        } else {
                                            chatSelected + conv.id
                                        }
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = conv.id in chatSelected,
                                    onCheckedChange = { checked ->
                                        chatSelected = if (checked) {
                                            chatSelected + conv.id
                                        } else {
                                            chatSelected - conv.id
                                        }
                                    }
                                )
                                Text(
                                    text = conv.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = chatSelected.isNotEmpty() && !clearingChats,
                    onClick = {
                        val ids = chatSelected.toList()
                        clearingChats = true
                        pageScope.launch {
                            runCatching {
                                ids.forEach { repo.clearConversation(it) }
                            }
                                .onSuccess {
                                    showClearChats = false
                                    notify("已清理")
                                }
                                .onFailure { notify(it.message ?: "清理失败") }
                            clearingChats = false
                        }
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(
                    enabled = !clearingChats,
                    onClick = { showClearChats = false }
                ) { Text("取消") }
            }
        )
    }

    if (showFeedback) {
        AlertDialog(
            onDismissRequest = { showFeedback = false },
            title = { Text("建议与反馈") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "腾讯频道（QQ 频道）",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "反馈使用中遇到的问题、提出功能建议，新版本也会在频道里发布。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "频道号：pd40502482",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "频道链接：pd.qq.com/s/gtvdapxfn",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFeedback = false
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://pd.qq.com/s/gtvdapxfn?b=9")
                                )
                            )
                        }.onFailure { notify("无法打开链接") }
                    }
                ) { Text("加入频道") }
            },
            dismissButton = {
                TextButton(onClick = { showFeedback = false }) { Text("关闭") }
            }
        )
    }

    if (showPermissions) {
        AlertDialog(
            onDismissRequest = { showPermissions = false },
            title = { Text("所需权限") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PermissionEntry(
                        "存储权限", storageGranted, storageDenied, requestStorage, openStorageSettings
                    )
                    PermissionEntry(
                        "通知权限", notifyGranted, notifyDenied, requestNotify, openNotifySettings
                    )
                    PermissionEntry(
                        "附近设备", nearbyGranted, nearbyDenied, requestNearby, openNearbySettings
                    )
                    PermissionEntry(
                        "蓝牙", bleGranted, bleDenied, requestBle, openBleSettings
                    )
                    PermissionEntry(
                        "安装应用", installGranted, installDenied, requestInstall, openInstallSettings
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPermissions = false }) { Text("关闭") }
            }
        )
    }

    val result = checks
    if (result != null) {
        AlertDialog(
            onDismissRequest = { checks = null },
            title = { Text("自检") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    result.forEach { check ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = check.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(80.dp)
                            )
                            Text(
                                text = check.detail,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (check.ok) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { checks = null }) { Text("关闭") }
            }
        )
    }
}

private data class SelfCheck(val name: String, val ok: Boolean, val detail: String)

private fun writeTest(dir: File): String? {
    if (!dir.isDirectory && !dir.mkdirs()) return "目录建不出来"
    return runCatching {
        File.createTempFile(".doctor-", ".tmp", dir).delete()
        null
    }.getOrElse { it.message ?: "不可写" }
}

@Composable
private fun ProfileCard(name: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Smartphone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TransferStatsCard(sent: Int, received: Int, devices: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(
                "发送文件" to sent,
                "接收文件" to received,
                "连接设备" to devices
            ).forEach { (label, value) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = value.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ServiceStatusRow(
    running: Boolean,
    restarting: Boolean,
    detail: String?,
    checking: Boolean,
    onRestart: () -> Unit,
    onCheck: () -> Unit
) {
    val color = when {
        restarting -> MaterialTheme.colorScheme.primary
        running -> LocalXtColors.current.success
        else -> MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                if (restarting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = color
                    )
                } else {
                    Icon(
                        imageVector = if (running) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "xlan 服务",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val sub = detail?.takeIf { it.isNotBlank() }
                if (sub != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = onCheck,
                enabled = !checking,
                modifier = Modifier.height(34.dp),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                if (checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(5.dp))
                Text("自检", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = onRestart,
                enabled = !restarting,
                modifier = Modifier.height(34.dp),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.RestartAlt,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text("重启", style = MaterialTheme.typography.labelMedium)
            }
        }
}

@Composable
private fun PermissionEntry(
    name: String,
    granted: Boolean,
    denied: Boolean,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        when {
            granted -> StatusPill(
                "已授予", LocalXtColors.current.success, onClick = onOpenSettings
            )
            denied -> StatusPill(
                "未授予", MaterialTheme.colorScheme.error, onClick = onOpenSettings
            )
            else -> StatusPill("授予", MaterialTheme.colorScheme.primary, onClick = onGrant)
        }
    }
}

private fun storageSettingsIntent(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    } else {
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
    }

private fun notifySettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

private fun nearbySettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:${context.packageName}"))

private fun installSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
        .setData(Uri.parse("package:${context.packageName}"))

private fun notificationPermissionGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

private fun nearbyPermissionGranted(context: Context): Boolean {
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.NEARBY_WIFI_DEVICES
    } else {
        Manifest.permission.ACCESS_FINE_LOCATION
    }
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

private fun installPermissionGranted(context: Context): Boolean =
    context.packageManager.canRequestPackageInstalls()

@Composable
private fun StatusPill(text: String, color: Color, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1
        )
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 4.dp)
    )
}

@Composable
internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp), content = content)
    }
}

@Composable
internal fun SettingRow(
    icon: ImageVector,
    title: String,
    description: String? = null,
    enabled: Boolean = true,
    showChevron: Boolean = false,
    trailingText: String? = null,
    singleRow: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (singleRow) Modifier.heightIn(min = 52.dp) else Modifier)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = if (singleRow) 0.dp else 14.dp,
                bottom = if (singleRow) 0.dp else 14.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (description != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (trailingText != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = trailingText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
            )
        }
        if (showChevron) {
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
            )
        }
        if (trailing != null) {
            trailing()
        }
    }
}

@Composable
private fun SettingSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        thumbContent = {
            Icon(
                imageVector = if (checked) Icons.Rounded.Check else Icons.Rounded.Close,
                contentDescription = null,
                modifier = Modifier.size(SwitchDefaults.IconSize)
            )
        }
    )
}

@Composable
internal fun SettingDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 56.dp, end = 16.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeModeRow(mode: String, onModeChange: (String) -> Unit) {
    val options = listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色")
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Palette,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = "主题模式",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(12.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = mode == value,
                    onClick = { onModeChange(value) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = options.size
                    ),
                    modifier = Modifier.weight(1f),
                    icon = {}
                ) {
                    Text(label, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SeedColorRow(seed: String, dark: Boolean, onSelect: (String) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Palette,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = "主题色",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.padding(start = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            for ((name, _) in seedNames) {
                val selected = name == seed
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(seedColor(name, dark))
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.onBackground
                            else MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape
                        )
                        .clickable { onSelect(name) }
                )
            }
        }
    }
}

// 缩略图/相机临时文件在应用缓存目录，分片在收件目录的 .parts 里。
private fun calcCacheBytes(context: android.content.Context, recvDir: String): Long {
    fun sizeOf(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    val parts = if (recvDir.isBlank()) null else File(recvDir, ".parts")
    return sizeOf(context.cacheDir) + (parts?.let { sizeOf(it) } ?: 0L)
}
