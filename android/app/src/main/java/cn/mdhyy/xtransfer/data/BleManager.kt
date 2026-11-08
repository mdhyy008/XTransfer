package cn.mdhyy.xtransfer.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import kotlin.math.min

const val BLE_CONVERSATION_PREFIX = "ble:"

// 心跳间隔；超过这个时长没收到对端任何帧就判定链路已断。
private const val HEARTBEAT_INTERVAL_MS = 5_000L
private const val HEARTBEAT_TIMEOUT_SEC = 15L
// 安卓设备之间由 id 小的一侧先连；大 id 一侧等这么久还没连上就兜底自己发起。
private const val AUTO_CONNECT_FALLBACK_SEC = 8L
private const val CONNECT_RETRY_SEC = 15L

data class BleDevice(
    val id: String,
    val name: String,
    val platform: String,
    val address: String,
    val rssi: Int,
    val lastSeen: Long,
    val connected: Boolean = false
)

data class BleState(
    val supported: Boolean = true,
    val on: Boolean = false,
    val scanning: Boolean = false,
    val advertising: Boolean = false,
    val bluetoothOff: Boolean = false,
    val devices: List<BleDevice> = emptyList(),
    val connecting: String? = null,
    val error: String? = null
)

internal object BleProto {
    val SERVICE: UUID = UUID.fromString("0000f0e0-0000-1000-8000-00805f9b34fb")
    val INFO: UUID = UUID.fromString("0000f0e1-0000-1000-8000-00805f9b34fb")
    val RX: UUID = UUID.fromString("0000f0e2-0000-1000-8000-00805f9b34fb")
    val TX: UUID = UUID.fromString("0000f0e3-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun frame(json: String): ByteArray {
        val payload = json.toByteArray(Charsets.UTF_8)
        val out = ByteArray(2 + payload.size)
        out[0] = ((payload.size shr 8) and 0xff).toByte()
        out[1] = (payload.size and 0xff).toByte()
        System.arraycopy(payload, 0, out, 2, payload.size)
        return out
    }

    fun chunks(data: ByteArray, size: Int): List<ByteArray> {
        if (data.isEmpty()) return emptyList()
        val step = size.coerceAtLeast(1)
        val out = ArrayList<ByteArray>((data.size + step - 1) / step)
        var offset = 0
        while (offset < data.size) {
            val end = min(offset + step, data.size)
            out += data.copyOfRange(offset, end)
            offset = end
        }
        return out
    }

    class Assembler {
        private var buffer = ByteArray(0)

        fun feed(chunk: ByteArray): List<String> {
            buffer = buffer + chunk
            val out = ArrayList<String>()
            while (buffer.size >= 2) {
                val length = ((buffer[0].toInt() and 0xff) shl 8) or (buffer[1].toInt() and 0xff)
                if (length <= 0 || length > 8192) {
                    buffer = ByteArray(0)
                    break
                }
                if (buffer.size < 2 + length) break
                out += String(buffer, 2, length, Charsets.UTF_8)
                buffer = buffer.copyOfRange(2 + length, buffer.size)
            }
            return out
        }
    }
}

/**
 * 蓝牙广播：BLE 广播本机、扫描同样运行本应用的设备、用 GATT 传文字消息。
 * 双方都开 GATT Server；需要发消息而当前没有链路时由发送方作为 central 连过去。
 */
class BleManager(private val context: Context) {
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    /** 收到一条完整的文字消息；由 Repository 落库。 */
    var onInbound: ((fromId: String, fromName: String, platform: String, body: String, ts: Long) -> Unit)? = null

    private val _state = MutableStateFlow(BleState())
    val state: StateFlow<BleState> = _state.asStateFlow()

    private val prefs = context.getSharedPreferences("xlan_ble", Context.MODE_PRIVATE)
    private var enabled = prefs.getBoolean("enabled", false)
    private var started = false
    private var scanning = false
    private var advertising = false
    private var receiverRegistered = false
    private var selfId = ""
    private var selfName = ""

    private var gattServer: BluetoothGattServer? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var infoBytes = ByteArray(0)
    private val serverAssemblers = HashMap<String, BleProto.Assembler>()
    private val subscribed = HashMap<String, BluetoothDevice>()
    private val serverMtu = HashMap<String, Int>()
    private val notifySignals = HashMap<String, CompletableDeferred<Boolean>>()

