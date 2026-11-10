package cn.mdhyy.xtransfer.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

data class WebChatStatus(
    val running: Boolean = false,
    val url: String = "",
    val port: Int = 0,
    val online: Int = 0,
    val error: String? = null
)

/**
 * 在局域网里开放网页聊天：浏览器访问本机地址即可用访客身份在公共频道聊天。
 * 发消息走和 xlan 相同的 UDP 43502 广播协议，所以其他设备看到的是独立访客。
 */
class WebChatServer(
    context: Context,
    private val repo: Repository,
    private val hotspot: LocalHotspot
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val _status = MutableStateFlow(WebChatStatus())
    val status: StateFlow<WebChatStatus> = _status.asStateFlow()

    private var server: ServerSocket? = null
    private var ticker: Job? = null
    private val guests = ConcurrentHashMap<String, Long>()
    private val random = SecureRandom()

    private val page: ByteArray? by lazy {
        runCatching { app.assets.open("webchat.html").use { it.readBytes() } }.getOrNull()
    }

    fun startIfEnabled() {
        if (repo.settings.value.webChat) scope.launch { start() }
    }

    suspend fun start(): WebChatStatus = lock.withLock {
        withContext(Dispatchers.IO) {
            val current = server
            if (current != null && !current.isClosed) return@withContext publish()
            val html = page
            if (html == null) {
                return@withContext publish(WebChatStatus(error = "页面资源缺失"))
            }
            val socket = open() ?: return@withContext publish(
                WebChatStatus(error = _status.value.error ?: "启动失败")
            )
            server = socket
            thread(name = "web-chat-accept", isDaemon = true) { acceptLoop(socket) }
            ticker?.cancel()
            ticker = scope.launch {
                while (!socket.isClosed) {
                    delay(2_000)
                    if (server === socket) publish()
                }
            }
            publish()
        }
    }

    suspend fun stop(): WebChatStatus = lock.withLock {
        withContext(Dispatchers.IO) {
            ticker?.cancel()
            ticker = null
            val current = server
            server = null
            guests.clear()
            runCatching { current?.close() }
            publish()
        }
    }

    private fun open(): ServerSocket? {
        runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(WEB_PORT)) } }
            .onSuccess { return it }
        return runCatching { ServerSocket(0) }
            .onFailure { _status.value = WebChatStatus(error = it.message ?: "启动失败") }
            .getOrNull()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            thread(name = "web-chat-client", isDaemon = true) {
                runCatching { handle(client) }
                runCatching { client.close() }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 10_000
        val input = socket.getInputStream()
        val requestLine = readLine(input) ?: return
        var contentLength = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")
        val out = BufferedOutputStream(socket.getOutputStream())

        if (method == "GET" && (path == "/" || path == "/index.html")) {
            val html = page ?: return respond(out, 500, "页面资源缺失".toByteArray(), "text/plain; charset=utf-8")
            return respond(out, 200, html, "text/html; charset=utf-8")
        }

        if (method == "GET" && path == "/api/messages") {
            val me = queryParam(query, "me")
            val now = System.currentTimeMillis()
            if (me != null && GUEST_RE.matches(me)) guests[me] = now
            val messages = runBlocking { publicTexts() }
            return respond(out, 200, JSONObject().put("messages", messages).toString().toByteArray(Charsets.UTF_8))
        }

        if (method == "POST" && path == "/api/send") {
            if (contentLength > MAX_REQUEST) {
                return respond(out, 413, JSONObject().put("ok", false).put("error", "内容过长").toString().toByteArray())
            }
            // 必须按原始字节收包再统一按 UTF-8 解码，按字符流读会把中文读成乱码。
            val bytes = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(bytes, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            val payload = runCatching { JSONObject(String(bytes, 0, read, Charsets.UTF_8)) }.getOrNull()
                ?: return respond(out, 400, JSONObject().put("ok", false).put("error", "请求无效").toString().toByteArray())
            val result = runCatching { runBlocking { send(payload) } }
                .getOrElse { JSONObject().put("ok", false).put("error", "发送失败") }
            if (result.optBoolean("ok")) publish()
            return respond(out, if (result.optBoolean("ok")) 200 else 400, result.toString().toByteArray(Charsets.UTF_8))
        }

        respond(out, 404, JSONObject().put("ok", false).toString().toByteArray())
    }

    private suspend fun publicTexts(): JSONArray {
        val rows = repo.db.messageDao().recentTexts(PUBLIC_CONVERSATION_ID, 300).asReversed()
        val array = JSONArray()
        for (row in rows) {
            array.put(
                JSONObject()
                    .put("id", row.id)
                    .put("from_id", row.fromId)
                    .put("from_name", row.fromName ?: "")
                    .put("body", row.body ?: "")
                    .put("created_at", row.createdAt)
            )
        }
        return array
    }

    private suspend fun send(raw: JSONObject): JSONObject {
        val body = raw.optString("body").trim()
        if (body.isEmpty()) return JSONObject().put("ok", false).put("error", "内容为空")
        if (body.toByteArray(Charsets.UTF_8).size > MAX_BODY) {
            return JSONObject().put("ok", false).put("error", "内容过长")
        }
        val name = raw.optString("name").trim().take(15).ifBlank { "网页访客" }
        val guest = raw.optString("from").takeIf { GUEST_RE.matches(it) } ?: ("web" + hex(5))
        val id = "w" + System.currentTimeMillis().toString(16) + hex(3)
        val ts = System.currentTimeMillis() / 1000

        val packet = JSONObject()
            .put("v", 1)
            .put("t", "msg")
            .put("id", id)
            .put("sid", guest)
            .put("sname", name)
            .put("channel", "public")
            .put("body", body)
            .put("ts", ts)
        if (!broadcast(packet.toString(), id)) {
            return JSONObject().put("ok", false).put("error", "未连接到局域网")
        }
        repo.ingestWebGuestText(id, guest, name, body, ts)
        return JSONObject().put("ok", true).put("from", guest)
    }

    private fun broadcast(json: String, id: String): Boolean {
        val data = json.toByteArray(Charsets.UTF_8)
        val targets = broadcastTargets()
        var sent = 0
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                for (round in 0 until 2) {
                    for (target in targets) {
                        val address = runCatching { InetAddress.getByName(target) }.getOrNull() ?: continue
                        runCatching {
                            socket.send(DatagramPacket(data, data.size, address, PUBLIC_PORT))
                            sent += 1
                        }
                    }
                }
                if (sent == 0) return false
                // 谁先回执就说明已经送达，不用把整个窗口等满。
                val deadline = System.currentTimeMillis() + ACK_WINDOW_MS
                val buffer = ByteArray(2048)
                while (true) {
                    val remain = (deadline - System.currentTimeMillis()).toInt()
                    if (remain <= 0) break
                    socket.soTimeout = remain
                    val packet = DatagramPacket(buffer, buffer.size)
                    if (runCatching { socket.receive(packet) }.isFailure) break
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (runCatching { JSONObject(text).optString("id") == id }.getOrDefault(false)) break
                }
            }
        }
        return sent > 0
    }

    private fun broadcastTargets(): List<String> {
        val targets = LinkedHashSet<String>()
        // 热点开着时对端在热点网段里，广播必须发到这张网卡的广播地址。
        hotspot.linkAddress()?.let { (ip, prefix) ->
            broadcastOf(ip, prefix)?.let { targets.add(it) }
        }
        val cidr = repo.lan.value.info?.cidr
        if (cidr != null) {
            cidr.substringBefore('/').let { ip ->
                val prefix = cidr.substringAfter('/', "24").toIntOrNull() ?: 24
                broadcastOf(ip, prefix)?.let { targets.add(it) }
            }
        }
        // 有些无线 AP 不放行广播包，再按已发现的设备逐个单播一份。
        for (peer in repo.peers.value) {
            val addr = peer.addr ?: continue
            if (broadcastOf(addr, 32) != null) targets.add(addr)
        }
        targets.add("255.255.255.255")
        return targets.toList()
    }

    private fun broadcastOf(ip: String, prefix: Int): String? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        var value = 0L
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            value = (value shl 8) or octet.toLong()
        }
        val mask = if (prefix <= 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val broadcast = (value or (mask.inv() and 0xFFFFFFFFL)) and 0xFFFFFFFFL
        return listOf(24, 16, 8, 0).joinToString(".") { ((broadcast shr it) and 255).toString() }
    }

    private fun publish(): WebChatStatus {
        val next = computeStatus()
        _status.value = next
        return next
    }

    private fun publish(next: WebChatStatus): WebChatStatus {
        _status.value = next
        return next
    }

    private fun computeStatus(): WebChatStatus {
        val now = System.currentTimeMillis()
        val stale = guests.entries.filter { now - it.value > GUEST_ONLINE_MS }.map { it.key }
        stale.forEach { guests.remove(it) }
        val socket = server
        val running = socket != null && !socket.isClosed
        // 热点开着时优先用热点网卡地址，局域网地址此时普通设备是连不上的。
        val host = hotspot.linkAddress()?.first
            ?: repo.lan.value.info?.cidr?.substringBefore('/').orEmpty()
        return WebChatStatus(
            running = running,
            url = if (running && host.isNotBlank()) "http://$host:${socket.localPort}/" else "",
            port = if (running) socket.localPort else 0,
            online = if (running) guests.size else 0,
            error = if (running) null else _status.value.error
        )
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        var byte = input.read()
        if (byte < 0) return null
        while (byte >= 0 && byte != '\n'.code) {
            if (byte != '\r'.code) builder.append(byte.toChar())
            byte = input.read()
        }
        return builder.toString()
    }

    private fun respond(out: OutputStream, code: Int, body: ByteArray, type: String = "application/json; charset=utf-8") {
        val reason = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            else -> "Internal Server Error"
        }
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    private fun queryParam(query: String, key: String): String? {
        if (query.isEmpty()) return null
        for (pair in query.split('&')) {
            if (pair.substringBefore('=') == key) {
                return runCatching {
                    URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
                }.getOrNull()
            }
        }
        return null
    }

    private fun hex(bytes: Int): String {
        val data = ByteArray(bytes)
        random.nextBytes(data)
        return data.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val WEB_PORT = 43100
        const val PUBLIC_PORT = 43502
        const val MAX_BODY = 900
        const val MAX_REQUEST = 16 * 1024
        const val ACK_WINDOW_MS = 260L
        const val GUEST_ONLINE_MS = 5_000L
        val GUEST_RE = Regex("^web[0-9a-f]{10}$")
    }
}
