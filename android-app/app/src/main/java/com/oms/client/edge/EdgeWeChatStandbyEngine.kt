package com.oms.client.edge

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.oms.client.data.StreamItemModel
import com.oms.client.data.StreamRepository
import com.oms.client.transport.OmsFrameProtocol
import com.oms.core.ai.DualLlmEngine
import com.oms.core.ai.MiniJson
import com.oms.core.ai.WeChatArticleAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.Proxy
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class StandbyLeaseState {
    DISABLED,
    MAC_CONNECTED_IDLE,
    BLOCKED_AWAY_UNCONFIRMED,
    STANDBY_SCREEN_OFF_POWERSAVE,
    ACTIVE_POLLING
}

object StandbyLeasePolicy {
    const val MIN_DISCONNECT_DEBOUNCE_MS = 45_000L
    const val SCREEN_OFF_GRACE_MS = 120_000L

    fun evaluate(
        standbyFeatureEnabled: Boolean,
        hasClawBotAuth: Boolean,
        isMacConnected: Boolean,
        macSleepingFlagFromHost: Boolean,
        forceAwayStandbyEnabled: Boolean,
        disconnectedDurationMs: Long,
        pairedLanSsidHint: String,
        currentWifiSsid: String,
        isScreenInteractive: Boolean,
        msSinceScreenOff: Long,
        relayConfirmedMacOffline: Boolean = false
    ): StandbyLeaseState {
        if (!standbyFeatureEnabled || !hasClawBotAuth) return StandbyLeaseState.DISABLED
        if (isMacConnected) return StandbyLeaseState.MAC_CONNECTED_IDLE

        val cleanPairedSsid = pairedLanSsidHint.trim().removeSurrounding("\"")
        val cleanCurrentSsid = currentWifiSsid.trim().removeSurrounding("\"")
        val sameHomeLanConfirmedOffline =
            disconnectedDurationMs >= MIN_DISCONNECT_DEBOUNCE_MS &&
                cleanPairedSsid.isNotEmpty() &&
                cleanCurrentSsid.isNotEmpty() &&
                cleanCurrentSsid.equals(cleanPairedSsid, ignoreCase = true)

        val relayAwayConfirmedOffline =
            relayConfirmedMacOffline && disconnectedDurationMs >= 15_000L

        val macConfirmedNotPolling =
            macSleepingFlagFromHost || forceAwayStandbyEnabled || sameHomeLanConfirmedOffline || relayAwayConfirmedOffline

        if (!macConfirmedNotPolling) {
            return StandbyLeaseState.BLOCKED_AWAY_UNCONFIRMED
        }

        val userRecentlyActive = isScreenInteractive || (msSinceScreenOff in 0L..SCREEN_OFF_GRACE_MS)
        return if (userRecentlyActive) {
            StandbyLeaseState.ACTIVE_POLLING
        } else {
            StandbyLeaseState.STANDBY_SCREEN_OFF_POWERSAVE
        }
    }
}

object EdgeClawBotProtocol {
    private const val CHANNEL_VERSION = "2.4.10"
    private const val ILINK_CLIENT_VERSION = "132106" // (2 << 16) | (4 << 8) | 10
    private val rng = SecureRandom()
    private val urlRegex = Regex("""https?://[^\s"'<>\\\u3000-\u303F\u4E00-\u9FFF\uFF00-\uFFEF]+""", RegexOption.IGNORE_CASE)

    fun quoteUint64MessageIds(rawJson: String): String {
        return Regex(""""(message_id|msg_id|svr_id)"\s*:\s*(-?\d+)""")
            .replace(rawJson, "\"$1\":\"$2\"")
    }

    fun randomWeChatUin(): String {
        val bytes = ByteArray(4).also { rng.nextBytes(it) }
        val uint32 = ByteBuffer.wrap(bytes).int.toLong() and 0xFFFFFFFFL
        return Base64.getEncoder().encodeToString(uint32.toString().toByteArray(Charsets.UTF_8))
    }

    fun baseHeaders(botToken: String): Map<String, String> = mapOf(
        "Content-Type" to "application/json",
        "AuthorizationType" to "ilink_bot_token",
        "X-WECHAT-UIN" to randomWeChatUin(),
        "iLink-App-Id" to "bot",
        "iLink-App-ClientVersion" to ILINK_CLIENT_VERSION,
        "Authorization" to "Bearer ${botToken.trim()}"
    )

