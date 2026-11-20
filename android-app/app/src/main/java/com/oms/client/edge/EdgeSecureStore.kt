package com.oms.client.edge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.oms.core.ai.AiModelCatalog
import com.oms.core.ai.ContextCapsule
import com.oms.core.ai.EdgeAiRuntimeConfig
import com.oms.core.ai.MiniJson
import com.oms.core.ai.ModelCatalogItem
import com.oms.core.ai.ModelCatalogRegistry
import com.oms.core.ai.ModelRoleCategory
import com.oms.core.ai.ProviderEndpointConfig
import com.oms.core.ai.ProxyNodeCodec
import com.oms.core.ai.VaultCandidateNote
import com.oms.core.proxy.DEFAULT_MAX_PREFER_LATENCY_MS
import com.oms.core.proxy.DEFAULT_PREFER_REGIONS
import com.oms.core.proxy.LEGACY_DEFAULT_PREFER_REGIONS
import com.oms.core.proxy.ProxyNode
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ClawBotAuthConfig(
    val botToken: String,
    val baseUrl: String,
    val botId: String,
    val userId: String
)

data class WanRelayStoredConfig(
    val enabled: Boolean,
    val wssUrl: String,
    val authToken: String,
    val caCertPem: String = ""
)

data class WeChatCursorState(
    val updatesBuf: String = "",
    val recipientUserId: String = "",
    val contextToken: String = "",
    val contextSourceTimeMs: Long = 0L,
    val macSleepingConfirmed: Boolean = false,
    val cursorAdvancedByPhone: Boolean = false,
    val updatedAtMs: Long = 0L
)

data class PendingProcessedArticle(
    val messageKey: String,
    val senderId: String,
    val url: String,
    val title: String,
    val author: String,
    val publishDate: String,
    val digest: String,
    val userNotes: String,
    val englishSlug: String,
    val triage: String,
    val extractionQualityStatus: String,
    val rawMarkdownBody: String,
    val wechatReplyText: String,
    val companionNoteMarkdown: String,
    val replyDeliveredToWeChat: Boolean,
    val modelUsed: String,
    val createdAtMs: Long
)

data class PendingDeferredMessage(
    val messageKey: String,
    val rawPayloadJson: String
)

/**
 * Hardware-backed AndroidKeyStore AES-GCM encrypted store for Edge Proxy subscriptions,
 * OpenAI/Gemini API keys, WeChat ClawBot credentials, cursor state, Context Capsule, and offline handoff queue.
 */
class EdgeSecureStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("oms_edge_secure_v2", Context.MODE_PRIVATE)
    private val lock = Any()

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "oms_edge_secure_aes256_v2"
    }

    private fun getOrCreateKey(): SecretKey? {
        return try {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val existing = (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            if (existing != null) return existing
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            kg.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            kg.generateKey()
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeEncryptedJson(prefKey: String, data: Any?) {
        val plain = MiniJson.stringify(data)
        val key = getOrCreateKey() ?: return
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val ivB64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            val ctB64 = Base64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            prefs.edit().putString(prefKey, "v2:$ivB64:$ctB64").apply()
        } catch (_: Throwable) {
        }
    }

    private fun readEncryptedJsonObj(prefKey: String): Map<String, Any?>? {
        val raw = prefs.getString(prefKey, null) ?: return null
        if (!raw.startsWith("v2:")) return null
        val parts = raw.split(':', limit = 3)
        if (parts.size != 3) return null
        val key = getOrCreateKey() ?: return null
        return try {
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val ct = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            val plain = String(cipher.doFinal(ct), Charsets.UTF_8)
            MiniJson.parseObject(plain)
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun saveSyncedEdgeConfig(rawParamsJson: String) = synchronized(lock) {
        val m = MiniJson.parseObject(rawParamsJson)
        writeEncryptedJson("edge_config", m)
        val ssidHint = m["pairedLanSsidHint"]?.toString()?.trim().orEmpty()
        if (ssidHint.isNotEmpty()) {
            prefs.edit().putString("paired_lan_ssid_hint", ssidHint).apply()
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun loadProxyBootstrapConfig(): Triple<List<ProxyNode>, List<String>, Pair<String, Double>> = synchronized(lock) {
        val m = readEncryptedJsonObj("edge_config")
            ?: return Triple(emptyList(), emptyList(), DEFAULT_PREFER_REGIONS to DEFAULT_MAX_PREFER_LATENCY_MS)
        val nodes = (m["bootstrapNodes"] as? List<*>)?.let { ProxyNodeCodec.fromJsonList(it) } ?: emptyList()
        val urls = (m["subscriptionUrls"] as? List<*>)?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) } ?: emptyList()
        val rawPrefer = m["preferRegions"]?.toString()?.trim().orEmpty()
        val prefer = if (rawPrefer.isEmpty() || rawPrefer == LEGACY_DEFAULT_PREFER_REGIONS) {
            DEFAULT_PREFER_REGIONS
        } else {
            rawPrefer
        }
        val maxLat = (m["maxPreferredLatencyMs"] as? Number)?.toDouble() ?: DEFAULT_MAX_PREFER_LATENCY_MS
        Triple(nodes, urls, prefer to maxLat)
    }

    @Suppress("UNCHECKED_CAST")
    fun loadAiRuntimeConfig(): EdgeAiRuntimeConfig = synchronized(lock) {
        val m = readEncryptedJsonObj("edge_config") ?: return EdgeAiRuntimeConfig()
        fun parseProv(key: String, defFast: String, defPro: String, defReason: String): ProviderEndpointConfig? {
            val pm = m[key] as? Map<String, Any?> ?: return null
            val apiKey = pm["apiKey"]?.toString()?.trim().orEmpty()
            if (apiKey.isEmpty()) return null
            return ProviderEndpointConfig(
                providerId = pm["providerId"]?.toString() ?: key,
                baseUrl = pm["baseUrl"]?.toString().orEmpty(),
                apiKey = apiKey,
                fastModel = pm["fastModel"]?.toString()?.ifBlank { defFast } ?: defFast,
                flagshipModel = pm["flagshipModel"]?.toString()?.ifBlank { defPro } ?: defPro,
                reasoningModel = pm["reasoningModel"]?.toString()?.ifBlank { defReason } ?: defReason,
                liveModel = pm["liveModel"]?.toString().orEmpty(),
                transcribeModel = pm["transcribeModel"]?.toString().orEmpty()
            )
        }
        EdgeAiRuntimeConfig(
            openai = parseProv("openai", AiModelCatalog.OPENAI_SOL, AiModelCatalog.OPENAI_SOL, AiModelCatalog.OPENAI_ASTRA),
            gemini = parseProv("gemini", AiModelCatalog.GEMINI_FLASH, AiModelCatalog.GEMINI_ARGON, AiModelCatalog.GEMINI_ARGON),
            domesticFallback = parseProv("domesticFallback", "deepseek-chat", "deepseek-chat", "deepseek-reasoner")
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun loadClawBotAuth(): ClawBotAuthConfig? = synchronized(lock) {
        val m = readEncryptedJsonObj("edge_config") ?: return null
        val cm = m["clawbotAuth"] as? Map<String, Any?> ?: return null
        val tok = cm["botToken"]?.toString()?.trim().orEmpty()
        if (tok.isEmpty()) return null
        ClawBotAuthConfig(
            botToken = tok,
            baseUrl = cm["baseUrl"]?.toString()?.ifBlank { "https://ilinkai.weixin.qq.com" } ?: "https://ilinkai.weixin.qq.com",
            botId = cm["botId"]?.toString().orEmpty(),
            userId = cm["userId"]?.toString().orEmpty()
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun loadWanRelayConfig(): WanRelayStoredConfig? = synchronized(lock) {
        val m = readEncryptedJsonObj("edge_config") ?: return null
        val wm = m["wanRelay"] as? Map<String, Any?> ?: return null
        val wssUrl = wm["wssUrl"]?.toString()?.trim().orEmpty()
        val authToken = wm["authToken"]?.toString()?.trim().orEmpty()
        if (wssUrl.isEmpty() || authToken.isEmpty()) return null
        val enabled = wm["enabled"] as? Boolean ?: true
        WanRelayStoredConfig(
            enabled = enabled,
            wssUrl = wssUrl,
            authToken = authToken,
            caCertPem = wm["caCertPem"]?.toString()?.trim().orEmpty()
        )
    }

    fun getPairedLanSsidHint(): String = prefs.getString("paired_lan_ssid_hint", "") ?: ""

    fun updatePairedLanSsidHint(ssid: String) {
        val clean = ssid.trim().removeSurrounding("\"")
        if (clean.isNotEmpty() && clean != "<unknown ssid>") {
            prefs.edit().putString("paired_lan_ssid_hint", clean).apply()
        }
    }

    fun isForceAwayStandbyEnabled(): Boolean = prefs.getBoolean("force_away_standby", false)

    fun setForceAwayStandbyEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("force_away_standby", enabled).apply()
    }

    fun isStandbyFeatureEnabled(): Boolean = prefs.getBoolean("edge_wechat_standby_enabled", true)

    fun setStandbyFeatureEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("edge_wechat_standby_enabled", enabled).apply()
    }

    fun saveContextCapsule(rawJson: String) = synchronized(lock) {
        writeEncryptedJson("context_capsule", MiniJson.parseObject(rawJson))
    }

    @Suppress("UNCHECKED_CAST")
    fun loadContextCapsule(): ContextCapsule = synchronized(lock) {
        val m = readEncryptedJsonObj("context_capsule") ?: return ContextCapsule()
        val warnings = (m["analysisWarnings"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        val candidates = (m["matchedCandidates"] as? List<*>)?.mapNotNull { item ->
            val cm = item as? Map<String, Any?> ?: return@mapNotNull null
            VaultCandidateNote(
                title = cm["title"]?.toString().orEmpty(),
                wikiLink = cm["wikiLink"]?.toString().orEmpty(),
                excerpt = cm["excerpt"]?.toString().orEmpty()
            )
        } ?: emptyList()
        val mem = (m["recentWorkingMemory"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        ContextCapsule(
            revision = m["revision"]?.toString().orEmpty(),
            generatedAtIso = m["generatedAtIso"]?.toString().orEmpty(),
            nowSummary = m["nowSummary"]?.toString().orEmpty(),
            queueSummary = m["queueSummary"]?.toString().orEmpty(),
            topicsOverview = m["topicsOverview"]?.toString().orEmpty(),
            analysisBackgroundFormatted = m["analysisBackgroundFormatted"]?.toString().orEmpty(),
            analysisWarnings = warnings,
            matchedCandidates = candidates,
            recentWorkingMemory = mem
        )
    }

    fun loadCursorState(): WeChatCursorState = synchronized(lock) {
        val m = readEncryptedJsonObj("wechat_cursor") ?: return WeChatCursorState()
        WeChatCursorState(
            updatesBuf = m["updatesBuf"]?.toString().orEmpty(),
            recipientUserId = m["recipientUserId"]?.toString().orEmpty(),
            contextToken = m["contextToken"]?.toString().orEmpty(),
            contextSourceTimeMs = (m["contextSourceTimeMs"] as? Number)?.toLong() ?: 0L,
            macSleepingConfirmed = m["macSleepingConfirmed"] == true,
            cursorAdvancedByPhone = m["cursorAdvancedByPhone"] == true,
            updatedAtMs = (m["updatedAtMs"] as? Number)?.toLong() ?: 0L
        )
    }

    fun saveCursorState(state: WeChatCursorState) = synchronized(lock) {
        writeEncryptedJson(
            "wechat_cursor",
            linkedMapOf(
                "updatesBuf" to state.updatesBuf,
                "recipientUserId" to state.recipientUserId,
                "contextToken" to state.contextToken,
                "contextSourceTimeMs" to state.contextSourceTimeMs,
                "macSleepingConfirmed" to state.macSleepingConfirmed,
                "cursorAdvancedByPhone" to state.cursorAdvancedByPhone,
                "updatedAtMs" to state.updatedAtMs
            )
        )
    }

    fun updateCursorFromMacCheckpoint(rawJson: String) = synchronized(lock) {
        val m = MiniJson.parseObject(rawJson)
        val cur = loadCursorState()
        // Do not overwrite un-handed-off phone cursor progress before reconnect handoff finishes
        if (cur.cursorAdvancedByPhone || hasPendingHandoffItems()) {
            if (m["macSleeping"] == true) {
                saveCursorState(cur.copy(macSleepingConfirmed = true))
            }
            return@synchronized
        }
        val incomingTime = (m["contextSourceTimeMs"] as? Number)?.toLong() ?: 0L
        val keepNewerCtx = incomingTime >= cur.contextSourceTimeMs || !m["contextToken"]?.toString().isNullOrBlank()
        saveCursorState(
            WeChatCursorState(
                updatesBuf = m["updatesBuf"]?.toString() ?: cur.updatesBuf,
                recipientUserId = m["recipientUserId"]?.toString()?.ifBlank { cur.recipientUserId } ?: cur.recipientUserId,
                contextToken = if (keepNewerCtx) (m["contextToken"]?.toString() ?: cur.contextToken) else cur.contextToken,
                contextSourceTimeMs = maxOf(cur.contextSourceTimeMs, incomingTime),
                macSleepingConfirmed = m["macSleeping"] == true,
                cursorAdvancedByPhone = false,
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun loadPendingStandbyQueue(): Pair<List<PendingProcessedArticle>, List<PendingDeferredMessage>> = synchronized(lock) {
        val m = readEncryptedJsonObj("pending_standby_handoff") ?: return emptyList<PendingProcessedArticle>() to emptyList()
        val articles = (m["articles"] as? List<*>)?.mapNotNull { raw ->
            val am = raw as? Map<String, Any?> ?: return@mapNotNull null
            PendingProcessedArticle(
                messageKey = am["messageKey"]?.toString().orEmpty(),
                senderId = am["senderId"]?.toString().orEmpty(),
                url = am["url"]?.toString().orEmpty(),
                title = am["title"]?.toString().orEmpty(),
                author = am["author"]?.toString().orEmpty(),
                publishDate = am["publishDate"]?.toString().orEmpty(),
                digest = am["digest"]?.toString().orEmpty(),
                userNotes = am["userNotes"]?.toString().orEmpty(),
                englishSlug = am["englishSlug"]?.toString().orEmpty(),
                triage = am["triage"]?.toString() ?: "process_later",
                extractionQualityStatus = am["extractionQualityStatus"]?.toString() ?: "complete",
                rawMarkdownBody = am["rawMarkdownBody"]?.toString().orEmpty(),
                wechatReplyText = am["wechatReplyText"]?.toString().orEmpty(),
                companionNoteMarkdown = am["companionNoteMarkdown"]?.toString().orEmpty(),
                replyDeliveredToWeChat = am["replyDeliveredToWeChat"] == true,
                modelUsed = am["modelUsed"]?.toString().orEmpty(),
                createdAtMs = (am["createdAtMs"] as? Number)?.toLong() ?: 0L
            )
        } ?: emptyList()

        val deferred = (m["deferred"] as? List<*>)?.mapNotNull { raw ->
            val dm = raw as? Map<String, Any?> ?: return@mapNotNull null
            PendingDeferredMessage(
                messageKey = dm["messageKey"]?.toString().orEmpty(),
                rawPayloadJson = dm["rawPayloadJson"]?.toString().orEmpty()
            )
        } ?: emptyList()

        articles to deferred
    }

    fun hasPendingHandoffItems(): Boolean = synchronized(lock) {
        val (a, d) = loadPendingStandbyQueue()
        a.isNotEmpty() || d.isNotEmpty()
    }

    fun appendPendingProcessedArticle(article: PendingProcessedArticle) = synchronized(lock) {
        val (curA, curD) = loadPendingStandbyQueue()
        if (curA.any { it.messageKey == article.messageKey }) return@synchronized
        savePendingQueueInternal(curA + article, curD)
    }

    fun appendPendingDeferredMessage(msg: PendingDeferredMessage) = synchronized(lock) {
        val (curA, curD) = loadPendingStandbyQueue()
        if (curD.any { it.messageKey == msg.messageKey }) return@synchronized
        savePendingQueueInternal(curA, curD + msg)
    }

    fun clearPendingStandbyQueue() = synchronized(lock) {
        savePendingQueueInternal(emptyList(), emptyList())
        val cur = loadCursorState()
        saveCursorState(cur.copy(cursorAdvancedByPhone = false, macSleepingConfirmed = false))
    }

    private fun savePendingQueueInternal(
        articles: List<PendingProcessedArticle>,
        deferred: List<PendingDeferredMessage>
    ) {
        writeEncryptedJson(
            "pending_standby_handoff",
            linkedMapOf(
                "articles" to articles.map { a ->
                    linkedMapOf(
                        "messageKey" to a.messageKey,
                        "senderId" to a.senderId,
                        "url" to a.url,
                        "title" to a.title,
                        "author" to a.author,
                        "publishDate" to a.publishDate,
                        "digest" to a.digest,
                        "userNotes" to a.userNotes,
                        "englishSlug" to a.englishSlug,
                        "triage" to a.triage,
                        "extractionQualityStatus" to a.extractionQualityStatus,
                        "rawMarkdownBody" to a.rawMarkdownBody,
                        "wechatReplyText" to a.wechatReplyText,
                        "companionNoteMarkdown" to a.companionNoteMarkdown,
                        "replyDeliveredToWeChat" to a.replyDeliveredToWeChat,
                        "modelUsed" to a.modelUsed,
                        "createdAtMs" to a.createdAtMs
                    )
                },
                "deferred" to deferred.map { d ->
                    linkedMapOf(
                        "messageKey" to d.messageKey,
                        "rawPayloadJson" to d.rawPayloadJson
                    )
                }
            )
        )
    }

    // =========================================================================
    // v2.1: Proxy Routing Policy, Categorized Model Overrides & Workspace Cache
    // =========================================================================

    fun loadProxyUserPolicy(): Pair<String, String?> {
        val mode = prefs.getString("proxy_user_routing_mode", "rule") ?: "rule"
        val pinned = prefs.getString("proxy_user_pinned_key", null)?.takeIf { it.isNotBlank() }
        return mode to pinned
    }

    fun saveProxyUserPolicy(mode: String, pinnedKey: String?) {
        prefs.edit()
            .putString("proxy_user_routing_mode", mode.lowercase())
            .putString("proxy_user_pinned_key", pinnedKey ?: "")
            .apply()
    }

    fun loadModelRoleSelections(): Map<ModelRoleCategory, String> {
        val defaults = ModelCatalogRegistry.defaultRoleSelections().toMutableMap()
        val raw = prefs.getString("model_role_selections_v1", null) ?: return defaults
        runCatching {
            val map = MiniJson.parseObject(raw)
            for (cat in ModelRoleCategory.values()) {
                val v = map[cat.name]?.toString()?.trim().orEmpty()
                if (v.isNotEmpty()) {
                    val prov = v.substringBefore(':', "")
                    val mod = v.substringAfter(':', v)
                    if (!ModelCatalogRegistry.shouldHideLegacyOrDatedModel(prov, mod)) {
                        defaults[cat] = v
                    }
                }
            }
        }
        return defaults
    }

    fun saveModelRoleSelections(selections: Map<ModelRoleCategory, String>) {
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in selections) out[k.name] = v
        prefs.edit().putString("model_role_selections_v1", MiniJson.stringify(out)).apply()
    }

    @Suppress("UNCHECKED_CAST")
    fun loadCachedModelCatalog(): Pair<List<ModelCatalogItem>, Long> {
        val refreshedAt = prefs.getLong("model_catalog_refreshed_at", 0L)
        val raw = prefs.getString("model_catalog_cache_v1", null)
            ?: return ModelCatalogRegistry.BUILTIN_PRESETS to refreshedAt
        return runCatching {
            val obj = MiniJson.parseObject(raw)
            val list = obj["items"] as? List<*> ?: return ModelCatalogRegistry.BUILTIN_PRESETS to refreshedAt
            val parsed = list.mapNotNull { entry ->
                val m = entry as? Map<String, Any?> ?: return@mapNotNull null
                val prov = m["providerId"]?.toString().orEmpty()
                val id = m["modelId"]?.toString().orEmpty()
                if (prov.isEmpty() || id.isEmpty()) return@mapNotNull null
                val cats = (m["categories"] as? List<*>)?.mapNotNull { cName ->
                    runCatching { ModelRoleCategory.valueOf(cName.toString()) }.getOrNull()
                }?.toSet() ?: emptySet()
                if (cats.isEmpty()) return@mapNotNull null
                ModelCatalogItem(
                    providerId = prov,
                    modelId = id,
                    label = m["label"]?.toString()?.ifBlank { id } ?: id,
                    description = m["description"]?.toString().orEmpty(),
                    categories = cats,
                    isLiveDiscovered = m["isLiveDiscovered"] == true
                )
            }
            val cleaned = ModelCatalogRegistry.filterCleanCatalog(parsed)
            (if (cleaned.isNotEmpty()) cleaned else ModelCatalogRegistry.BUILTIN_PRESETS) to refreshedAt
        }.getOrDefault(ModelCatalogRegistry.BUILTIN_PRESETS to refreshedAt)
    }

    fun saveCachedModelCatalog(items: List<ModelCatalogItem>, refreshedAtMs: Long) {
        val serialized = linkedMapOf(
            "items" to items.map { item ->
                linkedMapOf(
                    "providerId" to item.providerId,
                    "modelId" to item.modelId,
                    "label" to item.label,
                    "description" to item.description,
                    "categories" to item.categories.map { it.name },
                    "isLiveDiscovered" to item.isLiveDiscovered
                )
            }
        )
        prefs.edit()
            .putString("model_catalog_cache_v1", MiniJson.stringify(serialized))
            .putLong("model_catalog_refreshed_at", refreshedAtMs)
            .apply()
    }

    fun saveCachedWorkspaceCatalogJson(rawJson: String) {
        prefs.edit().putString("agent_workspace_catalog_cache", rawJson).apply()
    }

    fun loadCachedWorkspaceCatalogJson(): String? =
        prefs.getString("agent_workspace_catalog_cache", null)

    fun saveCachedChisuCatalogJson(rawJson: String) {
        prefs.edit().putString("chisu_articles_catalog_cache_v1", rawJson).apply()
    }

    fun loadCachedChisuCatalogJson(): String? =
        prefs.getString("chisu_articles_catalog_cache_v1", null)

    fun saveLiveVoiceName(mode: com.oms.core.ai.RealtimeBackendMode, voiceId: String) {
        val key = if (mode == com.oms.core.ai.RealtimeBackendMode.OPENAI_WALK_TALK) {
            "live_voice_openai_v1"
        } else {
            "live_voice_gemini_v1"
        }
        val valid = com.oms.core.ai.RealtimeVoiceCatalog.resolveVoice(mode, voiceId).id
        prefs.edit().putString(key, valid).apply()
    }

    fun loadLiveVoiceName(mode: com.oms.core.ai.RealtimeBackendMode): String {
        val key = if (mode == com.oms.core.ai.RealtimeBackendMode.OPENAI_WALK_TALK) {
            "live_voice_openai_v1"
        } else {
            "live_voice_gemini_v1"
        }
        return com.oms.core.ai.RealtimeVoiceCatalog.resolveVoice(mode, prefs.getString(key, null)).id
    }

    private fun loadAllAgentDraftsMap(): LinkedHashMap<String, String> {
        val raw = prefs.getString("agent_session_drafts_v1", null) ?: return LinkedHashMap()
        val out = LinkedHashMap<String, String>()
        runCatching {
            val parsed = MiniJson.parseObject(raw)
            for ((k, v) in parsed) {
                val s = v?.toString().orEmpty()
                if (k.isNotBlank() && s.isNotEmpty()) {
                    out[k] = s
                }
            }
        }
        return out
    }

    fun loadAgentDraft(draftKey: String): String {
        val k = draftKey.trim()
        if (k.isEmpty()) return ""
        return loadAllAgentDraftsMap()[k].orEmpty()
    }

    fun hasAgentDraft(draftKey: String): Boolean = loadAgentDraft(draftKey).isNotBlank()

    fun saveAgentDraft(draftKey: String, text: String) {
        val k = draftKey.trim()
        if (k.isEmpty()) return
        val map = loadAllAgentDraftsMap()
        if (text.isEmpty()) {
            map.remove(k)
        } else {
            // Re-insert so most recently edited draft stays at the end; cap at 40 entries
            map.remove(k)
            map[k] = text.take(8000)
            while (map.size > 40) {
                val oldestKey = map.keys.firstOrNull() ?: break
                map.remove(oldestKey)
            }
        }
        prefs.edit().putString("agent_session_drafts_v1", MiniJson.stringify(map)).apply()
    }
}
