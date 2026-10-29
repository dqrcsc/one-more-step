package com.oms.client.capability

internal object AirPodsMediaActivity {
    // Playback configurations can remain STARTED while Douyin is paused. They are
    // diagnostic evidence only, never an independent reason to claim the headset.
    fun isActive(speaking: Boolean, inCall: Boolean, sessionPlaying: Boolean, musicActive: Boolean): Boolean =
        !speaking && (inCall || sessionPlaying || musicActive)
}
