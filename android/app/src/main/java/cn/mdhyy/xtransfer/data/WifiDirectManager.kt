package cn.mdhyy.xtransfer.data

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.net.wifi.p2p.WifiP2pManager.ActionListener
import android.net.wifi.p2p.WifiP2pManager.Channel
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class WifiDirectDevice(
    val address: String,
    val name: String,
    val directId: String? = null
)

data class WifiDirectState(
    val supported: Boolean = true,
    val discovering: Boolean = false,
    val devices: List<WifiDirectDevice> = emptyList(),
    val connectingAddress: String? = null,
    val connected: Boolean = false,
    val peerAddress: String? = null,
    val peerName: String? = null,
    val groupOwner: Boolean = false,
    val wifiOff: Boolean = false,
    val error: String? = null
)

/**
 * Wi-Fi Direct：广播本机的 DNS-SD 服务、发现同样运行本应用的设备、组网。
 * 组网成功后传输仍交给 xlan 服务，在 P2P 网段上重新扫描即可互通。
 */
class WifiDirectManager(private val context: Context) {
    private val wifiP2p =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val wifi =
        context.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var channel: Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null
    private var localService: WifiP2pDnsSdServiceInfo? = null
    private val found = LinkedHashMap<String, WifiDirectDevice>()
    private var started = false
    private var enabled = false
    private var connectTarget: String? = null

    private val _state = MutableStateFlow(WifiDirectState(supported = supported))
    val state: StateFlow<WifiDirectState> = _state.asStateFlow()

    val supported: Boolean
        get() = wifiP2p != null &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    val permission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    fun granted(): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** 用户在界面里开启过一次后，应用回到前台就自动接着发现。 */
    fun enable() {
        enabled = true
        start()
    }

    fun startIfEnabled() {
        if (enabled) start()
    }

    fun start() {
        if (!supported || !granted()) return
        val manager = wifiP2p ?: return
        if (channel == null) {
            channel = manager.initialize(context, Looper.getMainLooper()) {
                _state.update { it.copy(error = "Wi-Fi Direct 连接已断开") }
            }
        }
        if (!started) {
            started = true
            register()
            advertise()
            listen()
        }
        _state.update { it.copy(wifiOff = wifi?.isWifiEnabled == false, supported = true) }
        if (wifi?.isWifiEnabled == false) return
        discover()
    }

    fun stop() {
        if (!started) return
        started = false
        val manager = wifiP2p
        val ch = channel
        if (manager != null && ch != null) {
            runCatching { manager.stopPeerDiscovery(ch, null) }
            runCatching { manager.clearServiceRequests(ch, null) }
            localService?.let { runCatching { manager.removeLocalService(ch, it, null) } }
        }
        serviceRequest = null
        localService = null
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        found.clear()
        _state.update {
            it.copy(discovering = false, devices = emptyList(), connectingAddress = null)
        }
    }

    fun discover() {
        val manager = wifiP2p ?: return
        val ch = channel ?: return
        if (!granted() || wifi?.isWifiEnabled == false) {
            _state.update { it.copy(wifiOff = wifi?.isWifiEnabled == false) }
            return
        }
        val request = serviceRequest ?: WifiP2pDnsSdServiceRequest.newInstance().also {
            serviceRequest = it
            runCatching { manager.addServiceRequest(ch, it, null) }
        }
        manager.discoverServices(
            ch,
            object : ActionListener {
                override fun onSuccess() {
                    _state.update { it.copy(discovering = true, error = null) }
                }

                override fun onFailure(reason: Int) {
                    _state.update {
                        it.copy(discovering = false, error = reasonText(reason))
                    }
                }
            }
        )
    }

    fun connect(address: String) {
        val manager = wifiP2p ?: return
        val ch = channel ?: return
        if (!granted()) return
        connectTarget = address
        _state.update { it.copy(connectingAddress = address, error = null) }
        val config = WifiP2pConfig().apply {
            deviceAddress = address
            wps.setup = WpsInfo.PBC
        }
        runCatching {
            manager.connect(
                ch,
                config,
                object : ActionListener {
                    override fun onSuccess() = Unit

                    override fun onFailure(reason: Int) {
                        _state.update {
                            it.copy(connectingAddress = null, error = reasonText(reason))
                        }
                    }
                }
            )
        }.onFailure {
            _state.update { state -> state.copy(connectingAddress = null, error = "连接失败") }
        }
    }

