package com.oms.core.proxy

import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.Executors

class NetProxyUnitTest {

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun blake3OfficialVectorsAndMultiChunkTreeMatchPythonReference() {
        assertEquals(
            "af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262",
            Blake3Pure.hash(ByteArray(0)).toHex()
        )
        assertEquals(
            "2d3adedff11b61f14c886e35afa036736dcd87a74d27b5c1510225d0f592e213",
            Blake3Pure.hash(byteArrayOf(0)).toHex()
        )
        // Multi-chunk 2500-byte payload (> 2 * 1024 bytes, exercises parent CV stack merging)
        val msg2500 = ByteArray(2500) { (it % 251).toByte() }
        val h2500 = Blake3Pure.hash(msg2500, 32)
        assertEquals(32, h2500.size)

        val derived = Blake3Pure.deriveKey("shadowsocks 2022 session subkey", ByteArray(64) { 'A'.code.toByte() }, 32)
        assertEquals(32, derived.size)
        // XOF > 64 bytes
        val xof96 = Blake3Pure.hash(msg2500, 96)
        assertEquals(96, xof96.size)
        assertArrayEquals(h2500, xof96.copyOf(32))
    }

    @Test
    fun subscriptionParserHandlesBase64UrisClashYamlSubconverterAndRedaction() {
        val k1 = Base64.getEncoder().encodeToString(ByteArray(32) { '1'.code.toByte() })
        val k2 = Base64.getEncoder().encodeToString(ByteArray(32) { '2'.code.toByte() })
        val userinfo = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("2022-blake3-aes-256-gcm:$k1:$k2".toByteArray(Charsets.UTF_8))
        val ssUri = "ss://$userinfo@ss.example.org:8388/?plugin=obfs-local%3Bobfs%3Dhttp%3Bobfs-host%3Dwww.bing.com#US-West-01"
        val trojanUri = "trojan://supersecret@trojan.example.org:443?sni=cdn.example.org#SG-Node-02"
        val b64Sub = Base64.getEncoder().encodeToString("$ssUri\n$trojanUri\n".toByteArray(Charsets.UTF_8))

        val bundle = SubscriptionParser.parseSubscriptionText(b64Sub, source = "SUB_VPN_1")
        assertEquals(2, bundle.nodes.size)
        assertEquals("US-West-01", bundle.nodes[0].name)
        assertEquals("ss", bundle.nodes[0].protocol)
        assertEquals("obfs", bundle.nodes[0].plugin)
        assertEquals("http", bundle.nodes[0].pluginOpts["mode"])
        assertEquals("SUB_VPN_1", bundle.nodes[0].source)
        assertEquals("trojan", bundle.nodes[1].protocol)
        assertEquals("cdn.example.org", bundle.nodes[1].sni)

        // Ensure ProxyNode.toString() strictly redacts server and password
        val repr = bundle.nodes[1].toString()
        assertFalse(repr.contains("supersecret"))
        assertFalse(repr.contains("trojan.example.org"))

        // Clash YAML flow + block parsing
        val yaml = """
            proxies:
              - {name: "🇺🇸 美国洛杉矶,高速", type: ss, server: 203.0.113.10, port: 8388, cipher: 2022-blake3-aes-256-gcm, password: "$k1:$k2"}
              - name: 🇸🇬 新加坡狮城
                type: trojan
                server: 203.0.113.11
                port: 443
                password: "t-pwd"
                sni: edge.example.com
            rules:
              - DOMAIN-SUFFIX,baidu.com,DIRECT
        """.trimIndent()
        val yamlBundle = SubscriptionParser.parseSubscriptionText(yaml, source = "SUB_VPN_2")
        assertEquals(2, yamlBundle.nodes.size)
        assertEquals("🇺🇸 美国洛杉矶,高速", yamlBundle.nodes[0].name)
        assertEquals(1, yamlBundle.rules.size)

        val wrapped = "https://conv.example.com/sub?target=clash&emoji=true&url=https%3A%2F%2Forigin.example.com%2Fsub%3Ftoken%3Dabc123"
        assertEquals(
            listOf("https://origin.example.com/sub?token=abc123"),
            SubscriptionParser.extractSubconverterInnerUrls(wrapped)
        )
        assertEquals(
            "Failed: <REDACTED_SUB_URL>",
            SubscriptionParser.redactSubscriptionError("Failed: https://origin.example.com/sub?token=abc123")
        )
    }

