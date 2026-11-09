package cn.mdhyy.xtransfer.data

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import cn.mdhyy.xtransfer.XlanApp
import cn.mdhyy.xtransfer.service.XlanService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class ConversationUi(
    val id: String,
    val name: String,
    val platform: String?,
    val online: Boolean,
    val lastMessageAt: Long,
    val lastReadAt: Long,
    val unread: Int,
    val createdAt: Long,
    val originalName: String? = null,
    val addr: String? = null
)

data class ServiceStatus(
    val running: Boolean = false,
    val base: String = "",
    val error: String? = null
)

data class BucketItem(
    val uri: String,
    val name: String,
    val kind: String,
    val size: Long,
    val exists: Boolean = true
)

data class SpeedTestResult(
    val peak: Long,
    val avg: Long,
    val bytes: Long,
    val seconds: Int = 5,
    val samples: List<Long> = emptyList()
)

// 广播每秒一次，但 Wi-Fi 省电会把广播攒着送，丢两三个包很常见；
// 窗口留到 12 秒才不会因为一次抖动在在线/离线之间反复跳。
const val ONLINE_WINDOW_SEC = 12L
const val DEFAULT_RECV_FOLDER = "X互传"

// 入站传输多久没有任何字节进来就判定对方已经退出/取消。
private const val INBOUND_IDLE_MS = 5_000L

private const val SPEEDTEST_CHUNK = 4L * 1024 * 1024
private const val SPEEDTEST_SIZE = SPEEDTEST_CHUNK * 2
private const val SPEEDTEST_PREFIX = "xlan-speedtest-"
private val SPEEDTEST_JSON = "application/json".toMediaType()
private val SPEEDTEST_OCTET = "application/octet-stream".toMediaType()

