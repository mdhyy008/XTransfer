package cn.mdhyy.xtransfer.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

data class AppSettings(
    val theme: String = "system",
    val seed: String = "purple",
    val notifications: Boolean = true,
    val publicNotifications: Boolean = false,
    val quickPreview: Boolean = true,
    val enterToSend: Boolean = false,
    val misans: Boolean = true,
    val webChat: Boolean = false,
    val blocked: Set<String> = emptySet(),
    val aliases: Map<String, String> = emptyMap(),
    val muted: Set<String> = emptySet(),
    val pinned: Set<String> = emptySet(),
    // 已删除的设备 → 删除时刻（秒）。设备再次被发现（lastSeen 更新）后自然重新出现。
    val hidden: Map<String, Long> = emptyMap()
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("xt_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(
        AppSettings(
            theme = prefs.getString("theme", "system") ?: "system",
            seed = prefs.getString("seed", "purple") ?: "purple",
            notifications = prefs.getBoolean("notifications", true),
            publicNotifications = prefs.getBoolean("publicNotifications", false),
            quickPreview = prefs.getBoolean("quickPreview", true),
            enterToSend = prefs.getBoolean("enterToSend", false),
            misans = prefs.getBoolean("misans", true),
            webChat = prefs.getBoolean("webChat", false),
            blocked = prefs.getStringSet("blocked", emptySet()) ?: emptySet(),
            aliases = readAliases(prefs.getString("aliases", null)),
            muted = prefs.getStringSet("muted", emptySet()) ?: emptySet(),
            pinned = prefs.getStringSet("pinned", emptySet()) ?: emptySet(),
            hidden = readTimeMap(prefs.getString("hidden", null))
        )
    )
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(
        theme: String? = null,
        seed: String? = null,
        notifications: Boolean? = null,
        publicNotifications: Boolean? = null,
        quickPreview: Boolean? = null,
        enterToSend: Boolean? = null,
        misans: Boolean? = null,
        webChat: Boolean? = null,
        blocked: Set<String>? = null,
        aliases: Map<String, String>? = null,
        muted: Set<String>? = null,
        pinned: Set<String>? = null,
        hidden: Map<String, Long>? = null
    ) {
        val current = _settings.value
        val next = current.copy(
            theme = theme ?: current.theme,
            seed = seed ?: current.seed,
            notifications = notifications ?: current.notifications,
            publicNotifications = publicNotifications ?: current.publicNotifications,
            quickPreview = quickPreview ?: current.quickPreview,
            enterToSend = enterToSend ?: current.enterToSend,
            misans = misans ?: current.misans,
            webChat = webChat ?: current.webChat,
            blocked = blocked ?: current.blocked,
            aliases = aliases ?: current.aliases,
            muted = muted ?: current.muted,
            pinned = pinned ?: current.pinned,
            hidden = hidden ?: current.hidden
        )
        prefs.edit()
            .putString("theme", next.theme)
            .putString("seed", next.seed)
            .putBoolean("notifications", next.notifications)
            .putBoolean("publicNotifications", next.publicNotifications)
            .putBoolean("quickPreview", next.quickPreview)
            .putBoolean("enterToSend", next.enterToSend)
            .putBoolean("misans", next.misans)
            .putBoolean("webChat", next.webChat)
            .putStringSet("blocked", next.blocked)
            .putString("aliases", JSONObject(next.aliases).toString())
            .putStringSet("muted", next.muted)
            .putStringSet("pinned", next.pinned)
            .putString("hidden", JSONObject(next.hidden.toMap()).toString())
            .apply()
        _settings.value = next
    }

    private fun readAliases(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                json.keys().forEach { key ->
                    json.optString(key).takeIf { it.isNotBlank() }?.let { put(key, it) }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun readTimeMap(raw: String?): Map<String, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                json.keys().forEach { key ->
                    val at = json.optLong(key, 0)
                    if (at > 0) put(key, at)
                }
            }
        }.getOrDefault(emptyMap())
    }
}

object XlanConfig {
    private fun file(dir: File) = File(dir, "config.json")

    fun read(dir: File): JSONObject =
        runCatching { JSONObject(file(dir).readText()) }.getOrDefault(JSONObject())

    fun deviceName(dir: File): String? =
        read(dir).optString("name").takeIf { it.isNotBlank() }

    // 与 xlan 的 core.LoadDevice 保持一致：id 文件缺失时生成 16 位十六进制并落盘。
    fun deviceId(dir: File): String {
        val idFile = File(dir, "id")
        val existing = runCatching { idFile.readText().trim() }.getOrDefault("")
        if (existing.isNotEmpty()) return existing
        val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val id = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        runCatching { idFile.writeText("$id\n") }
        return id
    }

    fun setDeviceName(dir: File, name: String) {
        val json = read(dir)
        json.put("name", name)
        file(dir).writeText(json.toString(2))
    }

    fun recvDir(dir: File): String? =
        read(dir).optString("recv_dir").takeIf { it.isNotBlank() }

    fun setRecvDir(dir: File, recvDir: String) {
        val json = read(dir)
        json.put("recv_dir", recvDir)
        file(dir).writeText(json.toString(2))
    }

    fun clearRecvDir(dir: File) {
        val json = read(dir)
        json.remove("recv_dir")
        file(dir).writeText(json.toString(2))
    }
}
