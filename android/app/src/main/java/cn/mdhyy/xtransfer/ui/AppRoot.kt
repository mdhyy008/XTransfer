package cn.mdhyy.xtransfer.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.DeviceHub
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cn.mdhyy.xtransfer.XlanApp
import cn.mdhyy.xtransfer.data.BLE_CONVERSATION_PREFIX
import cn.mdhyy.xtransfer.data.HotspotState
import cn.mdhyy.xtransfer.data.MessageEntity
import cn.mdhyy.xtransfer.data.PUBLIC_CONVERSATION_ID
import cn.mdhyy.xtransfer.data.RELAY_CONVERSATION_PREFIX
import cn.mdhyy.xtransfer.data.Repository
import cn.mdhyy.xtransfer.service.XlanService
import java.io.File
import java.net.URLDecoder

private const val EXIT_WINDOW_MS = 2000L
private const val ANIM_MS = 350

private data class HomeTab(val label: String, val icon: ImageVector)

private val HOME_TABS = listOf(
    HomeTab("会话", Icons.Rounded.ChatBubbleOutline),
    HomeTab("文件管理", Icons.Outlined.Folder),
    HomeTab("内网分发", Icons.Rounded.DeviceHub),
    HomeTab("小程序", Icons.Rounded.Apps)
)

data class ShareNotice(val files: Int, val folders: Int)

