package cn.mdhyy.xtransfer.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.mdhyy.xtransfer.XlanApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// 通知栏上的「接收 / 拒绝」按钮：不进界面也能直接处理。
class OfferActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id")?.takeIf { it.isNotBlank() } ?: return
        val action = intent.getStringExtra("action")?.takeIf { it == "reject" } ?: "accept"
        val app = context.applicationContext as? XlanApp ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                app.repo.decide(id, action)
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}
