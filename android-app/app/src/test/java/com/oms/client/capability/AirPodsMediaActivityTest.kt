package com.oms.client.capability

import org.junit.Assert.*
import org.junit.Test

class AirPodsMediaActivityTest {
    @Test fun douyinPausePlayPauseProducesANewEdgeWithoutAMediaSession() {
        // Real-device contrast: playback configurations stayed STARTED in all
        // three states; only isMusicActive followed the user's pause/play action.
        val samples = listOf(false, true, false, true).map {
            AirPodsMediaActivity.isActive(false, false, false, it)
        }
        assertEquals(listOf(false, true, false, true), samples)
    }

    @Test fun mediaSessionAndCallRemainValidSignals() {
        assertTrue(AirPodsMediaActivity.isActive(false, false, true, false))
        assertTrue(AirPodsMediaActivity.isActive(false, true, false, false))
    }

    @Test fun ownSpeechNeverClaimsEvenWhenSystemMusicFlagIsSet() {
        assertFalse(AirPodsMediaActivity.isActive(true, false, true, true))
        assertFalse(AirPodsMediaActivity.isActive(true, true, false, false))
    }
}
