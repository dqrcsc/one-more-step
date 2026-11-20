package com.oms.client.transport

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.KeyguardManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import com.oms.client.agent.OmsAccessibilityService
import com.oms.client.agent.ScreenCapturer
import com.oms.client.capability.AirPodsAutoSwitchManager
import com.oms.client.capability.AlarmExecutor
import com.oms.client.capability.AppLauncherHelper
import com.oms.client.capability.CalendarExecutor
import com.oms.client.capability.NotificationExecutor
import com.oms.client.capability.ScopedStorageExecutor
import com.oms.client.capability.SpeechAudioPolicy
import com.oms.client.data.PairedMacConfig
import com.oms.client.data.StreamItemModel
import com.oms.client.data.StreamRepository
import com.oms.client.otp.OtpExtractor
import com.oms.client.otp.OtpMatch
import com.oms.client.service.OmsCoreForegroundService
import com.oms.client.share.AttachmentBudget
import com.oms.client.share.AttachmentResolver
import com.oms.client.share.ClipboardCatcherActivity
import com.oms.client.share.ShareInputPolicy
import com.oms.client.xiaomi.XiaomiAdaptationHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.BitSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class OtaCheckResult(
    val ok: Boolean,
    val hasUpdate: Boolean,
    val localVersionName: String,
    val localVersionCode: Int,
    val localSha256: String,
    val remoteVersionName: String = "",
    val remoteVersionCode: Int = 0,
    val remoteSha256: String = "",
    val remoteSizeBytes: Long = 0L,
    val remoteUpdatedAtIso: String = "",
    val downloadUrl: String = "",
    val message: String = "",
    val integritySource: VerifiedOtaPolicy.Source? = null
)

/**
 * Module 02 (Android Side): Multi-Tier Adaptive Transport Client
 * (Non-VPN Physical Wi-Fi LAN WebSocket + BLE GATT Near-Field + WAN Relay)
 */