    private val clients = HashMap<String, ClientLink>()
    private val connectLock = Mutex()
    private val notifyLock = Mutex()

    private val found = LinkedHashMap<String, BleDevice>()
    private val addressToId = HashMap<String, String>()
    private val idToAddress = HashMap<String, String>()
    private val idToDevice = HashMap<String, BluetoothDevice>()
    private val introspecting = HashSet<String>()

    private val seen = LinkedHashSet<String>()
    private val ackSignals = HashMap<String, CompletableDeferred<Boolean>>()

    private var heartbeatJob: Job? = null
    private var beatSeq = 0L
    // 以 client 的 peerId 或 server 订阅端地址为键，记录最近一次收到对端帧的时间。
    private val lastRx = HashMap<String, Long>()
    private val lastConnectAttempt = HashMap<String, Long>()
    private val firstSeenAt = HashMap<String, Long>()

    val supported: Boolean
        get() = adapter != null &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    val permissions: List<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun granted(): Boolean = permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** 系统蓝牙是否开着；没权限时按关闭处理。 */
    val adapterOn: Boolean
        get() = runCatching { adapter?.isEnabled == true }.getOrDefault(false)

    fun enable() {
        enabled = true
        prefs.edit().putBoolean("enabled", true).apply()
        start()
    }

    fun disable() {
        enabled = false
        prefs.edit().putBoolean("enabled", false).apply()
        stop()
    }

    fun startIfEnabled() {
        if (enabled) start()
    }

    /** 应用退到后台时只停扫描，广播和 GATT 服务保留，仍能收消息。 */
    fun pauseScanning() {
        stopScanningInternal()
    }

