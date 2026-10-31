package com.oms.client.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.oms.client.transport.OmsFrameProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class StreamItemModel(
    val itemId: String,
    val direction: String, // "PHONE_TO_MAC" | "MAC_TO_PHONE" | "SYSTEM_LOG"
    val itemType: String,  // "MEDIA" | "FILE" | "TEXT" | "VOICE_IDEA" | "APPROVAL" | "CAPABILITY_LOG"
    val intentTag: String, // "default_inbox" | "pkb_vault" | "voice_idea" | "ui_bug"
    val title: String?,
    val textContent: String?,
    val voiceTranscript: String?,
    val localFileUri: String?,
    val macSavedPath: String?,
    val syncStatus: String, // "QUEUED" | "SENDING" | "DELIVERED" | "APPROVED" | "REJECTED"
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
    SQLiteOpenHelper(context.applicationContext, "oms_client.db", null, 1) {

    private val prefs = context.applicationContext.getSharedPreferences("oms_prefs", Context.MODE_PRIVATE)
    private val _itemsFlow = MutableStateFlow<List<StreamItemModel>>(emptyList())
    val itemsFlow: StateFlow<List<StreamItemModel>> = _itemsFlow

    init {
        refreshFlow()
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun getOrCreatePhoneId(): String {
        var id = prefs.getString("phone_id", null)
        if (id == null) {
            id = "xiaomi_${OmsFrameProtocol.generateUuidV7().takeLast(12)}"
            prefs.edit().putString("phone_id", id).apply()
        }
        return id
    }

    private companion object {
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

    private fun readStoredPairSecretHex(): String {
        val enc = prefs.getString("pair_secret_enc", null)
        if (!enc.isNullOrBlank()) {
            val decrypted = decryptPairSecret(enc)
            if (!decrypted.isNullOrBlank()) return decrypted
        }
        val legacyPlain = prefs.getString("pair_secret_hex", "") ?: ""
        if (legacyPlain.isNotBlank()) {
            val wrapped = encryptPairSecret(legacyPlain)
            if (!wrapped.isNullOrBlank()) {
                prefs.edit()
                    .putString("pair_secret_enc", wrapped)
                    .remove("pair_secret_hex")
                    .apply()
            }
        }
        return legacyPlain
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
            if (sameMac) readStoredPairSecretHex() else ""
        }
        val effectiveRelay = config.wanRelayWssUrl ?: if (sameMac) prefs.getString("wan_relay_url", null) else null

        val ipsArr = JSONArray(config.lanIps)
        val editor = prefs.edit()
            .putString("mac_id", config.macId)
            .putString("mac_name", config.macName)
            .putString("lan_ips", ipsArr.toString())
            .putInt("lan_port", config.lanPort)
            .putString("mac_pub_key", effectivePubKey)
            .putString("wan_room_id", effectiveWanRoom)
            .putString("wan_relay_url", effectiveRelay)
            .putInt("pairing_version", config.pairingVersion)

        val encryptedSecret = encryptPairSecret(effectivePairSecret)
        if (encryptedSecret != null) {
            editor.putString("pair_secret_enc", encryptedSecret).remove("pair_secret_hex")
        } else {
            editor.putString("pair_secret_hex", effectivePairSecret)
        }
        editor.commit().also { check(it) { "Unable to persist pairing configuration" } }
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
        savePairedMac(current.copy(lanIps = mergedIps.ifEmpty { discovered.lanIps }, lanPort = discovered.lanPort))
    }

    @Synchronized
    fun clearPairedMac() {
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

    @Synchronized
    fun getPairedMac(): PairedMacConfig? {
        val macId = prefs.getString("mac_id", null)?.takeIf { it.isNotBlank() } ?: return null
        val ipsJson = prefs.getString("lan_ips", "[]") ?: "[]"
        val arr = JSONArray(ipsJson)
        val ips = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            ips.add(arr.getString(i))
        }
        return PairedMacConfig(
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

    fun deleteStreamItem(itemId: String) {
        writableDatabase.delete("stream_items", "item_id = ?", arrayOf(itemId))
        refreshFlow()
    }

    fun clearDeliveredHistory(): Int {
        val deleted = writableDatabase.delete(
            "stream_items",
            "sync_status IN ('DELIVERED', 'APPROVED', 'REJECTED')",
            null
        )
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
