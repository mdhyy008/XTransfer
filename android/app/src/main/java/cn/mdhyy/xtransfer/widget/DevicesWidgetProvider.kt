package cn.mdhyy.xtransfer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import cn.mdhyy.xtransfer.MainActivity
import cn.mdhyy.xtransfer.R
import cn.mdhyy.xtransfer.XlanApp
import cn.mdhyy.xtransfer.data.PUBLIC_CONVERSATION_ID

class DevicesWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val views = build(context)
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
    }

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, DevicesWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            val views = build(context)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun build(context: Context): RemoteViews {
            val repo = (context.applicationContext as XlanApp).repo
            val devices = repo.conversations.value.filter { it.id != PUBLIC_CONVERSATION_ID }
            val views = RemoteViews(context.packageName, R.layout.widget_devices)
            views.setTextViewText(
                R.id.widget_devices_count,
                context.getString(R.string.widget_devices_total, devices.size)
            )
            views.setViewVisibility(
                R.id.widget_devices_empty,
                if (devices.isEmpty()) View.VISIBLE else View.GONE
            )
            val rows = intArrayOf(
                R.id.widget_devices_row1, R.id.widget_devices_row2, R.id.widget_devices_row3
            )
            val names = intArrayOf(
                R.id.widget_devices_name1, R.id.widget_devices_name2, R.id.widget_devices_name3
            )
            val dividers = intArrayOf(R.id.widget_devices_div1, R.id.widget_devices_div2)
            rows.indices.forEach { index ->
                val device = devices.getOrNull(index)
                if (device == null) {
                    views.setViewVisibility(rows[index], View.GONE)
                    return@forEach
                }
                views.setViewVisibility(rows[index], View.VISIBLE)
                views.setTextViewText(names[index], device.name.ifBlank { "设备" })
                views.setOnClickPendingIntent(rows[index], openConversation(context, device.id, index))
            }
            dividers.indices.forEach { index ->
                views.setViewVisibility(
                    dividers[index],
                    if (devices.size > index + 1) View.VISIBLE else View.GONE
                )
            }
            views.setOnClickPendingIntent(R.id.widget_devices_count, openApp(context))
            views.setOnClickPendingIntent(R.id.widget_devices_empty, openApp(context))
            return views
        }

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        private fun openConversation(context: Context, id: String, index: Int): PendingIntent =
            PendingIntent.getActivity(
                context,
                200 + index,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("conv", id)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
