package com.oms.client.transport

import java.security.MessageDigest
import java.io.InputStream
import java.io.ByteArrayOutputStream

/** Pure validation shared by transport and JVM regression tests. */
object DeliveryPolicy {
    const val MAX_BYTES = 64 * 1024 * 1024
    const val MAX_CHUNK_SIZE = 65536
    const val MAX_TRANSFERS = 16
    const val MAX_CHUNKS = 65536
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val sha = Regex("[0-9a-fA-F]{64}")

    data class Header(val streamId: String, val totalBytes: Long, val chunkSize: Int, val totalChunks: Int, val sha256: String) {
        fun validate() {
            require(uuid.matches(streamId)) { "Invalid stream UUID" }
            require(totalBytes in 0..MAX_BYTES.toLong()) { "Stream size exceeds 64 MiB" }
            require(chunkSize in 1..MAX_CHUNK_SIZE) { "Invalid chunk size" }
            require(totalChunks in 1..MAX_CHUNKS) { "Too many chunks" }
            require(totalChunks.toLong() == ((totalBytes + chunkSize - 1) / chunkSize).coerceAtLeast(1)) { "Invalid chunk count" }
            require(sha.matches(sha256)) { "Invalid SHA-256" }
        }
        fun validateChunk(index: Int, bytes: ByteArray) {
            require(index in 0 until totalChunks) { "Invalid chunk index" }
            val expected = minOf(chunkSize.toLong(), totalBytes - index.toLong() * chunkSize).toInt()
            require(bytes.size == expected) { "Invalid chunk length" }
        }
        fun assemble(chunks: Map<Int, ByteArray>): ByteArray {
            require(chunks.size == totalChunks) { "Missing chunks" }
            val result = ByteArray(totalBytes.toInt())
            for (index in 0 until totalChunks) {
                val chunk = requireNotNull(chunks[index]) { "Missing chunk $index" }
                validateChunk(index, chunk)
                chunk.copyInto(result, index * chunkSize)
            }
            val actual = MessageDigest.getInstance("SHA-256").digest(result).joinToString("") { "%02x".format(it) }
            require(actual.equals(sha256, true)) { "SHA-256 mismatch" }
            return result
        }
    }

    fun committedInboxAck(ack: Boolean, savedId: String?, itemId: String): Boolean = ack && savedId == itemId
    fun approvalCanDispatch(expiresAtMs: Long, nowMs: Long, status: String): Boolean =
        status == "PENDING_APPROVAL" && expiresAtMs > nowMs

    fun readBounded(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return out.toByteArray()
            require(out.size().toLong() + count <= MAX_BYTES) { "File exceeds 64 MiB" }
            out.write(buffer, 0, count)
        }
    }
}
