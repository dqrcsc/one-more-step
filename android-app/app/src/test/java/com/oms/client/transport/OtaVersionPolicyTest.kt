package com.oms.client.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaVersionPolicyTest {
    private val shaA = "a".repeat(64)
    private val shaB = "b".repeat(64)

    @Test
    fun parsesStandardAndPrefixedSemVer() {
        val v = OtaVersionPolicy.parseSemVer("v1.6.2-rc1")
        assertNotNull(v)
        assertEquals(OtaVersionPolicy.SemVer(1, 6, 2), v)
        assertTrue((OtaVersionPolicy.compareSemVer("1.6.2", "1.6.1") ?: 0) > 0)
        assertTrue((OtaVersionPolicy.compareSemVer("2.0.0", "1.9.9") ?: 0) > 0)
        assertEquals(0, OtaVersionPolicy.compareSemVer("v1.6.2", "1.6.2"))
    }

    @Test
    fun rejectsDowngradeFromMacHostAcrossAllChannels() {
        // Installed 1.6.2 (39) vs older Mac APK 1.6.1 (38) — previously falsely reported hasUpdate=true!
        assertFalse(
            OtaVersionPolicy.hasMacHostUpdate(
                localVersionName = "1.6.2",
                localVersionCode = 39,
                localSha256 = shaA,
                remoteVersionName = "1.6.1",
                remoteVersionCode = 38,
                remoteSha256 = shaB
            )
        )
        // Same binary SHA-256 never triggers update
        assertFalse(
            OtaVersionPolicy.hasMacHostUpdate(
                localVersionName = "1.6.2",
                localVersionCode = 39,
                localSha256 = shaA,
                remoteVersionName = "1.6.2",
                remoteVersionCode = 39,
                remoteSha256 = shaA
            )
        )
    }

    @Test
    fun acceptsUpgradeAndHotRebuiltDevApkAtSameVersionCode() {
        assertTrue(
            OtaVersionPolicy.hasMacHostUpdate(
                localVersionName = "1.6.2",
                localVersionCode = 39,
                localSha256 = shaA,
                remoteVersionName = "1.6.3",
                remoteVersionCode = 40,
                remoteSha256 = shaB
            )
        )
        // Dev hot-rebuild at same versionCode=39 with new SHA-256
        assertTrue(
            OtaVersionPolicy.hasMacHostUpdate(
                localVersionName = "1.6.2",
                localVersionCode = 39,
                localSha256 = shaA,
                remoteVersionName = "1.6.2",
                remoteVersionCode = 39,
                remoteSha256 = shaB
            )
        )
    }

    @Test
    fun githubReleaseComparisonHandles1_6And2_0WithoutHardcodedFormulas() {
        // Older GitHub release v1.6.1 must NOT offer downgrade to installed 1.6.2
        assertFalse(OtaVersionPolicy.hasGithubReleaseUpdate("1.6.2", "v1.6.1"))
        assertFalse(OtaVersionPolicy.hasGithubReleaseUpdate("1.6.2", "v1.6.2"))
        // Newer 1.6.x and 2.x releases are detected cleanly
        assertTrue(OtaVersionPolicy.hasGithubReleaseUpdate("1.6.2", "v1.6.3"))
        assertTrue(OtaVersionPolicy.hasGithubReleaseUpdate("1.6.2", "v2.0.0"))
        // Matching asset digest suppresses update even if tag string differs slightly
        assertFalse(OtaVersionPolicy.hasGithubReleaseUpdate("1.6.2", "v1.6.3", shaA, shaA))
    }
}
