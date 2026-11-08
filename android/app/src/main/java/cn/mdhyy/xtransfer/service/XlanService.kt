package cn.mdhyy.xtransfer.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import cn.mdhyy.xtransfer.XlanApp
import cn.mdhyy.xtransfer.data.DEFAULT_RECV_FOLDER
import cn.mdhyy.xtransfer.data.MessageEntity
import cn.mdhyy.xtransfer.data.Notifications
import cn.mdhyy.xtransfer.data.TransferInfo
import cn.mdhyy.xtransfer.data.XlanConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mobile.Mobile
import mobile.Logger
import java.io.File
import java.net.DatagramSocket
import java.util.Locale

class XlanService : Service() {
    private var multicastLock: WifiManager.MulticastLock? = null
    private var worker: Thread? = null
    private val notifScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastTransfer: TransferInfo? = null
    @Volatile private var generation = 0

    private val repo get() = (application as XlanApp).repo

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startForeground(Notifications.SERVICE_ID, Notifications.service(this))
        Notifications.clearStaleService(this)
        multicastLock = (getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("xlan")
            .apply {
                setReferenceCounted(false)
                acquireSafely()
            }
        Mobile.setLogger(object : Logger {
            override fun log(msg: String) {
                Log.i(TAG, msg)
            }
        })
        boot()
        watchTransfers()
    }

    // 传输中把常驻通知切成实时进度；API 36 上请求系统升格为实时活动。
    private fun watchTransfers() {
        notifScope.launch {
            while (isActive) {
                val transfer = activeTransfer(repo.messages.value)
                if (transfer != lastTransfer) {
                    lastTransfer = transfer
                    runCatching {
                        getSystemService(NotificationManager::class.java)?.notify(
                            Notifications.SERVICE_ID,
                            Notifications.service(this@XlanService, transfer)
                        )
                    }
                }
                delay(NOTIFY_INTERVAL_MS)
            }
        }
    }

    private fun activeTransfer(messages: List<MessageEntity>): TransferInfo? {
        val active = messages.filter { message ->
            message.kind != "text" &&
                message.status in ACTIVE_STATUSES &&
                message.total > 0
        }
        if (active.isEmpty()) return null
        val largest = active.maxByOrNull { it.total } ?: return null
        return TransferInfo(
            name = largest.fileName ?: "文件",
            received = active.sumOf { it.received.coerceIn(0, it.total) },
            total = active.sumOf { it.total },
            speedBps = active.sumOf { it.speedBps },
            count = active.size
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTART) {
            boot(restart = true)
        }
        return START_STICKY
    }

    private fun boot(restart: Boolean = false) {
        if (!restart && worker?.isAlive == true) return
        val gen = ++generation
        worker = Thread({
            if (restart) {
                repo.onServiceRestarting()
                runCatching { Mobile.stop() }
            }
            ensureDeviceName()
            while (generation == gen) {
                val recvDir = XlanConfig.recvDir(filesDir)
                    ?: File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        DEFAULT_RECV_FOLDER
                    ).apply { mkdirs() }.absolutePath
                val started = runCatching {
                    Mobile.start(
                        filesDir.absolutePath,
                        recvDir,
                        false
                    )
                }
                if (generation != gen) return@Thread
                started.onSuccess { base ->
                    Log.i(TAG, "服务已就绪：$base")
                    repo.onServiceStarted(base)
                    return@Thread
                }.onFailure { error ->
                    Log.e(TAG, "启动失败", error)
                    repo.onServiceFailed(describeFailure(error))
                }
                try {
                    Thread.sleep(RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "xlan-start").apply { start() }
    }

    private fun ensureDeviceName() {
        val suffix = XlanConfig.deviceId(filesDir).takeLast(4)
        val current = XlanConfig.deviceName(filesDir)
        if (current != null && !LEGACY_DEVICE_NAME.matches(current)) return
        if (current != null && current.endsWith("#$suffix")) return
        XlanConfig.setDeviceName(filesDir, defaultDeviceName(suffix))
    }

    private fun defaultDeviceName(suffix: String): String {
        val brand = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase(Locale.ROOT)
        val nickname = when {
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> "小米辣"
            brand.contains("huawei") -> "华子"
            brand.contains("honor") -> "荣耀王者"
            brand.contains("oneplus") -> "一加一"
            brand.contains("oppo") -> "欧珀"
            brand.contains("vivo") -> "维沃"
            brand.contains("meizu") -> "魅族精灵"
            brand.contains("samsung") -> "星星星"
            brand.contains("google") -> "谷歌宝宝"
            brand.contains("realme") -> "真我小钢炮"
            else -> "小透明"
        }
        return "$nickname#$suffix"
    }

    // 发现端口固定为 43501/43502，被其他应用占用时只能等它释放后重试。
    private fun describeFailure(error: Throwable): String {
        val busy = BUSY_PORTS.firstOrNull { isUdpBusy(it) }
        if (busy != null) return "端口 $busy 被其他应用占用，正在重试"
        return error.message ?: "服务启动失败"
    }

    private fun isUdpBusy(port: Int): Boolean =
        runCatching { DatagramSocket(port).close() }.isFailure

    override fun onDestroy() {
        generation++
        notifScope.cancel()
        worker?.interrupt()
        worker = null
        repo.onServiceStopped()
        Thread {
            runCatching { Mobile.stop() }
        }.start()
        multicastLock?.release()
        multicastLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun WifiManager.MulticastLock.acquireSafely() {
        runCatching { acquire() }
    }

    companion object {
        private const val TAG = "xlan"
        private const val ACTION_RESTART = "cn.mdhyy.xtransfer.RESTART"
        private const val RETRY_DELAY_MS = 5000L
        private const val NOTIFY_INTERVAL_MS = 1000L
        private val BUSY_PORTS = listOf(43501, 43502)
        private val ACTIVE_STATUSES = setOf("active", "accepted", "sending")
        private val LEGACY_DEVICE_NAME =
            Regex("(小米辣|小米|华子|荣耀王者|一加一|欧珀|维沃|魅族精灵|星星星|谷歌宝宝|真我小钢炮|小透明)[ #][0-9a-fA-F]{4}")

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, XlanService::class.java)
            )
        }

        fun restart(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, XlanService::class.java).setAction(ACTION_RESTART)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, XlanService::class.java))
        }
    }
}
