package com.oms.client.agent

/** A completed UI click is not evidence that the phone call ended. */
internal object HangupCallVerifier {
    data class Result(val hungUp: Boolean, val requested: Boolean, val reason: String)

    suspend fun execute(
        isInCall: () -> Boolean?,
        clickVerifiedTarget: () -> Boolean,
        awaitStateUpdate: suspend () -> Unit
    ): Result {
        when (isInCall()) {
            null -> return Result(false, false, "call_state_unavailable")
            false -> return Result(false, false, "no_active_call")
            true -> Unit
        }
        if (!clickVerifiedTarget()) return Result(false, false, "hangup_target_unavailable")
        repeat(10) {
            awaitStateUpdate()
            when (isInCall()) {
                false -> return Result(true, true, "call_ended")
                null -> return Result(false, true, "call_state_unavailable")
                true -> Unit
            }
        }
        return Result(false, true, "hangup_not_confirmed")
    }
}
