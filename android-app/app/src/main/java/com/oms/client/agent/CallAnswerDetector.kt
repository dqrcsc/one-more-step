package com.oms.client.agent

/**
 * Detects when an outgoing phone call transitions from dialing/ringing to answered (active call timer)
 * by inspecting in-call UI accessibility nodes.
 */
internal object CallAnswerDetector {
    enum class State {
        DIALING_OR_RINGING,
        ANSWERED,
        ENDED
    }

    data class NodeSnapshot(
        val text: String = "",
        val contentDescription: String = "",
        val viewIdResourceName: String = ""
    )

    data class Inspection(
        val state: State,
        val matchedTimer: String? = null
    )

    data class WaitResult(
        val answered: Boolean,
        val waitedMs: Long,
        val reason: String,
        val matchedTimer: String? = null
    )

    private val dialingKeywords = listOf(
        "正在拨号", "正在呼叫", "拨号中", "呼叫中", "对方响铃", "响铃中", "正在振铃", "正在连接", "连接中", "等待对方接听",
        "dialing", "calling", "ringing", "connecting"
    )

    private val endedKeywords = listOf(
        "通话结束", "通话已结束", "已挂断", "对方忙", "用户忙", "无人接听", "无法接通", "暂时无法接通", "对方已挂断", "呼叫失败",
        "call ended", "busy", "declined", "call failed"
    )

    private val connectedKeywords = listOf(
        "通话中", "正在通话", "已接通"
    )

    private val timerIdFragments = listOf(
        "elapsed", "chronometer", "call_time", "calltime", "duration", "call_timer"
    )

    // Matches call duration timer at the start of a newly answered call: 00:00 .. 00:59, 0:00 .. 0:59, 00:00:00 .. 00:00:59,
    // as well as Xiaomi HyperOS Chinese timer labels like "7秒", "SIM卡2 · 7秒", "1分12秒"
    private val standaloneTimerRegex = Regex(
        """^\s*(?:(?:SIM)?卡\d\s*[·|｜]\s*)?(?:通话中\s*|正在通话\s*|已接通\s*|通话时长\s*)?((?:(?:0?0[:：])?0?0[:：][0-5]\d)|(?:[1-5]?\d秒)|(?:\d+分[0-5]?\d秒))(?:\s*HD)?\s*$""",
        RegexOption.IGNORE_CASE
    )

    private val anyDurationRegex = Regex("""(?<!\d)((?:0?\d[:：])?0?\d[:：][0-5]\d)(?!\d)""")

    fun inspect(nodes: List<NodeSnapshot>): Inspection {
        if (nodes.isEmpty()) return Inspection(State.DIALING_OR_RINGING)

        var hasDialingKeyword = false
        var hasEndedKeyword = false
        var matchedTimer: String? = null
        var hasConnectedKeyword = false

        for (node in nodes) {
            val candidates = listOf(node.text.trim(), node.contentDescription.trim()).filter { it.isNotEmpty() }
            val viewIdLower = node.viewIdResourceName.substringAfterLast('/').lowercase()
            val isTimerViewId = timerIdFragments.any { viewIdLower.contains(it) }

            for (raw in candidates) {
                val lower = raw.lowercase()
                if (endedKeywords.any { lower.contains(it) }) {
                    hasEndedKeyword = true
                }
                if (dialingKeywords.any { lower.contains(it) }) {
                    hasDialingKeyword = true
                }
                if (connectedKeywords.any {
                        lower == it || lower.startsWith("$it ") || lower.startsWith("$it：") || lower.startsWith("$it:")
                    }
                ) {
                    hasConnectedKeyword = true
                }
                if (matchedTimer == null) {
                    val standaloneMatch = standaloneTimerRegex.matchEntire(raw)
                    if (standaloneMatch != null) {
                        matchedTimer = standaloneMatch.groupValues[1].replace('：', ':')
                    } else if (isTimerViewId) {
                        val idMatch = anyDurationRegex.find(raw)
                        if (idMatch != null) {
                            matchedTimer = idMatch.groupValues[1].replace('：', ':')
                        }
                    }
                }
            }
        }

        if (hasEndedKeyword) {
            return Inspection(State.ENDED)
        }
        if (hasDialingKeyword) {
            return Inspection(State.DIALING_OR_RINGING)
        }
        if (matchedTimer != null || hasConnectedKeyword) {
            return Inspection(State.ANSWERED, matchedTimer)
        }
        return Inspection(State.DIALING_OR_RINGING)
    }

    suspend fun awaitCallAnswered(
        maxWaitMs: Long,
        pollIntervalMs: Long = 200L,
        isInCall: () -> Boolean?,
        observeInCallNodes: () -> List<NodeSnapshot>,
        checkAuthorization: suspend () -> Unit,
        pause: suspend (Long) -> Unit
    ): WaitResult {
        val boundedMaxMs = maxWaitMs.coerceIn(1_000L, 60_000L)
        var elapsed = 0L
        while (true) {
            checkAuthorization()
            when (isInCall()) {
                false -> return WaitResult(false, elapsed, "call_ended_before_answer")
                null -> return WaitResult(false, elapsed, "call_state_unavailable")
                true -> Unit
            }
            val inspection = inspect(observeInCallNodes())
            when (inspection.state) {
                State.ANSWERED -> return WaitResult(true, elapsed, "call_answered", inspection.matchedTimer)
                State.ENDED -> return WaitResult(false, elapsed, "call_ended_before_answer")
                State.DIALING_OR_RINGING -> Unit
            }
            if (elapsed >= boundedMaxMs) {
                return WaitResult(false, elapsed, "answer_timeout")
            }
            val step = minOf(pollIntervalMs, boundedMaxMs - elapsed).coerceAtLeast(1L)
            pause(step)
            elapsed += step
        }
    }
}
