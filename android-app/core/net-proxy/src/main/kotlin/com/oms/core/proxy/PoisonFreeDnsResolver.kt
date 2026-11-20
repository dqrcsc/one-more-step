package com.oms.core.proxy

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.IDN
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

/**
 * Memory-cached DNS resolver with Fake-IP (198.18.0.0/15) / loopback hijack detection
 * and direct UDP DNS fallback (223.5.5.5 / 119.29.29.29).
 */
object PoisonFreeDnsResolver {
    private const val DNS_TTL_MS = 600_000L
    private val PUBLIC_DNS_SERVERS = listOf("223.5.5.5", "119.29.29.29")

    private data class CacheEntry(val ip: String, val expiresAtMs: Long)

    private val dnsCache = ConcurrentHashMap<String, CacheEntry>()

    fun isFakeOrPoisonedIp(ipStr: String): Boolean {
        val parts = ipStr.trim().split('.')
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        if (a == 198 && (b == 18 || b == 19)) return true
        if (a == 0 || a == 127) return true
        return false
    }

    fun isIpLiteral(host: String): Boolean {
        val clean = host.trim().removeSurrounding("[", "]")
        if (clean.contains(':')) return true
        val parts = clean.split('.')
        if (parts.size != 4) return false
        return parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
    }

    private fun skipDnsName(packet: ByteArray, startIdx: Int): Int {
        var idx = startIdx
        while (idx < packet.size) {
            val length = packet[idx].toInt() and 0xFF
            if (length == 0) return idx + 1
            if ((length and 0xC0) == 0xC0) return idx + 2
            idx += 1 + length
        }
        return idx
    }

    fun queryUdpDnsA(domain: String, timeoutMs: Int = 2000): String? {
        val cleanDomain = domain.trim().trimEnd('.')
        if (cleanDomain.isEmpty()) return null
        val tid = ThreadLocalRandom.current().nextInt(1, 0xFFFF)
        val out = ByteArrayOutputStream(128)
        out.write((tid ushr 8) and 0xFF)
        out.write(tid and 0xFF)
        // Flags: standard recursive query 0x0100, QDCOUNT=1, ANCOUNT=0, NSCOUNT=0, ARCOUNT=0
        out.write(byteArrayOf(0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        for (label in cleanDomain.split('.')) {
            if (label.isEmpty()) continue
            val ascii = IDN.toASCII(label).toByteArray(Charsets.US_ASCII)
            if (ascii.size > 63) return null
            out.write(ascii.size)
            out.write(ascii)
        }
        out.write(0x00)
        // QTYPE = A (1), QCLASS = IN (1)
        out.write(byteArrayOf(0x00, 0x01, 0x00, 0x01))
        val queryBytes = out.toByteArray()

        for (dnsServer in PUBLIC_DNS_SERVERS) {
            try {
                DatagramSocket().use { sock ->
                    sock.soTimeout = timeoutMs
                    val req = DatagramPacket(queryBytes, queryBytes.size, InetSocketAddress(dnsServer, 53))
                    sock.send(req)
                    val buf = ByteArray(2048)
                    val resp = DatagramPacket(buf, buf.size)
                    sock.receive(resp)
                    val len = resp.length
                    if (len < 12) return@use
                    val respTid = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
                    if (respTid != tid) return@use
                    val qdCount = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
                    val anCount = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
                    var idx = 12
                    for (q in 0 until qdCount) {
                        idx = skipDnsName(buf, idx) + 4
                    }
                    for (a in 0 until anCount) {
                        idx = skipDnsName(buf, idx)
                        if (idx + 10 > len) break
                        val rtype = ((buf[idx].toInt() and 0xFF) shl 8) or (buf[idx + 1].toInt() and 0xFF)
                        val rdlen = ((buf[idx + 8].toInt() and 0xFF) shl 8) or (buf[idx + 9].toInt() and 0xFF)
                        idx += 10
                        if (idx + rdlen > len) break
                        if (rtype == 1 && rdlen == 4) {
                            val ip = "${buf[idx].toInt() and 0xFF}.${buf[idx + 1].toInt() and 0xFF}." +
                                "${buf[idx + 2].toInt() and 0xFF}.${buf[idx + 3].toInt() and 0xFF}"
                            if (!isFakeOrPoisonedIp(ip)) {
                                return ip
                            }
                        }
                        idx += rdlen
                    }
                }
            } catch (_: Throwable) {
                // Try next public DNS server
            }
        }
        return null
    }

    fun resolveHostFast(host: String, timeoutMs: Int = 2500): String {
        val clean = host.trim().removeSurrounding("[", "]")
        if (clean.equals("localhost", ignoreCase = true) || clean.equals("ip6-localhost", ignoreCase = true)) {
            return "127.0.0.1"
        }
        if (isIpLiteral(clean)) {
            return clean
        }

        val now = System.currentTimeMillis()
        dnsCache[clean]?.let { cached ->
            if (cached.expiresAtMs > now) return cached.ip
        }

        var resolvedIp: String? = null
        try {
            val addrs = InetAddress.getAllByName(clean)
            val v4 = addrs.firstOrNull { it is Inet4Address }?.hostAddress
            if (v4 != null && !isFakeOrPoisonedIp(v4)) {
                resolvedIp = v4
            } else if (v4 == null) {
                val anyAddr = addrs.firstOrNull()?.hostAddress
                if (anyAddr != null && !isFakeOrPoisonedIp(anyAddr)) {
                    resolvedIp = anyAddr
                }
            }
        } catch (_: Throwable) {
        }

        if (resolvedIp == null) {
            resolvedIp = queryUdpDnsA(clean, timeoutMs)
        }

        val finalHost = resolvedIp ?: clean
        dnsCache[clean] = CacheEntry(finalHost, now + DNS_TTL_MS)
        return finalHost
    }

    fun clearCacheForTest() {
        dnsCache.clear()
    }
}
