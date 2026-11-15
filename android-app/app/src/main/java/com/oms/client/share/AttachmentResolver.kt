package com.oms.client.share

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.util.Locale

data class ResolvedAttachmentMeta(
    val safeFileName: String,
    val mimeType: String,
    val extension: String,
    val sizeBytes: Long?
)

/**
 * Resolves original display names, accurate MIME types, and safe extensions for arbitrary
 * shared / picked URIs (Album images, PDF, Word .doc/.docx, Excel .xls/.xlsx, PPT, Markdown, ZIP, etc.).
 */
object AttachmentResolver {
    fun selectionError(context: Context, uris: List<Uri>): String? = try {
        AttachmentBudget.validateCount(uris.size)
        AttachmentBudget.validateSizes(uris.map { describeUri(context, it).sizeBytes })
        null
    } catch (e: IllegalArgumentException) { e.message }


    private val EXT_TO_MIME = mapOf(
        // Images
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "bmp" to "image/bmp",
        "svg" to "image/svg+xml",
        // Audio
        "m4a" to "audio/mp4",
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
        "aac" to "audio/aac",
        "flac" to "audio/flac",
        // Video
        "mp4" to "video/mp4",
        "mov" to "video/quicktime",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        // Documents & Office
        "pdf" to "application/pdf",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "rtf" to "application/rtf",
        "epub" to "application/epub+zip",
        // Text & Data
        "txt" to "text/plain",
        "md" to "text/markdown",
        "markdown" to "text/markdown",
        "csv" to "text/csv",
        "tsv" to "text/tab-separated-values",
        "json" to "application/json",
        "jsonl" to "application/x-ndjson",
        "xml" to "application/xml",
        "yaml" to "application/yaml",
        "yml" to "application/yaml",
        "html" to "text/html",
        "htm" to "text/html",
        "log" to "text/plain",
        // Archives & Packages
        "zip" to "application/zip",
        "rar" to "application/vnd.rar",
        "7z" to "application/x-7z-compressed",
        "tar" to "application/x-tar",
        "gz" to "application/gzip",
        "apk" to "application/vnd.android.package-archive"
    )

    private val MIME_TO_EXT = mapOf(
        "image/jpeg" to "jpg",
        "image/jpg" to "jpg",
        "image/png" to "png",
        "image/webp" to "webp",
        "image/gif" to "gif",
        "image/heic" to "heic",
        "image/heif" to "heif",
        "image/bmp" to "bmp",
        "image/svg+xml" to "svg",
        "audio/mp4" to "m4a",
        "audio/x-m4a" to "m4a",
        "audio/mpeg" to "mp3",
        "audio/mp3" to "mp3",
        "audio/wav" to "wav",
        "audio/x-wav" to "wav",
        "audio/ogg" to "ogg",
        "audio/aac" to "aac",
        "audio/flac" to "flac",
        "video/mp4" to "mp4",
        "video/quicktime" to "mov",
        "video/x-matroska" to "mkv",
        "video/webm" to "webm",
        "application/pdf" to "pdf",
        "application/msword" to "doc",
        "application/vnd.ms-word" to "doc",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/wps-office.doc" to "doc",
        "application/wps-office.docx" to "docx",
        "application/vnd.ms-excel" to "xls",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/wps-office.xls" to "xls",
        "application/wps-office.xlsx" to "xlsx",
        "application/vnd.ms-powerpoint" to "ppt",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "application/wps-office.ppt" to "ppt",
        "application/wps-office.pptx" to "pptx",
        "application/rtf" to "rtf",
        "text/rtf" to "rtf",
        "application/epub+zip" to "epub",
        "text/plain" to "txt",
        "text/markdown" to "md",
        "text/x-markdown" to "md",
        "text/csv" to "csv",
        "text/comma-separated-values" to "csv",
        "application/json" to "json",
        "application/xml" to "xml",
        "text/xml" to "xml",
        "text/html" to "html",
        "application/zip" to "zip",
        "application/x-zip-compressed" to "zip",
        "application/vnd.rar" to "rar",
        "application/x-rar-compressed" to "rar",
        "application/x-7z-compressed" to "7z",
        "application/x-tar" to "tar",
        "application/gzip" to "gz",
        "application/vnd.android.package-archive" to "apk"
    )

    private val INVALID_FILENAME_CHARS = Regex("""[\u0000-\u001F\\/:*?"<>|]+""")

