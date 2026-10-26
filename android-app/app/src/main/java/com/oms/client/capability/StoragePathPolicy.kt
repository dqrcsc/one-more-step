package com.oms.client.capability

import java.io.File

object StoragePathPolicy {
    fun normalizeAlias(alias: String): String = if (alias.lowercase() == "downloads") "download" else alias.lowercase()

    fun isWithin(base: File, target: File): Boolean {
        val root = base.canonicalPath
        val path = target.canonicalPath
        return path == root || path.startsWith(root + File.separator)
    }

    fun mediaPathAllowed(relativePath: String, authorized: Set<String>): Boolean {
        val parts = relativePath.replace('\\', '/').trim('/').split('/')
        if (parts.any { it == ".." || it == "." || it.isBlank() }) return false
        val enabled = authorized.map { normalizeAlias(it) }.toSet()
        return when {
            parts.size >= 2 && parts[0] in setOf("DCIM", "Pictures") && parts[1] == "Screenshots" -> "screenshots" in enabled
            parts.size >= 2 && parts[0] == "DCIM" && parts[1] == "Camera" -> "camera" in enabled
            parts.firstOrNull() == "Download" -> "download" in enabled
            else -> false
        }
    }
}
