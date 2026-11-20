package com.oms.core.proxy

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.ThreadLocalRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

const val MAX_CHUNK_PAYLOAD = 0x3FFF // 16383 bytes

object ProxyCryptoPrimitives {
    private val rng = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { rng.nextBytes(it) }

    fun packNonceLittleEndian(counter: Long): ByteArray {
        val nonce = ByteArray(12)
        ByteBuffer.wrap(nonce).order(ByteOrder.LITTLE_ENDIAN).putLong(0, counter)
        return nonce
    }

    fun aeadEncrypt(isChaCha: Boolean, key: ByteArray, nonce12: ByteArray, plaintext: ByteArray): ByteArray {
        return if (isChaCha) {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce12))
            cipher.doFinal(plaintext)
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce12))
            cipher.doFinal(plaintext)
        }
    }

    fun aeadDecrypt(isChaCha: Boolean, key: ByteArray, nonce12: ByteArray, ciphertextWithTag: ByteArray): ByteArray {
        return if (isChaCha) {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce12))
            cipher.doFinal(ciphertextWithTag)
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce12))
            cipher.doFinal(ciphertextWithTag)
        }
    }

    fun aesEcbEncryptBlock(key: ByteArray, block16: ByteArray): ByteArray {
        require(block16.size == 16) { "AES-ECB block must be 16 bytes" }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(block16)
    }

    fun aesEcbDecryptBlock(key: ByteArray, block16: ByteArray): ByteArray {
        require(block16.size == 16) { "AES-ECB block must be 16 bytes" }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(block16)
    }

    fun evpBytesToKey(password: ByteArray, keyLen: Int): ByteArray {
        val out = ByteArrayOutputStream(keyLen + 16)
        var prev = ByteArray(0)
        while (out.size() < keyLen) {
            val md5 = MessageDigest.getInstance("MD5")
            md5.update(prev)
            md5.update(password)
            prev = md5.digest()
            out.write(prev)
        }
        return out.toByteArray().copyOf(keyLen)
    }

    fun hkdfSha1(masterKey: ByteArray, salt: ByteArray, info: ByteArray, outLen: Int): ByteArray {
        val hmacPrk = Mac.getInstance("HmacSHA1")
        val effectiveSalt = if (salt.isEmpty()) ByteArray(20) else salt
        hmacPrk.init(SecretKeySpec(effectiveSalt, "HmacSHA1"))
        val prk = hmacPrk.doFinal(masterKey)

        val out = ByteArrayOutputStream(outLen + 20)
        var prev = ByteArray(0)
        var counter = 1
        while (out.size() < outLen) {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(prk, "HmacSHA1"))
            mac.update(prev)
            mac.update(info)
            mac.update(counter.toByte())
            prev = mac.doFinal()
            out.write(prev)
            counter++
        }
        return out.toByteArray().copyOf(outLen)
    }

    fun sha224HexAscii(password: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-224").digest(password.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    fun packSocksAddr(host: String, port: Int): ByteArray {
        val clean = host.trim().removeSurrounding("[", "]")
        if (PoisonFreeDnsResolver.isIpLiteral(clean)) {
            try {
                val addr = InetAddress.getByName(clean)
                if (addr is Inet4Address) {
                    return ByteBuffer.allocate(1 + 4 + 2)
                        .put(0x01.toByte())
                        .put(addr.address)
                        .putShort(port.toShort())
                        .array()
                } else if (addr is Inet6Address) {
                    return ByteBuffer.allocate(1 + 16 + 2)
                        .put(0x04.toByte())
                        .put(addr.address)
                        .putShort(port.toShort())
                        .array()
                }
            } catch (_: Throwable) {
            }
        }
        val asciiHost = IDN.toASCII(clean).toByteArray(Charsets.US_ASCII)
        require(asciiHost.size <= 255) { "Domain too long: $clean" }
        return ByteBuffer.allocate(1 + 1 + asciiHost.size + 2)
            .put(0x03.toByte())
            .put(asciiHost.size.toByte())
            .put(asciiHost)
            .putShort(port.toShort())
            .array()
    }
}