    private fun extractCleanExtension(candidate: String?): String? {
        if (candidate.isNullOrBlank()) return null
        val leaf = candidate
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        val dotIdx = leaf.lastIndexOf('.')
        if (dotIdx <= 0 || dotIdx == leaf.length - 1) return null
        val rawExt = leaf.substring(dotIdx + 1).lowercase(Locale.ROOT)
        if (rawExt.length !in 1..12) return null
        if (!rawExt.all { it in 'a'..'z' || it in '0'..'9' }) return null
        return rawExt
    }

    /**
     * Pure MIME + extension resolver. Prioritizes concrete file extension when resolverMime is
     * missing or generic (`application/octet-stream` or wildcard), and maps complex vendor MIME types
     * (like Word `.docx`) to clean extensions instead of corrupting filenames.
     */
    fun resolveMimeAndExtension(
        rawFileName: String?,
        resolverMime: String?,
        uriPath: String? = null
    ): Pair<String, String> {
        val normalizedMime = resolverMime
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() && it != "*/*" && !it.endsWith("/*") }

        val nameExt = extractCleanExtension(rawFileName) ?: extractCleanExtension(uriPath)

        // 1. Known extension on display name or URI path
        if (nameExt != null) {
            val mappedMime = EXT_TO_MIME[nameExt]
            if (mappedMime != null) {
                val effectiveMime = if (
                    normalizedMime != null &&
                    normalizedMime != "application/octet-stream" &&
                    MIME_TO_EXT[normalizedMime] == nameExt
                ) {
                    normalizedMime
                } else {
                    mappedMime
                }
                return effectiveMime to nameExt
            }
            if (normalizedMime != null && normalizedMime != "application/octet-stream") {
                val mimeExt = MIME_TO_EXT[normalizedMime]
                if (mimeExt != null) {
                    return normalizedMime to mimeExt
                }
                return normalizedMime to nameExt
            }
            val systemMime = runCatching {
                MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(nameExt)
            }.getOrNull()?.takeIf { it.isNotBlank() }
            return (systemMime ?: "application/octet-stream") to nameExt
        }

        // 2. No clean extension in name/path -> infer from ContentResolver MIME
        if (normalizedMime != null) {
            MIME_TO_EXT[normalizedMime]?.let { return normalizedMime to it }
            val systemExt = runCatching {
                MimeTypeMap.getSingleton()?.getExtensionFromMimeType(normalizedMime)
            }.getOrNull()?.lowercase(Locale.ROOT)?.takeIf {
                it.length in 1..10 && it.all { ch -> ch in 'a'..'z' || ch in '0'..'9' }
            }
            if (systemExt != null) {
                return normalizedMime to systemExt
            }
            val sub = normalizedMime.substringAfterLast('/', "")
            if (sub.length in 1..8 && sub.all { it in 'a'..'z' || it in '0'..'9' } && sub != "octet") {
                return normalizedMime to sub
            }
            return normalizedMime to "bin"
        }

