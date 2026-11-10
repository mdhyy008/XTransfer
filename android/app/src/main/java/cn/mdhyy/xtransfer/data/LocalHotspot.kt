package cn.mdhyy.xtransfer.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

private const val HOTSPOT_PASSWORD = "33554488"

sealed interface HotspotState {
    data object Idle : HotspotState
    data object Starting : HotspotState
    data class Running(
        val ssid: String,
        val password: String,
        val startedAt: Long = System.currentTimeMillis()
    ) : HotspotState
    data class Failed(val message: String) : HotspotState
}

/** 用系统的临时热点（LocalOnlyHotspot）开一个不接外网的热点。 */
class LocalHotspot(context: Context) {
    private val appContext = context.applicationContext
    private val wifi = appContext.getSystemService(WifiManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow<HotspotState>(HotspotState.Idle)
    val state: StateFlow<HotspotState> = _state.asStateFlow()

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            wifi != null &&
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)

    val permission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    fun granted(): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (_state.value is HotspotState.Starting || _state.value is HotspotState.Running) return
        if (wifi == null) {
            _state.value = HotspotState.Failed("设备不支持临时热点")
            return
        }
        if (!granted()) {
            _state.value = HotspotState.Failed("没有热点权限")
            return
        }
        _state.value = HotspotState.Starting
        val callback = object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    val (ssid, password) = credentials(res)
                    if (ssid.isBlank()) {
                        runCatching { res.close() }
                        reservation = null
                        _state.value = HotspotState.Failed("无法获取热点信息")
                    } else {
                        _state.value = HotspotState.Running(ssid, password)
                    }
                }

                override fun onStopped() {
                    reservation = null
                    _state.value = HotspotState.Idle
                }

                override fun onFailed(reason: Int) {
                    reservation = null
                    _state.value = HotspotState.Failed(describe(reason))
                }
            }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val config = SoftApConfiguration.Builder()
                    .setPassphrase(HOTSPOT_PASSWORD, SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
                    .build()
                wifi.startLocalOnlyHotspotWithConfiguration(
                    config,
                    appContext.mainExecutor,
                    callback
                )
            } else {
                wifi.startLocalOnlyHotspot(callback, handler)
            }
        }.onFailure {
            _state.value = HotspotState.Failed(it.message ?: "热点开启失败")
        }
    }

    fun stop() {
        runCatching { reservation?.close() }
        reservation = null
        _state.value = HotspotState.Idle
    }

    /**
     * 热点开着的本机地址。热点的网卡不会出现在 ConnectivityManager 的网络列表里，
     * 只能从 NetworkInterface 里按无线/热点网卡名找 IPv4。
     */
    fun linkAddress(): Pair<String, Int>? {
        if (_state.value !is HotspotState.Running) return null
        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .filter {
                    val name = it.name.lowercase()
                    name.startsWith("wlan") || name.startsWith("ap") ||
                        name.startsWith("softap") || name.startsWith("eth")
                }
                .flatMap { it.interfaceAddresses.asSequence() }
                .firstNotNullOfOrNull { address ->
                    val ip = address.address
                    val host = ip.hostAddress
                    if (ip is Inet4Address && !ip.isLoopbackAddress &&
                        !ip.isLinkLocalAddress && host != null
                    ) {
                        host to address.networkPrefixLength.toInt()
                    } else {
                        null
                    }
                }
        }.getOrNull()
    }

    private fun credentials(res: WifiManager.LocalOnlyHotspotReservation): Pair<String, String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val config = res.softApConfiguration
            config.ssid.orEmpty() to config.passphrase.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            val config = res.wifiConfiguration
            (config?.SSID?.trim('"') ?: "") to (config?.preSharedKey ?: "")
        }

    private fun describe(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "系统限制了热点功能"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "当前状态下无法开启热点"
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "没有可用的热点信道"
        else -> "热点开启失败"
    }
}

fun wifiQrPayload(ssid: String, password: String): String =
    "WIFI:T:WPA;S:${qrEscape(ssid)};P:${qrEscape(password)};;"

private fun qrEscape(value: String): String = buildString {
    value.forEach { c ->
        if (c in "\\;,:\"") append('\\')
        append(c)
    }
}
