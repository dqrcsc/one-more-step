package com.oms.client.agent

/** Only semantically identified dial controls may be used; never infer a SIM from coordinates. */
internal object DialerTargetPolicy {
    private val callIds = setOf("dialbutton", "dial_button", "dial_button1", "dial_button2", "dialbutton1", "dialbutton2")

    fun isCallControl(label: String, resourceId: String): Boolean {
        val text = label.trim().lowercase()
        val id = resourceId.substringAfterLast('/').lowercase()
        return id in callIds || text in setOf("call", "拨打", "拨打电话", "呼叫") ||
            text.startsWith("call using ") ||
            Regex("^(中国)?(电信|联通|移动)(\\s*(sim|卡)\\s*[12])?$", RegexOption.IGNORE_CASE).matches(text)
    }

    fun matchesSim(label: String, resourceId: String, preference: String): Boolean {
        if (preference.isBlank()) return true
        val id = resourceId.substringAfterLast('/').lowercase()
        return when (preference) {
            "1", "2" -> Regex("(?:sim|卡)\\s*" + preference + "(?![0-9])", RegexOption.IGNORE_CASE).containsMatchIn(label) ||
                id in setOf("dial_button$preference", "dialbutton$preference")
            else -> label.contains(preference, ignoreCase = true)
        }
    }
}
