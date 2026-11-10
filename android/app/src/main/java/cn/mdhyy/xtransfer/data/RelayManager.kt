package cn.mdhyy.xtransfer.data

import android.app.Application
import android.os.Build
import cn.mdhyy.xtransfer.XlanApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min

const val RELAY_CONVERSATION_PREFIX = "relay:"
const val RELAY_PUBLIC_ROOM_ID = "public"
const val RELAY_PUBLIC_ROOM_NAME = "公网广播"

private const val RECONNECT_MIN = 2_000L
private const val RECONNECT_MAX = 30_000L
private const val ACK_TIMEOUT = 15_000L
private const val DEVICE_FILE = "relay-device"

data class RelayRoom(
    val id: String,
    val code: String,
    val name: String,
    val creator: String,
    val memberCount: Int,
    val createdAt: Long
)

data class RelayMember(
    val deviceId: String,
    val name: String,
    val platform: String?,
    val online: Boolean,
    val creator: Boolean
)

data class RelayState(
    val configured: Boolean = false,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val address: String = "",
    val deviceId: String = "",
    val serverName: String = "",
    val maxFileSize: Long = 0,
    val error: String? = null,
    val rooms: List<RelayRoom> = emptyList(),
    val members: Map<String, List<RelayMember>> = emptyMap()
)

