package com.oms.client.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.oms.client.transport.OmsFrameProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class StreamItemModel(
    val itemId: String,
    val direction: String, // "PHONE_TO_MAC" | "MAC_TO_PHONE" | "SYSTEM_LOG"
    val itemType: String,  // "MEDIA" | "FILE" | "TEXT" | "VOICE_IDEA" | "APPROVAL" | "CAPABILITY_LOG" | "OTP"
    val intentTag: String, // "default_inbox" | "pkb_vault" | "voice_idea" | "ui_bug" | "sms_otp"
    val title: String?,
    val textContent: String?,
    val voiceTranscript: String?,
    val localFileUri: String?,
    val macSavedPath: String?,
    val syncStatus: String, // "QUEUED" | "SENDING" | "DELIVERED" | "APPROVED" | "REJECTED" | "EXPIRED"
    val metadataJson: String?,
    val createdAtMs: Long
)

data class PairedMacConfig(
    val macId: String,
    val macName: String,
    val lanIps: List<String>,
    val lanPort: Int,
    val macPubKeyBase64Url: String,
    val wanRoomId: String,
    val wanRelayWssUrl: String?,
    val pairSecretHex: String = "",
    val pairingVersion: Int = 0
)

/**
 * Module 03: Local SQLite Store for Bidirectional Timeline Stream & Offline Idempotent Outbox Queue
 */
