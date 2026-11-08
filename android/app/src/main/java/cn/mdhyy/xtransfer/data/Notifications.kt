package cn.mdhyy.xtransfer.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import cn.mdhyy.xtransfer.MainActivity
import cn.mdhyy.xtransfer.R
import java.util.Locale

data class TransferInfo(
    val name: String,
    val received: Long,
    val total: Long,
    val speedBps: Long,
    val count: Int
) {
    val percent: Int
        get() = if (total > 0) ((received * 100) / total).toInt().coerceIn(0, 100) else 0
}

object Notifications {
    const val SERVICE_ID = 1
    private const val SERVICE_CHANNEL = "service"
    private const val MESSAGE_CHANNEL = "message"
    private const val OFFER_CHANNEL = "offer"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(SERVICE_CHANNEL, context.getString(R.string.channel_service), NotificationManager.IMPORTANCE_LOW)
        )
        // 老版本按默认优先级建过这个频道，系统不会自己升档，删掉重建才能弹横幅。
        val existing = manager.getNotificationChannel(MESSAGE_CHANNEL)
        if (existing != null && existing.importance < NotificationManager.IMPORTANCE_HIGH) {
            manager.deleteNotificationChannel(MESSAGE_CHANNEL)
        }
        manager.createNotificationChannel(
            NotificationChannel(MESSAGE_CHANNEL, context.getString(R.string.channel_message), NotificationManager.IMPORTANCE_HIGH)
        )
        manager.createNotificationChannel(
            NotificationChannel(OFFER_CHANNEL, context.getString(R.string.channel_offer), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    /** 清掉旧版本遗留在通知栏里的常驻通知，避免同一条服务通知出现两份。 */
    fun clearStaleService(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.activeNotifications
                .filter { status ->
                    status.id != SERVICE_ID &&
                        (
                            status.notification.channelId == SERVICE_CHANNEL ||
                                status.notification.flags and Notification.FLAG_ONGOING_EVENT != 0
                            )
                }
                .forEach { status -> manager.cancel(status.tag, status.id) }
        }
    }

    fun service(context: Context, transfer: TransferInfo? = null): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(context, SERVICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_xlan)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (transfer == null) {
            builder
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText("为保证程序运行稳定，常驻通知")
            return builder.build()
        }

        val percent = transfer.percent
        builder
            .setContentTitle(if (transfer.count > 1) "正在传输 ${transfer.count} 项" else "正在传输")
            .setContentText("${transfer.name} · $percent%")
            .setSubText(speedText(transfer.speedBps))
            .setProgress(100, percent, false)

        // Android 16 起由系统统一承接实时活动（HyperOS 支持时显示在大胶囊/超级岛位置）。
        if (Build.VERSION.SDK_INT >= 36) {
            builder
                .setShortCriticalText("$percent%")
                .setStyle(Notification.ProgressStyle().setProgress(percent))
            if (context.getSystemService(NotificationManager::class.java)
                    ?.canPostPromotedNotifications() == true
            ) {
                builder.setRequestPromotedOngoing(true)
            }
        }
        return builder.build()
    }

    private fun speedText(bps: Long): String = when {
        bps >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB/s", bps / 1024.0 / 1024.0)
        bps >= 1024L -> String.format(Locale.ROOT, "%.0f KB/s", bps / 1024.0)
        else -> "$bps B/s"
    }

    /** 别人发来文件、等待确认接收时的提醒；确认前一直留在通知栏。 */
    fun offer(context: Context, title: String, body: String, convId: String, itemId: String) {
        if (itemId.isBlank()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("conv", convId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(
            context,
            offerId(itemId),
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        val accept = PendingIntent.getBroadcast(
            context,
            offerId(itemId) + 1,
            Intent(context, OfferActionReceiver::class.java)
                .setAction("cn.mdhyy.xtransfer.ACCEPT_OFFER")
                .putExtra("id", itemId)
                .putExtra("action", "accept"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val reject = PendingIntent.getBroadcast(
            context,
            offerId(itemId) + 2,
            Intent(context, OfferActionReceiver::class.java)
                .setAction("cn.mdhyy.xtransfer.REJECT_OFFER")
                .putExtra("id", itemId)
                .putExtra("action", "reject"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, OFFER_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_xlan)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_stat_xlan),
                    "接收",
                    accept
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_stat_xlan),
                    "拒绝",
                    reject
                ).build()
            )
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(offerId(itemId), notification) }
    }

    /** 接收完成的短暂提醒：几秒后自动消失。 */
    fun received(context: Context, itemId: String, title: String, name: String) {
        if (itemId.isBlank()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val notification = Notification.Builder(context, MESSAGE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_xlan)
            .setContentTitle("接收完成")
            .setContentText("$title：$name")
            .setCategory(Notification.CATEGORY_STATUS)
            .setTimeoutAfter(4000)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(("done:$itemId").hashCode(), notification) }
    }

    fun cancelOffer(context: Context, itemId: String) {
        if (itemId.isBlank()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.cancel(offerId(itemId)) }
    }

    private fun offerId(itemId: String): Int = "offer:$itemId".hashCode()

    fun message(context: Context, title: String, body: String, convId: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("conv", convId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(
            context,
            convId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, MESSAGE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_xlan)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(convId.hashCode(), notification) }
    }
}