open class RawStreamTransport(
    private val input: InputStream,
    private val output: OutputStream,
    private val closeHandle: AutoCloseable,
    private val timeoutUpdater: ((Int) -> Unit)? = null
) : AutoCloseable {
    protected val buf = ByteArrayOutputStream()
    private var bufBytes = ByteArray(0)
    private var bufOffset = 0
    @Volatile private var closed = false

    fun updateReadTimeoutMs(timeoutMs: Int) {
        runCatching { timeoutUpdater?.invoke(timeoutMs) }
    }

    protected fun bufferedSize(): Int = bufBytes.size - bufOffset

    protected fun appendBuffered(data: ByteArray, off: Int = 0, len: Int = data.size) {
        if (len <= 0) return
        val rem = bufferedSize()
        if (rem == 0) {
            bufBytes = data.copyOfRange(off, off + len)
            bufOffset = 0
        } else {
            val merged = ByteArray(rem + len)
            System.arraycopy(bufBytes, bufOffset, merged, 0, rem)
            System.arraycopy(data, off, merged, rem, len)
            bufBytes = merged
            bufOffset = 0
        }
    }

    fun prependBuffered(data: ByteArray) {
        if (data.isEmpty()) return
        val rem = bufferedSize()
        val merged = ByteArray(data.size + rem)
        System.arraycopy(data, 0, merged, 0, data.size)
        if (rem > 0) {
            System.arraycopy(bufBytes, bufOffset, merged, data.size, rem)
        }
        bufBytes = merged
        bufOffset = 0
    }

    protected fun consumeBuffered(maxBytes: Int): ByteArray {
        val take = minOf(maxBytes, bufferedSize())
        val out = bufBytes.copyOfRange(bufOffset, bufOffset + take)
        bufOffset += take
        if (bufOffset >= bufBytes.size) {
            bufBytes = ByteArray(0)
            bufOffset = 0
        }
        return out
    }

    protected fun findBufferedPattern(pattern: ByteArray): Int {
        val rem = bufferedSize()
        if (rem < pattern.size) return -1
        outer@ for (i in bufOffset..(bufBytes.size - pattern.size)) {
            for (j in pattern.indices) {
                if (bufBytes[i + j] != pattern[j]) continue@outer
            }
            return i - bufOffset
        }
        return -1
    }

    open fun writeAndFlush(data: ByteArray) {
        if (data.isEmpty()) return
        output.write(data)
        output.flush()
    }

    protected fun rawReadOnce(temp: ByteArray): Int = input.read(temp)

    open fun readSome(maxBytes: Int = 32768): ByteArray {
        if (bufferedSize() > 0) return consumeBuffered(maxBytes)
        val tmp = ByteArray(maxBytes.coerceAtLeast(1))
        val n = input.read(tmp)
        if (n <= 0) return ByteArray(0)
        return tmp.copyOf(n)
    }

    open fun readExact(n: Int): ByteArray {
        val tmp = ByteArray(16384)
        while (bufferedSize() < n) {
            val r = input.read(tmp)
            if (r <= 0) throw EOFException("Unexpected EOF: expected $n bytes, got ${bufferedSize()}")
            appendBuffered(tmp, 0, r)
        }
        return consumeBuffered(n)
    }

    open fun readExactOrCleanEof(n: Int): ByteArray {
        if (bufferedSize() == 0) {
            val tmp = ByteArray(maxOf(16384, n))
            val r = input.read(tmp)
            if (r <= 0) return ByteArray(0)
            appendBuffered(tmp, 0, r)
        }
        return readExact(n)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { closeHandle.close() }
    }
}

