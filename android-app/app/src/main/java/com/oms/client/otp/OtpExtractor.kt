package com.oms.client.otp

import com.oms.client.OmsApplication
import java.util.concurrent.ConcurrentHashMap

data class OtpMatch(
    val code: String,
    val sender: String?,
    val fullText: String,
    val sourceApp: String? = null,
    val capturedAtMs: Long = System.currentTimeMillis()
)

/**
 * High-Precision Verification Code (OTP) Extractor & Multi-Source Deduplication Coordinator.
 * Unifies 4 capture channels on Xiaomi HyperOS / MIUI:
 *   1. OmsNotificationListenerService (SMS + WeChat / Feishu / DingTalk / Email notifications)
 *   2. OmsSmsBroadcastReceiver (Direct PDU SMS_RECEIVED broadcast, penetrates lockscreen redaction)
 *   3. SmsContentObserver in OmsCoreForegroundService (content://sms fallback)
 *   4. OmsAccessibilityService (TYPE_NOTIFICATION_STATE_CHANGED zero-config fallback)
 */
object OtpExtractor {

    const val OTP_MAX_AGE_MS = 5 * 60 * 1000L
    private const val DEDUP_WINDOW_MS = 25_000L
    private val recentCodes = ConcurrentHashMap<String, Long>()

    @Volatile
    var latestCapturedOtp: OtpMatch? = null
        private set

    fun getFreshLatestOtp(nowMs: Long = System.currentTimeMillis()): OtpMatch? {
        val current = latestCapturedOtp ?: return null
        return if (nowMs - current.capturedAtMs in 0..OTP_MAX_AGE_MS) current else null
    }

    private val GENERIC_SENDER_TITLES = setOf(
        "短信", "信息", "新消息", "消息", "通知", "验证码",
        "messages", "message", "sms", "mms",
        "com.android.mms", "com.google.android.apps.messaging", "com.miui.notification"
    )

    private val EXCLUDED_ALPHA_WORDS = setOf(
        "code", "your", "from", "with", "this", "that", "have", "will",
        "http", "https", "html", "info", "text", "user", "pass", "true", "false", "null"
    )

    private val TRIGGER_KEYWORD_REGEX = Regex(
        """(?:验证码|校验码|动态码|确认码|安全码|登录码|认证码|提取码|动态密码|随机码|口令码|验证代码|通行码|\b(?:verification\s*code|security\s*code|auth(?:entication)?\s*code|login\s*code|one[-\s]*time\s*(?:password|code)|passcode|otp|pin\s*code)\b|\bcode\s*(?:is|:)\b|\bis\s+your\s+(?:[\w-]+\s+)?code\b)""",
        RegexOption.IGNORE_CASE
    )

    private val CN_BRACKET_REGEX = Regex("""【([^【】]{1,24})】""")
    private val START_ASCII_BRACKET_REGEX = Regex("""^\s*\[([^\[\]]{2,24})]""")
    private val END_ASCII_BRACKET_REGEX = Regex("""\[([^\[\]]{2,24})]\s*$""")

    private val FORWARD_PROXIMITY_REGEX = Regex(
        """(?:验证码|校验码|动态码|确认码|安全码|登录码|认证码|提取码|动态密码|随机码|口令码|验证代码|通行码|verification\s*code|security\s*code|auth(?:entication)?\s*code|login\s*code|one[-\s]*time\s*(?:password|code)|passcode|otp|pin\s*code|\bcode\b)[^0-9A-Za-z]{0,18}(?<![0-9A-Za-z])([0-9]{4,8}|[0-9A-Za-z]{4,8})(?![0-9A-Za-z])""",
        RegexOption.IGNORE_CASE
    )

    private val BACKWARD_PROXIMITY_REGEX = Regex(
        """(?<![0-9A-Za-z])([0-9]{4,8}|[0-9A-Za-z]{4,8})(?![0-9A-Za-z])[^0-9A-Za-z]{0,14}(?:是您的|为您的|是您的验证码|为验证码|验证码|校验码|动态码|确认码|安全码|is\s+your\s+(?:[\w-]+\s+)?(?:code|otp|passcode))""",
        RegexOption.IGNORE_CASE
    )

    private val PURE_DIGITS_REGEX = Regex("""(?<![0-9A-Za-z])(\d{4,8})(?![0-9A-Za-z])""")

    private val ANTI_FRAUD_WARNING_REGEX = Regex(
        """(?:请勿|切勿|不要|谨防|以防|切莫|严禁)[^，。；！？,\n]{0,16}(?:提供|透露|泄露|告知|索要|轻信|转发)[^，。；！？,\n]{0,12}(?:验证码|校验码|动态码|安全码|密码)""",
        RegexOption.IGNORE_CASE
    )