    @Test
    fun smartRegionRouterPrefersUsSgAndFallsBackCleanly() {
        val hkFast = ProxyNode(name = "🇭🇰 香港 01", protocol = "ss", server = "1.1.1.1", port = 443, latencyMs = 95.0, healthy = true)
        val sgGood = ProxyNode(name = "🇸🇬 新加坡 01", protocol = "ss", server = "1.1.1.2", port = 443, latencyMs = 380.0, healthy = true)
        val usGood = ProxyNode(name = "🇺🇸 美国硅谷 01", protocol = "ss", server = "1.1.1.3", port = 443, latencyMs = 520.0, healthy = true)
        val usSlow = ProxyNode(name = "🇺🇸 美国慢速 02", protocol = "ss", server = "1.1.1.4", port = 443, latencyMs = 4500.0, healthy = true)

        var fakeNow = 10_000L
        val sameTier = SmartRegionRouter(
            initialNodes = listOf(hkFast, sgGood, usGood, usSlow),
            preferRegions = "美国|新加坡",
            maxPreferredLatencyMs = 2500.0,
            nowMsProvider = { fakeNow }
        )
        assertEquals(sgGood, sameTier.bestNode)

        val orderedTier = SmartRegionRouter(
            initialNodes = listOf(hkFast.copy(), sgGood.copy(), usGood.copy(), usSlow.copy()),
            preferRegions = "美国,新加坡",
            maxPreferredLatencyMs = 2500.0,
            nowMsProvider = { fakeNow }
        )
        assertEquals("🇺🇸 美国硅谷 01", orderedTier.bestNode?.name)

        // Mark US & SG failed -> automatically degrades to fastest healthy HK node instead of 4500ms slow US node
        orderedTier.markFailure(orderedTier.bestNode!!) // US fails -> SG becomes best
        assertEquals("🇸🇬 新加坡 01", orderedTier.bestNode?.name)
        orderedTier.markFailure(orderedTier.bestNode!!) // SG fails -> HK (95ms) becomes best!
        assertEquals("🇭🇰 香港 01", orderedTier.bestNode?.name)

        // Routing rules: WeChat & LAN direct; OpenAI & Gemini proxied
        assertTrue(sameTier.shouldUseDirect("ilinkai.weixin.qq.com"))
        assertTrue(sameTier.shouldUseDirect("mp.weixin.qq.com"))
        assertTrue(sameTier.shouldUseDirect("192.168.31.10"))
        assertTrue(sameTier.shouldUseDirect("100.64.5.9"))
        assertFalse(sameTier.shouldUseDirect("api.openai.com"))
        assertFalse(sameTier.shouldUseDirect("generativelanguage.googleapis.com"))
    }