@Composable
fun AppRoot(
    repo: Repository,
    pendingConv: String?,
    onConsumePending: () -> Unit,
    pendingPage: String?,
    onConsumePage: () -> Unit,
    pendingLocalFile: String?,
    onConsumeLocalFile: () -> Unit,
    shareNotice: ShareNotice?,
    onConsumeShareNotice: () -> Unit
) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val homePager = rememberPagerState(pageCount = { HOME_TABS.size })

    val conversations by repo.conversations.collectAsStateWithLifecycle()
    val messages by repo.messages.collectAsStateWithLifecycle()
    val service by repo.service.collectAsStateWithLifecycle()
    val settings by repo.settings.collectAsStateWithLifecycle()
    val info by repo.info.collectAsStateWithLifecycle()
    val peers by repo.peers.collectAsStateWithLifecycle()
    val bucket by repo.bucket.collectAsStateWithLifecycle()
    val bucketFailed by repo.bucketFailed.collectAsStateWithLifecycle()
    val recvDirPath by repo.recvDirPath.collectAsStateWithLifecycle()
    val lan by repo.lan.collectAsStateWithLifecycle()
    val lanMissing = lan.known && lan.info == null
    // 没网时那层模糊提示点一下就能收掉；网络回来后重新计。
    var offlineDismissed by remember { mutableStateOf(false) }

    var bucketOpen by remember { mutableStateOf(false) }
    // 预览页同一时间只留一个，连点文件时不会叠出好几层。
    var lastMediaNavAt by remember { mutableLongStateOf(0L) }
    val xlanApp = context.applicationContext as XlanApp
    val hotspotState by xlanApp.hotspot.state.collectAsStateWithLifecycle()
    // 热点开着时本机有自己的网段，对端能连上，不算断网。
    val hotspotLinkActive = hotspotState is HotspotState.Running ||
        hotspotState is HotspotState.Starting
    val offline = lanMissing && !hotspotLinkActive
    LaunchedEffect(offline) {
        if (!offline) offlineDismissed = false
    }
    val offlineActive = offline && !offlineDismissed

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            val items = uris.mapNotNull { repo.makeFileItem(it) }
            if (items.isEmpty()) {
                context.showError("无法读取所选文件")
            } else {
                repo.addBucket(items)
                context.toast("已加入 ${items.size} 项")
            }
        }
    }
    fun addPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        val items = uris.mapNotNull { repo.makeFileItem(it) }
        if (items.isEmpty()) {
            context.showError("无法读取所选文件")
        } else {
            repo.addBucket(items)
            context.toast("已加入 ${items.size} 项")
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> addPicked(uris) }
    val apkPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> addPicked(uris) }
    val pickBucketFolder = rememberDirectoryPicker { path ->
        val item = repo.makeFolderItem(File(path))
        if (item == null) {
            context.showError("无法读取该文件夹")
        } else {
            repo.addBucket(listOf(item))
            context.toast("已加入暂存桶")
        }
    }

    val latest = remember(messages) {
        val map = HashMap<String, MessageEntity>()
        for (message in messages) {
            val old = map[message.conversationId]
            if (old == null || message.createdAt >= old.createdAt) map[message.conversationId] = message
        }
        map
    }

    val peakSpeeds = remember(messages) {
        val map = HashMap<String, Long>()
        for (message in messages) {
            if (message.speedBps > (map[message.conversationId] ?: 0L)) {
                map[message.conversationId] = message.speedBps
            }
        }
        map
    }

    LaunchedEffect(pendingConv) {
        val target = pendingConv
        if (!target.isNullOrBlank()) {
            nav.navigate("detail/${Uri.encode(target)}") { launchSingleTop = true }
            onConsumePending()
        }
    }

    // 桌面快捷方式：直接落到对应的首页分页或暂存桶。
    LaunchedEffect(pendingPage) {
        when (pendingPage) {
            "bucket" -> bucketOpen = true
            "files" -> homePager.animateScrollToPage(
                1,
                animationSpec = tween(320, easing = FastOutSlowInEasing)
            )
            "share" -> homePager.animateScrollToPage(
                2,
                animationSpec = tween(320, easing = FastOutSlowInEasing)
            )
        }
        if (pendingPage != null) onConsumePage()
    }

    // 小组件点了某个文件：能内嵌预览的直接进预览页，其余交给系统打开。
    LaunchedEffect(pendingLocalFile) {
        val path = pendingLocalFile ?: return@LaunchedEffect
        val file = File(path)
        if (!file.exists()) {
            context.showError("文件不存在")
        } else {
            when (previewKindOf(file.name)) {
                PreviewKind.IMAGE, PreviewKind.VIDEO, PreviewKind.AUDIO ->
                    nav.navigate("localmedia/${Uri.encode(path)}") { launchSingleTop = true }
                else -> openFileExternally(context, path, file.name)
            }
        }
        onConsumeLocalFile()
    }

    val backdrop = Color.Black
    NavHost(
        navController = nav,
        startDestination = "list",
        modifier = Modifier
            .fillMaxSize()
            .background(backdrop),
        enterTransition = {
            slideInHorizontally(tween(ANIM_MS, easing = FastOutSlowInEasing)) { it }
        },
        exitTransition = {
            slideOutHorizontally(tween(ANIM_MS, easing = FastOutSlowInEasing)) { -it / 3 }
        },
        popEnterTransition = {
            slideInHorizontally(tween(ANIM_MS, easing = FastOutSlowInEasing)) { -it / 3 }
        },
        popExitTransition = {
            slideOutHorizontally(tween(ANIM_MS, easing = FastOutSlowInEasing)) { it }
        }
    ) {
        composable("list") {
            Column(modifier = Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = homePager,
                    userScrollEnabled = false,
                    modifier = Modifier.weight(1f)
                ) { index ->
                    if (index == 0) {
                        ListScreen(
                            repo = repo,
                            conversations = conversations,
                            latest = latest,
                            peers = peers,
                            lanInfo = lan.info,
                            bucketCount = bucket.size,
                            blocked = settings.blocked,
                            aliases = settings.aliases,
                            muted = settings.muted,
                            pinned = settings.pinned,
                            peakSpeeds = peakSpeeds,
                            lanOffline = offline,
                            scope = scope,
                            onOpenConv = { id -> nav.navigate("detail/${Uri.encode(id)}") },
                            onOpenSettings = { nav.navigate("settings") },
                            onOpenBucket = { bucketOpen = true },
                            hotspot = xlanApp.hotspot,
                            wifiDirect = xlanApp.wifiDirect,
                            ble = xlanApp.ble,
                            relay = xlanApp.relay,
                            onOpenHotspot = { nav.navigate("hotspot") },
                            onOpenWifiDirect = { nav.navigate("wifidirect") }
                        )
                    } else if (index == 1) {
                        FileManagerScreen(
                            recvDir = recvDirPath,
                            refreshKey = messages.size,
                            quickPreview = settings.quickPreview,
                            onOpenMedia = { path, _ ->
                                nav.navigate("localmedia/${Uri.encode(path)}") { launchSingleTop = true }
                            }
                        )
                    } else if (index == 2) {
                        ShareScreen(
                            items = bucket,
                            share = xlanApp.share,
                            onAddFiles = { filePicker.launch(arrayOf("*/*")) },
                            onAddFolder = { pickBucketFolder() },
                            onAddImages = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            onAddApks = {
                                apkPicker.launch(arrayOf("application/vnd.android.package-archive"))
                            },
                            onRemove = { repo.removeBucket(it) }
                        )
                    } else {
                        EmptyState(
                            icon = Icons.Rounded.Apps,
                            text = "暂无小程序",
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                        )
                    }
                }
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    windowInsets = WindowInsets.systemBars.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                    )
                ) {
                    HOME_TABS.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = homePager.currentPage == index,
                            onClick = {
                                scope.launch {
                                    homePager.animateScrollToPage(
                                        index,
                                        animationSpec = tween(320, easing = FastOutSlowInEasing)
                                    )
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
        composable(
            route = "detail/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) { entry ->
            val raw = entry.arguments?.getString("id").orEmpty()
            val id = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            val conv = conversations.firstOrNull { it.id == id }
            DetailScreen(
                repo = repo,
                convId = id,
                conv = conv,
                messages = messages.filter { it.conversationId == id },
                service = service,
                peer = peers.firstOrNull { it.id == id },
                publicNotify = settings.publicNotifications,
                quickPreview = settings.quickPreview,
                enterToSend = settings.enterToSend,
                bucketItems = bucket,
                offline = offlineActive,
                lanOnline = lan.info != null,
                scope = scope,
                onBack = { nav.popBackStack() },
                onSetTrust = { trusted ->
                    scope.launch {
                        runCatching { repo.setTrust(id, trusted) }
                            .onSuccess { context.toast(if (trusted) "已开启自动接受" else "已关闭自动接受") }
                            .onFailure { context.showError(it.message ?: "操作失败") }
                    }
                },
                onSetPublicNotify = { repo.settingsStore().update(publicNotifications = it) },
                alias = settings.aliases[id].orEmpty(),
                onSetAlias = { repo.setAlias(id, it) },
                muted = id in settings.muted,
                onSetMuted = { repo.setMuted(id, it) },
                onOpenBucket = { bucketOpen = true },
                onSendBucket = {
                    when {
                        bucket.isEmpty() -> Unit
                        !context.ensureStorageAccess() -> Unit
                        repo.sendBucket(id, bucket) -> Unit
                        else -> context.showError("设备不在线")
                    }
                },
                onOpenMedia = { message ->
                    val now = SystemClock.elapsedRealtime()
                    val alreadyPreviewing =
                        nav.currentDestination?.route?.startsWith("media/") == true
                    if (!alreadyPreviewing && now - lastMediaNavAt > 600) {
                        lastMediaNavAt = now
                        nav.navigate("media/${Uri.encode(message.id)}")
                    }
                },
                onOpenApps = { nav.navigate("apps/${Uri.encode(id)}") }
            )
        }
        composable(
            route = "apps/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) { entry ->
            val raw = entry.arguments?.getString("id").orEmpty()
            val id = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            AppListScreen(
                repo = repo,
                convId = id,
                onBack = { nav.popBackStack() }
            )
        }
        composable(
            route = "media/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) { entry ->
            val raw = entry.arguments?.getString("id").orEmpty()
            val id = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            MediaScreen(
                message = messages.firstOrNull { it.id == id },
                onBack = { nav.popBackStack() }
            )
        }
        composable(
            route = "localmedia/{path}",
            arguments = listOf(navArgument("path") { type = NavType.StringType })
        ) { entry ->
            val raw = entry.arguments?.getString("path").orEmpty()
            val path = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            MediaScreen(
                message = MessageEntity(
                    id = path,
                    conversationId = "",
                    direction = "in",
                    kind = "file",
                    channel = "dm",
                    fileName = path.substringAfterLast('/'),
                    fileSize = File(path).length(),
                    localPath = path,
                    status = "done",
                    createdAt = 0,
                    updatedAt = 0
                ),
                onBack = { nav.popBackStack() }
            )
        }
        composable("settings") {
            SettingsScreen(
                repo = repo,
                settings = settings,
                store = repo.settingsStore(),
                info = info,
                service = service,
                offline = offlineActive,
                sentFiles = messages.count { it.direction == "out" && it.kind != "text" },
                receivedFiles = messages.count { it.direction == "in" && it.kind != "text" },
                deviceCount = conversations.count { it.id != PUBLIC_CONVERSATION_ID },
                onBack = { nav.popBackStack() },
                onOpenAbout = { nav.navigate("about") }
            )
        }
        composable("about") {
            AboutScreen(onBack = { nav.popBackStack() })
        }
        composable("hotspot") {
            HotspotScreen(onBack = { nav.popBackStack() })
        }
        composable("wifidirect") {
            WifiDirectScreen(onBack = { nav.popBackStack() })
        }
    }

    val entry by nav.currentBackStackEntryAsState()
    // 只有真正显示离线模糊层的页面才需要这层点击收掉；热点、Wi-Fi Direct 等页面没有模糊层，
    // 留着这层会吞掉进入页面后的第一次点击。
    val route = entry?.destination?.route
    val detailId = entry?.arguments?.getString("id").orEmpty()
    val offlineBlurPage = route == "settings" ||
        (route == "detail/{id}" &&
            !detailId.startsWith(BLE_CONVERSATION_PREFIX) &&
            !detailId.startsWith(RELAY_CONVERSATION_PREFIX))
    if (offlineActive && offlineBlurPage) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { offlineDismissed = true }
        )
    }

    if (bucketOpen) {
        BucketSheet(
            items = bucket,
            failed = bucketFailed,
            onDismiss = { bucketOpen = false },
            onAddFiles = { filePicker.launch(arrayOf("*/*")) },
            onAddFolder = { pickBucketFolder() },
            onRemove = { repo.removeBucket(it) },
            onClear = {
                repo.clearBucket()
                context.toast("已清空")
            }
        )
    }

    val notice = shareNotice
    if (notice != null) {
        LaunchedEffect(notice) {
            delay(2000)
            onConsumeShareNotice()
        }
        val parts = buildList {
            if (notice.files > 0) add("${notice.files} 个文件")
            if (notice.folders > 0) add("${notice.folders} 个文件夹")
        }
        Dialog(
            onDismissRequest = onConsumeShareNotice,
            properties = DialogProperties(dismissOnClickOutside = true)
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.clickable(onClick = onConsumeShareNotice)
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 36.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(88.dp)
                            .clip(CircleShape)
                            .background(LocalXtColors.current.success.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Inventory2,
                            contentDescription = null,
                            tint = LocalXtColors.current.success,
                            modifier = Modifier.size(44.dp)
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = parts.joinToString("，") + "进入暂存箱",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "快去分享给其他设备吧。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    val onList = entry?.destination?.route == "list"
    var lastBack by remember { mutableLongStateOf(0L) }
    BackHandler(enabled = onList && !bucketOpen) {
        if (homePager.currentPage != 0) {
            scope.launch {
                homePager.animateScrollToPage(
                    0,
                    animationSpec = tween(320, easing = FastOutSlowInEasing)
                )
            }
            return@BackHandler
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastBack < EXIT_WINDOW_MS) {
            XlanService.stop(context)
            (context as? Activity)?.finish()
        } else {
            lastBack = now
            context.toast("再按一次退出")
        }
    }

    ErrorDialogHost()
}
