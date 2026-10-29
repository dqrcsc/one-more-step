package com.oms.client.agent

import org.junit.Assert.*
import org.junit.Test

class DialerTargetPolicyTest {
    @Test fun genericButtonsAndCallSettingsAreNotDialTargets() {
        assertFalse(DialerTargetPolicy.isCallControl("确定", "android:id/button1"))
        assertFalse(DialerTargetPolicy.isCallControl("拨打设置", "dial_settings"))
        assertFalse(DialerTargetPolicy.isCallControl("呼叫记录", "history"))
    }

    @Test fun recognizedCallAndCarrierControlsRemainSupported() {
        assertTrue(DialerTargetPolicy.isCallControl("拨打", ""))
        assertTrue(DialerTargetPolicy.isCallControl("中国电信 SIM 1", ""))
        assertTrue(DialerTargetPolicy.isCallControl("", "com.android.contacts:id/dialButton2"))
    }

    @Test fun explicitSimMustMatchALabelOrIdentifierNotPosition() {
        assertFalse(DialerTargetPolicy.matchesSim("中国电信", "dialButton", "1"))
        assertTrue(DialerTargetPolicy.matchesSim("中国电信 卡1", "dialButton", "1"))
        assertFalse(DialerTargetPolicy.matchesSim("中国电信 卡1", "dialButton", "2"))
        assertTrue(DialerTargetPolicy.matchesSim("", "dialButton2", "2"))
        assertFalse(DialerTargetPolicy.matchesSim("SIM 12", "dialButton", "1"))
    }

    @Test fun carrierAndUnspecifiedPreferenceAreExplicitlyHandled() {
        assertTrue(DialerTargetPolicy.matchesSim("中国联通", "dialButton", "联通"))
        assertFalse(DialerTargetPolicy.matchesSim("中国电信", "dialButton", "联通"))
        assertTrue(DialerTargetPolicy.matchesSim("拨打", "dialButton", ""))
    }
}
