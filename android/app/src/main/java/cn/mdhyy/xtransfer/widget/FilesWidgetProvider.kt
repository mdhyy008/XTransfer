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
import java.io.File

class FilesWidgetProvider : AppWidgetProvider() {

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
                ComponentName(context, FilesWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            val views = build(context)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun build(context: Context): RemoteViews {
            val repo = (context.applicationContext as XlanApp).repo
            val dir = File(repo.recvDirPath.value)
            val files = dir.listFiles()
                ?.filter { !it.name.startsWith(".") && it.isFile }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()

            val views = RemoteViews(context.packageName, R.layout.widget_files)
            views.setTextViewText(
                R.id.widget_files_count,
                context.getString(R.string.widget_files_count, files.size)
            )
            views.setViewVisibility(
                R.id.widget_files_empty,
                if (files.isEmpty()) View.VISIBLE else View.GONE
            )

            val rows = intArrayOf(
                R.id.widget_files_row1, R.id.widget_files_row2, R.id.widget_files_row3
            )
            val names = intArrayOf(
                R.id.widget_files_name1, R.id.widget_files_name2, R.id.widget_files_name3
            )
            val sizes = intArrayOf(
                R.id.widget_files_size1, R.id.widget_files_size2, R.id.widget_files_size3
            )
            val dividers = intArrayOf(R.id.widget_files_div1, R.id.widget_files_div2)
            rows.indices.forEach { index ->
                val file = files.getOrNull(index)
                if (file == null) {
                    views.setViewVisibility(rows[index], View.GONE)
                    return@forEach
                }
                views.setViewVisibility(rows[index], View.VISIBLE)
                views.setTextViewText(names[index], file.name)
                views.setTextViewText(sizes[index], sizeLabel(file.length()))
                views.setOnClickPendingIntent(
                    rows[index],
                    openFile(context, file.absolutePath, index)
                )
            }
            dividers.indices.forEach { index ->
                views.setViewVisibility(
                    dividers[index],
                    if (files.size > index + 1) View.VISIBLE else View.GONE
                )
            }

            views.setOnClickPendingIntent(R.id.widget_files_empty, openPage(context))
            views.setOnClickPendingIntent(R.id.widget_files_count, openPage(context))
            return views
        }

        private fun openPage(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("page", "files")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        private fun openFile(context: Context, path: String, index: Int): PendingIntent =
            PendingIntent.getActivity(
                context,
                100 + index,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("open_file", path)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun sizeLabel(bytes: Long): String {
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
}
