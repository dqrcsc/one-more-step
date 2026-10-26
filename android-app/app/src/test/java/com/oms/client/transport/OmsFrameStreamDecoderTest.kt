package com.oms.client.transport

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class OmsFrameStreamDecoderTest {
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }
    @Test fun rejectsInvalidVersionAndPayloadThenRecovers() {
        val decoder = OmsFrameStreamDecoder()
        val good = OmsFrameProtocol.encodeFrame(1, byteArrayOf(1, 2))
        rejects { decoder.push(good.copyOf().also { it[1] = 2 }) }
        assertEquals(1, decoder.push(good).size)
        for (length in listOf(-1, 16 * 1024 * 1024 + 1, Int.MAX_VALUE)) {
            val header = good.copyOf(8)
            ByteBuffer.wrap(header).putInt(4, length)
            rejects { decoder.push(header) }
            assertEquals(1, decoder.push(good).size)
        }
    }
    @Test fun fragmentedAndCoalescedFramesDecode() {
        val decoder = OmsFrameStreamDecoder()
        val frame = OmsFrameProtocol.encodeFrame(1, byteArrayOf(1, 2))
        assertTrue(decoder.push(frame.copyOfRange(0, 4)).isEmpty())
        assertEquals(2, decoder.push(frame.copyOfRange(4, frame.size) + frame).size)
    }
}
