package com.oms.client.otp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpDeduplicationTest {

    private fun recordFrom(match: OtpMatch, seenAtMs: Long) = RecentOtpRecord(
        code = match.code,
        sender = match.sender,
        sourceApp = match.sourceApp,
        normalizedTail = OtpExtractor.normalizeOtpContextTail(match.fullText, match.code),
        firstSeenAtMs = seenAtMs
    )

    @Test
    fun suppressesDelayedUnlockRepostAndTruncatedTickerUpTo30Minutes() {
        val t0 = 1_795_755_937_076L
        val lockedSms = OtpExtractor.extractOtp(
            rawText = "【小红书】您的验证码是: 192268， 5分钟内有效。请勿向他人泄漏。如非本人操作，可忽略本消息。",
            senderHint = "106945001234",
            sourceApp = "com.android.mms"
        )
        assertNotNull(lockedSms)
        assertEquals("192268", lockedSms!!.code)
        assertEquals("小红书", lockedSms.sender)

        val history = listOf(recordFrom(lockedSms, t0))

        // 9m20s later on screen unlock: HyperOS scrolls truncated ticker without bracket sender
        val unlockTicker = OtpExtractor.extractOtp(
            rawText = "…您的验证码是: 192268， 5分钟内有效。请勿向他人泄漏。如非本人操作，可忽略本消息。",
            senderHint = null,
            sourceApp = "com.android.mms"
        )
        assertNotNull(unlockTicker)
        assertEquals("192268", unlockTicker!!.code)

        val tUnlock = t0 + 560_000L // +9m20s
        assertTrue(OtpExtractor.isDuplicateOfRecent(unlockTicker, history, tUnlock))

        // Real case 2: half-truncated bracket "…能云】" 8m40s later
        val baiduLocked = OtpExtractor.extractOtp(
            rawText = "【百度智能云】验证码为：292545,感谢您使用百度云服务，请填写完整完成验证。",
            senderHint = null,
            sourceApp = "com.android.mms"
        )!!
        val baiduUnlock = OtpExtractor.extractOtp(
            rawText = "…能云】验证码为：292545,感谢您使用百度云服务，请填写完整完成验证。",
            senderHint = "百度智能云",
            sourceApp = "com.android.mms"
        )!!
        assertTrue(
            OtpExtractor.isDuplicateOfRecent(
                baiduUnlock,
                listOf(recordFrom(baiduLocked, t0)),
                t0 + 520_000L
            )
        )
    }

    @Test
    fun allowsIndependentServiceWithSameDigitsAfterFastWindowAndAllowsExpiredAfter30Minutes() {
        val t0 = 1_795_755_937_076L
        val xhs = OtpExtractor.extractOtp(
            rawText = "【小红书】您的验证码是: 192268， 5分钟内有效。",
            senderHint = "小红书",
            sourceApp = "com.android.mms"
        )!!
        val history = listOf(recordFrom(xhs, t0))

        // Within 60s: cross-channel burst is suppressed unconditionally
        val sameBurst = OtpExtractor.extractOtp(
            rawText = "您的动态码 192268，请尽快输入",
            senderHint = "10690000",
            sourceApp = "com.android.mms"
        )!!
        assertTrue(OtpExtractor.isDuplicateOfRecent(sameBurst, history, t0 + 15_000L))

        // After 3 minutes: a genuinely different app/sender ("飞书") with same digits is NOT blocked
        val feishuIndependent = OtpExtractor.extractOtp(
            rawText = "【飞书】验证码：192268，有效期10分钟。如非本人操作，请忽略。",
            senderHint = "飞书",
            sourceApp = "com.ss.android.lark"
        )!!
        assertFalse(OtpExtractor.isDuplicateOfRecent(feishuIndependent, history, t0 + 180_000L))

        // After 31 minutes: even the same sender sending a fresh code with same digits is allowed
        assertFalse(OtpExtractor.isDuplicateOfRecent(xhs, history, t0 + 31 * 60_000L))
    }
}
