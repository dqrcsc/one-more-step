package com.oms.client.capability

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.oms.client.data.StreamRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Module 04: Scoped Storage & Media Allowlist Executor
 * Prevents arbitrary filesystem scanning and enforces strict directory alias allowlist.
 */
object ScopedStorageExecutor {

    private fun resolveAliasBaseDir(alias: String): File? {
        val extRoot = Environment.getExternalStorageDirectory()
        return when (alias.lowercase()) {
            "screenshots" -> {
                val dcimShots = File(extRoot, "DCIM/Screenshots")
                if (dcimShots.exists()) dcimShots else File(extRoot, "Pictures/Screenshots")
            }
            "camera" -> File(extRoot, "DCIM/Camera")
            "download", "downloads" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            else -> null
        }
    }

    fun listRecentMedia(context: Context, repo: StreamRepository, params: JSONObject): JSONObject {
        val mediaType = params.optString("mediaType", "screenshot").lowercase()
        val limit = params.optInt("limit", 10).coerceIn(1, 50)
        val sinceMs = params.optLong("sinceMs", 0L)

        val authorized = repo.getAuthorizedDirs()
        if (mediaType == "screenshot" && !authorized.contains("screenshots")) {
            throw SecurityException("ERR_SCOPED_PATH_DENIED: 'screenshots' directory is not authorized")
        }

        val collection = if (mediaType == "video") {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DATE_ADDED
        )

        val selectionParts = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()

        if (mediaType == "screenshot") {
            selectionParts.add("${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?")
            selectionArgs.add("%Screenshots%")
        } else if (mediaType == "camera") {
            selectionParts.add("${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?")
            selectionArgs.add("%Camera%")
        }
        if (sinceMs > 0) {
            selectionParts.add("${MediaStore.MediaColumns.DATE_ADDED} >= ?")
            selectionArgs.add((sinceMs / 1000).toString())
        }

        val selection = if (selectionParts.isEmpty()) null else selectionParts.joinToString(" AND ")
        val itemsArr = JSONArray()

        context.contentResolver.query(
            collection,
            projection,
            selection,
            if (selectionArgs.isEmpty()) null else selectionArgs.toTypedArray(),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            var count = 0
            while (cursor.moveToNext() && count < limit) {
                val id = cursor.getLong(0)
                val name = cursor.getString(1) ?: "media_$id"
                val relPath = cursor.getString(2) ?: ""
                val mime = cursor.getString(3) ?: "image/jpeg"
                val size = cursor.getLong(4)
                val width = cursor.getInt(5)
                val height = cursor.getInt(6)
                val dateAddedSec = cursor.getLong(7)
                val contentUri = ContentUris.withAppendedId(collection, id)

                itemsArr.put(
                    JSONObject().apply {
                        put("mediaId", id.toString())
                        put("fileName", name)
                        put("remotePath", contentUri.toString())
                        put("relativePath", relPath)
                        put("mimeType", mime)
                        put("sizeBytes", size)
                        put("width", width)
                        put("height", height)
                        put("createdAtMs", dateAddedSec * 1000L)
                    }
                )
                count++
            }
        }

        return JSONObject().apply {
            put("items", itemsArr)
        }
    }

    fun listDir(repo: StreamRepository, params: JSONObject): JSONObject {
        val dirAlias = params.optString("dirAlias", "download").lowercase()
        val subPath = params.optString("subPath", "")
        val limit = params.optInt("limit", 50).coerceIn(1, 200)

        if (!repo.getAuthorizedDirs().contains(dirAlias)) {
            throw SecurityException("ERR_SCOPED_PATH_DENIED: Alias '$dirAlias' is not enabled in allowlist")
        }

        val baseDir = resolveAliasBaseDir(dirAlias)
            ?: throw SecurityException("ERR_SCOPED_PATH_DENIED: Unknown alias '$dirAlias'")
        val targetDir = if (subPath.isBlank()) baseDir else File(baseDir, subPath)

        // Strict canonical path check against `..` path traversal
        val baseCanonical = baseDir.canonicalPath
        val targetCanonical = targetDir.canonicalPath
        if (!targetCanonical.startsWith(baseCanonical)) {
            throw SecurityException("ERR_SCOPED_PATH_DENIED: Path traversal outside '$dirAlias' blocked")
        }

        val files = targetDir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit)
            ?: emptyList()

        val entriesArr = JSONArray()
        for (f in files) {
            entriesArr.put(
                JSONObject().apply {
                    put("name", f.name)
                    put("isDir", f.isDirectory)
                    put("size", if (f.isFile) f.length() else 0L)
                    put("updatedAtMs", f.lastModified())
                    put("uriOrPath", "$dirAlias/${f.relativeTo(baseDir).path}")
                }
            )
        }

        return JSONObject().apply {
            put("dirAlias", dirAlias)
            put("entries", entriesArr)
        }
    }

    data class PulledFilePayload(
        val fileName: String,
        val mimeType: String,
        val bytes: ByteArray
    )

    fun readAuthorizedFileBytes(
        context: Context,
        repo: StreamRepository,
        sourceUriOrPath: String,
        compressImage: Boolean
    ): PulledFilePayload {
        if (sourceUriOrPath.startsWith("content://")) {
            val uri = Uri.parse(sourceUriOrPath)
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalArgumentException("Unable to read URI: $sourceUriOrPath")
            val finalBytes = if (compressImage && mime.startsWith("image/")) {
                compressToWebpIfPossible(rawBytes)
            } else {
                rawBytes
            }
            return PulledFilePayload(
                fileName = "media_${uri.lastPathSegment ?: "item"}.${if (compressImage) "webp" else "jpg"}",
                mimeType = if (compressImage) "image/webp" else mime,
                bytes = finalBytes
            )
        }

        // Alias relative path format: e.g. "download/report.pdf"
        val slashIdx = sourceUriOrPath.indexOf('/')
        require(slashIdx > 0) { "Path must be 'content://...' or '<dirAlias>/<relativePath>'" }
        val alias = sourceUriOrPath.substring(0, slashIdx).lowercase()
        val rel = sourceUriOrPath.substring(slashIdx + 1)

        if (!repo.getAuthorizedDirs().contains(alias)) {
            throw SecurityException("ERR_SCOPED_PATH_DENIED: Alias '$alias' is not authorized")
        }

        val baseDir = resolveAliasBaseDir(alias)
            ?: throw SecurityException("ERR_SCOPED_PATH_DENIED: Invalid alias '$alias'")
        val file = File(baseDir, rel)
        if (!file.canonicalPath.startsWith(baseDir.canonicalPath) || !file.isFile) {
            throw SecurityException("ERR_SCOPED_PATH_DENIED: Invalid or unauthorized file path")
        }

        val rawBytes = file.readBytes()
        return PulledFilePayload(
            fileName = file.name,
            mimeType = "application/octet-stream",
            bytes = rawBytes
        )
    }

    private fun compressToWebpIfPossible(rawBytes: ByteArray): ByteArray {
        return try {
            val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return rawBytes
            val out = ByteArrayOutputStream()
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            bmp.compress(format, 82, out)
            out.toByteArray()
        } catch (_: Throwable) {
            rawBytes
        }
    }
}
