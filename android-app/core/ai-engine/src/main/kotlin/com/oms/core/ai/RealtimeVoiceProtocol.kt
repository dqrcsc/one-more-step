package com.oms.core.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class RealtimeBackendMode {
    OPENAI_WALK_TALK,   // OpenAI `gpt-realtime-2.1` full-duplex speech-to-speech + web_search function tool
    GEMINI_GLASS_LIVE   // Google `gemini-3.8-live` BidiGenerateContent audio + 1fps camera + Search Grounding
}

data class RealtimeSessionOptions(
    val mode: RealtimeBackendMode,
    val englishOnlyFluencyMode: Boolean = false,
    val voiceName: String = "",
    val extraContextSummary: String = ""
)

interface RealtimeSessionListener {
    fun onSessionReady(mode: RealtimeBackendMode, modelId: String)
    fun onPcm24kAudioOut(pcm24kMonoLe: ByteArray)
    fun onUserTranscript(text: String, isFinal: Boolean)
    fun onAssistantTranscriptDelta(delta: String)
    fun onTurnComplete()
    fun onInterruptedBargeIn()
    fun onStatusHint(hint: String) {}
    fun onError(message: String)
    fun onClosed()
}

/**
 * Pure-Kotlin WebSocket full-duplex engine for:
 *  1) OpenAI `gpt-realtime-2.1` Walk & Talk (with real-time `web_search` function calling & English fluency mode)
 *  2) Google `gemini-3.8-live` Google Glass real-time 1fps camera + bidirectional voice + Search Grounding
 *     (with 9s handshake watchdog, proper `onClosing` handling, and automatic proxy-node failover retry)
 */