    @Test
    fun ss2022EihAndChaCha20TunnelsEncryptAndVerifyServerResponseHeader() {
        val iPsk = ByteArray(32) { (it + 1).toByte() }
        val uPsk = ByteArray(32) { (it + 65).toByte() }
        val password = "${Base64.getEncoder().encodeToString(iPsk)}:${Base64.getEncoder().encodeToString(uPsk)}"

        val cToSIn = PipedInputStream(65536)
        val cToSOut = PipedOutputStream(cToSIn)
        val sToCIn = PipedInputStream(65536)
        val sToCOut = PipedOutputStream(sToCIn)

        val clientTransport = RawStreamTransport(sToCIn, cToSOut, AutoCloseable {
            cToSOut.close(); sToCIn.close()
        })
        val tunnel = SS2022Tunnel(clientTransport, "2022-blake3-aes-256-gcm", password)
        val initReq = "GET /v1/models HTTP/1.1\r\nHost: api.openai.com\r\n\r\n".toByteArray()
        tunnel.sendHandshake("api.openai.com", 443, initReq)

        // Verify client request packet structure as a simulated SS-2022 server
        val serverReader = RawStreamTransport(cToSIn, sToCOut, AutoCloseable {})
        val recvClientSalt = serverReader.readExact(32)
        assertArrayEquals(tunnel.reqSalt, recvClientSalt)

        // Verify SIP023 EIH block decrypts to blake3_hash(uPsk, 16)
        val eihBlock = serverReader.readExact(16)
        val idSubkey = Blake3Pure.deriveKey("shadowsocks 2022 identity subkey", iPsk + recvClientSalt, 32)
        val decEih = ProxyCryptoPrimitives.aesEcbDecryptBlock(idSubkey, eihBlock)
        assertArrayEquals(Blake3Pure.hash(uPsk, 16), decEih)

        // Decrypt client fixed header + variable header
        val cSubkey = Blake3Pure.deriveKey("shadowsocks 2022 session subkey", uPsk + recvClientSalt, 32)
        val fixHdr = ProxyCryptoPrimitives.aeadDecrypt(
            false, cSubkey, ProxyCryptoPrimitives.packNonceLittleEndian(0), serverReader.readExact(11 + 16)
        )
        assertEquals(0, fixHdr[0].toInt())
        val varLen = ((fixHdr[9].toInt() and 0xFF) shl 8) or (fixHdr[10].toInt() and 0xFF)
        val varHdr = ProxyCryptoPrimitives.aeadDecrypt(
            false, cSubkey, ProxyCryptoPrimitives.packNonceLittleEndian(1), serverReader.readExact(varLen + 16)
        )
        val decodedVarStr = String(varHdr, Charsets.ISO_8859_1)
        assertTrue(decodedVarStr.contains("api.openai.com"))
        assertTrue(decodedVarStr.endsWith("GET /v1/models HTTP/1.1\r\nHost: api.openai.com\r\n\r\n"))

        // Craft valid SS-2022 server response echoing reqSalt + initial HTTP response payload
        val serverSalt = ByteArray(32) { (0xA0 + it).toByte() }
        val sSubkey = Blake3Pure.deriveKey("shadowsocks 2022 session subkey", uPsk + serverSalt, 32)
        val serverInitPayload = "HTTP/1.1 200 OK\r\n\r\nok".toByteArray()
        val respFixPlain = ByteBuffer.allocate(1 + 8 + 32 + 2)
            .put(1.toByte())
            .putLong(System.currentTimeMillis() / 1000L)
            .put(recvClientSalt)
            .putShort(serverInitPayload.size.toShort())
            .array()
        val respPacket = ByteArrayOutputStream().apply {
            write(serverSalt)
            write(ProxyCryptoPrimitives.aeadEncrypt(false, sSubkey, ProxyCryptoPrimitives.packNonceLittleEndian(0), respFixPlain))
            write(ProxyCryptoPrimitives.aeadEncrypt(false, sSubkey, ProxyCryptoPrimitives.packNonceLittleEndian(1), serverInitPayload))
        }.toByteArray()
        sToCOut.write(respPacket)
        sToCOut.flush()

        val firstRead = tunnel.read()
        assertEquals("HTTP/1.1 200 OK\r\n\r\nok", String(firstRead, Charsets.UTF_8))
        tunnel.close()
    }

    @Test
    fun loopbackGatewayEnforcesAuthAndRoutesAuthenticatedOkHttpTraffic() {
        val echoPool = Executors.newSingleThreadExecutor()
        val targetServer = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val targetPort = targetServer.localPort
        echoPool.execute {
            runCatching {
                while (!targetServer.isClosed) {
                    val sock = targetServer.accept()
                    val reqBytes = ByteArray(1024)
                    val n = sock.getInputStream().read(reqBytes)
                    if (n > 0) {
                        val body = "oms-proxied-ok"
                        val httpResp = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                        sock.getOutputStream().write(httpResp.toByteArray(Charsets.US_ASCII))
                        sock.getOutputStream().flush()
                    }
                    sock.close()
                }
            }
        }

        val manager = OmsProxyManager()
        try {
            // Configure a local SOCKS/HTTP upstream or direct rule test via gateway
            val gwPort = manager.ensureStarted()

            // 1. Unauthenticated client hitting loopback gateway MUST receive 407
            val unauthClient = okhttp3.OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", gwPort)))
                .build()
            unauthClient.newCall(Request.Builder().url("http://127.0.0.1:$targetPort/test").build()).execute().use { resp ->
                assertEquals(407, resp.code)
            }

            // 2. Authenticated client via OmsProxyManager succeeds cleanly
            manager.applySyncedConfig(
                bootstrapNodes = listOf(
                    ProxyNode(name = "🇺🇸 US-Test", protocol = "http", server = "127.0.0.1", port = targetPort)
                ),
                refreshSubscriptionsInBackground = false
            )
            val authedProxyClient = okhttp3.OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", gwPort)))
                .proxyAuthenticator { _, response ->
                    if (response.code == 407) {
                        response.request.newBuilder()
                            .header("Proxy-Authorization", okhttp3.Credentials.basic(manager.gateway.authUser, manager.gateway.authSecret))
                            .build()
                    } else null
                }
                .build()
            authedProxyClient.newCall(Request.Builder().url("http://127.0.0.1:$targetPort/hello").build()).execute().use { resp ->
                assertEquals(200, resp.code)
                assertEquals("oms-proxied-ok", resp.body?.string())
            }
        } finally {
            manager.close()
            runCatching { targetServer.close() }
            echoPool.shutdownNow()
        }
    }