    fun baseInfoMap(): Map<String, Any> = linkedMapOf(
        "channel_version" to CHANNEL_VERSION,
        "bot_agent" to "OpenClaw"
    )

    fun extractPublicUrls(rawText: String): List<String> {
        if (rawText.isBlank()) return emptyList()
        val seen = LinkedHashSet<String>()
        for (m in urlRegex.findAll(rawText)) {
            val cleaned = m.value
                .replace("&amp;", "&")
                .trimEnd(')', ',', '.', ';', '!', '?', ':')
                .trim()
            if (cleaned.isEmpty()) continue
            if (cleaned.contains("novac2c.cdn.weixin.qq.com") ||
                cleaned.contains("wxapp.tc.qq.com") ||
                cleaned.contains("mmbiz.qpic.cn")
            ) {
                continue
            }
            seen.add(cleaned)
        }
        return seen.toList()
    }

    data class UnpackedInbound(
        val messageId: String,
        val fromUserId: String,
        val createTimeMs: Long,
        val contextToken: String,
        val userText: String,
        val urls: List<String>,
        val hasMediaAttachment: Boolean,
        val rawMessageJson: String
    )

    @Suppress("UNCHECKED_CAST")
    fun unpackMessage(msgMap: Map<String, Any?>): UnpackedInbound? {
        val msgType = (msgMap["message_type"] as? Number)?.toInt() ?: 1
        if (msgType != 1) return null
        val msgId = msgMap["message_id"]?.toString()?.trim().orEmpty()
        val fromUser = msgMap["from_user_id"]?.toString()?.trim().orEmpty()
        if (msgId.isEmpty() || fromUser.isEmpty()) return null

        val createTime = (msgMap["create_time_ms"] as? Number)?.toLong() ?: 0L
        val ctxToken = msgMap["context_token"]?.toString().orEmpty()
        val items = msgMap["item_list"] as? List<*> ?: emptyList<Any?>()

        val textParts = ArrayList<String>()
        val urls = LinkedHashSet<String>()
        var hasMedia = false

        for (rawItem in items) {
            val item = rawItem as? Map<String, Any?> ?: continue
            val t = (item["type"] as? Number)?.toInt() ?: 0
            when (t) {
                1 -> {
                    val txt = (item["text_item"] as? Map<String, Any?>)?.get("text")?.toString()?.trim().orEmpty()
                    if (txt.isNotEmpty()) textParts.add(txt)
                }
                3 -> {
                    val vTxt = (item["voice_item"] as? Map<String, Any?>)?.get("text")?.toString()?.trim().orEmpty()
                    if (vTxt.isNotEmpty()) textParts.add(vTxt)
                }
                2, 4, 5 -> hasMedia = true
            }
            val withoutRef = LinkedHashMap(item).apply { remove("ref_msg") }
            urls.addAll(extractPublicUrls(MiniJson.stringify(withoutRef)))
        }

        val combinedText = textParts.joinToString("\n").trim()
        if (combinedText.isEmpty() && urls.isEmpty() && !hasMedia) return null

        return UnpackedInbound(
            messageId = msgId,
            fromUserId = fromUser,
            createTimeMs = createTime,
            contextToken = ctxToken,
            userText = combinedText,
            urls = urls.toList(),
            hasMediaAttachment = hasMedia,
            rawMessageJson = MiniJson.stringify(msgMap)
        )
    }

    fun splitWeChatChunks(text: String, maxChars: Int = 2500): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val out = ArrayList<String>()
        var rem = text
        while (rem.length > maxChars) {
            val win = rem.substring(0, maxChars)
            var idx = win.lastIndexOf("\n\n")
            if (idx < maxChars * 2 / 5) idx = win.lastIndexOf('\n')
            if (idx <= 0) idx = maxChars
            out.add(rem.substring(0, idx).trimEnd())
            rem = rem.substring(idx).trimStart('\r', '\n')
        }
        if (rem.isNotEmpty()) out.add(rem)
        return out
    }
}

/**
 * Coordinates Zero-Split-Brain WeChat ClawBot standby polling when Mac is asleep,
 * runs GPT-6.2 initial screening over `:core:net-proxy`, replies directly in WeChat,
 * and atomically hands off processed articles & deferred videos upon Mac reconnection.
 */
