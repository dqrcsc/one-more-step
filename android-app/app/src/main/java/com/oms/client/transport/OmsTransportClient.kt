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
import com.oms.client.capability.AlarmExecutor
import com.oms.client.capability.AppLauncherHelper
import com.oms.client.capability.CalendarExecutor
import com.oms.client.capability.NotificationExecutor
import com.oms.client.capability.ScopedStorageExecutor
import com.oms.client.data.PairedMacConfig
import com.oms.client.data.StreamItemModel
import com.oms.client.data.StreamRepository
import com.oms.client.share.ClipboardCatcherActivity
import com.oms.client.xiaomi.XiaomiAdaptationHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    val message: String = ""
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

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

    @Volatile
    private var cachedWifiNetwork: Network? = null
    @Volatile
    private var cachedLanHttpClient: OkHttpClient? = null

    private val wanDecoder = OmsFrameStreamDecoder()
    private val btDecoder = OmsFrameStreamDecoder()

    private val lanRetryRunnable = Runnable {
        val paired = repo.getPairedMac()
        connectLanIfPossible(paired?.lanIps ?: emptyList(), paired?.lanPort ?: 19527)
    }

    private class IncomingPushTransfer(
        val header: JSONObject,
        val chunks: MutableMap<Int, ByteArray> = ConcurrentHashMap()
    )

    private val incomingTransfers = ConcurrentHashMap<String, IncomingPushTransfer>()

    init {
        registerWifiNetworkCallback()
        startUdpBeaconListener()
        startBackgroundWatchdog()
    }

    private fun startBackgroundWatchdog() {
        scope.launch {
            while (true) {
                delay(4500L)
                try {
                    if (_activeTierFlow.value != "LAN_WS" || lanWebSocket == null) {
                        startBleNearFieldScanner()
                        val paired = repo.getPairedMac()
                        connectLanIfPossible(paired?.lanIps ?: emptyList(), paired?.lanPort ?: 19527)
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
                    sock.receive(pkt)
                    val str = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                    if (str.contains("OMS_MAC_BEACON") || str.contains("OneMoreStep")) {
                        try {
                            val json = JSONObject(str)
                            val macId = json.optString("macId")
                            val macName = json.optString("macName", "MacBook Pro")
                            val macPub = json.optString("macPubKeyBase64Url", "")
                            val port = json.optInt("lanPort", 19527)
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
                                val existing = repo.getPairedMac()
                                val updated = PairedMacConfig(
                                    macId = macId.ifBlank { existing?.macId ?: "mac_host" },
                                    macName = macName,
                                    lanIps = ips,
                                    lanPort = port,
                                    macPubKeyBase64Url = macPub.ifBlank { existing?.macPubKeyBase64Url ?: "" },
                                    wanRoomId = existing?.wanRoomId ?: "",
                                    wanRelayWssUrl = existing?.wanRelayWssUrl
                                )
                                repo.savePairedMac(updated)
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
                    if (_activeTierFlow.value != "LAN_WS" || lanWebSocket == null) {
                        scheduleLanReconnect(150L)
                    }
                }

                override fun onLost(network: Network) {
                    if (cachedWifiNetwork == network) {
                        cachedWifiNetwork = null
                        cachedLanHttpClient = null
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
            var fallbackWifi: Network? = null
            for (net in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                ) {
                    val lp = cm.getLinkProperties(net)
                    val hasIpv4 = lp?.linkAddresses?.any { it.address is Inet4Address && !it.address.isLoopbackAddress } == true
                    if (hasIpv4) {
                        return net
                    }
                    if (fallbackWifi == null) fallbackWifi = net
                }
            }
            return fallbackWifi
        } catch (_: Throwable) {
        }
        return null
    }

    private fun buildLanOkHttpClient(): OkHttpClient {
        val physicalWifi = findNonVpnWifiNetwork()
        val existing = cachedLanHttpClient
        if (existing != null && physicalWifi == cachedWifiNetwork) {
            return existing
        }
        val builder = OkHttpClient.Builder()
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)

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
                wanRelayWssUrl = existing?.wanRelayWssUrl
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
            val client = buildLanOkHttpClient().newBuilder()
                .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .build()
            val res = executeMacHttpGet(client, ip, port)
            if (res != null) return res
        } catch (_: Throwable) {
        }

        // Fallback to default system network socket if custom Wi-Fi binding failed on priority IP
        if (!checkTcpFirst) {
            try {
                val fallbackClient = OkHttpClient.Builder()
                    .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .build()
                return executeMacHttpGet(fallbackClient, ip, port)
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /**
     * Automatically discover Mac Host on LAN when DHCP IP changes (e.g. 192.168.1.77 -> 192.168.1.100).
     * 1. Priority Direct HTTP Probe (no concurrency flood)
     * 2. Fast UDP Broadcast Probe (< 250ms)
     * 3. Batched TCP Subnet Scan (32 IPs per batch to prevent Dispatchers.IO LIFO starvation & ARP queue overflow)
     */
    private suspend fun discoverMacOnLan(
        port: Int = 19527,
        expectedMacId: String? = null
    ): PairedMacConfig? = coroutineScope {
        val phoneIps = getLocalPhoneWifiIpv4s()
        val paired = repo.getPairedMac()

        // 1. Priority Direct Probe first (0 contention, immediate response!)
        val priorityIps = LinkedHashSet<String>()
        paired?.lanIps?.forEach { if (it.isNotBlank() && !it.startsWith("198.18.")) priorityIps.add(it) }
        for (pip in priorityIps) {
            val cfg = probeMacHttpInfo(pip, port, timeoutMs = 650, checkTcpFirst = false)
            if (cfg != null && (expectedMacId == null || cfg.macId == expectedMacId)) {
                repo.savePairedMac(cfg)
                return@coroutineScope cfg
            }
        }

        // 2. Fast UDP Broadcast Discovery Probe
        try {
            DatagramSocket().use { udpSocket ->
                findNonVpnWifiNetwork()?.bindSocket(udpSocket)
                udpSocket.broadcast = true
                udpSocket.soTimeout = 250
                val payload = "OMS_DISCOVER_V1".toByteArray(Charsets.UTF_8)
                val broadcastTargets = mutableSetOf("255.255.255.255")
                for (pip in phoneIps + priorityIps) {
                    val parts = pip.split(".")
                    if (parts.size == 4) {
                        val p0 = parts[0]
                        val p1 = parts[1]
                        val p2 = parts[2].toIntOrNull() ?: continue
                        broadcastTargets.add("$p0.$p1.$p2.255")
                        broadcastTargets.add("$p0.$p1.${p2 or 1}.255")
                    }
                }
                for (bcast in broadcastTargets) {
                    try {
                        val pkt = DatagramPacket(payload, payload.size, InetAddress.getByName(bcast), port)
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
                    if (expectedMacId == null || expectedMacId == macId) {
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
                                macId = macId.ifBlank { paired?.macId ?: "mac_host" },
                                macName = json.optString("macName", paired?.macName ?: "MacBook Pro"),
                                lanIps = ips,
                                lanPort = json.optInt("lanPort", port),
                                macPubKeyBase64Url = json.optString("macPubKeyBase64Url", paired?.macPubKeyBase64Url ?: ""),
                                wanRoomId = paired?.wanRoomId ?: "",
                                wanRelayWssUrl = paired?.wanRelayWssUrl
                            )
                            repo.savePairedMac(found)
                            return@coroutineScope found
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }

        // 3. Batched Subnet Scan (32 IPs per batch to avoid ARP queue overflow and Dispatchers.IO LIFO starvation)
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
            prefixes.add("192.168.0")
        }

        val scanIps = mutableListOf<String>()
        for (prefix in prefixes) {
            for (host in 1..254) {
                val candidate = "$prefix.$host"
                if (!priorityIps.contains(candidate) && !scanIps.contains(candidate)) {
                    scanIps.add(candidate)
                }
            }
        }

        for (batch in scanIps.chunked(32)) {
            val results = batch.map { ip ->
                async(Dispatchers.IO) {
                    val cfg = probeMacHttpInfo(ip, port, timeoutMs = 240, checkTcpFirst = true)
                    if (cfg != null && (expectedMacId == null || cfg.macId == expectedMacId)) cfg else null
                }
            }.awaitAll()
            val hit = results.firstOrNull { it != null }
            if (hit != null) {
                repo.savePairedMac(hit)
                return@coroutineScope hit
            }
        }

        null
    }

    fun startAutoConnect() {
        startBleNearFieldScanner()
        val paired = repo.getPairedMac()
        connectLanIfPossible(paired?.lanIps ?: emptyList(), paired?.lanPort ?: 19527)
        paired?.wanRelayWssUrl?.takeIf { it.isNotBlank() }?.let { connectWanRelay(it) }
    }

    /**
     * Manual one-tap reconnect & LAN auto-discovery triggered from UI
     */
    fun reconnectNow(onResult: (Boolean, String) -> Unit) {
        scope.launch {
            if (_activeTierFlow.value == "LAN_WS" && lanWebSocket != null) {
                mainHandler.post { onResult(true, "✅ 已处于局域网高速直连状态") }
                return@launch
            }
            isConnectingLan.set(false)
            val paired = repo.getPairedMac()
            val port = paired?.lanPort ?: 19527
            val discovered = discoverMacOnLan(port, paired?.macId) ?: discoverMacOnLan(port, null)
            if (discovered != null) {
                connectLanIfPossible(discovered.lanIps, discovered.lanPort)
                mainHandler.post {
                    onResult(
                        true,
                        "🟢 已自动发现并连接 ${discovered.macName} (${discovered.lanIps.first()})"
                    )
                }
            } else {
                connectLanIfPossible(paired?.lanIps ?: emptyList(), port)
                mainHandler.post {
                    onResult(
                        false,
                        "⚠️ 未在当前局域网扫描到 Mac (端口 $port)，请确认手机与 Mac 在同一 Wi-Fi"
                    )
                }
            }
        }
    }

    /**
     * Pair with Mac using scanned `oms://pair?...` URI, raw IP address, or auto-discovery if blank
     */
    fun pairWithUri(pairingUri: String, onResult: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                val trimmed = pairingUri.trim()
                if (trimmed.isBlank() || !trimmed.startsWith("oms://")) {
                    // Support direct IP input (e.g. "192.168.1.100" or "192.168.1.100:19527") or auto-scan
                    val customHostPort = trimmed.removePrefix("http://").removePrefix("ws://").substringBefore("/")
                    val customIp = customHostPort.substringBefore(":").trim()
                    val customPort = customHostPort.substringAfter(":", "19527").toIntOrNull() ?: 19527
                    val found = if (customIp.isNotBlank()) {
                        probeMacHttpInfo(customIp, customPort, 1200) ?: discoverMacOnLan(customPort, null)
                    } else {
                        discoverMacOnLan(19527, null)
                    }
                    if (found != null) {
                        repo.savePairedMac(found)
                        isConnectingLan.set(false)
                        connectLanIfPossible(found.lanIps, found.lanPort)
                        mainHandler.post {
                            onResult(true, "✅ 已自动发现并绑定 ${found.macName} (${found.lanIps.first()})")
                        }
                    } else {
                        mainHandler.post {
                            onResult(false, "未在局域网找到 Mac，请确认同一 Wi-Fi 或输入完整配对链接")
                        }
                    }
                    return@launch
                }

                val uri = Uri.parse(trimmed)
                val macId = uri.getQueryParameter("id") ?: throw IllegalArgumentException("Missing id")
                val macName = uri.getQueryParameter("name") ?: "MacBook Pro"
                val uriIps = (uri.getQueryParameter("ip") ?: "")
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.startsWith("198.18.") }
                val port = (uri.getQueryParameter("port") ?: "19527").toInt()
                val macPub = uri.getQueryParameter("pk") ?: ""
                val pinCode = uri.getQueryParameter("pin") ?: ""
                val token = uri.getQueryParameter("token") ?: ""

                // Verify which IP is actually reachable; if Mac IP changed since URI was generated, auto-discover!
                var targetIp = uriIps.firstOrNull { probeMacHttpInfo(it, port, 500) != null }
                if (targetIp == null) {
                    val discovered = discoverMacOnLan(port, macId) ?: discoverMacOnLan(port, null)
                    targetIp = discovered?.lanIps?.firstOrNull() ?: uriIps.firstOrNull()
                }
                if (targetIp.isNullOrBlank()) throw IllegalArgumentException("No reachable LAN IP found")
                val effectiveIps = listOf(targetIp) + uriIps.filter { it != targetIp }

                val phoneId = repo.getOrCreatePhoneId()
                val phonePub = "pub_${phoneId}"
                val hmacHex = OmsFrameProtocol.computePairingHmac(token, pinCode, phoneId, phonePub)

                val client = buildLanOkHttpClient()
                val targetUrl = "ws://$targetIp:$port/?tier=lan"
                val req = Request.Builder().url(targetUrl).build()

                client.newWebSocket(req, object : WebSocketListener() {
                    val tempDecoder = OmsFrameStreamDecoder()
                    val resultDelivered = AtomicBoolean(false)

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        val frames = try {
                            tempDecoder.push(bytes.toByteArray())
                        } catch (_: Throwable) {
                            return
                        }
                        for (f in frames) {
                            if (f.frameType == OmsFrameProtocol.TYPE_CONTROL_JSON_RPC) {
                                val msg = JSONObject(String(f.payload, Charsets.UTF_8))
                                if (msg.optString("method") == "auth.helloChallenge") {
                                    val pairReq = JSONObject().apply {
                                        put("jsonrpc", "2.0")
                                        put("id", "pair_req_1")
                                        put("method", "pair.request")
                                        put("params", JSONObject().apply {
                                            put("phoneId", phoneId)
                                            put("phoneModel", "${Build.MANUFACTURER} ${Build.MODEL}")
                                            put("phoneName", Build.MODEL)
                                            put("phonePubKeyBase64Url", phonePub)
                                            put("pinCode", pinCode)
                                            put("hmacHex", hmacHex)
                                            put("timestampMs", System.currentTimeMillis())
                                        })
                                    }
                                    webSocket.send(OmsFrameProtocol.encodeJsonRpc(pairReq).toByteString())
                                } else if (msg.optString("id") == "pair_req_1") {
                                    val res = msg.optJSONObject("result")
                                    if (res != null && res.optBoolean("ok")) {
                                        val config = PairedMacConfig(
                                            macId = res.optString("macId", macId),
                                            macName = res.optString("macName", macName),
                                            lanIps = effectiveIps,
                                            lanPort = port,
                                            macPubKeyBase64Url = res.optString("macPubKeyBase64Url", macPub),
                                            wanRoomId = res.optString("wanRoomId", ""),
                                            wanRelayWssUrl = null
                                        )
                                        repo.savePairedMac(config)
                                        lanWebSocket = webSocket
                                        _activeTierFlow.value = "LAN_WS"
                                        flushOutboxQueue()
                                        if (resultDelivered.compareAndSet(false, true)) {
                                            mainHandler.post { onResult(true, "成功绑定 ${config.macName} ($targetIp)") }
                                        }
                                    } else {
                                        // If PIN expired on Mac, fall back to direct local LAN auth!
                                        val fallbackConfig = PairedMacConfig(
                                            macId = macId,
                                            macName = macName,
                                            lanIps = effectiveIps,
                                            lanPort = port,
                                            macPubKeyBase64Url = macPub,
                                            wanRoomId = "",
                                            wanRelayWssUrl = null
                                        )
                                        repo.savePairedMac(fallbackConfig)
                                        webSocket.close(1000, "Fallback to auth")
                                        isConnectingLan.set(false)
                                        connectLanIfPossible(effectiveIps, port)
                                        if (resultDelivered.compareAndSet(false, true)) {
                                            mainHandler.post { onResult(true, "已更新 Mac 地址 ($targetIp) 并自动重连") }
                                        }
                                    }
                                } else {
                                    handleDecodedFrame("LAN_WS", f, webSocket)
                                }
                            } else {
                                handleDecodedFrame("LAN_WS", f, webSocket)
                            }
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (lanWebSocket === webSocket) {
                            lanWebSocket = null
                            recomputeFallbackTier()
                            scheduleLanReconnect(2500L)
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (lanWebSocket === webSocket) {
                            lanWebSocket = null
                            recomputeFallbackTier()
                            scheduleLanReconnect(2500L)
                        } else if (resultDelivered.compareAndSet(false, true)) {
                            mainHandler.post { onResult(false, "连接 Mac 失败: ${t.message}") }
                        }
                    }
                })
            } catch (e: Throwable) {
                mainHandler.post { onResult(false, "解析配对码失败: ${e.message}") }
            }
        }
    }

    fun connectLanIfPossible(ips: List<String>, port: Int) {
        if (_activeTierFlow.value == "LAN_WS" && lanWebSocket != null) return
        if (!isConnectingLan.compareAndSet(false, true)) return

        scope.launch {
            try {
                val paired = repo.getPairedMac()
                val candidateIps = (ips + (paired?.lanIps ?: emptyList()))
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.startsWith("198.18.") }
                    .distinct()

                // 1. Test priority & saved IPs first (fast direct HTTP GET, no concurrency storm)
                var reachableConfig: PairedMacConfig? = null
                for (candidate in candidateIps) {
                    val info = probeMacHttpInfo(candidate, port, timeoutMs = 600, checkTcpFirst = false)
                    if (info != null) {
                        reachableConfig = info
                        repo.savePairedMac(info)
                        break
                    }
                }

                // 2. If saved IPs are unreachable (e.g. Mac DHCP IP changed), auto-discover on LAN!
                if (reachableConfig == null) {
                    reachableConfig = discoverMacOnLan(port, paired?.macId)
                        ?: discoverMacOnLan(port, null)
                }

                val targetIp = reachableConfig?.lanIps?.firstOrNull() ?: candidateIps.firstOrNull()
                val targetPort = reachableConfig?.lanPort ?: port
                if (targetIp.isNullOrBlank()) {
                    isConnectingLan.set(false)
                    scheduleLanReconnect(3500L)
                    return@launch
                }

                val client = buildLanOkHttpClient()
                val req = Request.Builder().url("ws://$targetIp:$targetPort/?tier=lan").build()
                val socketDecoder = OmsFrameStreamDecoder()

                client.newWebSocket(req, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        isConnectingLan.set(false)
                        val prev = lanWebSocket
                        if (prev != null && prev !== webSocket) {
                            try {
                                prev.close(1000, "Replaced")
                            } catch (_: Throwable) {
                            }
                        }
                        lanWebSocket = webSocket
                        _activeTierFlow.value = "LAN_WS"
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        handleIncomingRawBytes("LAN_WS", socketDecoder, bytes.toByteArray(), webSocket)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        isConnectingLan.set(false)
                        if (lanWebSocket === webSocket) {
                            lanWebSocket = null
                            recomputeFallbackTier()
                            scheduleLanReconnect(3000L)
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        isConnectingLan.set(false)
                        if (lanWebSocket === webSocket) {
                            lanWebSocket = null
                            recomputeFallbackTier()
                        }
                        scheduleLanReconnect(3000L)
                    }
                })
            } catch (_: Throwable) {
                isConnectingLan.set(false)
                scheduleLanReconnect(3500L)
            }
        }
    }

    fun connectWanRelay(wssUrl: String) {
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val req = Request.Builder().url(wssUrl).build()

        client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                wanDecoder.reset()
                wanWebSocket = webSocket
                if (_activeTierFlow.value == "DISCONNECTED") {
                    _activeTierFlow.value = "WAN_WEBRTC"
                }
                flushOutboxQueue()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleIncomingRawBytes("WAN_WEBRTC", wanDecoder, bytes.toByteArray())
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (wanWebSocket === webSocket) {
                    wanWebSocket = null
                    recomputeFallbackTier()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (wanWebSocket === webSocket) {
                    wanWebSocket = null
                    recomputeFallbackTier()
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun handleBleMacInfoBytes(
        gatt: BluetoothGatt,
        pipeChar: BluetoothGattCharacteristic?,
        valBytes: ByteArray
    ) {
        try {
            val info = JSONObject(String(valBytes, Charsets.UTF_8))
            val macId = info.optString("macId")
            val ipsArr = info.optJSONArray("lanIps")
            val port = info.optInt("lanPort", 19527)
            val ips = mutableListOf<String>()
            if (ipsArr != null) {
                for (i in 0 until ipsArr.length()) {
                    val s = ipsArr.getString(i)
                    if (s.isNotBlank() && !s.startsWith("198.18.")) {
                        ips.add(s)
                    }
                }
            }
            if (ips.isNotEmpty()) {
                val existing = repo.getPairedMac()
                repo.savePairedMac(
                    PairedMacConfig(
                        macId = macId.ifBlank { existing?.macId ?: "mac_host" },
                        macName = existing?.macName ?: "MacBook Pro",
                        lanIps = ips,
                        lanPort = port,
                        macPubKeyBase64Url = existing?.macPubKeyBase64Url ?: "",
                        wanRoomId = existing?.wanRoomId ?: "",
                        wanRelayWssUrl = existing?.wanRelayWssUrl
                    )
                )
                isConnectingLan.set(false)
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
                if (_activeTierFlow.value == "DISCONNECTED") {
                    _activeTierFlow.value = "BT_RFCOMM"
                    flushOutboxQueue()
                }
            }
        } catch (_: Throwable) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleNearFieldScanner() {
        try {
            if (activeBleGatt != null) return
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
            val adapter: BluetoothAdapter = bm.adapter ?: return
            if (!adapter.isEnabled) return
            val scanner = adapter.bluetoothLeScanner ?: return
            if (!isBleScanning.compareAndSet(false, true)) return

            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BLE_SERVICE_UUID))
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            scanner.startScan(listOf(filter), settings, object : ScanCallback() {
                override fun onScanFailed(errorCode: Int) {
                    isBleScanning.set(false)
                }

                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.device ?: return
                    if (activeBleGatt == null) {
                        try {
                            scanner.stopScan(this)
                        } catch (_: Throwable) {
                        }
                        isBleScanning.set(false)
                        activeBleGatt = device.connectGatt(
                            context,
                            false,
                            object : BluetoothGattCallback() {
                                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                                        gatt.discoverServices()
                                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                                        try {
                                            gatt.close()
                                        } catch (_: Throwable) {
                                        }
                                        activeBleGatt = null
                                        bleFramePipeChar = null
                                        recomputeFallbackTier()
                                    }
                                }

                                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                                    val svc = gatt.getService(BLE_SERVICE_UUID) ?: return
                                    val infoChar = svc.getCharacteristic(BLE_CHAR_MAC_INFO_UUID)
                                    val pipeChar = svc.getCharacteristic(BLE_CHAR_FRAME_PIPE_UUID)
                                    bleFramePipeChar = pipeChar
                                    if (infoChar != null) {
                                        gatt.readCharacteristic(infoChar)
                                    } else if (pipeChar != null) {
                                        gatt.setCharacteristicNotification(pipeChar, true)
                                        if (_activeTierFlow.value == "DISCONNECTED") {
                                            _activeTierFlow.value = "BT_RFCOMM"
                                            flushOutboxQueue()
                                        }
                                    }
                                }

                                // Android 13+ (API 33+) callback signature
                                override fun onCharacteristicRead(
                                    gatt: BluetoothGatt,
                                    characteristic: BluetoothGattCharacteristic,
                                    value: ByteArray,
                                    status: Int
                                ) {
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
                                    if (characteristic.uuid == BLE_CHAR_FRAME_PIPE_UUID && value.isNotEmpty()) {
                                        handleIncomingRawBytes("BT_RFCOMM", btDecoder, value)
                                    }
                                }

                                // Android 12 and below fallback signature
                                @Suppress("DEPRECATION")
                                override fun onCharacteristicChanged(
                                    gatt: BluetoothGatt,
                                    characteristic: BluetoothGattCharacteristic
                                ) {
                                    if (characteristic.uuid == BLE_CHAR_FRAME_PIPE_UUID) {
                                        val bytes = characteristic.value ?: return
                                        handleIncomingRawBytes("BT_RFCOMM", btDecoder, bytes)
                                    }
                                }
                            },
                            BluetoothDevice.TRANSPORT_LE
                        )
                    }
                }
            })
        } catch (_: Throwable) {
            isBleScanning.set(false)
        }
    }

    private fun recomputeFallbackTier() {
        _activeTierFlow.value = when {
            lanWebSocket != null -> "LAN_WS"
            bleFramePipeChar != null -> "BT_RFCOMM"
            wanWebSocket != null -> "WAN_WEBRTC"
            else -> "DISCONNECTED"
        }
    }

    @SuppressLint("MissingPermission")
    fun sendRawFrame(frameBytes: ByteArray): Boolean {
        val lan = lanWebSocket
        if (lan != null) {
            return lan.send(frameBytes.toByteString())
        }
        val gatt = activeBleGatt
        val pipeChar = bleFramePipeChar
        if (gatt != null && pipeChar != null) {
            @Suppress("DEPRECATION")
            pipeChar.value = frameBytes
            return gatt.writeCharacteristic(pipeChar)
        }
        val wan = wanWebSocket
        if (wan != null) {
            return wan.send(frameBytes.toByteString())
        }
        return false
    }

    /**
     * Submit an item (screenshot, shared article, voice idea, clipboard) to Mac Inbox,
     * or queue offline in SQLite if currently disconnected.
     */
    fun submitItemToMacOrQueue(
        intentTag: String,
        itemType: String,
        title: String?,
        textContent: String?,
        voiceTranscript: String?,
        fileUri: Uri?,
        sourceApp: String? = null,
        extraAudioUri: Uri? = null
    ): String {
        val itemId = OmsFrameProtocol.generateUuidV7()
        val metaJson = if (extraAudioUri != null) {
            JSONObject().put("extraAudioUri", extraAudioUri.toString()).toString()
        } else {
            null
        }
        val item = StreamItemModel(
            itemId = itemId,
            direction = "PHONE_TO_MAC",
            itemType = itemType,
            intentTag = intentTag,
            title = title,
            textContent = textContent,
            voiceTranscript = voiceTranscript,
            localFileUri = fileUri?.toString(),
            macSavedPath = null,
            syncStatus = "QUEUED",
            metadataJson = metaJson,
            createdAtMs = System.currentTimeMillis()
        )
        repo.upsertStreamItem(item)
        flushOutboxQueue()
        return itemId
    }

    private fun readUriBytesAndMime(uriStr: String, itemIdSuffix: String, prefix: String = "mobile"): Triple<ByteArray, String, String>? {
        return try {
            val uri = Uri.parse(uriStr)
            val bytes = if (uri.scheme == "file" && uri.path != null) {
                File(uri.path!!).takeIf { it.exists() }?.readBytes()
            } else {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } ?: return null

            val pathLower = (uri.path ?: uriStr).lowercase()
            val resolverMime = try {
                context.contentResolver.getType(uri)
            } catch (_: Throwable) {
                null
            }
            val (mime, ext) = when {
                pathLower.endsWith(".m4a") -> "audio/mp4" to "m4a"
                pathLower.endsWith(".mp3") -> "audio/mpeg" to "mp3"
                pathLower.endsWith(".wav") -> "audio/wav" to "wav"
                pathLower.endsWith(".ogg") -> "audio/ogg" to "ogg"
                pathLower.endsWith(".aac") -> "audio/aac" to "aac"
                pathLower.endsWith(".webp") -> "image/webp" to "webp"
                pathLower.endsWith(".jpg") || pathLower.endsWith(".jpeg") -> "image/jpeg" to "jpg"
                pathLower.endsWith(".png") -> "image/png" to "png"
                pathLower.endsWith(".pdf") -> "application/pdf" to "pdf"
                !resolverMime.isNullOrBlank() -> resolverMime to resolverMime.substringAfterLast('/', "bin")
                else -> "image/png" to "png"
            }
            val filePrefix = if (mime.startsWith("audio/")) "voice" else prefix
            val fileName = "${filePrefix}_${itemIdSuffix}.$ext"
            Triple(bytes, mime, fileName)
        } catch (_: Throwable) {
            null
        }
    }

    fun flushOutboxQueue() {
        if (_activeTierFlow.value == "DISCONNECTED") return
        if (!isFlushingOutbox.compareAndSet(false, true)) return
        scope.launch {
            try {
                val queued = repo.listQueuedOutboxItems()
                for (item in queued) {
                    try {
                        val attachmentsArr = JSONArray()
                        val suffix = item.itemId.takeLast(8)
                        val uriStr = item.localFileUri
                        if (!uriStr.isNullOrBlank()) {
                            val loaded = readUriBytesAndMime(uriStr, suffix, "mobile")
                            if (loaded != null) {
                                val (bytes, mime, fileName) = loaded
                                val streamId = streamBinaryBytesToMac(fileName, mime, bytes, item.intentTag)
                                val sha = OmsFrameProtocol.sha256Hex(bytes)
                                attachmentsArr.put(
                                    JSONObject().apply {
                                        put("streamId", streamId)
                                        put("fileName", fileName)
                                        put("mimeType", mime)
                                        put("totalBytes", bytes.size)
                                        put("sha256", sha)
                                    }
                                )
                            }
                        }

                        val extraAudioStr = try {
                            item.metadataJson?.let { JSONObject(it).optString("extraAudioUri", "") }
                        } catch (_: Throwable) {
                            null
                        }
                        if (!extraAudioStr.isNullOrBlank()) {
                            val loadedAudio = readUriBytesAndMime(extraAudioStr, "${suffix}_audio", "voice")
                            if (loadedAudio != null) {
                                val (bytes, mime, fileName) = loadedAudio
                                val streamId = streamBinaryBytesToMac(fileName, mime, bytes, item.intentTag)
                                val sha = OmsFrameProtocol.sha256Hex(bytes)
                                attachmentsArr.put(
                                    JSONObject().apply {
                                        put("streamId", streamId)
                                        put("fileName", fileName)
                                        put("mimeType", mime)
                                        put("totalBytes", bytes.size)
                                        put("sha256", sha)
                                    }
                                )
                            }
                        }

                        val req = JSONObject().apply {
                            put("jsonrpc", "2.0")
                            put("id", "inbox_${item.itemId}")
                            put("method", "inbox.submitItem")
                            put("params", JSONObject().apply {
                                put("clientItemId", item.itemId)
                                put("intentTag", item.intentTag)
                                if (item.title != null) put("title", item.title)
                                if (item.textContent != null) put("textContent", item.textContent)
                                if (item.voiceTranscript != null) put("voiceTranscript", item.voiceTranscript)
                                put("createdAtMs", item.createdAtMs)
                                if (attachmentsArr.length() > 0) put("attachments", attachmentsArr)
                            })
                        }

                        if (sendRawFrame(OmsFrameProtocol.encodeJsonRpc(req))) {
                            repo.updateSyncStatus(item.itemId, "DELIVERED")
                        }
                    } catch (_: Throwable) {
                    }
                }
            } finally {
                isFlushingOutbox.set(false)
            }
        }
    }

    fun sendApprovalDecision(
        itemId: String,
        approvalId: String,
        approved: Boolean,
        selectedOption: String,
        userComment: String?
    ) {
        scope.launch {
            val req = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", "apr_${OmsFrameProtocol.generateUuidV7()}")
                put("method", "approval.respond")
                put("params", JSONObject().apply {
                    put("approvalId", approvalId)
                    put("approved", approved)
                    put("selectedOption", selectedOption)
                    if (!userComment.isNullOrBlank()) put("userComment", userComment)
                    put("decidedAtMs", System.currentTimeMillis())
                })
            }
            sendRawFrame(OmsFrameProtocol.encodeJsonRpc(req))
            repo.updateSyncStatus(itemId, if (approved) "APPROVED" else "REJECTED")
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

    fun getInstalledApkInfo(): Triple<String, Int, String> {
        return try {
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val verName = pkgInfo.versionName ?: "1.1.0"
            val verCode = pkgInfo.longVersionCode.toInt()
            val srcFile = File(context.applicationInfo.sourceDir)
            val sha = if (srcFile.exists()) {
                OmsFrameProtocol.sha256Hex(srcFile.readBytes())
            } else {
                ""
            }
            Triple(verName, verCode, sha)
        } catch (_: Throwable) {
            Triple("1.1.0", 2, "")
        }
    }

    fun checkOtaUpdate(onResult: (OtaCheckResult) -> Unit) {
        scope.launch {
            val (localVerName, localVerCode, localSha) = getInstalledApkInfo()
            var paired = repo.getPairedMac()
            val port = paired?.lanPort ?: 19527
            var ip = paired?.lanIps?.firstOrNull { probeMacHttpInfo(it, port, 500) != null }
            if (ip == null) {
                val discovered = discoverMacOnLan(port, paired?.macId) ?: discoverMacOnLan(port, null)
                if (discovered != null) {
                    paired = discovered
                    ip = discovered.lanIps.firstOrNull()
                }
            }
            val finalIp = ip ?: paired?.lanIps?.firstOrNull()
            if (finalIp.isNullOrBlank()) {
                mainHandler.post {
                    onResult(
                        OtaCheckResult(
                            ok = false,
                            hasUpdate = false,
                            localVersionName = localVerName,
                            localVersionCode = localVerCode,
                            localSha256 = localSha,
                            message = "尚未连接或发现局域网内的 Mac 宿主"
                        )
                    )
                }
                return@launch
            }
            val versionUrl = "http://$finalIp:$port/apk/version?t=${System.currentTimeMillis()}"
            val downloadUrl = "http://$finalIp:$port/OneMoreStep.apk?t=${System.currentTimeMillis()}"

            try {
                val client = buildLanOkHttpClient()
                val req = Request.Builder().url(versionUrl).get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        mainHandler.post {
                            onResult(
                                OtaCheckResult(
                                    ok = false,
                                    hasUpdate = false,
                                    localVersionName = localVerName,
                                    localVersionCode = localVerCode,
                                    localSha256 = localSha,
                                    message = "查询 Mac 版本失败 (HTTP ${resp.code})"
                                )
                            )
                        }
                        return@launch
                    }

                    val bodyStr = resp.body?.string() ?: "{}"
                    val json = JSONObject(bodyStr)
                    val available = json.optBoolean("available", false)
                    val remoteVerCode = json.optInt("versionCode", 0)
                    val remoteVerName = json.optString("versionName", "1.1.0")
                    val remoteSha = json.optString("sha256", "")
                    val remoteSize = json.optLong("sizeBytes", 0L)
                    val remoteUpdatedIso = json.optString("updatedAtIso", "")

                    if (!available || remoteSha.isBlank()) {
                        mainHandler.post {
                            onResult(
                                OtaCheckResult(
                                    ok = false,
                                    hasUpdate = false,
                                    localVersionName = localVerName,
                                    localVersionCode = localVerCode,
                                    localSha256 = localSha,
                                    message = "Mac 端暂未找到可更新的 APK 安装包"
                                )
                            )
                        }
                        return@launch
                    }

                    val isSameBinary = localSha.isNotBlank() && localSha.equals(remoteSha, ignoreCase = true)
                    val isOlderOnMac = remoteVerCode < localVerCode
                    val hasUpdate = !isSameBinary && !isOlderOnMac

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
                                remoteSizeBytes = remoteSize,
                                remoteUpdatedAtIso = remoteUpdatedIso,
                                downloadUrl = downloadUrl,
                                message = if (hasUpdate) {
                                    "发现新版本 v$remoteVerName"
                                } else {
                                    "✅ 当前已是最新版本 (v$localVerName)"
                                }
                            )
                        )
                    }
                }
            } catch (e: Throwable) {
                mainHandler.post {
                    onResult(
                        OtaCheckResult(
                            ok = false,
                            hasUpdate = false,
                            localVersionName = localVerName,
                            localVersionCode = localVerCode,
                            localSha256 = localSha,
                            message = "无法连接 Mac 检查更新: ${e.message}"
                        )
                    )
                }
            }
        }
    }

    private fun streamBinaryBytesToMac(
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        intentTag: String? = null
    ): String {
        val streamId = OmsFrameProtocol.generateUuidV7()
        val chunkSize = if (_activeTierFlow.value == "BT_RFCOMM") 8192 else 65536
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
        sendRawFrame(OmsFrameProtocol.encodeStreamStart(startHeader))

        for (i in 0 until totalChunks) {
            val start = i * chunkSize
            val end = (start + chunkSize).coerceAtMost(bytes.size)
            val slice = bytes.copyOfRange(start, end)
            sendRawFrame(OmsFrameProtocol.encodeStreamChunk(streamId, i, slice))
        }
        return streamId
    }

    private fun handleIncomingRawBytes(
        tier: String,
        decoder: OmsFrameStreamDecoder,
        raw: ByteArray,
        sourceWebSocket: WebSocket? = null
    ) {
        val frames = try {
            decoder.push(raw)
        } catch (_: Throwable) {
            return
        }

        for (frame in frames) {
            handleDecodedFrame(tier, frame, sourceWebSocket)
        }
    }

    private fun handleDecodedFrame(
        tier: String,
        frame: OmsFrameProtocol.DecodedFrame,
        sourceWebSocket: WebSocket? = null
    ) {
        when (frame.frameType) {
            OmsFrameProtocol.TYPE_CONTROL_JSON_RPC -> {
                val msg = JSONObject(String(frame.payload, Charsets.UTF_8))
                if (msg.has("method")) {
                    handleIncomingRpcRequest(msg, sourceWebSocket)
                } else if (msg.optString("id").startsWith("auth_")) {
                    val res = msg.optJSONObject("result")
                    if (res != null && res.optBoolean("ok")) {
                        if (tier == "LAN_WS" && sourceWebSocket != null) {
                            lanWebSocket = sourceWebSocket
                            _activeTierFlow.value = "LAN_WS"
                        }
                        flushOutboxQueue()
                    }
                } else if (msg.optString("id").startsWith("inbox_")) {
                    val itemId = msg.optString("id").removePrefix("inbox_")
                    val macPath = msg.optJSONObject("result")?.optString("macPath")
                    repo.updateSyncStatus(itemId, "DELIVERED", macPath)
                }
            }
            OmsFrameProtocol.TYPE_STREAM_START -> {
                val header = JSONObject(String(frame.payload, Charsets.UTF_8))
                val streamId = header.optString("streamId")
                if (streamId.isNotEmpty()) {
                    incomingTransfers[streamId] = IncomingPushTransfer(header)
                }
            }
            OmsFrameProtocol.TYPE_STREAM_CHUNK -> {
                if (frame.payload.size < OmsFrameProtocol.STREAM_CHUNK_PREFIX_BYTES) return
                val streamId = OmsFrameProtocol.bytesToUuid(frame.payload.copyOfRange(0, 16))
                val chunkIdx = ByteBuffer.wrap(frame.payload, 16, 4).order(ByteOrder.BIG_ENDIAN).int
                val data = frame.payload.copyOfRange(OmsFrameProtocol.STREAM_CHUNK_PREFIX_BYTES, frame.payload.size)
                val transfer = incomingTransfers[streamId] ?: return
                transfer.chunks[chunkIdx] = data
                val totalChunks = transfer.header.optInt("totalChunks", 1)
                if (transfer.chunks.size >= totalChunks) {
                    val out = ByteArrayOutputStream()
                    for (i in 0 until totalChunks) {
                        transfer.chunks[i]?.let { out.write(it) }
                    }
                    val fullBytes = out.toByteArray()
                    val fileName = transfer.header.optString("fileName", "pushed_doc.md")
                    val title = transfer.header.optString("title", fileName)
                    val enableTts = transfer.header.optBoolean("enableCommuteTts", false)
                    val savedFile = File(context.filesDir, "${streamId}_$fileName")
                    savedFile.writeBytes(fullBytes)
                    incomingTransfers.remove(streamId)

                    val textPreview = if (fileName.endsWith(".md") || fileName.endsWith(".txt")) {
                        String(fullBytes, Charsets.UTF_8)
                    } else {
                        "收到 Mac 推送文件：$fileName (${fullBytes.size / 1024} KB)"
                    }

                    repo.upsertStreamItem(
                        StreamItemModel(
                            itemId = streamId,
                            direction = "MAC_TO_PHONE",
                            itemType = if (enableTts) "COMMUTE_READER" else "FILE",
                            intentTag = "default_inbox",
                            title = title,
                            textContent = textPreview,
                            voiceTranscript = null,
                            localFileUri = Uri.fromFile(savedFile).toString(),
                            macSavedPath = null,
                            syncStatus = "DELIVERED",
                            metadataJson = JSONObject().put("enableCommuteTts", enableTts).toString(),
                            createdAtMs = System.currentTimeMillis()
                        )
                    )
                }
            }
            OmsFrameProtocol.TYPE_PING -> {
                val sentTs = ByteBuffer.wrap(frame.payload).order(ByteOrder.BIG_ENDIAN).long
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val pongBytes = OmsFrameProtocol.encodePong(sentTs, pct, bm.isCharging)
                if (sourceWebSocket != null) {
                    sourceWebSocket.send(pongBytes.toByteString())
                } else {
                    sendRawFrame(pongBytes)
                }
            }
        }
    }

    private fun handleIncomingRpcRequest(req: JSONObject, sourceWebSocket: WebSocket? = null) {
        val reqId = req.optString("id", "")
        val method = req.optString("method", "")
        val params = req.optJSONObject("params") ?: JSONObject()

        scope.launch {
            try {
                val result: JSONObject = when (method) {
                    "auth.helloChallenge" -> {
                        val phoneId = repo.getOrCreatePhoneId()
                        val authResp = JSONObject().apply {
                            put("jsonrpc", "2.0")
                            put("id", "auth_${System.currentTimeMillis()}")
                            put("method", "auth.response")
                            put("params", JSONObject().apply {
                                put("phoneId", phoneId)
                                put("noncePhoneHex", OmsFrameProtocol.generateUuidV7().replace("-", ""))
                                put("phoneSignatureBase64Url", "trusted_xiaomi_sig")
                                put("deviceSnapshot", collectDeviceSnapshot())
                            })
                        }
                        val encoded = OmsFrameProtocol.encodeJsonRpc(authResp)
                        if (sourceWebSocket != null) {
                            sourceWebSocket.send(encoded.toByteString())
                        } else {
                            sendRawFrame(encoded)
                        }
                        delay(60)
                        flushOutboxQueue()
                        return@launch
                    }
                    "sys.getStatus" -> collectDeviceSnapshot()
                    "storage.listRecentMedia" -> ScopedStorageExecutor.listRecentMedia(context, repo, params)
                    "storage.listDir" -> ScopedStorageExecutor.listDir(repo, params)
                    "storage.pullFile" -> {
                        val src = params.getString("sourceUriOrPath")
                        val compress = params.optBoolean("compressImage", false)
                        val pulled = ScopedStorageExecutor.readAuthorizedFileBytes(context, repo, src, compress)
                        val streamId = streamBinaryBytesToMac(pulled.fileName, pulled.mimeType, pulled.bytes)
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
                        JSONObject().apply {
                            put("text", text)
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
                        val paired = repo.getPairedMac()
                        val ip = params.optString("ip").takeIf { it.isNotBlank() }
                            ?: paired?.lanIps?.firstOrNull()
                            ?: "127.0.0.1"
                        val port = params.optInt("port", paired?.lanPort ?: 19527)
                        val url = params.optString("url").takeIf { it.isNotBlank() }
                            ?: "http://$ip:$port/OneMoreStep.apk?t=${System.currentTimeMillis()}"
                        mainHandler.post {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (_: Throwable) {
                            }
                        }
                        JSONObject().apply {
                            put("ok", true)
                            put("url", url)
                        }
                    }
                    "alarm.set" -> AlarmExecutor.setSystemAlarm(context, params)
                    "calendar.list" -> CalendarExecutor.listEvents(context, params)
                    "calendar.insert" -> CalendarExecutor.insertEvent(context, params)
                    "calendar.delete" -> CalendarExecutor.deleteEvent(context, params)
                    "notify.send" -> {
                        val res = NotificationExecutor.sendNotification(context, params)
                        repo.upsertStreamItem(
                            StreamItemModel(
                                itemId = OmsFrameProtocol.generateUuidV7(),
                                direction = "MAC_TO_PHONE",
                                itemType = "TEXT",
                                intentTag = "default_inbox",
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
                            onSendBinaryStream = { fName, mime, bytes ->
                                streamBinaryBytesToMac(fName, mime, bytes)
                            }
                        )
                    }
                    "gui.act" -> {
                        val svc = OmsAccessibilityService.instance
                            ?: throw IllegalStateException("ERR_ACCESSIBILITY_DISABLED: OmsAccessibilityService not enabled")
                        svc.executeGuiAction(params)
                    }
                    "gui.abort" -> {
                        OmsAccessibilityService.instance?.abortGuiSession()
                        JSONObject().put("ok", true)
                    }
                    "sys.control" -> handleSysControlRpc(params)
                    else -> throw IllegalArgumentException("Unknown RPC method: $method")
                }

                if (reqId.isNotEmpty()) {
                    val resp = JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("id", reqId)
                        put("result", result)
                    }
                    sendRawFrame(OmsFrameProtocol.encodeJsonRpc(resp))
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
                    sendRawFrame(OmsFrameProtocol.encodeJsonRpc(errResp))
                }
            }
        }
    }

    private suspend fun handleSysControlRpc(params: JSONObject): JSONObject {
        val subAction = params.optString("action", "status").lowercase()
        return when (subAction) {
            "status", "refresh" -> collectDeviceSnapshot()
            "find_phone" -> {
                val text = params.optString("text", "正在寻找手机，我在这里！请查看青简咫尺。").ifBlank {
                    "正在寻找手机，我在这里！"
                }
                NotificationExecutor.sendNotification(
                    context,
                    JSONObject().apply {
                        put("title", "🔔 Mac 正在寻找此手机")
                        put("body", text)
                        put("level", "urgent")
                        put("ttsSpeak", true)
                        put("forceSpeakerAlarmStream", true)
                    }
                )
                JSONObject().apply {
                    put("ok", true)
                    put("action", "find_phone")
                    put("spokenText", text)
                }
            }
            "tts_speak" -> {
                val text = params.optString("text", "").trim()
                val useAlarm = params.optBoolean("useAlarmStream", false)
                val rate = params.optDouble("speechRate", 1.15).toFloat()
                if (text.isNotEmpty()) {
                    NotificationExecutor.speakText(context, text, useAlarmStream = useAlarm, speechRate = rate)
                }
                JSONObject().apply {
                    put("ok", text.isNotEmpty())
                    put("action", "tts_speak")
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
                val ok = svc.performSystemGlobalAction(target)
                delay(250)
                JSONObject().apply {
                    put("ok", ok)
                    put("target", target)
                    put("status", collectDeviceSnapshot())
                }
            }
            "take_screenshot" -> {
                val svc = OmsAccessibilityService.instance
                    ?: throw IllegalStateException("ERR_ACCESSIBILITY_DISABLED: 请先在手机上开启青简·咫尺无障碍服务")
                val webpBytes = ScreenCapturer.captureWebpBytes(svc, emptyList(), false)
                    ?: throw IllegalStateException("截屏失败，当前页面可能受隐私保护或屏幕已关闭")
                val fileName = "screen_${System.currentTimeMillis()}.webp"
                val streamId = streamBinaryBytesToMac(fileName, "image/webp", webpBytes, "default_inbox")
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
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            if (info != null) {
                wifiRssi = info.rssi
                wifiSpeedMbps = info.linkSpeed
                val rawSsid = info.ssid?.trim('"') ?: ""
                if (rawSsid.isNotBlank() && rawSsid != "<unknown ssid>") {
                    wifiSsid = rawSsid
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
        val (verName, verCode, _) = getInstalledApkInfo()
        val health = XiaomiAdaptationHelper.inspectAll(context)
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
            put("bluetoothEnabled", btEnabled)
            put("activeTransportTier", _activeTierFlow.value)
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
            put("permissions", health.toJson())
        }
    }
}
