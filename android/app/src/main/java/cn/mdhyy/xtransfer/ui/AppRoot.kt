package cn.mdhyy.xtransfer.ui

import android.app.Activity
import android.content.ClipboardManager
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.DeviceHub
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cn.mdhyy.xtransfer.XlanApp
import cn.mdhyy.xtransfer.data.BLE_CONVERSATION_PREFIX
import cn.mdhyy.xtransfer.data.BucketItem
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
    HomeTab("内网分发", Icons.Rounded.DeviceHub)
)

data class ShareNotice(
    val files: Int,
    val folders: Int,
    val items: List<BucketItem>
)

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
    var autoStartShare by remember { mutableStateOf(false) }
    var pendingSharePage by remember { mutableStateOf(false) }
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

    // 文件管理器里「复制」的文件会以 content URI 留在剪贴板，本应用在前台读到时系统临时授权。
    fun pasteFromClipboard() {
        val clip = runCatching {
            context.getSystemService(ClipboardManager::class.java)?.primaryClip
        }.getOrNull()
        val uris = buildList {
            if (clip != null) {
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { add(it) }
            }
        }
        if (uris.isEmpty()) {
            context.toast("剪贴板里没有文件")
            return
        }
        scope.launch {
            val items = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    val readable = runCatching {
                        context.contentResolver.openInputStream(uri)?.use { } != null
                    }.getOrDefault(false)
                    if (readable) repo.makeFileItem(uri) else null
                }
            }
            if (items.isEmpty()) {
                context.showError("无法读取剪贴板里的文件")
            } else {
                repo.addBucket(items)
                context.toast("已加入 ${items.size} 项")
            }
        }
    }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
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
            LaunchedEffect(pendingSharePage) {
                if (pendingSharePage) {
                    homePager.animateScrollToPage(
                        2,
                        animationSpec = tween(320, easing = FastOutSlowInEasing)
                    )
                    pendingSharePage = false
                }
            }
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
                            testedPeaks = settings.speedPeaks,
                            lanOffline = offline,
                            scope = scope,
                            onOpenConv = { id -> nav.navigate("detail/${Uri.encode(id)}") },
                            onOpenSettings = { nav.navigate("settings") },
                            onOpenBucket = { bucketOpen = true },
                            hotspot = xlanApp.hotspot,
                            ble = xlanApp.ble,
                            relay = xlanApp.relay,
                            onOpenHotspot = { nav.navigate("hotspot") }
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
                            onAddApks = { nav.navigate("apps") { launchSingleTop = true } },
                            onRemove = { repo.removeBucket(it) },
                            autoStart = autoStartShare,
                            onAutoStarted = { autoStartShare = false }
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
                autoAcceptAll = settings.autoAcceptAll,
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
                onOpenApps = { nav.navigate("apps/${Uri.encode(id)}") },
                onShareFile = { message ->
                    val path = message.localPath
                    if (path.isNullOrBlank()) context.showError("文件不存在")
                    else shareFileExternally(context, path, message.fileName)
                }
            )
        }
        composable("apps") {
            AppListScreen(
                repo = repo,
                convId = null,
                onBack = { nav.popBackStack() }
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
            val message = messages.firstOrNull { it.id == id }
            MediaScreen(
                message = message,
                onBack = { nav.popBackStack() },
                onShare = {
                    val path = message?.localPath
                    if (path.isNullOrBlank()) context.showError("文件不存在")
                    else shareFileExternally(context, path, message.fileName)
                }
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
                onBack = { nav.popBackStack() },
                onShare = { shareFileExternally(context, path, path.substringAfterLast('/')) }
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
                onOpenAbout = { nav.navigate("about") },
                onOpenTheme = { nav.navigate("theme") }
            )
        }
        composable("theme") {
            ThemeScreen(
                settings = settings,
                store = repo.settingsStore(),
                onBack = { nav.popBackStack() }
            )
        }
        composable("about") {
            AboutScreen(
                onBack = { nav.popBackStack() },
                onOpenShare = { nav.navigate("shareapp") }
            )
        }
        composable("shareapp") {
            ShareAppScreen(
                onBack = { nav.popBackStack() },
                onSharePackage = { item ->
                    repo.addBucket(listOf(item))
                    autoStartShare = true
                    pendingSharePage = true
                    nav.popBackStack("list", inclusive = false)
                }
            )
        }
        composable("hotspot") {
            HotspotScreen(onBack = { nav.popBackStack() })
        }
    }

    val entry by nav.currentBackStackEntryAsState()
    // 只有真正显示离线模糊层的页面才需要这层点击收掉；热点等页面没有模糊层，
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
            onPaste = { pasteFromClipboard() },
            onRemove = { repo.removeBucket(it) },
            onClear = {
                repo.clearBucket()
                context.toast("已清空")
            }
        )
    }

    val notice = shareNotice
    if (notice != null) {
        val selfId = info?.id
        val targets = peers.filter {
            it.id != selfId && it.addr != null && repo.isPeerOnline(it)
        }
        var sendingTo by remember(notice) { mutableStateOf<String?>(null) }
        // 刚进应用时设备发现还没回来，先转圈；等一会儿仍没有可发送的设备就把转圈收掉。
        var probing by remember(notice) { mutableStateOf(true) }
        LaunchedEffect(notice) {
            delay(1800)
            probing = false
        }
        val store = {
            repo.addBucket(notice.items)
            context.toast("已加入暂存桶")
            onConsumeShareNotice()
        }
        val parts = buildList {
            if (notice.files > 0) add("${notice.files} 个文件")
            if (notice.folders > 0) add("${notice.folders} 个文件夹")
        }
        Dialog(
            onDismissRequest = { if (sendingTo == null) store() },
            properties = DialogProperties(dismissOnClickOutside = sendingTo == null)
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
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
                        text = parts.joinToString("，"),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    if (targets.isEmpty() && probing) {
                        // 转圈占住和设备列表一样的高度，加载完弹窗不会跳一下。
                        Box(
                            modifier = Modifier
                                .width(240.dp)
                                .height(76.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 3.dp
                            )
                        }
                    } else if (targets.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .width(240.dp)
                                .heightIn(min = 76.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.width(240.dp)
                            ) {
                                items(targets, key = { it.id }) { peer ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .width(72.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                            .clickable(enabled = sendingTo == null) {
                                                sendingTo = peer.id
                                                scope.launch {
                                                    val sent = repo.sendShared(peer.id, notice.items)
                                                    sendingTo = null
                                                    if (sent) {
                                                        onConsumeShareNotice()
                                                        nav.navigate("detail/${Uri.encode(peer.id)}") {
                                                            launchSingleTop = true
                                                        }
                                                    } else {
                                                        repo.addBucket(notice.items)
                                                        context.showError("设备不在线")
                                                        onConsumeShareNotice()
                                                    }
                                                }
                                            }
                                            .padding(horizontal = 4.dp, vertical = 8.dp)
                                    ) {
                                        Box {
                                            AvatarIcon(
                                                icon = platformIcon(peer.platform),
                                                online = true
                                            )
                                            if (sendingTo == peer.id) {
                                                Box(
                                                    modifier = Modifier
                                                        .matchParentSize()
                                                        .clip(CircleShape)
                                                        .background(
                                                            MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f)
                                                        ),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(20.dp),
                                                        strokeWidth = 2.dp
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = peer.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "暂无局域网在线设备，关闭弹窗后会存入暂存桶",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(220.dp)
                        )
                    }
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