class SimpleObfsHttpTransport(
    input: InputStream,
    output: OutputStream,
    closeHandle: AutoCloseable,
    private val obfsHost: String = "www.bing.com",
    private val obfsUri: String = "/",
    private val port: Int = 80,
    timeoutUpdater: ((Int) -> Unit)? = null
) : RawStreamTransport(input, output, closeHandle, timeoutUpdater) {
    private var reqSent = false
    private var respStripped = false
    private val crlfCrlf = "\r\n\r\n".toByteArray(Charsets.US_ASCII)

    override fun writeAndFlush(data: ByteArray) {
        if (data.isEmpty()) return
        if (!reqSent) {
            reqSent = true
            val hostHdr = if (port == 80 || port == 443) obfsHost else "$obfsHost:$port"
            val wsKey = Base64.getEncoder().encodeToString(ProxyCryptoPrimitives.randomBytes(16))
            val verMajor = ThreadLocalRandom.current().nextInt(70, 89)
            val verMinor = ThreadLocalRandom.current().nextInt(0, 4)
            val hdr = (
                "GET $obfsUri HTTP/1.1\r\n" +
                    "Host: $hostHdr\r\n" +
                    "User-Agent: curl/7.$verMajor.$verMinor\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $wsKey\r\n" +
                    "Content-Length: ${data.size}\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII)
            val combined = ByteArray(hdr.size + data.size)
            System.arraycopy(hdr, 0, combined, 0, hdr.size)
            System.arraycopy(data, 0, combined, hdr.size, data.size)
            super.writeAndFlush(combined)
        } else {
            super.writeAndFlush(data)
        }
    }

    private fun ensureRespStripped() {
        if (respStripped) return
        val tmp = ByteArray(8192)
        while (findBufferedPattern(crlfCrlf) < 0) {
            if (bufferedSize() > 16384) throw IllegalStateException("obfs-http response header exceeded 16KB")
            val r = rawReadOnce(tmp)
            if (r <= 0) throw EOFException("Connection closed before obfs-http response header")
            appendBuffered(tmp, 0, r)
        }
        val endIdx = findBufferedPattern(crlfCrlf) + 4
        consumeBuffered(endIdx)
        respStripped = true
    }

    override fun readSome(maxBytes: Int): ByteArray {
        ensureRespStripped()
        return super.readSome(maxBytes)
    }

    override fun readExact(n: Int): ByteArray {
        ensureRespStripped()
        return super.readExact(n)
    }

    override fun readExactOrCleanEof(n: Int): ByteArray {
        ensureRespStripped()
        return super.readExactOrCleanEof(n)
    }
}

class SimpleObfsTlsTransport(
    input: InputStream,
    output: OutputStream,
    closeHandle: AutoCloseable,
    private val obfsHost: String = "www.bing.com",
    timeoutUpdater: ((Int) -> Unit)? = null
) : RawStreamTransport(input, output, closeHandle, timeoutUpdater) {
    private var reqSent = false
    private var handshakeDone = false
    private var rawBuf = ByteArray(0)
    private var rawOff = 0

    private fun rawRemaining(): Int = rawBuf.size - rawOff

    private fun rawReadExact(n: Int): ByteArray {
        val tmp = ByteArray(16384)
        while (rawRemaining() < n) {
            val r = rawReadOnce(tmp)
            if (r <= 0) {
                if (rawRemaining() == 0) return ByteArray(0)
                throw EOFException("Truncated obfs-tls record")
            }
            val rem = rawRemaining()
            val merged = ByteArray(rem + r)
            if (rem > 0) System.arraycopy(rawBuf, rawOff, merged, 0, rem)
            System.arraycopy(tmp, 0, merged, rem, r)
            rawBuf = merged
            rawOff = 0
        }
        val out = rawBuf.copyOfRange(rawOff, rawOff + n)
        rawOff += n
        if (rawOff >= rawBuf.size) {
            rawBuf = ByteArray(0)
            rawOff = 0
        }
        return out
    }

    private fun buildClientHello(payload: ByteArray): ByteArray {
        val sniBytes = IDN.toASCII(obfsHost.ifBlank { "www.bing.com" }).toByteArray(Charsets.US_ASCII)
        val rand = ByteBuffer.allocate(32)
            .putInt((System.currentTimeMillis() / 1000L).toInt())
            .put(ProxyCryptoPrimitives.randomBytes(28))
            .array()
        val sessionId = ProxyCryptoPrimitives.randomBytes(32)
        val cipherSuites = byteArrayOf(
            0xc0.toByte(), 0x2b, 0xc0.toByte(), 0x2f, 0xcc.toByte(), 0xa9.toByte(), 0xcc.toByte(), 0xa8.toByte(),
            0xcc.toByte(), 0x14, 0xcc.toByte(), 0x13, 0xc0.toByte(), 0x0a, 0xc0.toByte(), 0x14,
            0xc0.toByte(), 0x09, 0xc0.toByte(), 0x13, 0x00, 0x9c.toByte(), 0x00, 0x9d.toByte(),
            0x00, 0x2f, 0x00, 0x35
        )
        val exts = ByteArrayOutputStream()
        // SessionTicket (0x0023)
        exts.write(ByteBuffer.allocate(4).putShort(0x0023).putShort(payload.size.toShort()).array())
        exts.write(payload)
        // SNI (0x0000)
        val sniItem = ByteBuffer.allocate(3 + sniBytes.size).put(0).putShort(sniBytes.size.toShort()).put(sniBytes).array()
        exts.write(ByteBuffer.allocate(6 + sniItem.size).putShort(0x0000).putShort((sniItem.size + 2).toShort()).putShort(sniItem.size.toShort()).put(sniItem).array())
        // Renegotiation / curves / ec_point_formats
        exts.write(byteArrayOf(0xff.toByte(), 0x01, 0x00, 0x01, 0x00))
        exts.write(byteArrayOf(0x00, 0x0a, 0x00, 0x08, 0x00, 0x06, 0x00, 0x1d, 0x00, 0x17, 0x00, 0x18))
        exts.write(byteArrayOf(0x00, 0x0b, 0x00, 0x02, 0x01, 0x00))
        val extBytes = exts.toByteArray()

        val body = ByteArrayOutputStream()
        body.write(byteArrayOf(0x03, 0x03))
        body.write(rand)
        body.write(sessionId.size)
        body.write(sessionId)
        body.write(ByteBuffer.allocate(2).putShort(cipherSuites.size.toShort()).array())
        body.write(cipherSuites)
        body.write(byteArrayOf(0x01, 0x00))
        body.write(ByteBuffer.allocate(2).putShort(extBytes.size.toShort()).array())
        body.write(extBytes)
        val bodyBytes = body.toByteArray()

        val hs = ByteArray(4 + bodyBytes.size)
        hs[0] = 0x01
        hs[1] = ((bodyBytes.size ushr 16) and 0xFF).toByte()
        hs[2] = ((bodyBytes.size ushr 8) and 0xFF).toByte()
        hs[3] = (bodyBytes.size and 0xFF).toByte()
        System.arraycopy(bodyBytes, 0, hs, 4, bodyBytes.size)

        return ByteBuffer.allocate(5 + hs.size)
            .put(0x16.toByte()).put(0x03.toByte()).put(0x01.toByte())
            .putShort(hs.size.toShort())
            .put(hs)
            .array()
    }

    override fun writeAndFlush(data: ByteArray) {
        if (data.isEmpty()) return
        val out = ByteArrayOutputStream()
        var offset = 0
        if (!reqSent) {
            reqSent = true
            val headLen = minOf(8192, data.size)
            out.write(buildClientHello(data.copyOfRange(0, headLen)))
            offset = headLen
        }
        while (offset < data.size) {
            val partLen = minOf(16384, data.size - offset)
            out.write(byteArrayOf(0x17, 0x03, 0x03, ((partLen ushr 8) and 0xFF).toByte(), (partLen and 0xFF).toByte()))
            out.write(data, offset, partLen)
            offset += partLen
        }
        super.writeAndFlush(out.toByteArray())
    }

    private fun fillDecoded(): Boolean {
        while (bufferedSize() == 0) {
            val hdr = rawReadExact(5)
            if (hdr.isEmpty()) return false
            val recType = hdr[0].toInt() and 0xFF
            val recLen = ((hdr[3].toInt() and 0xFF) shl 8) or (hdr[4].toInt() and 0xFF)
            val recBody = rawReadExact(recLen)
            if (recBody.size < recLen) throw EOFException("Truncated obfs-tls record body")
            if (!handshakeDone) {
                if (recType == 0x16) {
                    continue
                }
                if (recType == 0x14) {
                    val nextHdr = rawReadExact(5)
                    if (nextHdr.isEmpty()) return false
                    val nLen = ((nextHdr[3].toInt() and 0xFF) shl 8) or (nextHdr[4].toInt() and 0xFF)
                    val nBody = rawReadExact(nLen)
                    handshakeDone = true
                    if (nBody.isNotEmpty()) appendBuffered(nBody)
                    continue
                }
            }
            if (recType == 0x17) {
                handshakeDone = true
                appendBuffered(recBody)
            }
        }
        return true
    }

    override fun readSome(maxBytes: Int): ByteArray {
        if (bufferedSize() == 0 && !fillDecoded()) return ByteArray(0)
        return consumeBuffered(maxBytes)
    }

    override fun readExact(n: Int): ByteArray {
        while (bufferedSize() < n) {
            if (!fillDecoded()) throw EOFException("Unexpected EOF in obfs-tls")
        }
        return consumeBuffered(n)
    }

    override fun readExactOrCleanEof(n: Int): ByteArray {
        if (bufferedSize() == 0 && !fillDecoded()) return ByteArray(0)
        return readExact(n)
    }
}

interface ProxyTunnel : AutoCloseable {
    fun read(): ByteArray
    fun write(data: ByteArray)
    fun setReadTimeoutMs(timeoutMs: Int) {}
    override fun close()
}

open class DirectTunnel(protected val transport: RawStreamTransport) : ProxyTunnel {
    override fun read(): ByteArray = transport.readSome(32768)
    override fun write(data: ByteArray) = transport.writeAndFlush(data)
    override fun setReadTimeoutMs(timeoutMs: Int) = transport.updateReadTimeoutMs(timeoutMs)
    override fun close() = transport.close()
}

class SS2022Tunnel(
    private val transport: RawStreamTransport,
    val cipherName: String,
    password: String,
    private val clockOffsetSec: Int = 0
) : ProxyTunnel {
    private val keySize: Int
    private val isChaCha: Boolean
    private val psks: List<ByteArray>
    private val upsk: ByteArray
    val reqSalt: ByteArray
    private val cSubkey: ByteArray
    private var cNonce: Long = 0L

    private var sSubkey: ByteArray? = null
    private var sNonce: Long = 0L
    private var respHeaderParsed = false

    init {
        when (cipherName) {
            "2022-blake3-aes-128-gcm" -> { keySize = 16; isChaCha = false }
            "2022-blake3-aes-256-gcm" -> { keySize = 32; isChaCha = false }
            "2022-blake3-chacha20-poly1305" -> { keySize = 32; isChaCha = true }
            else -> throw IllegalArgumentException("Unsupported SS-2022 cipher: $cipherName")
        }
        psks = password.split(':').filter { it.isNotBlank() }.map { SubscriptionParser.decodeBase64Loose(it) }
        require(psks.isNotEmpty()) { "Empty SS-2022 PSK" }
        for (psk in psks) {
            require(psk.size == keySize) {
                "SS-2022 PSK length mismatch for $cipherName: expected $keySize, got ${psk.size}"
            }
        }
        upsk = psks.last()
        reqSalt = ProxyCryptoPrimitives.randomBytes(keySize)
        cSubkey = Blake3Pure.deriveKey("shadowsocks 2022 session subkey", upsk + reqSalt, keySize)
    }

    private fun cSeal(pt: ByteArray): ByteArray {
        val nonce = ProxyCryptoPrimitives.packNonceLittleEndian(cNonce++)
        return ProxyCryptoPrimitives.aeadEncrypt(isChaCha, cSubkey, nonce, pt)
    }

    private fun sOpen(ct: ByteArray): ByteArray {
        val sk = checkNotNull(sSubkey) { "Server subkey not initialized" }
        val nonce = ProxyCryptoPrimitives.packNonceLittleEndian(sNonce++)
        return ProxyCryptoPrimitives.aeadDecrypt(isChaCha, sk, nonce, ct)
    }

    private fun encodeDataChunks(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < data.size) {
            val len = minOf(MAX_CHUNK_PAYLOAD, data.size - i)
            val piece = data.copyOfRange(i, i + len)
            out.write(cSeal(byteArrayOf(((len ushr 8) and 0xFF).toByte(), (len and 0xFF).toByte())))
            out.write(cSeal(piece))
            i += len
        }
        return out.toByteArray()
    }

    fun sendHandshake(targetHost: String, targetPort: Int, initialPayload: ByteArray = ByteArray(0)) {
        val eih = ByteArrayOutputStream()
        if (psks.size > 1) {
            require(!isChaCha) { "SS-2022 EIH requires AES cipher" }
            for (i in 0 until psks.size - 1) {
                val idSubkey = Blake3Pure.deriveKey(
                    "shadowsocks 2022 identity subkey",
                    psks[i] + reqSalt,
                    keySize
                )
                val pskHash = Blake3Pure.hash(psks[i + 1], 16)
                eih.write(ProxyCryptoPrimitives.aesEcbEncryptBlock(idSubkey, pskHash))
            }
        }

        val headLen = minOf(MAX_CHUNK_PAYLOAD, initialPayload.size)
        val head = initialPayload.copyOfRange(0, headLen)
        val tail = if (initialPayload.size > headLen) {
            initialPayload.copyOfRange(headLen, initialPayload.size)
        } else {
            ByteArray(0)
        }

        val padLen = if (head.isEmpty()) {
            ThreadLocalRandom.current().nextInt(32, 97)
        } else {
            ThreadLocalRandom.current().nextInt(0, 33)
        }
        val socksAddr = ProxyCryptoPrimitives.packSocksAddr(targetHost, targetPort)
        val varHdr = ByteBuffer.allocate(socksAddr.size + 2 + padLen + head.size)
            .put(socksAddr)
            .putShort(padLen.toShort())
            .put(ProxyCryptoPrimitives.randomBytes(padLen))
            .put(head)
            .array()

        val nowTs = (System.currentTimeMillis() / 1000L) + clockOffsetSec
        val fixHdr = ByteBuffer.allocate(1 + 8 + 2)
            .put(0.toByte())
            .putLong(nowTs)
            .putShort(varHdr.size.toShort())
            .array()

        val packet = ByteArrayOutputStream()
        packet.write(reqSalt)
        packet.write(eih.toByteArray())
        packet.write(cSeal(fixHdr))
        packet.write(cSeal(varHdr))
        if (tail.isNotEmpty()) {
            packet.write(encodeDataChunks(tail))
        }
        transport.writeAndFlush(packet.toByteArray())
    }

    override fun write(data: ByteArray) {
        if (data.isEmpty()) return
        transport.writeAndFlush(encodeDataChunks(data))
    }

    override fun read(): ByteArray {
        if (!respHeaderParsed) {
            val sSalt = transport.readExact(keySize)
            sSubkey = Blake3Pure.deriveKey("shadowsocks 2022 session subkey", upsk + sSalt, keySize)
            sNonce = 0L

            val respFixLen = 1 + 8 + keySize + 2
            val respFix = sOpen(transport.readExact(respFixLen + 16))
            val rType = respFix[0].toInt() and 0xFF
            val echoSalt = respFix.copyOfRange(9, 9 + keySize)
            val initLen = ((respFix[9 + keySize].toInt() and 0xFF) shl 8) or
                (respFix[10 + keySize].toInt() and 0xFF)

            if (rType != 1 || !echoSalt.contentEquals(reqSalt)) {
                throw SecurityException("SS-2022 server response header verification failed")
            }
            respHeaderParsed = true
            if (initLen > 0) {
                return sOpen(transport.readExact(initLen + 16))
            }
        }

        val encLen = transport.readExactOrCleanEof(2 + 16)
        if (encLen.isEmpty()) return ByteArray(0)
        val decLen = sOpen(encLen)
        val pLen = ((decLen[0].toInt() and 0xFF) shl 8) or (decLen[1].toInt() and 0xFF)
        if (pLen == 0) return ByteArray(0)
        return sOpen(transport.readExact(pLen + 16))
    }

    override fun setReadTimeoutMs(timeoutMs: Int) = transport.updateReadTimeoutMs(timeoutMs)

    override fun close() = transport.close()
}