    /** 下拉刷新时重新起一轮扫描；扫描器异常退出后也能靠它恢复。 */
    fun rescan() {
        if (!started) return
        val ad = adapter ?: return
        if (!ad.isEnabled) return
        stopScanningInternal()
        startScanning(ad)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!supported || !granted()) {
            _state.update { it.copy(on = false) }
            return
        }
        val ad = adapter ?: return
        // 先挂上蓝牙开关监听：应用启动时蓝牙是关的，也要能在用户打开蓝牙后自动恢复。
        registerReceiver()
        if (!ad.isEnabled) {
            advertising = false
            _state.update {
                it.copy(
                    on = false,
                    bluetoothOff = true,
                    scanning = false,
                    advertising = false,
                    error = null
                )
            }
            return
        }
        selfId = XlanConfig.deviceId(context.filesDir)
        selfName = XlanConfig.deviceName(context.filesDir)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL
        if (!started) {
            started = true
            startHeartbeat()
        }
        // 每次回到前台都要确认服务还在；退后台时只停了扫描，这里把它重新起起来。
        if (gattServer == null) startServer(ad)
        if (!advertising) startAdvertising(ad)
        startScanning(ad)
        _state.update {
            it.copy(on = true, bluetoothOff = false, error = null)
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!started) {
            _state.value = BleState(supported = supported)
            return
        }
        started = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        stopScanningInternal()
        stopAdvertisingInternal()
        stopServerInternal()
        closeAllClients()
        synchronized(lock) {
            found.clear()
            addressToId.clear()
            idToAddress.clear()
            idToDevice.clear()
            introspecting.clear()
            ackSignals.clear()
            seen.clear()
            lastRx.clear()
            lastConnectAttempt.clear()
            firstSeenAt.clear()
        }
        _state.value = BleState(supported = supported)
    }

    fun connect(peerId: String) {
        if (!started) return
        scope.launch {
            _state.update { it.copy(connecting = peerId) }
            try {
                ensureClient(peerId)
            } finally {
                _state.update { it.copy(connecting = null) }
            }
        }
    }

    fun isConnected(peerId: String): Boolean =
        synchronized(lock) {
            clients[peerId]?.ready == true ||
                idToAddress[peerId]?.let { subscribed.containsKey(it) } == true
        }

    suspend fun sendText(peerId: String, body: String, msgId: String) {
        val message = JSONObject()
            .put("t", "m")
            .put("id", msgId)
            .put("from", selfId)
            .put("fromName", selfName)
            .put("platform", "android")
            .put("body", body)
            .put("ts", nowSec())
        val frame = BleProto.frame(message.toString())
        val signal = CompletableDeferred<Boolean>()
        synchronized(lock) { ackSignals[msgId] = signal }
        try {
            if (!sendOnce(peerId, frame)) throw IllegalStateException("蓝牙未连接")
            if (withTimeoutOrNull(4_000) { signal.await() } == true) return
            if (!sendOnce(peerId, frame)) throw IllegalStateException("蓝牙未连接")
            if (withTimeoutOrNull(5_000) { signal.await() } == true) return
            throw IllegalStateException("蓝牙未确认")
        } finally {
            synchronized(lock) { ackSignals.remove(msgId) }
        }
    }

    private suspend fun sendOnce(peerId: String, frame: ByteArray): Boolean {
        val device = synchronized(lock) {
            val address = idToAddress[peerId]
            if (address != null && subscribed.containsKey(address)) subscribed[address] else null
        }
        if (device != null && notifyDevice(device, frame)) return true
        val link = ensureClient(peerId) ?: return false
        return link.send(frame)
    }

    private suspend fun ensureClient(peerId: String): ClientLink? {
        synchronized(lock) { clients[peerId] }?.takeIf { it.ready }?.let { return it }
        return connectLock.withLock {
            synchronized(lock) { clients[peerId] }?.takeIf { it.ready }?.let { return@withLock it }
            synchronized(lock) { clients.remove(peerId) }?.close()
            val device = synchronized(lock) { idToDevice[peerId] } ?: return@withLock null
            val link = ClientLink(device) { json -> handleFrame(json, null, peerId) }
            if (!link.connect()) {
                link.close()
                return@withLock null
            }
            synchronized(lock) {
                clients[peerId] = link
                lastRx[peerId] = nowSec()
            }
            publish()
            link
        }
    }

    private fun closeAllClients() {
        val links = synchronized(lock) {
            val all = clients.values.toList()
            clients.clear()
            all
        }
        links.forEach { it.close() }
    }

    private fun handleFrame(json: String, fromDevice: BluetoothDevice?, peerId: String?): String? {
        val message = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val key = peerId ?: fromDevice?.address
        if (key != null) synchronized(lock) { lastRx[key] = nowSec() }
        when (message.optString("t")) {
            "h" -> {
                val pong = BleProto.frame(
                    JSONObject().put("t", "b").put("from", selfId).toString()
                )
                replyFrame(peerId, fromDevice, pong)
                return null
            }
            "b" -> return null
            "a" -> {
                val id = message.optString("id")
                if (id.isNotEmpty()) {
                    synchronized(lock) { ackSignals.remove(id) }?.complete(true)
                }
                return null
            }
            "m" -> Unit
            else -> return null
        }
        val msgId = message.optString("id")
        val from = message.optString("from")
        if (msgId.isEmpty() || from.isEmpty() || from == selfId) return null

        val ack = BleProto.frame(JSONObject().put("t", "a").put("id", msgId).toString())
        replyFrame(peerId, fromDevice, ack)

        if (!remember(msgId)) return from

        val name = message.optString("fromName")
        val platform = message.optString("platform", "android")
        registerFromFrame(from, name, platform, fromDevice?.address)
        onInbound?.invoke(from, name, platform, message.optString("body"), message.optLong("ts"))
        return from
    }

    private fun replyFrame(peerId: String?, fromDevice: BluetoothDevice?, frame: ByteArray) {
        val device = fromDevice ?: synchronized(lock) {
            peerId?.let { idToAddress[it]?.let { address -> subscribed[address] } }
        }
        scope.launch {
            if (peerId != null) {
                val link = synchronized(lock) { clients[peerId] }
                if (link != null && link.ready && link.send(frame)) return@launch
            }
            if (device != null) notifyDevice(device, frame)
        }
    }

    private fun remember(id: String): Boolean {
        synchronized(lock) {
            if (!seen.add(id)) return false
            while (seen.size > 512) {
                val old = seen.firstOrNull() ?: break
                seen.remove(old)
            }
            return true
        }
    }

    private fun registerFromFrame(from: String, name: String, platform: String, address: String?) {
        synchronized(lock) {
            if (!address.isNullOrEmpty()) {
                addressToId[address] = from
                idToAddress[from] = address
            }
            val prev = found[from]
            found[from] = BleDevice(
                id = from,
                name = name.takeIf { it.isNotBlank() } ?: prev?.name ?: from,
                platform = platform.takeIf { it.isNotBlank() } ?: prev?.platform ?: "android",
                address = address ?: prev?.address ?: "",
                rssi = prev?.rssi ?: 0,
                lastSeen = nowSec(),
                connected = true
            )
        }
        publish()
    }

    private fun publish() {
        val devices = synchronized(lock) {
            found.values.map { device ->
                val serverConnected = subscribed.containsKey(device.address)
                val clientConnected = clients[device.id]?.ready == true
                device.copy(connected = serverConnected || clientConnected || device.connected)
            }.sortedByDescending { it.rssi }
        }
        _state.update { it.copy(devices = devices) }
    }

    private fun markConnected(device: BluetoothDevice, connected: Boolean) {
        val id = synchronized(lock) { addressToId[device.address] } ?: return
        synchronized(lock) {
            found[id]?.let { found[id] = it.copy(connected = connected) }
        }
        publish()
    }

    // ---------- 广播 ----------

    @SuppressLint("MissingPermission")
    private fun startAdvertising(ad: BluetoothAdapter) {
        val advertiser = runCatching { ad.bluetoothLeAdvertiser }.getOrNull() ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BleProto.SERVICE))
            .setIncludeDeviceName(false)
            .build()
        runCatching { advertiser.startAdvertising(settings, data, advertiseCallback) }
            .onFailure { _state.update { state -> state.copy(error = "广播启动失败") } }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertisingInternal() {
        advertising = false
        val advertiser = runCatching { adapter?.bluetoothLeAdvertiser }.getOrNull()
        if (advertiser != null) {
            runCatching { advertiser.stopAdvertising(advertiseCallback) }
        }
        _state.update { it.copy(advertising = false) }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertising = true
            _state.update { it.copy(advertising = true, error = null) }
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            _state.update { it.copy(advertising = false, error = "广播失败($errorCode)") }
        }
    }

    // ---------- 扫描 ----------

    @SuppressLint("MissingPermission")
    private fun startScanning(ad: BluetoothAdapter) {
        if (scanning) return
        val scanner = runCatching { ad.bluetoothLeScanner }.getOrNull() ?: return
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleProto.SERVICE))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        val ok = runCatching {
            scanner.startScan(filters, settings, scanCallback)
            true
        }.getOrDefault(false)
        scanning = ok
        _state.update { it.copy(scanning = ok, error = if (ok) it.error else "扫描启动失败") }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanningInternal() {
        if (!scanning) return
        scanning = false
        val scanner = runCatching { adapter?.bluetoothLeScanner }.getOrNull()
        if (scanner != null) {
            runCatching { scanner.stopScan(scanCallback) }
        }
        _state.update { it.copy(scanning = false) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            onScanResult(result.device, result.rssi)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(it.device, it.rssi) }
        }

        override fun onScanFailed(errorCode: Int) {
            _state.update { it.copy(scanning = false, error = "扫描失败($errorCode)") }
        }
    }

    @SuppressLint("MissingPermission")
    private fun onScanResult(device: BluetoothDevice, rssi: Int) {
        val address = runCatching { device.address }.getOrNull()
        if (address.isNullOrEmpty()) return
        val selfAddress = runCatching { adapter?.address }.getOrNull()
        if (!selfAddress.isNullOrEmpty() && address == selfAddress) return
        val known: String?
        synchronized(lock) {
            known = addressToId[address]
            if (known != null) {
                found[known]?.let { found[known] = it.copy(rssi = rssi, lastSeen = nowSec()) }
                idToDevice[known] = device
            } else {
                if (!introspecting.add(address)) return
            }
        }
        if (known != null) {
            publish()
            maybeAutoConnect(known)
            return
        }
        scope.launch {
            try {
                introspect(device, address, rssi)
            } finally {
                synchronized(lock) { introspecting.remove(address) }
            }
        }
    }

    private suspend fun introspect(device: BluetoothDevice, address: String, rssi: Int) {
        val link = ClientLink(device) { }
        var peerId = ""
        try {
            if (!link.connect()) return
            val id = link.peerId
            if (id.isEmpty() || id == selfId) return
            peerId = id
            synchronized(lock) {
                addressToId[address] = id
                idToAddress[id] = address
                idToDevice[id] = device
                val prev = found[id]
                found[id] = BleDevice(
                    id = id,
                    name = link.peerName.takeIf { it.isNotBlank() } ?: prev?.name ?: id,
                    platform = link.peerPlatform.takeIf { it.isNotBlank() } ?: prev?.platform ?: "android",
                    address = address,
                    rssi = rssi,
                    lastSeen = nowSec(),
                    connected = prev?.connected == true
                )
            }
            publish()
        } finally {
            link.close()
        }
        maybeAutoConnect(peerId)
    }

    /**
     * 发现设备后自动建链，不再等用户点进会话。桌面端由它自己主动连，
     * 手机之间默认 id 小的一侧先连，避免双方各连一条。
     */
    private fun maybeAutoConnect(id: String) {
        if (!started || id.isEmpty() || id == selfId) return
        if (synchronized(lock) { found[id]?.platform } == "windows") return
        if (isConnected(id)) return
        if (_state.value.connecting == id) return
        val now = nowSec()
        val seenAt = synchronized(lock) { firstSeenAt.getOrPut(id) { now } }
        // 小 id 那台若退回后台停了扫描，永远等不来连接；等够时间就由这一侧兜底发起。
        if (selfId > id && now - seenAt < AUTO_CONNECT_FALLBACK_SEC) return
        synchronized(lock) {
            val last = lastConnectAttempt[id] ?: 0L
            if (now - last < CONNECT_RETRY_SEC) return
            lastConnectAttempt[id] = now
        }
        connect(id)
    }

    private fun startHeartbeat() {
        if (heartbeatJob != null) return
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                runCatching { beatOnce() }
            }
        }
    }

    private suspend fun beatOnce() {
        val ping = BleProto.frame(
            JSONObject().put("t", "h").put("from", selfId).put("n", ++beatSeq).toString()
        )
        val clientsSnapshot = synchronized(lock) { clients.entries.map { it.key to it.value } }
        for ((peerId, link) in clientsSnapshot) {
            if (!link.ready) continue
            if (!runCatching { link.send(ping) }.getOrDefault(false)) {
                link.close()
                continue
            }
            val last = synchronized(lock) { lastRx[peerId] } ?: 0L
            if (last > 0 && nowSec() - last > HEARTBEAT_TIMEOUT_SEC) link.close()
        }
        val devices = synchronized(lock) { subscribed.values.toList() }
        for (device in devices) {
            val key = device.address
            val ok = runCatching { notifyDevice(device, ping) }.getOrDefault(false)
            val stale = synchronized(lock) { lastRx[key] }?.let {
                nowSec() - it > HEARTBEAT_TIMEOUT_SEC
            } ?: false
            if (!ok || stale) {
                synchronized(lock) {
                    subscribed.remove(key)
                    serverAssemblers.remove(key)
                    lastRx.remove(key)
                }
                markConnected(device, false)
            }
        }
    }

    // ---------- GATT Server ----------

    @SuppressLint("MissingPermission")
    private fun startServer(ad: BluetoothAdapter) {
        val info = JSONObject()
            .put("id", selfId)
            .put("name", selfName)
            .put("platform", "android")
        infoBytes = info.toString().toByteArray(Charsets.UTF_8)

        val server = manager?.openGattServer(context, serverCallback) ?: return
        val service = BluetoothGattService(
            BleProto.SERVICE,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        val infoChar = BluetoothGattCharacteristic(
            BleProto.INFO,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val rxChar = BluetoothGattCharacteristic(
            BleProto.RX,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val tx = BluetoothGattCharacteristic(
            BleProto.TX,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        tx.addDescriptor(
            BluetoothGattDescriptor(
                BleProto.CCCD,
                BluetoothGattDescriptor.PERMISSION_READ or
                    BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        service.addCharacteristic(infoChar)
        service.addCharacteristic(rxChar)
        service.addCharacteristic(tx)
        if (!server.addService(service)) {
            runCatching { server.close() }
            _state.update { it.copy(error = "GATT 服务创建失败") }
            return
        }
        gattServer = server
        txChar = tx
    }

    private fun stopServerInternal() {
        val server = gattServer ?: return
        gattServer = null
        txChar = null
        synchronized(lock) {
            subscribed.clear()
            serverAssemblers.clear()
            serverMtu.clear()
            notifySignals.clear()
        }
        runCatching { server.close() }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val server = gattServer ?: return
            if (characteristic.uuid != BleProto.INFO) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                return
            }
            val mtu = synchronized(lock) { serverMtu[device.address] } ?: 23
            val max = (mtu - 1).coerceAtLeast(20)
            if (offset >= infoBytes.size) {
                server.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, offset, ByteArray(0)
                )
                return
            }
            val end = min(offset + max, infoBytes.size)
            server.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                offset,
                infoBytes.copyOfRange(offset, end)
            )
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val server = gattServer ?: return
            if (responseNeeded) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
            if (characteristic.uuid != BleProto.RX || value == null || value.isEmpty()) return
            val assembler = synchronized(lock) {
                serverAssemblers.getOrPut(device.address) { BleProto.Assembler() }
            }
            for (frame in assembler.feed(value)) {
                handleFrame(frame, device, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val server = gattServer ?: return
            server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            if (descriptor.uuid != BleProto.CCCD) return
            val on = value != null &&
                value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            synchronized(lock) {
                if (on) {
                    subscribed[device.address] = device
                    lastRx[device.address] = nowSec()
                } else {
                    subscribed.remove(device.address)
                    lastRx.remove(device.address)
                }
            }
            markConnected(device, on)
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                markConnected(device, true)
                return
            }
            synchronized(lock) {
                subscribed.remove(device.address)
                serverAssemblers.remove(device.address)
                lastRx.remove(device.address)
            }
            markConnected(device, false)
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            synchronized(lock) { serverMtu[device.address] = mtu }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val signal = synchronized(lock) { notifySignals.remove(device.address) }
            signal?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun notifyDevice(device: BluetoothDevice, data: ByteArray): Boolean =
        notifyLock.withLock {
            val server = gattServer ?: return@withLock false
            val tx = txChar ?: return@withLock false
            val mtu = synchronized(lock) { serverMtu[device.address] } ?: 23
            // ATT 属性值上限 512 字节，超过会抛 IllegalArgumentException。
            val chunk = (mtu - 3).coerceIn(20, 512)
            var sent = false
            for (slice in BleProto.chunks(data, chunk)) {
                val signal = CompletableDeferred<Boolean>()
                synchronized(lock) { notifySignals[device.address] = signal }
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    server.notifyCharacteristicChanged(
                        device, tx, false, slice
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        tx.value = slice
                        server.notifyCharacteristicChanged(device, tx, false)
                    }
                }
                if (!started) {
                    synchronized(lock) { notifySignals.remove(device.address) }
                    break
                }
                val done = withTimeoutOrNull(1_200) { signal.await() } ?: true
                sent = sent || done
                if (!done) break
                delay(4)
            }
            sent
        }

    // ---------- GATT Client ----------

    private inner class ClientLink(
        private val device: BluetoothDevice,
        private val onFrame: (String) -> Unit
    ) {
        private var gatt: BluetoothGatt? = null
        private var rx: BluetoothGattCharacteristic? = null
        private var tx: BluetoothGattCharacteristic? = null
        private val assembler = BleProto.Assembler()
        private val writeLock = Mutex()
        private val connectedSignal = CompletableDeferred<Boolean>()
        private var writeSignal: CompletableDeferred<Boolean>? = null
        private var readSignal: CompletableDeferred<String?>? = null
        private var mtuSignal: CompletableDeferred<Unit>? = null
        private var servicesSignal: CompletableDeferred<Boolean>? = null
        private var mtu = 23
        private var closed = false

        @Volatile
        var ready = false
            private set
        var peerId = ""
            private set
        var peerName = ""
            private set
        var peerPlatform = "android"
            private set

        suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
            try {
                val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                    ?: return@withContext false
                gatt = g
                if (withTimeoutOrNull(8_000) { connectedSignal.await() } != true) {
                    return@withContext false
                }
                val mtuWait = CompletableDeferred<Unit>()
                mtuSignal = mtuWait
                if (g.requestMtu(517)) {
                    withTimeoutOrNull(1_500) { mtuWait.await() }
                }
                val servicesWait = CompletableDeferred<Boolean>()
                servicesSignal = servicesWait
                if (!g.discoverServices()) return@withContext false
                if (withTimeoutOrNull(8_000) { servicesWait.await() } != true) {
                    return@withContext false
                }
                val service = g.getService(BleProto.SERVICE) ?: return@withContext false
                val infoChar = service.getCharacteristic(BleProto.INFO) ?: return@withContext false
                val info = readInfo(g, infoChar) ?: return@withContext false
                val id = info.optString("id")
                if (id.isEmpty()) return@withContext false
                peerId = id
                peerName = info.optString("name").ifBlank { id }
                peerPlatform = info.optString("platform").ifBlank { "android" }
                rx = service.getCharacteristic(BleProto.RX)
                tx = service.getCharacteristic(BleProto.TX)
                if (rx == null || tx == null) return@withContext false
                g.setCharacteristicNotification(tx, true)
                val cccd = tx!!.getDescriptor(BleProto.CCCD)
                if (cccd != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        run {
                            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            g.writeDescriptor(cccd)
                        }
                    }
                }
                ready = true
                true
            } catch (error: Exception) {
                close()
                false
            }
        }

        private suspend fun readInfo(g: BluetoothGatt, char: BluetoothGattCharacteristic): JSONObject? {
            val signal = CompletableDeferred<String?>()
            readSignal = signal
            if (!g.readCharacteristic(char)) return null
            val text = withTimeoutOrNull(5_000) { signal.await() } ?: return null
            return runCatching { JSONObject(text) }.getOrNull()
        }

        suspend fun send(data: ByteArray): Boolean = writeLock.withLock {
            val g = gatt ?: return@withLock false
            val c = rx ?: return@withLock false
            if (!ready || closed) return@withLock false
            // ATT 属性值上限 512 字节，超过会抛 IllegalArgumentException。
            val chunk = (mtu - 3).coerceIn(20, 512)
            for (slice in BleProto.chunks(data, chunk)) {
                val signal = CompletableDeferred<Boolean>()
                writeSignal = signal
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(
                        c, slice, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        c.value = slice
                        g.writeCharacteristic(c)
                    }
                }
                if (!started) return@withLock false
                val ok = withTimeoutOrNull(3_000) { signal.await() } ?: false
                if (!ok) return@withLock false
            }
            true
        }

        fun close() {
            if (closed) return
            closed = true
            ready = false
            val g = gatt
            gatt = null
            if (g != null) {
                runCatching { g.disconnect() }
                runCatching { g.close() }
            }
            synchronized(lock) {
                if (peerId.isNotEmpty() && clients[peerId] === this) clients.remove(peerId)
                if (peerId.isNotEmpty()) lastRx.remove(peerId)
            }
            if (peerId.isNotEmpty()) {
                synchronized(lock) {
                    found[peerId]?.let {
                        if (clients[peerId] == null) found[peerId] = it.copy(connected = false)
                    }
                }
                publish()
            }
        }

        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectedSignal.complete(true)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connectedSignal.complete(false)
                    ready = false
                    close()
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && newMtu > 0) mtu = newMtu
                mtuSignal?.complete(Unit)
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                servicesSignal?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            override fun onCharacteristicRead(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                readSignal?.complete(
                    if (status == BluetoothGatt.GATT_SUCCESS) String(value, Charsets.UTF_8) else null
                )
            }

            @Deprecated("API 33 以下")
            override fun onCharacteristicRead(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                @Suppress("DEPRECATION")
                val value = characteristic.value
                readSignal?.complete(
                    if (status == BluetoothGatt.GATT_SUCCESS && value != null) {
                        String(value, Charsets.UTF_8)
                    } else {
                        null
                    }
                )
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                writeSignal?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (characteristic.uuid != BleProto.TX) return
                for (frame in assembler.feed(value)) onFrame(frame)
            }

            @Deprecated("API 33 以下")
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (characteristic.uuid != BleProto.TX) return
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                for (frame in assembler.feed(value)) onFrame(frame)
            }
        }
    }

    // ---------- 蓝牙开关 ----------

    private fun registerReceiver() {
        if (receiverRegistered) return
        receiverRegistered = true
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                        BluetoothAdapter.STATE_OFF -> {
                            heartbeatJob?.cancel()
                            heartbeatJob = null
                            stopScanningInternal()
                            stopAdvertisingInternal()
                            stopServerInternal()
                            closeAllClients()
                            _state.update {
                                it.copy(
                                    on = false,
                                    bluetoothOff = true,
                                    advertising = false,
                                    scanning = false,
                                    devices = emptyList()
                                )
                            }
                        }
                        BluetoothAdapter.STATE_ON -> {
                            if (enabled) {
                                started = false
                                start()
                            }
                        }
                    }
                }
            },
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000
}
