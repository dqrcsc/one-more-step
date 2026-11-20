package com.oms.client.transport

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class VerifiedOtaPolicyTest {
    private val bytes = "signed apk fixture".toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun metadata() = VerifiedOtaPolicy.Metadata("http://192.168.1.2/OneMoreStep.apk", sha,
        bytes.size.toLong(), "1.6.4", 41, VerifiedOtaPolicy.Source.AUTHENTICATED_RPC)

    @Test fun `plain HTTP metadata and missing digest never authorize installation`() {
        assertThrows(IllegalArgumentException::class.java) { metadata().copy(source = null).validate() }
        assertThrows(IllegalArgumentException::class.java) { metadata().copy(sha256 = "github_release").validate() }
        assertThrows(IllegalArgumentException::class.java) { metadata().copy(url = "file:///tmp/app.apk").validate() }
        assertThrows(IllegalArgumentException::class.java) { metadata().copy(source = VerifiedOtaPolicy.Source.GITHUB_HTTPS).validate() }
        metadata().copy(url = "https://github.com/dqrcsc/one-more-step/releases/download/v1.6.4/app.apk", source = VerifiedOtaPolicy.Source.GITHUB_HTTPS).validate()
    }
    @Test fun `matching bytes complete but mismatched truncated and empty streams fail`() {
        val output = ByteArrayOutputStream()
        assertEquals(bytes.size.toLong(), VerifiedOtaPolicy.copyAndVerify(bytes.inputStream(), output, metadata()))
        assertArrayEquals(bytes, output.toByteArray())
        assertThrows(IllegalArgumentException::class.java) { VerifiedOtaPolicy.copyAndVerify("wrong".toByteArray().inputStream(), output, metadata().copy(size = 0)) }
        assertThrows(IllegalArgumentException::class.java) { VerifiedOtaPolicy.copyAndVerify(bytes.dropLast(1).toByteArray().inputStream(), output, metadata()) }
        assertThrows(IllegalArgumentException::class.java) { VerifiedOtaPolicy.copyAndVerify(byteArrayOf().inputStream(), output, metadata().copy(size = 0)) }
    }
    @Test fun `declared and streaming oversize are rejected before writing overflow`() {
        assertThrows(IllegalArgumentException::class.java) { metadata().copy(size = VerifiedOtaPolicy.MAX_APK_BYTES + 1).validate() }
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) { VerifiedOtaPolicy.copyAndVerify(bytes.inputStream(), output, metadata().copy(size = 1)) }
        assertEquals(0, output.size())
    }
    @Test fun `unknown length response is still capped without retaining the APK in memory`() {
        val input = object : InputStream() {
            var remaining = VerifiedOtaPolicy.MAX_APK_BYTES + 1
            override fun read(): Int = if (remaining-- > 0) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val n = minOf(remaining, len.toLong()).toInt()
                remaining -= n
                return n
            }
        }
        val sink = object : java.io.OutputStream() {
            override fun write(b: Int) {}
            override fun write(b: ByteArray, off: Int, len: Int) {}
        }
        assertThrows(IllegalArgumentException::class.java) { VerifiedOtaPolicy.copyAndVerify(input, sink, metadata().copy(size = 0)) }
    }

    @Test fun `network and disk failures propagate`() {
        assertThrows(IOException::class.java) { VerifiedOtaPolicy.copyAndVerify(object : InputStream() { override fun read(): Int = throw IOException("network interrupted") }, ByteArrayOutputStream(), metadata()) }
        assertThrows(IOException::class.java) { VerifiedOtaPolicy.copyAndVerify(bytes.inputStream(), object : java.io.OutputStream() { override fun write(b: Int) { throw IOException("disk full") } }, metadata()) }
    }
    private fun check(meta: VerifiedOtaPolicy.Metadata = metadata(), pkg: String = "com.oms.client", version: String = "1.6.4",
                      code: Long = 41, signer: Set<String> = setOf("installed-cert"), installedCode: Long = 40) {
        VerifiedOtaPolicy.validatePackage(meta, pkg, "com.oms.client", version, code, "1.6.3", installedCode, signer, setOf("installed-cert"))
    }
    @Test fun `package version and signing identity are all required`() {
        check()
        assertThrows(IllegalArgumentException::class.java) { check(pkg = "attacker.app") }
        assertThrows(IllegalArgumentException::class.java) { check(version = "1.6.5") }
        assertThrows(IllegalArgumentException::class.java) { check(code = 42) }
        assertThrows(IllegalArgumentException::class.java) { check(installedCode = 42) }
        assertThrows(IllegalArgumentException::class.java) { check(signer = emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { check(signer = setOf("other-cert")) }
        assertThrows(IllegalArgumentException::class.java) { check(signer = setOf("installed-cert", "other-cert")) }
        check(meta = metadata().copy(versionCode = 0)) // GitHub does not invent a build number.
    }

    @Test fun `redirect validation allows trusted GitHub CDN hops and forbids scheme downgrade or untrusted hosts`() {
        val ghMeta = metadata().copy(
            url = "https://github.com/dqrcsc/one-more-step/releases/download/v1.7.1/OneMoreStep-v1.7.1.apk",
            versionName = "1.7.1",
            source = VerifiedOtaPolicy.Source.GITHUB_HTTPS
        )
        val cdnUrl = VerifiedOtaPolicy.validateRedirectTarget(
            ghMeta,
            ghMeta.url,
            "https://objects.githubusercontent.com/github-production-release-asset/12345?X-Amz-Signature=abc"
        )
        assertTrue(cdnUrl.startsWith("https://objects.githubusercontent.com/"))

        // LAN HTTP redirect to GitHub HTTPS -> GitHub CDN HTTPS is allowed
        val lanMeta = metadata()
        val hop1 = VerifiedOtaPolicy.validateRedirectTarget(
            lanMeta,
            lanMeta.url,
            "https://github.com/dqrcsc/one-more-step/releases/download/v1.6.4/OneMoreStep-v1.6.4.apk"
        )
        val hop2 = VerifiedOtaPolicy.validateRedirectTarget(
            lanMeta,
            hop1,
            "https://release-assets.githubusercontent.com/asset.apk"
        )
        assertEquals("https://release-assets.githubusercontent.com/asset.apk", hop2)

        // Scheme downgrade from HTTPS to HTTP is strictly rejected
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedOtaPolicy.validateRedirectTarget(ghMeta, ghMeta.url, "http://objects.githubusercontent.com/asset.apk")
        }
        // Redirect to untrusted third-party host is strictly rejected
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedOtaPolicy.validateRedirectTarget(ghMeta, ghMeta.url, "https://evil.example.com/asset.apk")
        }
    }

    @Test fun `copyAndVerify emits monotonic byte progress through completion`() {
        val progressSnapshots = mutableListOf<Pair<Long, Long>>()
        val output = ByteArrayOutputStream()
        val written = VerifiedOtaPolicy.copyAndVerify(bytes.inputStream(), output, metadata()) { dl, total ->
            progressSnapshots.add(dl to total)
        }
        assertEquals(bytes.size.toLong(), written)
        assertEquals(0L to bytes.size.toLong(), progressSnapshots.first())
        assertEquals(bytes.size.toLong() to bytes.size.toLong(), progressSnapshots.last())
    }
}
