package com.oms.client.share

import android.net.Uri
import java.io.File

object ShareInputPolicy {
    private val ALLOWED_VOICE_IDEA_TAGS = setOf("voice_idea", "pkb_vault", "ui_bug", "default_inbox")

    private val BLOCKED_AUTHORITIES = setOf(
        "sms",
        "mms",
        "mms-sms",
        "com.android.providers.telephony",
        "com.android.contacts",
        "contacts",
        "com.android.calendar",
        "calendar",
        "call_log",
        "call_log_shadow",
        "com.android.blockednumber",
        "settings"
    )

    fun resolveDirectShareTag(shortcutId: String?): String {
        val id = shortcutId?.trim().orEmpty()
        return when {
            id.contains("pkb_vault") -> "pkb_vault"
            id.contains("ui_bug") -> "ui_bug"
            else -> "default_inbox"
        }
    }

    fun resolveVoiceIdeaTag(rawTag: String?): String {
        val candidate = rawTag?.trim().orEmpty()
        return if (candidate in ALLOWED_VOICE_IDEA_TAGS) candidate else "voice_idea"
    }

    fun isSafeExternalStreamParts(
        scheme: String?,
        authority: String?,
        path: String?,
        appDataDirPath: String? = null
    ): Boolean {
        val normScheme = scheme?.lowercase() ?: return false
        if (normScheme != "content") return false
        val normAuthority = authority?.lowercase()?.substringBefore('@')?.trim().orEmpty()
        if (normAuthority.isEmpty() || normAuthority in BLOCKED_AUTHORITIES) return false
        if (normAuthority.contains("telephony") || normAuthority.contains("sms") || normAuthority.contains("contacts")) {
            return false
        }
        val normPath = path.orEmpty()
        if (normPath.contains("..")) return false
        if (!appDataDirPath.isNullOrBlank() && normPath.startsWith(appDataDirPath)) return false
        return true
    }

    fun isSafeExternalStreamUri(uri: Uri?, appDataDirPath: String? = null): Boolean {
        if (uri == null) return false
        return isSafeExternalStreamParts(uri.scheme, uri.authority, uri.path, appDataDirPath)
    }

    fun isSafeInternalOrContentParts(
        scheme: String?,
        authority: String?,
        path: String?,
        internalAllowedDirs: List<File>
    ): Boolean {
        val normScheme = scheme?.lowercase() ?: return false
        if (normScheme == "content") {
            return isSafeExternalStreamParts(normScheme, authority, path)
        }
        if (normScheme == "file") {
            val filePath = path ?: return false
            return try {
                val canonicalTarget = File(filePath).canonicalPath
                internalAllowedDirs.any { dir ->
                    val canonicalDir = dir.canonicalPath.trimEnd(File.separatorChar) + File.separatorChar
                    canonicalTarget.startsWith(canonicalDir)
                }
            } catch (_: Throwable) {
                false
            }
        }
        return false
    }

    fun isSafeInternalOrContentUri(uri: Uri?, internalAllowedDirs: List<File>): Boolean {
        if (uri == null) return false
        return isSafeInternalOrContentParts(uri.scheme, uri.authority, uri.path, internalAllowedDirs)
    }
}
