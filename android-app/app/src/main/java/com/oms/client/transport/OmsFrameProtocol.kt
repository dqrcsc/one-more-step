package com.oms.client.transport

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Module 01 (Android Side): 8-Byte Binary Framing, UUIDv7, Priority Queue & Ed25519 Auth
 */
object OmsFrameProtocol {
    const val MAGIC_BYTE: Byte = 0x4F // 'O'
    const val PROTOCOL_VERSION: Byte = 0x01
    const val HEADER_BYTES = 8
    const val STREAM_CHUNK_PREFIX_BYTES = 20 // 16B UUID + 4B uint32 chunkIndex

    const val TYPE_CONTROL_JSON_RPC: Int = 0x01
    const val TYPE_STREAM_START: Int = 0x02
    const val TYPE_STREAM_CHUNK: Int = 0x03
    const val TYPE_STREAM_ACK_RESUME: Int = 0x04
    const val TYPE_PING: Int = 0x05
    const val TYPE_PONG: Int = 0x06
    const val TYPE_SCREEN_FRAME: Int = 0x07

    const val SCREEN_FRAME_HEADER_BYTES = 16
    const val CODEC_STATUS_ONLY: Int = 0x00
    const val CODEC_WEBP: Int = 0x01
    const val CODEC_JPEG: Int = 0x02

    const val SCREEN_FLAG_NONE: Int = 0x00
    const val SCREEN_FLAG_SECURE_WINDOW: Int = 0x01
    const val SCREEN_FLAG_SCREEN_OFF: Int = 0x02
    const val SCREEN_FLAG_SCREEN_LOCKED: Int = 0x04
    const val SCREEN_FLAG_KEY_FRAME: Int = 0x08

    private val secureRandom = SecureRandom()

    data class DecodedFrame(
        val version: Int,
        val frameType: Int,
        val flags: Int,
        val payload: ByteArray
    )

    /**
     * Generate RFC 9562 time-ordered UUIDv7
     */
    fun generateUuidV7(nowMs: Long = System.currentTimeMillis()): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        bytes[0] = ((nowMs ushr 40) and 0xFF).toByte()
        bytes[1] = ((nowMs ushr 32) and 0xFF).toByte()
        bytes[2] = ((nowMs ushr 24) and 0xFF).toByte()
        bytes[3] = ((nowMs ushr 16) and 0xFF).toByte()
        bytes[4] = ((nowMs ushr 8) and 0xFF).toByte()
        bytes[5] = (nowMs and 0xFF).toByte()

        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()

