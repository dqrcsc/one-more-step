package com.oms.client.transport

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** OMS v2 pure JCA crypto. Transport must lock seal + write and destroy after a failed write. */
object SecureChannel {
    data class Hello(val v: Int = 2, val macId: String, val nonceMacHex: String, val publicKeyHex: String)
    data class Start(val v: Int = 2, val mode: String, val phoneId: String, val noncePhoneHex: String,
                     val publicKeyHex: String, val proofHex: String)
    data class Ephemeral(val privateKeyHex: String, val publicKeyHex: String)
    data class Established(val session: Session, val pairSecretHex: String, val macProofHex: String)

    private val random = SecureRandom()
    private val canonicalHex = Regex("[0-9a-f]*")
    private val identifier = Regex("[A-Za-z0-9_.-]{1,128}")
    private val curve: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
    }
    const val MAX_INNER_BYTES = 16 * 1024 * 1024 - 24
    private const val MAX_PLAINTEXT_BYTES = 1L shl 30
    private const val MAX_RECORDS = 1L shl 20

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    fun unhex(value: String): ByteArray {
        require(value.length % 2 == 0 && canonicalHex.matches(value)) { "Noncanonical hexadecimal encoding" }
        return ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun fixedHex(value: String, bytes: Int): ByteArray {
        require(value.length == bytes * 2) { "Invalid hexadecimal field length" }
        return unhex(value)
    }

    fun equalHex(expected: String, actual: String?): Boolean {
        if (actual == null || actual.length != 64 || expected.length != 64 ||
            !canonicalHex.matches(actual) || !canonicalHex.matches(expected)) return false
        return MessageDigest.isEqual(unhex(expected), unhex(actual))
    }

    fun randomHex(): String = hex(ByteArray(32).also { random.nextBytes(it) })

    fun generateEphemeral(): Ephemeral {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), random)
        val pair = generator.generateKeyPair()
        val public = pair.public as ECPublicKey
        return Ephemeral(hex(unsigned32((pair.private as ECPrivateKey).s)),
            hex(byteArrayOf(4) + unsigned32(public.w.affineX) + unsigned32(public.w.affineY)))
    }

    private fun unsigned32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        require(value.signum() >= 0 && value.bitLength() <= 256) { "Invalid P-256 integer" }
        val significant = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
        return ByteArray(32 - significant.size) + significant
    }

    private fun privateKey(value: String): PrivateKey {
        val scalar = BigInteger(1, fixedHex(value, 32))
        require(scalar.signum() > 0 && scalar < curve.order) { "Invalid P-256 private scalar" }
        return KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(scalar, curve))
    }

    private fun publicKey(value: String): PublicKey {
        val raw = fixedHex(value, 65)
        require(raw[0] == 4.toByte()) { "P-256 public key must be uncompressed" }
        val point = ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
        val key = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, curve))
        // KeyFactory alone does not reject off-curve points on all providers. ECDH
        // performs the provider's point validation; scalar one is not a secret.
        val validator = KeyAgreement.getInstance("ECDH")
        validator.init(KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger.ONE, curve)))
        validator.doPhase(key, true)
        validator.generateSecret().fill(0)
        return key
    }

    private fun checkedPrivateKey(privateHex: String, ownPublicHex: String): PrivateKey {
        val private = privateKey(privateHex)
        val public = publicKey(ownPublicHex)
        val message = "oms-v2-local-key-check".toByteArray(Charsets.UTF_8)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(private, random)
        signer.update(message)
        val signature = signer.sign()
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(public)
        verifier.update(message)
        require(verifier.verify(signature)) { "Local private key does not match handshake public key" }
        return private
    }

    private fun fields(vararg values: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (value in values) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            out.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
            out.write(bytes)
        }
        return out.toByteArray()
    }

    private fun transcript(hello: Hello, start: Start): ByteArray {
        require(hello.v == 2 && start.v == 2) { "Unsupported secure protocol version" }
        require(start.mode == "pair" || start.mode == "auth") { "Invalid secure handshake mode" }
        require(identifier.matches(hello.macId) && identifier.matches(start.phoneId)) { "Invalid device ID" }
        fixedHex(hello.nonceMacHex, 32)
        fixedHex(start.noncePhoneHex, 32)
        publicKey(hello.publicKeyHex)
        publicKey(start.publicKeyHex)
        return fields("oms-secure-v2", "P256-HKDF-SHA256-AES256GCM", start.mode,
            hello.macId, start.phoneId, hello.nonceMacHex, start.noncePhoneHex,
            hello.publicKeyHex, start.publicKeyHex)
    }

    private fun hmac(key: ByteArray, bytes: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(bytes)
    }

    internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * 32)) { "Invalid HKDF output length" }
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArrayOutputStream()
        var previous = ByteArray(0)
        try {
            for (counter in 1..((length + 31) / 32)) {
                val next = hmac(prk, previous + info + counter.toByte())
                previous.fill(0)
                previous = next
                out.write(next)
            }
            return out.toByteArray().copyOf(length)
        } finally { prk.fill(0); previous.fill(0) }
    }

    fun phoneStart(hello: Hello, mode: String, phoneId: String, pskHex: String,
                   ephemeral: Ephemeral, noncePhoneHex: String = randomHex()): Start {
        val psk = fixedHex(pskHex, 32)
        checkedPrivateKey(ephemeral.privateKeyHex, ephemeral.publicKeyHex)
        val unsigned = Start(mode = mode, phoneId = phoneId, noncePhoneHex = noncePhoneHex,
            publicKeyHex = ephemeral.publicKeyHex, proofHex = "")
        try {
            val hash = MessageDigest.getInstance("SHA-256").digest(transcript(hello, unsigned))
            return unsigned.copy(proofHex = hex(hmac(psk, fields("oms-v2-phone-proof", hex(hash)))))
        } finally { psk.fill(0) }
    }

    fun establish(role: String, hello: Hello, start: Start, pskHex: String, privateKeyHex: String): Established {
        require(role == "mac" || role == "phone") { "Invalid session role" }
        val psk = fixedHex(pskHex, 32)
        var shared = ByteArray(0)
        var root = ByteArray(0)
        try {
            val hash = MessageDigest.getInstance("SHA-256").digest(transcript(hello, start))
            val expected = hex(hmac(psk, fields("oms-v2-phone-proof", hex(hash))))
            require(equalHex(expected, start.proofHex)) { "Invalid phone handshake proof" }
            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(checkedPrivateKey(privateKeyHex, if (role == "mac") hello.publicKeyHex else start.publicKeyHex))
            agreement.doPhase(publicKey(if (role == "mac") start.publicKeyHex else hello.publicKeyHex), true)
            shared = agreement.generateSecret()
            require(shared.size == 32) { "Invalid P-256 shared secret length" }
            root = hkdf(shared, psk, fields("oms-v2-root", hex(hash)), 32)
            fun derive(label: String, length: Int) = hkdf(root, hash, fields("oms-v2", label), length)
            val session = Session(role, derive("mac-key", 32), derive("phone-key", 32),
                derive("mac-iv", 4), derive("phone-iv", 4), hash.copyOf(16))
            return Established(session, hex(derive("pair-secret", 32)),
                hex(hmac(psk, fields("oms-v2-mac-proof", hex(hash)))))
        } finally { psk.fill(0); shared.fill(0); root.fill(0) }
    }

    internal fun gcm(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(input)
    }

    class Session internal constructor(
        role: String, private val macKey: ByteArray, private val phoneKey: ByteArray,
        private val macIv: ByteArray, private val phoneIv: ByteArray, private val sessionId: ByteArray
    ) {
        private val sendDirection: Byte = if (role == "mac") 0 else 1
        private val receiveDirection: Byte = if (role == "mac") 1 else 0
        private var sendSequence = 0L
        private var receiveSequence = 0L
        private var sentBytes = 0L
        private var receivedBytes = 0L
        private var destroyed = false

        private fun validateInner(inner: ByteArray) {
            require(inner.size in 8..MAX_INNER_BYTES && inner[0] == 0x4f.toByte() && inner[1] == 1.toByte()
                && inner[2].toInt() in 1..7 && inner[3] == 0.toByte()) { "Invalid inner OMS frame" }
            require(ByteBuffer.wrap(inner, 4, 4).int.toLong() == inner.size.toLong() - 8) { "Invalid inner frame length" }
        }

        @Synchronized fun seal(inner: ByteArray): ByteArray {
            try {
                check(!destroyed) { "Secure session has been destroyed" }
                validateInner(inner)
                check(sendSequence < MAX_RECORDS && sentBytes + inner.size <= MAX_PLAINTEXT_BYTES) { "Secure send limit reached; reconnect" }
                val sequence = ByteBuffer.allocate(8).putLong(sendSequence).array()
                val header = ByteBuffer.allocate(8).put(0x4f.toByte()).put(1.toByte()).put(0x7e.toByte()).put(0.toByte())
                    .putInt(inner.size + 24).array()
                val key = if (sendDirection == 0.toByte()) macKey else phoneKey
                val iv = if (sendDirection == 0.toByte()) macIv else phoneIv
                val ciphertext = gcm(true, key, iv + sequence, header + sequence + sessionId + sendDirection, inner)
                sendSequence++
                sentBytes += inner.size
                return header + sequence + ciphertext
            } catch (error: Exception) { destroy(); throw error }
        }

        @Synchronized fun open(record: ByteArray): ByteArray {
            try {
                check(!destroyed) { "Secure session has been destroyed" }
                require(record.size in 40..(MAX_INNER_BYTES + 32) && record[0] == 0x4f.toByte()
                    && record[1] == 1.toByte() && record[2] == 0x7e.toByte() && record[3] == 0.toByte()) { "Invalid encrypted OMS record" }
                require(ByteBuffer.wrap(record, 4, 4).int.toLong() == record.size.toLong() - 8) { "Invalid encrypted record length" }
                val sequenceNumber = ByteBuffer.wrap(record, 8, 8).long
                check(sequenceNumber == receiveSequence && receiveSequence < MAX_RECORDS) { "Replay, out-of-order record or receive limit reached" }
                check(receivedBytes + record.size - 32 <= MAX_PLAINTEXT_BYTES) { "Secure receive byte limit reached; reconnect" }
                val sequence = record.copyOfRange(8, 16)
                val key = if (receiveDirection == 0.toByte()) macKey else phoneKey
                val iv = if (receiveDirection == 0.toByte()) macIv else phoneIv
                val inner = gcm(false, key, iv + sequence,
                    record.copyOfRange(0, 8) + sequence + sessionId + receiveDirection,
                    record.copyOfRange(16, record.size))
                validateInner(inner)
                receiveSequence++
                receivedBytes += inner.size
                return inner
            } catch (error: Exception) { destroy(); throw error }
        }

        @Synchronized fun destroy() {
            destroyed = true
            macKey.fill(0); phoneKey.fill(0); macIv.fill(0); phoneIv.fill(0); sessionId.fill(0)
        }
    }
}
