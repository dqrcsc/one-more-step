package com.oms.client.cockpit

import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** A generation changes even when the user switches A -> B -> A. */
class MacRequestScope {
    data class Ticket(val macId: String, val generation: Long)
    private var owner = ""
    private var generation = 0L

    @Synchronized fun switchTo(macId: String): Boolean {
        if (owner == macId) return false
        owner = macId
        generation++
        return true
    }
    @Synchronized fun ticket() = Ticket(owner, generation)
    @Synchronized fun accepts(ticket: Ticket) = ticket == Ticket(owner, generation)

    companion object {
        fun key(raw: String): String = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        fun directory(root: File, macId: String): File = File(root, key(macId)).apply { mkdirs() }
    }
}

/** Includes a sentinel byte to reject chunked or misleading Content-Length responses. */
fun InputStream.readBounded(limit: Int, declaredLength: Long = -1): ByteArray {
    require(declaredLength <= limit) { "Image exceeds size limit" }
    val output = ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val n = read(buffer, 0, minOf(buffer.size, limit - total + 1))
        if (n < 0) return output.toByteArray()
        if (n == 0) continue
        total += n
        require(total <= limit) { "Image exceeds size limit" }
        output.write(buffer, 0, n)
    }
}
