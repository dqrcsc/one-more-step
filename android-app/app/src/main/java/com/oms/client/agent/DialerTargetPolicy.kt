package com.oms.client.agent

/** Only semantically identified dial controls may be used; never infer a SIM from coordinates. */
internal object DialerTargetPolicy {
    private val callIds = setOf("dialbutton", "dial_button", "dial_button1", "dial_button2", "dialbutton1", "dialbutton2")
    private val speakerIds = setOf("audiobutton", "audio_button", "speaker", "speaker_button", "speakerbutton", "btn_speaker")
    private val hangupIds = setOf(
        "endbutton", "hangup", "end_call", "end_call_button", "endcallbutton",
        "floating_end_call_action_button", "btn_end_call"
    )

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

    fun isSpeakerControl(label: String, resourceId: String): Boolean {
        val text = label.trim().lowercase()
        val id = resourceId.substringAfterLast('/').lowercase()
        return id in speakerIds ||
            text in setOf("免提", "扬声器", "外放", "speaker", "speakerphone") ||
            text.startsWith("免提，") || text.startsWith("免提 ") ||
            text.startsWith("扬声器，") || text.startsWith("扬声器 ")
    }

    fun isHangupControl(label: String, resourceId: String): Boolean {
        val text = label.trim().lowercase()
        val id = resourceId.substringAfterLast('/').lowercase()
        return id in hangupIds ||
            text in setOf("挂断", "挂断电话", "结束通话", "结束", "取消呼叫", "end call", "hang up")
    }
}
