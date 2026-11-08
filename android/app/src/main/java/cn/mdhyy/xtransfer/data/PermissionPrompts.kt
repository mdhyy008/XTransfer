package cn.mdhyy.xtransfer.data

import android.content.Context

/** 记住哪些权限已经向用户要过，用来区分「还没问」和「明确拒绝」。 */
object PermissionPrompts {
    const val STORAGE = "storage"
    const val NOTIFICATIONS = "notifications"
    const val NEARBY = "nearby"
    const val BLUETOOTH = "bluetooth"
    const val INSTALL = "install"

    private const val PREFS = "permission_prompts"

    fun mark(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key, true)
            .apply()
    }

    fun asked(context: Context, key: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, false)
}