    fun disconnect() {
        val manager = wifiP2p ?: return
        val ch = channel ?: return
        runCatching { manager.removeGroup(ch, null) }
        connectTarget = null
        _state.update {
            it.copy(
                connected = false,
                peerAddress = null,
                peerName = null,
                groupOwner = false,
                connectingAddress = null
            )
        }
    }

    private fun register() {
        if (receiver != null) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
        }
        val next = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val enabled = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE,
                            WifiP2pManager.WIFI_P2P_STATE_DISABLED
                        ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        val off = wifi?.isWifiEnabled == false
                        _state.update { it.copy(wifiOff = off || !enabled) }
                        if (enabled && !off && started) discover()
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> connectionChanged()
                    WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(
                            WifiP2pManager.EXTRA_DISCOVERY_STATE,
                            WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED
                        )
                        val running = state == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED
                        _state.update { it.copy(discovering = running) }
                        // 系统搜索会话大约两分钟就结束，前台时接着搜。
                        if (!running && started) discover()
                    }
                }
            }
        }
        receiver = next
        ContextCompat.registerReceiver(
            context,
            next,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun advertise() {
        val manager = wifiP2p ?: return
        val ch = channel ?: return
        val deviceId = XlanConfig.deviceId(context.filesDir)
        val name = XlanConfig.deviceName(context.filesDir)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL
        val instance = "Xlan-${deviceId.takeLast(6)}"
        val info = WifiP2pDnsSdServiceInfo.newInstance(
            instance,
            SERVICE_TYPE,
            mapOf("id" to deviceId, "name" to name, "platform" to "android")
        )
        localService = info
        runCatching { manager.addLocalService(ch, info, null) }
    }

    private fun listen() {
        val manager = wifiP2p ?: return
        val ch = channel ?: return
        runCatching {
            manager.setDnsSdResponseListeners(
                ch,
                { instanceName, _, srcDevice ->
                    srcDevice ?: return@setDnsSdResponseListeners
                    val existing = found[srcDevice.deviceAddress]
                    found[srcDevice.deviceAddress] = WifiDirectDevice(
                        address = srcDevice.deviceAddress,
                        name = existing?.name?.takeIf { it.isNotBlank() }
                            ?: srcDevice.deviceName?.takeIf { it.isNotBlank() }
                            ?: instanceName.orEmpty(),
                        directId = existing?.directId
                    )
                    publishDevices()
                },
                { _, record, srcDevice ->
                    srcDevice ?: return@setDnsSdResponseListeners
                    val existing = found[srcDevice.deviceAddress]
                    found[srcDevice.deviceAddress] = WifiDirectDevice(
                        address = srcDevice.deviceAddress,
                        name = record?.get("name")?.takeIf { it.isNotBlank() }
                            ?: existing?.name?.takeIf { it.isNotBlank() }
                            ?: srcDevice.deviceName.orEmpty(),
                        directId = record?.get("id") ?: existing?.directId
                    )
                    publishDevices()
                }
            )
        }
    }

    private fun connectionChanged() {
        val manager = wifiP2p ?: return
        val ch = channel ?: return
        manager.requestConnectionInfo(ch) { info ->
            if (info == null || !info.groupFormed) {
                connectTarget = null
                _state.update {
                    it.copy(
                        connected = false,
                        peerAddress = null,
                        peerName = null,
                        groupOwner = false,
                        connectingAddress = null
                    )
                }
                return@requestConnectionInfo
            }
            val owner = info.isGroupOwner
            _state.update {
                it.copy(
                    connected = true,
                    groupOwner = owner,
                    connectingAddress = null,
                    peerName = it.peerName ?: found[connectTarget]?.name
                )
            }
            manager.requestPeers(ch) { peers ->
                val peer = peers?.deviceList?.firstOrNull {
                    it.status == WifiP2pDevice.CONNECTED
                }
                if (peer != null) {
                    connectTarget = peer.deviceAddress
                    _state.update {
                        it.copy(
                            peerAddress = peer.deviceAddress,
                            peerName = peer.deviceName?.takeIf { name -> name.isNotBlank() }
                                ?: found[peer.deviceAddress]?.name
                                ?: it.peerName
                        )
                    }
                }
            }
        }
    }

    private fun publishDevices() {
        _state.update { it.copy(devices = found.values.toList()) }
    }

    private fun reasonText(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "设备不支持 Wi-Fi Direct"
        WifiP2pManager.BUSY -> "系统忙，请稍后重试"
        else -> "操作失败"
    }

    companion object {
        private const val SERVICE_TYPE = "_xlan._tcp"
    }
}