    private val URL_MASK_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val ACCOUNT_TAIL_MASK_REGEX = Regex(
        """(?:尾号|末四位|后四位|尾数|卡号|账号|账户|订单号|流水号|工单号|序列号|编号|手机尾号|银行卡)(?:为|是|:|：|\s)*[0-9A-Za-z]{3,20}""",
        RegexOption.IGNORE_CASE
    )
    private val MOBILE_PHONE_MASK_REGEX = Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""")
    private val HOTLINE_400_MASK_REGEX = Regex("""(?<!\d)(?:400|800)[-\s]?\d{3}[-\s]?\d{4}(?!\d)""")
    private val LANDLINE_MASK_REGEX = Regex("""(?<!\d)\d{3,4}-\d{7,8}(?!\d)""")
    private val CN_DATE_MASK_REGEX = Regex("""20[2-3]\d\s*年(?:\s*\d{1,2}\s*月(?:\s*\d{1,2}\s*日)?)?""")
    private val ISO_DATE_MASK_REGEX = Regex("""20[2-3]\d[-/.]\d{1,2}[-/.]\d{1,2}""")
    private val TIME_MASK_REGEX = Regex("""\b\d{1,2}:\d{2}(?::\d{2})?\b""")
    private val DURATION_MASK_REGEX = Regex(
        """\d+\s*(?:分钟|秒钟?|小时|天|个?工作日|min|mins|minutes|sec|secs|seconds|hr|hrs|hours)\b""",
        RegexOption.IGNORE_CASE
    )
    private val CURRENCY_PREFIX_MASK_REGEX = Regex(
        """(?:人民币|金额|消费|支出|转出|存入|余额|合计|总计|RMB|USD|CNY|\$|¥|￥)\s*\d+(?:\.\d+)?""",
        RegexOption.IGNORE_CASE
    )
    private val CURRENCY_SUFFIX_MASK_REGEX = Regex("""\d+(?:\.\d+)?\s*(?:元|块|万元|美元|港币)""")

    private fun isValidOtpCandidate(raw: String): Boolean {
        if (raw.length < 4 || raw.length > 8) return false
        if (EXCLUDED_ALPHA_WORDS.contains(raw.lowercase())) return false
        if (raw.all { it.isDigit() }) return true
        val digitCount = raw.count { it.isDigit() }
        return digitCount >= 2 && raw.all { it.isLetterOrDigit() }
    }

    fun extractSenderSignature(text: String, senderHint: String?): String? {
        CN_BRACKET_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return it
        }
        START_ASCII_BRACKET_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return it
        }
        END_ASCII_BRACKET_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return it
        }
        if (!senderHint.isNullOrBlank()) {
            val cleaned = senderHint.trim().removePrefix("【").removeSuffix("】").trim()
            if (cleaned.isNotEmpty() &&
                !GENERIC_SENDER_TITLES.contains(cleaned.lowercase()) &&
                cleaned.length <= 32
            ) {
                return cleaned
            }
        }
        return null
    }

    private fun maskInterferingNumbers(text: String): String {
        return text
            .replace(ANTI_FRAUD_WARNING_REGEX, " ")
            .replace(URL_MASK_REGEX, " ")
            .replace(CN_BRACKET_REGEX, " ")
            .replace(ACCOUNT_TAIL_MASK_REGEX, " ")
            .replace(MOBILE_PHONE_MASK_REGEX, " ")
            .replace(HOTLINE_400_MASK_REGEX, " ")
            .replace(LANDLINE_MASK_REGEX, " ")
            .replace(CN_DATE_MASK_REGEX, " ")
            .replace(ISO_DATE_MASK_REGEX, " ")
            .replace(TIME_MASK_REGEX, " ")
            .replace(DURATION_MASK_REGEX, " ")
            .replace(CURRENCY_PREFIX_MASK_REGEX, " ")
            .replace(CURRENCY_SUFFIX_MASK_REGEX, " ")
    }

    fun extractOtp(rawText: String?, senderHint: String? = null, sourceApp: String? = null): OtpMatch? {
        if (rawText.isNullOrBlank()) return null
        val fullText = rawText.trim()
        if (fullText.length < 4 || fullText.length > 600) return null

        val combinedForTrigger = "${senderHint ?: ""} $fullText".replace(ANTI_FRAUD_WARNING_REGEX, " ")
        if (!TRIGGER_KEYWORD_REGEX.containsMatchIn(combinedForTrigger)) {
            return null
        }

        val sender = extractSenderSignature(fullText, senderHint)
        val masked = maskInterferingNumbers(fullText)

        for (m in FORWARD_PROXIMITY_REGEX.findAll(masked)) {
            val candidate = m.groupValues.getOrNull(1) ?: continue
            if (isValidOtpCandidate(candidate)) {
                return OtpMatch(code = candidate, sender = sender, fullText = fullText, sourceApp = sourceApp)
            }
        }

        for (m in BACKWARD_PROXIMITY_REGEX.findAll(masked)) {
            val candidate = m.groupValues.getOrNull(1) ?: continue
            if (isValidOtpCandidate(candidate)) {
                return OtpMatch(code = candidate, sender = sender, fullText = fullText, sourceApp = sourceApp)
            }
        }

        val pureDigits = PURE_DIGITS_REGEX.findAll(masked).map { it.groupValues[1] }.toList()
        if (pureDigits.isNotEmpty()) {
            val preferred = pureDigits.firstOrNull { it.length == 6 }
                ?: pureDigits.firstOrNull { it.length == 4 }
                ?: pureDigits.first()
            return OtpMatch(code = preferred, sender = sender, fullText = fullText, sourceApp = sourceApp)
        }

        return null
    }

    /**
     * Entry point called by NotificationListener, SmsBroadcastReceiver, ContentObserver, or Accessibility.
     * Deduplicates within a 25-second window and immediately dispatches to Mac.
     */
    @Synchronized
    fun onCandidateTextReceived(
        rawText: String?,
        senderHint: String? = null,
        sourcePackage: String? = null
    ): OtpMatch? {
        if (rawText.isNullOrBlank()) return null
        val app = OmsApplication.instance ?: return null
        if (sourcePackage == app.packageName) return null
        if (!app.repository.isAutoSyncOtpEnabled()) return null

        val match = extractOtp(rawText, senderHint, sourcePackage) ?: return null
        val now = System.currentTimeMillis()

        val iter = recentCodes.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (now - entry.value > DEDUP_WINDOW_MS) {
                iter.remove()
            }
        }

        val prevTime = recentCodes[match.code]
        if (prevTime != null && now - prevTime < DEDUP_WINDOW_MS) {
            return null
        }

        recentCodes[match.code] = now
        latestCapturedOtp = match
        app.transportClient.submitOtpToMacOrQueue(match)
        return match
    }
}
