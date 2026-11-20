package com.oms.core.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * In-process 127.0.0.1 loopback gateway supporting authenticated HTTP CONNECT, HTTP proxy, and SOCKS5.
 * Protected by per-process random credentials so no external UID/app on Android can piggyback on the port.
 */
class LoopbackMixedGateway(
    val router: SmartRegionRouter,
    val authUser: String = "oms",
    val authSecret: String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ProxyCryptoPrimitives.randomBytes(18))
) : AutoCloseable {

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "oms-loopback-proxy").apply { isDaemon = true }
    }

    var port: Int = 0
        private set

    private data class ActiveClientConnection(
        val client: Socket,
        val tunnel: ProxyTunnel,
        val node: ProxyNode?,
        val targetHost: String
    )

    private val activeConnections = ConcurrentHashMap.newKeySet<ActiveClientConnection>()

    private val expectedBasicHeader: String =
        Credentials.basic(authUser, authSecret)

    fun findActiveNodeForHost(host: String): ProxyNode? {
        val clean = host.trim().lowercase()
        return activeConnections.firstOrNull { it.targetHost == clean && it.node != null }?.node
    }

    fun closeConnectionsForNode(node: ProxyNode) {
        val targetKey = SmartRegionRouter.nodeKey(node)
        for (conn in activeConnections) {
            val n = conn.node ?: continue
            if (SmartRegionRouter.nodeKey(n) == targetKey) {
                runCatching { conn.tunnel.close() }
                runCatching { conn.client.close() }
            }
        }
    }

    fun closeAllConnections() {
        for (conn in activeConnections) {
            runCatching { conn.tunnel.close() }
            runCatching { conn.client.close() }
        }
    }

    fun start(bindPort: Int = 0): Int {
        if (running.compareAndSet(false, true)) {
            val srv = ServerSocket()
            srv.reuseAddress = true
            srv.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), bindPort), 128)
            serverSocket = srv
            port = srv.localPort
            executor.execute { acceptLoop(srv) }
        }
        return port
    }

    private fun acceptLoop(srv: ServerSocket) {
        while (running.get() && !srv.isClosed) {
            val client = try {
                srv.accept()
            } catch (_: Throwable) {
                break
            }
            executor.execute { handleClient(client) }
        }
    }

    private fun handleClient(client: Socket) {
        var tunnel: ProxyTunnel? = null
        var tracked: ActiveClientConnection? = null
        try {
            if (!client.inetAddress.isLoopbackAddress) {
                client.close()
                return
            }
            client.tcpNoDelay = true
            client.soTimeout = 15_000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val first = input.read()
            if (first < 0) return
            client.soTimeout = 660_000

            val routed = if (first == 0x05) {
                handleSocks5(client, input, output)
            } else {
                handleHttp(client, first.toByte(), input, output)
            } ?: return

            tunnel = routed.tunnel
            val entry = ActiveClientConnection(client, routed.tunnel, routed.node, routed.targetHost)
            tracked = entry
            activeConnections.add(entry)
            pumpBidirectional(client, input, output, routed.tunnel, routed.initialServerChunk)
        } catch (_: Throwable) {
        } finally {
            tracked?.let { activeConnections.remove(it) }
            runCatching { tunnel?.close() }
            runCatching { client.close() }
        }
    }

    private fun readExactBytes(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) throw EOFException("Unexpected EOF")
            off += r
        }
        return out
    }

    private fun peekInitialClientPayload(client: Socket, input: InputStream): ByteArray {
        val prevTimeout = client.soTimeout
        return try {
            client.soTimeout = 90
            val buf = ByteArray(16384)
            val r = input.read(buf)
            if (r > 0) buf.copyOf(r) else ByteArray(0)
        } catch (_: java.net.SocketTimeoutException) {
            ByteArray(0)
        } finally {
            runCatching { client.soTimeout = prevTimeout }
        }
    }

    private fun handleSocks5(client: Socket, input: InputStream, output: OutputStream): RoutedTunnelResult? {
        val nMethods = readExactBytes(input, 1)[0].toInt() and 0xFF
        val methods = readExactBytes(input, nMethods)
        if (!methods.any { (it.toInt() and 0xFF) == 0x02 }) {
            output.write(byteArrayOf(0x05, 0xFF.toByte()))
            output.flush()
            return null
        }
        output.write(byteArrayOf(0x05, 0x02))
        output.flush()

        val authVer = readExactBytes(input, 1)[0].toInt() and 0xFF
        if (authVer != 0x01) return null
        val uLen = readExactBytes(input, 1)[0].toInt() and 0xFF
        val uStr = String(readExactBytes(input, uLen), Charsets.UTF_8)
        val pLen = readExactBytes(input, 1)[0].toInt() and 0xFF
        val pStr = String(readExactBytes(input, pLen), Charsets.UTF_8)
        if (uStr != authUser || pStr != authSecret) {
            output.write(byteArrayOf(0x01, 0x01))
            output.flush()
            return null
        }
        output.write(byteArrayOf(0x01, 0x00))
        output.flush()

        val head = readExactBytes(input, 4)
        if (head[0].toInt() != 0x05 || head[1].toInt() != 0x01) {
            output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            return null
        }
        val targetHost = when (head[3].toInt() and 0xFF) {
            0x01 -> InetAddress.getByAddress(readExactBytes(input, 4)).hostAddress
            0x03 -> {
                val dLen = readExactBytes(input, 1)[0].toInt() and 0xFF
                String(readExactBytes(input, dLen), Charsets.US_ASCII)
            }
            0x04 -> InetAddress.getByAddress(readExactBytes(input, 16)).hostAddress
            else -> return null
        }
        val portBytes = readExactBytes(input, 2)
        val targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

        output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        output.flush()

        val initPayload = peekInitialClientPayload(client, input)
        return router.openRoutedTunnelWithNodeBlocking(targetHost, targetPort, initPayload)
    }

    private fun handleHttp(
        client: Socket,
        firstByte: Byte,
        input: InputStream,
        output: OutputStream
    ): RoutedTunnelResult? {
        val hdrBuf = ByteArrayOutputStream(1024)
        hdrBuf.write(firstByte.toInt() and 0xFF)
        val tmp = ByteArray(4096)
        var headerBytes: ByteArray
        var sepIdx: Int
        while (true) {
            val cur = hdrBuf.toByteArray()
            val curStr = String(cur, Charsets.ISO_8859_1)
            sepIdx = curStr.indexOf("\r\n\r\n")
            if (sepIdx >= 0) {
                headerBytes = cur
                break
            }
            if (cur.size > 32768) return null
            val r = input.read(tmp)
            if (r <= 0) return null
            hdrBuf.write(tmp, 0, r)
        }

        val rawHeader = String(headerBytes, 0, sepIdx, Charsets.ISO_8859_1)
        val bodyRemainder = if (headerBytes.size > sepIdx + 4) {
            headerBytes.copyOfRange(sepIdx + 4, headerBytes.size)
        } else {
            ByteArray(0)
        }

        val lines = rawHeader.split("\r\n")
        val reqLine = lines.firstOrNull() ?: return null
        val reqParts = reqLine.split(' ')
        if (reqParts.size < 3) return null
        val method = reqParts[0].uppercase()
        val rawTarget = reqParts[1]
        val version = reqParts[2]

        val proxyAuth = lines.drop(1).firstOrNull {
            it.startsWith("Proxy-Authorization:", ignoreCase = true)
        }?.substringAfter(':')?.trim()

        if (proxyAuth != expectedBasicHeader) {
            val resp407 = (
                "HTTP/1.1 407 Proxy Authentication Required\r\n" +
                    "Proxy-Authenticate: Basic realm=\"oms-local\"\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII)
            output.write(resp407)
            output.flush()
            return null
        }

        if (method == "CONNECT") {
            val colon = rawTarget.lastIndexOf(':')
            val host = if (colon > 0) rawTarget.substring(0, colon).removeSurrounding("[", "]") else rawTarget
            val targetPort = if (colon > 0) rawTarget.substring(colon + 1).toIntOrNull() ?: 443 else 443
            return try {
                output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.flush()
                val initPayload = if (bodyRemainder.isNotEmpty()) {
                    bodyRemainder
                } else {
                    peekInitialClientPayload(client, input)
                }
                router.openRoutedTunnelWithNodeBlocking(host, targetPort, initPayload)
            } catch (_: Throwable) {
                null
            }
        }

        val parsedUri = URI(rawTarget)
        val host = parsedUri.host ?: return null
        val targetPort = if (parsedUri.port > 0) parsedUri.port else 80
        val pathAndQuery = buildString {
            append(parsedUri.rawPath?.ifEmpty { "/" } ?: "/")
            if (!parsedUri.rawQuery.isNullOrEmpty()) {
                append('?')
                append(parsedUri.rawQuery)
            }
        }
        val rebuiltHeaders = buildString {
            append("$method $pathAndQuery $version\r\n")
            for (line in lines.drop(1)) {
                if (!line.startsWith("Proxy-Authorization:", ignoreCase = true) &&
                    !line.startsWith("Proxy-Connection:", ignoreCase = true)
                ) {
                    append(line).append("\r\n")
                }
            }
            append("\r\n")
        }.toByteArray(Charsets.ISO_8859_1)

        val initPayload = rebuiltHeaders + bodyRemainder
        return router.openRoutedTunnelWithNodeBlocking(host, targetPort, initPayload)
    }

    private fun pumpBidirectional(
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        tunnel: ProxyTunnel,
        initialServerChunk: ByteArray
    ) {
        if (initialServerChunk.isNotEmpty()) {
            clientOut.write(initialServerChunk)
            clientOut.flush()
        }
        val c2sDone = AtomicBoolean(false)
        val c2sFuture = executor.submit {
            try {
                val buf = ByteArray(32768)
                while (!client.isClosed) {
                    val r = clientIn.read(buf)
                    if (r <= 0) break
                    tunnel.write(buf.copyOf(r))
                }
            } catch (_: Throwable) {
            } finally {
                c2sDone.set(true)
                runCatching { tunnel.close() }
            }
        }

        try {
            while (!client.isClosed) {
                val chunk = tunnel.read()
                if (chunk.isEmpty()) break
                clientOut.write(chunk)
                clientOut.flush()
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { tunnel.close() }
            runCatching { client.close() }
            if (!c2sDone.get()) c2sFuture.cancel(true)
        }
    }

    override fun close() {
        if (running.compareAndSet(true, false)) {
            runCatching { serverSocket?.close() }
            executor.shutdownNow()
        }
    }
}

/**
 * High-level lifecycle manager for in-app proxy tunnel + OkHttpClient factory + Screen-On TLS Pre-warming.
 */
class OmsProxyManager(
    preferRegions: String = DEFAULT_PREFER_REGIONS,
    maxPreferredLatencyMs: Double = DEFAULT_MAX_PREFER_LATENCY_MS
) : AutoCloseable {
    val router = SmartRegionRouter(
        initialNodes = emptyList(),
        preferRegions = preferRegions,
        maxPreferredLatencyMs = maxPreferredLatencyMs
    )
    val gateway = LoopbackMixedGateway(router)

    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastPrewarmAtMs = AtomicLong(0L)
    private val directFetchClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var sharedProxiedClient: OkHttpClient? = null

    companion object {
        fun isAiGeoBlockResponse(statusCode: Int, bodyText: String): Boolean {
            if (statusCode !in listOf(400, 403, 451)) return false
            val low = bodyText.lowercase()
            return low.contains("user location is not supported") ||
                low.contains("unsupported_country_region_territory") ||
                low.contains("country, region, or territory not supported") ||
                low.contains("region is not supported")
        }
    }

    fun ensureStarted(): Int {
        if (gateway.port == 0) {
            gateway.start(0)
        }
        return gateway.port
    }

    fun hasAvailableNodes(): Boolean = router.allNodesSnapshot().isNotEmpty()

    /**
     * Immediately drops pooled HTTP/1.1 & HTTP/2 connections so node switches or geo-block failovers
     * take effect on the very next request without waiting for OkHttp keep-alive expiry.
     */
    fun evictStaleConnections(onlyNode: ProxyNode? = null) {
        if (onlyNode != null) {
            gateway.closeConnectionsForNode(onlyNode)
        } else {
            gateway.closeAllConnections()
        }
        runCatching { sharedProxiedClient?.connectionPool?.evictAll() }
    }

    /**
     * Marks the active proxy node for `host` as failed or AI-geo-blocked (e.g. when a WebSocket
     * handshake is rejected with Close 1007/1008 or times out waiting for setupComplete) so the very
     * next retry routes through a clean candidate node.
     */
    fun penalizeActiveNodeForHost(host: String, geoBlocked: Boolean = false) {
        val cleanHost = host.trim().lowercase()
        val badNode = gateway.findActiveNodeForHost(cleanHost)
            ?: router.lastUsedNodeForHost(cleanHost)
            ?: router.bestNode
            ?: return
        if (geoBlocked) {
            router.markAiGeoBlocked(badNode)
        } else {
            router.markFailure(badNode)
        }
        evictStaleConnections(badNode)
    }

    fun setRoutingPolicyAndEvict(mode: String, pinnedKey: String? = router.pinnedNodeKey) {
        router.setRoutingPolicy(mode, pinnedKey)
        evictStaleConnections()
    }

    /**
     * Loads bootstrap nodes synced from Mac immediately, then optionally refreshes raw subscription URLs.
     */
    fun applySyncedConfig(
        bootstrapNodes: List<ProxyNode>,
        subscriptionUrls: List<String> = emptyList(),
        refreshSubscriptionsInBackground: Boolean = true
    ) {
        ensureStarted()
        if (bootstrapNodes.isNotEmpty()) {
            router.updateNodes(bootstrapNodes)
            bgScope.launch {
                val prevKey = router.bestNode?.let(SmartRegionRouter::nodeKey)
                runCatching { router.healthCheckAll(concurrency = 12, timeoutMs = 4000) }
                val nextKey = router.bestNode?.let(SmartRegionRouter::nodeKey)
                if (prevKey != nextKey) {
                    evictStaleConnections()
                }
            }
        }
        if (subscriptionUrls.isNotEmpty() && refreshSubscriptionsInBackground) {
            bgScope.launch {
                val prevKey = router.bestNode?.let(SmartRegionRouter::nodeKey)
                val cnt = refreshFromSubscriptionUrls(subscriptionUrls)
                if (cnt > 0) {
                    runCatching { router.healthCheckAll(concurrency = 12, timeoutMs = 4000) }
                    val nextKey = router.bestNode?.let(SmartRegionRouter::nodeKey)
                    if (prevKey != nextKey) {
                        evictStaleConnections()
                    }
                }
            }
        }
    }

    fun refreshFromSubscriptionUrls(urls: List<String>): Int {
        val collected = mutableListOf<ProxyNode>()
        val rules = mutableListOf<String>()
        var clockOffset = 0
        for ((idx, rawUrl) in urls.withIndex()) {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) continue
            val candidates = listOf(trimmed) + SubscriptionParser.extractSubconverterInnerUrls(trimmed)
            for (u in candidates) {
                var loadedFromCandidate = false
                try {
                    val req = Request.Builder()
                        .url(u)
                        .header("User-Agent", "clash.meta/1.18.0")
                        .build()
                    directFetchClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string().orEmpty()
                            val parsed = SubscriptionParser.parseSubscriptionText(
                                body,
                                source = "SUB_${idx + 1}"
                            )
                            if (parsed.nodes.isNotEmpty()) {
                                collected.addAll(parsed.nodes)
                                if (rules.isEmpty() && parsed.rules.isNotEmpty()) {
                                    rules.addAll(parsed.rules)
                                }
                                if (parsed.clockOffsetSec != 0) clockOffset = parsed.clockOffsetSec
                                loadedFromCandidate = true
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
                if (loadedFromCandidate) break
            }
        }
        if (collected.isNotEmpty()) {
            router.updateNodes(collected, rules, clockOffset)
        }
        return collected.size
    }

    private fun ensureBaseProxiedClient(): OkHttpClient {
        sharedProxiedClient?.let { return it }
        synchronized(this) {
            sharedProxiedClient?.let { return it }
            val localPort = ensureStarted()
            val loopbackProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", localPort))
            val selector = object : ProxySelector() {
                override fun select(uri: URI?): List<Proxy> {
                    val host = uri?.host ?: return listOf(Proxy.NO_PROXY)
                    // Bypass proxy completely if no proxy nodes configured OR if domain is domestic/LAN
                    if (!hasAvailableNodes() || router.shouldUseDirect(host)) {
                        return listOf(Proxy.NO_PROXY)
                    }
                    return listOf(loopbackProxy)
                }

                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) {}
            }
            val auth = Authenticator { _, response ->
                if (response.code == 407) {
                    val cred = Credentials.basic(gateway.authUser, gateway.authSecret)
                    if (response.request.header("Proxy-Authorization") == cred) return@Authenticator null
                    response.request.newBuilder()
                        .header("Proxy-Authorization", cred)
                        .build()
                } else null
            }
            val geoFailoverInterceptor = Interceptor { chain ->
                val req = chain.request()
                val reqBody = req.body
                val canReplay = reqBody == null || !reqBody.isOneShot()
                val autoFailoverAllowed = canReplay &&
                    !router.routing.equals("direct", ignoreCase = true) &&
                    !router.routing.equals("manual", ignoreCase = true)
                val maxAttempts = if (autoFailoverAllowed) 4 else 1

                var lastResp: okhttp3.Response? = null
                for (attempt in 1..maxAttempts) {
                    val resp = chain.proceed(req)
                    if (resp.code in listOf(400, 403, 451)) {
                        val preview = runCatching { resp.peekBody(4096).string() }.getOrDefault("")
                        if (isAiGeoBlockResponse(resp.code, preview)) {
                            val host = req.url.host
                            val badNode = gateway.findActiveNodeForHost(host)
                                ?: router.lastUsedNodeForHost(host)
                                ?: router.bestNode
                            if (badNode != null) {
                                router.markAiGeoBlocked(badNode)
                                val hasCleanAlternative = router.orderedCandidates().any { !it.aiGeoBlocked && it.healthy }
                                if (attempt < maxAttempts && hasCleanAlternative) {
                                    resp.close()
                                    evictStaleConnections(badNode)
                                    continue
                                }
                            }
                        }
                    }
                    lastResp = resp
                    break
                }
                checkNotNull(lastResp)
            }
            val client = OkHttpClient.Builder()
                .proxySelector(selector)
                .proxyAuthenticator(auth)
                .addInterceptor(geoFailoverInterceptor)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .build()
            sharedProxiedClient = client
            return client
        }
    }

    fun getOrCreateOkHttpClient(
        connectTimeoutSec: Long = 15,
        readTimeoutSec: Long = 90,
        writeTimeoutSec: Long = 30
    ): OkHttpClient {
        val base = ensureBaseProxiedClient()
        if (connectTimeoutSec == 15L && readTimeoutSec == 90L && writeTimeoutSec == 30L) {
            return base
        }
        return base.newBuilder()
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSec, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Triggered upon screen unlock (`ACTION_USER_PRESENT`), debounced by 3 minutes to save battery.
     * Pre-establishes HTTP/2 TLS sockets to OpenAI & Gemini so the first user turn achieves <800ms TTFT.
     */
    fun prewarmAiEndpointsIfDue(minIntervalMs: Long = 180_000L) {
        if (!hasAvailableNodes()) return
        val now = System.currentTimeMillis()
        val prev = lastPrewarmAtMs.get()
        if (now - prev < minIntervalMs) return
        if (!lastPrewarmAtMs.compareAndSet(prev, now)) return

        bgScope.launch {
            val client = getOrCreateOkHttpClient()
            listOf(
                "https://api.openai.com/v1/models",
                "https://generativelanguage.googleapis.com/"
            ).forEach { url ->
                runCatching {
                    val req = Request.Builder().url(url).head().build()
                    client.newCall(req).execute().close()
                }
            }
        }
    }

    override fun close() {
        evictStaleConnections()
        gateway.close()
    }

    suspend fun runFullSpeedTest(concurrency: Int = 14, timeoutMs: Int = 4000): List<ProxyNodeStatusView> =
        withContext(Dispatchers.IO) {
            val prevKey = router.bestNode?.let(SmartRegionRouter::nodeKey)
            router.healthCheckAll(concurrency = concurrency, timeoutMs = timeoutMs)
            val nextKey = router.bestNode?.let(SmartRegionRouter::nodeKey)
            if (prevKey != nextKey) {
                evictStaleConnections()
            }
            router.getNodesStatusViews()
        }

    suspend fun probeSingleNodeByKey(nodeKey: String, timeoutMs: Int = 4000): Long? =
        withContext(Dispatchers.IO) {
            val target = router.allNodesSnapshot().firstOrNull {
                SmartRegionRouter.nodeKey(it) == nodeKey || it.name == nodeKey
            } ?: return@withContext null
            val ms = router.probeSingleNodeBlocking(target, timeoutMs)
            if (ms.isFinite()) ms.toLong() else null
        }

    suspend fun probeAiEndpointsConnectivity(
        openAiApiKey: String = "",
        openAiBaseUrl: String = "https://api.openai.com/v1",
        geminiApiKey: String = "",
        geminiBaseUrl: String = "https://generativelanguage.googleapis.com/v1beta"
    ): AiConnectivityReport = withContext(Dispatchers.IO) {
        val client = getOrCreateOkHttpClient(connectTimeoutSec = 8, readTimeoutSec = 10)
        data class ProbeOutcome(val ok: Boolean, val ms: Long?, val statusText: String)

        fun probeProvider(label: String, url: String, headerName: String?, headerValue: String?): ProbeOutcome {
            val t0 = System.nanoTime()
            return try {
                val rb = Request.Builder().url(url).get()
                val hasKey = !headerName.isNullOrBlank() && !headerValue.isNullOrBlank()
                if (hasKey) {
                    rb.header(headerName!!, headerValue!!)
                }
                client.newCall(rb.build()).execute().use { resp ->
                    val ms = (System.nanoTime() - t0) / 1_000_000L
                    val body = resp.body?.string().orEmpty()
                    when {
                        isAiGeoBlockResponse(resp.code, body) ->
                            ProbeOutcome(false, ms, "$label: 🚫 节点被锁区 (${ms}ms)")
                        hasKey && resp.isSuccessful ->
                            ProbeOutcome(true, ms, "$label: ✅ ${ms}ms")
                        hasKey && (resp.code == 401 || body.contains("API_KEY_INVALID", ignoreCase = true)) ->
                            ProbeOutcome(false, ms, "$label: ❌ 密钥无效 (${resp.code})")
                        hasKey ->
                            ProbeOutcome(false, ms, "$label: ❌ HTTP ${resp.code}")
                        resp.code in 200..499 ->
                            ProbeOutcome(true, ms, "$label: ✅ 隧道可达 ${ms}ms")
                        else ->
                            ProbeOutcome(false, ms, "$label: ❌ HTTP ${resp.code}")
                    }
                }
            } catch (_: Throwable) {
                ProbeOutcome(false, null, "$label: ❌ 不可达")
            }
        }

        val oaiUrl = "${openAiBaseUrl.ifBlank { "https://api.openai.com/v1" }.trimEnd('/')}/models"
        val gemBase = geminiBaseUrl.ifBlank { "https://generativelanguage.googleapis.com/v1beta" }.trimEnd('/').let {
            if (it.endsWith("/v1beta") || it.endsWith("/v1")) it else "$it/v1beta"
        }
        val gemUrl = "$gemBase/models?pageSize=5"

        val oai = probeProvider(
            label = "OpenAI",
            url = oaiUrl,
            headerName = "Authorization",
            headerValue = openAiApiKey.trim().takeIf { it.isNotEmpty() }?.let { "Bearer $it" }
        )
        val gem = probeProvider(
            label = "Gemini",
            url = gemUrl,
            headerName = "x-goog-api-key",
            headerValue = geminiApiKey.trim().takeIf { it.isNotEmpty() }
        )

        AiConnectivityReport(
            openAiReachable = oai.ok,
            openAiLatencyMs = oai.ms,
            openAiStatusText = oai.statusText,
            geminiReachable = gem.ok,
            geminiLatencyMs = gem.ms,
            geminiStatusText = gem.statusText,
            routeDescription = router.describeSelection()
        )
    }
}

data class AiConnectivityReport(
    val openAiReachable: Boolean,
    val openAiLatencyMs: Long?,
    val openAiStatusText: String = "",
    val geminiReachable: Boolean,
    val geminiLatencyMs: Long?,
    val geminiStatusText: String = "",
    val routeDescription: String
)