class StreamRepository(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "oms_client.db", null, DATABASE_VERSION) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("oms_prefs", Context.MODE_PRIVATE)
    private val _itemsFlow = MutableStateFlow<List<StreamItemModel>>(emptyList())
    val itemsFlow: StateFlow<List<StreamItemModel>> = _itemsFlow

    private val _pairedMacsFlow = MutableStateFlow<List<PairedMacConfig>>(emptyList())
    val pairedMacsFlow: StateFlow<List<PairedMacConfig>> = _pairedMacsFlow

    @Volatile
    private var cachedPairedMac: PairedMacConfig? = null
    @Volatile
    private var pairedMacLoaded = false

    init {
        writableDatabase.execSQL("UPDATE stream_items SET sync_status = 'FAILED' WHERE sync_status = 'PREPARING'")
        refreshFlow()
        refreshPairedMacsFlow()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS stream_items (
                item_id TEXT PRIMARY KEY,
                direction TEXT NOT NULL,
                item_type TEXT NOT NULL,
                intent_tag TEXT NOT NULL,
                title TEXT,
                text_content TEXT,
                voice_transcript TEXT,
                local_file_uri TEXT,
                mac_saved_path TEXT,
                sync_status TEXT NOT NULL,
                metadata_json TEXT,
                created_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        ensureIndexes(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            ensureIndexes(db)
        }
    }

    private fun ensureIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_stream_items_created ON stream_items(created_at_ms DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_stream_items_dir_status ON stream_items(direction, sync_status, created_at_ms ASC)")
    }

    fun getOrCreatePhoneId(): String {
        var id = prefs.getString("phone_id", null)
        if (id == null) {
            id = "xiaomi_${OmsFrameProtocol.generateUuidV7().takeLast(12)}"
            prefs.edit().putString("phone_id", id).apply()
        }
        return id
    }

    fun isAutoDownloadOtaEnabled(): Boolean = prefs.getBoolean("auto_download_ota_wifi", true)

    fun setAutoDownloadOtaEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("auto_download_ota_wifi", enabled).apply()
    }

    private companion object {
        private const val DATABASE_VERSION = 2
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val PAIR_SECRET_KEY_ALIAS = "oms_pair_secret_aes_key_v1"
    }

    private fun getOrCreateSecretKey(): SecretKey? {
        return try {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val existing = (ks.getEntry(PAIR_SECRET_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            if (existing != null) return existing
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            val spec = KeyGenParameterSpec.Builder(
                PAIR_SECRET_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        } catch (_: Throwable) {
            null
        }
    }

    private fun encryptPairSecret(plainHex: String): String? {
        if (plainHex.isBlank()) return ""
        return try {
            val key = getOrCreateSecretKey() ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val ivB64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            val ctB64 = Base64.encodeToString(cipher.doFinal(plainHex.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            "v1:$ivB64:$ctB64"
        } catch (_: Throwable) {
            null
        }
    }

    private fun decryptPairSecret(encoded: String): String? {
        if (encoded.isBlank()) return ""
        if (!encoded.startsWith("v1:")) return null
        return try {
            val parts = encoded.split(':')
            if (parts.size != 3) return null
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val ct = Base64.decode(parts[2], Base64.NO_WRAP)
            val key = getOrCreateSecretKey() ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    private fun readStoredPairSecretHex(migrateLegacy: Boolean = true): String =
        PairingSecretPersistence.read(
            prefs.getString("pair_secret_enc", null),
            prefs.getString("pair_secret_hex", "") ?: "",
            ::decryptPairSecret,
            { if (migrateLegacy) encryptPairSecret(it) else null }
        ) { wrapped ->
            prefs.edit().putString("pair_secret_enc", wrapped).remove("pair_secret_hex").apply()
        }

    @Synchronized
    fun savePairedMac(config: PairedMacConfig) {
        val existingMacId = prefs.getString("mac_id", null)
        val sameMac = existingMacId != null && existingMacId == config.macId
        val effectivePubKey = config.macPubKeyBase64Url.ifBlank {
            if (sameMac) prefs.getString("mac_pub_key", "") ?: "" else ""
        }
        val effectiveWanRoom = config.wanRoomId.ifBlank {
            if (sameMac) prefs.getString("wan_room_id", "") ?: "" else ""
        }
        val effectivePairSecret = config.pairSecretHex.ifBlank {
            if (sameMac) readStoredPairSecretHex(migrateLegacy = false) else ""
        }
        val effectiveRelay = config.wanRelayWssUrl ?: if (sameMac) prefs.getString("wan_relay_url", null) else null

        val ipsArr = JSONArray(config.lanIps)
        PairingSecretPersistence.save(effectivePairSecret, ::encryptPairSecret) { encryptedSecret ->
            val editor = prefs.edit()
                .putString("mac_id", config.macId)
                .putString("mac_name", config.macName)
                .putString("lan_ips", ipsArr.toString())
                .putInt("lan_port", config.lanPort)
                .putString("mac_pub_key", effectivePubKey)
                .putString("wan_room_id", effectiveWanRoom)
                .putString("wan_relay_url", effectiveRelay)
                .putInt("pairing_version", config.pairingVersion)

            editor.putString("pair_secret_enc", encryptedSecret).remove("pair_secret_hex")
            editor.commit().also { check(it) { "Unable to persist pairing configuration" } }
        }
        cachedPairedMac = PairedMacConfig(
            macId = config.macId,
            macName = config.macName,
            lanIps = config.lanIps,
            lanPort = config.lanPort,
            macPubKeyBase64Url = effectivePubKey,
            wanRoomId = effectiveWanRoom,
            wanRelayWssUrl = effectiveRelay,
            pairSecretHex = effectivePairSecret,
            pairingVersion = config.pairingVersion
        )
        pairedMacLoaded = true
        upsertIntoMacRegistryLocked(cachedPairedMac!!)
    }

    @Synchronized
    private fun upsertIntoMacRegistryLocked(entry: PairedMacConfig) {
        val currentList = loadMacRegistryLocked().toMutableList()
        val encSecret = encryptPairSecret(entry.pairSecretHex) ?: return
        val idx = currentList.indexOfFirst { it.first.macId == entry.macId }
        if (idx >= 0) {
            currentList[idx] = entry to encSecret
        } else {
            currentList.add(0, entry to encSecret)
        }
        persistMacRegistryLocked(currentList.take(10))
        _pairedMacsFlow.value = currentList.take(10).map { it.first }
    }

    private fun loadMacRegistryLocked(): List<Pair<PairedMacConfig, String>> {
        val raw = prefs.getString("paired_macs_registry_v1", null)
        val out = mutableListOf<Pair<PairedMacConfig, String>>()
        if (!raw.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("macId", "").trim()
                    val enc = o.optString("pairSecretEnc", "").trim()
                    if (id.isEmpty() || enc.isEmpty()) continue
                    val decSecret = decryptPairSecret(enc).orEmpty()
                    if (decSecret.isEmpty()) continue
                    val ipsArr = o.optJSONArray("lanIps") ?: JSONArray()
                    val ips = (0 until ipsArr.length()).mapNotNull { ipsArr.optString(it).takeIf(String::isNotBlank) }
                    val cfg = PairedMacConfig(
                        macId = id,
                        macName = o.optString("macName", "Mac"),
                        lanIps = ips,
                        lanPort = o.optInt("lanPort", 19527),
                        macPubKeyBase64Url = o.optString("macPubKey", ""),
                        wanRoomId = o.optString("wanRoomId", ""),
                        wanRelayWssUrl = o.optString("wanRelayUrl", "").takeIf { it.isNotBlank() },
                        pairSecretHex = decSecret,
                        pairingVersion = o.optInt("pairingVersion", 2)
                    )
                    out.add(cfg to enc)
                }
            }
        }
        return out
    }

    private fun persistMacRegistryLocked(entries: List<Pair<PairedMacConfig, String>>) {
        val arr = JSONArray()
        for ((cfg, enc) in entries) {
            arr.put(
                JSONObject()
                    .put("macId", cfg.macId)
                    .put("macName", cfg.macName)
                    .put("lanIps", JSONArray(cfg.lanIps))
                    .put("lanPort", cfg.lanPort)
                    .put("macPubKey", cfg.macPubKeyBase64Url)
                    .put("wanRoomId", cfg.wanRoomId)
                    .put("wanRelayUrl", cfg.wanRelayWssUrl ?: "")
                    .put("pairSecretEnc", enc)
                    .put("pairingVersion", cfg.pairingVersion)
            )
        }
        prefs.edit().putString("paired_macs_registry_v1", arr.toString()).apply()
    }

    @Synchronized
    private fun refreshPairedMacsFlow() {
        val active = getPairedMac()
        val reg = loadMacRegistryLocked()
        if (reg.isEmpty() && active != null && active.pairSecretHex.isNotBlank()) {
            upsertIntoMacRegistryLocked(active)
        } else {
            _pairedMacsFlow.value = reg.map { it.first }
        }
    }

    @Synchronized
    fun getPairedMacs(): List<PairedMacConfig> {
        if (_pairedMacsFlow.value.isEmpty()) {
            refreshPairedMacsFlow()
        }
        val list = _pairedMacsFlow.value
        if (list.isNotEmpty()) return list
        return listOfNotNull(getPairedMac())
    }

    @Synchronized
    fun switchActiveMac(macId: String): PairedMacConfig? {
        val target = getPairedMacs().firstOrNull { it.macId == macId } ?: return getPairedMac()
        savePairedMac(target)
        return target
    }

    @Synchronized
    fun removePairedMac(macId: String) {
        val remaining = loadMacRegistryLocked().filterNot { it.first.macId == macId }
        persistMacRegistryLocked(remaining)
        _pairedMacsFlow.value = remaining.map { it.first }
        if (cachedPairedMac?.macId == macId) {
            val next = remaining.firstOrNull()?.first
            if (next != null) {
                savePairedMac(next)
            } else {
                clearPairedMac()
            }
        }
    }

    /** Discovery may refresh routing, never restore a stale pairing credential or security version. */
    @Synchronized
    fun updatePairedMacDiscovery(discovered: PairedMacConfig) {
        val current = getPairedMac() ?: return
        if (current.macId != discovered.macId) return
        val mergedIps = (discovered.lanIps + current.lanIps)
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("198.18.") && !it.startsWith("127.") }
            .distinct()
            .take(6)
        val finalIps = mergedIps.ifEmpty { discovered.lanIps }
        prefs.edit()
            .putString("lan_ips", JSONArray(finalIps).toString())
            .putInt("lan_port", discovered.lanPort)
            .commit().also { check(it) { "Unable to persist discovery routing" } }
        cachedPairedMac = current.copy(lanIps = finalIps, lanPort = discovered.lanPort)
        pairedMacLoaded = true
    }

    @Synchronized
    fun clearPairedMac() {
        cachedPairedMac = null
        pairedMacLoaded = true
        prefs.edit()
            .remove("mac_id")
            .remove("mac_name")
            .remove("lan_ips")
            .remove("lan_port")
            .remove("mac_pub_key")
            .remove("wan_room_id")
            .remove("wan_relay_url")
            .remove("pair_secret_hex")
            .remove("pair_secret_enc")
            .remove("pairing_version")
            .apply()
    }

    fun getPairedMac(): PairedMacConfig? {
        if (pairedMacLoaded) return cachedPairedMac
        synchronized(this) {
            if (pairedMacLoaded) return cachedPairedMac
            val macId = prefs.getString("mac_id", null)?.takeIf { it.isNotBlank() }
            if (macId == null) {
                cachedPairedMac = null
                pairedMacLoaded = true
                return null
            }
            val ipsJson = prefs.getString("lan_ips", "[]") ?: "[]"
            val arr = JSONArray(ipsJson)
            val ips = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                ips.add(arr.getString(i))
            }
            val loaded = PairedMacConfig(
                macId = macId,
                macName = prefs.getString("mac_name", "MacBook Pro") ?: "MacBook Pro",
                lanIps = ips,
                lanPort = prefs.getInt("lan_port", 19527),
                macPubKeyBase64Url = prefs.getString("mac_pub_key", "") ?: "",
                wanRoomId = prefs.getString("wan_room_id", "") ?: "",
                wanRelayWssUrl = prefs.getString("wan_relay_url", null),
                pairSecretHex = readStoredPairSecretHex(),
                pairingVersion = prefs.getInt("pairing_version", 0)
            )
            cachedPairedMac = loaded
            pairedMacLoaded = true
            return loaded
        }
    }

    fun getAuthorizedDirs(): Set<String> {
        return prefs.getStringSet("authorized_dirs", setOf("screenshots", "camera", "download"))
            ?: setOf("screenshots", "camera", "download")
    }

    fun setAuthorizedDirEnabled(alias: String, enabled: Boolean) {
        val current = getAuthorizedDirs().toMutableSet()
        if (enabled) current.add(alias) else current.remove(alias)
        prefs.edit().putStringSet("authorized_dirs", current).apply()
    }

    fun isAutoSyncOtpEnabled(): Boolean {
        return prefs.getBoolean("auto_sync_otp", true)
    }

    fun setAutoSyncOtpEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("auto_sync_otp", enabled).apply()
    }

    fun isCameraPillEnabled(): Boolean {
        return prefs.getBoolean("camera_pill_enabled", false)
    }

    fun setCameraPillEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("camera_pill_enabled", enabled).apply()
    }

    fun upsertStreamItem(item: StreamItemModel) {
        val cv = ContentValues().apply {
            put("item_id", item.itemId)
            put("direction", item.direction)
            put("item_type", item.itemType)
            put("intent_tag", item.intentTag)
            put("title", item.title)
            put("text_content", item.textContent)
            put("voice_transcript", item.voiceTranscript)
            put("local_file_uri", item.localFileUri)
            put("mac_saved_path", item.macSavedPath)
            put("sync_status", item.syncStatus)
            put("metadata_json", item.metadataJson)
            put("created_at_ms", item.createdAtMs)
        }
        val rowId = writableDatabase.insertWithOnConflict(
            "stream_items",
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        check(rowId != -1L) { "Failed to commit stream item" }
        refreshFlow()
    }

    fun updateSyncStatus(itemId: String, status: String, macSavedPath: String? = null) {
        val cv = ContentValues().apply {
            put("sync_status", status)
            if (macSavedPath != null) put("mac_saved_path", macSavedPath)
        }
        writableDatabase.update("stream_items", cv, "item_id = ?", arrayOf(itemId))
        refreshFlow()
    }

    fun getStreamItem(itemId: String): StreamItemModel? =
        queryItems("item_id = ?", "created_at_ms DESC", arrayOf(itemId)).firstOrNull()

    fun listQueuedOutboxItems(): List<StreamItemModel> {
        return queryItems("direction = 'PHONE_TO_MAC' AND sync_status = 'QUEUED'", "created_at_ms ASC")
    }

    private fun deleteOwnedAttachmentIfPresent(uriString: String?) {
        if (uriString.isNullOrBlank()) return
        try {
            val uri = Uri.parse(uriString)
            if (uri.scheme != "file") return
            val path = uri.path ?: return
            val file = File(path).canonicalFile
            val filesRoot = appContext.filesDir.canonicalPath
            val cacheRoot = appContext.cacheDir.canonicalPath
            val outboxRoot = File(appContext.filesDir, "outbox_attachments").canonicalPath
            if (file.path.startsWith(filesRoot + File.separator) || file.path.startsWith(cacheRoot + File.separator)) {
                val parent = file.parentFile
                file.delete()
                if (parent != null &&
                    parent.canonicalPath.startsWith(outboxRoot + File.separator) &&
                    parent.list()?.isEmpty() == true
                ) {
                    parent.delete()
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun cleanupItemAttachments(item: StreamItemModel) {
        deleteOwnedAttachmentIfPresent(item.localFileUri)
        if (!item.metadataJson.isNullOrBlank()) {
            runCatching {
                val meta = JSONObject(item.metadataJson)
                deleteOwnedAttachmentIfPresent(meta.optString("extraAudioUri", ""))
                val extraArr = meta.optJSONArray("extraFileUris")
                if (extraArr != null) {
                    for (i in 0 until extraArr.length()) {
                        deleteOwnedAttachmentIfPresent(extraArr.optString(i, ""))
                    }
                }
            }
        }
    }

    fun deleteStreamItem(itemId: String) {
        val existing = getStreamItem(itemId)
        writableDatabase.delete("stream_items", "item_id = ?", arrayOf(itemId))
        if (existing != null) {
            cleanupItemAttachments(existing)
        }
        refreshFlow()
    }

    fun clearDeliveredHistory(): Int {
        val doomed = queryItems("sync_status IN ('DELIVERED', 'APPROVED', 'REJECTED', 'EXPIRED')", "created_at_ms ASC")
        val deleted = writableDatabase.delete(
            "stream_items",
            "sync_status IN ('DELIVERED', 'APPROVED', 'REJECTED', 'EXPIRED')",
            null
        )
        for (item in doomed) {
            cleanupItemAttachments(item)
        }
        refreshFlow()
        return deleted
    }

    fun refreshFlow() {
        _itemsFlow.value = queryItems(null, "created_at_ms DESC LIMIT 200")
    }

    private fun queryItems(selection: String?, orderBy: String, selectionArgs: Array<String>? = null): List<StreamItemModel> {
        val list = mutableListOf<StreamItemModel>()
        readableDatabase.query(
            "stream_items",
            null,
            selection,
            selectionArgs,
            null,
            null,
            orderBy
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    StreamItemModel(
                        itemId = c.getString(c.getColumnIndexOrThrow("item_id")),
                        direction = c.getString(c.getColumnIndexOrThrow("direction")),
                        itemType = c.getString(c.getColumnIndexOrThrow("item_type")),
                        intentTag = c.getString(c.getColumnIndexOrThrow("intent_tag")),
                        title = c.getString(c.getColumnIndexOrThrow("title")),
                        textContent = c.getString(c.getColumnIndexOrThrow("text_content")),
                        voiceTranscript = c.getString(c.getColumnIndexOrThrow("voice_transcript")),
                        localFileUri = c.getString(c.getColumnIndexOrThrow("local_file_uri")),
                        macSavedPath = c.getString(c.getColumnIndexOrThrow("mac_saved_path")),
                        syncStatus = c.getString(c.getColumnIndexOrThrow("sync_status")),
                        metadataJson = c.getString(c.getColumnIndexOrThrow("metadata_json")),
                        createdAtMs = c.getLong(c.getColumnIndexOrThrow("created_at_ms"))
                    )
                )
            }
        }
        return list
    }
}
