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
import com.oms.client.transport.DeliveryPolicy
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

        val authorized = repo.getAuthorizedDirs().map { StoragePathPolicy.normalizeAlias(it) }.toSet()
        if (mediaType == "screenshot" && !authorized.contains("screenshots")) {
            throw SecurityException("ERR_SCOPED_PATH_DENIED: 'screenshots' directory is not authorized")
        }

        if (mediaType == "camera" && "camera" !in authorized) {
            throw SecurityException("ERR_SCOPED_PATH_DENIED: 'camera' directory is not authorized")
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
                if (!StoragePathPolicy.mediaPathAllowed(relPath, authorized)) continue
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
        val dirAlias = StoragePathPolicy.normalizeAlias(params.optString("dirAlias", "download"))
        val subPath = params.optString("subPath", "")
        val limit = params.optInt("limit", 50).coerceIn(1, 200)

        if (!repo.getAuthorizedDirs().map { StoragePathPolicy.normalizeAlias(it) }.contains(dirAlias)) {
            throw SecurityException("ERR_SCOPED_PATH_DENIED: Alias '$dirAlias' is not enabled in allowlist")
        }

        val baseDir = resolveAliasBaseDir(dirAlias)
            ?: throw SecurityException("ERR_SCOPED_PATH_DENIED: Unknown alias '$dirAlias'")
        val targetDir = if (subPath.isBlank()) baseDir else File(baseDir, subPath)

        // Strict canonical path check against `..` path traversal
        if (!StoragePathPolicy.isWithin(baseDir, targetDir)) {
            throw SecurityException("ERR_SCOPED_PATH_DENIED: Path traversal outside '$dirAlias' blocked")
        }

        val files = targetDir.listFiles()
            ?.filter { StoragePathPolicy.isWithin(baseDir, it) }
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
            // Only MediaStore can attest a public-media directory; arbitrary providers cannot bypass the allowlist.
            if (uri.authority != MediaStore.AUTHORITY) {
                throw SecurityException("ERR_SCOPED_PATH_DENIED: Only authorized MediaStore content URIs are supported")
            }
            val metadata = context.contentResolver.query(uri,
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) null else {
                    val relativePath = cursor.getString(0) ?: ""
                    if (!StoragePathPolicy.mediaPathAllowed(relativePath, repo.getAuthorizedDirs())) {
                        throw SecurityException("ERR_SCOPED_PATH_DENIED: URI directory is not authorized")
                    }
                    require(cursor.getLong(3) <= DeliveryPolicy.MAX_BYTES) { "File exceeds 64 MiB" }
                    Pair(cursor.getString(1) ?: "media.bin", cursor.getString(2) ?: "application/octet-stream")
                }
            } ?: throw SecurityException("ERR_SCOPED_PATH_DENIED: URI directory cannot be verified")
            val rawBytes = context.contentResolver.openInputStream(uri)?.use { DeliveryPolicy.readBounded(it) }
                ?: throw IllegalArgumentException("Unable to read URI")
            val compressed = if (compressImage && metadata.second.startsWith("image/")) compressToWebpIfPossible(rawBytes) else null
            return PulledFilePayload(
                fileName = if (compressed != null) metadata.first.substringBeforeLast('.') + ".webp" else metadata.first,
                mimeType = if (compressed != null) "image/webp" else metadata.second,
                bytes = compressed ?: rawBytes
            )
        }

        // Alias relative path format: e.g. "download/report.pdf"
        val slashIdx = sourceUriOrPath.indexOf('/')
        require(slashIdx > 0) { "Path must be 'content://...' or '<dirAlias>/<relativePath>'" }
        val alias = StoragePathPolicy.normalizeAlias(sourceUriOrPath.substring(0, slashIdx))
        val rel = sourceUriOrPath.substring(slashIdx + 1)

        if (!repo.getAuthorizedDirs().map { StoragePathPolicy.normalizeAlias(it) }.contains(alias)) {
            throw SecurityException("ERR_SCOPED_PATH_DENIED: Alias '$alias' is not authorized")
        }

        val baseDir = resolveAliasBaseDir(alias)
            ?: throw SecurityException("ERR_SCOPED_PATH_DENIED: Invalid alias '$alias'")
        val file = File(baseDir, rel)
        if (!StoragePathPolicy.isWithin(baseDir, file) || !file.isFile) {
            throw SecurityException("ERR_SCOPED_PATH_DENIED: Invalid or unauthorized file path")
        }

        val rawBytes = file.inputStream().use { DeliveryPolicy.readBounded(it) }
        return PulledFilePayload(
            fileName = file.name,
            mimeType = "application/octet-stream",
            bytes = rawBytes
        )
    }

    private fun compressToWebpIfPossible(rawBytes: ByteArray): ByteArray? {
        return try {
            val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null
            val out = ByteArrayOutputStream()
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            val ok = bmp.compress(format, 82, out)
            bmp.recycle()
            if (ok) out.toByteArray() else null
        } catch (_: Throwable) {
            null
        }
    }
}
