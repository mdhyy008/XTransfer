package cn.mdhyy.xtransfer.data

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
private val OCTET_TYPE = "application/octet-stream".toMediaType()
private const val CHUNK_SIZE = 4L * 1024 * 1024
private const val ACCEPT_TIMEOUT_MS = 60_000L

private class SendError(val code: String, message: String) : IOException(message)

private class SendJob(
    val messageId: String,
    val peer: Peer,
    val uri: Uri,
    val name: String,
    val kind: String,
    val size: Long
) {
    @Volatile var cancelled = false
    @Volatile var call: Call? = null
}

private data class ManifestEntry(
    val rel: String,
    val uri: Uri?,
    val size: Long,
    val sha256: String,
    val dir: Boolean
)

private data class Manifest(val root: String, val entries: List<ManifestEntry>) {
    val totalSize: Long get() = entries.sumOf { if (it.dir) 0L else it.size }
}

/** 直接走对端 HTTP 接口发文件：offer → 等确认 → 分块 PUT → 校验。 */
class FileSender(
    private val app: Application,
    private val db: AppDatabase,
    private val repo: Repository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, SendJob>()
    // 一次发出的文件最多三个同时传，其余排队等空位。
    private val gate = Semaphore(3)

    fun send(peer: Peer, items: List<BucketItem>): Boolean {
        val info = repo.info.value ?: return false
        if (items.isEmpty()) return false
        val now = nowSec()
        val queue = items.mapNotNull { item ->
            val uri = runCatching { Uri.parse(item.uri) }.getOrNull() ?: return@mapNotNull null
            // 能还原成真实文件就用本地路径，避免 content:// 授权过期后连自己都预览不了。
            val readUri = MediaPublisher.realFile(app, uri)
                ?.let { Uri.parse(it.absolutePath) }
                ?: uri
            SendJob(UUID.randomUUID().toString(), peer, readUri, item.name, item.kind, item.size)
        }
        if (queue.isEmpty()) return false
        // 先登记所有任务，排队中的也能被取消。
        for (job in queue) jobs[job.messageId] = job
        scope.launch {
            // 先把所有消息都落库，界面立刻显示全部文件，排队中的标 queued。
            for (job in queue) {
                db.messageDao().insert(
                    MessageEntity(
                        id = job.messageId,
                        conversationId = peer.id,
                        direction = "out",
                        kind = job.kind,
                        channel = "dm",
                        fromId = info.id,
                        fromName = info.name,
                        fileName = job.name,
                        fileSize = job.size,
                        localPath = job.uri.toString(),
                        status = "queued",
                        total = job.size,
                        createdAt = now,
                        updatedAt = now
                    )
                )
                repo.ensureConversation(peer.id, peer.name)
                db.conversationDao().touch(peer.id, now)
            }
            for (job in queue) {
                // 每个文件先各自把 offer 发出去，对方一次就能看到全部；
                // 真正上传最多三个并发，由 runJob 里的 gate 控制。
                launch { runJob(job) }
            }
        }
        return true
    }

    fun cancel(messageId: String) {
        val job = jobs[messageId]
        if (job != null) {
            job.cancelled = true
            job.call?.cancel()
            if (job.call == null) {
                // 还在排队，不必等空位，直接收口。
                scope.launch {
                    val message = db.messageDao().get(messageId) ?: return@launch
                    if (message.status == "queued") {
                        db.messageDao().setStatus(messageId, "canceled", null, nowSec())
                    }
                }
            }
            return
        }
        // 进程已经结束、job 已从表里摘掉的，也要立刻把状态收口，不能让按钮点了没反应。
        scope.launch {
            val message = db.messageDao().get(messageId) ?: return@launch
            if (message.status == "done" || message.status == "duplicate" ||
                message.status == "failed" || message.status == "canceled" ||
                message.status == "rejected"
            ) {
                return@launch
            }
            db.messageDao().setStatus(messageId, "canceled", null, nowSec())
        }
    }

    suspend fun retry(message: MessageEntity) {
        if (jobs.containsKey(message.id)) return
        val uriText = message.localPath ?: return
        val peer = repo.peers.value.firstOrNull { it.id == message.conversationId } ?: return
        val parsed = runCatching { Uri.parse(uriText) }.getOrNull() ?: return
        val real = MediaPublisher.realFile(app, parsed)
        val uri = real?.let { Uri.parse(it.absolutePath) } ?: parsed
        if (real != null) db.messageDao().setLocalPath(message.id, real.absolutePath, nowSec())
        db.messageDao().resetForRetry(message.id, nowSec())
        val job = SendJob(
            message.id,
            peer,
            uri,
            message.fileName ?: uri.lastPathSegment ?: "文件",
            message.kind,
            message.fileSize ?: 0
        )
        scope.launch { runJob(job) }
    }

    private suspend fun runJob(job: SendJob) {
        jobs[job.messageId] = job
        if (job.cancelled) {
            db.messageDao().setStatus(job.messageId, "canceled", null, nowSec())
            jobs.remove(job.messageId)
            return
        }
        repo.markBucketFailed(job.uri.toString(), false)
        try {
            val host = hostOf(job.peer)
            val base = "https://$host"
            val client = peerClient()
            val duplicated =
                if (job.kind == "folder") sendFolder(job, client, base) else sendFile(job, client, base)
            if (job.cancelled) throw CancellationException("canceled")
            db.messageDao().setStatus(
                job.messageId,
                if (duplicated) "duplicate" else "done",
                null,
                nowSec()
            )
            repo.onSendFinished(job.uri.toString())
        } catch (error: CancellationException) {
            db.messageDao().setStatus(job.messageId, "canceled", null, nowSec())
            throw error
        } catch (error: Exception) {
            if (job.cancelled) {
                db.messageDao().setStatus(job.messageId, "canceled", null, nowSec())
            } else {
                repo.markBucketFailed(job.uri.toString(), true)
                val code = if (error is SendError) error.code else "ERROR"
                db.messageDao().setStatus(job.messageId, "failed", code, nowSec())
            }
        } finally {
            jobs.remove(job.messageId)
        }
    }

    // ---------- 单文件 ----------

    private suspend fun sendFile(job: SendJob, client: OkHttpClient, base: String): Boolean {
        val size = contentSize(job.uri)
        val sha = sha256Of(job.uri)
        if (job.cancelled) throw CancellationException("canceled")
        val info = repo.info.value
        val offer = JSONObject()
            .put("from_id", info?.id ?: "")
            .put("from_name", info?.name ?: "")
            .put("to_id", job.peer.id)
            .put("kind", "file")
            .put("name", job.name)
            .put("size", size)
            .put("sha256", sha)
        val resp = postJson(job, client, "$base/v1/offer", offer)
        if (resp.optBoolean("duplicate")) {
            db.messageDao().setProgress(job.messageId, size, size, 0, nowSec())
            return true
        }
        val offerId = resp.optString("id")
        if (offerId.isEmpty()) throw SendError("ERROR", "对端没有返回传输编号")
        if (!resp.optBoolean("accepted")) {
            db.messageDao().setStatus(job.messageId, "sending", null, nowSec())
            waitAccepted(job, client, base, offerId)
        }
        awaitSlot(job, client, base, offerId)
        try {
            uploadRange(job, client, "$base/v1/offer/$offerId/data", job.uri, size, 0, size)
        } finally {
            gate.release()
        }
        return false
    }

    // ---------- 目录 ----------

    private suspend fun sendFolder(job: SendJob, client: OkHttpClient, base: String): Boolean {
        val manifest = buildManifest(job.uri, job.name)
        val total = manifest.totalSize
        if (job.cancelled) throw CancellationException("canceled")
        val entries = JSONArray()
        for (e in manifest.entries) {
            val item = if (e.dir) {
                JSONObject().put("rel", e.rel).put("dir", true)
            } else {
                JSONObject().put("rel", e.rel).put("size", e.size).put("sha256", e.sha256)
            }
            entries.put(item)
        }
        val info = repo.info.value
        val offer = JSONObject()
            .put("from_id", info?.id ?: "")
            .put("from_name", info?.name ?: "")
            .put("to_id", job.peer.id)
            .put("kind", "folder")
            .put("name", manifest.root)
            .put("size", total)
            .put("transfer_id", manifestId(manifest))
            .put(
                "manifest",
                JSONObject().put("root", manifest.root).put("entries", entries)
            )
        val resp = postJson(job, client, "$base/v1/offer", offer)
        val offerId = resp.optString("id")
        if (offerId.isEmpty()) throw SendError("ERROR", "对端没有返回传输编号")
        val accepted = resp.optBoolean("accepted")
        val alreadyDone = accepted && resp.optString("status") == "done"
        if (!accepted) {
            db.messageDao().setStatus(job.messageId, "sending", null, nowSec())
            waitAccepted(job, client, base, offerId)
        }

        val skip = mutableSetOf<String>()
        resp.optJSONArray("done_files")?.let { array ->
            for (i in 0 until array.length()) skip += array.optString(i)
        }

        if (!alreadyDone) {
            awaitSlot(job, client, base, offerId)
            try {
                var sent = 0L
                for (e in manifest.entries) {
                    if (e.dir) continue
                    if (e.rel in skip) {
                        sent += e.size
                        continue
                    }
                    if (e.size == 0L) continue
                    val url = "$base/v1/offer/$offerId/file/" + encodeRel(e.rel)
                    sent = uploadRange(job, client, url, e.uri ?: continue, e.size, sent, total, sendProgress = true)
                }
                postJson(job, client, "$base/v1/offer/$offerId/finish", JSONObject())
            } finally {
                gate.release()
            }
        }
        db.messageDao().setProgress(job.messageId, total, total, 0, nowSec())
        return false
    }

    private fun buildManifest(rootUri: Uri, rootName: String): Manifest {
        val path = filePath(rootUri)
        if (path != null) {
            if (!path.isDirectory) throw SendError("SOURCE", "无法读取文件夹")
            val entries = mutableListOf<ManifestEntry>()
            fun walk(dir: File, prefix: String) {
                val children = dir.listFiles().orEmpty().sortedBy { it.name }
                for (child in children) {
                    val name = child.name
                    val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                    if (child.isDirectory) {
                        entries += ManifestEntry(rel, null, 0, "", true)
                        walk(child, rel)
                    } else {
                        val uri = Uri.fromFile(child)
                        entries += ManifestEntry(rel, uri, child.length(), sha256Of(uri), false)
                    }
                }
            }
            walk(path, "")
            return Manifest(rootName, entries)
        }
        val root = DocumentFile.fromTreeUri(app, rootUri)
            ?: throw SendError("SOURCE", "无法读取文件夹")
        val entries = mutableListOf<ManifestEntry>()
        fun walk(dir: DocumentFile, prefix: String) {
            val children = dir.listFiles().sortedBy { it.name.orEmpty() }
            for (child in children) {
                val name = child.name ?: continue
                val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                if (child.isDirectory) {
                    entries += ManifestEntry(rel, null, 0, "", true)
                    walk(child, rel)
                } else {
                    val uri = child.uri
                    entries += ManifestEntry(rel, uri, child.length(), sha256Of(uri), false)
                }
            }
        }
        walk(root, "")
        return Manifest(rootName, entries)
    }

    private fun manifestId(manifest: Manifest): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("${manifest.root}\n".toByteArray())
        for (e in manifest.entries) {
            digest.update("${e.rel}|${e.dir}|${e.size}|${e.sha256}\n".toByteArray())
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return "d" + hex.take(15)
    }

    // ---------- 传输 ----------

    private suspend fun waitAccepted(job: SendJob, client: OkHttpClient, base: String, offerId: String) {
        val deadline = System.currentTimeMillis() + ACCEPT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (job.cancelled) throw CancellationException("canceled")
            delay(400)
            val state = getJson(job, client, "$base/v1/offer/$offerId")
            when (state.optString("status")) {
                "active", "done" -> return
                "failed", "expired" -> throw SendError("DENIED", "对方拒绝了")
            }
        }
        throw SendError("TIMEOUT", "等待对方确认超时")
    }

    /**
     * 抢一个上传空位。还没轮到的文件标记成排队中并定期查询对端，
     * 否则对方会把已经确认、但迟迟没数据的传输当成断线收掉。
     * 成功返回时调用方持有 gate 的一个名额，用完必须 release。
     */
    private suspend fun awaitSlot(job: SendJob, client: OkHttpClient, base: String, offerId: String) {
        var queued = false
        while (true) {
            if (job.cancelled) throw CancellationException("canceled")
            if (gate.tryAcquire()) return
            if (!queued) {
                queued = true
                db.messageDao().setStatus(job.messageId, "queued", null, nowSec())
            }
            val state = getJson(job, client, "$base/v1/offer/$offerId")
            when (state.optString("status")) {
                "failed", "expired" -> throw SendError("DENIED", "对方取消了")
            }
            delay(2000)
        }
    }

    private suspend fun uploadRange(
        job: SendJob,
        client: OkHttpClient,
        url: String,
        uri: Uri,
        size: Long,
        baseSent: Long,
        grandTotal: Long,
        sendProgress: Boolean = true
    ): Long {
        var off = 0L
        val started = System.currentTimeMillis()
        var sent = baseSent
        while (off < size) {
            if (job.cancelled) throw CancellationException("canceled")
            val n = minOf(CHUNK_SIZE, size - off)
            val buffer = readRange(uri, off, n.toInt())
            val request = Request.Builder()
                .url(url)
                .put(buffer.toRequestBody(OCTET_TYPE))
                .header("Content-Range", "bytes $off-${off + n - 1}/$size")
                .build()
            putWithRetry(job, client, request)
            off += n
            sent = baseSent + off
            if (sendProgress) {
                val elapsed = (System.currentTimeMillis() - started) / 1000.0
                val speed = if (elapsed > 0) (off / elapsed).toLong() else 0
                db.messageDao().setProgress(job.messageId, sent, grandTotal, speed, nowSec())
            }
        }
        return sent
    }

    private fun putWithRetry(job: SendJob, client: OkHttpClient, request: Request) {
        var last: Exception? = null
        repeat(3) {
            try {
                execute(job, client, request)
                return
            } catch (error: SendError) {
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                last = error
                if (job.cancelled) throw error
                Thread.sleep(500)
            }
        }
        throw last ?: SendError("ERROR", "上传失败")
    }

    private fun postJson(job: SendJob, client: OkHttpClient, url: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        return execute(job, client, request)
    }

    private fun getJson(job: SendJob, client: OkHttpClient, url: String): JSONObject =
        execute(job, client, Request.Builder().url(url).get().build())

    private fun execute(job: SendJob, client: OkHttpClient, request: Request): JSONObject {
        val call = client.newCall(request)
        job.call = call
        try {
            call.execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val json = runCatching { JSONObject(text) }.getOrNull()
                    val code = json?.optString("code").orEmpty().ifBlank { "HTTP ${resp.code}" }
                    val msg = json?.optString("msg").orEmpty().ifBlank { "HTTP ${resp.code}" }
                    throw SendError(code, msg)
                }
                return if (text.isBlank()) JSONObject() else JSONObject(text)
            }
        } catch (error: IOException) {
            if (job.cancelled) throw CancellationException("canceled")
            throw error
        } finally {
            if (job.call === call) job.call = null
        }
    }

    // ---------- 内容读取 ----------

    private fun contentSize(uri: Uri): Long {
        filePath(uri)?.let { if (it.isFile) return it.length() }
        var size = 0L
        runCatching {
            app.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0) size = cursor.getLong(index)
                }
            }
        }
        if (size <= 0L) size = DocumentFile.fromSingleUri(app, uri)?.length() ?: 0L
        return size
    }

    private fun sha256Of(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        open(uri).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readRange(uri: Uri, offset: Long, length: Int): ByteArray {
        val buffer = ByteArray(length)
        open(uri).use { input ->
            var skipped = 0L
            while (skipped < offset) {
                val step = input.skip(offset - skipped)
                if (step <= 0) {
                    if (input.read() < 0) throw SendError("SOURCE", "文件比声明的短")
                    skipped += 1
                } else {
                    skipped += step
                }
            }
            var read = 0
            while (read < length) {
                val n = input.read(buffer, read, length - read)
                if (n < 0) throw SendError("SOURCE", "文件比声明的短")
                read += n
            }
        }
        return buffer
    }

    private fun open(uri: Uri): InputStream {
        filePath(uri)?.let { return it.inputStream() }
        return app.contentResolver.openInputStream(uri)
            ?: throw SendError("SOURCE", "无法读取所选文件")
    }

    private fun filePath(uri: Uri): File? = when (uri.scheme) {
        null, "file" -> File(uri.path ?: uri.toString())
        else -> null
    }

    private fun encodeRel(rel: String): String =
        rel.split("/").joinToString("/") { Uri.encode(it) }

    private fun hostOf(peer: Peer): String = "${peer.addr}:${peer.port}"

    private fun nowSec(): Long = System.currentTimeMillis() / 1000
}