        val sb = StringBuilder(36)
        for (i in 0 until 16) {
            if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-')
            sb.append(String.format("%02x", bytes[i].toInt() and 0xFF))
        }
        return sb.toString()
    }

    fun uuidToBytes(uuid: String): ByteArray {
        val hex = uuid.replace("-", "")
        require(hex.length == 32) { "Invalid UUID: $uuid" }
        val out = ByteArray(16)
        for (i in 0 until 16) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    fun bytesToUuid(bytes: ByteArray): String {
        val sb = StringBuilder(36)
        for (i in 0 until 16) {
            if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-')
            sb.append(String.format("%02x", bytes[i].toInt() and 0xFF))
        }
        return sb.toString()
    }

    fun encodeFrame(frameType: Int, payload: ByteArray, flags: Int = 0): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_BYTES + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(MAGIC_BYTE)
        buf.put(PROTOCOL_VERSION)
        buf.put(frameType.toByte())
        buf.put(flags.toByte())
        buf.putInt(payload.size)
        buf.put(payload)
        return buf.array()
    }

    fun encodeJsonRpc(json: JSONObject): ByteArray {
        return encodeFrame(TYPE_CONTROL_JSON_RPC, json.toString().toByteArray(Charsets.UTF_8))
    }

    fun encodeStreamStart(headerJson: JSONObject): ByteArray {
        return encodeFrame(TYPE_STREAM_START, headerJson.toString().toByteArray(Charsets.UTF_8))
    }

    fun encodeStreamChunk(streamId: String, chunkIndex: Int, data: ByteArray): ByteArray {
        val payload = ByteBuffer.allocate(STREAM_CHUNK_PREFIX_BYTES + data.size).order(ByteOrder.BIG_ENDIAN)
        payload.put(uuidToBytes(streamId))
        payload.putInt(chunkIndex)
        payload.put(data)
        return encodeFrame(TYPE_STREAM_CHUNK, payload.array())
    }

    fun encodePong(sentTimestampMs: Long, batteryPct: Int, isCharging: Boolean): ByteArray {
        val payload = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
        payload.putLong(sentTimestampMs)
        payload.put(batteryPct.coerceIn(0, 100).toByte())
        payload.put(if (isCharging) 1.toByte() else 0.toByte())
        return encodeFrame(TYPE_PONG, payload.array())
    }

    /**
     * Encode real-time screen mirror frame (FrameType 0x07):
     * [1B codec][1B flags][2B width][2B height][2B seq][8B timestampMs][raw_image_bytes]
     */
    fun encodeScreenFrame(
        codec: Int,
        flags: Int,
        width: Int,
        height: Int,
        seq: Int,
        timestampMs: Long,
        imageBytes: ByteArray = ByteArray(0)
    ): ByteArray {
        val payload = ByteBuffer.allocate(SCREEN_FRAME_HEADER_BYTES + imageBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
        payload.put((codec and 0xFF).toByte())
        payload.put((flags and 0xFF).toByte())
        payload.putShort((width and 0xFFFF).toShort())
        payload.putShort((height and 0xFFFF).toShort())
        payload.putShort((seq and 0xFFFF).toShort())
        payload.putLong(timestampMs)
        if (imageBytes.isNotEmpty()) {
            payload.put(imageBytes)
        }
        return encodeFrame(TYPE_SCREEN_FRAME, payload.array())
    }

    fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun computePairingHmac(
        ephemeralToken: String,
        pinCode: String,
        phoneId: String,
        phonePubKeyBase64Url: String
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        val keyBytes = "$ephemeralToken:$pinCode".toByteArray(Charsets.UTF_8)
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        val raw = mac.doFinal("$phoneId:$phonePubKeyBase64Url".toByteArray(Charsets.UTF_8))
        return raw.joinToString("") { "%02x".format(it) }
    }

    fun computeAuthHmac(secretKey: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val keyBytes = secretKey.toByteArray(Charsets.UTF_8)
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        val raw = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return raw.joinToString("") { "%02x".format(it) }
    }

    fun deriveWanRoomId(macPubKeyBase64Url: String, phonePubKeyBase64Url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("oms-wan-v1:$macPubKeyBase64Url:$phonePubKeyBase64Url".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }
}

/**
 * Stateful streaming frame decoder supporting fragmented Bluetooth RFCOMM streams & WebSocket frames
 */
class OmsFrameStreamDecoder {
    private val buffer = ByteArrayOutputStream()
    private val maxPayloadBytes = 16 * 1024 * 1024

    private fun validateFrame(condition: Boolean, message: String) {
        if (!condition) { buffer.reset(); throw IllegalArgumentException(message) }
    }

    @Synchronized
    fun push(chunk: ByteArray): List<OmsFrameProtocol.DecodedFrame> {
        if (chunk.isEmpty()) return emptyList()
        validateFrame(buffer.size().toLong() + chunk.size <= 2L * (maxPayloadBytes + OmsFrameProtocol.HEADER_BYTES), "OMS decoder buffer exceeds limit")
        buffer.write(chunk)
        var current = buffer.toByteArray()
        val frames = mutableListOf<OmsFrameProtocol.DecodedFrame>()
        var offset = 0

        while (current.size - offset >= OmsFrameProtocol.HEADER_BYTES) {
            val magic = current[offset]
            validateFrame(magic == OmsFrameProtocol.MAGIC_BYTE, "Invalid OMS frame magic")
            val version = current[offset + 1].toInt() and 0xFF
            validateFrame(version == OmsFrameProtocol.PROTOCOL_VERSION.toInt(), "Unsupported OMS protocol version")
            val frameType = current[offset + 2].toInt() and 0xFF
            val flags = current[offset + 3].toInt() and 0xFF
            val payloadLen = ByteBuffer.wrap(current, offset + 4, 4).order(ByteOrder.BIG_ENDIAN).int

            validateFrame(payloadLen in 0..maxPayloadBytes, "OMS frame payload exceeds limit")
            val totalLen = OmsFrameProtocol.HEADER_BYTES + payloadLen
            if (current.size - offset < totalLen) {
                break
            }

            val payload = current.copyOfRange(offset + OmsFrameProtocol.HEADER_BYTES, offset + totalLen)
            frames.add(OmsFrameProtocol.DecodedFrame(version, frameType, flags, payload))
            offset += totalLen
        }

        if (offset > 0) {
            buffer.reset()
            if (offset < current.size) {
                buffer.write(current, offset, current.size - offset)
            }
        }
        return frames
    }

    @Synchronized
    fun reset() {
        buffer.reset()
    }
}
