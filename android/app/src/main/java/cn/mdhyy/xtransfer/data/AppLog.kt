package cn.mdhyy.xtransfer.data

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// 应用内日志缓冲。普通应用读不到自己的 logcat，设置里的日志页从这里取。
object AppLog {
    private const val MAX_LINES = 1500
    private val lines = ArrayDeque<String>()
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        append('D', tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        append('I', tag, message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        Log.w(tag, message, error)
        append('W', tag, detail(message, error))
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        Log.e(tag, message, error)
        append('E', tag, detail(message, error))
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() {
        synchronized(lines) { lines.clear() }
    }

    private fun detail(message: String, error: Throwable?): String {
        if (error == null) return message
        return "$message：${error.message ?: error.javaClass.simpleName}"
    }

    private fun append(level: Char, tag: String, message: String) {
        val line = "${format.format(Date())} $level/$tag $message"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }
}