class SSLegacyAeadTunnel(
    private val transport: RawStreamTransport,
    cipherName: String,
    password: String
) : ProxyTunnel {
    private val keySize: Int
    private val saltSize: Int
    private val isChaCha: Boolean
    private val masterKey: ByteArray
    private val reqSalt: ByteArray
    private val cSubkey: ByteArray
    private var cNonce: Long = 0L

    private var sSubkey: ByteArray? = null
    private var sNonce: Long = 0L

    init {
        when (cipherName) {
            "aes-128-gcm" -> { keySize = 16; saltSize = 16; isChaCha = false }
            "aes-256-gcm" -> { keySize = 32; saltSize = 32; isChaCha = false }
            "chacha20-ietf-poly1305" -> { keySize = 32; saltSize = 32; isChaCha = true }
            else -> throw IllegalArgumentException("Unsupported SS AEAD cipher: $cipherName")
        }
        masterKey = ProxyCryptoPrimitives.evpBytesToKey(password.toByteArray(Charsets.UTF_8), keySize)
        reqSalt = ProxyCryptoPrimitives.randomBytes(saltSize)
        cSubkey = ProxyCryptoPrimitives.hkdfSha1(
            masterKey,
            reqSalt,
            "ss-subkey".toByteArray(Charsets.US_ASCII),
            keySize
        )
    }

    private fun cSeal(pt: ByteArray): ByteArray {
        val n = ProxyCryptoPrimitives.packNonceLittleEndian(cNonce++)
        return ProxyCryptoPrimitives.aeadEncrypt(isChaCha, cSubkey, n, pt)
    }

    private fun sOpen(ct: ByteArray): ByteArray {
        val sk = checkNotNull(sSubkey)
        val n = ProxyCryptoPrimitives.packNonceLittleEndian(sNonce++)
        return ProxyCryptoPrimitives.aeadDecrypt(isChaCha, sk, n, ct)
    }

    private fun encodeChunks(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < data.size) {
            val len = minOf(MAX_CHUNK_PAYLOAD, data.size - i)
            val piece = data.copyOfRange(i, i + len)
            val masked = len and 0x3FFF
            out.write(cSeal(byteArrayOf(((masked ushr 8) and 0xFF).toByte(), (masked and 0xFF).toByte())))
            out.write(cSeal(piece))
            i += len
        }
        return out.toByteArray()
    }

    fun sendHandshake(targetHost: String, targetPort: Int, initialPayload: ByteArray = ByteArray(0)) {
        val addr = ProxyCryptoPrimitives.packSocksAddr(targetHost, targetPort)
        val firstPlain = addr + initialPayload
        transport.writeAndFlush(reqSalt + encodeChunks(firstPlain))
    }

    override fun write(data: ByteArray) {
        if (data.isEmpty()) return
        transport.writeAndFlush(encodeChunks(data))
    }

    override fun read(): ByteArray {
        if (sSubkey == null) {
            val sSalt = transport.readExact(saltSize)
            sSubkey = ProxyCryptoPrimitives.hkdfSha1(
                masterKey,
                sSalt,
                "ss-subkey".toByteArray(Charsets.US_ASCII),
                keySize
            )
            sNonce = 0L
        }
        val encLen = transport.readExactOrCleanEof(2 + 16)
        if (encLen.isEmpty()) return ByteArray(0)
        val decLen = sOpen(encLen)
        val pLen = (((decLen[0].toInt() and 0xFF) shl 8) or (decLen[1].toInt() and 0xFF)) and 0x3FFF
        if (pLen == 0) return ByteArray(0)
        return sOpen(transport.readExact(pLen + 16))
    }

    override fun setReadTimeoutMs(timeoutMs: Int) = transport.updateReadTimeoutMs(timeoutMs)

    override fun close() = transport.close()
}

