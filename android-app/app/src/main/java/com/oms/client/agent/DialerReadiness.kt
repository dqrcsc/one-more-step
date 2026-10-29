package com.oms.client.agent

/** ACTION_DIAL returning does not mean the dialer has rendered its number yet. */
internal object DialerReadiness {
    suspend fun awaitVerifiedNumber(
        isLocked: () -> Boolean,
        numberMatches: () -> Boolean,
        checkAuthorization: suspend () -> Unit,
        pause: suspend (Long) -> Unit
    ): Boolean {
        // At most three seconds, also bounded by the enclosing action deadline.
        repeat(31) { attempt ->
            checkAuthorization()
            if (isLocked()) return false
            if (numberMatches()) return true
            if (attempt < 30) pause(100L)
        }
        return false
    }
}
