package com.oms.client.transport

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class DeliveryPolicyTest {
    private val id = "019a1234-1234-7123-8123-123456789abc"
    private fun header(bytes: ByteArray = byteArrayOf(1, 2, 3), chunkSize: Int = 2) = DeliveryPolicy.Header(
        id, bytes.size.toLong(), chunkSize, ((bytes.size + chunkSize - 1) / chunkSize).coerceAtLeast(1),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected validation failure") } catch (_: IllegalArgumentException) { }
    }

    @Test fun contiguousChunksAreVerifiedBeforeCommit() {
        val header = header()
        header.validate()
        assertArrayEquals(byteArrayOf(1, 2, 3), header.assemble(mapOf(1 to byteArrayOf(3), 0 to byteArrayOf(1, 2))))
    }
    @Test fun missingIndexCannotPassWithMatchingMapSize() {
        rejects { header().assemble(mapOf(0 to byteArrayOf(1, 2), 2 to byteArrayOf(3))) }
    }
    @Test fun wrongHashAndWrongChunkLengthAreRejected() {
        rejects { header().assemble(mapOf(0 to byteArrayOf(1, 9), 1 to byteArrayOf(3))) }
        rejects { header().validateChunk(0, byteArrayOf(1)) }
        rejects { header().validateChunk(-1, byteArrayOf()) }
        rejects { header().validateChunk(2, byteArrayOf()) }
    }
    @Test fun oversizedAndMalformedHeadersAreRejected() {
        rejects { header().copy(totalBytes = DeliveryPolicy.MAX_BYTES.toLong() + 1).validate() }
        rejects { header().copy(totalBytes = -1).validate() }
        rejects { header().copy(chunkSize = 65537).validate() }
        rejects { header().copy(chunkSize = 0).validate() }
        rejects { header().copy(totalChunks = 3).validate() }
        rejects { header().copy(totalBytes = 65537, chunkSize = 1, totalChunks = 65537).validate() }
        rejects { header().copy(streamId = "../bad").validate() }
        rejects { header().copy(sha256 = "bad").validate() }
    }
    @Test fun emptyFileRequiresOneEmptyChunkAndCorrectHash() {
        val empty = header(byteArrayOf())
        empty.validate()
        assertArrayEquals(byteArrayOf(), empty.assemble(mapOf(0 to byteArrayOf())))
    }
    @Test fun failedMissingOrForeignAckLeavesItemQueued() {
        assertFalse(DeliveryPolicy.committedInboxAck(false, id, id))
        assertFalse(DeliveryPolicy.committedInboxAck(true, null, id))
        assertFalse(DeliveryPolicy.committedInboxAck(true, "other", id))
        assertTrue(DeliveryPolicy.committedInboxAck(true, id, id))
    }
    @Test fun expiredAndTerminalApprovalsCannotDispatch() {
        assertFalse(DeliveryPolicy.approvalCanDispatch(100, 100, "PENDING_APPROVAL"))
        assertFalse(DeliveryPolicy.approvalCanDispatch(0, 100, "PENDING_APPROVAL"))
        assertFalse(DeliveryPolicy.approvalCanDispatch(200, 100, "APPROVED"))
        assertTrue(DeliveryPolicy.approvalCanDispatch(200, 100, "PENDING_APPROVAL"))
    }
}
