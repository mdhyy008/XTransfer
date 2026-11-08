package cn.mdhyy.xtransfer.data

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

// 收件文件直接留在接收目录里，数据库只存真实路径；这里只做媒体库扫描和把 content:// 还原成真实文件。
object MediaPublisher {
    fun scan(context: Context, file: File) {
        runCatching {
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        }
    }

    fun realFile(context: Context, uri: Uri): File? {
        when (uri.scheme) {
            null, "", "file" -> return uri.path?.let(::File)?.takeIf { it.exists() }
            "content" -> Unit
            else -> return null
        }
        queryPath(context, uri, null)?.let { path ->
            File(path).takeIf { it.exists() }?.let { return it }
        }
        documentPath(context, uri)?.let { path ->
            File(path).takeIf { it.exists() }?.let { return it }
        }
        val last = uri.lastPathSegment?.let(Uri::decode).orEmpty()
        if (last.startsWith("/")) File(last).takeIf { it.exists() }?.let { return it }
        val id = uri.lastPathSegment?.toLongOrNull()
        if (id != null) {
            val files = MediaStore.Files.getContentUri("external")
            queryPath(context, files, "_id = $id")?.let { path ->
                File(path).takeIf { it.exists() }?.let { return it }
            }
        }
        return null
    }

    private fun queryPath(context: Context, uri: Uri, selection: String?): String? =
        runCatching {
            context.contentResolver
                .query(uri, arrayOf(MediaStore.MediaColumns.DATA), selection, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()

    private fun documentPath(context: Context, uri: Uri): String? {
        val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val relative = id.substringAfter(':', id)
        if (relative.startsWith("/")) return relative
        return File(Environment.getExternalStorageDirectory(), relative).absolutePath
    }
}