class TrojanTunnel(transport: RawStreamTransport) : DirectTunnel(transport) {
    fun sendHandshake(password: String, targetHost: String, targetPort: Int, initialPayload: ByteArray = ByteArray(0)) {
        val pwdHex = ProxyCryptoPrimitives.sha224HexAscii(password)
        val addr = ProxyCryptoPrimitives.packSocksAddr(targetHost, targetPort)
        val out = ByteArrayOutputStream(pwdHex.size + 5 + addr.size + initialPayload.size)
        out.write(pwdHex)
        out.write(byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), 0x01))
        out.write(addr)
        out.write(byteArrayOf('\r'.code.toByte(), '\n'.code.toByte()))
        if (initialPayload.isNotEmpty()) out.write(initialPayload)
        transport.writeAndFlush(out.toByteArray())
    }
}

object ProxyTunnelConnector {
    private val defaultSslFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, null, null) }.socketFactory
    }

    private val insecureSslFactory: SSLSocketFactory by lazy {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }.socketFactory
    }

    private fun openConfiguredTcpSocket(hostIp: String, port: Int, connectTimeoutMs: Int, readTimeoutMs: Int): Socket {
        val sock = Socket()
        try {
            sock.tcpNoDelay = true
            sock.keepAlive = true
            sock.soTimeout = readTimeoutMs
            sock.connect(InetSocketAddress(hostIp, port), connectTimeoutMs)
            return sock
        } catch (t: Throwable) {
            runCatching { sock.close() }
            throw t
        }
    }

    fun openDirectTunnel(
        targetHost: String,
        targetPort: Int,
        initialPayload: ByteArray = ByteArray(0),
        connectTimeoutMs: Int = 6000,
        readTimeoutMs: Int = 300000
    ): DirectTunnel {
        val dstIp = PoisonFreeDnsResolver.resolveHostFast(targetHost, connectTimeoutMs)
        val sock = openConfiguredTcpSocket(dstIp, targetPort, connectTimeoutMs, readTimeoutMs)
        val transport = RawStreamTransport(sock.getInputStream(), sock.getOutputStream(), sock) { sock.soTimeout = it }
        if (initialPayload.isNotEmpty()) {
            try {
                transport.writeAndFlush(initialPayload)
            } catch (t: Throwable) {
                transport.close()
                throw t
            }
        }
        return DirectTunnel(transport)
    }

    fun openNodeTunnel(
        node: ProxyNode,
        targetHost: String,
        targetPort: Int,
        initialPayload: ByteArray = ByteArray(0),
        clockOffsetSec: Int = 0,
        connectTimeoutMs: Int = 6000,
        readTimeoutMs: Int = 6000
    ): ProxyTunnel {
        val srvIp = PoisonFreeDnsResolver.resolveHostFast(node.server, connectTimeoutMs)
        val baseSock = openConfiguredTcpSocket(srvIp, node.port, connectTimeoutMs, readTimeoutMs)

        if (node.protocol == "trojan") {
            try {
                val factory = if (node.skipCertVerify) insecureSslFactory else defaultSslFactory
                val sniHost = node.sni.ifBlank { node.server }
                val sslSock = factory.createSocket(baseSock, sniHost, node.port, true) as SSLSocket
                sslSock.soTimeout = readTimeoutMs
                if (!PoisonFreeDnsResolver.isIpLiteral(sniHost)) {
                    val params = sslSock.sslParameters
                    params.serverNames = listOf(SNIHostName(IDN.toASCII(sniHost)))
                    if (!node.skipCertVerify) {
                        params.endpointIdentificationAlgorithm = "HTTPS"
                    }
                    sslSock.sslParameters = params
                }
                sslSock.startHandshake()
                val transport = RawStreamTransport(sslSock.inputStream, sslSock.outputStream, sslSock) {
                    sslSock.soTimeout = it
                }
                val trojan = TrojanTunnel(transport)
                trojan.sendHandshake(node.password, targetHost, targetPort, initialPayload)
                return trojan
            } catch (t: Throwable) {
                runCatching { baseSock.close() }
                throw t
            }
        }

        val updater: (Int) -> Unit = { baseSock.soTimeout = it }
        val transport: RawStreamTransport = if (node.plugin == "obfs") {
            val mode = node.pluginOpts["mode"]?.lowercase() ?: "http"
            val obfsHost = node.pluginOpts["host"] ?: "www.bing.com"
            val obfsUri = node.pluginOpts["path"] ?: "/"
            if (mode == "tls") {
                SimpleObfsTlsTransport(baseSock.getInputStream(), baseSock.getOutputStream(), baseSock, obfsHost, updater)
            } else {
                SimpleObfsHttpTransport(baseSock.getInputStream(), baseSock.getOutputStream(), baseSock, obfsHost, obfsUri, node.port, updater)
            }
        } else {
            RawStreamTransport(baseSock.getInputStream(), baseSock.getOutputStream(), baseSock, updater)
        }

        try {
            when (node.protocol) {
                "ss" -> {
                    return if (node.cipher.startsWith("2022-blake3-")) {
                        SS2022Tunnel(transport, node.cipher, node.password, clockOffsetSec).also {
                            it.sendHandshake(targetHost, targetPort, initialPayload)
                        }
                    } else {
                        SSLegacyAeadTunnel(transport, node.cipher, node.password).also {
                            it.sendHandshake(targetHost, targetPort, initialPayload)
                        }
                    }
                }
                "socks5" -> {
                    if (node.username.isNotEmpty() || node.password.isNotEmpty()) {
                        transport.writeAndFlush(byteArrayOf(0x05, 0x01, 0x02))
                        val resp = transport.readExact(2)
                        if (resp[1].toInt() == 0x02) {
                            val ub = node.username.toByteArray(Charsets.UTF_8)
                            val pb = node.password.toByteArray(Charsets.UTF_8)
                            val authReq = ByteBuffer.allocate(3 + ub.size + pb.size)
                                .put(0x01).put(ub.size.toByte()).put(ub).put(pb.size.toByte()).put(pb).array()
                            transport.writeAndFlush(authReq)
                            val authRes = transport.readExact(2)
                            if (authRes[1].toInt() != 0x00) throw SecurityException("SOCKS5 upstream auth failed")
                        }
                    } else {
                        transport.writeAndFlush(byteArrayOf(0x05, 0x01, 0x00))
                        val resp = transport.readExact(2)
                        if (resp[1].toInt() != 0x00) throw SecurityException("SOCKS5 upstream rejected no-auth")
                    }
                    val req = byteArrayOf(0x05, 0x01, 0x00) + ProxyCryptoPrimitives.packSocksAddr(targetHost, targetPort)
                    transport.writeAndFlush(req)
                    val head = transport.readExact(4)
                    if (head[1].toInt() != 0x00) throw IllegalStateException("SOCKS5 upstream connect error: ${head[1]}")
                    when (head[3].toInt() and 0xFF) {
                        0x01 -> transport.readExact(6)
                        0x03 -> {
                            val dLen = transport.readExact(1)[0].toInt() and 0xFF
                            transport.readExact(dLen + 2)
                        }
                        0x04 -> transport.readExact(18)
                    }
                    if (initialPayload.isNotEmpty()) transport.writeAndFlush(initialPayload)
                    return DirectTunnel(transport)
                }
                "http" -> {
                    val authHdr = if (node.username.isNotEmpty() || node.password.isNotEmpty()) {
                        val cred = Base64.getEncoder().encodeToString("${node.username}:${node.password}".toByteArray(Charsets.UTF_8))
                        "Proxy-Authorization: Basic $cred\r\n"
                    } else ""
                    val connectReq = (
                        "CONNECT $targetHost:$targetPort HTTP/1.1\r\n" +
                            "Host: $targetHost:$targetPort\r\n" +
                            "$authHdr\r\n"
                        ).toByteArray(Charsets.US_ASCII)
                    transport.writeAndFlush(connectReq)
                    val respBuf = ByteArrayOutputStream()
                    while (true) {
                        val chunk = transport.readSome(4096)
                        if (chunk.isEmpty()) throw EOFException("HTTP upstream closed during CONNECT")
                        respBuf.write(chunk)
                        val bytes = respBuf.toByteArray()
                        val text = String(bytes, Charsets.ISO_8859_1)
                        val sep = text.indexOf("\r\n\r\n")
                        if (sep >= 0) {
                            val statusLine = text.lineSequence().firstOrNull().orEmpty()
                            if (!statusLine.contains(" 200 ")) {
                                throw IllegalStateException("HTTP upstream CONNECT failed: $statusLine")
                            }
                            val headerByteLen = sep + 4
                            if (bytes.size > headerByteLen) {
                                transport.prependBuffered(bytes.copyOfRange(headerByteLen, bytes.size))
                            }
                            break
                        }
                    }
                    if (initialPayload.isNotEmpty()) transport.writeAndFlush(initialPayload)
                    return DirectTunnel(transport)
                }
                else -> throw IllegalArgumentException("Unsupported protocol: ${node.protocol}")
            }
        } catch (t: Throwable) {
            transport.close()
            throw t
        }
    }
}
