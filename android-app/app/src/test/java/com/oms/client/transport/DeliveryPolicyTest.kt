package com.oms.client.transport

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class DeliveryPolicyTest {
    @org.junit.Test fun `quota rejection is distinguished from retryable network errors`() {
        org.junit.Assert.assertTrue(DeliveryPolicy.isInboxQuotaError("ERR_INBOX_QUOTA: 收件箱已满"))
        org.junit.Assert.assertFalse(DeliveryPolicy.isInboxQuotaError("Host acknowledgement timed out"))
        org.junit.Assert.assertFalse(DeliveryPolicy.isInboxQuotaError(null))
    }

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

    @Test fun diskBackedPartFileWritesOutOfOrderHandlesDuplicatesAndVerifiesSha256() {
        val payload = byteArrayOf(10, 20, 30, 40, 50)
        val hdr = header(payload, chunkSize = 2)
        hdr.validate()
        val tempFile = java.io.File.createTempFile("oms_stream_test_", ".part")
        try {
            val received = java.util.BitSet(hdr.totalChunks)
            assertFalse(hdr.writeChunkToPartFile(tempFile, received, 2, byteArrayOf(50)))
            assertFalse(hdr.writeChunkToPartFile(tempFile, received, 0, byteArrayOf(10, 20)))
            // Idempotent duplicate chunk succeeds; conflicting duplicate chunk is rejected
            assertFalse(hdr.writeChunkToPartFile(tempFile, received, 0, byteArrayOf(10, 20)))
            rejects { hdr.writeChunkToPartFile(tempFile, received, 0, byteArrayOf(10, 99)) }
            assertTrue(hdr.writeChunkToPartFile(tempFile, received, 1, byteArrayOf(30, 40)))
            hdr.verifyAndSyncPartFile(tempFile)
            assertArrayEquals(payload, tempFile.readBytes())
        } finally {
            tempFile.delete()
        }
    }
}
