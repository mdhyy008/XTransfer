package cn.mdhyy.xtransfer.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap

data class LanInfo(val name: String, val cidr: String)

/** known 为 false 表示还没检测出结果，界面此时先不显示断网遮罩。 */
data class LanState(val known: Boolean = false, val info: LanInfo? = null)

/**
 * 监听设备是否接入局域网（Wi-Fi / 以太网，且已拿到 IPv4），并给出接口名与 CIDR。
 */
class LanMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val nets = ConcurrentHashMap<Network, LanInfo>()
    private val _state = MutableStateFlow(LanState())
    val state: StateFlow<LanState> = _state.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh(network)

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
            refresh(network)

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            refresh(network)

        override fun onLost(network: Network) {
            nets.remove(network)
            publish()
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
        _state.value = LanState(known = true, info = currentLan())
    }

    private fun currentLan(): LanInfo? = runCatching {
        cm.allNetworks.firstNotNullOfOrNull { lanOf(it) }
    }.getOrNull()

    private fun refresh(network: Network) {
        val info = lanOf(network)
        if (info != null) nets[network] = info else nets.remove(network)
        publish()
    }

    private fun lanOf(network: Network): LanInfo? {
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: return null
        val name = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            else -> return null
        }
        val link = runCatching { cm.getLinkProperties(network) }.getOrNull() ?: return null
        val ipv4 = link.linkAddresses.firstOrNull {
            it.address is Inet4Address &&
                !it.address.isLoopbackAddress &&
                // 169.254/16 是没拿到 DHCP 时自己编的链路本地地址，接不通别人。
                !it.address.isLinkLocalAddress
        } ?: return null
        return LanInfo(name, "${ipv4.address.hostAddress}/${ipv4.prefixLength}")
    }

    private fun publish() {
        _state.value = LanState(known = true, info = nets.values.firstOrNull())
    }
}