class RelayManager(
    private val app: Application,
    private val db: AppDatabase,
    private val settingsStore: SettingsStore,
    private val recvDir: () -> File
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val deviceId: String = loadDeviceId()

    private val _state = MutableStateFlow(RelayState(deviceId = deviceId))
    val state: StateFlow<RelayState> = _state.asStateFlow()

    @Volatile
    private var ws: WebSocket? = null
    private var generation = 0
    private var address = ""
    private var connected = false
    private var connecting = false
    private var error = ""
    private var serverName = ""
    private var maxFileSize = 0L
    private var rooms: MutableList<RelayRoom> = mutableListOf()
    private var members: MutableMap<String, List<RelayMember>> = mutableMapOf()
    private var retry = RECONNECT_MIN
    private var reconnectJob: Job? = null
    private var errorJob: Job? = null
    private var pendingRoom: CompletableDeferred<String>? = null
    private val pending = ConcurrentHashMap<String, Job>()
    private val downloading = ConcurrentHashMap.newKeySet<String>()

    init {
        scope.launch {
            settingsStore.settings.collect { setAddress(it.relayUrl) }
        }
    }

    fun setAddress(value: String) {
        val next = value.trim().trimEnd('/')
        if (next == address) {
            if (next.isNotEmpty() && ws == null && reconnectJob == null) open()
            return
        }
        closeSocket()
        address = next
        connected = false
        connecting = false
        error = ""
        rooms = mutableListOf()
        members = mutableMapOf()
        serverName = ""
        maxFileSize = 0
        if (next.isEmpty()) {
            scope.launch { removeRelayConversations() }
            emit()
            return
        }
        emit()
        open()
    }

    fun refreshProfile() {
        if (address.isEmpty()) return
        closeSocket()
        connected = false
        connecting = false
        emit()
        open()
    }

    suspend fun createRoom(name: String): String {
        requireOpen()
        return requestRoom(
            JSONObject()
                .put("type", "create")
                .put("name", name.trim().take(32))
        )
    }

    suspend fun joinRoom(code: String): String {
        val value = code.trim()
        if (!Regex("""^\d{6}$""").matches(value)) throw IllegalStateException("房间号是六位数字")
        requireOpen()
        return requestRoom(JSONObject().put("type", "join").put("code", value))
    }

    fun leaveRoom(roomId: String) {
        if (roomId.isEmpty()) return
        if (ws != null && connected) {
            sendSafe(JSONObject().put("type", "leave").put("room", roomId))
        } else {
            removeRoom(roomId)
        }
    }

    suspend fun sendText(roomId: String, body: String) {
        val text = body.trim()
        if (text.isEmpty() || roomId.isEmpty()) return
        requireOpen()
        val id = UUID.randomUUID().toString()
        val ts = now()
        val convId = ensureConversation(roomId, roomLabel(roomId))
        db.messageDao().insert(
            MessageEntity(
                id = id,
                conversationId = convId,
                direction = "out",
                kind = "text",
                channel = "relay",
                fromId = deviceId,
                fromName = selfName(),
                body = text,
                status = "sending",
                createdAt = ts,
                updatedAt = ts
            )
        )
        db.conversationDao().touch(convId, ts)
        trackAck(id)
        send(
            JSONObject()
                .put("type", "send")
                .put("id", id)
                .put("room", roomId)
                .put("kind", "text")
                .put("body", text)
        )
    }

    suspend fun sendFile(roomId: String, path: String, displayName: String, size: Long) {
        if (roomId.isEmpty()) return
        requireOpen()
        val file = File(path)
        if (!file.isFile) throw IllegalStateException("文件不存在")
        val limit = maxFileSize
        if (limit > 0 && file.length() > limit) {
            throw IllegalStateException("单个文件不能超过 ${limit / 1024 / 1024} MB")
        }
        val name = sanitizeName(displayName)
        val id = UUID.randomUUID().toString()
        val ts = now()
        val convId = ensureConversation(roomId, roomLabel(roomId))
        db.messageDao().insert(
            MessageEntity(
                id = id,
                conversationId = convId,
                direction = "out",
                kind = "file",
                channel = "relay",
                fromId = deviceId,
                fromName = selfName(),
                fileName = name,
                fileSize = file.length(),
                localPath = file.absolutePath,
                status = "sending",
                total = file.length(),
                createdAt = ts,
                updatedAt = ts
            )
        )
        db.conversationDao().touch(convId, ts)
        try {
            val fileId = upload(file, name)
            trackAck(id)
            send(
                JSONObject()
                    .put("type", "send")
                    .put("id", id)
                    .put("room", roomId)
                    .put("kind", if (isImage(name)) "image" else "file")
                    .put("fileId", fileId)
            )
        } catch (e: Exception) {
            db.messageDao().setStatus(id, "failed", e.message, now())
            throw e
        }
    }

    fun recall(roomId: String, messageId: String) {
        if (roomId.isEmpty() || messageId.isEmpty()) return
        send(JSONObject().put("type", "recall").put("room", roomId).put("id", messageId))
    }

    fun kick(roomId: String, target: String) {
        if (roomId.isEmpty() || target.isEmpty()) return
        send(JSONObject().put("type", "kick").put("room", roomId).put("target", target))
    }

    fun rename(roomId: String, name: String) {
        val value = name.trim().take(32)
        if (roomId.isEmpty() || value.isEmpty()) return
        send(JSONObject().put("type", "rename").put("room", roomId).put("name", value))
    }

    fun requestMembers(roomId: String) {
        if (roomId.isEmpty()) return
        send(JSONObject().put("type", "members").put("room", roomId))
    }

    private fun loadDeviceId(): String {
        val file = File(app.filesDir, DEVICE_FILE)
        val existing = runCatching { file.readText().trim() }.getOrDefault("")
        if (existing.matches(Regex("""^[A-Za-z0-9_-]{4,64}$"""))) return existing
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val id = "xr" + bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        runCatching { file.writeText("$id\n") }
        return id
    }

    private fun selfName(): String =
        XlanConfig.deviceName(app.filesDir)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL?.takeIf { it.isNotBlank() }
            ?: "安卓设备"

    private fun open() {
        if (address.isEmpty() || ws != null) return
        val url = try {
            wsUrl()
        } catch (_: Exception) {
            connecting = false
            error = "中继地址无效"
            emit()
            return
        }
        connecting = true
        error = ""
        emit()
        val gen = ++generation
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != generation) {
                    webSocket.close(1000, null)
                    return
                }
                ws = webSocket
                connected = true
                connecting = false
                retry = RECONNECT_MIN
                error = ""
                emit()
                scope.launch { loadInfo() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != generation) return
                handle(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (gen == generation) webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen != generation) return
                ws = null
                connected = false
                connecting = false
                failPending()
                error = "与中继服务器的连接已断开"
                emit()
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen != generation) return
                ws = null
                connected = false
                connecting = false
                failPending()
                error = t.message?.takeIf { it.isNotBlank() } ?: "无法连接中继服务器"
                emit()
                scheduleReconnect()
            }
        }
        ws = try {
            http.newWebSocket(Request.Builder().url(url).build(), listener)
        } catch (e: Exception) {
            connecting = false
            error = e.message ?: "无法连接中继服务器"
            emit()
            scheduleReconnect()
            return
        }
    }

    private fun closeSocket() {
        generation++
        val socket = ws
        ws = null
        reconnectJob?.cancel()
        reconnectJob = null
        try {
            socket?.close(1000, null)
        } catch (_: Exception) {
            // 已经关闭
        }
        failPending()
    }

    private fun scheduleReconnect() {
        if (address.isEmpty() || ws != null || reconnectJob?.isActive == true) return
        connecting = true
        emit()
        val delayMs = retry
        retry = min(RECONNECT_MAX, retry * 2)
        reconnectJob = scope.launch {
            delay(delayMs)
            reconnectJob = null
            open()
        }
    }

    private fun wsUrl() = run {
        // OkHttp 的 WebSocket 只接受 http/https 地址，升级由它自己完成；
        // 写成 ws:// 会让 toHttpUrl() 直接抛异常。
        "${origin()}/ws".toHttpUrl().newBuilder()
            .addQueryParameter("device", deviceId)
            .addQueryParameter("name", selfName())
            .addQueryParameter("platform", "android")
            .build()
    }

    private fun origin(): String =
        if (address.contains("://")) address else "https://$address"

    suspend fun test(value: String): String = withContext(Dispatchers.IO) {
        val raw = value.trim().trimEnd('/')
        if (raw.isEmpty()) throw IllegalStateException("请填写服务器地址")
        val url = (if (raw.contains("://")) raw else "https://$raw").toHttpUrl()
        val call = http.newCall(
            Request.Builder()
                .url(url)
                .header("accept", "application/json")
                .build()
        )
        call.timeout().timeout(5, TimeUnit.SECONDS)
        call.execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("服务器返回 ${response.code}")
            val data = JSONObject(response.body?.string().orEmpty())
            if (!data.optBoolean("ok")) throw IllegalStateException("地址无效")
            data.optString("name").trim()
        }
    }

    private suspend fun loadInfo() = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(
                Request.Builder()
                    .url(origin())
                    .header("accept", "application/json")
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) return@use
                val data = JSONObject(response.body?.string().orEmpty())
                val max = data.optLong("maxFileSize")
                val name = data.optString("name").trim()
                if (max > 0) maxFileSize = max
                if (name.isNotEmpty()) serverName = name
                if (max > 0 || name.isNotEmpty()) emit()
            }
        }
    }

    private fun handle(raw: String) {
        val data = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (data.optString("type")) {
            "ready" -> handleReady(data)
            "joined" -> handleJoined(data)
            "left", "kicked" -> removeRoom(data.optString("room"))
            "room" -> handleRoom(data)
            "members" -> handleMembers(data)
            "message" -> data.optJSONObject("message")?.let { ingest(it, true) }
            "history" -> handleHistory(data)
            "ack" -> handleAck(data.optString("id"))
            "recalled" -> handleRecalled(data.optString("id"))
            "error" -> handleError(data.optString("message").ifBlank { "操作失败" })
        }
    }

    private fun handleReady(data: JSONObject) {
        val array = data.optJSONArray("rooms") ?: JSONArray()
        val next = ArrayList<RelayRoom>(array.length())
        for (i in 0 until array.length()) {
            val room = toRoom(array.optJSONObject(i))
            if (room.id.isNotEmpty()) next += room
        }
        rooms = next
        sortRooms()
        emit()
        scope.launch {
            reconcileConversations()
            for (room in rooms.toList()) {
                ensureConversation(room.id, roomLabel(room.id))
                sendSafe(JSONObject().put("type", "history").put("room", room.id).put("limit", 60))
                sendSafe(JSONObject().put("type", "members").put("room", room.id))
            }
        }
    }

    private fun handleJoined(data: JSONObject) {
        val room = toRoom(data.optJSONObject("room"))
        if (room.id.isEmpty()) return
        val index = rooms.indexOfFirst { it.id == room.id }
        if (index >= 0) rooms[index] = room else rooms += room
        sortRooms()
        emit()
        scope.launch {
            ensureConversation(room.id, roomLabel(room.id))
            sendSafe(JSONObject().put("type", "history").put("room", room.id).put("limit", 60))
            sendSafe(JSONObject().put("type", "members").put("room", room.id))
        }
        pendingRoom?.let {
            if (!it.isCompleted) it.complete(room.id)
            pendingRoom = null
        }
    }

    private fun handleRoom(data: JSONObject) {
        val room = toRoom(data.optJSONObject("room"))
        if (room.id.isEmpty()) return
        val index = rooms.indexOfFirst { it.id == room.id }
        if (index >= 0) rooms[index] = room else rooms += room
        sortRooms()
        emit()
        scope.launch { ensureConversation(room.id, roomLabel(room.id)) }
    }

    private fun handleMembers(data: JSONObject) {
        val roomId = data.optString("room")
        if (roomId.isEmpty()) return
        val array = data.optJSONArray("members") ?: JSONArray()
        val list = ArrayList<RelayMember>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("deviceId")
            if (id.isEmpty()) continue
            list += RelayMember(
                deviceId = id,
                name = item.optString("name").ifBlank { id },
                platform = item.optString("platform").takeIf { it.isNotBlank() },
                online = item.optBoolean("online", true),
                creator = item.optBoolean("creator", false)
            )
        }
        members = members.toMutableMap().apply { put(roomId, list) }
        emit()
    }

    private fun handleHistory(data: JSONObject) {
        val array = data.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until array.length()) {
            val message = array.optJSONObject(i) ?: continue
            ingest(message, false)
        }
    }

    private fun ingest(raw: JSONObject, live: Boolean) {
        val id = raw.optString("id")
        val roomId = raw.optString("room")
        if (id.isEmpty() || roomId.isEmpty()) return
        val mine = raw.optString("from") == deviceId
        val kind = if (raw.optString("kind") == "text" || raw.optString("kind").isEmpty()) "text" else "file"
        val fileName = raw.optString("fileName").takeIf { it.isNotBlank() }
        val fileId = raw.optString("fileId").takeIf { it.isNotBlank() }
        val fromName = raw.optString("fromName").ifBlank { raw.optString("from") }
        val createdAt = raw.optLong("createdAt").takeIf { it > 0 } ?: now()
        scope.launch {
            val convId = ensureConversation(roomId, roomLabel(roomId))
            val existing = db.messageDao().get(id)
            val status = if (kind == "file" && !mine && existing?.localPath.isNullOrBlank()) {
                if (roomId == RELAY_PUBLIC_ROOM_ID) "done" else "active"
            } else {
                "done"
            }
            db.messageDao().insert(
                MessageEntity(
                    id = id,
                    conversationId = convId,
                    direction = if (mine) "out" else "in",
                    kind = kind,
                    channel = "relay",
                    fromId = raw.optString("from"),
                    fromName = fromName.takeIf { it.isNotBlank() },
                    body = raw.optString("body").takeIf { it.isNotEmpty() },
                    fileName = fileName,
                    fileSize = if (raw.has("fileSize")) raw.optLong("fileSize") else null,
                    localPath = existing?.localPath,
                    status = status,
                    total = if (raw.has("fileSize")) raw.optLong("fileSize") else 0,
                    recalled = raw.optBoolean("recalled", false),
                    relayFileId = fileId,
                    createdAt = createdAt,
                    updatedAt = now()
                )
            )
            db.conversationDao().touch(convId, createdAt)
            if (kind == "file" && !mine && !fileId.isNullOrBlank() &&
                existing?.localPath.isNullOrBlank() && roomId != RELAY_PUBLIC_ROOM_ID
            ) {
                download(id, fileId, fileName ?: "文件")
            }
            if (live && !mine) {
                notifyIncoming(roomId, fromName, kind, raw.optString("body"), fileName)
            }
        }
    }

    private fun download(id: String, fileId: String, name: String) {
        if (!downloading.add(id)) return
        scope.launch {
            try {
                val target = fetchTo(fileId, name, recvDir())
                db.messageDao().applyItemUpdate(
                    id,
                    "done",
                    target.absolutePath,
                    target.name,
                    target.length(),
                    now()
                )
                MediaPublisher.scan(app, target)
            } catch (_: Exception) {
                db.messageDao().setStatus(id, "failed", null, now())
            } finally {
                downloading.remove(id)
            }
        }
    }

    suspend fun cacheFile(message: MessageEntity): File = withContext(Dispatchers.IO) {
        val existing = message.localPath?.takeIf { it.isNotBlank() }?.let(::File)
        if (existing != null && existing.exists()) return@withContext existing
        val fileId = message.relayFileId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("文件不可用")
        val target = fetchTo(fileId, message.fileName ?: "文件", File(app.filesDir, "relay"))
        db.messageDao().applyItemUpdate(
            message.id,
            "done",
            target.absolutePath,
            target.name,
            target.length(),
            now()
        )
        target
    }

    suspend fun saveFile(message: MessageEntity): File = withContext(Dispatchers.IO) {
        val local = message.localPath?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.exists() }
        val dir = recvDir().apply { mkdirs() }
        val target = if (local != null) {
            val dest = uniqueFile(dir, sanitizeName(message.fileName ?: local.name))
            local.copyTo(dest)
            dest
        } else {
            val fileId = message.relayFileId?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("文件不可用")
            fetchTo(fileId, message.fileName ?: "文件", dir)
        }
        db.messageDao().applyItemUpdate(
            message.id,
            "done",
            target.absolutePath,
            target.name,
            target.length(),
            now()
        )
        MediaPublisher.scan(app, target)
        target
    }

    private fun fetchTo(fileId: String, name: String, dir: File): File {
        val url = "${origin()}/files/$fileId".toHttpUrl()
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            dir.mkdirs()
            val target = uniqueFile(dir, sanitizeName(name))
            val stream = response.body?.byteStream() ?: throw IOException("响应为空")
            stream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return target
        }
    }

    private suspend fun upload(file: File, name: String): String = withContext(Dispatchers.IO) {
        val mime = mimeOf(name)
        val url = "${origin()}/files".toHttpUrl().newBuilder()
            .addQueryParameter("name", name)
            .addQueryParameter("mime", mime)
            .build()
        val body = file.asRequestBody(mime.toMediaType())
        http.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(text).optString("error") }.getOrDefault("")
                throw IllegalStateException(
                    when {
                        response.code == 413 -> detail.ifBlank { "文件过大" }
                        else -> detail.ifBlank { "上传失败：HTTP ${response.code}" }
                    }
                )
            }
            val fileId = runCatching { JSONObject(text).optString("id") }.getOrDefault("")
            if (fileId.isBlank()) throw IllegalStateException("上传失败")
            fileId
        }
    }

    private fun handleAck(id: String) {
        if (id.isEmpty()) return
        pending.remove(id)?.cancel()
        scope.launch { runCatching { db.messageDao().setStatus(id, "done", null, now()) } }
    }

    private fun handleRecalled(id: String) {
        if (id.isEmpty()) return
        scope.launch { runCatching { db.messageDao().setRecalled(id, now()) } }
    }

    private fun handleError(message: String) {
        error = message
        emit()
        errorJob?.cancel()
        errorJob = scope.launch {
            delay(5_000)
            if (error == message) {
                error = ""
                emit()
            }
        }
        pendingRoom?.let {
            if (!it.isCompleted) it.completeExceptionally(IllegalStateException(message))
            pendingRoom = null
        }
    }

    private suspend fun requestRoom(payload: JSONObject): String {
        if (pendingRoom != null) throw IllegalStateException("正在处理上一个请求")
        val deferred = CompletableDeferred<String>()
        pendingRoom = deferred
        return try {
            send(payload)
            withTimeout(8_000) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            throw IllegalStateException("服务器响应超时")
        } finally {
            if (pendingRoom === deferred) pendingRoom = null
        }
    }

    private fun trackAck(id: String) {
        pending.remove(id)?.cancel()
        pending[id] = scope.launch {
            delay(ACK_TIMEOUT)
            pending.remove(id)
            runCatching { db.messageDao().setStatus(id, "failed", null, now()) }
        }
    }

    private fun failPending() {
        for (id in pending.keys.toList()) {
            pending.remove(id)?.cancel()
            scope.launch { runCatching { db.messageDao().setStatus(id, "failed", null, now()) } }
        }
        pendingRoom?.let {
            if (!it.isCompleted) it.completeExceptionally(IllegalStateException("与中继服务器的连接已断开"))
            pendingRoom = null
        }
    }

    private fun requireOpen() {
        if (ws == null || !connected) throw IllegalStateException("公网聊天室未连接")
    }

    private fun send(payload: JSONObject) {
        val socket = ws
        if (socket == null || !connected) throw IllegalStateException("公网聊天室未连接")
        if (!socket.send(payload.toString())) throw IllegalStateException("发送失败")
    }

    private fun sendSafe(payload: JSONObject) {
        runCatching { send(payload) }
    }

    private suspend fun ensureConversation(roomId: String, name: String): String {
        val convId = RELAY_CONVERSATION_PREFIX + roomId
        val dao = db.conversationDao()
        val existing = dao.get(convId)
        if (existing == null) {
            dao.upsert(ConversationEntity(convId, name.ifBlank { roomId }, createdAt = now()))
        } else if (existing.name != name && name.isNotBlank()) {
            dao.rename(convId, name)
        }
        return convId
    }

    private suspend fun reconcileConversations() {
        val ids = rooms.mapTo(HashSet()) { RELAY_CONVERSATION_PREFIX + it.id }
        for (conv in db.conversationDao().all()) {
            if (!conv.id.startsWith(RELAY_CONVERSATION_PREFIX)) continue
            if (conv.id in ids) continue
            deleteConversationLocal(conv.id)
        }
    }

    private suspend fun removeRelayConversations() {
        for (conv in db.conversationDao().all()) {
            if (!conv.id.startsWith(RELAY_CONVERSATION_PREFIX)) continue
            deleteConversationLocal(conv.id)
        }
    }

    private suspend fun deleteConversationLocal(convId: String) {
        db.messageDao().deleteByConversation(convId)
        db.conversationDao().delete(convId)
    }

    private fun removeRoom(roomId: String) {
        if (roomId.isEmpty()) return
        rooms = rooms.filter { it.id != roomId }.toMutableList()
        members = members.toMutableMap().apply { remove(roomId) }
        emit()
        scope.launch { deleteConversationLocal(RELAY_CONVERSATION_PREFIX + roomId) }
    }

    private fun roomLabel(roomId: String): String {
        if (roomId == RELAY_PUBLIC_ROOM_ID) return RELAY_PUBLIC_ROOM_NAME
        val room = rooms.firstOrNull { it.id == roomId }
        return room?.name?.takeIf { it.isNotBlank() }
            ?: room?.code?.takeIf { it.isNotBlank() }
            ?: "聊天室"
    }

    private fun toRoom(raw: JSONObject?): RelayRoom {
        if (raw == null) return RelayRoom("", "", "", "", 0, 0)
        val id = raw.optString("id")
        val name = raw.optString("name").ifBlank {
            if (id == RELAY_PUBLIC_ROOM_ID) RELAY_PUBLIC_ROOM_NAME else "聊天室"
        }
        return RelayRoom(
            id = id,
            code = raw.optString("code"),
            name = name,
            creator = raw.optString("creator"),
            memberCount = raw.optInt("memberCount"),
            createdAt = raw.optLong("createdAt")
        )
    }

    private fun sortRooms() {
        rooms.sortWith(
            compareBy<RelayRoom> { if (it.id == RELAY_PUBLIC_ROOM_ID) 0 else 1 }
                .thenBy { it.createdAt }
        )
    }

    private fun emit() {
        _state.value = RelayState(
            configured = address.isNotEmpty(),
            connected = connected,
            connecting = connecting,
            address = address,
            deviceId = deviceId,
            serverName = serverName,
            maxFileSize = maxFileSize,
            error = error.takeIf { it.isNotBlank() },
            rooms = rooms.toList(),
            members = members.toMap()
        )
    }

    private fun notifyIncoming(
        roomId: String,
        fromName: String,
        kind: String,
        body: String,
        fileName: String?
    ) {
        val conf = settingsStore.settings.value
        if (!conf.notifications) return
        val convId = RELAY_CONVERSATION_PREFIX + roomId
        if (convId in conf.muted) return
        if (roomId == RELAY_PUBLIC_ROOM_ID && !conf.publicNotifications) return
        if (XlanApp.foreground) return
        val text = if (kind == "text") body else "发来文件 ${fileName.orEmpty()}"
        Notifications.message(app, fromName.ifBlank { "公网聊天室" }, text, convId)
    }

    private fun sanitizeName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
        return base.replace(Regex("""[\\/:*?"<>|]"""), "_").take(120).ifBlank { "文件" }
    }

    private fun uniqueFile(dir: File, name: String): File {
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var candidate = File(dir, name)
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, if (ext.isEmpty()) "$stem ($index)" else "$stem ($index).$ext")
            index += 1
        }
        return candidate
    }

    private fun isImage(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in
            setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg")

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    companion object {
        fun now(): Long = System.currentTimeMillis() / 1000
    }
}