class Repository(
    private val app: Application,
    val db: AppDatabase,
    private val settingsStore: SettingsStore
) {
    val settings = settingsStore.settings
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lanMonitor = LanMonitor(app)
    val lan: StateFlow<LanState> = lanMonitor.state

    init {
        lanMonitor.start()
    }

    @Volatile
    var api: LocalApi? = null
        private set

    private val _info = MutableStateFlow<DeviceInfo?>(null)
    val info: StateFlow<DeviceInfo?> = _info.asStateFlow()

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _service = MutableStateFlow(ServiceStatus())
    val service: StateFlow<ServiceStatus> = _service.asStateFlow()

    private val _restarting = MutableStateFlow(false)
    val restarting: StateFlow<Boolean> = _restarting.asStateFlow()

    private val _bucket = MutableStateFlow<List<BucketItem>>(emptyList())
    val bucket: StateFlow<List<BucketItem>> = _bucket.asStateFlow()

    private val _bucketFailed = MutableStateFlow<Set<String>>(emptySet())
    val bucketFailed: StateFlow<Set<String>> = _bucketFailed.asStateFlow()

    // 设备静默消失（断网/被杀）时没有任何事件，靠这个节拍重算在线状态。
    private val onlineTicker = flow {
        while (true) {
            emit(Unit)
            delay(1_000)
        }
    }

    val conversations: StateFlow<List<ConversationUi>> = combine(
        db.conversationDao().observeRows(),
        _peers,
        _info,
        settingsStore.settings,
        onlineTicker
    ) { rows, peers, _, conf, _ ->
        val now = now()
        val merged = LinkedHashMap<String, ConversationUi>()
        for (row in rows) {
            val c = row.conversation
            merged[c.id] = ConversationUi(
                id = c.id,
                name = c.name,
                platform = c.platform,
                online = false,
                lastMessageAt = c.lastMessageAt,
                lastReadAt = c.lastReadAt,
                unread = row.unread,
                createdAt = c.createdAt
            )
        }
        for (peer in peers) {
            val online = now - peer.lastSeen <= ONLINE_WINDOW_SEC
            val existing = merged[peer.id]
            merged[peer.id] = if (existing != null) {
                existing.copy(
                    name = peer.name.ifBlank { existing.name },
                    platform = peer.platform ?: existing.platform,
                    online = online,
                    addr = peer.addr ?: existing.addr
                )
            } else {
                ConversationUi(
                    id = peer.id,
                    name = peer.name.ifBlank { peer.id },
                    platform = peer.platform,
                    online = online,
                    lastMessageAt = 0,
                    lastReadAt = 0,
                    unread = 0,
                    createdAt = 0,
                    addr = peer.addr
                )
            }
        }
        merged[PUBLIC_CONVERSATION_ID]?.let {
            merged[PUBLIC_CONVERSATION_ID] = it.copy(name = PUBLIC_CONVERSATION_NAME, online = true)
        }
        val hidden = conf.hidden
        merged.values
            .filter { it.id == PUBLIC_CONVERSATION_ID || it.id !in conf.blocked }
            // 用户删除过的设备，在被重新发现之前不再出现。
            .filter { conv ->
                val hiddenAt = hidden[conv.id] ?: 0L
                val peer = peers.find { it.id == conv.id }
                hiddenAt <= 0L || (peer?.lastSeen ?: 0L) > hiddenAt
            }
            .map { conv ->
                // 免打扰的设备不显示未读数。
                val base = if (conv.id in conf.muted) conv.copy(unread = 0) else conv
                val alias = conf.aliases[base.id]
                if (alias.isNullOrBlank()) base
                else base.copy(name = alias, originalName = base.name)
            }
            .sortedWith(
                compareByDescending<ConversationUi> { it.id == PUBLIC_CONVERSATION_ID }
                    .thenByDescending { it.id in conf.pinned }
                    .thenByDescending { it.lastMessageAt }
            )
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val messages: StateFlow<List<MessageEntity>> =
        db.messageDao().observeAll().stateIn(scope, SharingStarted.Eagerly, emptyList())

    private var eventJob: Job? = null
    private var pollJob: Job? = null
    private var lastItemTs = 0L
    private var healthFailures = 0
    private var lastRecoveryAt = 0L
    private val progressSamples = ConcurrentHashMap<String, Pair<Long, Long>>()
    private val inboundSeen = ConcurrentHashMap<String, Long>()

    val sender by lazy { FileSender(app, db, this) }

    @Volatile
    var ble: BleManager? = null
        private set

    fun attachBle(manager: BleManager) {
        ble = manager
        manager.onInbound = { fromId, fromName, platform, body, ts ->
            scope.launch {
                runCatching { insertBleInbound(fromId, fromName, platform, body, ts) }
            }
        }
        manager.onFileOffer = { peerId, id, fromName, platform, name, size, ts ->
            val accepted = peerId !in settings.value.blocked && size in 1..BLE_FILE_LIMIT
            if (accepted) {
                // 必须在放行前先把消息落库，否则第一批进度回调会找不到这一条。
                runCatching {
                    runBlocking {
                        insertBleFilePending(peerId, id, fromName, name, size, ts)
                    }
                }.isSuccess
            } else {
                false
            }
        }
        manager.onFileProgress = { id, received, total ->
            scope.launch {
                runCatching { db.messageDao().setProgress(id, received, total, 0, now()) }
            }
        }
        manager.onFileDone = { id, name, bytes ->
            scope.launch {
                runCatching { finishBleFileInbound(id, name, bytes) }
            }
        }
        manager.onFileFailed = { id, reason ->
            scope.launch {
                runCatching { db.messageDao().setStatus(id, "failed", reason, now()) }
            }
        }
        manager.onFileSendProgress = { id, sent, total ->
            scope.launch {
                runCatching { db.messageDao().setProgress(id, sent, total, 0, now()) }
            }
        }
    }

    val dataDir: File get() = app.filesDir

    private val _recvDirPath = MutableStateFlow(
        XlanConfig.recvDir(app.filesDir) ?: defaultRecvDir().absolutePath
    )
    val recvDirPath: StateFlow<String> = _recvDirPath.asStateFlow()
    val recvDir: File get() = File(_recvDirPath.value)

    private fun defaultRecvDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        DEFAULT_RECV_FOLDER
    )

    suspend fun setRecvDir(path: String) {
        val dir = File(path)
        if (!dir.isDirectory || !dir.canWrite()) throw IllegalStateException("无法写入该目录")
        XlanConfig.setRecvDir(app.filesDir, dir.absolutePath)
        _recvDirPath.value = dir.absolutePath
        XlanService.restart(app)
    }

    suspend fun resetRecvDir() {
        val dir = defaultRecvDir()
        if (!dir.isDirectory && !dir.mkdirs()) throw IllegalStateException("无法创建默认目录")
        XlanConfig.clearRecvDir(app.filesDir)
        _recvDirPath.value = dir.absolutePath
        XlanService.restart(app)
    }

    fun settingsStore(): SettingsStore = settingsStore

    // ---------- 服务生命周期 ----------

    fun onServiceStarted(base: String) {
        val cert = File(app.filesDir, "tls.crt")
        api = LocalApi(base, cert)
        _restarting.value = false
        _service.value = ServiceStatus(running = true, base = base)
        scope.launch {
            runCatching { refreshInfo() }
            runCatching { purgeSelfConversation() }
            // 上次进程退出时没传完的，不会再有结果了，启动时直接收口为失败。
            runCatching { db.messageDao().failUnfinishedOutbound(now()) }
            runCatching { ensurePublicConversation() }
            runCatching { refreshPeers() }
            runCatching { refreshInbox() }
            runCatching { repairMissingPaths() }
            startWatchers()
        }
    }

    fun onServiceRestarting() {
        _restarting.value = true
    }

    fun onServiceStopped(error: String? = null) {
        eventJob?.cancel()
        eventJob = null
        pollJob?.cancel()
        pollJob = null
        api = null
        _restarting.value = false
        _service.value = ServiceStatus(running = false, base = "", error = error)
    }

    fun onServiceFailed(message: String?) {
        onServiceStopped(message ?: "服务启动失败")
    }

    private fun startWatchers() {
        if (eventJob?.isActive == true) return
        eventJob = scope.launch { eventLoop() }
        pollJob = scope.launch {
            var tick = 0
            while (isActive) {
                delay(1000)
                runCatching { reapStuckInbound() }
                // 设备列表不用每秒刷，五秒一次就够。
                if (++tick % 5 == 0) checkServiceHealth()
            }
        }
    }

    // 本地服务偶尔会因为进程被系统回收、端口冲突而悄悄退出，
    // 界面却还拿着 127.0.0.1 的旧地址去请求。连续三次探活失败就整条服务重启。
    private suspend fun checkServiceHealth() {
        val health = runCatching { refreshPeers() }
        if (health.isSuccess) {
            healthFailures = 0
            return
        }
        if (api == null || _restarting.value) return
        if (++healthFailures < 3) return
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastRecoveryAt < 30_000) return
        healthFailures = 0
        lastRecoveryAt = nowMs
        XlanService.restart(app)
    }

    // 入站传输长时间没有任何字节进来 = 对方已经退出/取消，直接收口，别让进度条卡着。
    private suspend fun reapStuckInbound() {
        val nowMs = System.currentTimeMillis()
        for (message in messages.value) {
            if (message.direction != "in" || message.kind == "text") continue
            if (message.status != "active" && message.status != "accepted") continue
            val last = inboundSeen[message.id]
            // 已确认但还排在并发队列里、一个字节都没收到的传输由本地服务按轮询情况判断，
            // 这里只收口真正开始接收后长时间没动静的，否则批量发送时排队文件会被误杀。
            if (message.received <= 0) {
                inboundSeen.remove(message.id)
                continue
            }
            if (last == null) {
                // 第一次看到先记个起点，给它一个宽限期。
                inboundSeen[message.id] = nowMs
                continue
            }
            if (nowMs - last < INBOUND_IDLE_MS) continue
            cancelIncoming(message.id)
        }
    }

    // 取消接收：先本地收口再通知服务端，点下去立刻有反馈。
    suspend fun cancelIncoming(id: String) {
        val message = db.messageDao().get(id) ?: return
        if (message.status == "pending" || message.status == "active" || message.status == "accepted") {
            db.messageDao().setStatus(id, "canceled", null, now())
        }
        Notifications.cancelOffer(app, id)
        progressSamples.remove(id)
        inboundSeen.remove(id)
        val current = api ?: return
        runCatching {
            withContext(Dispatchers.IO) {
                current.post("/v1/offer/$id/decision", JSONObject().put("action", "cancel"))
            }
        }
    }

    private suspend fun eventLoop() {
        var reconnected = false
        while (scope.isActive) {
            val current = api ?: return
            try {
                current.stream.newCall(Request.Builder().url(current.base + "/v1/events").build())
                    .execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        val source = resp.body?.source() ?: throw IOException("事件流为空")
                        if (reconnected) {
                            runCatching { refreshPeers() }
                            runCatching { refreshInbox(if (lastItemTs > 0) lastItemTs - 5 else 0) }
                        }
                        reconnected = true
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data: ")) continue
                            runCatching { handleEvent(JSONObject(line.substring(6))) }
                        }
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // 服务重启或网络抖动，稍后重连
            }
            if (scope.isActive) delay(1000)
        }
    }

    // ---------- 刷新 ----------

    suspend fun refreshInfo() {
        val current = api ?: return
        val json = withContext(Dispatchers.IO) { current.get("/v1/info") }
        _info.value = DeviceInfo.from(json)
    }

    suspend fun refreshPeers() {
        val current = api ?: return
        val json = withContext(Dispatchers.IO) { current.get("/v1/peers") }
        val array = json.optJSONArray("peers") ?: JSONArray()
        val peers = buildList {
            for (i in 0 until array.length()) add(Peer.from(array.getJSONObject(i)))
        }
        _peers.value = peers
        val conversationDao = db.conversationDao()
        for (peer in peers) {
            if (peer.name.isBlank()) continue
            val existing = conversationDao.get(peer.id) ?: continue
            if (existing.name != peer.name) conversationDao.rename(peer.id, peer.name)
            if (peer.platform != null && existing.platform != peer.platform) {
                conversationDao.setPlatform(peer.id, peer.platform)
            }
        }
    }

    suspend fun refreshInbox(since: Long = 0) {
        val current = api ?: return
        val suffix = if (since > 0) "&since=$since" else ""
        val json = withContext(Dispatchers.IO) { current.get("/v1/inbox?limit=500$suffix") }
        val items = json.optJSONArray("items") ?: JSONArray()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            if (isSpeedtestName(item.optString("name"))) {
                if (isSpeedtestTerminal(item.optString("status"))) {
                    dropSpeedtest(item.optString("id"))
                }
                continue
            }
            ingestItem(item)
        }
    }

    suspend fun scan() {
        val current = api ?: throw IllegalStateException("服务未运行")
        val json = withContext(Dispatchers.IO) { current.post("/v1/scan?deep=1", JSONObject()) }
        val seen = buildSet {
            val array = json.optJSONArray("peers") ?: JSONArray()
            for (i in 0 until array.length()) add(array.getJSONObject(i).optString("id"))
        }
        refreshPeers()
        // 手动刷新以这次扫描为准：没应答的直接判离线，不用等窗口过期。
        _peers.value = _peers.value.map { if (it.id in seen) it else it.copy(lastSeen = 0) }
        refreshInbox()
    }

    /** 进入公共频道时，向记录最全的设备补齐一次历史。 */
    suspend fun syncPublicHistory() {
        val current = api ?: return
        runCatching { withContext(Dispatchers.IO) { current.post("/v1/public/sync", JSONObject()) } }
    }

    // ---------- 事件 ----------

    private suspend fun handleEvent(event: JSONObject) {
        when (event.optString("type")) {
            "item.new" -> {
                val item = event.optJSONObject("data") ?: return
                if (blockedIncoming(item)) {
                    rejectSilently(item)
                    return
                }
                if (isSpeedtestName(item.optString("name"))) {
                    if (isSpeedtestTerminal(item.optString("status"))) {
                        dropSpeedtest(item.optString("id"))
                    }
                    return
                }
                ingestItem(item)
                notifyInbound(item)
            }
            "item.update" -> {
                val data = event.optJSONObject("data") ?: return
                val id = data.optString("id")
                if (id.isEmpty()) return
                if (isSpeedtestName(data.optString("name"))) {
                    if (isSpeedtestTerminal(data.optString("status"))) dropSpeedtest(id)
                    return
                }
                val existing = db.messageDao().get(id)
                val rawStatus = data.optString("status")
                val reason = data.optString("reason")
                var status = rawStatus.takeIf { it.isNotBlank() }?.let(::mapStatus)
                // 对端退出/取消导致的失败，对用户来说就是"已取消"。
                if (status == "failed" && (reason == "stale" || reason == "expired")) {
                    status = "canceled"
                }
                // 发送方撤回了这批文件，接收方要看出是对方取消的。
                if (status == "failed" && reason == "peer_canceled") {
                    status = "peer_canceled"
                }
                // 本地已经取消/拒绝的，晚到的失败通知不要改回去。
                if ((status == "failed" || status == "peer_canceled") &&
                    (existing?.status == "canceled" || existing?.status == "rejected")
                ) {
                    status = existing.status
                }
                val incomingPath = keepBetterPath(
                    existing?.localPath,
                    data.optString("path").takeIf { it.isNotBlank() }
                )
                db.messageDao().applyItemUpdate(
                    id = id,
                    status = status,
                    path = incomingPath,
                    name = data.optString("name").takeIf { it.isNotBlank() },
                    size = if (data.has("size")) data.optLong("size") else null,
                    now = now()
                )
                if (status != null && status != "pending") Notifications.cancelOffer(app, id)
                if (status == "active") {
                    inboundSeen[id] = System.currentTimeMillis()
                } else if (status != null) {
                    progressSamples.remove(id)
                    inboundSeen.remove(id)
                }
                if (status == "done" && existing?.status != "done" && existing?.direction == "in") {
                    scanReceivedPath(incomingPath ?: existing?.localPath)
                    if (existing.kind != "text" &&
                        settings.value.notifications &&
                        !(existing.channel == "public" && !settings.value.publicNotifications) &&
                        existing.fromId !in settings.value.muted
                    ) {
                        Notifications.received(
                            app,
                            existing.id,
                            existing.fromName ?: existing.fromId,
                            existing.fileName
                                ?: data.optString("name").takeIf { it.isNotBlank() }
                                ?: "文件"
                        )
                    }
                }
            }
            "peer.online" -> {
                val peer = Peer.from(event.optJSONObject("data") ?: return)
                if (peer.id.isBlank()) return
                _peers.value = _peers.value.filter { it.id != peer.id } + peer
            }
            "peer.offline" -> {
                val id = event.optJSONObject("data")?.optString("id").orEmpty()
                if (id.isBlank()) return
                _peers.value = _peers.value.map { if (it.id == id) it.copy(lastSeen = 0) else it }
                // 对端掉线后不会再有数据进来，该收口的入站传输立刻取消。
                val stuck = messages.value.filter {
                    it.direction == "in" && it.kind != "text" && it.fromId == id &&
                        (it.status == "pending" || it.status == "active" || it.status == "accepted")
                }
                for (message in stuck) cancelIncoming(message.id)
            }
            "transfer.progress" -> {
                val data = event.optJSONObject("data") ?: return
                val id = data.optString("id")
                if (id.isEmpty()) return
                val received = data.optLong("received")
                val total = data.optLong("total")
                val at = System.currentTimeMillis()
                val prev = progressSamples[id]
                val elapsed = if (prev != null) ((at - prev.second) / 1000.0).coerceAtLeast(0.05) else 0.0
                val speed = if (prev != null) (((received - prev.first) / elapsed).toLong()).coerceAtLeast(0) else 0
                progressSamples[id] = received to at
                inboundSeen[id] = at
                db.messageDao().setProgress(id, received, total, speed, now())
            }
        }
    }

    private suspend fun ensurePublicConversation() {
        val dao = db.conversationDao()
        if (dao.get(PUBLIC_CONVERSATION_ID) == null) {
            dao.upsert(
                ConversationEntity(
                    PUBLIC_CONVERSATION_ID,
                    PUBLIC_CONVERSATION_NAME,
                    createdAt = now()
                )
            )
        } else {
            dao.rename(PUBLIC_CONVERSATION_ID, PUBLIC_CONVERSATION_NAME)
        }
    }

    private suspend fun purgeSelfConversation() {
        val selfId = _info.value?.id?.takeIf { it.isNotBlank() } ?: return
        db.messageDao().deleteByConversation(selfId)
        db.conversationDao().delete(selfId)
    }

    // 黑名单里的设备发来的消息直接丢掉，未处理的传输顺手帮它拒掉。
    private fun blockedIncoming(item: JSONObject): Boolean {
        if (item.optString("channel") == "public") return false
        val from = item.optString("from_id")
        return from.isNotEmpty() && from != _info.value?.id && from in settings.value.blocked
    }

    private fun rejectSilently(item: JSONObject) {
        val id = item.optString("id")
        if (id.isEmpty() || item.optString("status") != "pending") return
        val current = api ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    current.post("/v1/offer/$id/decision", JSONObject().put("action", "reject"))
                }
            }
        }
    }

    // 对端测速时落下的临时文件：不进消息列表、不通知，传完立刻删掉。
    private fun isSpeedtestName(name: String): Boolean = name.startsWith(SPEEDTEST_PREFIX)

    private fun isSpeedtestTerminal(status: String): Boolean =
        status == "done" || status == "failed" || status == "canceled" ||
            status == "rejected" || status == "expired"

    private fun dropSpeedtest(id: String) {
        if (id.isEmpty()) return
        val current = api ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    current.delete("/v1/item/${Uri.encode(id)}?files=1")
                }
            }
        }
    }

    private suspend fun ingestItem(item: JSONObject) {
        val id = item.optString("id")
        if (id.isEmpty()) return
        if (blockedIncoming(item)) {
            rejectSilently(item)
            return
        }
        val channel = if (item.optString("channel") == "public") "public" else "dm"
        // 自测是发给自己，不是和某台设备的会话。
        if (channel != "public" &&
            item.optString("from_id") == _info.value?.id &&
            item.optString("to_id") == _info.value?.id
        ) return
        val mine = item.optString("from_id") == _info.value?.id
        val convId = when {
            channel == "public" -> PUBLIC_CONVERSATION_ID
            mine -> item.optString("to_id")
            else -> item.optString("from_id")
        }
        if (convId.isEmpty()) return

        val createdAt = item.optLong("created_at").takeIf { it > 0 } ?: now()
        lastItemTs = maxOf(lastItemTs, createdAt)
        val direction = if (mine) "out" else "in"
        val fromName = item.optString("from_name").takeIf { it.isNotBlank() }
        ensureConversationNow(
            convId,
            when {
                convId == PUBLIC_CONVERSATION_ID -> PUBLIC_CONVERSATION_NAME
                direction == "in" -> fromName ?: convId
                else -> convId
            }
        )

        val dao = db.messageDao()
        val existing = dao.get(id)
        var status = mapStatus(item.optString("status"))
        // 接收目录里已有同名同内容的文件时，服务端直接标成已完成并带上 duplicate。
        if (item.optBoolean("duplicate") && status == "done") status = "duplicate"
        // 本地已取消/拒绝的，晚到的失败状态不要覆盖。
        if (status == "failed" &&
            (existing?.status == "canceled" ||
                existing?.status == "rejected" ||
                existing?.status == "peer_canceled")
        ) {
            status = existing.status
        }
        if (existing != null) {
            val incomingPath = keepBetterPath(
                existing.localPath,
                item.optString("path").takeIf { it.isNotBlank() }
            )
            dao.applyItemUpdate(
                id,
                status,
                incomingPath,
                item.optString("name").takeIf { it.isNotBlank() },
                if (item.has("size")) item.optLong("size") else null,
                now()
            )
            if (status == "done" && existing.status != "done" && direction == "in") {
                scanReceivedPath(incomingPath ?: existing.localPath)
            }
            if (status != "pending") Notifications.cancelOffer(app, id)
            return
        }

        // 服务端回显自己的公共消息时，先删掉本地乐观插入的那条，避免重复气泡。
        if (channel == "public" && mine) {
            val body = item.optString("body")
            if (body.isNotEmpty()) {
                dao.findRecentOutText(PUBLIC_CONVERSATION_ID, body, createdAt - 30)?.let {
                    dao.delete(it.id)
                }
            }
        }

        dao.insert(
            MessageEntity(
                id = id,
                conversationId = convId,
                direction = direction,
                kind = item.optString("kind").ifBlank { "text" },
                channel = channel,
                fromId = item.optString("from_id"),
                fromName = fromName,
                body = item.optString("body").takeIf { it.isNotEmpty() },
                fileName = item.optString("name").takeIf { it.isNotBlank() },
                fileSize = if (item.has("size")) item.optLong("size") else null,
                localPath = item.optString("path").takeIf { it.isNotBlank() },
                status = status,
                received = 0,
                total = if (item.has("size")) item.optLong("size") else 0,
                createdAt = createdAt,
                updatedAt = now()
            )
        )
        db.conversationDao().touch(convId, createdAt)
        if (status == "done" && direction == "in" && item.optString("kind") != "text") {
            scanReceivedPath(item.optString("path"))
        }
    }

    // 服务端有时会在完成事件里再发一次旧暂存路径，别让它把已经有效的真实路径冲掉。
    private fun keepBetterPath(current: String?, incoming: String?): String? {
        if (incoming.isNullOrBlank()) return null
        if (current.isNullOrBlank() || current == incoming) return incoming
        val currentOk = current.startsWith("content://") || File(current).exists()
        val incomingOk = incoming.startsWith("content://") || File(incoming).exists()
        return if (currentOk && !incomingOk) null else incoming
    }

    private fun scanReceivedPath(path: String?) {
        if (path.isNullOrBlank() || path.startsWith("content://")) return
        val file = File(path)
        if (!file.exists()) return
        scope.launch {
            if (file.isDirectory) {
                file.walkTopDown().filter { it.isFile }.forEach { MediaPublisher.scan(app, it) }
            } else {
                MediaPublisher.scan(app, file)
            }
        }
    }

    // 旧版本把收件路径覆盖成已删除的源文件，这里按文件名在接收目录里找回真实文件。
    private suspend fun repairMissingPaths() {
        val dirs = listOf(
            recvDir,
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DEFAULT_RECV_FOLDER),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), DEFAULT_RECV_FOLDER)
        ).distinctBy { it.absolutePath }
        val used = HashSet<String>()
        for (message in db.messageDao().allFileMessages()) {
            val current = message.localPath ?: continue
            if (current.startsWith("content://")) {
                val real = MediaPublisher.realFile(app, Uri.parse(current))
                if (real != null) db.messageDao().setLocalPath(message.id, real.absolutePath, now())
                continue
            }
            if (File(current).exists()) {
                used += current
                continue
            }
            val name = message.fileName?.takeIf { it.isNotBlank() } ?: continue
            val found = findReceivedFile(dirs, name, used) ?: continue
            used += found.absolutePath
            db.messageDao().setLocalPath(message.id, found.absolutePath, now())
        }
    }

    private fun findReceivedFile(dirs: List<File>, name: String, used: Set<String>): File? {
        for (dir in dirs) {
            val exact = File(dir, name)
            if (exact.isFile && exact.absolutePath !in used) return exact
        }
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        val duplicate = Regex(
            "^" + Regex.escape(base) + " \\(\\d+\\)" + if (ext.isEmpty()) "" else "\\." + Regex.escape(ext) + "$"
        )
        for (dir in dirs) {
            dir.listFiles()
                ?.filter { it.isFile && duplicate.matches(it.name) && it.absolutePath !in used }
                ?.sortedBy { it.lastModified() }
                ?.firstOrNull()
                ?.let { return it }
        }
        return null
    }

    private fun notifyInbound(item: JSONObject) {
        val fromId = item.optString("from_id")
        if (fromId == _info.value?.id) return
        val isPublic = item.optString("channel") == "public"
        if (!isPublic && fromId.isNotEmpty() && item.optString("kind") == "text") {
            copyToClipboard(
                convId = fromId,
                platform = item.optString("platform"),
                fromId = fromId,
                fromName = item.optString("from_name"),
                text = item.optString("body")
            )
        }
        if (!settings.value.notifications) return
        if (isPublic && !settings.value.publicNotifications) return
        if (!isPublic && fromId in settings.value.muted) {
            return
        }
        val kind = item.optString("kind")
        val name = item.optString("name")
        val title = item.optString("from_name").takeIf { it.isNotBlank() } ?: fromId
        val convId = if (isPublic) PUBLIC_CONVERSATION_ID else fromId
        // 文件要先确认接收，确认前在通知栏留一条提醒，前台也照发。
        if (kind != "text" && item.optString("status") == "pending") {
            val body = if (kind == "folder") "发来文件夹 $name，等待接收" else "发来文件 $name，等待接收"
            Notifications.offer(app, title, body, convId, item.optString("id"))
            return
        }
        if (XlanApp.foreground) return
        val body = when (kind) {
            "text" -> item.optString("body")
            "folder" -> "发来文件夹 $name"
            else -> "发来文件 $name"
        }
        Notifications.message(app, title, body, convId)
    }

    /** 会话开了「接收跨设备复制」时把对端文本写进本机剪贴板，并提示是谁复制的。 */
    private fun copyToClipboard(convId: String, platform: String, fromId: String, fromName: String, text: String) {
        if (text.isEmpty() || convId !in settings.value.clipboardPeers) return
        val copied = runCatching {
            app.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("X互传", text))
        }.isSuccess
        if (!copied) return
        val peer = _peers.value.find { it.id == fromId }
        val raw = platform.ifBlank { peer?.platform ?: "" }
        val label = when {
            raw.contains("win", true) -> "Windows"
            raw.contains("android", true) -> "Android"
            raw.contains("mac", true) || raw.contains("darwin", true) -> "macOS"
            raw.contains("linux", true) -> "Linux"
            else -> null
        }
        val name = fromName.takeIf { it.isNotBlank() }
            ?: peer?.name?.takeIf { it.isNotBlank() }
            ?: fromId
        val message = if (label != null) "${label}平台复制了文本" else "$name 复制了文本"
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- 发文本 / 决策 / 已读 / 信任 ----------

    suspend fun sendText(to: String, body: String) {
        if (to.startsWith(BLE_CONVERSATION_PREFIX)) {
            sendBleText(to.removePrefix(BLE_CONVERSATION_PREFIX), body)
            return
        }
        val current = api ?: throw IllegalStateException("服务未运行")
        val text = body.trim()
        if (text.isEmpty()) throw IllegalStateException("内容为空")
        if (to.isEmpty()) {
            val self = _info.value
            val id = UUID.randomUUID().toString()
            val ts = now()
            ensureConversationNow(PUBLIC_CONVERSATION_ID, PUBLIC_CONVERSATION_NAME)
            db.messageDao().insert(
                MessageEntity(
                    id = id,
                    conversationId = PUBLIC_CONVERSATION_ID,
                    direction = "out",
                    kind = "text",
                    channel = "public",
                    fromId = self?.id ?: "",
                    fromName = self?.name,
                    body = text,
                    status = "sending",
                    createdAt = ts,
                    updatedAt = ts
                )
            )
            db.conversationDao().touch(PUBLIC_CONVERSATION_ID, ts)
            try {
                withContext(Dispatchers.IO) {
                    current.post("/v1/text", JSONObject().put("body", text).put("to", ""))
                }
                db.messageDao().setStatus(id, "done", null, now())
            } catch (error: Exception) {
                db.messageDao().setStatus(id, "failed", error.message, now())
                throw error
            }
            scope.launch {
                delay(2000)
                runCatching { refreshInbox() }
            }
            return
        }
        val self = _info.value
        val peer = _peers.value.find { it.id == to }
        ensureConversationNow(to, peer?.name ?: to)
        val id = UUID.randomUUID().toString()
        val ts = now()
        db.messageDao().insert(
            MessageEntity(
                id = id,
                conversationId = to,
                direction = "out",
                kind = "text",
                channel = "dm",
                fromId = self?.id ?: "",
                fromName = self?.name,
                body = text,
                status = "sending",
                createdAt = ts,
                updatedAt = ts
            )
        )
        db.conversationDao().touch(to, ts)
        try {
            withContext(Dispatchers.IO) {
                current.post("/v1/text", JSONObject().put("body", text).put("to", to))
            }
            db.messageDao().setStatus(id, "done", null, now())
        } catch (error: Exception) {
            db.messageDao().setStatus(id, "failed", error.message, now())
            throw error
        }
    }

    // 网页访客的消息：xlan 自己收到广播后会回传事件，这里先本地落一条，
    // 事件晚到时按同 id 更新，不会出现两条。
    suspend fun ingestWebGuestText(id: String, fromId: String, fromName: String, body: String, ts: Long) {
        if (id.isEmpty()) return
        val dao = db.messageDao()
        if (dao.get(id) != null) return
        ensureConversationNow(PUBLIC_CONVERSATION_ID, PUBLIC_CONVERSATION_NAME)
        dao.insert(
            MessageEntity(
                id = id,
                conversationId = PUBLIC_CONVERSATION_ID,
                direction = "in",
                kind = "text",
                channel = "public",
                fromId = fromId,
                fromName = fromName.takeIf { it.isNotBlank() },
                body = body,
                status = "done",
                createdAt = ts,
                updatedAt = ts
            )
        )
        db.conversationDao().touch(PUBLIC_CONVERSATION_ID, now())
    }

    private suspend fun sendBleText(peerId: String, body: String) {
        val text = body.trim()
        if (text.isEmpty()) throw IllegalStateException("内容为空")
        val manager = ble ?: throw IllegalStateException("蓝牙未启用")
        if (peerId.isEmpty()) throw IllegalStateException("蓝牙设备为空")
        val convId = BLE_CONVERSATION_PREFIX + peerId
        val name = manager.state.value.devices.firstOrNull { it.id == peerId }?.name ?: peerId
        ensureConversationNow(convId, name)
        val self = _info.value
        val id = UUID.randomUUID().toString()
        val ts = now()
        db.messageDao().insert(
            MessageEntity(
                id = id,
                conversationId = convId,
                direction = "out",
                kind = "text",
                channel = "ble",
                fromId = self?.id ?: "",
                fromName = self?.name,
                body = text,
                status = "sending",
                createdAt = ts,
                updatedAt = ts
            )
        )
        db.conversationDao().touch(convId, ts)
        try {
            manager.sendText(peerId, text, id)
            db.messageDao().setStatus(id, "done", null, now())
        } catch (error: Exception) {
            db.messageDao().setStatus(id, "failed", error.message, now())
            throw error
        }
    }

    private suspend fun insertBleInbound(
        fromId: String,
        fromName: String,
        platform: String,
        body: String,
        ts: Long
    ) {
        if (fromId.isEmpty() || body.isBlank()) return
        if (fromId in settings.value.blocked) return
        val convId = BLE_CONVERSATION_PREFIX + fromId
        val createdAt = normalizeTs(ts)
        ensureConversationNow(convId, fromName.takeIf { it.isNotBlank() } ?: fromId)
        db.messageDao().insert(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = convId,
                direction = "in",
                kind = "text",
                channel = "ble",
                fromId = fromId,
                fromName = fromName.takeIf { it.isNotBlank() },
                body = body,
                status = "done",
                createdAt = createdAt,
                updatedAt = now()
            )
        )
        db.conversationDao().touch(convId, createdAt)
        copyToClipboard(
            convId = convId,
            platform = platform,
            fromId = fromId,
            fromName = fromName,
            text = body
        )
        if (settings.value.notifications && !XlanApp.foreground) {
            Notifications.message(
                app,
                fromName.takeIf { it.isNotBlank() } ?: fromId,
                body,
                convId
            )
        }
    }

    // ---------- 蓝牙文件 ----------

    private suspend fun insertBleFilePending(
        fromId: String,
        id: String,
        fromName: String,
        name: String,
        size: Long,
        ts: Long
    ) {
        if (fromId.isEmpty() || name.isBlank()) return
        if (fromId in settings.value.blocked) return
        if (db.messageDao().get(id) != null) return
        val convId = BLE_CONVERSATION_PREFIX + fromId
        val createdAt = normalizeTs(ts)
        ensureConversationNow(convId, fromName.takeIf { it.isNotBlank() } ?: fromId)
        db.messageDao().insert(
            MessageEntity(
                id = id,
                conversationId = convId,
                direction = "in",
                kind = "file",
                channel = "ble",
                fromId = fromId,
                fromName = fromName.takeIf { it.isNotBlank() },
                fileName = name,
                fileSize = size,
                status = "active",
                total = size,
                createdAt = createdAt,
                updatedAt = now()
            )
        )
        db.conversationDao().touch(convId, now())
    }

    private suspend fun finishBleFileInbound(id: String, name: String, bytes: ByteArray) {
        val message = db.messageDao().get(id) ?: return
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val dir = recvDir
                dir.mkdirs()
                val target = uniqueFile(dir, safeFileName(name))
                target.writeBytes(bytes)
                target
            }.getOrNull()
        }
        if (file == null) {
            db.messageDao().setStatus(id, "failed", "写入失败", now())
            return
        }
        db.messageDao().applyItemUpdate(id, "done", file.absolutePath, file.name, bytes.size.toLong(), now())
        val convId = message.conversationId
        if (settings.value.notifications && !XlanApp.foreground) {
            Notifications.message(
                app,
                message.fromName?.takeIf { it.isNotBlank() } ?: message.fromId,
                file.name,
                convId
            )
        }
    }

    private fun safeFileName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
        return base.replace(Regex("""[\\/:*?"<>|]"""), "_").take(120).ifBlank { "文件" }
    }

    private fun uniqueFile(dir: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var candidate = File(dir, name)
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, if (ext.isEmpty()) "$base ($index)" else "$base ($index).$ext")
            index += 1
        }
        return candidate
    }

    /** 蓝牙会话里选中的文件，逐个压缩（图片）后发送。 */
    private suspend fun sendBleFiles(convId: String, items: List<BucketItem>) {
        val peerId = convId.removePrefix(BLE_CONVERSATION_PREFIX)
        val manager = ble ?: throw IllegalStateException("蓝牙未启用")
        if (peerId.isEmpty()) throw IllegalStateException("蓝牙设备为空")
        val self = _info.value
        val peerName = manager.state.value.devices.firstOrNull { it.id == peerId }?.name ?: peerId
        for (item in items) {
            if (item.kind == "folder") throw IllegalStateException("蓝牙不支持发送文件夹")
            val raw = readBucketBytes(item) ?: throw IllegalStateException("无法读取「${item.name}」")
            val (fileName, data) = compressBleImage(item.name, raw)
            if (data.size.toLong() > BLE_FILE_LIMIT) {
                throw IllegalStateException("「$fileName」超过 5 MB，请改用局域网")
            }
            val id = UUID.randomUUID().toString()
            val ts = now()
            // 压缩后的内容存一份到缓存，会话里才能打开/另存为，也方便重试。
            val localPath = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(app.cacheDir, "ble-out").apply { mkdirs() }
                    File(dir, "$id-${safeFileName(fileName)}").also { it.writeBytes(data) }
                        .absolutePath
                }.getOrNull()
            }
            ensureConversationNow(convId, peerName)
            db.messageDao().insert(
                MessageEntity(
                    id = id,
                    conversationId = convId,
                    direction = "out",
                    kind = "file",
                    channel = "ble",
                    fromId = self?.id ?: "",
                    fromName = self?.name,
                    fileName = fileName,
                    fileSize = data.size.toLong(),
                    localPath = localPath,
                    status = "active",
                    total = data.size.toLong(),
                    createdAt = ts,
                    updatedAt = ts
                )
            )
            db.conversationDao().touch(convId, ts)
            try {
                manager.sendFile(peerId, id, fileName, data)
                db.messageDao().setStatus(id, "done", null, now())
            } catch (error: Exception) {
                db.messageDao().setStatus(id, "failed", error.message ?: "发送失败", now())
            }
        }
    }

    private fun readBucketBytes(item: BucketItem): ByteArray? = runCatching {
        val uriText = item.uri
        if (uriText.startsWith("content://")) {
            app.contentResolver.openInputStream(Uri.parse(uriText))?.use { it.readBytes() }
        } else {
            val path = if (uriText.startsWith("file://")) Uri.parse(uriText).path else uriText
            File(path ?: return@runCatching null).takeIf { it.isFile }?.readBytes()
        }
    }.getOrNull()

    /**
     * 蓝牙带宽只有几十 KB/s，图片先压一轮再发：最大边 1600、JPEG 80。
     * 压完反而更大（本来就很小的图）就继续用原图。
     */
    private fun compressBleImage(name: String, bytes: ByteArray): Pair<String, ByteArray> {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif")) return name to bytes
        // 2 MB 以内的图片原样发送，只有大图才值得压一轮。
        if (bytes.size <= 2 * 1024 * 1024) return name to bytes
        if (bytes.size > 24 * 1024 * 1024) return name to bytes
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return name to bytes
            var sample = 1
            while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return name to bytes
            val edge = maxOf(decoded.width, decoded.height)
            val bitmap = if (edge > 1600) {
                val ratio = 1600f / edge
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * ratio).toInt().coerceAtLeast(1),
                    (decoded.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                decoded
            }
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            if (bitmap !== decoded) decoded.recycle()
            bitmap.recycle()
            val result = out.toByteArray()
            if (result.isEmpty() || result.size >= bytes.size) return name to bytes
            val base = name.substringBeforeLast('.', name)
            "$base.jpg" to result
        }.getOrDefault(name to bytes)
    }

    private fun normalizeTs(ts: Long): Long =
        if (ts > 100_000_000_000L) ts / 1000 else ts.takeIf { it > 0 } ?: now()

    suspend fun decide(id: String, action: String) {
        if (action == "cancel") {
            cancelIncoming(id)
            scope.launch {
                delay(300)
                runCatching { refreshInbox() }
            }
            return
        }
        val current = api ?: throw IllegalStateException("服务未运行")
        val message = db.messageDao().get(id)
        val prev = message?.status
        // 先本地改状态，按钮点下去立刻有反应；服务端失败再回滚。
        if (message != null && prev == "pending") {
            db.messageDao().setStatus(
                id,
                if (action == "accept") "accepted" else "rejected",
                null,
                now()
            )
            if (action == "accept") inboundSeen[id] = System.currentTimeMillis()
        }
        Notifications.cancelOffer(app, id)
        try {
            withContext(Dispatchers.IO) {
                current.post("/v1/offer/$id/decision", JSONObject().put("action", action))
            }
        } catch (error: Exception) {
            if (message != null && prev != null) {
                db.messageDao().setStatus(id, prev, null, now())
                inboundSeen.remove(id)
            }
            throw error
        } finally {
            scope.launch {
                delay(300)
                runCatching { refreshInbox() }
            }
        }
    }

    suspend fun markRead(convId: String) {
        db.conversationDao().markRead(convId)
    }

    suspend fun deleteMessage(id: String) {
        db.messageDao().delete(id)
    }

    // 本地服务会把收到的条目一直存在 Store 里，只清本地库的话下次刷新会被整批灌回来，
    // 所以要逐条让服务端也删掉（不传 files 参数，不动磁盘上的文件）。
    private suspend fun purgeServerItems(ids: List<String>) {
        if (ids.isEmpty()) return
        val current = api ?: return
        for (id in ids) {
            runCatching {
                withContext(Dispatchers.IO) { current.delete("/v1/item/${Uri.encode(id)}") }
            }
        }
    }

    suspend fun clearConversation(convId: String) {
        val ids = db.messageDao().idsByConversation(convId)
        db.messageDao().deleteByConversation(convId)
        db.conversationDao().resetLastMessage(convId)
        if (convId == PUBLIC_CONVERSATION_ID) {
            // 服务端也清掉公共频道历史并记下时间点，之后补齐不再拉回旧消息。
            api?.let { current ->
                runCatching {
                    withContext(Dispatchers.IO) { current.post("/v1/public/clear", JSONObject()) }
                }
            }
            return
        }
        purgeServerItems(ids)
    }

    suspend fun deleteConversation(id: String) {
        if (id == PUBLIC_CONVERSATION_ID) return
        val ids = db.messageDao().idsByConversation(id)
        db.messageDao().deleteByConversation(id)
        db.conversationDao().delete(id)
        if (_peers.value.any { it.id == id }) {
            _peers.value = _peers.value.filter { it.id != id }
        }
        val current = settings.value
        settingsStore.update(
            blocked = current.blocked - id,
            muted = current.muted - id,
            clipboardPeers = current.clipboardPeers - id,
            pinned = current.pinned - id,
            aliases = current.aliases - id,
            hidden = current.hidden + (id to now())
        )
        purgeServerItems(ids)
    }

    suspend fun deleteOfflineDevices(): Int {
        val nowSec = now()
        val hidden = settings.value.hidden
        val alreadyHidden = { id: String ->
            val hiddenAt = hidden[id] ?: 0L
            val lastSeen = _peers.value.find { it.id == id }?.lastSeen ?: 0L
            hiddenAt > 0L && lastSeen <= hiddenAt
        }
        val ids = LinkedHashSet<String>()
        conversations.value.forEach {
            if (it.id != PUBLIC_CONVERSATION_ID && !it.online && !alreadyHidden(it.id)) ids += it.id
        }
        _peers.value.forEach {
            if (nowSec - it.lastSeen > ONLINE_WINDOW_SEC && !alreadyHidden(it.id)) ids += it.id
        }
        for (id in ids) deleteConversation(id)
        return ids.size
    }

    suspend fun setBlocked(id: String, blocked: Boolean) {
        val next = settings.value.blocked.toMutableSet()
        if (blocked) next += id else next -= id
        settingsStore.update(blocked = next)
        if (!blocked) return
        // 拉黑时顺手把还没接收完的传输收掉。
        messages.value
            .filter {
                it.direction == "in" && it.fromId == id &&
                    (it.status == "pending" || it.status == "active" || it.status == "accepted")
            }
            .forEach { cancelIncoming(it.id) }
    }

    fun setAlias(id: String, alias: String) {
        val next = settings.value.aliases.toMutableMap()
        val text = alias.trim().take(15)
        if (text.isEmpty()) next -= id else next[id] = text
        settingsStore.update(aliases = next)
    }

    fun setMuted(id: String, muted: Boolean) {
        val next = settings.value.muted.toMutableSet()
        if (muted) next += id else next -= id
        settingsStore.update(muted = next)
    }

    fun setClipboard(id: String, on: Boolean) {
        val next = settings.value.clipboardPeers.toMutableSet()
        if (on) next += id else next -= id
        settingsStore.update(clipboardPeers = next)
    }

    fun setPinned(id: String, pinned: Boolean) {
        val next = settings.value.pinned.toMutableSet()
        if (pinned) next += id else next -= id
        settingsStore.update(pinned = next)
    }

    suspend fun setTrust(id: String, trusted: Boolean) {
        val current = api ?: throw IllegalStateException("服务未运行")
        withContext(Dispatchers.IO) {
            current.post("/v1/trust", JSONObject().put("id", id).put("trusted", trusted))
        }
        refreshPeers()
    }

    // 真实文件传输测速：向对端发一个 8MiB 的临时文件，5 秒内 4 个线程反复写前半段保持传输活跃，
    // 最后补传后半段让对端落盘；对端识别到测速文件名后不入库、直接删除。
    suspend fun speedTest(
        peerId: String,
        onSample: (Long) -> Unit = {}
    ): SpeedTestResult {
        val peer = peers.value.firstOrNull { it.id == peerId }
            ?: throw IllegalStateException("设备不在线")
        val base = peerBaseUrl(peer) ?: throw IllegalStateException("设备不在线")
        val client = peerClient()
        val info = _info.value
        val offerBody = JSONObject()
            .put("from_id", info?.id ?: "")
            .put("from_name", info?.name ?: "")
            .put("to_id", peerId)
            .put("kind", "file")
            .put("name", "$SPEEDTEST_PREFIX${System.currentTimeMillis()}.bin")
            .put("size", SPEEDTEST_SIZE)
        val offer = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$base/v1/offer")
                .post(offerBody.toString().toRequestBody(SPEEDTEST_JSON))
                .build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                JSONObject(text)
            }
        }
        val offerId = offer.optString("id")
        if (offerId.isEmpty()) throw IllegalStateException("对端没有返回传输编号")
        if (!offer.optBoolean("accepted") && !offer.optBoolean("duplicate")) {
            var accepted = false
            val until = System.currentTimeMillis() + 6_000
            while (System.currentTimeMillis() < until) {
                delay(400)
                val status = runCatching {
                    withContext(Dispatchers.IO) {
                        val request = Request.Builder()
                            .url("$base/v1/offer/$offerId")
                            .get()
                            .build()
                        client.newCall(request).execute().use { resp ->
                            JSONObject(resp.body?.string().orEmpty()).optString("status")
                        }
                    }
                }.getOrDefault("")
                if (status == "active" || status == "done") {
                    accepted = true
                    break
                }
                if (status == "failed" || status == "expired") break
            }
            if (!accepted) throw IllegalStateException("对方未接受测速文件（需对方信任本机）")
        }

        val url = "$base/v1/offer/$offerId/data"
        val block = ByteArray(SPEEDTEST_CHUNK.toInt())
        val total = AtomicLong(0)
        val deadline = System.currentTimeMillis() + 5_000
        val samples = ArrayList<Long>(5)

        suspend fun upload(start: Long, end: Long) {
            val body = CountingBody(block, SPEEDTEST_OCTET) { n -> total.addAndGet(n.toLong()) }
            val request = Request.Builder()
                .url(url)
                .put(body)
                .header("Content-Range", "bytes $start-$end/$SPEEDTEST_SIZE")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("上传失败：HTTP ${resp.code}")
                }
            }
        }

        withContext(Dispatchers.IO) {
            coroutineScope {
                repeat(4) {
                    launch {
                        while (System.currentTimeMillis() < deadline) {
                            upload(0, SPEEDTEST_CHUNK - 1)
                        }
                    }
                }
                var lastBytes = 0L
                repeat(5) {
                    delay(1_000)
                    val now = total.get()
                    val sample = (now - lastBytes).coerceAtLeast(0)
                    lastBytes = now
                    samples += sample
                    withContext(Dispatchers.Main) { onSample(sample) }
                }
            }
            upload(SPEEDTEST_CHUNK, SPEEDTEST_SIZE - 1)
        }
        val bytes = total.get()
        val peak = samples.maxOrNull() ?: 0L
        val avg = if (samples.isEmpty()) 0L else samples.sum() / samples.size
        return SpeedTestResult(peak = peak, avg = avg, bytes = bytes, samples = samples)
    }

    suspend fun setDeviceName(name: String) {
        val trimmed = name.trim().take(15)
        if (trimmed.isEmpty()) throw IllegalStateException("设备名不能为空")
        XlanConfig.setDeviceName(app.filesDir, trimmed)
        XlanService.restart(app)
    }

    suspend fun cleanCache(): CacheCleanResult {
        val current = api ?: throw IllegalStateException("服务未运行")
        val json = withContext(Dispatchers.IO) {
            current.post(
                "/v1/cache/clean",
                JSONObject().put("older_than_sec", 30 * 24 * 3600).put("dry_run", false)
            )
        }
        return CacheCleanResult(
            parts = json.optInt("parts"),
            freedBytes = json.optLong("freed_bytes"),
            records = json.optInt("records")
        )
    }

    // ---------- 暂存桶 ----------

    fun addBucket(items: List<BucketItem>) {
        if (items.isEmpty()) return
        val map = LinkedHashMap<String, BucketItem>()
        for (item in _bucket.value) map[item.uri] = item
        for (item in items) map[item.uri] = item
        _bucket.value = map.values.toList()
        _bucketFailed.value = _bucketFailed.value - items.map { it.uri }.toSet()
    }

    fun removeBucket(uri: String) {
        _bucket.value = _bucket.value.filter { it.uri != uri }
    }

    fun clearBucket() {
        _bucket.value = emptyList()
        _bucketFailed.value = emptySet()
    }

    fun markBucketFailed(uri: String, failed: Boolean) {
        _bucketFailed.value = if (failed) _bucketFailed.value + uri else _bucketFailed.value - uri
    }

    fun onSendFinished(uri: String) {
        _bucket.value = _bucket.value.filter { it.uri != uri }
    }

    fun makeFileItem(uri: Uri): BucketItem? {
        val resolver = app.contentResolver
        var name = uri.lastPathSegment ?: return null
        var size = 0L
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
                }
            }
        }
        return BucketItem(uri.toString(), name, "file", size)
    }

    fun makeFolderItem(dir: File): BucketItem? {
        if (!dir.isDirectory) return null
        val name = dir.name.takeIf { it.isNotBlank() } ?: return null
        return BucketItem(dir.absolutePath, name, "folder", 0)
    }

    fun sendBucket(peerId: String, items: List<BucketItem>): Boolean {
        if (items.isEmpty()) return false
        if (peerId.startsWith(BLE_CONVERSATION_PREFIX)) {
            scope.launch { runCatching { sendBleFiles(peerId, items) } }
            // 文件已经交给发送队列，暂存桶随即清空。
            clearBucket()
            return true
        }
        val peer = _peers.value.find { it.id == peerId } ?: return false
        val started = sender.send(peer, items)
        // 文件已经交给发送队列，暂存桶随即清空。
        if (started) clearBucket()
        return started
    }

    fun cancelSend(messageId: String) {
        val message = messages.value.firstOrNull { it.id == messageId }
        if (message?.channel == "ble") {
            ble?.cancelFile(messageId)
            scope.launch { db.messageDao().setStatus(messageId, "canceled", null, now()) }
            return
        }
        sender.cancel(messageId)
    }

    suspend fun retrySend(message: MessageEntity) {
        if (message.kind == "text") {
            retryText(message)
            return
        }
        if (message.conversationId.startsWith(BLE_CONVERSATION_PREFIX)) {
            retryBleFile(message)
            return
        }
        sender.retry(message)
    }

    private suspend fun retryBleFile(message: MessageEntity) {
        val manager = ble ?: throw IllegalStateException("蓝牙未启用")
        val path = message.localPath ?: throw IllegalStateException("文件不存在")
        val peerId = message.conversationId.removePrefix(BLE_CONVERSATION_PREFIX)
        val data = withContext(Dispatchers.IO) {
            runCatching { File(path).readBytes() }.getOrNull()
        } ?: throw IllegalStateException("文件不存在")
        db.messageDao().setStatus(message.id, "active", null, now())
        try {
            manager.sendFile(peerId, message.id, message.fileName ?: File(path).name, data)
            db.messageDao().setStatus(message.id, "done", null, now())
        } catch (error: Exception) {
            db.messageDao().setStatus(message.id, "failed", error.message ?: "发送失败", now())
            throw error
        }
    }

    suspend fun retryText(message: MessageEntity) {
        if (message.direction != "out" || message.kind != "text") return
        val body = message.body?.trim().orEmpty()
        if (body.isEmpty()) return
        if (message.conversationId.startsWith(BLE_CONVERSATION_PREFIX)) {
            val manager = ble ?: throw IllegalStateException("蓝牙未启用")
            val peerId = message.conversationId.removePrefix(BLE_CONVERSATION_PREFIX)
            db.messageDao().setStatus(message.id, "sending", null, now())
            try {
                manager.sendText(peerId, body, message.id)
                db.messageDao().setStatus(message.id, "done", null, now())
            } catch (error: Exception) {
                db.messageDao().setStatus(message.id, "failed", error.message, now())
                throw error
            }
            return
        }
        val current = api ?: throw IllegalStateException("服务未运行")
        val isPublic = message.conversationId == PUBLIC_CONVERSATION_ID
        db.messageDao().setStatus(message.id, "sending", null, now())
        try {
            withContext(Dispatchers.IO) {
                current.post(
                    "/v1/text",
                    JSONObject()
                        .put("body", body)
                        .put("to", if (isPublic) "" else message.conversationId)
                )
            }
            db.messageDao().setStatus(message.id, "done", null, now())
            if (isPublic) {
                scope.launch {
                    delay(2000)
                    runCatching { refreshInbox() }
                }
            }
        } catch (error: Exception) {
            db.messageDao().setStatus(message.id, "failed", error.message, now())
            throw error
        }
    }

    fun ensureConversation(id: String, name: String) {
        scope.launch { ensureConversationNow(id, name) }
    }

    fun renameBleConversation(id: String, name: String) {
        if (name.isBlank() || !id.startsWith(BLE_CONVERSATION_PREFIX)) return
        scope.launch {
            val dao = db.conversationDao()
            val existing = dao.get(id) ?: return@launch
            val peerId = id.removePrefix(BLE_CONVERSATION_PREFIX)
            if (existing.name == id || existing.name == peerId || existing.name.isBlank()) {
                dao.rename(id, name)
            }
        }
    }

    private suspend fun ensureConversationNow(id: String, name: String) {
        val dao = db.conversationDao()
        val existing = dao.get(id)
        if (existing == null) {
            dao.upsert(ConversationEntity(id, name.ifBlank { id }, createdAt = now()))
        }
    }

    companion object {
        fun now(): Long = System.currentTimeMillis() / 1000

        fun mapStatus(status: String?): String = when (status) {
            "pending", "active", "done", "duplicate", "failed", "canceled", "peer_canceled",
            "rejected", "sending", "accepted" -> status
            "expired" -> "failed"
            null, "" -> "done"
            else -> "done"
        }
    }
}

data class CacheCleanResult(val parts: Int, val freedBytes: Long, val records: Int)

// 边写边上报已发出的字节数，测速的每秒样本才不会因为整块传完才计数而忽高忽低。
private class CountingBody(
    private val bytes: ByteArray,
    private val type: MediaType?,
    private val onBytes: (Int) -> Unit
) : RequestBody() {
    override fun contentType(): MediaType? = type

    override fun contentLength(): Long = bytes.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        val step = 64 * 1024
        var off = 0
        while (off < bytes.size) {
            val n = minOf(step, bytes.size - off)
            sink.write(bytes, off, n)
            off += n
            onBytes(n)
        }
    }
}
