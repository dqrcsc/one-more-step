package com.oms.client.share

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareInputPolicyTest {
    @Test
    fun userQualifiedProvidersCannotBypassSensitiveProviderChecks() {
        for (provider in listOf("sms", "mms", "calendar", "com.android.contacts", "call_log", "settings")) {
            for (authority in listOf(provider, "0@$provider", "10@$provider", "0@10@$provider", "0@${provider.uppercase()}")) {
                assertFalse(authority, ShareInputPolicy.isSafeExternalStreamParts("content", authority, "/item/1"))
            }
        }
        // Reject all cross-user forms, including safe providers, rather than guessing Android's user routing.
        assertFalse(ShareInputPolicy.isSafeExternalStreamParts("content", "0@media", "/external/1"))
    }

    @Test
    fun ordinaryAttachmentsStillWorkButPrivateFileAndTraversalStayBlocked() {
        assertTrue(ShareInputPolicy.isSafeExternalStreamParts("content", "media", "/external/images/media/42"))
        assertTrue(ShareInputPolicy.isSafeExternalStreamParts("content", "com.example.fileprovider", "/attachments/a.pdf"))
        assertFalse(ShareInputPolicy.isSafeExternalStreamParts("file", null, "/data/data/app/secret"))
        assertFalse(ShareInputPolicy.isSafeExternalStreamParts("content", "media", "/../secret"))
    }
}