class EdgeWeChatStandbyEngine(
    private val context: Context,
    private val store: EdgeSecureStore,
    private val streamRepo: StreamRepository,
    private val llmEngine: DualLlmEngine,
    private val sendHostRpc: suspend (method: String, params: JSONObject, timeoutMs: Long) -> JSONObject?,
    private val isRelayConfirmedMacOffline: () -> Boolean = { false }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val directWeChatHttp = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(38, TimeUnit.SECONDS)
        .build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val _leaseStateFlow = MutableStateFlow(StandbyLeaseState.DISABLED)
    val leaseStateFlow: StateFlow<StandbyLeaseState> = _leaseStateFlow

    private val _standbyHandledCountFlow = MutableStateFlow(0)
    val standbyHandledCountFlow: StateFlow<Int> = _standbyHandledCountFlow

    private val isMacConnected = AtomicBoolean(false)
    private val lastDisconnectedAtMs = AtomicLong(System.currentTimeMillis())
    private val lastScreenOffAtMs = AtomicLong(0L)
    private val handoffInFlight = AtomicBoolean(false)

    @Volatile private var currentPollCall: Call? = null
    private var loopJob: Job? = null

    fun start() {
        refreshPendingBadgeCount()
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (isActive) {
                val state = computeCurrentLeaseState()
                _leaseStateFlow.value = state
                if (state == StandbyLeaseState.ACTIVE_POLLING) {
                    runCatching { pollAndProcessOnce() }
                    delay(1200L)
                } else {
                    delay(4000L)
                }
            }
        }
    }

    fun onTransportTierChanged(tier: String) {
        val connected = tier != "DISCONNECTED"
        val prev = isMacConnected.getAndSet(connected)
        if (connected) {
            // Immediately abort any in-flight mobile getupdates call so Mac owns exclusive lease
            runCatching { currentPollCall?.cancel() }
            currentPollCall = null
            _leaseStateFlow.value = StandbyLeaseState.MAC_CONNECTED_IDLE
            triggerHandoffToMacIfNeeded()
        } else if (prev) {
            lastDisconnectedAtMs.set(System.currentTimeMillis())
            _leaseStateFlow.value = computeCurrentLeaseState()
        }
    }

    fun onScreenInteractiveChanged(interactive: Boolean) {
        if (!interactive) {
            lastScreenOffAtMs.set(System.currentTimeMillis())
        }
        _leaseStateFlow.value = computeCurrentLeaseState()
    }

    fun refreshState() {
        refreshPendingBadgeCount()
        _leaseStateFlow.value = computeCurrentLeaseState()
    }

    private fun refreshPendingBadgeCount() {
        val (a, d) = store.loadPendingStandbyQueue()
        _standbyHandledCountFlow.value = a.size + d.size
    }

    private fun isScreenCurrentlyInteractive(): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isInteractive != false
        } catch (_: Throwable) {
            true
        }
    }

    private fun currentWifiSsidSafe(): String {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val raw = wm?.connectionInfo?.ssid.orEmpty().trim().removeSurrounding("\"")
            if (raw == "<unknown ssid>") "" else raw
        } catch (_: Throwable) {
            ""
        }
    }

    private fun computeCurrentLeaseState(): StandbyLeaseState {
        val now = System.currentTimeMillis()
        val auth = store.loadClawBotAuth()
        val cursor = store.loadCursorState()
        val interactive = isScreenCurrentlyInteractive()
        val offAt = lastScreenOffAtMs.get()
        val msSinceOff = if (interactive) 0L else if (offAt > 0L) (now - offAt) else Long.MAX_VALUE
        val discDur = if (isMacConnected.get()) 0L else (now - lastDisconnectedAtMs.get()).coerceAtLeast(0L)

        return StandbyLeasePolicy.evaluate(
            standbyFeatureEnabled = store.isStandbyFeatureEnabled(),
            hasClawBotAuth = auth != null,
            isMacConnected = isMacConnected.get(),
            macSleepingFlagFromHost = cursor.macSleepingConfirmed,
            forceAwayStandbyEnabled = store.isForceAwayStandbyEnabled(),
            disconnectedDurationMs = discDur,
            pairedLanSsidHint = store.getPairedLanSsidHint(),
            currentWifiSsid = currentWifiSsidSafe(),
            isScreenInteractive = interactive,
            msSinceScreenOff = msSinceOff,
            relayConfirmedMacOffline = isRelayConfirmedMacOffline()
        )
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun pollAndProcessOnce() {
        if (isMacConnected.get()) return
        val auth = store.loadClawBotAuth() ?: return
        val cursor = store.loadCursorState()

        val base = auth.baseUrl.trimEnd('/')
        val reqBody = MiniJson.stringify(
            linkedMapOf(
                "get_updates_buf" to cursor.updatesBuf,
                "base_info" to EdgeClawBotProtocol.baseInfoMap()
            )
        )
        val reqBuilder = Request.Builder()
            .url("$base/ilink/bot/getupdates")
            .post(reqBody.toRequestBody(jsonMediaType))
        for ((k, v) in EdgeClawBotProtocol.baseHeaders(auth.botToken)) {
            reqBuilder.header(k, v)
        }

        val call = directWeChatHttp.newCall(reqBuilder.build())
        currentPollCall = call
        val rawResp = try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) return
                resp.body?.string().orEmpty()
            }
        } finally {
            if (currentPollCall === call) currentPollCall = null
        }

        if (isMacConnected.get() || rawResp.isBlank()) return
        val safeJson = EdgeClawBotProtocol.quoteUint64MessageIds(rawResp)
        val parsed = MiniJson.parseObject(safeJson)
        val retCode = (parsed["ret"] as? Number)?.toInt() ?: 0
        val errCode = (parsed["errcode"] as? Number)?.toInt() ?: 0
        if (retCode != 0 || errCode != 0) return

        val nextBuf = parsed["get_updates_buf"]?.toString().orEmpty()
        val rawMsgs = parsed["msgs"] as? List<*> ?: emptyList<Any?>()
        if (rawMsgs.isEmpty() && (nextBuf.isEmpty() || nextBuf == cursor.updatesBuf)) {
            return
        }

        var latestCtxToken = cursor.contextToken
        var latestCtxTime = cursor.contextSourceTimeMs
        var latestRecipient = cursor.recipientUserId

        val unpackedList = ArrayList<EdgeClawBotProtocol.UnpackedInbound>()
        for (m in rawMsgs) {
            val mm = m as? Map<String, Any?> ?: continue
            val unpacked = EdgeClawBotProtocol.unpackMessage(mm) ?: continue
            unpackedList.add(unpacked)
            if (unpacked.contextToken.isNotEmpty() && unpacked.createTimeMs >= latestCtxTime) {
                latestCtxToken = unpacked.contextToken
                latestCtxTime = unpacked.createTimeMs
                latestRecipient = unpacked.fromUserId
            }
        }

        store.saveCursorState(
            WeChatCursorState(
                updatesBuf = if (nextBuf.isNotEmpty()) nextBuf else cursor.updatesBuf,
                recipientUserId = latestRecipient,
                contextToken = latestCtxToken,
                contextSourceTimeMs = latestCtxTime,
                macSleepingConfirmed = cursor.macSleepingConfirmed,
                cursorAdvancedByPhone = true,
                updatedAtMs = System.currentTimeMillis()
            )
        )

        for (msg in unpackedList) {
            if (isMacConnected.get()) break
            processSingleInboundMessage(auth, msg, latestCtxToken)
        }
        refreshPendingBadgeCount()
    }

    private suspend fun processSingleInboundMessage(
        auth: ClawBotAuthConfig,
        msg: EdgeClawBotProtocol.UnpackedInbound,
        contextToken: String
    ) {
        val messageKey = "${auth.botId}:${msg.messageId}"
        val wxUrl = msg.urls.firstOrNull { EdgeWeChatExtractor.isWeChatMpUrl(it) }

        if (wxUrl != null && !msg.hasMediaAttachment) {
            val inlineNotes = msg.userText.replace(wxUrl, "").trim()
            try {
                val extracted = EdgeWeChatExtractor.extractArticleBlocking(wxUrl)
                val capsule = store.loadContextCapsule()
                val analysis = WeChatArticleAnalyzer.analyzeArticle(
                    engine = llmEngine,
                    article = extracted,
                    userNotes = inlineNotes,
                    capsule = capsule
                )
                val replyBody = "📱 [咫尺夜间直连初筛 · ${analysis.modelUsed}]\n\n${analysis.wechatReply}"
                val delivered = sendWeChatReply(auth, msg.fromUserId, replyBody, contextToken, "edge_${msg.messageId}")

                store.appendPendingProcessedArticle(
                    PendingProcessedArticle(
                        messageKey = messageKey,
                        senderId = msg.fromUserId,
                        url = extracted.url,
                        title = extracted.title,
                        author = extracted.author,
                        publishDate = extracted.publishDate,
                        digest = extracted.digest,
                        userNotes = inlineNotes,
                        englishSlug = analysis.englishSlug,
                        triage = analysis.triage,
                        extractionQualityStatus = extracted.extractionQualityStatus,
                        rawMarkdownBody = extracted.markdownBody,
                        wechatReplyText = analysis.wechatReply,
                        companionNoteMarkdown = analysis.companionNoteMarkdown,
                        replyDeliveredToWeChat = delivered,
                        modelUsed = analysis.modelUsed,
                        createdAtMs = System.currentTimeMillis()
                    )
                )

                // Immediately surface in 咫尺 App's local 💬 微信尺素 stream for headphone TTS & original text viewing!
                val localItemId = "edge_wx_${OmsFrameProtocol.generateUuidV7()}"
                val savedSourcePath = if (extracted.markdownBody.isNotBlank()) {
                    runCatching {
                        val cacheDir = java.io.File(context.filesDir, "chisu_cache").apply { mkdirs() }
                        val sourceFile = java.io.File(cacheDir, "${localItemId}_source.md")
                        sourceFile.writeText(extracted.markdownBody, Charsets.UTF_8)
                        sourceFile.absolutePath
                    }.getOrNull()
                } else null

                streamRepo.upsertStreamItem(
                    StreamItemModel(
                        itemId = localItemId,
                        direction = "MAC_TO_PHONE",
                        itemType = "TEXT",
                        intentTag = "wechat_context",
                        title = "💬 ${extracted.title}",
                        textContent = analysis.companionNoteMarkdown,
                        voiceTranscript = null,
                        localFileUri = null,
                        macSavedPath = null,
                        syncStatus = "DELIVERED",
                        metadataJson = JSONObject().apply {
                            put("enableCommuteTts", true)
                            put("edgeAnalyzed", true)
                            put("modelUsed", analysis.modelUsed)
                            put("sourceUrl", extracted.url)
                            put("author", extracted.author)
                            put("publishDate", extracted.publishDate)
                            put("triage", analysis.triage)
                            put("userNotes", inlineNotes)
                            put("shortSummary", analysis.wechatReply)
                            if (!savedSourcePath.isNullOrBlank()) {
                                put("sourceFilePath", savedSourcePath)
                                put("hasSource", true)
                            }
                        }.toString(),
                        createdAtMs = System.currentTimeMillis()
                    )
                )
                return
            } catch (_: Throwable) {
                // Fall through to deferred queue if MP link required desktop Chrome verification
            }
        }

        // L2: Deferred item (videos, Douyin/XHS/YouTube, complex agent tasks, or dynamic-only pages)
        store.appendPendingDeferredMessage(
            PendingDeferredMessage(
                messageKey = messageKey,
                rawPayloadJson = msg.rawMessageJson
            )
        )
        sendWeChatReply(
            auth = auth,
            toUserId = msg.fromUserId,
            text = "🌙 Mac 当前休眠中，手机「咫尺」已为你锁定此消息并排入晨间队列，电脑唤醒重连后将自动补齐深度解析与归档。",
            contextToken = contextToken,
            clientIdPrefix = "edge_defer_${msg.messageId}"
        )
    }

    private fun sendWeChatReply(
        auth: ClawBotAuthConfig,
        toUserId: String,
        text: String,
        contextToken: String,
        clientIdPrefix: String
    ): Boolean {
        val base = auth.baseUrl.trimEnd('/')
        val chunks = EdgeClawBotProtocol.splitWeChatChunks(text, 2500)
        for ((idx, chunk) in chunks.withIndex()) {
            val cid = if (idx == 0) clientIdPrefix else "${clientIdPrefix}_p${idx + 1}"
            val okWithToken = postSingleWeChatChunk(base, auth.botToken, toUserId, chunk, cid, contextToken.takeIf { it.isNotBlank() })
            if (!okWithToken && contextToken.isNotBlank()) {
                if (!postSingleWeChatChunk(base, auth.botToken, toUserId, chunk, cid, null)) {
                    return false
                }
            } else if (!okWithToken) {
                return false
            }
        }
        return true
    }

    private fun postSingleWeChatChunk(
        baseUrl: String,
        botToken: String,
        toUserId: String,
        chunkText: String,
        clientId: String,
        ctxToken: String?
    ): Boolean {
        return try {
            val msgObj = linkedMapOf<String, Any?>(
                "from_user_id" to "",
                "to_user_id" to toUserId,
                "client_id" to clientId,
                "message_type" to 2,
                "message_state" to 2,
                "item_list" to listOf(
                    linkedMapOf("type" to 1, "text_item" to linkedMapOf("text" to chunkText))
                )
            )
            if (!ctxToken.isNullOrBlank()) {
                msgObj["context_token"] = ctxToken
            }
            val payload = MiniJson.stringify(
                linkedMapOf("msg" to msgObj, "base_info" to EdgeClawBotProtocol.baseInfoMap())
            )
            val reqBuilder = Request.Builder()
                .url("$baseUrl/ilink/bot/sendmessage")
                .post(payload.toRequestBody(jsonMediaType))
            for ((k, v) in EdgeClawBotProtocol.baseHeaders(botToken)) {
                reqBuilder.header(k, v)
            }
            directWeChatHttp.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val obj = MiniJson.parseObject(resp.body?.string().orEmpty())
                val ret = (obj["ret"] as? Number)?.toInt() ?: 0
                val err = (obj["errcode"] as? Number)?.toInt() ?: 0
                ret == 0 && err == 0
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun triggerHandoffToMacIfNeeded() {
        val cursor = store.loadCursorState()
        val (articles, deferred) = store.loadPendingStandbyQueue()
        if (!cursor.cursorAdvancedByPhone && articles.isEmpty() && deferred.isEmpty()) return
        if (!handoffInFlight.compareAndSet(false, true)) return

        scope.launch {
            try {
                val params = JSONObject().apply {
                    put("handoffId", "hoff_${UUID.randomUUID()}")
                    put("latestUpdatesBuf", cursor.updatesBuf)
                    put("recipientUserId", cursor.recipientUserId)
                    put("latestContextToken", cursor.contextToken)
                    put("contextSourceTimeMs", cursor.contextSourceTimeMs)
                    put("cursorAdvancedByPhone", cursor.cursorAdvancedByPhone)
                    put("handedOffAtMs", System.currentTimeMillis())
                    put("processedArticles", JSONArray().apply {
                        for (a in articles) {
                            put(JSONObject().apply {
                                put("messageKey", a.messageKey)
                                put("senderId", a.senderId)
                                put("url", a.url)
                                put("title", a.title)
                                put("author", a.author)
                                put("publishDate", a.publishDate)
                                put("digest", a.digest)
                                put("userNotes", a.userNotes)
                                put("englishSlug", a.englishSlug)
                                put("triage", a.triage)
                                put("extractionQualityStatus", a.extractionQualityStatus)
                                put("rawMarkdownBody", a.rawMarkdownBody)
                                put("wechatReplyText", a.wechatReplyText)
                                put("companionNoteMarkdown", a.companionNoteMarkdown)
                                put("replyDeliveredToWeChat", a.replyDeliveredToWeChat)
                                put("modelUsed", a.modelUsed)
                                put("createdAtMs", a.createdAtMs)
                            })
                        }
                    })
                    put("deferredMessages", JSONArray().apply {
                        for (d in deferred) {
                            put(JSONObject().apply {
                                put("messageKey", d.messageKey)
                                put("rawPayloadJson", d.rawPayloadJson)
                            })
                        }
                    })
                }
                val res = sendHostRpc("wechat.standby.handoff", params, 45_000L)
                if (res != null && res.optBoolean("ok", false)) {
                    store.clearPendingStandbyQueue()
                    refreshPendingBadgeCount()
                }
            } catch (_: Throwable) {
            } finally {
                handoffInFlight.set(false)
            }
        }
    }
}