class RealtimeVoiceSessionEngine(
    private val httpClientProvider: () -> OkHttpClient,
    private val runtimeConfigProvider: () -> EdgeAiRuntimeConfig,
    private val roleSelectionProvider: (ModelRoleCategory) -> String = { it.defaultSelection },
    private val webSearchExecutor: (suspend (String) -> String)? = null,
    private val onNodeHandshakeFailed: ((host: String, geoBlocked: Boolean) -> Unit)? = null,
    private val setupTimeoutMs: Long = 9000L
) {
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var activeWebSocket: WebSocket? = null
    @Volatile
    private var activeMode: RealtimeBackendMode = RealtimeBackendMode.OPENAI_WALK_TALK
    private val isConnected = AtomicBoolean(false)
    private val sessionGen = AtomicInteger(0)
    private var setupWatchdogJob: Job? = null
    private val handledToolCallIds = ConcurrentHashMap.newKeySet<String>()

    fun isActive(): Boolean = isConnected.get() && activeWebSocket != null

    fun startSession(options: RealtimeSessionOptions, listener: RealtimeSessionListener) {
        stopSession()
        handledToolCallIds.clear()
        val cfg = runtimeConfigProvider()
        val effectiveMode = when (options.mode) {
            RealtimeBackendMode.OPENAI_WALK_TALK -> {
                if (cfg.openai?.apiKey?.isNotBlank() == true) RealtimeBackendMode.OPENAI_WALK_TALK
                else if (cfg.gemini?.apiKey?.isNotBlank() == true) RealtimeBackendMode.GEMINI_GLASS_LIVE
                else {
                    listener.onError("尚未配置 OpenAI 或 Gemini API Key")
                    return
                }
            }
            RealtimeBackendMode.GEMINI_GLASS_LIVE -> {
                if (cfg.gemini?.apiKey?.isNotBlank() == true) RealtimeBackendMode.GEMINI_GLASS_LIVE
                else {
                    listener.onError("尚未同步 Gemini API Key，请连接 Mac 同步密钥后再使用实时镜头")
                    return
                }
            }
        }
        activeMode = effectiveMode
        val instructions = buildLiveInstructions(effectiveMode, options)
        connectAttempt(
            effectiveMode = effectiveMode,
            cfg = cfg,
            options = options,
            instructions = instructions,
            listener = listener,
            attempt = 1,
            maxAttempts = 2
        )
    }

    private fun resolveRealtimeModel(mode: RealtimeBackendMode, cfg: EdgeAiRuntimeConfig): String {
        return if (mode == RealtimeBackendMode.OPENAI_WALK_TALK) {
            val sel = roleSelectionProvider(ModelRoleCategory.LIVE_WALK_TALK)
            val selModel = if (sel.startsWith("openai:")) sel.removePrefix("openai:").trim() else ""
            if (selModel.isNotEmpty() && (selModel.contains("realtime") || selModel.contains("live"))) {
                selModel
            } else {
                cfg.openai?.liveModel?.ifBlank { AiModelCatalog.OPENAI_REALTIME } ?: AiModelCatalog.OPENAI_REALTIME
            }
        } else {
            val sel = roleSelectionProvider(ModelRoleCategory.LIVE_GLASS_CAMERA)
            val selModel = if (sel.startsWith("gemini:")) sel.removePrefix("gemini:").removePrefix("models/").trim() else ""
            if (selModel.isNotEmpty() && selModel.contains("live") && !selModel.contains("transcribe")) {
                selModel
            } else {
                cfg.gemini?.liveModel?.removePrefix("models/")?.ifBlank { AiModelCatalog.GEMINI_LIVE }
                    ?: AiModelCatalog.GEMINI_LIVE
            }
        }
    }

    private fun connectAttempt(
        effectiveMode: RealtimeBackendMode,
        cfg: EdgeAiRuntimeConfig,
        options: RealtimeSessionOptions,
        instructions: String,
        listener: RealtimeSessionListener,
        attempt: Int,
        maxAttempts: Int
    ) {
        val myGen = sessionGen.incrementAndGet()
        isConnected.set(false)
        setupWatchdogJob?.cancel()

        val model = resolveRealtimeModel(effectiveMode, cfg)
        val targetHost: String
        val req: Request

        if (effectiveMode == RealtimeBackendMode.OPENAI_WALK_TALK) {
            val oai = cfg.openai!!
            val base = oai.baseUrl.trimEnd('/').ifBlank { "https://api.openai.com/v1" }
            val wsBase = base
                .replaceFirst(Regex("^https://", RegexOption.IGNORE_CASE), "wss://")
                .replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "ws://")
            val wsUrl = "$wsBase/realtime?model=$model"
            req = Request.Builder()
                .url(wsUrl)
                .header("Authorization", "Bearer ${oai.apiKey}")
                .build()
            targetHost = req.url.host
            activeWebSocket = httpClientProvider().newWebSocket(
                req,
                createOpenAiListener(
                    myGen = myGen,
                    model = model,
                    instructions = instructions,
                    voice = options.voiceName.ifBlank { "marin" },
                    listener = listener,
                    onRetryBeforeReady = if (attempt < maxAttempts) { _, isGeo ->
                        onNodeHandshakeFailed?.invoke(targetHost, isGeo)
                        listener.onStatusHint("正在自动切换优质语音线路重连…")
                        connectAttempt(effectiveMode, cfg, options, instructions, listener, attempt + 1, maxAttempts)
                    } else null
                )
            )
        } else {
            val gem = cfg.gemini!!
            val base = gem.baseUrl.trimEnd('/').ifBlank { "https://generativelanguage.googleapis.com/v1beta" }
            val hostBase = base
                .replace(Regex("/v1beta$|/v1$"), "")
                .replaceFirst(Regex("^https://", RegexOption.IGNORE_CASE), "wss://")
                .replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "ws://")
            val wsUrl = "$hostBase/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=${gem.apiKey}"
            req = Request.Builder().url(wsUrl).build()
            targetHost = req.url.host
            activeWebSocket = httpClientProvider().newWebSocket(
                req,
                createGeminiLiveListener(
                    myGen = myGen,
                    model = model,
                    instructions = instructions,
                    voice = options.voiceName.ifBlank { "Puck" },
                    listener = listener,
                    onRetryBeforeReady = if (attempt < maxAttempts) { _, isGeo ->
                        onNodeHandshakeFailed?.invoke(targetHost, isGeo)
                        listener.onStatusHint("正在自动切换低延迟视觉线路重连…")
                        connectAttempt(effectiveMode, cfg, options, instructions, listener, attempt + 1, maxAttempts)
                    } else null
                )
            )
        }

        // Watchdog: if WebSocket / setupComplete doesn't finish within `setupTimeoutMs`, fail over or surface error cleanly!
        setupWatchdogJob = engineScope.launch {
            delay(setupTimeoutMs)
            if (sessionGen.get() == myGen && !isConnected.get()) {
                val staleWs = activeWebSocket
                activeWebSocket = null
                runCatching { staleWs?.cancel() }
                if (attempt < maxAttempts) {
                    onNodeHandshakeFailed?.invoke(targetHost, false)
                    listener.onStatusHint("当前节点握手超时，正在自动切换备用线路…")
                    connectAttempt(effectiveMode, cfg, options, instructions, listener, attempt + 1, maxAttempts)
                } else {
                    sessionGen.incrementAndGet()
                    listener.onError("实时通道建立超时，请检查代理节点或网络状态")
                    listener.onClosed()
                }
            }
        }
    }

    /**
     * Feeds microphone PCM16 mono little-endian audio at `sampleRateHz` (e.g. 16000 or 24000).
     * Automatically resamples to 24kHz for OpenAI Realtime and 16kHz for Gemini 3.8 Live.
     */
    fun sendMicPcm16(pcmMonoLe: ByteArray, sampleRateHz: Int) {
        val ws = activeWebSocket ?: return
        if (!isConnected.get() || pcmMonoLe.isEmpty()) return
        if (activeMode == RealtimeBackendMode.OPENAI_WALK_TALK) {
            val pcm24k = if (sampleRateHz == 24000) pcmMonoLe else PcmAudioCodec.resamplePcm16Mono(pcmMonoLe, sampleRateHz, 24000)
            val b64 = Base64.getEncoder().encodeToString(pcm24k)
            ws.send(MiniJson.stringify(linkedMapOf("type" to "input_audio_buffer.append", "audio" to b64)))
        } else {
            val pcm16k = if (sampleRateHz == 16000) pcmMonoLe else PcmAudioCodec.resamplePcm16Mono(pcmMonoLe, sampleRateHz, 16000)
            val b64 = Base64.getEncoder().encodeToString(pcm16k)
            ws.send(
                MiniJson.stringify(
                    linkedMapOf(
                        "realtimeInput" to linkedMapOf(
                            "audio" to linkedMapOf(
                                "data" to b64,
                                "mimeType" to "audio/pcm;rate=16000"
                            )
                        )
                    )
                )
            )
        }
    }

    /**
     * Feeds a 1fps CameraX JPEG frame during `GEMINI_GLASS_LIVE` Google Glass companion sessions.
     * Uses Gemini 3.8 Live's modern `realtimeInput.video` key (not legacy `media`).
     */
    fun sendCameraJpegFrame(jpegBytes: ByteArray) {
        val ws = activeWebSocket ?: return
        if (!isConnected.get() || jpegBytes.isEmpty()) return
        if (activeMode == RealtimeBackendMode.GEMINI_GLASS_LIVE) {
            val b64 = Base64.getEncoder().encodeToString(jpegBytes)
            ws.send(
                MiniJson.stringify(
                    linkedMapOf(
                        "realtimeInput" to linkedMapOf(
                            "video" to linkedMapOf(
                                "data" to b64,
                                "mimeType" to "image/jpeg"
                            )
                        )
                    )
                )
            )
        }
    }

    fun notifyMicStreamEnd() {
        val ws = activeWebSocket ?: return
        if (!isConnected.get()) return
        if (activeMode == RealtimeBackendMode.GEMINI_GLASS_LIVE) {
            ws.send(MiniJson.stringify(linkedMapOf("realtimeInput" to linkedMapOf("audioStreamEnd" to true))))
        } else {
            ws.send(MiniJson.stringify(linkedMapOf("type" to "input_audio_buffer.commit")))
        }
    }

    fun stopSession() {
        sessionGen.incrementAndGet()
        setupWatchdogJob?.cancel()
        setupWatchdogJob = null
        isConnected.set(false)
        val ws = activeWebSocket
        activeWebSocket = null
        runCatching { ws?.close(1000, "Client closed") }
    }

    private fun isGeoBlockedErrorText(raw: String): Boolean {
        val low = raw.lowercase()
        return low.contains("location is not supported") ||
            low.contains("unsupported_country") ||
            low.contains("region is not supported") ||
            low.contains("territory not supported")
    }

    private fun createOpenAiListener(
        myGen: Int,
        model: String,
        instructions: String,
        voice: String,
        listener: RealtimeSessionListener,
        onRetryBeforeReady: ((reason: String, isGeo: Boolean) -> Unit)?
    ): WebSocketListener = object : WebSocketListener() {
        private val readyAcked = AtomicBoolean(false)

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (sessionGen.get() != myGen) return
            setupWatchdogJob?.cancel()
            readyAcked.set(true)
            isConnected.set(true)
            listener.onStatusHint("")
            webSocket.send(
                MiniJson.stringify(
                    buildOpenAiSessionUpdatePayload(
                        instructions = instructions,
                        voice = voice,
                        enableWebSearchTool = webSearchExecutor != null
                    )
                )
            )
            listener.onSessionReady(RealtimeBackendMode.OPENAI_WALK_TALK, model)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (sessionGen.get() != myGen) return
            handleOpenAiServerMessage(myGen, webSocket, text, listener)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            runCatching { webSocket.close(code, null) }
            if (sessionGen.get() != myGen) return
            isConnected.set(false)
            if (!readyAcked.get() && onRetryBeforeReady != null) {
                onRetryBeforeReady("$code $reason", isGeoBlockedErrorText(reason))
                return
            }
            if (code != 1000 && reason.isNotBlank()) {
                listener.onError("OpenAI Realtime 关闭 ($code): $reason")
            }
            listener.onClosed()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (sessionGen.get() != myGen) return
            isConnected.set(false)
            val respBody = runCatching { response?.peekBody(2048)?.string().orEmpty() }.getOrDefault("")
            val detail = listOfNotNull(t.message, response?.code?.let { "HTTP $it" }, respBody.take(140).ifBlank { null })
                .joinToString(" · ")
            if (!readyAcked.get() && onRetryBeforeReady != null) {
                onRetryBeforeReady(detail, isGeoBlockedErrorText(detail))
                return
            }
            listener.onError("OpenAI Realtime 连接断开: ${detail.ifBlank { "unknown" }}")
            listener.onClosed()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (sessionGen.get() != myGen) return
            isConnected.set(false)
            listener.onClosed()
        }
    }

    private fun createGeminiLiveListener(
        myGen: Int,
        model: String,
        instructions: String,
        voice: String,
        listener: RealtimeSessionListener,
        onRetryBeforeReady: ((reason: String, isGeo: Boolean) -> Unit)?
    ): WebSocketListener = object : WebSocketListener() {
        private val readyAcked = AtomicBoolean(false)

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (sessionGen.get() != myGen) return
            webSocket.send(MiniJson.stringify(buildGeminiLiveSetupPayload(model, instructions, voice)))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (sessionGen.get() != myGen) return
            handleGeminiLiveServerMessage(text, model, readyAcked, listener)
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            if (sessionGen.get() != myGen) return
            handleGeminiLiveServerMessage(bytes.utf8(), model, readyAcked, listener)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            runCatching { webSocket.close(code, null) }
            if (sessionGen.get() != myGen) return
            isConnected.set(false)
            val isGeo = isGeoBlockedErrorText(reason)
            if (!readyAcked.get() && onRetryBeforeReady != null) {
                onRetryBeforeReady("$code $reason", isGeo)
                return
            }
            if (code != 1000) {
                val friendly = if (isGeo) {
                    "当前代理节点地区暂不支持 Gemini Live，请切换美国/新加坡/日本节点"
                } else {
                    "Gemini Live 会话关闭 ($code): ${reason.ifBlank { "握手未通过" }}"
                }
                listener.onError(friendly)
            }
            listener.onClosed()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (sessionGen.get() != myGen) return
            isConnected.set(false)
            val respBody = runCatching { response?.peekBody(2048)?.string().orEmpty() }.getOrDefault("")
            val detail = listOfNotNull(t.message, response?.code?.let { "HTTP $it" }, respBody.take(140).ifBlank { null })
                .joinToString(" · ")
            val isGeo = isGeoBlockedErrorText(detail)
            if (!readyAcked.get() && onRetryBeforeReady != null) {
                onRetryBeforeReady(detail, isGeo)
                return
            }
            listener.onError(
                if (isGeo) "当前代理节点地区暂不支持 Gemini Live，请切换美国/新加坡/日本节点"
                else "Gemini Live 连接断开: ${detail.ifBlank { "unknown" }}"
            )
            listener.onClosed()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (sessionGen.get() != myGen) return
            isConnected.set(false)
            listener.onClosed()
        }
    }

    companion object {
        fun buildLiveInstructions(mode: RealtimeBackendMode, options: RealtimeSessionOptions): String {
            return buildString {
                if (mode == RealtimeBackendMode.GEMINI_GLASS_LIVE) {
                    if (options.englishOnlyFluencyMode) {
                        appendLine("You are the user's real-time multimodal Google Glass vision & voice companion.")
                        appendLine("You continuously receive live JPEG frames from the user's phone camera (`realtimeInput.video`) along with live audio, and you have live Google Search grounding enabled.")
                        appendLine("Speak ONLY natural, concise English (2 to 4 sentences). Directly describe what you see in the live camera frame whenever asked, and use Google Search immediately for live weather, news, prices, or factual lookups.")
                    } else {
                        appendLine("你是用户的随身实时视觉与语音伴游助手（青简·咫尺 Live 镜头模式）。")
                        appendLine("你正在持续接收用户手机摄像头传来的实时画面帧（realtimeInput.video）与双向语音，并已挂载实时 Google 搜索工具。")
                        appendLine("当用户询问「能看到吗 / 这是什么 / 帮我看看」时，请直接根据最新收到的摄像头画面具体描述你看到的物体、文字、颜色或植物特征；当问及天气、今日新闻、价格或实时事实，请直接调用联网检索核实后自然口语作答（每次 2~4 句，方便随时打断）。")
                    }
                } else if (options.englishOnlyFluencyMode) {
                    appendLine("You are the user's crisp, thoughtful English walking partner on AirPods.")
                    appendLine("Speak ONLY natural, idiomatic English at a comfortable conversational pace.")
                    appendLine("Keep each spoken turn concise (2 to 4 sentences) so dialogue flows naturally back and forth.")
                    appendLine("You have a live `web_search` tool: whenever the user asks about today's weather, breaking news, prices, schedules, or current facts, call `web_search` immediately and answer with accurate up-to-date facts.")
                    appendLine("Do NOT interrupt the conversation flow with pedantic grammar drills unless the user explicitly asks how to phrase something better.")
                } else {
                    appendLine("你是用户的随身散步深度对谈伙伴（青简·咫尺 Live）。")
                    appendLine("请用自然、敏锐、不啰嗦的口语与用户对谈或头脑风暴，每次回应控制在 2~5 句话，像老朋友并肩散步交谈一样留白。")
                    appendLine("你已具备实时联网搜索工具 `web_search`：只要用户问到天气、今日新闻、近期动态、股价汇率、赛事比分或任何需要核实的实时信息，请立即调用 `web_search` 查询最新结果后再回答，切勿说自己无法联网。")
                }
                if (options.extraContextSummary.isNotBlank()) {
                    appendLine("\n【用户近期背景摘要（仅在话题相关时自然呼应）】")
                    appendLine(options.extraContextSummary.take(1500))
                }
            }
        }

        fun buildOpenAiSessionUpdatePayload(
            instructions: String,
            voice: String,
            enableWebSearchTool: Boolean = true
        ): Map<String, Any?> {
            val sessionMap = linkedMapOf<String, Any?>(
                "type" to "realtime",
                "instructions" to instructions,
                "audio" to linkedMapOf(
                    "input" to linkedMapOf(
                        "format" to linkedMapOf("type" to "audio/pcm", "rate" to 24000),
                        "transcription" to linkedMapOf("model" to AiModelCatalog.OPENAI_LIVE_TRANSCRIBE),
                        "turn_detection" to linkedMapOf(
                            "type" to "server_vad",
                            "interrupt_response" to true,
                            "silence_duration_ms" to 550
                        )
                    ),
                    "output" to linkedMapOf(
                        "format" to linkedMapOf("type" to "audio/pcm", "rate" to 24000),
                        "voice" to voice
                    )
                )
            )
            if (enableWebSearchTool) {
                sessionMap["tool_choice"] = "auto"
                sessionMap["tools"] = listOf(
                    linkedMapOf(
                        "type" to "function",
                        "name" to "web_search",
                        "description" to "Search the live web for real-time weather, news, events, prices, schedules, or factual verification.",
                        "parameters" to linkedMapOf(
                            "type" to "object",
                            "properties" to linkedMapOf(
                                "query" to linkedMapOf(
                                    "type" to "string",
                                    "description" to "Concise search query including location or date if relevant"
                                )
                            ),
                            "required" to listOf("query")
                        )
                    )
                )
            }
            return linkedMapOf(
                "type" to "session.update",
                "session" to sessionMap
            )
        }

        fun buildGeminiLiveSetupPayload(model: String, instructions: String, voice: String): Map<String, Any?> {
            val normalizedModel = if (model.startsWith("models/")) model else "models/$model"
            return linkedMapOf(
                "setup" to linkedMapOf(
                    "model" to normalizedModel,
                    "generationConfig" to linkedMapOf(
                        "responseModalities" to listOf("AUDIO"),
                        "speechConfig" to linkedMapOf(
                            "voiceConfig" to linkedMapOf(
                                "prebuiltVoiceConfig" to linkedMapOf("voiceName" to voice)
                            )
                        )
                    ),
                    "inputAudioTranscription" to linkedMapOf<String, Any?>(),
                    "outputAudioTranscription" to linkedMapOf<String, Any?>(),
                    "contextWindowCompression" to linkedMapOf(
                        "slidingWindow" to linkedMapOf<String, Any?>()
                    ),
                    "tools" to listOf(linkedMapOf("googleSearch" to linkedMapOf<String, Any?>())),
                    "systemInstruction" to linkedMapOf(
                        "parts" to listOf(linkedMapOf("text" to instructions))
                    )
                )
            )
        }

        data class OpenAiFunctionCallRequest(
            val callId: String,
            val name: String,
            val argumentsJson: String
        )

        @Suppress("UNCHECKED_CAST")
        fun parseOpenAiServerEvent(
            rawJson: String,
            listener: RealtimeSessionListener,
            onFunctionCall: ((OpenAiFunctionCallRequest) -> Unit)? = null
        ) {
            val obj = runCatching { MiniJson.parseObject(rawJson) }.getOrNull() ?: return
            when (obj["type"]?.toString()) {
                "input_audio_buffer.speech_started" -> listener.onInterruptedBargeIn()
                "response.output_audio.delta", "response.audio.delta" -> {
                    val b64 = obj["delta"]?.toString().orEmpty()
                    if (b64.isNotEmpty()) {
                        runCatching { Base64.getDecoder().decode(b64) }.getOrNull()?.let { pcm ->
                            if (pcm.isNotEmpty()) listener.onPcm24kAudioOut(pcm)
                        }
                    }
                }
                "response.output_audio_transcript.delta", "response.audio_transcript.delta" -> {
                    val d = obj["delta"]?.toString().orEmpty()
                    if (d.isNotEmpty()) listener.onAssistantTranscriptDelta(d)
                }
                "conversation.item.input_audio_transcription.completed" -> {
                    val t = obj["transcript"]?.toString()?.trim().orEmpty()
                    if (t.isNotEmpty()) listener.onUserTranscript(t, true)
                }
                "response.function_call_arguments.done" -> {
                    val callId = obj["call_id"]?.toString().orEmpty()
                    val name = obj["name"]?.toString().orEmpty()
                    val args = obj["arguments"]?.toString().orEmpty()
                    if (callId.isNotBlank() && name.isNotBlank()) {
                        onFunctionCall?.invoke(OpenAiFunctionCallRequest(callId, name, args))
                    }
                }
                "response.output_item.done" -> {
                    val item = obj["item"] as? Map<String, Any?> ?: return
                    if (item["type"]?.toString() == "function_call") {
                        val callId = item["call_id"]?.toString().orEmpty()
                        val name = item["name"]?.toString().orEmpty()
                        val args = item["arguments"]?.toString().orEmpty()
                        if (callId.isNotBlank() && name.isNotBlank()) {
                            onFunctionCall?.invoke(OpenAiFunctionCallRequest(callId, name, args))
                        }
                    }
                }
                "response.done" -> listener.onTurnComplete()
                "error" -> {
                    val errObj = obj["error"] as? Map<String, Any?>
                    val msg = errObj?.get("message")?.toString() ?: "Realtime error"
                    listener.onError(msg)
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        fun parseGeminiLiveServerEvent(
            rawJson: String,
            model: String,
            onSetupAck: () -> Unit,
            listener: RealtimeSessionListener
        ) {
            val obj = runCatching { MiniJson.parseObject(rawJson) }.getOrNull() ?: return
            if (obj.containsKey("setupComplete")) {
                onSetupAck()
                listener.onStatusHint("")
                listener.onSessionReady(RealtimeBackendMode.GEMINI_GLASS_LIVE, model)
                return
            }
            val errObj = obj["error"] as? Map<String, Any?>
            if (errObj != null) {
                listener.onError(errObj["message"]?.toString() ?: "Gemini Live 错误")
                return
            }
            val content = obj["serverContent"] as? Map<String, Any?> ?: return
            if (content["interrupted"] == true) {
                listener.onInterruptedBargeIn()
            }
            val modelTurn = content["modelTurn"] as? Map<String, Any?>
            val parts = modelTurn?.get("parts") as? List<*> ?: emptyList<Any?>()
            for (p in parts) {
                val pm = p as? Map<String, Any?> ?: continue
                val inlineData = pm["inlineData"] as? Map<String, Any?>
                if (inlineData != null) {
                    val b64 = inlineData["data"]?.toString().orEmpty()
                    if (b64.isNotEmpty()) {
                        runCatching { Base64.getDecoder().decode(b64) }.getOrNull()?.let { pcm24k ->
                            if (pcm24k.isNotEmpty()) listener.onPcm24kAudioOut(pcm24k)
                        }
                    }
                }
            }
            val inTr = (content["inputTranscription"] as? Map<String, Any?>)?.get("text")?.toString().orEmpty()
            if (inTr.isNotEmpty()) listener.onUserTranscript(inTr, true)
            val outTr = (content["outputTranscription"] as? Map<String, Any?>)?.get("text")?.toString().orEmpty()
            if (outTr.isNotEmpty()) listener.onAssistantTranscriptDelta(outTr)
            if (content["turnComplete"] == true) {
                listener.onTurnComplete()
            }
        }
    }

    private fun handleOpenAiServerMessage(
        myGen: Int,
        webSocket: WebSocket,
        text: String,
        listener: RealtimeSessionListener
    ) {
        parseOpenAiServerEvent(text, listener) { fnReq ->
            if (fnReq.name == "web_search" && handledToolCallIds.add(fnReq.callId)) {
                val query = runCatching {
                    MiniJson.parseObject(fnReq.argumentsJson)["query"]?.toString()?.trim().orEmpty()
                }.getOrDefault("").ifBlank { fnReq.argumentsJson.take(120) }
                val executor = webSearchExecutor ?: return@parseOpenAiServerEvent
                listener.onStatusHint("正在实时联网检索：$query")
                engineScope.launch {
                    val output = try {
                        executor(query)
                    } catch (e: Throwable) {
                        "联网检索暂未返回结果：${e.message ?: "network error"}"
                    }
                    if (sessionGen.get() != myGen || !isConnected.get()) return@launch
                    listener.onStatusHint("")
                    val itemPayload = linkedMapOf(
                        "type" to "conversation.item.create",
                        "item" to linkedMapOf(
                            "type" to "function_call_output",
                            "call_id" to fnReq.callId,
                            "output" to output.take(4000)
                        )
                    )
                    val triggerResponse = linkedMapOf("type" to "response.create")
                    runCatching {
                        webSocket.send(MiniJson.stringify(itemPayload))
                        webSocket.send(MiniJson.stringify(triggerResponse))
                    }
                }
            }
        }
    }

    private fun handleGeminiLiveServerMessage(
        text: String,
        model: String,
        readyAcked: AtomicBoolean,
        listener: RealtimeSessionListener
    ) {
        parseGeminiLiveServerEvent(
            rawJson = text,
            model = model,
            onSetupAck = {
                setupWatchdogJob?.cancel()
                readyAcked.set(true)
                isConnected.set(true)
            },
            listener = listener
        )
    }
}

object PcmAudioCodec {
    fun resamplePcm16Mono(inputBytes: ByteArray, fromRate: Int, toRate: Int): ByteArray {
        if (fromRate == toRate || inputBytes.size < 4) return inputBytes
        val inSamples = inputBytes.size / 2
        val outSamples = ((inSamples.toLong() * toRate) / fromRate).toInt().coerceAtLeast(1)
        val inBuf = ByteBuffer.wrap(inputBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val outBytes = ByteArray(outSamples * 2)
        val outBuf = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val ratio = fromRate.toDouble() / toRate.toDouble()
        for (i in 0 until outSamples) {
            val srcPos = i * ratio
            val idx0 = srcPos.toInt().coerceIn(0, inSamples - 1)
            val idx1 = (idx0 + 1).coerceIn(0, inSamples - 1)
            val frac = srcPos - idx0
            val s0 = inBuf.get(idx0).toDouble()
            val s1 = inBuf.get(idx1).toDouble()
            val sample = (s0 + (s1 - s0) * frac).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            outBuf.put(i, sample.toShort())
        }
        return outBytes
    }
}
