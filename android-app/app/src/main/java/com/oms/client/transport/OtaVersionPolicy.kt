package com.oms.client.transport

/**
 * Pure semantic version & OTA upgrade eligibility evaluator.
 * Guarantees downgrade APKs are never falsely offered as updates across RPC, LAN HTTP, or GitHub Releases.
 */
object OtaVersionPolicy {
    data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
        override fun compareTo(other: SemVer): Int {
            if (major != other.major) return major.compareTo(other.major)
            if (minor != other.minor) return minor.compareTo(other.minor)
            return patch.compareTo(other.patch)
        }
    }

    private val SEMVER_REGEX = Regex("""^\s*v?(\d+)\.(\d+)\.(\d+)(?:[-+].*)?\s*$""", RegexOption.IGNORE_CASE)

    fun parseSemVer(raw: String?): SemVer? {
        if (raw.isNullOrBlank()) return null
        val m = SEMVER_REGEX.matchEntire(raw) ?: return null
        val major = m.groupValues[1].toIntOrNull() ?: return null
        val minor = m.groupValues[2].toIntOrNull() ?: return null
        val patch = m.groupValues[3].toIntOrNull() ?: return null
        return SemVer(major, minor, patch)
    }

    fun compareSemVer(a: String?, b: String?): Int? {
        val va = parseSemVer(a) ?: return null
        val vb = parseSemVer(b) ?: return null
        return va.compareTo(vb)
    }

    /**
     * Evaluate whether an APK hosted on the paired Mac (`apk.getVersion` or `/apk/version`) is an upgrade
     * or a hot-rebuilt dev binary at the exact same versionCode. Never returns true for older versionCode
     * or older semantic version.
     */
    fun hasMacHostUpdate(
        localVersionName: String,
        localVersionCode: Int,
        localSha256: String,
        remoteVersionName: String,
        remoteVersionCode: Int,
        remoteSha256: String
    ): Boolean {
        if (remoteSha256.isNotBlank() && localSha256.isNotBlank() &&
            localSha256.equals(remoteSha256, ignoreCase = true)
        ) {
            return false
        }

        val semVerCmp = compareSemVer(remoteVersionName, localVersionName)
        if (remoteVersionCode > 0 && localVersionCode > 0) {
            if (remoteVersionCode > localVersionCode) {
                return semVerCmp == null || semVerCmp >= 0
            }
            if (remoteVersionCode < localVersionCode) {
                return false
            }
            // remoteVersionCode == localVersionCode: allow local hot-rebuild only if semver is not older
            if (semVerCmp != null && semVerCmp < 0) {
                return false
            }
            return remoteSha256.isNotBlank() && localSha256.isNotBlank() &&
                !localSha256.equals(remoteSha256, ignoreCase = true)
        }

        return semVerCmp != null && semVerCmp > 0
    }

    /**
     * Evaluate whether a GitHub Release tag represents a strictly newer semantic version than installed.
     */
    fun hasGithubReleaseUpdate(
        localVersionName: String,
        releaseTagName: String,
        localSha256: String = "",
        assetSha256: String = ""
    ): Boolean {
        if (assetSha256.isNotBlank() && localSha256.isNotBlank() &&
            localSha256.equals(assetSha256, ignoreCase = true)
        ) {
            return false
        }
        val cmp = compareSemVer(releaseTagName, localVersionName) ?: return false
        return cmp > 0
    }
}