class OmsTransportClient(
    private val context: Context,
    private val repo: StreamRepository
) {
    companion object {
        val BLE_SERVICE_UUID: UUID = UUID.fromString("0000fd9a-0000-1000-8000-00805f9b34fb")
        val BLE_CHAR_MAC_INFO_UUID: UUID = UUID.fromString("0000fd9b-0000-1000-8000-00805f9b34fb")
        val BLE_CHAR_FRAME_PIPE_UUID: UUID = UUID.fromString("0000fd9c-0000-1000-8000-00805f9b34fb")
        val BLE_CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> }
    )
    private val mainHandler = Handler(Looper.getMainLooper())

    private val diagnostics = ConnectionDiagnostics(File(context.filesDir, "connection-diagnostics.log"))
    private val _connectionHintFlow = MutableStateFlow(diagnostics.offlineSummary())
    val connectionHintFlow: StateFlow<String> = _connectionHintFlow

    private fun traceConnection(event: ConnectionDiagnostics.Event, tier: String = "LAN_WS",
                                attempt: Long = lanGeneration.get(), affectState: Boolean = true) {
        diagnostics.record(event, tier, attempt, affectState)
        _connectionHintFlow.value = diagnostics.offlineSummary()
    }

    fun connectionDiagnosticsText(): String {
        fun safeIps(values: List<String>) = values.filter { value ->
            val parts = value.split('.')
            parts.size == 4 && parts.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
        }.joinToString(", ").ifBlank { "未获取" }
        val wifi = findNonVpnWifiNetwork() != null
        val locked = (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked
        return "OneMoreStep 连接诊断\n采集时间：${java.time.Instant.now()}\n当前链路：${_activeTierFlow.value}\n" +
            "Wi-Fi 可用：$wifi；当前锁屏：$locked\n手机 IPv4：${safeIps(getLocalPhoneWifiIpv4s())}\n" +
            "已保存 Mac IPv4：${safeIps(repo.getPairedMac()?.lanIps ?: emptyList())}\n" + diagnostics.exportText()
    }

    private val _activeTierFlow = MutableStateFlow("DISCONNECTED")
    val activeTierFlow: StateFlow<String> = _activeTierFlow

    @Volatile
    private var lanWebSocket: WebSocket? = null
    @Volatile
    private var wanWebSocket: WebSocket? = null
    @Volatile
    private var activeBleGatt: BluetoothGatt? = null
    @Volatile
    private var bleFramePipeChar: BluetoothGattCharacteristic? = null

    private val isConnectingLan = AtomicBoolean(false)
    private val isBleScanning = AtomicBoolean(false)
    private val isFlushingOutbox = AtomicBoolean(false)

    private val baseHttpClient: OkHttpClient = OkHttpClient.Builder().build()

    @Volatile
    private var cachedWifiNetwork: Network? = null
    @Volatile
    private var cachedLanHttpClient: OkHttpClient? = null
    @Volatile
    private var preferDefaultSocketFactory = false

    private val btDecoder = OmsFrameStreamDecoder()
    private val bleWriteQueue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    private val isBleWriting = AtomicBoolean(false)
    @Volatile
    private var blePayloadLimit = 180
    @Volatile
    private var activeBleScanCallback: ScanCallback? = null
    @Volatile
    private var lastBleScanStartMs = 0L
    @Volatile
    private var bleConnectStartedAtMs = 0L
    private var watchdogTickCount = 0L

    private val lanRetryRunnable = Runnable {
        val paired = repo.getPairedMac()
        connectLanIfPossible(paired?.lanIps ?: emptyList(), paired?.lanPort ?: 19527)
    }

    private class IncomingPushTransfer(
        val header: JSONObject,
        val source: FrameSource,
        val validated: DeliveryPolicy.Header,
        val partFile: File,
        val createdAtMs: Long = System.currentTimeMillis(),
        val receivedChunks: BitSet = BitSet(validated.totalChunks)
    ) {
        fun cleanup() {
            runCatching { if (partFile.exists()) partFile.delete() }
        }
    }

    private data class FrameSource(val tier: String, val socket: WebSocket?, val gatt: BluetoothGatt?)
    private data class PendingReply(val source: FrameSource, val result: CompletableDeferred<JSONObject>)
    private data class PairAttempt(val generation: Long, val config: PairedMacConfig, var token: String,
                                   val expiresAtMs: Long, val callback: (Boolean, String) -> Unit,
                                   val reported: AtomicBoolean = AtomicBoolean(false))
    private enum class SecurePhase { HELLO, START, FINISH, ACTIVE, CLOSED }
    private class SecureLink(val config: PairedMacConfig, val mode: String, var pskHex: String,
                             val pairing: PairAttempt? = null) {
        val lock = Any()
        val expiresAt = SystemClock.elapsedRealtime() + 30_000
        @Volatile var phase = SecurePhase.HELLO
        var handshakeBytesReceived: Long = 0
        var session: SecureChannel.Session? = null
        var newPairSecret: String = ""
        var expectedMacProof: String = ""
    }
    private val connectionLock = Any()
    private val secureLinks = ConcurrentHashMap<FrameSource, SecureLink>()
    private val pairGeneration = AtomicLong(0)
    private val lanGeneration = AtomicLong(0)
    private val wanGeneration = AtomicLong(0)
    @Volatile private var pairingWebSocket: WebSocket? = null
    @Volatile private var activeBlePairingAttempt: PairAttempt? = null
    private val pendingReplies = ConcurrentHashMap<String, PendingReply>()
    private val decidingApprovals = ConcurrentHashMap.newKeySet<String>()

    private val incomingTransfers = ConcurrentHashMap<String, IncomingPushTransfer>()
    private fun isV2Pairing(config: PairedMacConfig?): Boolean = config != null &&
        config.pairingVersion == 2 && Regex("[0-9a-f]{64}").matches(config.pairSecretHex)

    private fun isSourceAuthenticated(tier: String, sourceWebSocket: WebSocket?): Boolean =
        sourceAuthenticated(captureSource(tier, sourceWebSocket))

    val airpodsManager = AirPodsAutoSwitchManager(
        context = context,
        onClaimByPhone = { targetMac, targetName, reason ->
            val source = activeSource()
            val link = secureLinks[source] ?: error("Connection unavailable")
            val req = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", "ap_claim_${UUID.randomUUID()}")
                put("method", "airpods.claimByPhone")
                put("params", JSONObject().apply {
                    put("targetMac", targetMac)
                    put("targetName", targetName)
                    put("reason", reason)
                    put("pauseMacMedia", true)
                })
            }
            val result = requestHost(req, source, 10_000L)
            check(result.optBoolean("ok") && result.optBoolean("released")) {
                result.optString("reason", result.optString("message", "Mac release not confirmed"))
            }
            val sessionStillValid = { secureLinks[source] === link && sourceAuthenticated(source) }
            sessionStillValid
        },
        onStatusChanged = { statusJson ->
            scope.launch {
                if (_activeTierFlow.value == "DISCONNECTED") return@launch
                val req = JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("method", "airpods.statusReport")
                    put("params", statusJson)
                }
                sendRawFrame(OmsFrameProtocol.encodeJsonRpc(req))
            }
        }
    )

    init {
        val saved = repo.getPairedMac()
        if (saved == null) traceConnection(ConnectionDiagnostics.Event.NOT_PAIRED)
        else if (!isV2Pairing(saved)) traceConnection(ConnectionDiagnostics.Event.OLD_PAIRING)
        registerWifiNetworkCallback()
        startUdpBeaconListener()
        startBackgroundWatchdog()
        airpodsManager.start()
    }

    private fun startBackgroundWatchdog() {
        scope.launch {
            while (true) {
                delay(4500L)
                try {
                    flushOutboxQueue()
                    val now = System.currentTimeMillis()
                    for ((source, link) in secureLinks) {
                        if (!isSourceCurrent(source) || (link.phase != SecurePhase.ACTIVE && SystemClock.elapsedRealtime() >= link.expiresAt)) {
                            closeSecureSource(source, "Secure handshake expired or connection replaced")
                        }
                    }
                    val expiredTransfers = synchronized(incomingTransferLock) {
                        val exp = incomingTransfers.entries.filter { it.value.createdAtMs + 180_000 <= now }
                            .mapNotNull { (id, transfer) -> if (incomingTransfers.remove(id, transfer)) id to transfer else null }
                        OmsCoreForegroundService.setLowLatencyWifiNeeded("incoming_stream", incomingTransfers.isNotEmpty())
                        exp
                    }
                    for ((id, transfer) in expiredTransfers) {
                        transfer.cleanup()
                        streamAck(id, "ERROR", transfer.source, error = "Stream timed out")
                    }
                    val paired = repo.getPairedMac()?.takeIf { isV2Pairing(it) } ?: continue
                    val lanSource = captureSource("LAN_WS", lanWebSocket)
                    val btSource = captureSource("BT_RFCOMM", null)
                    val wanSource = captureSource("WAN_WEBRTC", wanWebSocket)
                    watchdogTickCount++
                    if (watchdogTickCount % 2L == 0L) {
                        val heartbeatTarget = when {
                            _activeTierFlow.value == "LAN_WS" && lanWebSocket != null && sourceAuthenticated(lanSource) -> lanSource
                            sourceAuthenticated(btSource) -> btSource
                            sourceAuthenticated(wanSource) -> wanSource
                            else -> null
                        }
                        if (heartbeatTarget != null) {
                            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                            val pongBytes = OmsFrameProtocol.encodePong(System.currentTimeMillis(), pct, bm.isCharging)
                            sendOnSource(pongBytes, heartbeatTarget)
                        }
                    }
                    if (_activeTierFlow.value != "LAN_WS" || lanWebSocket == null || !sourceAuthenticated(lanSource)) {
                        if (!sourceAuthenticated(btSource)) {
                            startBleNearFieldScanner()
                        }
                        connectLanIfPossible(paired.lanIps, paired.lanPort)
                        if (watchdogTickCount % 3L == 0L && wanWebSocket == null && !sourceAuthenticated(btSource)) {
                            paired.wanRelayWssUrl?.takeIf { it.isNotBlank() }?.let { connectWanRelay(it) }
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun startUdpBeaconListener() {
        scope.launch {
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(19528))
                }
                val buf = ByteArray(2048)
                while (true) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(pkt)
                    } catch (_: Throwable) {
                        delay(500L)
                        continue
                    }
                    val str = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                    if (str.contains("OMS_MAC_BEACON") || str.contains("OneMoreStep")) {
                        try {
                            val existing = repo.getPairedMac() ?: continue
                            val json = JSONObject(str)
                            val macId = json.optString("macId")
                            // Strict 1-to-1 isolation: ignore beacons from any other Mac on the LAN
                            if (!isMatchingPairedMacId(macId, existing.macId)) continue

                            val macName = json.optString("macName", existing.macName)
                            val macPub = json.optString("macPubKeyBase64Url", "")
                            val port = json.optInt("lanPort", existing.lanPort)
                            val senderIp = pkt.address?.hostAddress
                            val ips = mutableListOf<String>()
                            if (!senderIp.isNullOrBlank() && !senderIp.startsWith("198.18.")) {
                                ips.add(senderIp)
                            }
                            val arr = json.optJSONArray("lanIps")
                            if (arr != null) {
                                for (i in 0 until arr.length()) {
                                    val candidate = arr.optString(i)
                                    if (candidate.isNotBlank() && !candidate.startsWith("198.18.") && !ips.contains(candidate)) {
                                        ips.add(candidate)
                                    }
                                }
                            }
                            if (ips.isNotEmpty()) {
                                val updated = existing.copy(
                                    macId = macId,
                                    macName = macName,
                                    lanIps = ips,
                                    lanPort = port,
                                    macPubKeyBase64Url = macPub.ifBlank { existing.macPubKeyBase64Url }
                                )
                                repo.updatePairedMacDiscovery(updated)
                                if (_activeTierFlow.value != "LAN_WS" || lanWebSocket == null) {
                                    connectLanIfPossible(ips, port)
                                }
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                try {
                    sock?.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun registerWifiNetworkCallback() {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    cachedWifiNetwork = network
                    cachedLanHttpClient = null
                    traceConnection(ConnectionDiagnostics.Event.WIFI_AVAILABLE, affectState = _activeTierFlow.value != "LAN_WS" && isV2Pairing(repo.getPairedMac()))
                    if (_activeTierFlow.value != "LAN_WS" || lanWebSocket == null) {
                        scheduleLanReconnect(150L)
                    }
                }

                override fun onLost(network: Network) {
                    if (cachedWifiNetwork == network) {
                        cachedWifiNetwork = null
                        cachedLanHttpClient = null
                        traceConnection(ConnectionDiagnostics.Event.WIFI_UNAVAILABLE, affectState = isV2Pairing(repo.getPairedMac()))
                    }
                }
            })
        } catch (_: Throwable) {
        }
    }

    private fun scheduleLanReconnect(delayMs: Long = 3000L) {
        mainHandler.removeCallbacks(lanRetryRunnable)
        mainHandler.postDelayed(lanRetryRunnable, delayMs)
    }

    /**
     * Locate physical Wi-Fi Network with NET_CAPABILITY_NOT_VPN so LAN traffic bypasses
     * Xiaomi resident VPN apps (Clash / Sing-box / NekoBox) automatically!
     */
    @Suppress("DEPRECATION")
    private fun findNonVpnWifiNetwork(): Network? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val targetPrefixes = repo.getPairedMac()?.lanIps
                ?.mapNotNull { ip ->
                    val parts = ip.split(".")
                    if (parts.size == 4) "${parts[0]}.${parts[1]}." else null
                }
                ?.toSet()
                ?: emptySet()
            val activeNet = try { cm.activeNetwork } catch (_: Throwable) { null }
            var subnetMatchedWifi: Network? = null
            var activeMatchedWifi: Network? = null
            var ipv4Wifi: Network? = null
            var fallbackWifi: Network? = null
            for (net in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                ) {
                    val lp = cm.getLinkProperties(net)
                    val ipv4s = lp?.linkAddresses
                        ?.mapNotNull { (it.address as? Inet4Address)?.takeIf { a -> !a.isLoopbackAddress }?.hostAddress }
                        ?: emptyList()
                    if (ipv4s.isNotEmpty()) {
                        if (targetPrefixes.isNotEmpty() && ipv4s.any { ip -> targetPrefixes.any { p -> ip.startsWith(p) } }) {
                            subnetMatchedWifi = net
                            break
                        }
                        if (net == activeNet && activeMatchedWifi == null) {
                            activeMatchedWifi = net
                        }
                        if (ipv4Wifi == null) {
                            ipv4Wifi = net
                        }
                    }
                    if (fallbackWifi == null) fallbackWifi = net
                }
            }
            return subnetMatchedWifi ?: activeMatchedWifi ?: ipv4Wifi ?: fallbackWifi
        } catch (_: Throwable) {
        }
        return null
    }

    private fun buildLanOkHttpClient(): OkHttpClient {
        val physicalWifi = if (preferDefaultSocketFactory) null else findNonVpnWifiNetwork()
        val existing = cachedLanHttpClient
        if (existing != null && physicalWifi == cachedWifiNetwork) {
            return existing
        }
        val builder = baseHttpClient.newBuilder()
            .connectTimeout(5000, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(10, TimeUnit.SECONDS)

        if (physicalWifi != null) {
            builder.socketFactory(physicalWifi.socketFactory)
        }
        val built = builder.build()
        cachedWifiNetwork = physicalWifi
        cachedLanHttpClient = built
        return built
    }

    private fun getLocalPhoneWifiIpv4s(): List<String> {
        val result = mutableListOf<String>()
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val wifiNet = findNonVpnWifiNetwork()
            if (cm != null && wifiNet != null) {
                val lp: LinkProperties? = cm.getLinkProperties(wifiNet)
                lp?.linkAddresses?.forEach { la ->
                    val addr = la.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { result.add(it) }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        if (result.isEmpty()) {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces != null && interfaces.hasMoreElements()) {
                    val nif = interfaces.nextElement()
                    if (!nif.isUp || nif.isLoopback) continue
                    if (nif.name.startsWith("tun") || nif.name.startsWith("ppp")) continue
                    val addrs = nif.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val a = addrs.nextElement()
                        if (a is Inet4Address && !a.isLoopbackAddress) {
                            val ip = a.hostAddress ?: continue
                            if (!ip.startsWith("198.18.") && !ip.startsWith("169.254.")) {
                                result.add(ip)
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return result.distinct()
    }

    private fun executeMacHttpGet(client: OkHttpClient, ip: String, port: Int): PairedMacConfig? {
        val req = Request.Builder()
            .url("http://$ip:$port/")
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bodyStr = resp.body?.string() ?: return null
            val json = JSONObject(bodyStr)
            if (!json.optString("service").contains("OneMoreStep")) return null
            val macId = json.optString("macId").takeIf { it.isNotBlank() } ?: return null
            val macName = json.optString("macName", "MacBook Pro")
            val macPub = json.optString("macPubKeyBase64Url", "")
            val lanPort = json.optInt("lanPort", port)
            val ips = mutableListOf(ip)
            val arr = json.optJSONArray("lanIps")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val candidate = arr.optString(i)
                    if (candidate.isNotBlank() && !ips.contains(candidate)) {
                        ips.add(candidate)
                    }
                }
            }
            val existing = repo.getPairedMac()
            return PairedMacConfig(
                macId = macId,
                macName = macName,
                lanIps = ips,
                lanPort = lanPort,
                macPubKeyBase64Url = macPub.ifBlank { existing?.macPubKeyBase64Url ?: "" },
                wanRoomId = existing?.wanRoomId ?: "",
                wanRelayWssUrl = existing?.wanRelayWssUrl,
                pairSecretHex = existing?.takeIf { it.macId == macId }?.pairSecretHex ?: "",
                pairingVersion = existing?.takeIf { it.macId == macId }?.pairingVersion ?: 0
            )
        }
    }

    private fun probeMacHttpInfo(
        ip: String,
        port: Int,
        timeoutMs: Int = 600,
        checkTcpFirst: Boolean = false
    ): PairedMacConfig? {
        if (checkTcpFirst) {
            val wifiNet = findNonVpnWifiNetwork()
            try {
                val sock = wifiNet?.socketFactory?.createSocket() ?: Socket()
                sock.use { s ->
                    s.connect(InetSocketAddress(ip, port), timeoutMs)
                }
            } catch (_: Throwable) {
                return null
            }
        }

        try {
            val wifiNet = findNonVpnWifiNetwork()
            val clientBuilder = baseHttpClient.newBuilder()
                .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (wifiNet != null) {
                clientBuilder.socketFactory(wifiNet.socketFactory)
            }
            val res = executeMacHttpGet(clientBuilder.build(), ip, port)
            if (res != null) {
                if (preferDefaultSocketFactory) {
                    preferDefaultSocketFactory = false
                    cachedLanHttpClient = null
                }
                return res
            }
        } catch (_: Throwable) {
        }

        // Fallback to default system network socket if custom Wi-Fi binding failed on priority IP
        if (!checkTcpFirst) {
            try {
                val fallbackClient = baseHttpClient.newBuilder()
                    .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .build()
                val res = executeMacHttpGet(fallbackClient, ip, port)
                if (res != null) {
                    if (!preferDefaultSocketFactory) {
                        preferDefaultSocketFactory = true
                        cachedLanHttpClient = null
                    }
                    return res
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun isMatchingPairedMacId(candidateMacId: String, pairedMacId: String): Boolean {
        if (candidateMacId.isBlank() || pairedMacId.isBlank()) return false
        return candidateMacId == pairedMacId
    }

    private fun sendFastUdpDiscoveryWake(
        port: Int,
        targetMacId: String,
        phoneIps: List<String>,
        candidateIps: Collection<String>,
        timeoutMs: Int = 280
    ): PairedMacConfig? {
        val paired = repo.getPairedMac()
        try {
            DatagramSocket().use { udpSocket ->
                val wifiNet = findNonVpnWifiNetwork()
                wifiNet?.bindSocket(udpSocket)
                udpSocket.broadcast = true
                udpSocket.soTimeout = timeoutMs
                val payload = "OMS_DISCOVER_V1".toByteArray(Charsets.UTF_8)
                val targets = LinkedHashSet<String>()
                targets.add("255.255.255.255")
                for (pip in phoneIps + candidateIps) {
                    val clean = pip.trim()
                    if (clean.isNotBlank() && !clean.startsWith("198.18.")) {
                        targets.add(clean)
                    }
                    val parts = clean.split(".")
                    if (parts.size == 4) {
                        val p0 = parts[0]
                        val p1 = parts[1]
                        val p2 = parts[2].toIntOrNull() ?: continue
                        targets.add("$p0.$p1.$p2.255")
                        targets.add("$p0.$p1.${p2 or 1}.255")
                        // Wake up enterprise AC/AP gateway ARP & client forwarding table
                        targets.add("$p0.$p1.${p2 or 1}.254")
                        targets.add("$p0.$p1.$p2.1")
                    }
                }
                for (dst in targets) {
                    try {
                        val pkt = DatagramPacket(payload, payload.size, InetAddress.getByName(dst), port)
                        udpSocket.send(pkt)
                    } catch (_: Throwable) {
                    }
                }
                val buf = ByteArray(1024)
                val recvPkt = DatagramPacket(buf, buf.size)
                udpSocket.receive(recvPkt)
                val replyStr = String(recvPkt.data, 0, recvPkt.length, Charsets.UTF_8)
                val json = JSONObject(replyStr)
                if (json.optString("service").contains("OneMoreStep")) {
                    val senderIp = recvPkt.address?.hostAddress
                    val macId = json.optString("macId")
                    if (isMatchingPairedMacId(macId, targetMacId)) {
                        val ips = mutableListOf<String>()
                        if (!senderIp.isNullOrBlank()) ips.add(senderIp)
                        val arr = json.optJSONArray("lanIps")
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val s = arr.optString(i)
                                if (s.isNotBlank() && !ips.contains(s)) ips.add(s)
                            }
                        }
                        if (ips.isNotEmpty()) {
                            val found = PairedMacConfig(
                                macId = macId,
                                macName = json.optString("macName", paired?.macName ?: "MacBook Pro"),
                                lanIps = ips,
                                lanPort = json.optInt("lanPort", port),
                                macPubKeyBase64Url = json.optString("macPubKeyBase64Url", paired?.macPubKeyBase64Url ?: ""),
                                wanRoomId = paired?.wanRoomId ?: "",
                                wanRelayWssUrl = paired?.wanRelayWssUrl,
                                pairSecretHex = paired?.takeIf { it.macId == macId }?.pairSecretHex ?: "",
                                pairingVersion = paired?.takeIf { it.macId == macId }?.pairingVersion ?: 0
                            )
                            repo.updatePairedMacDiscovery(found)
                            return repo.getPairedMac() ?: found
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return null
    }

    /**
     * Automatically discover the ALREADY PAIRED Mac Host on LAN when DHCP IP changes (e.g. 192.168.1.77 -> 192.168.1.100).
     * Strictly enforces 1-to-1 device binding: only accepts responses where `macId == targetMacId`.
     * Never binds to or overwrites config with an unknown Mac on the same LAN.
     */
    private suspend fun discoverMacOnLan(
        port: Int = 19527,
        expectedMacId: String? = null,
        allowSubnetScan: Boolean = false
    ): PairedMacConfig? = coroutineScope {
        val paired = repo.getPairedMac()
        val targetMacId = expectedMacId?.takeIf { it.isNotBlank() }
            ?: paired?.macId?.takeIf { it.isNotBlank() }
            ?: return@coroutineScope null
        val phoneIps = getLocalPhoneWifiIpv4s()
        val phonePrefixes = phoneIps.mapNotNull { ip ->
            val parts = ip.split(".")
            if (parts.size == 4) "${parts[0]}.${parts[1]}." else null
        }.toSet()

        val priorityIps = LinkedHashSet<String>()
        paired?.lanIps?.forEach { if (it.isNotBlank() && !it.startsWith("198.18.")) priorityIps.add(it) }

        // 1. Fast UDP Broadcast + Unicast Discovery Probe FIRST (<=300ms, wakes enterprise AP ARP/forwarding table!)
        val udpFound = sendFastUdpDiscoveryWake(port, targetMacId, phoneIps, priorityIps, timeoutMs = 300)
        if (udpFound != null) {
            return@coroutineScope udpFound
        }

        // 2. Priority Direct HTTP Probe (prioritize current Wi-Fi subnet; avoid stalling 5s+ on stale hotspot IPs!)
        val subnetMatchedIps = if (phonePrefixes.isNotEmpty()) {
            priorityIps.filter { cand -> phonePrefixes.any { p -> cand.startsWith(p) } }
        } else {
            priorityIps.toList()
        }
        val probeCandidates = if (subnetMatchedIps.isNotEmpty()) subnetMatchedIps else priorityIps.toList()
        for (pip in probeCandidates) {
            val cfg = probeMacHttpInfo(pip, port, timeoutMs = 1500, checkTcpFirst = false)
            if (cfg != null && isMatchingPairedMacId(cfg.macId, targetMacId)) {
                repo.updatePairedMacDiscovery(cfg)
                return@coroutineScope repo.getPairedMac() ?: cfg
            }
        }

        if (!allowSubnetScan) {
            return@coroutineScope null
        }

        // 3. Gentle Batched Subnet Scan (ONLY when explicitly requested by user action, 16 IPs per batch to protect ARP table)
        val prefixes = LinkedHashSet<String>()
        for (ip in phoneIps + priorityIps) {
            val parts = ip.split(".")
            if (parts.size == 4) {
                val p0 = parts[0]
                val p1 = parts[1]
                val p2 = parts[2].toIntOrNull() ?: continue
                prefixes.add("$p0.$p1.$p2")
                prefixes.add("$p0.$p1.${p2 xor 1}")
            }
        }
        if (prefixes.isEmpty()) {
            prefixes.add("192.168.1")
        }

        val scanIps = mutableListOf<String>()
        for (pip in priorityIps) {
            if (!scanIps.contains(pip)) scanIps.add(pip)
        }
        for (prefix in prefixes) {
            for (host in 1..254) {
                val candidate = "$prefix.$host"
                if (!scanIps.contains(candidate)) {
                    scanIps.add(candidate)
                }
            }
        }

        for (batch in scanIps.chunked(16)) {
            val results = batch.map { ip ->
                async(Dispatchers.IO) {
                    val cfg = probeMacHttpInfo(ip, port, timeoutMs = 240, checkTcpFirst = true)
                    if (cfg != null && isMatchingPairedMacId(cfg.macId, targetMacId)) cfg else null
                }
            }.awaitAll()
            val hit = results.firstOrNull { it != null }
            if (hit != null) {
                repo.updatePairedMacDiscovery(hit)
                return@coroutineScope repo.getPairedMac() ?: hit
            }
        }

        null
    }

    fun startAutoConnect() {
        val paired = repo.getPairedMac()?.takeIf { isV2Pairing(it) } ?: return
        startBleNearFieldScanner()
        connectLanIfPossible(paired.lanIps, paired.lanPort)
        paired.wanRelayWssUrl?.takeIf { it.isNotBlank() }?.let { connectWanRelay(it) }
    }

    /**
     * Manual one-tap reconnect & LAN auto-discovery triggered from UI (strictly restricted to already-paired Mac)
     */
    fun reconnectNow(onResult: (Boolean, String) -> Unit) {
        scope.launch {
            val paired = repo.getPairedMac()
            if (!isV2Pairing(paired)) {
                mainHandler.post {
                    onResult(false, "请在 Mac 生成新版 v2 完整配对链接并重新绑定；旧配对与六位码不再支持")
                }
                return@launch
            }
            if (paired == null) return@launch
            if (_activeTierFlow.value == "LAN_WS" && lanWebSocket != null && isSourceAuthenticated("LAN_WS", lanWebSocket)) {
                mainHandler.post { onResult(true, "✅ 已处于局域网 v2 加密连接状态 (${paired.macName})") }
                return@launch
            }
            if (!isSourceAuthenticated("BT_RFCOMM", null)) {
                startBleNearFieldScanner(forceRestart = true)
            }
            isConnectingLan.set(false)
            val port = paired.lanPort
            val discovered = discoverMacOnLan(port, paired.macId, allowSubnetScan = true)
            if (discovered != null) {
                connectLanIfPossible(discovered.lanIps, discovered.lanPort)
                mainHandler.post {
                    onResult(
                        true,
                        "🟢 已找到已绑定的 ${discovered.macName} (${discovered.lanIps.first()})，正在双向加密握手..."
                    )
                }
            } else {
                connectLanIfPossible(paired.lanIps, port)
                mainHandler.post {
                    if (isSourceAuthenticated("BT_RFCOMM", null)) {
                        onResult(
                            true,
                            "🔵 局域网当前不可达，已通过近场蓝牙 (Tier 2) 保持加密连接"
                        )
                    } else {
                        onResult(
                            false,
                            "⚠️ 局域网暂不可达 (${paired.macName})，已同时启动近场蓝牙扫描重连..."
                        )
                    }
                }
            }
        }
    }

    /** Pairing credentials are accepted only from a user-supplied complete v2 URI. */
    fun pairWithUri(pairingUri: String, onResult: (Boolean, String) -> Unit) {
        val generation = synchronized(connectionLock) { pairGeneration.incrementAndGet() }
        pairingWebSocket?.let { closeSecureSource(FrameSource("LAN_WS", it, null), "Pairing attempt replaced") }
        scope.launch {
            try {
                val uri = Uri.parse(pairingUri.trim())
                require(uri.scheme == "oms" && uri.host == "pair" && uri.getQueryParameters("v") == listOf("2"))
                fun one(name: String): String {
                    val values = uri.getQueryParameters(name)
                    require(values.size == 1)
                    return values.single()
                }
                val macId = one("id")
                val token = one("token")
                val expiresAtMs = one("exp").toLong()
                require(Regex("[A-Za-z0-9_.-]{1,128}").matches(macId))
                require(Regex("[0-9a-f]{64}").matches(token))
                require(expiresAtMs > System.currentTimeMillis() && expiresAtMs - System.currentTimeMillis() <= 600_000)
                val port = one("port").toInt()
                require(port in 1..65535)
                val ips = one("ip").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                require(ips.isNotEmpty() && ips.all { host ->
                    val parts = host.split('.')
                    parts.size == 4 && parts.all { it.toIntOrNull()?.let { octet -> octet in 0..255 } == true }
                })
                val config = PairedMacConfig(macId, uri.getQueryParameter("name")?.take(256) ?: "Mac", ips,
                    port, "", "", null)
                val attempt = PairAttempt(generation, config, token, expiresAtMs, onResult)
                val targetIp = ips.firstOrNull { probeMacHttpInfo(it, port, 1200)?.macId == macId } ?: ips.first()
                check(generation == pairGeneration.get() && System.currentTimeMillis() < expiresAtMs)
                val request = Request.Builder().url("ws://$targetIp:$port/?tier=lan").build()
                buildLanOkHttpClient().newWebSocket(request, secureSocketListener("LAN_WS", generation, attempt))
            } catch (_: Exception) {
                if (generation == pairGeneration.get()) mainHandler.post {
                    onResult(false, "配对链接无效或已过期：请从 Mac 本地获取 v2 完整 oms://pair 链接；不支持六位码、IP 或旧链接")
                }
            }
        }
    }

    fun connectLanIfPossible(ips: List<String>, port: Int) {
        if (hasPendingSecureHandshake(captureSource("LAN_WS", lanWebSocket))) return
        if (_activeTierFlow.value == "LAN_WS" && lanWebSocket != null && isSourceAuthenticated("LAN_WS", lanWebSocket)) return
        val paired = repo.getPairedMac()?.takeIf { isV2Pairing(it) } ?: return
        if (pairingWebSocket != null || !isConnectingLan.compareAndSet(false, true)) return
        val generation = synchronized(connectionLock) { lanGeneration.incrementAndGet() }
        traceConnection(if (findNonVpnWifiNetwork() == null) ConnectionDiagnostics.Event.WIFI_UNAVAILABLE
            else ConnectionDiagnostics.Event.CONNECTING, attempt = generation)

        scope.launch {
            try {
                val phoneIps = getLocalPhoneWifiIpv4s()
                val phonePrefixes = phoneIps.mapNotNull { ip ->
                    val parts = ip.split(".")
                    if (parts.size == 4) "${parts[0]}.${parts[1]}." else null
                }.toSet()
                val rawCandidates = (ips + paired.lanIps)
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.startsWith("198.18.") }
                    .distinct()
                val subnetMatched = rawCandidates.filter { cand -> phonePrefixes.any { p -> cand.startsWith(p) } }
                val subnetUnmatched = rawCandidates.filterNot { cand -> phonePrefixes.any { p -> cand.startsWith(p) } }
                val candidateIps = subnetMatched + subnetUnmatched

                var reachableConfig: PairedMacConfig? = null
                if (candidateIps.isEmpty() || (phonePrefixes.isNotEmpty() && subnetMatched.isEmpty())) {
                    reachableConfig = discoverMacOnLan(port, paired.macId, allowSubnetScan = false)
                } else if (subnetMatched.size == 1 && !subnetMatched.first().startsWith("127.")) {
                    // Always fire a fast 180ms UDP broadcast/unicast wake probe before WS connect on enterprise Wi-Fi
                    reachableConfig = sendFastUdpDiscoveryWake(
                        port = port,
                        targetMacId = paired.macId,
                        phoneIps = phoneIps,
                        candidateIps = subnetMatched,
                        timeoutMs = 180
                    )
                } else if (subnetMatched.size > 1) {
                    reachableConfig = sendFastUdpDiscoveryWake(
                        port = port,
                        targetMacId = paired.macId,
                        phoneIps = phoneIps,
                        candidateIps = subnetMatched,
                        timeoutMs = 180
                    )
                    if (reachableConfig == null) {
                        for (candidate in subnetMatched) {
                            val info = probeMacHttpInfo(candidate, port, timeoutMs = 1500, checkTcpFirst = false)
                            if (info != null && isMatchingPairedMacId(info.macId, paired.macId)) {
                                reachableConfig = info
                                repo.updatePairedMacDiscovery(info)
                                break
                            }
                        }
                    }
                }

                val targetIp = reachableConfig?.lanIps?.firstOrNull() ?: candidateIps.firstOrNull()
                val targetPort = reachableConfig?.lanPort ?: port
                if (targetIp.isNullOrBlank()) {
                    if (generation == lanGeneration.get()) {
                        traceConnection(if (findNonVpnWifiNetwork() == null) ConnectionDiagnostics.Event.WIFI_UNAVAILABLE
                            else ConnectionDiagnostics.Event.MAC_UNREACHABLE, attempt = generation)
                    }
                    isConnectingLan.set(false)
                    scheduleLanReconnect(3500L)
                    return@launch
                }

                val client = buildLanOkHttpClient()
                val req = Request.Builder().url("ws://$targetIp:$targetPort/?tier=lan").build()
                if (generation != lanGeneration.get()) {
                    if (lanWebSocket == null) isConnectingLan.set(false)
                    return@launch
                }
                client.newWebSocket(req, secureSocketListener("LAN_WS", generation))
            } catch (_: Throwable) {
                isConnectingLan.set(false)
                scheduleLanReconnect(3500L)
            }
        }
    }

    fun connectWanRelay(wssUrl: String) {
        if (wanWebSocket != null && isSourceAuthenticated("WAN_WEBRTC", wanWebSocket)) return
        if (hasPendingSecureHandshake(captureSource("WAN_WEBRTC", wanWebSocket))) return
        if (!isV2Pairing(repo.getPairedMac())) return
        val client = baseHttpClient.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
        val generation = synchronized(connectionLock) { wanGeneration.incrementAndGet() }
        client.newWebSocket(Request.Builder().url(wssUrl).build(), secureSocketListener("WAN_WEBRTC", generation))
    }

    private fun secureSocketListener(tier: String, generation: Long, pairing: PairAttempt? = null) =
        object : WebSocketListener() {
            private val decoder = OmsFrameStreamDecoder()
            @Volatile private var didOpen = false
            override fun onOpen(webSocket: WebSocket, response: Response) {
                didOpen = true
                val source = FrameSource(tier, webSocket, null)
                var replaced: FrameSource? = null
                val accepted = synchronized(connectionLock) {
                    val expected = if (pairing != null) pairGeneration.get() else if (tier == "LAN_WS") lanGeneration.get() else wanGeneration.get()
                    val config = pairing?.config ?: repo.getPairedMac()
                    if (generation != expected || config == null || (pairing == null && !isV2Pairing(config)) ||
                        (pairing != null && System.currentTimeMillis() >= pairing.expiresAtMs)) false else {
                        if (pairing != null) {
                            replaced = pairingWebSocket?.let { FrameSource(tier, it, null) }
                            pairingWebSocket = webSocket
                        } else if (tier == "LAN_WS") {
                            replaced = lanWebSocket?.let { FrameSource(tier, it, null) }
                            lanWebSocket = webSocket
                            isConnectingLan.set(false)
                        } else {
                            replaced = wanWebSocket?.let { FrameSource(tier, it, null) }
                            wanWebSocket = webSocket
                        }
                        beginSecureSource(source, config, pairing)
                        true
                    }
                }
                replaced?.let { closeSecureSource(it, "Connection replaced") }
                if (!accepted) {
                    if (tier == "LAN_WS" && pairing == null && lanWebSocket == null) {
                        isConnectingLan.set(false)
                    }
                    webSocket.close(4003, "Stale or invalid secure connection")
                    reportPairing(pairing, false)
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleIncomingRawBytes(tier, decoder, bytes.toByteArray(), webSocket)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                closeSecureSource(FrameSource(tier, webSocket, null), "Text frames are not supported")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                closeSecureSource(FrameSource(tier, webSocket, null), "Connection closing")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closeSecureSource(FrameSource(tier, webSocket, null), "Connection closed")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val source = FrameSource(tier, webSocket, null)
                val affectsCurrent = isSourceCurrent(source) ||
                    (!didOpen && pairing == null && tier == "LAN_WS" && generation == lanGeneration.get())
                closeSecureSource(source, "Connection failed")
                if (affectsCurrent) traceConnection(
                    if (tier == "LAN_WS" && findNonVpnWifiNetwork() == null) ConnectionDiagnostics.Event.WIFI_UNAVAILABLE
                    else ConnectionDiagnostics.networkFailure(t), tier, generation)
                if (pairing != null && !didOpen && tier == "LAN_WS" &&
                    generation == pairGeneration.get() && System.currentTimeMillis() < pairing.expiresAtMs
                ) {
                    val btEnabled = runCatching {
                        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true
                    }.getOrDefault(false)
                    if (btEnabled) {
                        activeBlePairingAttempt = pairing
                        activeBleGatt?.let { existingGatt ->
                            closeSecureSource(FrameSource("BT_RFCOMM", null, existingGatt), "Pairing attempt replaced")
                        }
                        startBleNearFieldScanner(forceRestart = true)
                        mainHandler.postDelayed({
                            if (generation == pairGeneration.get() && activeBlePairingAttempt === pairing && !pairing.reported.get()) {
                                activeBlePairingAttempt = null
                                reportPairing(pairing, false)
                            }
                        }, 15_000L)
                        return
                    }
                }
                reportPairing(pairing, false)
                if (tier == "LAN_WS" && pairing == null) {
                    if (lanWebSocket == null) {
                        isConnectingLan.set(false)
                    }
                    if (generation == lanGeneration.get()) {
                        if (!isSourceAuthenticated("BT_RFCOMM", null)) {
                            startBleNearFieldScanner()
                        }
                        if (!didOpen) {
                            if (preferDefaultSocketFactory) {
                                preferDefaultSocketFactory = false
                                cachedLanHttpClient = null
                            }
                            val paired = repo.getPairedMac()
                            if (paired != null) {
                                scope.launch {
                                    discoverMacOnLan(paired.lanPort, paired.macId, allowSubnetScan = false)
                                }
                            }
                        }
                        scheduleLanReconnect(3000L)
                    }
                }
            }
        }

    @SuppressLint("MissingPermission")
    private fun handleBleMacInfoBytes(
        gatt: BluetoothGatt,
        pipeChar: BluetoothGattCharacteristic?,
        valBytes: ByteArray
    ) {
        try {
            val pendingPair = activeBlePairingAttempt?.takeIf {
                it.generation == pairGeneration.get() && System.currentTimeMillis() < it.expiresAtMs
            }
            val existing = pendingPair?.config ?: repo.getPairedMac()
            val info = JSONObject(String(valBytes, Charsets.UTF_8))
            val macId = info.optString("macId")
            // Strict 1-to-1 isolation: disconnect immediately if BLE peripheral is not the paired Mac
            if (existing == null || !isMatchingPairedMacId(macId, existing.macId)) {
                try {
                    gatt.disconnect()
                    gatt.close()
                } catch (_: Throwable) {
                }
                closeSecureSource(FrameSource("BT_RFCOMM", null, gatt), "Untrusted Bluetooth identity")
                return
            }

            val ipsArr = info.optJSONArray("lanIps")
            val port = info.optInt("lanPort", existing.lanPort)
            val ips = mutableListOf<String>()
            if (ipsArr != null) {
                for (i in 0 until ipsArr.length()) {
                    val s = ipsArr.getString(i)
                    if (s.isNotBlank() && !s.startsWith("198.18.")) {
                        ips.add(s)
                    }
                }
            }
            if (ips.isNotEmpty() && pendingPair == null) {
                repo.updatePairedMacDiscovery(
                    existing.copy(
                        macId = macId,
                        lanIps = ips,
                        lanPort = port
                    )
                )
                connectLanIfPossible(ips, port)
            }
            if (pipeChar != null) {
                gatt.setCharacteristicNotification(pipeChar, true)
                val cccd = pipeChar.getDescriptor(BLE_CCCD_UUID)
                if (cccd != null) {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(cccd)
                }
            }
        } catch (_: Throwable) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBleNearFieldScan() {
        val cb = activeBleScanCallback
        activeBleScanCallback = null
        isBleScanning.set(false)
        if (cb != null) {
            try {
                val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                bm?.adapter?.bluetoothLeScanner?.stopScan(cb)
            } catch (_: Throwable) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleNearFieldScanner(forceRestart: Boolean = false) {
        try {
            val pendingPair = activeBlePairingAttempt?.takeIf {
                it.generation == pairGeneration.get() && System.currentTimeMillis() < it.expiresAtMs
            }
            if (pendingPair == null && !isV2Pairing(repo.getPairedMac())) return
            val existingGatt = activeBleGatt
            if (existingGatt != null) {
                val btSource = FrameSource("BT_RFCOMM", null, existingGatt)
                val nowElapsed = SystemClock.elapsedRealtime()
                if (!sourceAuthenticated(btSource) && bleConnectStartedAtMs > 0L && nowElapsed - bleConnectStartedAtMs > 15_000L) {
                    closeSecureSource(btSource, "Stale BLE connection attempt timed out")
                } else {
                    return
                }
            }
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
            val adapter: BluetoothAdapter = bm.adapter ?: return
            if (!adapter.isEnabled) {
                isBleScanning.set(false)
                return
            }
            val scanner = adapter.bluetoothLeScanner ?: return
            val now = SystemClock.elapsedRealtime()
            if (isBleScanning.get()) {
                if (forceRestart || now - lastBleScanStartMs > 10_000L) {
                    stopBleNearFieldScan()
                } else {
                    return
                }
            }
            if (!forceRestart && lastBleScanStartMs > 0L && now - lastBleScanStartMs < 7_000L) {
                return
            }
            if (!isBleScanning.compareAndSet(false, true)) return
            lastBleScanStartMs = now

            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BLE_SERVICE_UUID))
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            val scanCallback = object : ScanCallback() {
                override fun onScanFailed(errorCode: Int) {
                    if (activeBleScanCallback === this) {
                        activeBleScanCallback = null
                    }
                    isBleScanning.set(false)
                }

                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.device ?: return
                    if (activeBleGatt == null) {
                        stopBleNearFieldScan()
                        bleConnectStartedAtMs = SystemClock.elapsedRealtime()
                        activeBleGatt = device.connectGatt(
                            context,
                            false,
                            object : BluetoothGattCallback() {
                                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                                    if (gatt !== activeBleGatt) return
                                    if (status != BluetoothGatt.GATT_SUCCESS) {
                                        closeSecureSource(FrameSource("BT_RFCOMM", null, gatt), "Bluetooth connection failed")
                                        return
                                    }
                                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                                        btDecoder.reset()
                                        bleWriteQueue.clear()
                                        isBleWriting.set(false)
                                        blePayloadLimit = 180
                                        bleConnectStartedAtMs = SystemClock.elapsedRealtime()
                                        val blePair = activeBlePairingAttempt?.takeIf {
                                            it.generation == pairGeneration.get() && System.currentTimeMillis() < it.expiresAtMs
                                        }
                                        val paired = blePair?.config ?: repo.getPairedMac()?.takeIf { isV2Pairing(it) }
                                        if (paired == null) {
                                            closeSecureSource(FrameSource("BT_RFCOMM", null, gatt), "Not paired")
                                            return
                                        }
                                        beginSecureSource(FrameSource("BT_RFCOMM", null, gatt), paired, blePair)
                                        if (!gatt.requestMtu(512)) {
                                            gatt.discoverServices()
                                        }
                                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                                        bleWriteQueue.clear()
                                        isBleWriting.set(false)
                                        closeSecureSource(FrameSource("BT_RFCOMM", null, gatt), "Bluetooth disconnected")
                                    }
                                }

                                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                                    if (gatt !== activeBleGatt) return
                                    if (status == BluetoothGatt.GATT_SUCCESS) {
                                        blePayloadLimit = (mtu - 3).coerceIn(20, 500)
                                    }
                                    gatt.discoverServices()
                                }

                                override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                                    if (gatt !== activeBleGatt) return
                                    if (status != BluetoothGatt.GATT_SUCCESS) {
                                        bleWriteQueue.clear()
                                        isBleWriting.set(false)
                                        closeSecureSource(FrameSource("BT_RFCOMM", null, gatt), "Encrypted Bluetooth write failed")
                                        return
                                    }
                                    isBleWriting.set(false)
                                    drainBleWriteQueue(gatt)
                                }

                                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                                    if (gatt !== activeBleGatt) return
                                    if (status != BluetoothGatt.GATT_SUCCESS) {
                                        closeSecureSource(FrameSource("BT_RFCOMM", null, gatt), "BLE service discovery failed")
                                        return
                                    }
                                    val svc = gatt.getService(BLE_SERVICE_UUID)
                                    if (svc == null) {
                                        closeSecureSource(FrameSource("BT_RFCOMM", null, gatt), "Missing OMS BLE service")
                                        return
                                    }
                                    val infoChar = svc.getCharacteristic(BLE_CHAR_MAC_INFO_UUID)
                                    val pipeChar = svc.getCharacteristic(BLE_CHAR_FRAME_PIPE_UUID)
                                    bleFramePipeChar = pipeChar
                                    if (infoChar != null && pipeChar != null) {
                                        gatt.readCharacteristic(infoChar)
                                    } else {
                                        closeSecureSource(FrameSource("BT_RFCOMM", null, gatt), "Missing Mac identity characteristic")
                                    }
                                }

                                // Android 13+ (API 33+) callback signature
                                override fun onCharacteristicRead(
                                    gatt: BluetoothGatt,
                                    characteristic: BluetoothGattCharacteristic,
                                    value: ByteArray,
                                    status: Int
                                ) {
                                    if (gatt !== activeBleGatt) return
                                    if (characteristic.uuid == BLE_CHAR_MAC_INFO_UUID && value.isNotEmpty()) {
                                        handleBleMacInfoBytes(gatt, bleFramePipeChar, value)
                                    }
                                }

                                // Android 12 and below fallback signature
                                @Suppress("DEPRECATION")
                                override fun onCharacteristicRead(
                                    gatt: BluetoothGatt,
                                    characteristic: BluetoothGattCharacteristic,
                                    status: Int
                                ) {
                                    if (gatt !== activeBleGatt) return
                                    if (characteristic.uuid == BLE_CHAR_MAC_INFO_UUID) {
                                        val valBytes = characteristic.value ?: return
                                        handleBleMacInfoBytes(gatt, bleFramePipeChar, valBytes)
                                    }
                                }

                                // Android 13+ (API 33+) callback signature
                                override fun onCharacteristicChanged(
                                    gatt: BluetoothGatt,
                                    characteristic: BluetoothGattCharacteristic,
                                    value: ByteArray
                                ) {
                                    if (gatt !== activeBleGatt) return
                                    if (characteristic.uuid == BLE_CHAR_FRAME_PIPE_UUID && value.isNotEmpty()) {
                                        handleIncomingRawBytes("BT_RFCOMM", btDecoder, value, sourceBleGatt = gatt)
                                    }
                                }

                                // Android 12 and below fallback signature
                                @Suppress("DEPRECATION")
                                override fun onCharacteristicChanged(
                                    gatt: BluetoothGatt,
                                    characteristic: BluetoothGattCharacteristic
                                ) {
                                    if (gatt !== activeBleGatt) return
                                    if (characteristic.uuid == BLE_CHAR_FRAME_PIPE_UUID) {
                                        val bytes = characteristic.value ?: return
                                        handleIncomingRawBytes("BT_RFCOMM", btDecoder, bytes, sourceBleGatt = gatt)
                                    }
                                }
                            },
                            BluetoothDevice.TRANSPORT_LE
                        )
                    }
                }
            }
            activeBleScanCallback = scanCallback
            scanner.startScan(listOf(filter), settings, scanCallback)
            mainHandler.postDelayed({
                if (activeBleScanCallback === scanCallback && isBleScanning.get()) {
                    stopBleNearFieldScan()
                }
            }, 9500L)
        } catch (_: Throwable) {
            activeBleScanCallback = null
            isBleScanning.set(false)
        }
    }

    private fun recomputeFallbackTier() {
        _activeTierFlow.value = listOf(captureSource("LAN_WS", lanWebSocket), captureSource("BT_RFCOMM", null),
            captureSource("WAN_WEBRTC", wanWebSocket)).firstOrNull { sourceAuthenticated(it) }?.tier ?: "DISCONNECTED"
    }

    private fun captureSource(tier: String, socket: WebSocket?): FrameSource =
        FrameSource(tier, socket, if (socket == null && tier == "BT_RFCOMM") activeBleGatt else null)

    private fun isSourceCurrent(source: FrameSource): Boolean = when (source.tier) {
        "LAN_WS" -> source.socket != null && (source.socket === lanWebSocket || source.socket === pairingWebSocket)
        "WAN_WEBRTC" -> source.socket != null && source.socket === wanWebSocket
        "BT_RFCOMM" -> source.gatt != null && source.gatt === activeBleGatt
        else -> false
    }

    private fun hasPendingSecureHandshake(source: FrameSource): Boolean {
        val link = secureLinks[source] ?: return false
        return isSourceCurrent(source) && SystemClock.elapsedRealtime() < link.expiresAt &&
            link.phase in setOf(SecurePhase.HELLO, SecurePhase.START, SecurePhase.FINISH)
    }

    private fun sourceAuthenticated(source: FrameSource): Boolean {
        val link = secureLinks[source] ?: return false
        val paired = repo.getPairedMac() ?: return false
        return link.phase == SecurePhase.ACTIVE && isSourceCurrent(source) && isV2Pairing(paired) &&
            paired.macId == link.config.macId && paired.pairSecretHex == link.newPairSecret
    }

    private fun activeSource(): FrameSource = listOf(captureSource("LAN_WS", lanWebSocket),
        captureSource("BT_RFCOMM", null), captureSource("WAN_WEBRTC", wanWebSocket))
        .firstOrNull { sourceAuthenticated(it) } ?: throw IllegalStateException("No encrypted connection")

    @SuppressLint("MissingPermission")
    private fun drainBleWriteQueue(gatt: BluetoothGatt): Boolean {
        if (gatt !== activeBleGatt) return false
        val characteristic = bleFramePipeChar ?: return false
        if (!isBleWriting.compareAndSet(false, true)) return true
        val nextChunk = bleWriteQueue.poll()
        if (nextChunk == null) {
            isBleWriting.set(false)
            return true
        }
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        @Suppress("DEPRECATION")
        characteristic.value = nextChunk
        @Suppress("DEPRECATION")
        val started = gatt.writeCharacteristic(characteristic)
        if (!started) {
            isBleWriting.set(false)
            bleWriteQueue.clear()
            return false
        }
        return true
    }

    /** Only secure.start plaintext and already sealed records may reach this transport writer. */
    @SuppressLint("MissingPermission")
    private fun writeTransport(frameBytes: ByteArray, source: FrameSource): Boolean {
        if (!isSourceCurrent(source)) return false
        source.socket?.let { return it.send(frameBytes.toByteString()) }
        val gatt = source.gatt ?: return false
        if (bleFramePipeChar == null) return false
        val chunkLimit = blePayloadLimit.coerceIn(20, 500)
        var offset = 0
        while (offset < frameBytes.size) {
            val end = (offset + chunkLimit).coerceAtMost(frameBytes.size)
            bleWriteQueue.add(frameBytes.copyOfRange(offset, end))
            offset = end
        }
        return drainBleWriteQueue(gatt)
    }

    private fun sendOnSource(frameBytes: ByteArray, source: FrameSource): Boolean {
        val link = secureLinks[source] ?: return false
        return synchronized(link.lock) {
            try {
                check(sourceAuthenticated(source)) { "Secure connection is unavailable" }
                check(writeTransport(requireNotNull(link.session).seal(frameBytes), source)) { "Encrypted write failed" }
                true
            } catch (_: Exception) {
                closeSecureSource(source, "Encrypted send failed")
                false
            }
        }
    }

    private fun reportPairing(pairing: PairAttempt?, success: Boolean) {
        if (pairing != null && pairing.reported.compareAndSet(false, true)) mainHandler.post {
            pairing.callback(success, if (success) "已完成 v2 加密配对：${pairing.config.macName}" else
                "安全配对未完成；收件数据未清除，请获取新的 v2 链接重新配对")
        }
    }

    private fun beginSecureSource(source: FrameSource, config: PairedMacConfig, pairing: PairAttempt? = null) {
        val link = SecureLink(config, if (pairing != null) "pair" else "auth", pairing?.token ?: config.pairSecretHex, pairing)
        synchronized(connectionLock) {
            check(isSourceCurrent(source) && secureLinks.putIfAbsent(source, link) == null)
        }
        traceConnection(ConnectionDiagnostics.Event.HANDSHAKE_STARTED, source.tier)
        mainHandler.postDelayed({
            if (secureLinks[source] === link && link.phase != SecurePhase.ACTIVE) closeSecureSource(source, "Secure handshake timed out")
        }, 30_000)
    }

    @SuppressLint("MissingPermission")
    private fun closeSecureSource(source: FrameSource, reason: String) {
        val wasCurrent = isSourceCurrent(source)
        val link = synchronized(connectionLock) {
            if (source.socket === pairingWebSocket && source.socket != null) pairingWebSocket = null
            if (source.socket === lanWebSocket && source.socket != null) { lanWebSocket = null; isConnectingLan.set(false) }
            if (source.socket === wanWebSocket && source.socket != null) wanWebSocket = null
            if (source.gatt != null && source.gatt === activeBleGatt) {
                activeBleGatt = null
                bleFramePipeChar = null
                bleConnectStartedAtMs = 0L
                bleWriteQueue.clear()
                isBleWriting.set(false)
            }
            secureLinks.remove(source)
        }
        if (link != null) synchronized(link.lock) {
            if (wasCurrent && reason !in setOf("Connection replaced", "Pairing attempt replaced", "Re-pair completed")) {
                val event = when {
                    reason == "Secure protocol validation failed" || reason == "Text frames are not supported" -> ConnectionDiagnostics.Event.SECURITY_REJECTED
                    reason.contains("timed out") || reason == "Secure handshake expired or connection replaced" -> ConnectionDiagnostics.Event.HANDSHAKE_TIMEOUT
                    else -> ConnectionDiagnostics.Event.CONNECTION_LOST
                }
                traceConnection(event, source.tier)
            }
            link.phase = SecurePhase.CLOSED
            link.session?.destroy()
            link.session = null
            link.pskHex = ""
            link.pairing?.token = ""
            link.newPairSecret = ""
            link.expectedMacProof = ""
            reportPairing(link.pairing, false)
        }
        for ((id, pending) in pendingReplies) {
            if (pending.source == source && pendingReplies.remove(id, pending)) pending.result.completeExceptionally(IllegalStateException(reason))
        }
        val iter = incomingTransfers.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.value.source == source) {
                entry.value.cleanup()
                iter.remove()
            }
        }
        OmsCoreForegroundService.setLowLatencyWifiNeeded("incoming_stream", incomingTransfers.isNotEmpty())
        if (source.gatt != null) runCatching { source.gatt.disconnect(); source.gatt.close() }
        runCatching { source.socket?.close(4003, reason.take(100)) }
        recomputeFallbackTier()
        if (mirrorActive.get() && _activeTierFlow.value !in setOf("LAN_WS", "WAN_WEBRTC")) {
            stopScreenMirror()
        }
        if (_activeTierFlow.value == "DISCONNECTED") {
            mainHandler.postDelayed({
                if (_activeTierFlow.value == "DISCONNECTED" && !isSourceAuthenticated("BT_RFCOMM", null)) {
                    startBleNearFieldScanner()
                }
            }, 1500L)
        }
    }

    fun sendRawFrame(frameBytes: ByteArray): Boolean =
        runCatching { sendOnSource(frameBytes, activeSource()) }.getOrDefault(false)

    private suspend fun requestHost(request: JSONObject, source: FrameSource, timeoutMs: Long = 30_000): JSONObject {
        val id = request.getString("id")
        val pending = PendingReply(source, CompletableDeferred())
        check(pendingReplies.putIfAbsent(id, pending) == null)
        try {
            check(sendOnSource(OmsFrameProtocol.encodeJsonRpc(request), source)) { "Connection unavailable" }
            val response = withTimeoutOrNull(timeoutMs) { pending.result.await() }
                ?: throw IllegalStateException("Host acknowledgement timed out")
            response.optJSONObject("error")?.let { throw IllegalStateException(it.optString("message", "Host rejected request")) }
            return response.optJSONObject("result") ?: throw IllegalStateException("Missing host result")
        } finally {
            pendingReplies.remove(id, pending)
        }
    }

    private data class OpenedShareAttachment(
        val originalUri: Uri,
        val stream: InputStream?,
        val itemId: String,
        val fileName: String,
        val mimeType: String
    )

    private fun uniqueFileNameInSet(baseName: String, usedLower: MutableSet<String>): String {
        val dot = baseName.lastIndexOf('.')
        val stem = if (dot > 0) baseName.substring(0, dot) else baseName
        val ext = if (dot > 0) baseName.substring(dot) else ""
        var candidate = baseName
        var idx = 2
        while (!usedLower.add(candidate.lowercase())) {
            candidate = "${stem}_${idx++}${ext}"
        }
        return candidate
    }

    private fun openShareAttachmentForPersistence(
        uri: Uri?,
        itemId: String,
        suffix: String,
        usedNames: MutableSet<String>
    ): OpenedShareAttachment? {
        if (uri == null) return null
        if (!ShareInputPolicy.isSafeInternalOrContentUri(uri, listOf(context.filesDir, context.cacheDir))) {
            return OpenedShareAttachment(uri, null, itemId, "", "application/octet-stream")
        }
        return try {
            val stream = if (uri.scheme == "file" && uri.path != null) {
                File(uri.path!!).takeIf { it.exists() }?.inputStream()
            } else {
                context.contentResolver.openInputStream(uri)
            }
            val meta = AttachmentResolver.describeUri(
                context,
                uri,
                fallbackPrefix = if (suffix.startsWith("audio")) "voice_${itemId.takeLast(6)}" else "file_${suffix}"
            )
            val uniqueName = uniqueFileNameInSet(meta.safeFileName, usedNames)
            OpenedShareAttachment(uri, stream, itemId, uniqueName, meta.mimeType)
        } catch (_: Throwable) {
            OpenedShareAttachment(uri, null, itemId, "", "application/octet-stream")
        }
    }

    private fun persistOpenedShareAttachment(
        opened: OpenedShareAttachment?, budget: AttachmentBudget.CopyBudget
    ): Uri? {
        if (opened == null) return null
        val input = requireNotNull(opened.stream) { "附件无法读取，请重新分享" }
        require(opened.fileName.isNotBlank()) { "附件名称无效，请重新分享" }
        val itemDir = File(context.filesDir, "outbox_attachments/${opened.itemId}").apply { mkdirs() }
        val target = File(itemDir, opened.fileName)
        try {
            input.use { src ->
                java.io.FileOutputStream(target).use { out ->
                    budget.copy(src, out)
                    out.fd.sync()
                }
            }
            return Uri.fromFile(target)
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    /**
     * Submit an item (screenshot, album photos, PDF/Word/Office documents, shared article, voice idea, clipboard)
     * to Mac Inbox, or queue offline in SQLite if currently disconnected.
     */
    fun submitItemToMacOrQueue(
        intentTag: String,
        itemType: String,
        title: String?,
        textContent: String?,
        voiceTranscript: String?,
        fileUri: Uri?,
        sourceApp: String? = null,
        extraAudioUri: Uri? = null,
        additionalFileUris: List<Uri> = emptyList()
    ): String {
        val itemId = OmsFrameProtocol.generateUuidV7()
        val createdAtMs = System.currentTimeMillis()
        val allPrimaryUris = buildList {
            if (fileUri != null) add(fileUri)
            for (u in additionalFileUris) {
                if (u != fileUri) add(u)
            }
        }
        if (allPrimaryUris.isEmpty() && extraAudioUri == null) {
            val metaObj = JSONObject()
            if (!sourceApp.isNullOrBlank()) metaObj.put("sourceApp", sourceApp)
            val metaJson = if (metaObj.length() > 0) metaObj.toString() else null
            repo.upsertStreamItem(
                StreamItemModel(
                    itemId = itemId,
                    direction = "PHONE_TO_MAC",
                    itemType = itemType,
                    intentTag = intentTag,
                    title = title,
                    textContent = textContent,
                    voiceTranscript = voiceTranscript,
                    localFileUri = null,
                    macSavedPath = null,
                    syncStatus = "QUEUED",
                    metadataJson = metaJson,
                    createdAtMs = createdAtMs
                )
            )
            flushOutboxQueue()
            return itemId
        }

        val pendingItem = StreamItemModel(itemId, "PHONE_TO_MAC", itemType, intentTag, title,
            textContent, voiceTranscript, null, null, "PREPARING",
            JSONObject().put("failureReason", "附件保存中断，请从原应用重新分享").toString(), createdAtMs)
        repo.upsertStreamItem(pendingItem)
        val selectionError = AttachmentResolver.selectionError(context, allPrimaryUris + listOfNotNull(extraAudioUri))
        if (selectionError != null) {
            repo.upsertStreamItem(pendingItem.copy(syncStatus = "FAILED",
                metadataJson = JSONObject().put("failureReason", selectionError).toString()))
            mainHandler.post { android.widget.Toast.makeText(context, selectionError, android.widget.Toast.LENGTH_LONG).show() }
            return itemId
        }
        // Open transient ContentResolver streams synchronously so ShareReceiverActivity.finish()
        // cannot revoke FLAG_GRANT_READ_URI_PERMISSION before background I/O completes.
        val usedNames = mutableSetOf<String>()
        val openedFiles = allPrimaryUris.mapIndexed { idx, u ->
            checkNotNull(openShareAttachmentForPersistence(u, itemId, "att${idx + 1}", usedNames))
        }
        val openedAudio = openShareAttachmentForPersistence(extraAudioUri, itemId, "audio", usedNames)
        // Record owned destinations before copying. Interrupted/failed submissions can be removed
        // through the existing per-item delete action, including any partially copied file.
        val ownedUris = (openedFiles + listOfNotNull(openedAudio)).filter { it.fileName.isNotBlank() }
            .map { Uri.fromFile(File(context.filesDir, "outbox_attachments/$itemId/${it.fileName}")).toString() }
        val preparingMetadata = JSONObject(pendingItem.metadataJson ?: "{}")
            .put("extraFileUris", JSONArray(ownedUris.drop(1)))
        val preparingItem = pendingItem.copy(localFileUri = ownedUris.firstOrNull(), metadataJson = preparingMetadata.toString())
        repo.upsertStreamItem(preparingItem)
        scope.launch(Dispatchers.IO) {
            try {
                val budgetTracker = AttachmentBudget.CopyBudget()
                val durableFiles = openedFiles.map { checkNotNull(persistOpenedShareAttachment(it, budgetTracker)) }
                val durableAudio = persistOpenedShareAttachment(openedAudio, budgetTracker)
                val metaObj = JSONObject()
                if (durableFiles.size > 1) {
                    val extraArr = JSONArray()
                    for (i in 1 until durableFiles.size) {
                        extraArr.put(durableFiles[i].toString())
                    }
                    metaObj.put("extraFileUris", extraArr)
                }
                if (openedFiles.isNotEmpty()) {
                    val namesArr = JSONArray()
                    openedFiles.forEach { if (it.fileName.isNotBlank()) namesArr.put(it.fileName) }
                    if (namesArr.length() > 0) metaObj.put("attachmentNames", namesArr)
                }
                if (durableAudio != null) metaObj.put("extraAudioUri", durableAudio.toString())
                if (!sourceApp.isNullOrBlank()) metaObj.put("sourceApp", sourceApp)
                val metaJson = if (metaObj.length() > 0) metaObj.toString() else null
                val effectiveType = if (itemType == "MEDIA" || itemType == "FILE") {
                    AttachmentResolver.inferItemType(
                        openedFiles.map { it.mimeType },
                        hasVoiceNote = durableAudio != null
                    )
                } else {
                    itemType
                }
                val item = StreamItemModel(
                    itemId = itemId,
                    direction = "PHONE_TO_MAC",
                    itemType = effectiveType,
                    intentTag = intentTag,
                    title = title,
                    textContent = textContent,
                    voiceTranscript = voiceTranscript,
                    localFileUri = durableFiles.firstOrNull()?.toString(),
                    macSavedPath = null,
                    syncStatus = "QUEUED",
                    metadataJson = metaJson,
                    createdAtMs = createdAtMs
                )
                repo.upsertStreamItem(item)
                flushOutboxQueue()
            } catch (e: Exception) {
                val reason = "附件保存失败：${e.message ?: "无法读取"}；请从原应用重新分享"
                repo.upsertStreamItem(preparingItem.copy(syncStatus = "FAILED",
                    metadataJson = JSONObject(preparingItem.metadataJson ?: "{}").put("failureReason", reason).toString()))
                mainHandler.post { android.widget.Toast.makeText(context, reason, android.widget.Toast.LENGTH_LONG).show() }
            } finally {
                openedFiles.forEach { opened -> runCatching { opened.stream?.close() } }
                runCatching { openedAudio?.stream?.close() }
            }
        }
        return itemId
    }

    /**
     * Real-time dispatch of extracted SMS / App verification code (OTP) to Mac Notification & Clipboard.
     */
    fun submitOtpToMacOrQueue(match: OtpMatch): String {
        val itemId = OmsFrameProtocol.generateUuidV7()
        val senderPrefix = match.sender?.let { "【$it】" } ?: ""
        val metaJson = JSONObject().apply {
            put("otpCode", match.code)
            if (!match.sender.isNullOrBlank()) put("otpSender", match.sender)
            if (!match.sourceApp.isNullOrBlank()) put("sourceApp", match.sourceApp)
        }.toString()
        val item = StreamItemModel(
            itemId = itemId,
            direction = "PHONE_TO_MAC",
            itemType = "OTP",
            intentTag = "sms_otp",
            title = "🔑 ${senderPrefix}验证码: ${match.code}",
            textContent = match.fullText,
            voiceTranscript = null,
            localFileUri = null,
            macSavedPath = null,
            syncStatus = "QUEUED",
            metadataJson = metaJson,
            createdAtMs = match.capturedAtMs
        )
        repo.upsertStreamItem(item)
        flushOutboxQueue()
        return itemId
    }

    private fun readUriBytesAndMime(uriStr: String, itemIdSuffix: String, prefix: String = "mobile"): Triple<ByteArray, String, String>? {
        return try {
            val uri = Uri.parse(uriStr)
            if (!ShareInputPolicy.isSafeInternalOrContentUri(uri, listOf(context.filesDir, context.cacheDir))) {
                return null
            }
            val bytes = if (uri.scheme == "file" && uri.path != null) {
                File(uri.path!!).takeIf { it.exists() }?.inputStream()?.use { java.io.ByteArrayOutputStream().also { out -> AttachmentBudget.CopyBudget().copy(it, out) }.toByteArray() }
            } else {
                context.contentResolver.openInputStream(uri)?.use { java.io.ByteArrayOutputStream().also { out -> AttachmentBudget.CopyBudget().copy(it, out) }.toByteArray() }
            } ?: return null

            val meta = AttachmentResolver.describeUri(
                context,
                uri,
                fallbackPrefix = "${prefix}_${itemIdSuffix}"
            )
            Triple(bytes, meta.mimeType, meta.safeFileName)
        } catch (_: Throwable) {
            null
        }
    }

    fun retryQuotaRejectedItem(itemId: String) {
        val item = repo.getStreamItem(itemId) ?: return
        val metadata = runCatching { JSONObject(item.metadataJson ?: "{}") }.getOrNull() ?: return
        if (item.direction != "PHONE_TO_MAC" || item.syncStatus != "FAILED" || metadata.optString("failureCode") != "INBOX_QUOTA") return
        metadata.remove("failureCode")
        metadata.remove("failureReason")
        repo.upsertStreamItem(item.copy(syncStatus = "QUEUED", metadataJson = metadata.toString()))
        flushOutboxQueue()
    }

    fun flushOutboxQueue() {
        if (_activeTierFlow.value == "DISCONNECTED") return
        if (!isFlushingOutbox.compareAndSet(false, true)) return
        scope.launch {
            try {
                for (item in repo.listQueuedOutboxItems()) {
                    if (item.itemType == "OTP" && System.currentTimeMillis() - item.createdAtMs > OtpExtractor.OTP_MAX_AGE_MS) {
                        repo.updateSyncStatus(item.itemId, "EXPIRED")
                        continue
                    }
                    try {
                        val source = activeSource()
                        val attachments = JSONArray()
                        var totalAttachmentBytes = 0L
                        val originalSizes = mutableListOf<Long?>()
                        fun attach(uri: String?) {
                            if (uri.isNullOrBlank()) return
                            val (rawBytes, rawMime, rawName) = readUriBytesAndMime(uri, item.itemId.takeLast(8))
                                ?: throw IllegalArgumentException("附件无法读取或超过 30 MiB，请从原应用重新分享")
                            originalSizes.add(rawBytes.size.toLong())
                            AttachmentBudget.validateSizes(originalSizes)
                            val (bytes, mime, name) = if (source.tier == "BT_RFCOMM" && rawMime.startsWith("image/") && rawBytes.size > 100 * 1024) {
                                val compressed = ScopedStorageExecutor.compressToWebpIfPossible(
                                    rawBytes,
                                    maxDimensionPx = 960,
                                    quality = 68
                                )
                                if (compressed != null && compressed.size < rawBytes.size) {
                                    Triple(compressed, "image/webp", rawName.substringBeforeLast('.') + ".webp")
                                } else {
                                    Triple(rawBytes, rawMime, rawName)
                                }
                            } else {
                                Triple(rawBytes, rawMime, rawName)
                            }
                            totalAttachmentBytes += bytes.size
                            val streamId = streamBinaryBytesToMac(name, mime, bytes, item.intentTag, source)
                            attachments.put(JSONObject().put("streamId", streamId).put("fileName", name)
                                .put("mimeType", mime).put("totalBytes", bytes.size)
                                .put("sha256", OmsFrameProtocol.sha256Hex(bytes)))
                        }
                        val metadata = item.metadataJson?.takeIf { it.isNotBlank() }?.let { JSONObject(it) }
                        val attachmentUris = buildList {
                            item.localFileUri?.takeIf { it.isNotBlank() }?.let { add(it) }
                            val extras = metadata?.optJSONArray("extraFileUris")
                            if (extras != null) for (i in 0 until extras.length()) {
                                extras.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                            }
                            metadata?.optString("extraAudioUri")?.takeIf { it.isNotBlank() }?.let { add(it) }
                        }
                        AttachmentBudget.validateCount(attachmentUris.size)
                        AttachmentBudget.validateSizes(attachmentUris.map { AttachmentResolver.describeUri(context, Uri.parse(it)).sizeBytes })
                        attachmentUris.forEach { attach(it) }
                        val request = JSONObject().put("jsonrpc", "2.0")
                            .put("id", "inbox_${UUID.randomUUID()}").put("method", "inbox.submitItem")
                            .put("params", JSONObject().put("clientItemId", item.itemId).put("intentTag", item.intentTag)
                                .put("title", item.title).put("textContent", item.textContent)
                                .put("voiceTranscript", item.voiceTranscript).put("createdAtMs", item.createdAtMs)
                                .put("otpCode", metadata?.optString("otpCode")?.takeIf { it.isNotBlank() })
                                .put("otpSender", metadata?.optString("otpSender")?.takeIf { it.isNotBlank() })
                                .put("sourceApp", metadata?.optString("sourceApp")?.takeIf { it.isNotBlank() })
                                .put("attachments", attachments))
                        val rpcTimeoutMs = if (source.tier == "BT_RFCOMM") {
                            maxOf(90_000L, 30_000L + (totalAttachmentBytes / 8192L) * 1200L)
                        } else {
                            30_000L
                        }
                        val result = requestHost(request, source, rpcTimeoutMs)
                        if (DeliveryPolicy.committedInboxAck(result.optBoolean("ack"), result.optString("savedId"), item.itemId)) {
                            repo.updateSyncStatus(item.itemId, "DELIVERED", result.optString("macPath"))
                        }
                    } catch (e: IllegalArgumentException) {
                        val failedMeta = runCatching { JSONObject(item.metadataJson ?: "{}") }.getOrDefault(JSONObject())
                        failedMeta.put("failureReason", e.message ?: "附件无效，请重新分享")
                        repo.upsertStreamItem(item.copy(syncStatus = "FAILED", metadataJson = failedMeta.toString()))
                    } catch (e: Exception) {
                        if (DeliveryPolicy.isInboxQuotaError(e.message)) {
                            val failedMeta = runCatching { JSONObject(item.metadataJson ?: "{}") }.getOrDefault(JSONObject())
                            failedMeta.put("failureReason", e.message).put("failureCode", "INBOX_QUOTA")
                            repo.upsertStreamItem(item.copy(syncStatus = "FAILED", metadataJson = failedMeta.toString()))
                        }
                        // Network failures remain QUEUED; quota rejection is visible and explicitly retryable.
                    }
                }
            } finally {
                isFlushingOutbox.set(false)
            }
        }
    }

    fun sendApprovalDecision(itemId: String, approvalId: String, approved: Boolean,
                             selectedOption: String, userComment: String?) {
        if (!decidingApprovals.add(itemId)) return
        scope.launch {
            try {
                val item = repo.getStreamItem(itemId) ?: return@launch
                val metadata = JSONObject(item.metadataJson ?: "{}")
                val expires = metadata.optLong("expiresAtMs", 0)
                if (!DeliveryPolicy.approvalCanDispatch(expires, System.currentTimeMillis(), item.syncStatus)) {
                    if (item.syncStatus == "PENDING_APPROVAL" && expires <= System.currentTimeMillis()) repo.updateSyncStatus(itemId, "EXPIRED")
                    return@launch
                }
                val options = metadata.getJSONArray("options")
                val index = (0 until options.length()).firstOrNull { options.getString(it) == selectedOption }
                    ?: throw IllegalArgumentException("Unknown approval option")
                require(metadata.getString("approvalId") == approvalId && approved == (index == 0))
                val request = JSONObject().put("jsonrpc", "2.0").put("id", "apr_${UUID.randomUUID()}")
                    .put("method", "approval.respond").put("params", JSONObject()
                        .put("approvalId", approvalId).put("approved", approved).put("selectedOption", selectedOption)
                        .put("userComment", userComment).put("decidedAtMs", System.currentTimeMillis()))
                val result = requestHost(request, activeSource(), minOf(30_000L, expires - System.currentTimeMillis()).coerceAtLeast(1))
                check(result.optBoolean("ack")) { "Host did not acknowledge approval" }
                repo.updateSyncStatus(itemId, if (approved) "APPROVED" else "REJECTED")
            } catch (_: Exception) {
                // A retry is permitted only while this persisted approval remains live.
            } finally {
                decidingApprovals.remove(itemId)
            }
        }
    }

    fun sendEmergencyStopEvent(triggerReason: String) {
        scope.launch {
            val req = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", "gui.emergencyStop")
                put("params", JSONObject().apply {
                    put("trigger", triggerReason)
                    put("timestamp", System.currentTimeMillis())
                })
            }
            sendRawFrame(OmsFrameProtocol.encodeJsonRpc(req))
        }
    }

    @Volatile
    private var cachedApkInfoKey: String = ""
    @Volatile
    private var cachedApkInfo: Triple<String, Int, String>? = null

    fun getInstalledApkInfo(includeSha256: Boolean = true): Triple<String, Int, String> {
        return try {
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val verName = pkgInfo.versionName ?: "1.1.0"
            val verCode = pkgInfo.longVersionCode.toInt()
            if (!includeSha256) {
                val cached = cachedApkInfo
                return Triple(verName, verCode, cached?.third ?: "")
            }
            val srcFile = File(context.applicationInfo.sourceDir)
            val cacheKey = "${verName}:${verCode}:${srcFile.lastModified()}:${srcFile.length()}"
            val existing = cachedApkInfo
            if (existing != null && cachedApkInfoKey == cacheKey && existing.third.isNotEmpty()) {
                return existing
            }
            val sha = if (srcFile.exists()) {
                val digest = MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(65536)
                srcFile.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            } else {
                ""
            }
            val computed = Triple(verName, verCode, sha)
            cachedApkInfoKey = cacheKey
            cachedApkInfo = computed
            computed
        } catch (_: Throwable) {
            Triple("1.1.0", 2, "")
        }
    }

    private val otaInstaller = VerifiedOtaInstaller(context)
    private val _otaStateFlow = MutableStateFlow<OtaUiState>(OtaUiState.Idle)
    val otaStateFlow: StateFlow<OtaUiState> = _otaStateFlow
    private val awaitingInstallPermissionResume = AtomicBoolean(false)
    private val lastAutoOtaCheckElapsedMs = AtomicLong(0L)
    private val autoOtaJobRunning = AtomicBoolean(false)

    private fun isUnmeteredWifiOrLanConnected(): Boolean {
        if (_activeTierFlow.value == "LAN_WS") return true
        if (findNonVpnWifiNetwork() != null) return true
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = cm?.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } catch (_: Throwable) {
            false
        }
    }

    private fun toVerifiedMetadata(ota: OtaCheckResult): VerifiedOtaPolicy.Metadata {
        return VerifiedOtaPolicy.Metadata(
            ota.downloadUrl,
            ota.remoteSha256,
            ota.remoteSizeBytes,
            ota.remoteVersionName,
            ota.remoteVersionCode.toLong(),
            ota.integritySource
        )
    }

    private suspend fun prepareAndOptionallyLaunchOta(
        ota: OtaCheckResult,
        launchWhenReady: Boolean
    ): String {
        check(ota.ok && ota.hasUpdate) { ota.message.ifBlank { "当前没有可安装的更新" } }
        val metadata = toVerifiedMetadata(ota)
        val initialClient = if (ota.downloadUrl.startsWith("http://", ignoreCase = true)) {
            buildLanOkHttpClient()
        } else {
            baseHttpClient
        }
        _otaStateFlow.value = OtaUiState.Downloading(
            ota = ota,
            downloadedBytes = 0L,
            totalBytes = ota.remoteSizeBytes,
            percent = 0
        )
        val verifiedFile = try {
            otaInstaller.prepareVerifiedApk(
                metadata = metadata,
                client = initialClient,
                fallbackHttpsClient = baseHttpClient
            ) { dl, total ->
                val pct = if (total > 0L) ((dl * 100L) / total).toInt().coerceIn(0, 100) else 0
                _otaStateFlow.value = OtaUiState.Downloading(
                    ota = ota,
                    downloadedBytes = dl,
                    totalBytes = total,
                    percent = pct
                )
            }
        } catch (e: Exception) {
            val errMsg = e.message ?: "下载或校验失败"
            _otaStateFlow.value = OtaUiState.Error(errMsg, ota)
            throw e
        }

        if (!launchWhenReady) {
            _otaStateFlow.value = OtaUiState.ReadyToInstall(ota, verifiedFile)
            return "新版本 v${ota.remoteVersionName} 已后台下载并验签就绪"
        }

        return if (otaInstaller.launchSystemInstaller(verifiedFile)) {
            awaitingInstallPermissionResume.set(false)
            _otaStateFlow.value = OtaUiState.InstallerLaunched(ota, verifiedFile)
            "APK 校验通过，已打开系统安装器，请确认安装"
        } else {
            awaitingInstallPermissionResume.set(true)
            _otaStateFlow.value = OtaUiState.NeedsInstallPermission(ota, verifiedFile)
            runCatching { otaInstaller.openUnknownSourcesSettings() }
            "安装包已验签就绪，请开启「安装未知应用」权限（返回后将自动安装）"
        }
    }

    private suspend fun installVerifiedOta(ota: OtaCheckResult) {
        val msg = prepareAndOptionallyLaunchOta(ota, launchWhenReady = true)
        check(_otaStateFlow.value is OtaUiState.InstallerLaunched) { msg }
    }

    fun installOtaUpdate(ota: OtaCheckResult, onResult: (String) -> Unit) {
        scope.launch {
            val message = try {
                prepareAndOptionallyLaunchOta(ota, launchWhenReady = true)
            } catch (e: Exception) { "更新未安装：${e.message ?: "下载或校验失败"}" }
            mainHandler.post { onResult(message) }
        }
    }

    fun startBackgroundPreDownload(ota: OtaCheckResult) {
        scope.launch {
            runCatching { prepareAndOptionallyLaunchOta(ota, launchWhenReady = false) }
        }
    }

    fun installOrResumeReadyOta(onResult: ((String) -> Unit)? = null) {
        scope.launch {
            val cur = _otaStateFlow.value
            val pair: Pair<OtaCheckResult, File>? = when (cur) {
                is OtaUiState.ReadyToInstall -> cur.ota to cur.apkFile
                is OtaUiState.NeedsInstallPermission -> cur.ota to cur.apkFile
                is OtaUiState.InstallerLaunched -> cur.ota to cur.apkFile
                else -> null
            }
            if (pair == null) {
                val fallbackOta = when (cur) {
                    is OtaUiState.UpdateAvailable -> cur.ota
                    is OtaUiState.Error -> cur.ota
                    else -> null
                }
                if (fallbackOta != null) {
                    val msg = try {
                        prepareAndOptionallyLaunchOta(fallbackOta, launchWhenReady = true)
                    } catch (e: Exception) {
                        "更新未安装：${e.message ?: "下载或校验失败"}"
                    }
                    if (onResult != null) mainHandler.post { onResult(msg) }
                }
                return@launch
            }

            val (ota, file) = pair
            if (!file.exists()) {
                val msg = try {
                    prepareAndOptionallyLaunchOta(ota, launchWhenReady = true)
                } catch (e: Exception) {
                    "更新未安装：${e.message ?: "下载或校验失败"}"
                }
                if (onResult != null) mainHandler.post { onResult(msg) }
                return@launch
            }

            val msg = if (otaInstaller.launchSystemInstaller(file)) {
                awaitingInstallPermissionResume.set(false)
                _otaStateFlow.value = OtaUiState.InstallerLaunched(ota, file)
                "🚀 已拉起系统安装器，请点击确认完成升级"
            } else {
                awaitingInstallPermissionResume.set(true)
                _otaStateFlow.value = OtaUiState.NeedsInstallPermission(ota, file)
                runCatching { otaInstaller.openUnknownSourcesSettings() }
                "🔓 请允许「安装未知应用」权限，返回本应用后将自动弹出安装"
            }
            if (onResult != null) mainHandler.post { onResult(msg) }
        }
    }

    fun openInstallPermissionAndAwaitResume() {
        val cur = _otaStateFlow.value
        if (cur is OtaUiState.ReadyToInstall) {
            _otaStateFlow.value = OtaUiState.NeedsInstallPermission(cur.ota, cur.apkFile)
        }
        awaitingInstallPermissionResume.set(true)
        runCatching { otaInstaller.openUnknownSourcesSettings() }
    }

    fun onAppForegroundResume() {
        scope.launch {
            val (_, _, localSha) = getInstalledApkInfo(includeSha256 = true)
            val cur = _otaStateFlow.value
            val currentTargetOta = when (cur) {
                is OtaUiState.ReadyToInstall -> cur.ota
                is OtaUiState.NeedsInstallPermission -> cur.ota
                is OtaUiState.InstallerLaunched -> cur.ota
                is OtaUiState.UpdateAvailable -> cur.ota
                is OtaUiState.Error -> cur.ota
                else -> null
            }
            if (currentTargetOta != null && localSha.isNotEmpty() && localSha.equals(currentTargetOta.remoteSha256, ignoreCase = true)) {
                awaitingInstallPermissionResume.set(false)
                _otaStateFlow.value = OtaUiState.Idle
                return@launch
            }
            if (awaitingInstallPermissionResume.get() && otaInstaller.canRequestPackageInstalls()) {
                val readyFile = when (cur) {
                    is OtaUiState.NeedsInstallPermission -> cur.apkFile
                    is OtaUiState.ReadyToInstall -> cur.apkFile
                    else -> null
                }
                if (readyFile != null && readyFile.exists() && currentTargetOta != null) {
                    awaitingInstallPermissionResume.set(false)
                    if (otaInstaller.launchSystemInstaller(readyFile)) {
                        _otaStateFlow.value = OtaUiState.InstallerLaunched(currentTargetOta, readyFile)
                    }
                    return@launch
                }
            }
            triggerAutoOtaCheckIfEligible(reason = "foreground_resume")
        }
    }

    fun triggerAutoOtaCheckIfEligible(reason: String = "auto") {
        val nowElapsed = SystemClock.elapsedRealtime()
        val connectedToMac = _activeTierFlow.value != "DISCONNECTED"
        val minIntervalMs = if (reason == "session_connected") 60_000L else if (connectedToMac) 5 * 60_000L else 30 * 60_000L
        val prev = lastAutoOtaCheckElapsedMs.get()
        if (prev > 0L && nowElapsed - prev < minIntervalMs) return
        if (_otaStateFlow.value is OtaUiState.Downloading) return
        if (!autoOtaJobRunning.compareAndSet(false, true)) return
        lastAutoOtaCheckElapsedMs.set(nowElapsed)

        checkOtaUpdate { res ->
            autoOtaJobRunning.set(false)
            if (!res.ok || !res.hasUpdate) {
                if (_otaStateFlow.value !is OtaUiState.Downloading) {
                    _otaStateFlow.value = OtaUiState.Idle
                }
                return@checkOtaUpdate
            }
            val cur = _otaStateFlow.value
            val alreadyReadySame = when (cur) {
                is OtaUiState.ReadyToInstall -> cur.ota.remoteSha256.equals(res.remoteSha256, true) && cur.apkFile.exists()
                is OtaUiState.InstallerLaunched -> cur.ota.remoteSha256.equals(res.remoteSha256, true) && cur.apkFile.exists()
                is OtaUiState.NeedsInstallPermission -> cur.ota.remoteSha256.equals(res.remoteSha256, true) && cur.apkFile.exists()
                else -> false
            }
            if (alreadyReadySame) return@checkOtaUpdate

            if (repo.isAutoDownloadOtaEnabled() && isUnmeteredWifiOrLanConnected()) {
                startBackgroundPreDownload(res)
            } else {
                _otaStateFlow.value = OtaUiState.UpdateAvailable(res)
            }
        }
    }

    fun checkOtaUpdate(onResult: (OtaCheckResult) -> Unit) {
        scope.launch {
            val (localVerName, localVerCode, localSha) = getInstalledApkInfo()
            val paired = repo.getPairedMac()
            val port = paired?.lanPort ?: 19527

            // 1. Priority 1: Query Mac directly over the active encrypted channel (works over LAN_WS, BT_RFCOMM, and WAN_WEBRTC!)
            val activeSrc = runCatching { activeSource() }.getOrNull()
            if (activeSrc != null && paired != null) {
                try {
                    val rpcReq = JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("id", "ota_${UUID.randomUUID()}")
                        .put("method", "apk.getVersion")
                        .put("params", JSONObject())
                    val rpcRes = requestHost(rpcReq, activeSrc, 8_000L)
                    if (rpcRes.has("versionName") || rpcRes.has("available")) {
                        val available = rpcRes.optBoolean("available", false)
                        val remoteVerCode = rpcRes.optInt("versionCode", 0)
                        val remoteVerName = rpcRes.optString("versionName", localVerName)
                        val remoteSha = rpcRes.optString("sha256", "")
                        val remoteSize = rpcRes.optLong("sizeBytes", 0L)
                        val remoteUpdatedIso = rpcRes.optString("updatedAtIso", "")
                        val ghUrl = rpcRes.optString("githubDownloadUrl").takeIf { it.isNotBlank() }
                            ?: "https://github.com/dqrcsc/one-more-step/releases/download/v$remoteVerName/OneMoreStep-v$remoteVerName.apk"

                        if (available && remoteSha.isNotBlank()) {
                            val reachableLanIp = if (activeSrc.tier == "LAN_WS") {
                                paired.lanIps.firstOrNull {
                                    probeMacHttpInfo(it, port, 1200, checkTcpFirst = false)?.macId == paired!!.macId
                                } ?: paired.lanIps.firstOrNull()
                            } else {
                                paired.lanIps.firstOrNull {
                                    probeMacHttpInfo(it, port, 900, checkTcpFirst = false)?.macId == paired!!.macId
                                }
                            }
                            val resolvedDownloadUrl = if (!reachableLanIp.isNullOrBlank()) {
                                "http://$reachableLanIp:$port/OneMoreStep.apk?t=${System.currentTimeMillis()}"
                            } else {
                                ghUrl
                            }
                            VerifiedOtaPolicy.Metadata(resolvedDownloadUrl, remoteSha, remoteSize, remoteVerName,
                                remoteVerCode.toLong(), VerifiedOtaPolicy.Source.AUTHENTICATED_RPC).validate()
                            val hasUpdate = OtaVersionPolicy.hasMacHostUpdate(
                                localVersionName = localVerName,
                                localVersionCode = localVerCode,
                                localSha256 = localSha,
                                remoteVersionName = remoteVerName,
                                remoteVersionCode = remoteVerCode,
                                remoteSha256 = remoteSha
                            )

                            mainHandler.post {
                                onResult(
                                    OtaCheckResult(
                                        ok = true,
                                        hasUpdate = hasUpdate,
                                        localVersionName = localVerName,
                                        localVersionCode = localVerCode,
                                        localSha256 = localSha,
                                        remoteVersionName = remoteVerName,
                                        remoteVersionCode = remoteVerCode,
                                        remoteSha256 = remoteSha,
                                        integritySource = VerifiedOtaPolicy.Source.AUTHENTICATED_RPC,
                                        remoteSizeBytes = remoteSize,
                                        remoteUpdatedAtIso = remoteUpdatedIso,
                                        downloadUrl = resolvedDownloadUrl,
                                        message = if (hasUpdate) {
                                            "发现新版本 v$remoteVerName"
                                        } else {
                                            "✅ 当前已是最新版本 (v$localVerName)"
                                        }
                                    )
                                )
                            }
                            return@launch
                        }
                    }
                } catch (_: Throwable) {
                    // Only the HTTPS GitHub API is a trusted fallback for update metadata.
                }
            }

            // Plain HTTP /apk/version cannot authenticate a digest, even on a paired LAN.
            // 3. Priority 3: Fallback to GitHub Releases API when LAN HTTP is isolated/unreachable
            try {
                val ghClient = baseHttpClient.newBuilder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(6, TimeUnit.SECONDS)
                    .followRedirects(false)
                    .build()
                val ghReq = Request.Builder()
                    .url("https://api.github.com/repos/dqrcsc/one-more-step/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .get()
                    .build()
                ghClient.newCall(ghReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string() ?: "{}")
                        val tagName = json.optString("tag_name", "").removePrefix("v").trim()
                        if (tagName.isNotBlank()) {
                            val assets = json.optJSONArray("assets")
                            var assetUrl = "https://github.com/dqrcsc/one-more-step/releases/download/v$tagName/OneMoreStep-v$tagName.apk"
                            var assetSize = 0L
                            var assetUpdatedIso = json.optString("published_at", "")
                            var assetSha256 = ""
                            if (assets != null && assets.length() > 0) {
                                val apkAsset = (0 until assets.length())
                                    .map { assets.getJSONObject(it) }
                                    .firstOrNull { it.optString("name", "").endsWith(".apk", ignoreCase = true) }
                                    ?: throw IllegalStateException("GitHub Release 没有 APK")
                                assetUrl = apkAsset.optString("browser_download_url", assetUrl)
                                assetSize = apkAsset.optLong("size", 0L)
                                assetUpdatedIso = apkAsset.optString("updated_at", assetUpdatedIso)
                                val digest = apkAsset.optString("digest", "")
                                if (digest.startsWith("sha256:", ignoreCase = true)) {
                                    assetSha256 = digest.substringAfter(':').trim()
                                }
                            }
                            VerifiedOtaPolicy.Metadata(assetUrl, assetSha256, assetSize, tagName, 0,
                                VerifiedOtaPolicy.Source.GITHUB_HTTPS).validate()
                            val hasUpdate = OtaVersionPolicy.hasGithubReleaseUpdate(
                                localVersionName = localVerName,
                                releaseTagName = tagName,
                                localSha256 = localSha,
                                assetSha256 = assetSha256
                            )
                            mainHandler.post {
                                onResult(
                                    OtaCheckResult(
                                        ok = true,
                                        hasUpdate = hasUpdate,
                                        localVersionName = localVerName,
                                        localVersionCode = localVerCode,
                                        localSha256 = localSha,
                                        remoteVersionName = tagName,
                                        remoteVersionCode = 0,
                                        remoteSha256 = assetSha256,
                                        integritySource = VerifiedOtaPolicy.Source.GITHUB_HTTPS,
                                        remoteSizeBytes = assetSize,
                                        remoteUpdatedAtIso = assetUpdatedIso,
                                        downloadUrl = assetUrl,
                                        message = if (hasUpdate) {
                                            "发现新版本 v$tagName (GitHub Release)"
                                        } else {
                                            "✅ 当前已是最新版本 (v$localVerName)"
                                        }
                                    )
                                )
                            }
                            return@launch
                        }
                    }
                }
            } catch (_: Throwable) {
            }

            mainHandler.post {
                onResult(
                    OtaCheckResult(
                        ok = false,
                        hasUpdate = false,
                        localVersionName = localVerName,
                        localVersionCode = localVerCode,
                        localSha256 = localSha,
                        message = "⚠️ 暂无法通过蓝牙/局域网/GitHub获取更新信息，请稍后重试"
                    )
                )
            }
        }
    }

    private fun streamBinaryBytesToMac(
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        intentTag: String? = null,
        source: FrameSource = activeSource()
    ): String {
        require(bytes.size <= DeliveryPolicy.MAX_BYTES) { "File exceeds 64 MiB" }
        val streamId = OmsFrameProtocol.generateUuidV7()
        val chunkSize = if (source.tier == "BT_RFCOMM") 8192 else 65536
        val totalChunks = ((bytes.size + chunkSize - 1) / chunkSize).coerceAtLeast(1)
        val sha256 = OmsFrameProtocol.sha256Hex(bytes)

        val startHeader = JSONObject().apply {
            put("streamId", streamId)
            put("fileName", fileName)
            put("mimeType", mimeType)
            put("totalBytes", bytes.size)
            put("chunkSize", chunkSize)
            put("totalChunks", totalChunks)
            put("sha256", sha256)
            if (intentTag != null) put("intentTag", intentTag)
        }
        val needWifiLowLatency = source.tier == "LAN_WS" || source.tier == "WAN_WEBRTC"
        if (needWifiLowLatency) {
            OmsCoreForegroundService.setLowLatencyWifiNeeded("outgoing_stream", true)
        }
        try {
            check(sendOnSource(OmsFrameProtocol.encodeStreamStart(startHeader), source)) { "Stream start send failed" }

            for (i in 0 until totalChunks) {
                val ws = source.socket
                if (ws != null) {
                    var waitedMs = 0L
                    while (isSourceCurrent(source) && ws.queueSize() > 4L * 1024L * 1024L && waitedMs < 15_000L) {
                        Thread.sleep(25L)
                        waitedMs += 25L
                    }
                }
                val start = i * chunkSize
                val end = (start + chunkSize).coerceAtMost(bytes.size)
                val slice = bytes.copyOfRange(start, end)
                check(sendOnSource(OmsFrameProtocol.encodeStreamChunk(streamId, i, slice), source)) { "Stream chunk send failed" }
            }
            return streamId
        } finally {
            if (needWifiLowLatency) {
                OmsCoreForegroundService.setLowLatencyWifiNeeded("outgoing_stream", false)
            }
        }
    }

    private val incomingTransferLock = Any()

    private fun streamAck(streamId: String, status: String, source: FrameSource,
                          chunks: Int = 0, error: String? = null) {
        val ack = JSONObject().put("streamId", streamId).put("status", status)
            .put("nextExpectedChunkIndex", chunks)
        if (error != null) ack.put("errorMessage", error)
        sendOnSource(OmsFrameProtocol.encodeFrame(OmsFrameProtocol.TYPE_STREAM_ACK_RESUME,
            ack.toString().toByteArray(Charsets.UTF_8)), source)
    }

    private fun readPreviewFromFile(file: File, maxBytes: Int = 256 * 1024): String {
        val len = minOf(file.length(), maxBytes.toLong()).toInt()
        if (len <= 0) return ""
        val buf = ByteArray(len)
        RandomAccessFile(file, "r").use { it.readFully(buf) }
        return String(buf, Charsets.UTF_8)
    }

    private fun receiveStreamStart(payload: ByteArray, source: FrameSource) = synchronized(incomingTransferLock) {
        if (!sourceAuthenticated(source)) return@synchronized
        var streamId = ""
        try {
            val header = JSONObject(String(payload, Charsets.UTF_8))
            streamId = header.getString("streamId").lowercase()
            fun integer(name: String): Long {
                val raw = header.get(name)
                require(raw is Number && raw.toDouble().isFinite() && raw.toDouble() == raw.toLong().toDouble()) { "Invalid $name" }
                return raw.toLong()
            }
            val chunkSize = integer("chunkSize")
            val chunks = integer("totalChunks")
            require(chunkSize in 1..DeliveryPolicy.MAX_CHUNK_SIZE.toLong() && chunks in 1..Int.MAX_VALUE.toLong())
            val validated = DeliveryPolicy.Header(streamId, integer("totalBytes"), chunkSize.toInt(), chunks.toInt(), header.getString("sha256"))
            validated.validate()
            val existingItem = repo.getStreamItem(streamId)
            if (existingItem != null) {
                val metadata = JSONObject(existingItem.metadataJson ?: "{}")
                require(existingItem.direction == "MAC_TO_PHONE" && existingItem.syncStatus == "DELIVERED" &&
                    metadata.optString("sha256").equals(validated.sha256, true) && metadata.optLong("totalBytes", -1) == validated.totalBytes) { "Stream ID conflicts with committed item" }
                val file = File(Uri.parse(existingItem.localFileUri ?: "").path ?: "")
                require(file.isFile && file.length() == validated.totalBytes &&
                    DeliveryPolicy.sha256FileHex(file).equals(validated.sha256, true)) { "Committed file unavailable" }
                streamAck(streamId, "COMPLETED", source, validated.totalChunks)
                return@synchronized
            }
            val existing = incomingTransfers[streamId]
            require(existing == null || existing.source == source) { "Stream belongs to another connection" }
            require(existing == null || existing.validated == validated) { "Stream header changed" }
            require(existing != null || incomingTransfers.size < DeliveryPolicy.MAX_TRANSFERS) { "Too many concurrent streams" }
            if (existing == null) {
                val partFile = File(context.filesDir, "$streamId.part")
                if (partFile.exists()) partFile.delete()
                incomingTransfers[streamId] = IncomingPushTransfer(header, source, validated, partFile)
                OmsCoreForegroundService.setLowLatencyWifiNeeded("incoming_stream", incomingTransfers.isNotEmpty())
            }
        } catch (e: Exception) {
            if (incomingTransfers[streamId]?.source == source) {
                incomingTransfers.remove(streamId)?.cleanup()
                OmsCoreForegroundService.setLowLatencyWifiNeeded("incoming_stream", incomingTransfers.isNotEmpty())
            }
            streamAck(streamId, "ERROR", source, error = e.message ?: "Invalid stream")
        }
    }

    private fun receiveStreamChunk(payload: ByteArray, source: FrameSource) = synchronized(incomingTransferLock) {
        if (!sourceAuthenticated(source) || payload.size < OmsFrameProtocol.STREAM_CHUNK_PREFIX_BYTES) return@synchronized
        val streamId = OmsFrameProtocol.bytesToUuid(payload.copyOfRange(0, 16))
        val transfer = incomingTransfers[streamId]
        if (transfer == null) {
            if (repo.getStreamItem(streamId)?.syncStatus != "DELIVERED") {
                streamAck(streamId, "ERROR", source, error = "Missing stream start")
            }
            return@synchronized
        }
        if (transfer.source != source) {
            streamAck(streamId, "ERROR", source, error = "Stream belongs to another connection")
            return@synchronized
        }
        try {
            val index = ByteBuffer.wrap(payload, 16, 4).order(ByteOrder.BIG_ENDIAN).int
            val bytes = payload.copyOfRange(OmsFrameProtocol.STREAM_CHUNK_PREFIX_BYTES, payload.size)
            val completed = transfer.validated.writeChunkToPartFile(transfer.partFile, transfer.receivedChunks, index, bytes)
            if (!completed) return@synchronized
            transfer.validated.verifyAndSyncPartFile(transfer.partFile)
            check(sourceAuthenticated(source)) { "Connection revoked" }
            val fileName = transfer.header.optString("fileName", "pushed_doc.md")
                .substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(120).ifBlank { "file.bin" }
            val savedFile = File(context.filesDir, "${streamId}_$fileName")
            if (savedFile.exists()) savedFile.delete()
            try {
                check(transfer.partFile.renameTo(savedFile)) { "Unable to commit stream file" }
                val enableTts = transfer.header.optBoolean("enableCommuteTts", false)
                val intentTag = transfer.header.optString("intentTag", "default_inbox").ifBlank { "default_inbox" }
                val preview = if (fileName.endsWith(".md") || fileName.endsWith(".txt")) {
                    readPreviewFromFile(savedFile, 256 * 1024)
                } else "收到 Mac 推送文件：$fileName (${transfer.validated.totalBytes / 1024} KB)"
                repo.upsertStreamItem(StreamItemModel(streamId, "MAC_TO_PHONE", if (enableTts) "COMMUTE_READER" else "FILE",
                    intentTag, transfer.header.optString("title", fileName), preview, null,
                    Uri.fromFile(savedFile).toString(), null, "DELIVERED",
                    JSONObject().put("enableCommuteTts", enableTts).put("sha256", transfer.validated.sha256)
                        .put("totalBytes", transfer.validated.totalBytes).toString(), System.currentTimeMillis()))
            } finally {
                transfer.cleanup()
            }
            incomingTransfers.remove(streamId)
            OmsCoreForegroundService.setLowLatencyWifiNeeded("incoming_stream", incomingTransfers.isNotEmpty())
            streamAck(streamId, "COMPLETED", source, transfer.validated.totalChunks)
        } catch (e: Exception) {
            incomingTransfers.remove(streamId)?.cleanup()
            OmsCoreForegroundService.setLowLatencyWifiNeeded("incoming_stream", incomingTransfers.isNotEmpty())
            streamAck(streamId, "ERROR", source, error = e.message ?: "Stream commit failed")
        }
    }

    private fun handleIncomingRawBytes(tier: String, decoder: OmsFrameStreamDecoder, raw: ByteArray,
                                       sourceWebSocket: WebSocket? = null, sourceBleGatt: BluetoothGatt? = null) {
        val source = FrameSource(tier, sourceWebSocket, sourceBleGatt)
        val link = secureLinks[source] ?: run { closeSecureSource(source, "Unknown secure connection"); return }
        synchronized(link.lock) {
            try {
                check(isSourceCurrent(source) && link.phase != SecurePhase.CLOSED)
                if (link.phase != SecurePhase.ACTIVE) {
                    link.handshakeBytesReceived += raw.size
                    check(link.handshakeBytesReceived <= 64 * 1024) { "Handshake byte budget exceeded" }
                }
                for (frame in decoder.push(raw)) receiveSecureFrame(source, link, frame)
            } catch (_: Exception) {
                decoder.reset()
                closeSecureSource(source, "Secure protocol validation failed")
            }
        }
    }

    private fun receiveSecureFrame(source: FrameSource, link: SecureLink, frame: OmsFrameProtocol.DecodedFrame) {
        check(isSourceCurrent(source) && secureLinks[source] === link && link.phase != SecurePhase.CLOSED)
        check(frame.flags == 0)
        if (link.phase != SecurePhase.ACTIVE) {
            check(SystemClock.elapsedRealtime() < link.expiresAt)
            link.pairing?.let { check(it.generation == pairGeneration.get() && System.currentTimeMillis() < it.expiresAtMs) }
        }
        if (frame.frameType == 0x7e) {
            check(link.phase == SecurePhase.FINISH || link.phase == SecurePhase.ACTIVE)
            val inner = requireNotNull(link.session).open(OmsFrameProtocol.encodeFrame(frame.frameType, frame.payload, frame.flags))
            val decoded = OmsFrameStreamDecoder().push(inner).single()
            if (link.phase == SecurePhase.FINISH) {
                check(inner.size <= 32 * 1024 && decoded.frameType == OmsFrameProtocol.TYPE_CONTROL_JSON_RPC)
                val message = JSONObject(String(decoded.payload, Charsets.UTF_8))
                check(message.optString("id") == "secure_finish" && !message.has("method") && !message.has("error"))
                val result = message.getJSONObject("result")
                check(result.opt("ok") == true && result.opt("pairingVersion") == 2 && result.opt("macId") == link.config.macId)
                check(!result.has("wanRoomId") || (result.opt("wanRoomId") is String && result.getString("wanRoomId").length <= 256))
                val revoked = synchronized(connectionLock) {
                    check(isSourceCurrent(source) && secureLinks[source] === link)
                    if (link.mode == "pair") {
                        val attempt = requireNotNull(link.pairing)
                        check(attempt.generation == pairGeneration.get() && System.currentTimeMillis() < attempt.expiresAtMs)
                        val config = link.config.copy(pairSecretHex = link.newPairSecret, pairingVersion = 2,
                            wanRoomId = result.optString("wanRoomId", ""))
                        repo.savePairedMac(config)
                        activeBlePairingAttempt = null
                        lanGeneration.incrementAndGet()
                        wanGeneration.incrementAndGet()
                        isConnectingLan.set(false)
                        if (source.tier == "LAN_WS") {
                            lanWebSocket = source.socket
                        }
                        pairingWebSocket = null
                    } else {
                        val stored = repo.getPairedMac()
                        check(stored != null && isV2Pairing(stored) && stored.macId == link.config.macId && stored.pairSecretHex == link.newPairSecret)
                    }
                    link.phase = SecurePhase.ACTIVE
                    if (link.mode == "pair") secureLinks.keys.filter { it != source } else emptyList()
                }
                for (old in revoked) closeSecureSource(old, "Re-pair completed")
                recomputeFallbackTier()
                traceConnection(ConnectionDiagnostics.Event.CONNECTED, source.tier)
                reportPairing(link.pairing, true)
                flushOutboxQueue()
                triggerAutoOtaCheckIfEligible(reason = "session_connected")
            } else {
                check(sourceAuthenticated(source))
                handleDecodedFrame(source.tier, decoded, source.socket, source.gatt)
            }
            return
        }
        check(link.phase == SecurePhase.HELLO || link.phase == SecurePhase.START)
        check(frame.frameType == OmsFrameProtocol.TYPE_CONTROL_JSON_RPC && frame.payload.size <= 8192)
        val message = JSONObject(String(frame.payload, Charsets.UTF_8))
        check(message.optString("jsonrpc") == "2.0")
        if (link.phase == SecurePhase.HELLO) {
            check(message.optString("method") == "secure.hello" && !message.has("id"))
            val params = message.getJSONObject("params")
            check(params.opt("v") == 2 && listOf("macId", "nonceMacHex", "publicKeyHex").all { params.opt(it) is String })
            val hello = SecureChannel.Hello(params.getInt("v"), params.getString("macId"),
                params.getString("nonceMacHex"), params.getString("publicKeyHex"))
            check(hello.macId == link.config.macId)
            val ephemeral = SecureChannel.generateEphemeral()
            val start = SecureChannel.phoneStart(hello, link.mode, repo.getOrCreatePhoneId(), link.pskHex, ephemeral)
            val established = SecureChannel.establish("phone", hello, start, link.pskHex, ephemeral.privateKeyHex)
            link.session = established.session
            link.expectedMacProof = established.macProofHex
            link.newPairSecret = if (link.mode == "pair") established.pairSecretHex else link.pskHex
            link.pskHex = ""
            link.pairing?.token = ""
            link.phase = SecurePhase.START
            val request = JSONObject().put("jsonrpc", "2.0").put("id", "secure_start").put("method", "secure.start")
                .put("params", JSONObject().put("v", start.v).put("mode", start.mode).put("phoneId", start.phoneId)
                    .put("noncePhoneHex", start.noncePhoneHex).put("publicKeyHex", start.publicKeyHex).put("proofHex", start.proofHex))
            check(writeTransport(OmsFrameProtocol.encodeJsonRpc(request), source))
        } else {
            check(message.optString("id") == "secure_start" && !message.has("method") && !message.has("error"))
            val result = message.getJSONObject("result")
            check(result.opt("v") == 2 && result.opt("proofHex") is String && SecureChannel.equalHex(link.expectedMacProof, result.getString("proofHex")))
            link.expectedMacProof = ""
            link.phase = SecurePhase.FINISH
            val finish = JSONObject().put("jsonrpc", "2.0").put("id", "secure_finish").put("method", "secure.finish")
                .put("params", JSONObject().put("phoneName", Build.MODEL.take(256))
                    .put("phoneModel", "${Build.MANUFACTURER} ${Build.MODEL}".take(256)).put("deviceSnapshot", collectDeviceSnapshot().apply {
                        // Optional structured diagnostics belong to authenticated status RPC,
                        // not the backwards-compatible connection handshake.
                        remove("airpodsAutoSwitchDiagnostics")
                    }))
            val finishBytes = OmsFrameProtocol.encodeJsonRpc(finish)
            check(finishBytes.size <= 32 * 1024) { "Finish metadata exceeds limit" }
            check(writeTransport(requireNotNull(link.session).seal(finishBytes), source))
        }
    }

    private fun handleDecodedFrame(
        tier: String,
        frame: OmsFrameProtocol.DecodedFrame,
        sourceWebSocket: WebSocket? = null,
        sourceBleGatt: BluetoothGatt? = null
    ) {
        val source = FrameSource(tier, sourceWebSocket, sourceBleGatt)
        when (frame.frameType) {
            OmsFrameProtocol.TYPE_CONTROL_JSON_RPC -> {
                val msg = JSONObject(String(frame.payload, Charsets.UTF_8))
                if (msg.has("method")) {
                    handleIncomingRpcRequest(tier, msg, sourceWebSocket, sourceBleGatt)
                } else {
                    if (!sourceAuthenticated(source)) return
                    val pending = pendingReplies[msg.optString("id")] ?: return
                    if (pending.source == source) pending.result.complete(msg)
                }
            }
            OmsFrameProtocol.TYPE_STREAM_START -> receiveStreamStart(frame.payload, source)
            OmsFrameProtocol.TYPE_STREAM_CHUNK -> receiveStreamChunk(frame.payload, source)
            OmsFrameProtocol.TYPE_PING -> {
                if (!sourceAuthenticated(source)) return
                val sentTs = ByteBuffer.wrap(frame.payload).order(ByteOrder.BIG_ENDIAN).long
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val pongBytes = OmsFrameProtocol.encodePong(sentTs, pct, bm.isCharging)
                sendOnSource(pongBytes, source)
            }
        }
    }

    private fun handleIncomingRpcRequest(
        tier: String,
        req: JSONObject,
        sourceWebSocket: WebSocket? = null,
        sourceBleGatt: BluetoothGatt? = null
    ) {
        val reqId = req.optString("id", "")
        val method = req.optString("method", "")
        val params = req.optJSONObject("params") ?: JSONObject()
        val source = FrameSource(tier, sourceWebSocket, sourceBleGatt)
        val linkAtReceipt = secureLinks[source]
        val sessionStillValid = { linkAtReceipt != null && secureLinks[source] === linkAtReceipt && sourceAuthenticated(source) }

        scope.launch {
            try {
                if (!sessionStillValid()) return@launch
                val result: JSONObject = when (method) {
                    "sys.getStatus" -> collectDeviceSnapshot()
                    "storage.listRecentMedia" -> ScopedStorageExecutor.listRecentMedia(context, repo, params)
                    "storage.listDir" -> ScopedStorageExecutor.listDir(context, repo, params)
                    "storage.pullFile" -> {
                        val src = params.getString("sourceUriOrPath")
                        val isBle = source.tier == "BT_RFCOMM"
                        val compress = params.optBoolean("compressImage", isBle)
                        val pulled = ScopedStorageExecutor.readAuthorizedFileBytes(
                            context,
                            repo,
                            src,
                            compressImage = compress,
                            compactForBle = isBle
                        )
                        val streamId = streamBinaryBytesToMac(pulled.fileName, pulled.mimeType, pulled.bytes, source = source)
                        JSONObject().apply {
                            put("streamId", streamId)
                            put("fileName", pulled.fileName)
                            put("totalBytes", pulled.bytes.size)
                            put("sha256", OmsFrameProtocol.sha256Hex(pulled.bytes))
                        }
                    }
                    "clipboard.get" -> {
                        val directDef = CompletableDeferred<String>()
                        mainHandler.post {
                            try {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val txt = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
                                directDef.complete(txt)
                            } catch (_: Throwable) {
                                directDef.complete("")
                            }
                        }
                        var text = withTimeoutOrNull(800L) { directDef.await() } ?: ""
                        // Android 10+ background restriction fallback: launch 1x1 transparent focus catcher
                        if (text.isEmpty()) {
                            val focusDef = CompletableDeferred<String>()
                            ClipboardCatcherActivity.pendingRpcClipboardRead = focusDef
                            mainHandler.post {
                                try {
                                    val i = Intent(context, ClipboardCatcherActivity::class.java).apply {
                                        putExtra("rpc_only", true)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                                    }
                                    context.startActivity(i)
                                } catch (_: Throwable) {
                                    focusDef.complete("")
                                }
                            }
                            text = withTimeoutOrNull(2500L) { focusDef.await() } ?: ""
                        }
                        val includeOtp = params.optBoolean("includeRecentOtp", false)
                        val latestOtp = OtpExtractor.getFreshLatestOtp()
                        JSONObject().apply {
                            put("text", text)
                            if (includeOtp && latestOtp != null) {
                                put("recentOtp", JSONObject().apply {
                                    put("code", latestOtp.code)
                                    if (!latestOtp.sender.isNullOrBlank()) put("sender", latestOtp.sender)
                                    put("fullText", latestOtp.fullText)
                                    put("capturedAtMs", latestOtp.capturedAtMs)
                                })
                            }
                            put("updatedAtMs", System.currentTimeMillis())
                        }
                    }
                    "clipboard.set" -> {
                        val text = params.optString("text", "")
                        val setDef = CompletableDeferred<Boolean>()
                        mainHandler.post {
                            try {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("OneMoreStep", text))
                                setDef.complete(true)
                            } catch (_: Throwable) {
                                setDef.complete(false)
                            }
                        }
                        val ok = withTimeoutOrNull(1000L) { setDef.await() } ?: false
                        JSONObject().put("ok", ok)
                    }
                    "sys.otaUpdate" -> {
                        // Re-query authenticated metadata; a caller-supplied bare URL is not an integrity source.
                        val checked = CompletableDeferred<OtaCheckResult>()
                        checkOtaUpdate { checked.complete(it) }
                        val ota = checked.await()
                        installVerifiedOta(ota)
                        JSONObject().put("ok", true).put("status", "installer_launched")
                            .put("message", "APK 已验证，请在手机系统安装器中确认；尚未确认安装完成")
                    }
                    "alarm.set" -> AlarmExecutor.setSystemAlarm(context, params)
                    "calendar.list" -> CalendarExecutor.listEvents(context, params)
                    "calendar.insert" -> CalendarExecutor.insertEvent(context, params)
                    "calendar.delete" -> CalendarExecutor.deleteEvent(context, params)
                    "notify.send" -> {
                        val res = NotificationExecutor.sendNotification(context, params)
                        val notifyTag = params.optString("intentTag", "default_inbox").ifBlank { "default_inbox" }
                        repo.upsertStreamItem(
                            StreamItemModel(
                                itemId = OmsFrameProtocol.generateUuidV7(),
                                direction = "MAC_TO_PHONE",
                                itemType = "TEXT",
                                intentTag = notifyTag,
                                title = params.optString("title", "Mac AI 通知"),
                                textContent = params.optString("body", ""),
                                voiceTranscript = null,
                                localFileUri = null,
                                macSavedPath = null,
                                syncStatus = "DELIVERED",
                                metadataJson = null,
                                createdAtMs = System.currentTimeMillis()
                            )
                        )
                        res
                    }
                    "notify.requestApproval" -> {
                        val approvalId = params.optString("approvalId", OmsFrameProtocol.generateUuidV7())
                        val title = params.optString("title", "需要远程审批")
                        val summary = params.optString("summary", "")
                        val contextText = params.optString("contextText", "")
                        val options = params.optJSONArray("options") ?: JSONArray(listOf("批准继续", "拒绝并中止"))
                        val expiresAtMs = params.optLong("expiresAtMs", 0)
                        require(expiresAtMs > System.currentTimeMillis()) { "Approval has expired or has no expiry" }
                        require(options.length() >= 2 && options.length() <= 16) { "Invalid approval options" }
                        val labels = (0 until options.length()).map { options.getString(it) }
                        require(labels.all { it.isNotBlank() } && labels.distinct().size == labels.size) { "Invalid approval options" }
                        require(repo.getStreamItem(approvalId) == null) { "Approval already exists" }

                        repo.upsertStreamItem(
                            StreamItemModel(
                                itemId = approvalId,
                                direction = "MAC_TO_PHONE",
                                itemType = "APPROVAL",
                                intentTag = "default_inbox",
                                title = title,
                                textContent = "$summary\n$contextText".trim(),
                                voiceTranscript = null,
                                localFileUri = null,
                                macSavedPath = null,
                                syncStatus = "PENDING_APPROVAL",
                                metadataJson = JSONObject().apply {
                                    put("approvalId", approvalId)
                                    put("options", options)
                                    put("expiresAtMs", expiresAtMs)
                                }.toString(),
                                createdAtMs = System.currentTimeMillis()
                            )
                        )
                        NotificationExecutor.sendNotification(
                            context,
                            JSONObject().apply {
                                put("title", "⚠️ 远程审批: $title")
                                put("body", summary)
                                put("level", "urgent")
                            }
                        )
                        JSONObject().put("received", true)
                    }
                    "gui.observe" -> {
                        val svc = OmsAccessibilityService.instance
                            ?: throw IllegalStateException("ERR_ACCESSIBILITY_DISABLED: OmsAccessibilityService not enabled")
                        svc.observeScreen(
                            includeScreenshot = params.optBoolean("includeScreenshot", false),
                            somAnnotation = params.optBoolean("somAnnotation", true),
                            compactForBle = source.tier == "BT_RFCOMM",
                            onSendBinaryStream = { fName, mime, bytes ->
                                streamBinaryBytesToMac(fName, mime, bytes, source = source)
                            }
                        )
                    }
                    "gui.act" -> {
                        val svc = OmsAccessibilityService.instance
                            ?: throw IllegalStateException("ERR_ACCESSIBILITY_DISABLED: OmsAccessibilityService not enabled")
                        svc.executeGuiAction(params, sessionStillValid)
                    }
                    "gui.abort" -> {
                        OmsAccessibilityService.instance?.abortGuiSession()
                        JSONObject().put("ok", true)
                    }
                    "mirror.start" -> startScreenMirror(params, source)
                    "mirror.stop" -> stopScreenMirror()
                    "mirror.status" -> getScreenMirrorStatus()
                    "mirror.input" -> handleMirrorInput(params, sessionStillValid)
                    "sys.control" -> handleSysControlRpc(params, source, sessionStillValid)
                    "airpods.handoffToMac" -> {
                        airpodsManager.handleHandoffToMac(
                            targetMac = params.optString("targetMac", ""),
                            pausePhoneMedia = params.optBoolean("pausePhoneMedia", true),
                            disconnectOnPhone = params.optBoolean("disconnectOnPhone", true),
                            deadlineMs = params.optLong("deadlineMs", System.currentTimeMillis() + 3000L),
                            sessionStillValid = sessionStillValid
                        )
                    }
                    "airpods.connectOnPhone" -> {
                        val targetMac = params.optString("targetMac", "")
                        val (ok, msg, diagnostics) = airpodsManager.connectAirpodsOnPhone(
                            targetMac,
                            params.optLong("deadlineMs", System.currentTimeMillis() + 12000L),
                            sessionStillValid
                        )
                        JSONObject().apply {
                            put("ok", ok)
                            put("message", msg)
                            put("diagnostics", diagnostics)
                            put("status", collectDeviceSnapshot())
                        }
                    }
                    "airpods.syncConfig" -> {
                        airpodsManager.updateSyncConfig(params)
                        JSONObject().put("ok", true)
                    }
                    "edge.config.sync" -> {
                        val app = com.oms.client.OmsApplication.instance
                        app?.edgeStore?.saveSyncedEdgeConfig(params.toString())
                        app?.reloadEdgeRuntimeFromStore()
                        JSONObject().put("ok", true)
                    }
                    "edge.context_capsule.sync" -> {
                        com.oms.client.OmsApplication.instance?.edgeStore?.saveContextCapsule(params.toString())
                        JSONObject().put("ok", true)
                    }
                    "wechat.cursor.checkpoint" -> {
                        val app = com.oms.client.OmsApplication.instance
                        app?.edgeStore?.updateCursorFromMacCheckpoint(params.toString())
                        app?.wechatStandby?.refreshState()
                        JSONObject().put("ok", true)
                    }
                    "agent.session.event" -> {
                        com.oms.client.OmsApplication.instance?.cockpitCoordinator?.onRemoteAgentSessionEvent(params)
                        JSONObject().put("ok", true)
                    }
                    else -> throw IllegalArgumentException("Unknown RPC method: $method")
                }

                if (reqId.isNotEmpty()) {
                    val resp = JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("id", reqId)
                        put("result", result)
                    }
                    sendOnSource(OmsFrameProtocol.encodeJsonRpc(resp), source)
                }
            } catch (e: Throwable) {
                if (reqId.isNotEmpty()) {
                    val msg = e.message ?: "Execution failed"
                    val code = when {
                        msg.contains("ERR_SCOPED_PATH_DENIED") -> -32002
                        msg.contains("ERR_ACCESSIBILITY_DISABLED") -> -32003
                        msg.contains("ERR_SECURE_WINDOW_BLOCKED") -> -32004
                        msg.contains("ERR_USER_INTERRUPTED") -> -32005
                        else -> -32001
                    }
                    val errResp = JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("id", reqId)
                        put("error", JSONObject().apply {
                            put("code", code)
                            put("message", msg)
                        })
                    }
                    sendOnSource(OmsFrameProtocol.encodeJsonRpc(errResp), source)
                }
            }
        }
    }

    suspend fun callHostRpc(
        method: String,
        params: JSONObject,
        timeoutMs: Long = 20_000L
    ): JSONObject? {
        val activeSrc = runCatching { activeSource() }.getOrNull() ?: return null
        val rpcReq = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", "edge_${UUID.randomUUID()}")
            .put("method", method)
            .put("params", params)
        val effectiveTimeout = if (activeSrc.tier == "BT_RFCOMM") maxOf(timeoutMs, 15_000L) else timeoutMs
        val resp = runCatching { requestHost(rpcReq, activeSrc, effectiveTimeout) }.getOrNull() ?: return null
        return resp.optJSONObject("result") ?: resp
    }

    fun speakTextSmart(
        rawText: String,
        useAlarmStream: Boolean = true,
        repeatCount: Int = 1,
        voice: String = "xiaoyi",
        audioPolicy: SpeechAudioPolicy? = null
    ) {
        scope.launch {
            val activeSrc = runCatching { activeSource() }.getOrNull()
            if (activeSrc != null) {
                try {
                    val rpcReq = JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("id", "tts_${UUID.randomUUID()}")
                        .put("method", "tts.synthesize")
                        .put("params", JSONObject().apply {
                            put("text", rawText)
                            put("voice", voice)
                        })
                    val rpcRes = requestHost(rpcReq, activeSrc, 7_500L)
                    val audioB64 = rpcRes.optString("audioBase64", "")
                    if (audioB64.isNotBlank()) {
                        NotificationExecutor.speakText(
                            context,
                            rawText,
                            useAlarmStream = useAlarmStream,
                            speechRate = 1.0f,
                            audioBase64 = audioB64,
                            repeatCount = repeatCount,
                            audioPolicy = audioPolicy
                        )
                        return@launch
                    }
                } catch (_: Throwable) {
                }
            }
            NotificationExecutor.speakText(
                context,
                rawText,
                useAlarmStream = useAlarmStream,
                speechRate = 1.0f,
                repeatCount = repeatCount,
                audioPolicy = audioPolicy
            )
        }
    }

    private suspend fun handleSysControlRpc(params: JSONObject, source: FrameSource, sessionStillValid: () -> Boolean): JSONObject {
        val subAction = params.optString("action", "status").lowercase()
        return when (subAction) {
            "status", "refresh" -> collectDeviceSnapshot()
            "find_phone" -> {
                val text = params.optString("text", "正在寻找手机，我在这里！请查看青简咫尺。").ifBlank {
                    "正在寻找手机，我在这里！"
                }
                val audioBase64 = params.optString("audioBase64", "").takeIf { it.isNotBlank() }
                val repeatCount = params.optInt("repeatCount", 2).coerceIn(1, 20)
                NotificationExecutor.sendNotification(
                    context,
                    JSONObject().apply {
                        put("title", "🔔 Mac 正在寻找此手机")
                        put("body", text)
                        put("level", "urgent")
                        put("ttsSpeak", true)
                        put("audioPolicy", SpeechAudioPolicy.FORCE_SPEAKER_ALARM.wireName)
                        put("forceSpeakerAlarmStream", true)
                        if (audioBase64 != null) put("audioBase64", audioBase64)
                        put("repeatCount", repeatCount)
                    }
                )
                JSONObject().apply {
                    put("ok", true)
                    put("action", "find_phone")
                    put("audioPolicy", SpeechAudioPolicy.FORCE_SPEAKER_ALARM.wireName)
                    put("spokenText", text)
                    put("ttsEngine", if (audioBase64 != null) "edge_neural" else "android_local")
                    put("repeatCount", repeatCount)
                }
            }
            "tts_speak" -> {
                val text = params.optString("text", "").trim()
                val useAlarm = params.optBoolean("useAlarmStream", true)
                val policy = SpeechAudioPolicy.fromParams(
                    params,
                    defaultPolicy = SpeechAudioPolicy.ALARM_RESPECT_HEADSET
                )
                val rate = params.optDouble("speechRate", 1.0).toFloat()
                val audioBase64 = params.optString("audioBase64", "").takeIf { it.isNotBlank() }
                val repeatCount = params.optInt("repeatCount", 1).coerceIn(1, 20)
                val hasContent = text.isNotEmpty() || audioBase64 != null
                if (hasContent) {
                    NotificationExecutor.speakText(
                        context,
                        text,
                        useAlarmStream = useAlarm,
                        speechRate = rate,
                        audioBase64 = audioBase64,
                        repeatCount = repeatCount,
                        audioPolicy = policy
                    )
                }
                JSONObject().apply {
                    put("ok", hasContent)
                    put("action", "tts_speak")
                    put("useAlarmStream", useAlarm)
                    put("audioPolicy", policy.wireName)
                    put("ttsEngine", if (audioBase64 != null) "edge_neural" else "android_local")
                    put("repeatCount", repeatCount)
                }
            }
            "stop_tts" -> {
                NotificationExecutor.stopSpeaking()
                JSONObject().put("ok", true)
            }
            "vibrate" -> {
                val durationMs = params.optLong("durationMs", 500L).coerceIn(50L, 5000L)
                try {
                    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                } catch (_: Throwable) {
                }
                JSONObject().put("ok", true)
            }
            "set_volume" -> {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val streamName = params.optString("stream", "music").lowercase()
                val streamType = when (streamName) {
                    "ring" -> AudioManager.STREAM_RING
                    "alarm" -> AudioManager.STREAM_ALARM
                    "notification" -> AudioManager.STREAM_NOTIFICATION
                    else -> AudioManager.STREAM_MUSIC
                }
                val direction = params.optString("direction", "").lowercase()
                val maxVol = am.getStreamMaxVolume(streamType).coerceAtLeast(1)
                when (direction) {
                    "up" -> am.adjustStreamVolume(streamType, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    "down" -> am.adjustStreamVolume(streamType, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    "mute" -> am.adjustStreamVolume(streamType, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    "unmute" -> am.adjustStreamVolume(streamType, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                    else -> {
                        if (params.has("volumePct")) {
                            val pct = params.optInt("volumePct", 50).coerceIn(0, 100)
                            val targetIdx = Math.round(pct * maxVol / 100.0).toInt().coerceIn(0, maxVol)
                            am.setStreamVolume(streamType, targetIdx, AudioManager.FLAG_SHOW_UI)
                        }
                    }
                }
                val curVol = am.getStreamVolume(streamType)
                JSONObject().apply {
                    put("ok", true)
                    put("stream", streamName)
                    put("volumePct", Math.round(curVol * 100.0 / maxVol).toInt())
                    put("status", collectDeviceSnapshot())
                }
            }
            "open_url" -> {
                check(sessionStillValid()) { "Session is no longer valid" }
                check(OmsAccessibilityService.instance?.isEmergencyStopped() != true) { "ERR_USER_INTERRUPTED: GUI emergency stop is active" }
                val url = params.optString("url", "").trim()
                val (ok, errMsg) = AppLauncherHelper.openUrl(context, url)
                delay(350)
                JSONObject().apply {
                    put("ok", ok)
                    put("url", url)
                    if (errMsg != null) put("error", errMsg)
                    put("status", collectDeviceSnapshot())
                }
            }
            "launch_app" -> {
                check(sessionStillValid()) { "Session is no longer valid" }
                check(OmsAccessibilityService.instance?.isEmergencyStopped() != true) { "ERR_USER_INTERRUPTED: GUI emergency stop is active" }
                val rawTarget = params.optString("packageOrAlias", params.optString("text", "")).trim()
                val res = AppLauncherHelper.launchApp(context, rawTarget)
                delay(350)
                JSONObject().apply {
                    put("ok", res.ok)
                    put("packageName", res.packageName)
                    put("appLabel", res.appLabel)
                    put("screenLocked", res.screenLocked)
                    if (res.error != null) put("error", res.error)
                    if (res.ok && res.screenLocked) {
                        put("hint", "已为您点亮屏幕并唤起「${res.appLabel}」，解锁手机后即可直接查看")
                    }
                    put("status", collectDeviceSnapshot())
                }
            }
            "system_action" -> {
                val target = params.optString("target", "home").lowercase()
                val svc = OmsAccessibilityService.instance
                    ?: throw IllegalStateException("ERR_ACCESSIBILITY_DISABLED: 请先在手机上开启青简·咫尺无障碍服务")
                if (target == "unlock" || target == "unlock_screen") {
                    val res = svc.performScreenUnlock(params, sessionStillValid)
                    res.put("status", collectDeviceSnapshot())
                    return res
                }
                val ok = svc.performSystemGlobalAction(target, params.optLong("deadlineMs", 0L), sessionStillValid)
                delay(250)
                JSONObject().apply {
                    put("ok", ok)
                    put("target", target)
                    put("status", collectDeviceSnapshot())
                }
            }
            "unlock_screen", "unlock" -> {
                val svc = OmsAccessibilityService.instance
                    ?: throw IllegalStateException("ERR_ACCESSIBILITY_DISABLED: 请先在手机上开启青简·咫尺无障碍服务")
                val res = svc.performScreenUnlock(params, sessionStillValid)
                res.put("status", collectDeviceSnapshot())
                res
            }
            "call_phone", "call", "dial" -> {
                val svc = OmsAccessibilityService.instance
                    ?: throw IllegalStateException("ERR_ACCESSIBILITY_DISABLED: 请先在手机上开启青简·咫尺无障碍服务")
                if (subAction == "dial" && !params.has("dialOnly")) {
                    params.put("dialOnly", true)
                }
                val hasInCallSpeech = !params.optBoolean("dialOnly", false) && (
                    params.optString("sayText", params.optString("say", "")).isNotBlank() ||
                        params.optString("audioBase64", "").isNotBlank()
                    )
                if (hasInCallSpeech) {
                    airpodsManager.suppressAutoGrab(90_000L)
                    if (params.optBoolean("disconnectBluetoothForSpeech", true)) {
                        runCatching {
                            airpodsManager.handleHandoffToMac(
                                targetMac = "",
                                pausePhoneMedia = true,
                                disconnectOnPhone = true,
                                deadlineMs = System.currentTimeMillis() + 2500L,
                                sessionStillValid = sessionStillValid
                            )
                        }
                    }
                }
                val res = svc.performPhoneCall(params, sessionStillValid)
                res.put("status", collectDeviceSnapshot())
                res
            }
            "hangup_call", "hangup", "end_call" -> {
                val svc = OmsAccessibilityService.instance
                    ?: throw IllegalStateException("ERR_ACCESSIBILITY_DISABLED: 请先在手机上开启青简·咫尺无障碍服务")
                val res = svc.performHangupCall(sessionStillValid)
                res.put("status", collectDeviceSnapshot())
                res
            }
            "take_screenshot" -> {
                val svc = OmsAccessibilityService.instance
                    ?: throw IllegalStateException("ERR_ACCESSIBILITY_DISABLED: 请先在手机上开启青简·咫尺无障碍服务")
                val webpBytes = ScreenCapturer.captureWebpBytes(
                    svc,
                    emptyList(),
                    false,
                    compactForBle = source.tier == "BT_RFCOMM"
                ) ?: throw IllegalStateException("截屏失败，当前页面可能受隐私保护或屏幕已关闭")
                val fileName = "screen_${System.currentTimeMillis()}.webp"
                val streamId = streamBinaryBytesToMac(fileName, "image/webp", webpBytes, "default_inbox", source)
                val sha = OmsFrameProtocol.sha256Hex(webpBytes)
                JSONObject().apply {
                    put("ok", true)
                    put("streamId", streamId)
                    put("fileName", fileName)
                    put("mimeType", "image/webp")
                    put("totalBytes", webpBytes.size)
                    put("sha256", sha)
                }
            }
            else -> throw IllegalArgumentException("Unsupported sys.control action: $subAction")
        }
    }

    private fun resolveFriendlyAppName(pkg: String): String {
        return AppLauncherHelper.resolveFriendlyAppName(context, pkg)
    }

    private fun collectDeviceSnapshot(): JSONObject {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val batteryIntent = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Throwable) {
            null
        }
        val tempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempC = if (tempTenths > 0) tempTenths / 10.0 else 0.0
        val voltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val chargePlug = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
            else -> "NONE"
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val screenInteractive = pm.isInteractive
        val screenLocked = km.isKeyguardLocked
        val powerSaveMode = pm.isPowerSaveMode
        val brightness = try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Throwable) {
            -1
        }

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val ringerMode = when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "SILENT"
            AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"
            else -> "NORMAL"
        }
        fun streamPct(stream: Int): Int {
            val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
            val cur = am.getStreamVolume(stream).coerceAtLeast(0)
            return Math.round(cur * 100.0 / max).toInt()
        }
        val volMusic = streamPct(AudioManager.STREAM_MUSIC)
        val volRing = streamPct(AudioManager.STREAM_RING)
        val volNotif = streamPct(AudioManager.STREAM_NOTIFICATION)
        val volAlarm = streamPct(AudioManager.STREAM_ALARM)

        val localIps = getLocalPhoneWifiIpv4s()
        val lanIpsArr = JSONArray()
        for (ip in localIps) lanIpsArr.put(ip)

        var wifiSsid = ""
        var wifiRssi = 0
        var wifiSpeedMbps = 0
        var wifiFreqMhz = 0
        var wifiGateway = ""
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            if (info != null) {
                wifiRssi = info.rssi
                wifiSpeedMbps = info.linkSpeed
                wifiFreqMhz = info.frequency
                val rawSsid = info.ssid?.trim('"') ?: ""
                if (rawSsid.isNotBlank() && rawSsid != "<unknown ssid>") {
                    wifiSsid = rawSsid
                }
            }
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val wifiNet = findNonVpnWifiNetwork()
            if (cm != null && wifiNet != null) {
                val lp = cm.getLinkProperties(wifiNet)
                val gw = lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
                if (!gw.isNullOrBlank()) {
                    wifiGateway = gw
                }
            }
        } catch (_: Throwable) {
        }

        val btEnabled = try {
            val btm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            btm.adapter?.isEnabled == true
        } catch (_: Throwable) {
            false
        }

        var storageTotalGb = 0.0
        var storageAvailGb = 0.0
        var storageUsedPct = 0
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val totalBytes = stat.totalBytes.toDouble()
            val availBytes = stat.availableBytes.toDouble()
            storageTotalGb = Math.round((totalBytes / 1073741824.0) * 10.0) / 10.0
            storageAvailGb = Math.round((availBytes / 1073741824.0) * 10.0) / 10.0
            if (totalBytes > 0) {
                storageUsedPct = Math.round(((totalBytes - availBytes) / totalBytes) * 100.0).toInt()
            }
        } catch (_: Throwable) {
        }

        var ramTotalMb = 0L
        var ramAvailMb = 0L
        var ramUsedPct = 0
        try {
            val actMgr = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actMgr.getMemoryInfo(memInfo)
            ramTotalMb = memInfo.totalMem / 1048576L
            ramAvailMb = memInfo.availMem / 1048576L
            if (ramTotalMb > 0) {
                ramUsedPct = Math.round(((ramTotalMb - ramAvailMb).toDouble() / ramTotalMb.toDouble()) * 100.0).toInt()
            }
        } catch (_: Throwable) {
        }

        val fgPkg = OmsAccessibilityService.instance?.currentPackageName ?: ""
        val fgAct = OmsAccessibilityService.instance?.currentActivityName ?: ""
        val fgLabel = resolveFriendlyAppName(fgPkg)
        val (verName, verCode, _) = getInstalledApkInfo(includeSha256 = false)
        val health = XiaomiAdaptationHelper.inspectAll(context)
        val apSnap = airpodsManager.buildStatusJson()
        val queuedCount = try {
            repo.listQueuedOutboxItems().size
        } catch (_: Throwable) {
            0
        }

        return JSONObject().apply {
            put("batteryPct", pct)
            put("isCharging", bm.isCharging)
            put("chargePlug", chargePlug)
            put("temperatureC", tempC)
            put("voltageMv", voltageMv)
            put("powerSaveMode", powerSaveMode)
            put("lanIps", lanIpsArr)
            put("wifiSsid", wifiSsid)
            put("wifiRssiDbm", wifiRssi)
            put("wifiLinkSpeedMbps", wifiSpeedMbps)
            put("wifiFrequencyMhz", wifiFreqMhz)
            put("wifiGateway", wifiGateway)
            put("bluetoothEnabled", btEnabled)
            put("activeTransportTier", _activeTierFlow.value)
            put("connectionDiagnosticCode", diagnostics.reason().name)
            put("pairingVersion", repo.getPairedMac()?.pairingVersion ?: 0)
            put("secureProtocolVersion", if (secureLinks.keys.any { sourceAuthenticated(it) }) 2 else 0)
            put("foregroundPackage", fgPkg)
            put("foregroundActivity", fgAct)
            put("foregroundAppLabel", fgLabel)
            put("screenLocked", screenLocked)
            put("screenInteractive", screenInteractive)
            put("screenBrightness", brightness)
            put("ringerMode", ringerMode)
            put("volumeMusicPct", volMusic)
            put("volumeRingPct", volRing)
            put("volumeNotificationPct", volNotif)
            put("volumeAlarmPct", volAlarm)
            put("storageTotalGb", storageTotalGb)
            put("storageAvailableGb", storageAvailGb)
            put("storageUsedPct", storageUsedPct)
            put("ramTotalMb", ramTotalMb)
            put("ramAvailableMb", ramAvailMb)
            put("ramUsedPct", ramUsedPct)
            put("deviceBrand", Build.BRAND)
            put("deviceManufacturer", Build.MANUFACTURER)
            put("deviceModel", Build.MODEL)
            put("androidVersion", Build.VERSION.RELEASE)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("romVersion", Build.DISPLAY)
            put("appVersionName", verName)
            put("appVersionCode", verCode)
            put("uptimeMinutes", SystemClock.elapsedRealtime() / 60000L)
            put("pendingOutboxCount", queuedCount)
            put("updatedAtMs", System.currentTimeMillis())
            put("airpodsAutoSwitchEnabled", apSnap.optBoolean("autoSwitchEnabled", true))
            put("airpodsAutoSwitchDiagnostics", apSnap.optJSONObject("autoSwitchDiagnostics"))
            put("airpodsConnected", apSnap.optBoolean("connected", false))
            put("airpodsName", apSnap.optString("deviceName", ""))
            put("airpodsMac", apSnap.optString("deviceMac", ""))
            put("pairedAirpods", apSnap.optJSONArray("pairedAirpods") ?: JSONArray())
            put("mediaPlaying", apSnap.optBoolean("mediaPlaying", false))
            put("audioModeInCall", apSnap.optBoolean("inCall", false))
            put("permissions", health.toJson())
        }
    }

    // =========================================================================
    // Real-Time Screen Mirroring Engine (L1 Silent Stream + Direct Input)
    // =========================================================================
    private val mirrorActive = AtomicBoolean(false)
    private val mirrorSeq = AtomicInteger(0)
    private val mirrorBoostUntilElapsedMs = AtomicLong(0L)
    private val latestMirrorFrameSlot = AtomicReference<ByteArray?>(null)
    private val mirrorWakeSignal = Channel<Unit>(Channel.CONFLATED)
    private val mirrorSendSignal = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var mirrorCaptureJob: Job? = null
    @Volatile
    private var mirrorSenderJob: Job? = null
    @Volatile
    private var mirrorMaxWidth: Int = 640
    @Volatile
    private var mirrorQuality: Int = 72
    @Volatile
    private var mirrorMaxFps: Int = 8
    @Volatile
    private var mirrorWakeLock: PowerManager.WakeLock? = null

    private fun boostMirrorPacing(durationMs: Long = 2200L) {
        if (!mirrorActive.get()) return
        val target = SystemClock.elapsedRealtime() + durationMs
        while (true) {
            val cur = mirrorBoostUntilElapsedMs.get()
            if (target <= cur || mirrorBoostUntilElapsedMs.compareAndSet(cur, target)) break
        }
        mirrorWakeSignal.trySend(Unit)
    }

    private fun acquireMirrorWakeLock() {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (mirrorWakeLock?.isHeld == true) return
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                "OneMoreStep:LiveMirrorKeepScreenOn"
            )
            wl.setReferenceCounted(false)
            wl.acquire(15 * 60 * 1000L) // max 15 min per session renew
            mirrorWakeLock = wl
        } catch (_: Throwable) {
        }
    }

    private fun releaseMirrorWakeLock() {
        try {
            if (mirrorWakeLock?.isHeld == true) {
                mirrorWakeLock?.release()
            }
        } catch (_: Throwable) {
        } finally {
            mirrorWakeLock = null
        }
    }

    private fun startScreenMirror(params: JSONObject, source: FrameSource): JSONObject {
        val svc = OmsAccessibilityService.instance
            ?: throw IllegalStateException("ERR_ACCESSIBILITY_DISABLED: 请先在手机上开启青简·咫尺无障碍服务")
        if (source.tier == "BT_RFCOMM") {
            throw IllegalStateException("实时同屏需要局域网 (LAN_WS) 或高速通道，蓝牙通道带宽不足")
        }

        mirrorMaxWidth = params.optInt("maxWidth", 640).coerceIn(360, 1080)
        mirrorQuality = params.optInt("quality", 72).coerceIn(45, 90)
        mirrorMaxFps = params.optInt("maxFps", 8).coerceIn(2, 12)
        val autoWake = params.optBoolean("autoWake", true)
        val keepScreenOn = params.optBoolean("keepScreenOn", true)

        if (autoWake) {
            AppLauncherHelper.wakeScreenIfNeeded(context)
        }
        if (keepScreenOn) {
            acquireMirrorWakeLock()
        }

        svc.onMirrorUiEvent = { boostMirrorPacing(1800L) }
        boostMirrorPacing(3500L)

        if (mirrorActive.compareAndSet(false, true)) {
            OmsCoreForegroundService.setLowLatencyWifiNeeded("mirror", true)
            latestMirrorFrameSlot.set(null)

            // Non-blocking Latest-Frame-Wins sender coroutine
            mirrorSenderJob = scope.launch {
                while (isActive && mirrorActive.get()) {
                    try {
                        val frameBytes = latestMirrorFrameSlot.getAndSet(null)
                        if (frameBytes != null) {
                            val curSrc = runCatching { activeSource() }.getOrNull()
                            if (curSrc != null && (curSrc.tier == "LAN_WS" || curSrc.tier == "WAN_WEBRTC")) {
                                val ws = curSrc.socket
                                if (ws != null && ws.queueSize() > 1536L * 1024L) {
                                    // Drop mirror frame on backpressure instead of overflowing the WebSocket send queue
                                    delay(60L)
                                } else {
                                    sendOnSource(frameBytes, curSrc)
                                }
                            }
                        } else {
                            withTimeoutOrNull(400L) {
                                mirrorSendSignal.receive()
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        delay(400L)
                    }
                }
            }

            // Adaptive screen capture coroutine
            mirrorCaptureJob = scope.launch {
                var lastHash = 0L
                var lastSentAtElapsed = 0L
                var lastStatusFlags = -1

                try {
                    while (isActive && mirrorActive.get()) {
                        try {
                            val curSrc = runCatching { activeSource() }.getOrNull()
                            if (curSrc == null || (curSrc.tier != "LAN_WS" && curSrc.tier != "WAN_WEBRTC")) {
                                delay(500L)
                                continue
                            }
                            val currentSvc = OmsAccessibilityService.instance
                            if (currentSvc == null) {
                                delay(500L)
                                continue
                            }

                            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                            val screenOn = pm.isInteractive
                            val screenLocked = km.isKeyguardLocked
                            val dm = currentSvc.resources.displayMetrics
                            val screenW = dm.widthPixels.coerceAtLeast(1)
                            val screenH = dm.heightPixels.coerceAtLeast(1)
                            val nowElapsed = SystemClock.elapsedRealtime()

                            if (!screenOn) {
                                val flags = OmsFrameProtocol.SCREEN_FLAG_SCREEN_OFF or
                                    (if (screenLocked) OmsFrameProtocol.SCREEN_FLAG_SCREEN_LOCKED else 0)
                                if (flags != lastStatusFlags || nowElapsed - lastSentAtElapsed > 2000L) {
                                    val seq = mirrorSeq.incrementAndGet() and 0xFFFF
                                    val encoded = OmsFrameProtocol.encodeScreenFrame(
                                        codec = OmsFrameProtocol.CODEC_STATUS_ONLY,
                                        flags = flags,
                                        width = screenW,
                                        height = screenH,
                                        seq = seq,
                                        timestampMs = System.currentTimeMillis()
                                    )
                                    latestMirrorFrameSlot.set(encoded)
                                    mirrorSendSignal.trySend(Unit)
                                    lastStatusFlags = flags
                                    lastSentAtElapsed = nowElapsed
                                    lastHash = 0L
                                }
                                withTimeoutOrNull(500L) { mirrorWakeSignal.receive() }
                                continue
                            }

                            val forceHeartbeat = (nowElapsed - lastSentAtElapsed) > 2800L
                            val captured = ScreenCapturer.captureMirrorFrame(
                                service = currentSvc,
                                maxWidth = mirrorMaxWidth,
                                quality = mirrorQuality,
                                previousHash = lastHash,
                                forceEncode = forceHeartbeat
                            )

                            if (captured != null) {
                                if (captured.isSecureWindow) {
                                    val flags = OmsFrameProtocol.SCREEN_FLAG_SECURE_WINDOW or
                                        (if (screenLocked) OmsFrameProtocol.SCREEN_FLAG_SCREEN_LOCKED else 0)
                                    if (flags != lastStatusFlags || forceHeartbeat) {
                                        val seq = mirrorSeq.incrementAndGet() and 0xFFFF
                                        val encoded = OmsFrameProtocol.encodeScreenFrame(
                                            codec = OmsFrameProtocol.CODEC_STATUS_ONLY,
                                            flags = flags,
                                            width = captured.screenWidth,
                                            height = captured.screenHeight,
                                            seq = seq,
                                            timestampMs = System.currentTimeMillis()
                                        )
                                        latestMirrorFrameSlot.set(encoded)
                                        mirrorSendSignal.trySend(Unit)
                                        lastStatusFlags = flags
                                        lastSentAtElapsed = nowElapsed
                                        lastHash = -1L
                                    }
                                } else if (!captured.unchanged && captured.bytes.isNotEmpty()) {
                                    val flags = if (screenLocked) OmsFrameProtocol.SCREEN_FLAG_SCREEN_LOCKED else 0
                                    val seq = mirrorSeq.incrementAndGet() and 0xFFFF
                                    val encoded = OmsFrameProtocol.encodeScreenFrame(
                                        codec = OmsFrameProtocol.CODEC_WEBP,
                                        flags = flags,
                                        width = captured.screenWidth,
                                        height = captured.screenHeight,
                                        seq = seq,
                                        timestampMs = System.currentTimeMillis(),
                                        imageBytes = captured.bytes
                                    )
                                    latestMirrorFrameSlot.set(encoded)
                                    mirrorSendSignal.trySend(Unit)
                                    lastHash = captured.frameHash
                                    lastStatusFlags = flags
                                    lastSentAtElapsed = SystemClock.elapsedRealtime()
                                }
                            }

                            val isBoosted = SystemClock.elapsedRealtime() < mirrorBoostUntilElapsedMs.get()
                            val waitMs = if (isBoosted) {
                                (1000L / mirrorMaxFps.coerceIn(2, 12)).coerceIn(110L, 350L)
                            } else {
                                480L
                            }
                            if (isBoosted) {
                                delay(waitMs)
                            } else {
                                withTimeoutOrNull(waitMs) { mirrorWakeSignal.receive() }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            delay(500L)
                        }
                    }
                } finally {
                    releaseMirrorWakeLock()
                }
            }
        } else {
            // Already running: force an immediate refresh frame
            boostMirrorPacing(2500L)
        }

        val dm = svc.resources.displayMetrics
        return JSONObject().apply {
            put("ok", true)
            put("running", true)
            put("mode", "native_stream")
            put("maxWidth", mirrorMaxWidth)
            put("quality", mirrorQuality)
            put("maxFps", mirrorMaxFps)
            put("screenWidth", dm.widthPixels)
            put("screenHeight", dm.heightPixels)
        }
    }

    private fun stopScreenMirror(): JSONObject {
        mirrorActive.set(false)
        OmsCoreForegroundService.setLowLatencyWifiNeeded("mirror", false)
        OmsAccessibilityService.instance?.onMirrorUiEvent = null
        latestMirrorFrameSlot.set(null)
        mirrorCaptureJob?.cancel()
        mirrorSenderJob?.cancel()
        mirrorCaptureJob = null
        mirrorSenderJob = null
        releaseMirrorWakeLock()
        return JSONObject().apply {
            put("ok", true)
            put("running", false)
        }
    }

    private fun getScreenMirrorStatus(): JSONObject {
        val dm = context.resources.displayMetrics
        return JSONObject().apply {
            put("ok", true)
            put("running", mirrorActive.get())
            put("seq", mirrorSeq.get())
            put("maxWidth", mirrorMaxWidth)
            put("quality", mirrorQuality)
            put("maxFps", mirrorMaxFps)
            put("screenWidth", dm.widthPixels)
            put("screenHeight", dm.heightPixels)
        }
    }

    private suspend fun handleMirrorInput(params: JSONObject, sessionStillValid: () -> Boolean): JSONObject {
        val svc = OmsAccessibilityService.instance
            ?: throw IllegalStateException("ERR_ACCESSIBILITY_DISABLED: 请先在手机上开启青简·咫尺无障碍服务")
        boostMirrorPacing(2400L)
        val res = svc.executeMirrorInput(params, sessionStillValid)
        boostMirrorPacing(2400L)
        return res
    }
}
