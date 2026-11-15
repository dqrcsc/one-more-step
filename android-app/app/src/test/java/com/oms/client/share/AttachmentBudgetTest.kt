package com.oms.client.share

import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class AttachmentBudgetTest {
    private val sink = object : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }
    private fun sized(size: Long) = object : InputStream() {
        var left = size
        override fun read(): Int = if (left-- > 0) 0 else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left == 0L) return -1
            return minOf(left, len.toLong()).toInt().also { left -= it }
        }
    }
    @Test fun `count includes voice and cannot grow across repeated picker selections`() {
        AttachmentBudget.validateSizes(List(9) { null })
        assertThrows(IllegalArgumentException::class.java) { AttachmentBudget.validateSizes(List(10) { null }) }
    }
    @Test fun `metadata checks per file and aggregate without trusting unknown lengths`() {
        AttachmentBudget.validateSizes(listOf(AttachmentBudget.MAX_FILE_BYTES, null, -1))
        assertThrows(IllegalArgumentException::class.java) { AttachmentBudget.validateSizes(listOf(AttachmentBudget.MAX_FILE_BYTES + 1)) }
        assertThrows(IllegalArgumentException::class.java) { AttachmentBudget.validateSizes(List(3) { 25L * 1024 * 1024 }) }
    }
    @Test fun `unknown length stream enforces exact file boundary`() {
        assertEquals(AttachmentBudget.MAX_FILE_BYTES, AttachmentBudget.CopyBudget().copy(sized(AttachmentBudget.MAX_FILE_BYTES), sink))
        assertThrows(IllegalArgumentException::class.java) {
            AttachmentBudget.CopyBudget().copy(sized(AttachmentBudget.MAX_FILE_BYTES + 1), sink)
        }
    }
    @Test fun `unknown streams share a total budget including voice`() {
        val budget = AttachmentBudget.CopyBudget()
        budget.copy(sized(30L * 1024 * 1024), sink)
        budget.copy(sized(30L * 1024 * 1024), sink)
        assertThrows(IllegalArgumentException::class.java) { budget.copy(sized(4L * 1024 * 1024 + 1), sink) }
    }
    @Test fun `stream read and disk write failures propagate instead of returning transient URI success`() {
        assertThrows(IOException::class.java) {
            AttachmentBudget.CopyBudget().copy(object : InputStream() { override fun read(): Int = throw IOException("grant expired") }, sink)
        }
        assertThrows(IOException::class.java) {
            AttachmentBudget.CopyBudget().copy(sized(1), object : OutputStream() { override fun write(b: Int) { throw IOException("disk full") } })
        }
    }
    @Test fun `stream copy count cannot bypass selection count`() {
        val budget = AttachmentBudget.CopyBudget()
        repeat(9) { budget.copy(sized(0), sink) }
        assertThrows(IllegalArgumentException::class.java) { budget.copy(sized(0), sink) }
    }
}
