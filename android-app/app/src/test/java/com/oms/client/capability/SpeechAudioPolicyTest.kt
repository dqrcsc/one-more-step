package com.oms.client.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechAudioPolicyTest {
    @Test
    fun threeTierRoutingResolvesHeadsetVsSpeakerCorrectly() {
        // Tier 1: FORCE_SPEAKER_ALARM always forces speaker + alarm stream + hardware stop
        val forceWithHeadset = SpeechAudioPolicy.resolveRouting(
            SpeechAudioPolicy.FORCE_SPEAKER_ALARM,
            externalHeadsetConnected = true
        )
        assertTrue(forceWithHeadset.useAlarmStream)
        assertTrue(forceWithHeadset.forceBuiltInSpeaker)
        assertTrue(forceWithHeadset.enableHardwareStop)

        // Tier 2: ALARM_RESPECT_HEADSET uses private media stream when headphones/AirPods are connected
        val respectWithHeadset = SpeechAudioPolicy.resolveRouting(
            SpeechAudioPolicy.ALARM_RESPECT_HEADSET,
            externalHeadsetConnected = true
        )
        assertFalse(respectWithHeadset.useAlarmStream)
        assertFalse(respectWithHeadset.forceBuiltInSpeaker)
        assertFalse(respectWithHeadset.enableHardwareStop)

        // Tier 2 without headset pierces silent mode via STREAM_ALARM without forcing speaker device
        val respectWithoutHeadset = SpeechAudioPolicy.resolveRouting(
            SpeechAudioPolicy.ALARM_RESPECT_HEADSET,
            externalHeadsetConnected = false
        )
        assertTrue(respectWithoutHeadset.useAlarmStream)
        assertFalse(respectWithoutHeadset.forceBuiltInSpeaker)
        assertTrue(respectWithoutHeadset.enableHardwareStop)

        // Tier 3: MEDIA_COMMUTE always uses media stream and never stops on lock/volume keys
        for (headset in listOf(false, true)) {
            val commute = SpeechAudioPolicy.resolveRouting(SpeechAudioPolicy.MEDIA_COMMUTE, headset)
            assertFalse(commute.useAlarmStream)
            assertFalse(commute.forceBuiltInSpeaker)
            assertFalse(commute.enableHardwareStop)
        }
    }

    @Test
    fun wireNameResolutionMaintainsBackwardsCompatibility() {
        assertEquals(
            SpeechAudioPolicy.FORCE_SPEAKER_ALARM,
            SpeechAudioPolicy.fromWireName("force_speaker_alarm")
        )
        assertEquals(
            SpeechAudioPolicy.ALARM_RESPECT_HEADSET,
            SpeechAudioPolicy.fromWireName("alarm_respect_headset")
        )
        assertEquals(
            SpeechAudioPolicy.MEDIA_COMMUTE,
            SpeechAudioPolicy.fromWireName("media_commute")
        )
        assertEquals(
            SpeechAudioPolicy.FORCE_SPEAKER_ALARM,
            SpeechAudioPolicy.fromWireName(null, forceSpeakerAlarmStream = true, useAlarmStream = true)
        )
        assertEquals(
            SpeechAudioPolicy.MEDIA_COMMUTE,
            SpeechAudioPolicy.fromWireName(null, forceSpeakerAlarmStream = false, useAlarmStream = false)
        )
        assertEquals(
            SpeechAudioPolicy.ALARM_RESPECT_HEADSET,
            SpeechAudioPolicy.fromWireName(null, forceSpeakerAlarmStream = false, useAlarmStream = true)
        )
    }

    @Test
    fun utteranceGenerationExtractionIgnoresPreemptedUtterances() {
        assertEquals(7, SpeechAudioPolicy.extractGenFromUtteranceId("oms_tts_g7_0_last"))
        assertEquals(42, SpeechAudioPolicy.extractGenFromUtteranceId("oms_tts_g42_2_mid"))
        assertNull(SpeechAudioPolicy.extractGenFromUtteranceId("legacy_id"))
        assertNull(SpeechAudioPolicy.extractGenFromUtteranceId(null))
    }
}
