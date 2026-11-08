package cn.mdhyy.xtransfer.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.Calendar
import java.util.zip.CRC32
import kotlin.concurrent.thread

data class ShareStatus(
    val running: Boolean = false,
    val url: String = "",
    val count: Int = 0,
    val bytes: Long = 0,
    val error: String? = null
)

/**
 * 在局域网里用 HTTP 把暂存桶的文件分发出去，手机浏览器扫码即可下载。
 * 文件按索引寻址，文件名只出现在响应头里，省得处理各种编码。
 */
class LanShareServer(
    private val app: Application,
    private val repo: Repository
) {
    private val _status = MutableStateFlow(ShareStatus())
    val status: StateFlow<ShareStatus> = _status.asStateFlow()

    private var server: ServerSocket? = null

    @Volatile
    private var files: List<ShareEntry> = emptyList()

    suspend fun start(items: List<BucketItem>): ShareStatus = withContext(Dispatchers.IO) {
        val host = repo.lan.value.info?.cidr?.substringBefore('/')
        if (host.isNullOrBlank()) {
            return@withContext publish(ShareStatus(error = "未连接到局域网"))
        }
        val collected = mutableListOf<ShareEntry>()
        items.forEach { collect(it, collected) }
        // 暂存桶为空也照常启动，之后往桶里加文件会立即出现在分发页上。
        val current = server
        if (current == null || current.isClosed) {
            val socket = runCatching { ServerSocket(0) }.getOrElse {
                return@withContext publish(ShareStatus(error = it.message ?: "启动失败"))
            }
            server = socket
            thread(name = "lan-share-accept", isDaemon = true) { acceptLoop(socket) }
        }
        files = collected
        publish(
            ShareStatus(
                running = true,
                url = "http://$host:${server?.localPort}",
                count = collected.size,
                bytes = collected.sumOf { it.size }
            )
        )
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        files = emptyList()
        _status.value = ShareStatus()
    }

    private fun publish(next: ShareStatus): ShareStatus {
        _status.value = next
        return next
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            thread(name = "lan-share-client", isDaemon = true) {
                runCatching { handle(client) }
                runCatching { client.close() }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 10_000
        val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val request = reader.readLine() ?: return
        var rangeHeader: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) {
                rangeHeader = line.substringAfter(':').trim()
            }
        }
        val parts = request.split(' ')
        if (parts.size < 2) return
        val path = parts[1].substringBefore('?')
        val out = BufferedOutputStream(socket.getOutputStream())

        if (path == "/" || path == "/index.html") {
            val body = indexHtml().toByteArray(Charsets.UTF_8)
            writeHead(out, 200, "text/html; charset=utf-8", body.size.toLong(), "Cache-Control: no-store\r\n")
            out.write(body)
            out.flush()
            return
        }

        if (path == "/z") {
            serveZip(out)
            return
        }

        val segments = path.trim('/').split('/')
        val index = segments.getOrNull(1)?.toIntOrNull()
        val entry = if (segments.size == 2 && index != null) files.getOrNull(index) else null
        if (entry == null || index == null) {
            val body = "Not Found".toByteArray()
            writeHead(out, 404, "text/plain; charset=utf-8", body.size.toLong(), null)
            out.write(body)
            out.flush()
            return
        }

        if (segments[0] == "v") {
            val view = previewKindOf(entry.name)
            if (view == null) {
                val body = "Not Found".toByteArray()
                writeHead(out, 404, "text/plain; charset=utf-8", body.size.toLong(), null)
                out.write(body)
                out.flush()
                return
            }
            val body = previewHtml(index, entry, view).toByteArray(Charsets.UTF_8)
            writeHead(out, 200, "text/html; charset=utf-8", body.size.toLong(), "Cache-Control: no-store\r\n")
            out.write(body)
            out.flush()
            return
        }

        if (segments[0] == "t") {
            val data = if (previewKindOf(entry.name) == "image") thumbnailOf(entry) else null
            if (data == null) {
                val body = "Not Found".toByteArray()
                writeHead(out, 404, "text/plain; charset=utf-8", body.size.toLong(), null)
                out.write(body)
                out.flush()
                return
            }
            writeHead(
                out,
                200,
                "image/jpeg",
                data.size.toLong(),
                "Cache-Control: max-age=600\r\n"
            )
            out.write(data)
            out.flush()
            return
        }

        if (segments[0] != "f" && segments[0] != "r") {
            val body = "Not Found".toByteArray()
            writeHead(out, 404, "text/plain; charset=utf-8", body.size.toLong(), null)
            out.write(body)
            out.flush()
            return
        }

        val inline = segments[0] == "r"
        serveFile(out, entry, inline, rangeHeader)
    }

    private fun serveFile(
        out: BufferedOutputStream,
        entry: ShareEntry,
        inline: Boolean,
        rangeHeader: String?
    ) {
        val total = entry.size
        val range = parseRange(rangeHeader, total)
        val stream = entry.open()
        if (stream == null) {
            val body = "Gone".toByteArray()
            writeHead(out, 404, "text/plain; charset=utf-8", body.size.toLong(), null)
            out.write(body)
            out.flush()
            return
        }
        stream.use {
            val kind = if (inline) "inline" else "attachment"
            val disposition = "Content-Disposition: $kind; filename=\"${asciiFileName(entry.name)}\"; " +
                "filename*=UTF-8''${encodeName(entry.name)}\r\n"
            val type = mimeOf(entry.name)
            if (range != null) {
                skipFully(it, range.first)
                writeHead(
                    out,
                    206,
                    type,
                    range.last - range.first + 1,
                    "Content-Range: bytes ${range.first}-${range.last}/$total\r\n$disposition"
                )
                copy(it, out, range.last - range.first + 1)
            } else {
                writeHead(out, 200, type, total, disposition)
                copy(it, out, total)
            }
            out.flush()
        }
    }

    private fun collect(item: BucketItem, out: MutableList<ShareEntry>) {
        if (item.kind == "folder") {
            if (item.uri.startsWith("/")) {
                val root = File(item.uri)
                if (!root.isDirectory) return
                val base = root.absolutePath
                root.walkTopDown()
                    .filter { it.isFile && !it.name.startsWith(".") }
                    .forEach { file ->
                        val name = file.absolutePath.removePrefix(base).trimStart(File.separatorChar)
                        out += ShareEntry(name.ifBlank { file.name }, file.length()) { file.inputStream() }
                    }
            } else {
                val root = runCatching { DocumentFile.fromTreeUri(app, Uri.parse(item.uri)) }.getOrNull()
                    ?: return
                walk(root, item.name.trimEnd('/'), out)
            }
        } else {
            val uri = runCatching { Uri.parse(item.uri) }.getOrNull() ?: return
            val known = runCatching {
                app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull() ?: -1L
            if (known > 0) {
                out += ShareEntry(item.name, known) {
                    runCatching { app.contentResolver.openInputStream(uri) }.getOrNull()
                }
            } else {
                // 拿不到长度的文档先落到缓存里，否则没法给浏览器一个准确的 Content-Length。
                val copy = runCatching {
                    val dir = File(app.cacheDir, "share").apply { mkdirs() }
                    val target = File(dir, "entry-${System.nanoTime()}-${item.name}")
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@runCatching null
                    target
                }.getOrNull() ?: return
                out += ShareEntry(item.name, copy.length()) { copy.inputStream() }
            }
        }
    }

    private fun walk(dir: DocumentFile, prefix: String, out: MutableList<ShareEntry>) {
        for (child in dir.listFiles()) {
            val name = if (prefix.isBlank()) child.name.orEmpty() else "$prefix/${child.name.orEmpty()}"
            if (child.isDirectory) {
                walk(child, name, out)
            } else if (!name.substringAfterLast('/').startsWith(".")) {
                val uri = child.uri
                out += ShareEntry(name, child.length()) {
                    runCatching { app.contentResolver.openInputStream(uri) }.getOrNull()
                }
            }
        }
    }

    private fun previewKindOf(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg") -> "image"
            in setOf("mp4", "m4v", "webm", "mov", "mkv") -> "video"
            in setOf("mp3", "wav", "m4a", "flac", "ogg", "oga", "opus") -> "audio"
            "pdf" -> "pdf"
            in setOf(
                "txt", "md", "markdown", "log", "json", "csv", "tsv", "xml", "yaml", "yml",
                "ini", "conf", "toml", "js", "mjs", "cjs", "ts", "tsx", "jsx", "html", "htm",
                "css", "scss", "less", "py", "java", "kt", "kts", "go", "c", "cpp", "cc", "h",
                "hpp", "cs", "rs", "rb", "php", "swift", "lua", "sh", "bash", "zsh", "bat",
                "cmd", "ps1", "sql", "gradle", "properties", "env"
            ) -> "text"
            else -> null
        }
    }

    private val ICON_EYE =
        """<svg viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M2.6 12S6.1 5.6 12 5.6 21.4 12 21.4 12 17.9 18.4 12 18.4 2.6 12 2.6 12Z"/><circle cx="12" cy="12" r="2.9"/></svg>"""

    private val ICON_DOWNLOAD =
        """<svg viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3.8v11.4"/><path d="m7.4 10.6 4.6 4.6 4.6-4.6"/><path d="M4.8 19.6h14.4"/></svg>"""

    private val ICON_ZIP =
        """<svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M6.2 3.2h11.6v17.6H6.2z"/><path d="M10.4 3.2v1.8M13.6 5v1.8M10.4 6.8v1.8M13.6 8.6v1.8M10.4 10.4v1.8M13.6 12.2v1.8"/><rect x="9.8" y="15.6" width="4.4" height="3.4" rx="1"/></svg>"""

    // Bootstrap 随安装包走，分发页在没有外网的内网里也能正常显示。
    private val bootstrapCss: String by lazy {
        runCatching {
            app.assets.open("bootstrap.min.css").bufferedReader().use { it.readText() }
        }.getOrDefault("").substringAfter("@charset \"UTF-8\";")
    }

    private fun shareStyle(): String = """
        body{font-family:-apple-system,"MiSans","Microsoft YaHei",system-ui,sans-serif;font-size:15px}
        .share-wrap{max-width:780px}
        .share-thumb{position:relative;flex:0 0 auto;width:46px;height:46px;border-radius:12px;overflow:hidden;display:grid;place-items:center;background:var(--bs-tertiary-bg)}
        .share-thumb img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}
        .share-badge{font-size:10px;font-weight:600;letter-spacing:.02em;color:var(--bs-primary)}
        .share-act{width:38px;height:38px;display:grid;place-items:center;padding:0}
        .min-w-0{min-width:0}
        .share-pre{margin:0;padding:16px;border-radius:12px;font:13px/1.55 ui-monospace,Menlo,Consolas,monospace;white-space:pre-wrap;word-break:break-word;max-height:78vh;overflow:auto}
        @media (max-width:575.98px){.share-zip{width:100%}}
    """.trimIndent()

    private fun themeScript(): String =
        """<script>document.documentElement.setAttribute('data-bs-theme',window.matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light')</script>"""

    private fun indexHtml(): String {
        val current = files
        val total = current.sumOf { it.size }
        val rows = current.mapIndexed { index, entry ->
            val view = previewKindOf(entry.name)
            val href = if (view != null) "/v/$index" else "/f/$index"
            val badge = escapeHtml(entry.name.substringAfterLast('.', "").uppercase().ifBlank { "文件" })
            val thumb = if (view == "image") {
                """<span class="share-thumb"><span class="share-badge">$badge</span><img src="/t/$index" alt="" loading="lazy" onerror="this.remove()"></span>"""
            } else {
                """<span class="share-thumb"><span class="share-badge">$badge</span></span>"""
            }
            val preview = if (view != null) {
                """<a class="btn btn-sm btn-light border-0 share-act" href="/v/$index" title="预览">$ICON_EYE</a>"""
            } else {
                ""
            }
            """<li class="list-group-item d-flex align-items-center gap-2 py-2 px-2 px-sm-3"><a class="d-flex align-items-center gap-3 flex-grow-1 min-w-0 text-reset text-decoration-none" href="$href">$thumb<span class="d-block min-w-0"><span class="d-block text-truncate">${escapeHtml(entry.name)}</span><span class="d-block small text-body-secondary">${readableSize(entry.size)}</span></span></a><span class="d-flex flex-shrink-0 gap-1">$preview<a class="btn btn-sm btn-light border-0 share-act" href="/f/$index" title="下载">$ICON_DOWNLOAD</a></span></li>"""
        }.joinToString("")
        val list = if (current.isEmpty()) {
            """<div class="text-center text-body-secondary py-5">暂无可分发的文件</div>"""
        } else {
            """<ul class="list-group list-group-flush rounded-4 overflow-hidden shadow-sm">$rows</ul>"""
        }
        val pack = if (current.isEmpty()) {
            ""
        } else {
            """<a class="btn btn-primary rounded-pill share-zip flex-shrink-0 d-inline-flex align-items-center justify-content-center gap-2" href="/z">${ICON_ZIP}打包下载</a>"""
        }
        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>X互传 · 内网分发</title>
            ${themeScript()}
            <style>$bootstrapCss</style>
            <style>${shareStyle()}</style>
            </head>
            <body class="bg-body-tertiary">
            <div class="container py-4 share-wrap">
            <div class="d-flex flex-column flex-sm-row align-items-sm-center justify-content-sm-between gap-3 mb-4">
            <div class="min-w-0"><h1 class="h4 mb-1">X互传</h1><p class="small text-body-secondary mb-0">${current.size} 个文件 · ${readableSize(total)}</p></div>
            $pack
            </div>
            $list
            </div>
            </body>
            </html>
        """.trimIndent()
    }

    private val thumbs = java.util.Collections.synchronizedMap(HashMap<String, ByteArray>())

    private fun thumbnailOf(entry: ShareEntry): ByteArray? {
        val key = "${entry.name}|${entry.size}"
        thumbs[key]?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        entry.open()?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 240 && bounds.outHeight / (sample * 2) >= 240) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = entry.open()?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val scaled = if (bitmap.width > 240) {
            val height = (bitmap.height * 240L / bitmap.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, 240, height, true)
        } else {
            bitmap
        }
        val data = ByteArrayOutputStream().use { buffer ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, buffer)
            buffer.toByteArray()
        }
        runCatching { scaled.recycle() }
        if (scaled !== bitmap) runCatching { bitmap.recycle() }
        if (thumbs.size > 64) thumbs.clear()
        thumbs[key] = data
        return data
    }

    private data class ZipItem(
        val name: ByteArray,
        val entry: ShareEntry,
        val crc: Long,
        val time: Int,
        val day: Int
    )

    private fun serveZip(out: BufferedOutputStream) {
        val used = HashSet<String>()
        val items = ArrayList<ZipItem>()
        var total = 22L
        for (entry in files) {
            val crc = crcOf(entry) ?: continue
            val name = uniqueZipName(entry.name, used).toByteArray(Charsets.UTF_8)
            val stamp = dosStamp()
            items += ZipItem(name, entry, crc, stamp.first, stamp.second)
            total += 30L + name.size + entry.size + 46L + name.size
        }
        if (total > 0xFFFFFFFEL) {
            val body = "文件过大".toByteArray()
            writeHead(out, 413, "text/plain; charset=utf-8", body.size.toLong(), null)
            out.write(body)
            out.flush()
            return
        }
        val disposition = "Content-Disposition: attachment; filename=\"xshare.zip\"; " +
            "filename*=UTF-8''${encodeName("X互传.zip")}\r\n"
        writeHead(out, 200, "application/zip", total, disposition)
        val central = ByteArrayOutputStream()
        var offset = 0L
        for (item in items) {
            out.write(zipLocalHeader(item.name, item.crc, item.entry.size, item.time, item.day))
            val stream = item.entry.open()
            if (stream != null) stream.use { copy(it, out, item.entry.size) }
            central.write(zipCentralEntry(item.name, item.crc, item.entry.size, offset, item.time, item.day))
            offset += 30L + item.name.size + item.entry.size
        }
        out.write(central.toByteArray())
        out.write(zipEnd(items.size, central.size().toLong(), offset))
        out.flush()
    }

    private fun crcOf(entry: ShareEntry): Long? {
        val stream = entry.open() ?: return null
        return stream.use {
            val crc = CRC32()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = it.read(buffer)
                if (read <= 0) break
                crc.update(buffer, 0, read)
            }
            crc.value
        }
    }

    private fun dosStamp(): Pair<Int, Int> {
        val now = Calendar.getInstance()
        val time = (now.get(Calendar.HOUR_OF_DAY) shl 11) or
            (now.get(Calendar.MINUTE) shl 5) or
            (now.get(Calendar.SECOND) shr 1)
        val day = ((now.get(Calendar.YEAR) - 1980) shl 9) or
            ((now.get(Calendar.MONTH) + 1) shl 5) or
            now.get(Calendar.DAY_OF_MONTH)
        return time to day
    }

    private fun uniqueZipName(name: String, used: MutableSet<String>): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var candidate = name
        var n = 1
        while (!used.add(candidate.lowercase())) {
            n += 1
            candidate = "$base ($n)$ext"
        }
        return candidate
    }

    private fun le16(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte()
    )

    private fun le32(value: Long): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte()
    )

    private fun zipLocalHeader(
        name: ByteArray,
        crc: Long,
        size: Long,
        time: Int,
        day: Int
    ): ByteArray {
        val head = ByteArrayOutputStream(30 + name.size)
        head.write(le32(0x04034b50L))
        head.write(le16(20))
        head.write(le16(0x0800))
        head.write(le16(0))
        head.write(le16(time))
        head.write(le16(day))
        head.write(le32(crc))
        head.write(le32(size))
        head.write(le32(size))
        head.write(le16(name.size))
        head.write(le16(0))
        head.write(name)
        return head.toByteArray()
    }

    private fun zipCentralEntry(
        name: ByteArray,
        crc: Long,
        size: Long,
        offset: Long,
        time: Int,
        day: Int
    ): ByteArray {
        val head = ByteArrayOutputStream(46 + name.size)
        head.write(le32(0x02014b50L))
        head.write(le16(20))
        head.write(le16(20))
        head.write(le16(0x0800))
        head.write(le16(0))
        head.write(le16(time))
        head.write(le16(day))
        head.write(le32(crc))
        head.write(le32(size))
        head.write(le32(size))
        head.write(le16(name.size))
        head.write(le16(0))
        head.write(le16(0))
        head.write(le16(0))
        head.write(le16(0))
        head.write(le32(0))
        head.write(le32(offset))
        head.write(name)
        return head.toByteArray()
    }

    private fun zipEnd(entries: Int, size: Long, offset: Long): ByteArray {
        val head = ByteArrayOutputStream(22)
        head.write(le32(0x06054b50L))
        head.write(le16(0))
        head.write(le16(0))
        head.write(le16(entries))
        head.write(le16(entries))
        head.write(le32(size))
        head.write(le32(offset))
        head.write(le16(0))
        return head.toByteArray()
    }

    private fun previewHtml(index: Int, entry: ShareEntry, view: String): String {
        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>${escapeHtml(entry.name)}</title>
            ${themeScript()}
            <style>$bootstrapCss</style>
            <style>${shareStyle()}</style>
            </head>
            <body class="bg-body-tertiary">
            <div class="container py-4 share-wrap">
            <div class="mb-3"><h1 class="h5 mb-1 text-break">${escapeHtml(entry.name)}</h1><p class="small text-body-secondary mb-0">${readableSize(entry.size)}</p></div>
            <div id="view" class="bg-body rounded-4 shadow-sm p-2" data-kind="$view" data-index="$index" data-size="${entry.size}">
            <div class="text-center text-body-secondary py-5">载入中</div>
            </div>
            </div>
            <script>
            (function(){
              var box=document.getElementById('view');
              var kind=box.dataset.kind,index=Number(box.dataset.index),size=Number(box.dataset.size);
              var raw='/r/'+index;
              if(kind==='image'){box.innerHTML='<img class="img-fluid w-100 rounded-3 d-block" alt="" src="'+raw+'">'}
              else if(kind==='video'){box.innerHTML='<video class="w-100 rounded-3 d-block bg-black" controls playsinline autoplay src="'+raw+'"></video>'}
              else if(kind==='audio'){box.innerHTML='<audio class="w-100 my-3" controls autoplay src="'+raw+'"></audio>'}
              else if(kind==='pdf'){box.innerHTML='<iframe class="w-100 rounded-3 border-0" style="height:78vh" src="'+raw+'"></iframe>'}
              else if(kind==='text'){
                if(size>2097152){box.innerHTML='<div class="text-center text-body-secondary py-5">文件较大</div>'}
                else{fetch(raw).then(function(r){return r.text()}).then(function(t){
                  var pre=document.createElement('pre');pre.className='share-pre bg-body-tertiary';pre.textContent=t;box.innerHTML='';box.appendChild(pre);
                }).catch(function(){box.innerHTML='<div class="text-center text-body-secondary py-5">载入失败</div>'})}
              }
            })();
            </script>
            </body>
            </html>
        """.trimIndent()
    }

    private data class ShareEntry(
        val name: String,
        val size: Long,
        val open: () -> InputStream?
    )

    private fun writeHead(
        out: OutputStream,
        code: Int,
        type: String,
        length: Long,
        extra: String?
    ) {
        val phrase = when (code) {
            200 -> "OK"
            206 -> "Partial Content"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            else -> "Error"
        }
        val head = buildString {
            append("HTTP/1.1 $code $phrase\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: $length\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Connection: close\r\n")
            if (extra != null) append(extra)
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
    }

    private fun copy(input: InputStream, output: OutputStream, limit: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = limit
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        val buffer = ByteArray(16 * 1024)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) break
            remaining -= read
        }
    }

    private fun parseRange(header: String?, total: Long): LongRange? {
        if (header == null || total <= 0) return null
        if (!header.startsWith("bytes=", ignoreCase = true)) return null
        val spec = header.substringAfter('=').substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startText = spec.substring(0, dash).trim()
        val endText = spec.substring(dash + 1).trim()
        if (startText.isEmpty()) {
            val suffix = endText.toLongOrNull() ?: return null
            if (suffix <= 0) return null
            val from = (total - suffix).coerceAtLeast(0)
            return from until total
        }
        val start = startText.toLongOrNull() ?: return null
        if (start !in 0 until total) return null
        val end = endText.toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1)
        if (end < start) return null
        return start..end
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    private fun encodeName(name: String): String =
        URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    private fun asciiFileName(name: String): String =
        name.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }.joinToString("")

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun readableSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit += 1
        }
        return if (unit == 0) "${value.toInt()} ${units[unit]}"
        else String.format("%.1f %s", value, units[unit])
    }
}