        return "application/octet-stream" to "bin"
    }

    /**
     * Produces a safe, human-readable filename preserving Chinese/Unicode characters while stripping
     * path traversal characters and bounding UTF-8 byte length below POSIX NAME_MAX (255 bytes).
     */
    fun sanitizeFileName(
        rawDisplayName: String?,
        fallbackPrefix: String,
        fallbackExt: String
    ): String {
        val cleanExt = fallbackExt.lowercase(Locale.ROOT)
            .filter { it in 'a'..'z' || it in '0'..'9' }
            .ifBlank { "bin" }

        val leaf = rawDisplayName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.replace(INVALID_FILENAME_CHARS, "_")
            ?.trim()
            ?.trimStart('.')
            .orEmpty()

        if (leaf.isBlank()) {
            return "${fallbackPrefix}.${cleanExt}"
        }

        val existingExt = extractCleanExtension(leaf)
        val stemRaw = if (existingExt != null) {
            leaf.substring(0, leaf.length - existingExt.length - 1).trim()
        } else {
            leaf
        }.ifBlank { fallbackPrefix }

        // Keep existing extension unless it was a bogus vendor string or missing; if resolver knew a
        // concrete extension for a generic extensionless file, append cleanExt.
        val finalExt = when {
            existingExt == null -> cleanExt
            EXT_TO_MIME.containsKey(existingExt) -> existingExt
            cleanExt != "bin" && existingExt.length > 6 -> cleanExt
            else -> existingExt
        }

        val boundedStem = truncateUtf8Bytes(stemRaw, maxBytes = 180).ifBlank { fallbackPrefix }
        return "${boundedStem}.${finalExt}"
    }

    private fun truncateUtf8Bytes(input: String, maxBytes: Int): String {
        if (input.toByteArray(Charsets.UTF_8).size <= maxBytes) return input
        val sb = StringBuilder()
        var usedBytes = 0
        var i = 0
        while (i < input.length) {
            val cp = input.codePointAt(i)
            val charCount = Character.charCount(cp)
            val cpBytes = when {
                cp <= 0x7F -> 1
                cp <= 0x7FF -> 2
                cp <= 0xFFFF -> 3
                else -> 4
            }
            if (usedBytes + cpBytes > maxBytes) break
            sb.appendCodePoint(cp)
            usedBytes += cpBytes
            i += charCount
        }
        return sb.toString().trimEnd('_', ' ', '.')
    }

    fun inferItemType(mimes: List<String>, hasVoiceNote: Boolean = false): String {
        if (mimes.isEmpty()) return if (hasVoiceNote) "VOICE_IDEA" else "TEXT"
        if (mimes.all { it.startsWith("audio/") }) return "VOICE_IDEA"
        val allVisualMedia = mimes.all { it.startsWith("image/") || it.startsWith("video/") }
        return if (allVisualMedia) "MEDIA" else "FILE"
    }

    fun badgeForFile(fileName: String?, mimeType: String? = null): String {
        val ext = extractCleanExtension(fileName) ?: ""
        val mime = mimeType?.lowercase(Locale.ROOT) ?: ""
        return when {
            mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "svg") -> "🖼️"
            mime.startsWith("audio/") || ext in setOf("m4a", "mp3", "wav", "ogg", "aac", "flac") -> "🎙️"
            mime.startsWith("video/") || ext in setOf("mp4", "mov", "mkv", "webm") -> "🎬"
            ext == "pdf" || mime == "application/pdf" -> "📕"
            ext in setOf("doc", "docx", "rtf") || mime.contains("word") -> "📘"
            ext in setOf("xls", "xlsx", "csv") || mime.contains("excel") || mime.contains("spreadsheet") -> "📗"
            ext in setOf("ppt", "pptx") || mime.contains("powerpoint") || mime.contains("presentation") -> "📙"
            ext in setOf("zip", "rar", "7z", "tar", "gz") -> "🗜️"
            ext in setOf("md", "markdown", "txt", "json", "yaml", "yml", "xml") -> "📄"
            else -> "📎"
        }
    }

    fun formatSizeShort(bytes: Long?): String {
        val b = bytes ?: return ""
        if (b <= 0L) return ""
        return when {
            b < 1024L -> "${b}B"
            b < 1024L * 1024L -> String.format(Locale.US, "%.1fKB", b / 1024.0)
            else -> String.format(Locale.US, "%.1fMB", b / (1024.0 * 1024.0))
        }
    }

    fun describeUri(
        context: Context,
        uri: Uri,
        fallbackPrefix: String = "attachment"
    ): ResolvedAttachmentMeta {
        var displayName: String? = null
        var sizeBytes: Long? = null

        if (uri.scheme == "file") {
            val f = uri.path?.let { File(it) }
            if (f != null) {
                displayName = f.name
                if (f.exists()) sizeBytes = f.length()
            }
        } else if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0 && !c.isNull(nameIdx)) {
                            displayName = c.getString(nameIdx)
                        }
                        val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx >= 0 && !c.isNull(sizeIdx)) {
                            sizeBytes = c.getLong(sizeIdx)
                        }
                    }
                }
            }
        }

        val resolverMime = if (uri.scheme == "content") {
            runCatching { context.contentResolver.getType(uri) }.getOrNull()
        } else {
            null
        }

        val (mime, ext) = resolveMimeAndExtension(displayName, resolverMime, uri.path)
        val effectivePrefix = if (mime.startsWith("audio/") && fallbackPrefix == "attachment") {
            "voice"
        } else {
            fallbackPrefix
        }
        val safeName = sanitizeFileName(displayName, effectivePrefix, ext)
        return ResolvedAttachmentMeta(
            safeFileName = safeName,
            mimeType = mime,
            extension = ext,
            sizeBytes = sizeBytes
        )
    }
}
