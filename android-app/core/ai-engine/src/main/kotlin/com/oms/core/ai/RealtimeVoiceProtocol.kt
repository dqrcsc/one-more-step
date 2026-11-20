package com.oms.core.ai

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

enum class RealtimeBackendMode {
    OPENAI_WALK_TALK,   // OpenAI `gpt-realtime-2.1` full-duplex speech-to-speech
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
    fun onError(message: String)
    fun onClosed()
}

/**
 * Pure-Kotlin WebSocket full-duplex engine for:
 *  1) OpenAI `gpt-realtime-2.1` Walk & Talk (including `[🇬🇧 English Only]` natural fluency mode)
 *  2) Google `gemini-3.8-live` Google Glass real-time 1fps camera + bidirectional voice + Search Grounding
 */
class RealtimeVoiceSessionEngine(
    private val httpClientProvider: () -> OkHttpClient,
    private val runtimeConfigProvider: () -> EdgeAiRuntimeConfig
) {
    @Volatile
    private var activeWebSocket: WebSocket? = null
    @Volatile
    private var activeMode: RealtimeBackendMode = RealtimeBackendMode.OPENAI_WALK_TALK
    private val isConnected = AtomicBoolean(false)

    fun isActive(): Boolean = isConnected.get() && activeWebSocket != null

    fun startSession(options: RealtimeSessionOptions, listener: RealtimeSessionListener) {
        stopSession()
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
                else if (cfg.openai?.apiKey?.isNotBlank() == true) RealtimeBackendMode.OPENAI_WALK_TALK
                else {
                    listener.onError("尚未配置 Gemini API Key")
                    return
                }
            }
        }
        activeMode = effectiveMode
        val instructions = buildLiveInstructions(effectiveMode, options)

        if (effectiveMode == RealtimeBackendMode.OPENAI_WALK_TALK) {
            val oai = cfg.openai!!
            val model = oai.liveModel.ifBlank { AiModelCatalog.OPENAI_REALTIME }
            val wsBase = oai.baseUrl.trimEnd('/')
                .replaceFirst(Regex("^https://", RegexOption.IGNORE_CASE), "wss://")
                .replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "ws://")
            val wsUrl = "$wsBase/realtime?model=$model"
            val req = Request.Builder()
                .url(wsUrl)
                .header("Authorization", "Bearer ${oai.apiKey}")
                .build()
            activeWebSocket = httpClientProvider().newWebSocket(
                req,
                createOpenAiListener(model, instructions, options.voiceName.ifBlank { "marin" }, listener)
            )
        } else {
            val gem = cfg.gemini!!
            val model = gem.liveModel.ifBlank { AiModelCatalog.GEMINI_LIVE }
            val hostBase = gem.baseUrl.trimEnd('/')
                .replace(Regex("/v1beta$|/v1$"), "")
                .replaceFirst(Regex("^https://", RegexOption.IGNORE_CASE), "wss://")
                .replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "ws://")
            val wsUrl = "$hostBase/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=${gem.apiKey}"
            val req = Request.Builder().url(wsUrl).build()
            activeWebSocket = httpClientProvider().newWebSocket(
                req,
                createGeminiLiveListener(model, instructions, options.voiceName.ifBlank { "Puck" }, listener)
            )
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
        isConnected.set(false)
        val ws = activeWebSocket
        activeWebSocket = null
        runCatching { ws?.close(1000, "Client closed") }
    }

    private fun createOpenAiListener(
        model: String,
        instructions: String,
        voice: String,
        listener: RealtimeSessionListener
    ): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            isConnected.set(true)
            webSocket.send(MiniJson.stringify(buildOpenAiSessionUpdatePayload(instructions, voice)))
            listener.onSessionReady(RealtimeBackendMode.OPENAI_WALK_TALK, model)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleOpenAiServerMessage(text, listener)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            isConnected.set(false)
            listener.onError("OpenAI Realtime 连接断开: ${t.message ?: "unknown"}")
            listener.onClosed()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            isConnected.set(false)
            listener.onClosed()
        }
    }

    private fun createGeminiLiveListener(
        model: String,
        instructions: String,
        voice: String,
        listener: RealtimeSessionListener
    ): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(MiniJson.stringify(buildGeminiLiveSetupPayload(model, instructions, voice)))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleGeminiLiveServerMessage(text, model, listener)
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            handleGeminiLiveServerMessage(bytes.utf8(), model, listener)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            isConnected.set(false)
            listener.onError("Gemini Live 连接断开: ${t.message ?: "unknown"}")
            listener.onClosed()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            isConnected.set(false)
            listener.onClosed()
        }
    }

    companion object {
        fun buildLiveInstructions(mode: RealtimeBackendMode, options: RealtimeSessionOptions): String {
            return buildString {
                if (options.englishOnlyFluencyMode) {
                    appendLine("You are the user's crisp, thoughtful English walking partner on AirPods.")
                    appendLine("Speak ONLY natural, idiomatic English at a comfortable conversational pace.")
                    appendLine("Keep each spoken turn concise (2 to 4 sentences) so dialogue flows naturally back and forth.")
                    appendLine("Do NOT interrupt the conversation flow with pedantic grammar drills unless the user explicitly asks how to phrase something better.")
                } else if (mode == RealtimeBackendMode.GEMINI_GLASS_LIVE) {
                    appendLine("你是用户的随身 Google Glass 实时视觉与博物伴游助手（青简·咫尺 Live）。")
                    appendLine("你会持续收到用户手机摄像头每秒 1 帧的实时画面与双向语音。")
                    appendLine("看到植物、花草、建筑或器物时，请用自然简洁的口语直接告诉用户这是什么、画面里哪个特征最关键、有什么有趣背景；每次回答控制在 2~4 句口语长度，方便随时打断追问。")
                } else {
                    appendLine("你是用户的随身散步深度对谈伙伴（青简·咫尺 Live）。")
                    appendLine("请用自然、敏锐、不啰嗦的口语与用户头脑风暴，每次回应控制在 2~5 句话，像老朋友并肩散步交谈一样留白。")
                }
                if (options.extraContextSummary.isNotBlank()) {
                    appendLine("\n【用户近期背景摘要（仅在话题相关时自然呼应）】")
                    appendLine(options.extraContextSummary.take(1500))
                }
            }
        }

        fun buildOpenAiSessionUpdatePayload(instructions: String, voice: String): Map<String, Any?> = linkedMapOf(
            "type" to "session.update",
            "session" to linkedMapOf(
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
        )

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
                    "tools" to listOf(linkedMapOf("googleSearch" to linkedMapOf<String, Any?>())),
                    "systemInstruction" to linkedMapOf(
                        "parts" to listOf(linkedMapOf("text" to instructions))
                    )
                )
            )
        }

        @Suppress("UNCHECKED_CAST")
        fun parseOpenAiServerEvent(rawJson: String, listener: RealtimeSessionListener) {
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
                listener.onSessionReady(RealtimeBackendMode.GEMINI_GLASS_LIVE, model)
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

    private fun handleOpenAiServerMessage(text: String, listener: RealtimeSessionListener) {
        parseOpenAiServerEvent(text, listener)
    }

    private fun handleGeminiLiveServerMessage(text: String, model: String, listener: RealtimeSessionListener) {
        parseGeminiLiveServerEvent(text, model, onSetupAck = { isConnected.set(true) }, listener = listener)
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