    @Test
    fun coldStartAvoidsHkAndGeoBlockInterceptorAutoFailsOverToCleanSgNode() {
        // 1. Cold-start (untested latency = +Infinity): HK node at index 0 MUST NOT beat preferred SG/JP/US nodes
        val hkUntested = ProxyNode(name = "- HK Premium 1 -", protocol = "ss", server = "1.1.1.1", port = 1680)
        val sgUntested = ProxyNode(name = "- SG 新加坡 1-", protocol = "ss", server = "1.1.1.2", port = 1680)
        val coldRouter = SmartRegionRouter(initialNodes = listOf(hkUntested, sgUntested))
        assertEquals("- SG 新加坡 1-", coldRouter.bestNode?.name)

        // 2. End-to-end OkHttp Geo-Block Auto-Failover:
        //    Node 1 (US-4, faster 120ms) returns Google Gemini HTTP 400 "User location is not supported"
        //    Node 2 (SG-1, 220ms) returns HTTP 200 OK
        val pool = Executors.newCachedThreadPool()
        val geoBlockedProxySrv = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val cleanSgProxySrv = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))

        fun serveMockHttpProxy(srv: ServerSocket, innerHttpResponse: String) {
            pool.execute {
                runCatching {
                    while (!srv.isClosed) {
                        val s = srv.accept()
                        val inp = s.getInputStream()
                        val out = s.getOutputStream()
                        val buf = ByteArray(4096)
                        val n1 = inp.read(buf)
                        if (n1 > 0) {
                            val req1 = String(buf, 0, n1, Charsets.ISO_8859_1)
                            if (req1.startsWith("CONNECT ")) {
                                out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                                out.flush()
                                inp.read(buf)
                            }
                            out.write(innerHttpResponse.toByteArray(Charsets.UTF_8))
                            out.flush()
                        }
                        s.close()
                    }
                }
            }
        }

        val errJson = """{"error":{"code":400,"message":"User location is not supported for the API use.","status":"FAILED_PRECONDITION"}}"""
        serveMockHttpProxy(
            geoBlockedProxySrv,
            "HTTP/1.1 400 Bad Request\r\nContent-Type: application/json\r\nContent-Length: ${errJson.length}\r\nConnection: close\r\n\r\n$errJson"
        )
        val okJson = """{"models":[{"name":"models/gemini-4-argon","displayName":"Gemini 4 Argon"}]}"""
        serveMockHttpProxy(
            cleanSgProxySrv,
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${okJson.length}\r\nConnection: close\r\n\r\n$okJson"
        )

        val manager = OmsProxyManager()
        try {
            val fakeUs4 = ProxyNode(
                name = "- US 美国 4 -",
                protocol = "http",
                server = "127.0.0.1",
                port = geoBlockedProxySrv.localPort,
                latencyMs = 120.0,
                healthy = true
            )
            val cleanSg1 = ProxyNode(
                name = "- SG 新加坡 1-",
                protocol = "http",
                server = "127.0.0.1",
                port = cleanSgProxySrv.localPort,
                latencyMs = 220.0,
                healthy = true
            )
            manager.router.updateNodes(listOf(fakeUs4, cleanSg1))
            assertEquals("- US 美国 4 -", manager.router.bestNode?.name)

            val client = manager.getOrCreateOkHttpClient()
            val req = Request.Builder()
                .url("http://generativelanguage.googleapis.com/v1beta/models?pageSize=200")
                .header("x-goog-api-key", "test-key")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                assertEquals(200, resp.code)
                val body = resp.body?.string().orEmpty()
                assertTrue(body.contains("gemini-4-argon"))
            }

            // US-4 must now be flagged aiGeoBlocked and SG-1 promoted to bestNode!
            assertTrue(fakeUs4.aiGeoBlocked)
            assertEquals("- SG 新加坡 1-", manager.router.bestNode?.name)
            val views = manager.router.getNodesStatusViews()
            assertTrue(views.first { it.name == "- US 美国 4 -" }.aiGeoBlocked)
        } finally {
            manager.close()
            runCatching { geoBlockedProxySrv.close() }
            runCatching { cleanSgProxySrv.close() }
            pool.shutdownNow()
        }
    }
}
