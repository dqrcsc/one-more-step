package com.oms.client.transport

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.BitSet

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
        fun writeChunkToPartFile(partFile: File, receivedChunks: BitSet, index: Int, bytes: ByteArray): Boolean {
            validateChunk(index, bytes)
            val offset = index.toLong() * chunkSize.toLong()
            RandomAccessFile(partFile, "rw").use { raf ->
                if (receivedChunks.get(index)) {
                    require(raf.length() >= offset + bytes.size) { "Corrupted part file on duplicate chunk" }
                    val existing = ByteArray(bytes.size)
                    raf.seek(offset)
                    raf.readFully(existing)
                    require(existing.contentEquals(bytes)) { "Conflicting duplicate chunk" }
                } else {
                    if (bytes.isNotEmpty()) {
                        raf.seek(offset)
                        raf.write(bytes)
                    } else if (raf.length() == 0L) {
                        raf.setLength(0L)
                    }
                    receivedChunks.set(index)
                }
            }
            return receivedChunks.cardinality() == totalChunks
        }
        fun verifyAndSyncPartFile(partFile: File) {
            require(partFile.isFile) { "Part file missing" }
            RandomAccessFile(partFile, "rw").use { raf ->
                require(raf.length() == totalBytes) { "Part file size mismatch" }
                raf.fd.sync()
            }
            val actual = sha256FileHex(partFile)
            require(actual.equals(sha256, ignoreCase = true)) { "SHA-256 mismatch" }
        }
    }

    fun sha256FileHex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        file.inputStream().use { input ->
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_BYTES.toLong()) { "File exceeds 64 MiB" }
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
