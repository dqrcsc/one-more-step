package com.oms.client.transport

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class SecureChannelTest {
    private val psk = "11".repeat(32)
    private val mac = SecureChannel.Ephemeral("00".repeat(31) + "01",
        "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296" +
            "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5")
    private val phone = SecureChannel.Ephemeral("00".repeat(31) + "02",
        "047cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc47669978" +
            "07775510db8ed040293d9ac69f7430dbba7dade63ce982299e04b79d227873d1")
    private val hello = SecureChannel.Hello(macId = "mac_test", nonceMacHex = "22".repeat(32), publicKeyHex = mac.publicKeyHex)
    private fun start() = SecureChannel.phoneStart(hello, "pair", "phone_test", psk, phone, "33".repeat(32))
    private fun session(role: String, helloInput: SecureChannel.Hello = hello, startInput: SecureChannel.Start = start()) =
        SecureChannel.establish(role, helloInput, startInput, psk, if (role == "mac") mac.privateKeyHex else phone.privateKeyHex).session
    private fun frame(text: String = "synthetic secret"): ByteArray {
        val body = text.toByteArray()
        return ByteBuffer.allocate(8 + body.size).put(0x4f.toByte()).put(1.toByte()).put(1.toByte())
            .put(0.toByte()).putInt(body.size).put(body).array()
    }
    private fun denied(block: () -> Unit) {
        try { block(); fail("Invalid secure operation was accepted") } catch (_: Exception) { }
    }

    // Expected bytes copied from shared-protocol/test-vectors/secure-v2.json.
    // JCA derives all values independently; neither side uses the other's crypto code.
    @Test fun typescriptFixtureMatchesJcaProofsDerivationAndBothCiphertexts() {
        val s = start()
        assertEquals("8b8f9ab67686c46d6c4ea255917525358d221a781bbf6a29f634457b23b7bbd7", s.proofHex)
        val m = SecureChannel.establish("mac", hello, s, psk, mac.privateKeyHex)
        val p = SecureChannel.establish("phone", hello, s, psk, phone.privateKeyHex)
        assertEquals("edc4bb3635ad9a57561a134dd157b71be8e46401fd0e905cb0191da087d0fd66", m.pairSecretHex)
        assertEquals("edc4bb3635ad9a57561a134dd157b71be8e46401fd0e905cb0191da087d0fd66", p.pairSecretHex)
        assertEquals("139e6f2730a660fd3b29963f80d723d4d6cfb8b13ddd818134918fda5b610b12", m.macProofHex)
        assertEquals("139e6f2730a660fd3b29963f80d723d4d6cfb8b13ddd818134918fda5b610b12", p.macProofHex)
        val inner = SecureChannel.unhex("4f0101000000004a7b226a736f6e727063223a22322e30222c226964223a22766563746f72222c22726573756c74223a7b226f6b223a747275652c226d657373616765223a227365637572652d7632227d7d")
        val macRecord = SecureChannel.unhex("4f017e000000006a0000000000000000a2a17b1ea14765f5a87863a98de8e972322c9f9cc41e729d11dcf27d58792bfd3d18e2d4de314fcc87f1f477083e57a47cdbc354142270f4ee83343df32d259228a5eee8d0d2c1746e3bc5514bd6dff8d7099d4ab82338ec2f93c68b398b79515452")
        val phoneRecord = SecureChannel.unhex("4f017e000000006a00000000000000003e1329009b6113ec9ed0699460171174c15b7d0dd8f1dfadccc598ce7fb3fdd58e52f71d677084ac8b38067750609f1bf7f4cd54532a22e5cebcee8c704772745a60b289e609ed63741c73c9e3f5c308cc8a96ab2cb06a5e1cf276ad1ff2437cfe0a")
        assertArrayEquals(macRecord, m.session.seal(inner))
        assertArrayEquals(phoneRecord, p.session.seal(inner))
        assertArrayEquals(inner, p.session.open(macRecord))
        assertArrayEquals(inner, m.session.open(phoneRecord))
    }

    @Test fun rfc5869Sha256TestCaseOne() {
        val output = SecureChannel.hkdf(ByteArray(22) { 0x0b },
            SecureChannel.unhex("000102030405060708090a0b0c"), SecureChannel.unhex("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", SecureChannel.hex(output))
    }

    @Test fun nistAes256GcmKnownAnswer() {
        val expected = SecureChannel.unhex("cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919")
        assertArrayEquals(expected, SecureChannel.gcm(true, ByteArray(32), ByteArray(12), ByteArray(0), ByteArray(16)))
        assertArrayEquals(ByteArray(16), SecureChannel.gcm(false, ByteArray(32), ByteArray(12), ByteArray(0), expected))
    }

    @Test fun independentlyGeneratedEphemeralsCommunicateInBothDirections() {
        val m = SecureChannel.generateEphemeral()
        val p = SecureChannel.generateEphemeral()
        val h = SecureChannel.Hello(macId = "mac_live", nonceMacHex = SecureChannel.randomHex(), publicKeyHex = m.publicKeyHex)
        val s = SecureChannel.phoneStart(h, "auth", "phone_live", psk, p)
        val macSession = SecureChannel.establish("mac", h, s, psk, m.privateKeyHex)
        val phoneSession = SecureChannel.establish("phone", h, s, psk, p.privateKeyHex)
        assertEquals(macSession.macProofHex, phoneSession.macProofHex)
        assertEquals(macSession.pairSecretHex, phoneSession.pairSecretHex)
        repeat(3) {
            assertArrayEquals(frame("mac$it"), phoneSession.session.open(macSession.session.seal(frame("mac$it"))))
            assertArrayEquals(frame("phone$it"), macSession.session.open(phoneSession.session.seal(frame("phone$it"))))
        }
    }

    @Test fun wrongPskProofTranscriptAndInvalidPointsFailHandshake() {
        val s = start()
        denied { SecureChannel.establish("mac", hello, s, "12".repeat(32), mac.privateKeyHex) }
        denied { SecureChannel.establish("mac", hello, s.copy(proofHex = "00".repeat(32)), psk, mac.privateKeyHex) }
        denied { SecureChannel.establish("mac", hello.copy(macId = "changed"), s, psk, mac.privateKeyHex) }
        denied { SecureChannel.establish("mac", hello, s.copy(mode = "auth"), psk, mac.privateKeyHex) }
        denied { SecureChannel.phoneStart(hello.copy(publicKeyHex = "04" + "00".repeat(64)), "pair", "phone_test", psk, phone) }
        denied { SecureChannel.phoneStart(hello.copy(publicKeyHex = "04" + "ff".repeat(64)), "pair", "phone_test", psk, phone) }
        denied { SecureChannel.phoneStart(hello.copy(publicKeyHex = "02" + mac.publicKeyHex.substring(2, 66)), "pair", "phone_test", psk, phone) }
        denied { SecureChannel.phoneStart(hello.copy(v = 1), "pair", "phone_test", psk, phone) }
        denied { SecureChannel.phoneStart(hello, "pair", "phone:invalid", psk, phone) }
        denied { SecureChannel.phoneStart(hello.copy(nonceMacHex = "AB".repeat(32)), "pair", "phone_test", psk, phone) }
        denied { SecureChannel.establish("mac", hello, s, psk, "00".repeat(32)) }
        denied { SecureChannel.phoneStart(hello, "pair", "phone_test", psk, phone.copy(privateKeyHex = mac.privateKeyHex)) }
        denied { SecureChannel.establish("mac", hello, s, psk, phone.privateKeyHex) }
        denied { SecureChannel.establish("phone", hello, s, psk, mac.privateKeyHex) }
        assertTrue(SecureChannel.equalHex(psk, psk))
        assertFalse(SecureChannel.equalHex("ab", "ab"))
        assertFalse(SecureChannel.equalHex("ab", "AB"))
        assertFalse(SecureChannel.equalHex("ab", "ab00"))
        assertFalse(SecureChannel.equalHex("ab", null))
    }

    @Test fun replayReorderingReflectionAndCrossSessionRecordsDestroyReceiver() {
        val sender = session("mac")
        val first = sender.seal(frame("first"))
        val second = sender.seal(frame("second"))
        val replayReceiver = session("phone")
        replayReceiver.open(first)
        denied { replayReceiver.open(first) }
        denied { replayReceiver.open(second) }
        val skippedReceiver = session("phone")
        denied { skippedReceiver.open(second) }
        denied { skippedReceiver.open(first) }
        val reflected = session("mac")
        denied { reflected.open(first) }
        denied { reflected.seal(frame()) }
        val newHello = hello.copy(nonceMacHex = "44".repeat(32))
        val newStart = SecureChannel.phoneStart(newHello, "pair", "phone_test", psk, phone, "33".repeat(32))
        val other = session("phone", newHello, newStart)
        denied { other.open(first) }
    }

    @Test fun headerCiphertextTagAndTruncationTamperAreFailClosed() {
        val record = session("mac").seal(frame())
        for (position in listOf(0, 1, 2, 3, 7, 8, 16, record.lastIndex)) {
            val receiver = session("phone")
            val corrupt = record.copyOf()
            corrupt[position] = (corrupt[position].toInt() xor 1).toByte()
            denied { receiver.open(corrupt) }
            denied { receiver.open(record) }
        }
        for (length in listOf(0, 7, 16, record.size - 1)) {
            val receiver = session("phone")
            denied { receiver.open(record.copyOf(length)) }
            denied { receiver.open(record) }
        }
    }

    @Test fun malformedNestedPlaintextAndDestroyedContextsAreRejected() {
        val valid = frame()
        val invalid = listOf(ByteArray(0), valid.copyOf().also { it[2] = 0x7e },
            valid.copyOf().also { it[3] = 1 }, valid.copyOf().also { it[7] = 0 },
            ByteArray(SecureChannel.MAX_INNER_BYTES + 1))
        for (bytes in invalid) {
            val sender = session("mac")
            denied { sender.seal(bytes) }
            denied { sender.seal(valid) }
        }
        val receiver = session("phone")
        denied { receiver.open(valid) }
        val destroyed = session("mac")
        destroyed.destroy()
        denied { destroyed.seal(valid) }
        denied { destroyed.open(session("phone").seal(valid)) }
    }

    @Test fun usageLimitsCannotResetKeysOrCounters() {
        fun setCounter(session: SecureChannel.Session, name: String, value: Long) {
            session.javaClass.getDeclaredField(name).apply { isAccessible = true }.setLong(session, value)
        }
        val sender = session("mac")
        setCounter(sender, "sendSequence", 1L shl 20)
        denied { sender.seal(frame()) }
        val byteSender = session("mac")
        setCounter(byteSender, "sentBytes", (1L shl 30) - 7)
        denied { byteSender.seal(frame("")) }
        val byteReceiver = session("phone")
        setCounter(byteReceiver, "receivedBytes", (1L shl 30) - 7)
        denied { byteReceiver.open(session("mac").seal(frame(""))) }
        val sequenceReceiver = session("phone")
        setCounter(sequenceReceiver, "receiveSequence", 1L shl 20)
        denied { sequenceReceiver.open(session("mac").seal(frame())) }
    }
}
