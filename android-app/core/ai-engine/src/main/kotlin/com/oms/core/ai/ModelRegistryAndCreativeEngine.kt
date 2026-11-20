package com.oms.core.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64

enum class ModelRoleCategory(
    val title: String,
    val subtitle: String,
    val defaultSelection: String
) {
    CHAT_PRIMARY(
        title = "💬 日常对话主模型",
        subtitle = "默认会话极速响应主力（兼顾推理深度与首字速度）",
        defaultSelection = "openai:gpt-6.2-sol"
    ),
    DEEP_REASONING(
        title = "💡 深度思考与架构推演",
        subtitle = "点亮输入框「💡深度思考」时调用的顶级旗舰模型",
        defaultSelection = "openai:gpt-6.2-astra"
    ),
    VISION_UNDERSTANDING(
        title = "🌿 视觉理解与博物识物",
        subtitle = "相机拍植物图鉴、相册识图与多模态细节核查",
        defaultSelection = "gemini:gemini-4-argon"
    ),
    WECHAT_TRIAGE(
        title = "📰 早晚尺素文章初筛",
        subtitle = "Mac 休眠时手机自动抓取公众号并生成脱水干货",
        defaultSelection = "openai:gpt-6.2-sol"
    ),
    LIVE_WALK_TALK(
        title = "🎧 散步实时语音对谈",
        subtitle = "AirPods 全双工自然打断语音会话与英语口语陪练",
        defaultSelection = "openai:gpt-realtime-2.1"
    ),
    LIVE_GLASS_CAMERA(
        title = "👓 Google Glass 实时镜头",
        subtitle = "1fps 摄像头连续画面 + 双向语音 + 联网核查",
        defaultSelection = "gemini:gemini-3.8-live"
    ),
    SPEECH_TRANSCRIBE(
        title = "🎙️ 语音极速听写",
        subtitle = "输入框中英混排语音毫秒级转文字",
        defaultSelection = "openai:gpt-live-transcribe"
    ),
    IMAGE_GENERATION(
        title = "🎨 图片创作与修图",
        subtitle = "文生图、海报插画与多轮对话改图",
        defaultSelection = "openai:gpt-image-2.6-sunburst"
    ),
    VIDEO_GENERATION(
        title = "🎬 视频创作与运镜",
        subtitle = "原生有声短视频生成（Sora API 已下线，默认 Gemini Omni 1.2）",
        defaultSelection = "gemini:gemini-omni-1.2-flash"
    ),
    MUSIC_GENERATION(
        title = "🎵 歌曲与配乐创作",
        subtitle = "根据歌词或风格描述生成高品质音乐曲目",
        defaultSelection = "gemini:lyria-3.5"
    );
}

data class ModelCatalogItem(
    val providerId: String, // "openai" | "gemini"
    val modelId: String,
    val label: String,
    val description: String,
    val categories: Set<ModelRoleCategory>,
    val isLiveDiscovered: Boolean = false
) {
    val selectionKey: String get() = "$providerId:$modelId"
}

data class ModelCatalogRefreshResult(
    val items: List<ModelCatalogItem>,
    val openAiDiscoveredCount: Int,
    val geminiDiscoveredCount: Int,
    val refreshedAtMs: Long,
    val warnings: List<String>
)

data class GeneratedMediaResult(
    val mediaKind: String, // "image" | "video" | "music"
    val mimeType: String,
    val bytes: ByteArray,
    val modelUsed: String,
    val caption: String = ""
)

/**
 * Curated 2026-11 OpenAI + Google Gemini Model Matrix + Live `/models` Auto-Discovery Classifier
 * + Native Image / Video / Music Creative Execution Engine.
 */
object ModelCatalogRegistry {

    val BUILTIN_PRESETS: List<ModelCatalogItem> = listOf(
        // ==================== OpenAI 2026-11 ====================
        ModelCatalogItem(
            "openai", "gpt-6.2-sol", "GPT-6.2 Sol", "OpenAI 均衡主力旗舰（日常秒回 & 尺素初筛）",
            setOf(ModelRoleCategory.CHAT_PRIMARY, ModelRoleCategory.VISION_UNDERSTANDING, ModelRoleCategory.WECHAT_TRIAGE)
        ),
        ModelCatalogItem(
            "openai", "gpt-6.2-astra", "GPT-6.2 Astra", "OpenAI 顶级深度推理与科研编码旗舰 (~1.05M 上下文)",
            setOf(ModelRoleCategory.DEEP_REASONING, ModelRoleCategory.CHAT_PRIMARY, ModelRoleCategory.WECHAT_TRIAGE, ModelRoleCategory.VISION_UNDERSTANDING)
        ),
        ModelCatalogItem(
            "openai", "gpt-6.1-terra", "GPT-6.1 Terra", "OpenAI 高吞吐多模态与工具调用模型",
            setOf(ModelRoleCategory.CHAT_PRIMARY, ModelRoleCategory.VISION_UNDERSTANDING)
        ),
        ModelCatalogItem(
            "openai", "gpt-6-luna", "GPT-6 Luna", "OpenAI 轻量极速与低成本记忆整理模型",
            setOf(ModelRoleCategory.CHAT_PRIMARY)
        ),
        ModelCatalogItem(
            "openai", "gpt-realtime-2.1", "GPT Realtime 2.1", "OpenAI 原生端到端全双工实时语音旗舰",
            setOf(ModelRoleCategory.LIVE_WALK_TALK)
        ),
        ModelCatalogItem(
            "openai", "gpt-live-1.1", "GPT Live 1.1", "OpenAI 低延迟实时语音对话模型",
            setOf(ModelRoleCategory.LIVE_WALK_TALK)
        ),
        ModelCatalogItem(
            "openai", "gpt-live-transcribe", "GPT Live Transcribe", "OpenAI 极速中英混排流式语音听写",
            setOf(ModelRoleCategory.SPEECH_TRANSCRIBE)
        ),
        ModelCatalogItem(
            "openai", "gpt-transcribe", "GPT Transcribe", "OpenAI 高精度长音频语音转写",
            setOf(ModelRoleCategory.SPEECH_TRANSCRIBE)
        ),
        ModelCatalogItem(
            "openai", "gpt-image-2.6-sunburst", "GPT-Image 2.6 Sunburst", "OpenAI 高精写实生图、密集文字渲染与精准局部修图",
            setOf(ModelRoleCategory.IMAGE_GENERATION)
        ),
        ModelCatalogItem(
            "openai", "gpt-image-2.6-flare", "GPT-Image 2.6 Flare", "OpenAI 6 秒极速灵感草图生成",
            setOf(ModelRoleCategory.IMAGE_GENERATION)
        ),

        // ==================== Google Gemini 2026-11 ====================
        ModelCatalogItem(
            "gemini", "gemini-4-argon", "Gemini 4 Argon", "Google 1M 上下文多模态推理主力（博物识物 + Search Grounding）",
            setOf(ModelRoleCategory.VISION_UNDERSTANDING, ModelRoleCategory.DEEP_REASONING, ModelRoleCategory.CHAT_PRIMARY, ModelRoleCategory.WECHAT_TRIAGE)
        ),
        ModelCatalogItem(
            "gemini", "gemini-4-carbon", "Gemini 4 Carbon", "Google 最强前沿推理、数学证明与持续编码旗舰",
            setOf(ModelRoleCategory.DEEP_REASONING, ModelRoleCategory.CHAT_PRIMARY, ModelRoleCategory.VISION_UNDERSTANDING)
        ),
        ModelCatalogItem(
            "gemini", "gemini-4-hydrogen", "Gemini 4 Hydrogen", "Google 高速均衡全能多模态主力模型",
            setOf(ModelRoleCategory.CHAT_PRIMARY, ModelRoleCategory.VISION_UNDERSTANDING, ModelRoleCategory.WECHAT_TRIAGE)
        ),
        ModelCatalogItem(
            "gemini", "gemini-3.8-flash", "Gemini 3.8 Flash", "Google 高速实时联网搜索与轻量多模态问答",
            setOf(ModelRoleCategory.CHAT_PRIMARY, ModelRoleCategory.VISION_UNDERSTANDING)
        ),
        ModelCatalogItem(
            "gemini", "gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", "Google 超低延迟高吞吐轻量模型",
            setOf(ModelRoleCategory.CHAT_PRIMARY)
        ),
        ModelCatalogItem(
            "gemini", "gemini-3.8-live", "Gemini 3.8 Live", "Google GA 实时双向语音 + 1fps 镜头 Google Glass 模型",
            setOf(ModelRoleCategory.LIVE_GLASS_CAMERA, ModelRoleCategory.LIVE_WALK_TALK)
        ),
        ModelCatalogItem(
            "gemini", "gemini-3.8-live-extended-thinking", "Gemini 3.8 Live (Extended Thinking)", "支持边说边深度后台推理的 Gemini Live 模型",
            setOf(ModelRoleCategory.LIVE_GLASS_CAMERA, ModelRoleCategory.LIVE_WALK_TALK)
        ),
        ModelCatalogItem(
            "gemini", "gemini-3.5-transcribe", "Gemini 3.5 Transcribe", "Google 智能断句与多说话人语音转写",
            setOf(ModelRoleCategory.SPEECH_TRANSCRIBE)
        ),
        ModelCatalogItem(
            "gemini", "gemini-nano-banana-2.1", "Nano Banana 2.1 (Gemini Image)", "Google 高效文生图与最多 14 张参考图融合修图",
            setOf(ModelRoleCategory.IMAGE_GENERATION)
        ),
        ModelCatalogItem(
            "gemini", "gemini-3-pro-image", "Nano Banana Pro (Gemini 3 Pro Image)", "Google 旗舰级高精细度图像创作",
            setOf(ModelRoleCategory.IMAGE_GENERATION)
        ),
        ModelCatalogItem(
            "gemini", "gemini-3.1-flash-lite-image", "Nano Banana 2 Lite", "Google 超高速轻量图像生成",
            setOf(ModelRoleCategory.IMAGE_GENERATION)
        ),
        ModelCatalogItem(
            "gemini", "gemini-omni-1.2-flash", "Gemini Omni 1.2 Flash", "Google 3s–15s 原生有声视频生成、首尾帧过渡与视频编辑",
            setOf(ModelRoleCategory.VIDEO_GENERATION)
        ),
        ModelCatalogItem(
            "gemini", "lyria-3.5", "Google Lyria 3.5", "Google 原生高品质音乐与歌曲配乐创作模型",
            setOf(ModelRoleCategory.MUSIC_GENERATION)
        )
    )

    fun defaultRoleSelections(): Map<ModelRoleCategory, String> =
        ModelRoleCategory.values().associateWith { it.defaultSelection }

    fun optionsForCategory(
        category: ModelRoleCategory,
        allItems: List<ModelCatalogItem>
    ): List<ModelCatalogItem> =
        allItems.filter { category in it.categories }

    /**
     * Classifies any newly released model ID returned by OpenAI `/v1/models` or Gemini `/v1beta/models`
     * into functional categories while filtering out obsolete/embedding/internal endpoints.
     */
    fun classifyDiscoveredModel(providerId: String, rawModelId: String, displayNameHint: String = ""): ModelCatalogItem? {
        val id = rawModelId.removePrefix("models/").trim()
        val low = id.lowercase()
        if (id.isEmpty()) return null

        // Filter out deprecated, embedding, moderation, or non-generative endpoints
        val blockedSubstrings = listOf(
            "embedding", "moderation", "babbage", "davinci", "curie", "ada",
            "gpt-3.5", "dall-e-2", "whisper-1", "tts-1", "gemini-1.0", "gemini-1.5", "gemini-2.0", "gemini-2.5",
            "aqa", "imagen-2", "text-bison", "chat-bison", "decision-"
        )
        if (blockedSubstrings.any { low.contains(it) }) return null

        val cats = LinkedHashSet<ModelRoleCategory>()
        when {
            low.contains("lyria") || low.contains("music") -> cats.add(ModelRoleCategory.MUSIC_GENERATION)
            low.contains("omni-") || low.contains("veo") || low.contains("video") -> cats.add(ModelRoleCategory.VIDEO_GENERATION)
            low.contains("gpt-image") || low.contains("banana") || low.endsWith("-image") || low.contains("imagen") ->
                cats.add(ModelRoleCategory.IMAGE_GENERATION)
            low.contains("realtime") || low.contains("-live") -> {
                cats.add(ModelRoleCategory.LIVE_WALK_TALK)
                if (providerId == "gemini") cats.add(ModelRoleCategory.LIVE_GLASS_CAMERA)
            }
            low.contains("transcribe") -> cats.add(ModelRoleCategory.SPEECH_TRANSCRIBE)
            low.contains("-tts") -> return null
            providerId == "openai" && (low.startsWith("gpt-6") || low.startsWith("gpt-7") || low.startsWith("gpt-5") || low.startsWith("o3") || low.startsWith("o4") || low.startsWith("o5")) -> {
                cats.add(ModelRoleCategory.CHAT_PRIMARY)
                cats.add(ModelRoleCategory.WECHAT_TRIAGE)
                cats.add(ModelRoleCategory.VISION_UNDERSTANDING)
                if (low.contains("astra") || low.contains("pro") || low.startsWith("o")) {
                    cats.add(ModelRoleCategory.DEEP_REASONING)
                }
            }
            providerId == "gemini" && (low.startsWith("gemini-3") || low.startsWith("gemini-4") || low.startsWith("gemini-5")) -> {
                cats.add(ModelRoleCategory.CHAT_PRIMARY)
                cats.add(ModelRoleCategory.VISION_UNDERSTANDING)
                cats.add(ModelRoleCategory.WECHAT_TRIAGE)
                if (low.contains("argon") || low.contains("carbon") || low.contains("pro") || low.contains("ultra")) {
                    cats.add(ModelRoleCategory.DEEP_REASONING)
                }
            }
        }

        if (cats.isEmpty()) return null
        return ModelCatalogItem(
            providerId = providerId,
            modelId = id,
            label = displayNameHint.ifBlank { id },
            description = "${if (providerId == "openai") "OpenAI" else "Gemini"} 在线发现模型 ($id)",
            categories = cats,
            isLiveDiscovered = true
        )
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun refreshLiveProviderCatalog(
        httpClient: OkHttpClient,
        config: EdgeAiRuntimeConfig
    ): ModelCatalogRefreshResult = coroutineScope {
        val warnings = mutableListOf<String>()
        var oaiCount = 0
        var gemCount = 0

        val oaiDeferred = async(Dispatchers.IO) {
            val p = config.openai?.takeIf { it.apiKey.isNotBlank() } ?: return@async emptyList<ModelCatalogItem>()
            try {
                val url = "${p.baseUrl.trimEnd('/')}/models"
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${p.apiKey}")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val obj = MiniJson.parseObject(raw)
                    val data = obj["data"] as? List<*> ?: emptyList<Any?>()
                    data.mapNotNull { item ->
                        val id = (item as? Map<String, Any?>)?.get("id")?.toString().orEmpty()
                        classifyDiscoveredModel("openai", id)
                    }
                }
            } catch (e: Throwable) {
                synchronized(warnings) { warnings.add("OpenAI 模型列表拉取失败: ${e.message}") }
                emptyList()
            }
        }

        val gemDeferred = async(Dispatchers.IO) {
            val p = config.gemini?.takeIf { it.apiKey.isNotBlank() } ?: return@async emptyList<ModelCatalogItem>()
            try {
                val base = p.baseUrl.trimEnd('/').let {
                    if (it.endsWith("/v1beta") || it.endsWith("/v1")) it else "$it/v1beta"
                }
                val req = Request.Builder()
                    .url("$base/models?pageSize=200")
                    .header("x-goog-api-key", p.apiKey)
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val obj = MiniJson.parseObject(raw)
                    val models = obj["models"] as? List<*> ?: emptyList<Any?>()
                    models.mapNotNull { item ->
                        val m = item as? Map<String, Any?> ?: return@mapNotNull null
                        val name = m["name"]?.toString().orEmpty()
                        val disp = m["displayName"]?.toString().orEmpty()
                        classifyDiscoveredModel("gemini", name, disp)
                    }
                }
            } catch (e: Throwable) {
                synchronized(warnings) { warnings.add("Gemini 模型列表拉取失败: ${e.message}") }
                emptyList()
            }
        }

        val oaiList = oaiDeferred.await()
        val gemList = gemDeferred.await()
        oaiCount = oaiList.size
        gemCount = gemList.size

        val mergedByKey = LinkedHashMap<String, ModelCatalogItem>()
        for (preset in BUILTIN_PRESETS) {
            mergedByKey[preset.selectionKey] = preset
        }
        for (live in oaiList + gemList) {
            val existing = mergedByKey[live.selectionKey]
            if (existing != null) {
                mergedByKey[live.selectionKey] = existing.copy(
                    categories = existing.categories + live.categories,
                    isLiveDiscovered = true
                )
            } else {
                mergedByKey[live.selectionKey] = live
            }
        }

        ModelCatalogRefreshResult(
            items = mergedByKey.values.toList(),
            openAiDiscoveredCount = oaiCount,
            geminiDiscoveredCount = gemCount,
            refreshedAtMs = System.currentTimeMillis(),
            warnings = warnings
        )
    }
}

/**
 * Executes native creative generation (Image / Video / Music) against the user's selected OpenAI or Gemini model.
 */
class CreativeMediaEngine(
    private val httpClientProvider: () -> OkHttpClient,
    private val runtimeConfigProvider: () -> EdgeAiRuntimeConfig,
    private val roleSelectionProvider: (ModelRoleCategory) -> String
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun generateCreativeMedia(
        category: ModelRoleCategory,
        prompt: String
    ): GeneratedMediaResult = withContext(Dispatchers.IO) {
        val sel = roleSelectionProvider(category).ifBlank { category.defaultSelection }
        val providerId = sel.substringBefore(':', "gemini")
        val modelId = sel.substringAfter(':', sel)
        val cfg = runtimeConfigProvider()

        when (category) {
            ModelRoleCategory.IMAGE_GENERATION -> {
                if (providerId == "openai") {
                    val oai = cfg.openai?.takeIf { it.apiKey.isNotBlank() }
                        ?: error("未配置 OpenAI API Key")
                    generateOpenAiImage(oai, modelId, prompt)
                } else {
                    val gem = cfg.gemini?.takeIf { it.apiKey.isNotBlank() }
                        ?: error("未配置 Gemini API Key")
                    generateGeminiInteractionMedia(gem, modelId, prompt, expectedKind = "image")
                }
            }
            ModelRoleCategory.VIDEO_GENERATION -> {
                val gem = cfg.gemini?.takeIf { it.apiKey.isNotBlank() }
                    ?: error("视频生成需要配置 Google Gemini API Key（OpenAI Sora API 已下线）")
                generateGeminiInteractionMedia(gem, modelId, prompt, expectedKind = "video")
            }
            ModelRoleCategory.MUSIC_GENERATION -> {
                val gem = cfg.gemini?.takeIf { it.apiKey.isNotBlank() }
                    ?: error("歌曲/音乐创作需要配置 Google Gemini API Key (Lyria 3.5)")
                generateGeminiInteractionMedia(gem, modelId, prompt, expectedKind = "music")
            }
            else -> error("不支持的创作类型: $category")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun generateOpenAiImage(
        provider: ProviderEndpointConfig,
        modelId: String,
        prompt: String
    ): GeneratedMediaResult {
        val url = "${provider.baseUrl.trimEnd('/')}/images/generations"
        val payload = linkedMapOf(
            "model" to modelId,
            "prompt" to prompt,
            "n" to 1,
            "size" to "1024x1024"
        )
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${provider.apiKey}")
            .post(MiniJson.stringify(payload).toRequestBody(jsonMediaType))
            .build()

        val client = httpClientProvider()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("OpenAI 生图 HTTP ${resp.code}: ${raw.take(240)}")
            }
            val parsed = MiniJson.parseObject(raw)
            val data = parsed["data"] as? List<*> ?: emptyList<Any?>()
            val first = data.firstOrNull() as? Map<String, Any?> ?: error("OpenAI 未返回图片数据")
            val b64 = first["b64_json"]?.toString().orEmpty()
            val imgUrl = first["url"]?.toString().orEmpty()
            val bytes = when {
                b64.isNotBlank() -> Base64.getDecoder().decode(b64)
                imgUrl.isNotBlank() -> client.newCall(Request.Builder().url(imgUrl).get().build()).execute().use { r ->
                    r.body?.bytes() ?: error("下载生成图片失败")
                }
                else -> error("OpenAI 图片响应缺少 b64_json 与 url")
            }
            return GeneratedMediaResult(
                mediaKind = "image",
                mimeType = "image/png",
                bytes = bytes,
                modelUsed = modelId,
                caption = first["revised_prompt"]?.toString().orEmpty()
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun generateGeminiInteractionMedia(
        provider: ProviderEndpointConfig,
        modelId: String,
        prompt: String,
        expectedKind: String
    ): GeneratedMediaResult {
        val client = httpClientProvider()
        val hostRoot = provider.baseUrl.trimEnd('/').replace(Regex("/v1beta$|/v1$"), "")

        // Try Gemini Interactions v1 API first (supports Nano Banana 2.1, Gemini Omni 1.2 Flash video polling, and Lyria 3.5 music!)
        val createUrl = "$hostRoot/v1/interactions"
        val isAsyncVideo = expectedKind == "video"
        val createBody = linkedMapOf<String, Any?>(
            "model" to modelId,
            "input" to prompt
        )
        if (isAsyncVideo) createBody["background"] = true

        val createReq = Request.Builder()
            .url(createUrl)
            .header("x-goog-api-key", provider.apiKey)
            .post(MiniJson.stringify(createBody).toRequestBody(jsonMediaType))
            .build()

        var interactionObj: Map<String, Any?> = client.newCall(createReq).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                // Fallback to classic generateContent for image models if /v1/interactions is unavailable on custom base URL
                if (expectedKind == "image") {
                    return generateGeminiClassicImageFallback(provider, modelId, prompt)
                }
                throw IOException("Gemini $expectedKind HTTP ${resp.code}: ${raw.take(260)}")
            }
            MiniJson.parseObject(raw)
        }

        val interactionId = interactionObj["id"]?.toString().orEmpty()
        var pollCount = 0
        while (interactionId.isNotBlank() && pollCount < 45) {
            val status = interactionObj["status"]?.toString().orEmpty().lowercase()
            if (status in setOf("completed", "succeeded", "done") || status.isEmpty()) break
            if (status in setOf("failed", "cancelled", "error")) {
                throw IOException("Gemini 创作任务失败 ($status): ${interactionObj["error"] ?: ""}")
            }
            delay(3000L)
            pollCount++
            val pollReq = Request.Builder()
                .url("$hostRoot/v1/interactions/$interactionId")
                .header("x-goog-api-key", provider.apiKey)
                .get()
                .build()
            interactionObj = client.newCall(pollReq).execute().use { r ->
                val raw = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw IOException("轮询创作状态失败 HTTP ${r.code}")
                MiniJson.parseObject(raw)
            }
        }

        extractMediaFromInteraction(interactionObj, client, provider.apiKey, hostRoot)?.let { (mime, bytes, text) ->
            return GeneratedMediaResult(
                mediaKind = expectedKind,
                mimeType = mime,
                bytes = bytes,
                modelUsed = modelId,
                caption = text
            )
        }

        if (expectedKind == "image") {
            return generateGeminiClassicImageFallback(provider, modelId, prompt)
        }
        throw IOException("Gemini 未返回可解码的 $expectedKind 媒体数据")
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractMediaFromInteraction(
        obj: Map<String, Any?>,
        client: OkHttpClient,
        apiKey: String,
        hostRoot: String
    ): Triple<String, ByteArray, String>? {
        val textSb = StringBuilder()
        val steps = (obj["steps"] as? List<*>) ?: (obj["outputs"] as? List<*>) ?: emptyList<Any?>()
        for (st in steps) {
            val sm = st as? Map<String, Any?> ?: continue
            val contentList = (sm["content"] as? List<*>) ?: listOf(sm)
            for (c in contentList) {
                val cm = c as? Map<String, Any?> ?: continue
                val cType = cm["type"]?.toString().orEmpty()
                if (cType == "text") {
                    textSb.append(cm["text"]?.toString().orEmpty())
                }
                val b64 = (cm["data"] ?: (cm["inline_data"] as? Map<*, *>)?.get("data")
                    ?: (cm["inlineData"] as? Map<*, *>)?.get("data"))?.toString().orEmpty()
                val mime = (cm["mime_type"] ?: cm["mimeType"]
                    ?: (cm["inline_data"] as? Map<*, *>)?.get("mime_type")
                    ?: (cm["inlineData"] as? Map<*, *>)?.get("mimeType"))?.toString().orEmpty()
                if (b64.isNotBlank() && (mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/"))) {
                    return Triple(mime, Base64.getDecoder().decode(b64), textSb.toString().trim())
                }
                val fileUri = (cm["uri"] ?: cm["file_uri"] ?: (cm["file_data"] as? Map<*, *>)?.get("file_uri"))?.toString().orEmpty()
                if (fileUri.isNotBlank()) {
                    val dlUrl = if (fileUri.startsWith("http")) fileUri else "$hostRoot/v1beta/${fileUri.trimStart('/')}?alt=media"
                    val bytes = client.newCall(
                        Request.Builder().url(dlUrl).header("x-goog-api-key", apiKey).get().build()
                    ).execute().use { r -> r.body?.bytes() }
                    if (bytes != null && bytes.isNotEmpty()) {
                        val fallbackMime = when {
                            mime.isNotBlank() -> mime
                            dlUrl.contains("mp4") -> "video/mp4"
                            dlUrl.contains("wav") || dlUrl.contains("mp3") -> "audio/mpeg"
                            else -> "application/octet-stream"
                        }
                        return Triple(fallbackMime, bytes, textSb.toString().trim())
                    }
                }
            }
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun generateGeminiClassicImageFallback(
        provider: ProviderEndpointConfig,
        modelId: String,
        prompt: String
    ): GeneratedMediaResult {
        val base = provider.baseUrl.trimEnd('/').let {
            if (it.endsWith("/v1beta") || it.endsWith("/v1")) it else "$it/v1beta"
        }
        val url = "$base/models/$modelId:generateContent"
        val payload = linkedMapOf(
            "contents" to listOf(linkedMapOf("role" to "user", "parts" to listOf(linkedMapOf("text" to prompt)))),
            "generationConfig" to linkedMapOf("responseModalities" to listOf("TEXT", "IMAGE"))
        )
        val req = Request.Builder()
            .url(url)
            .header("x-goog-api-key", provider.apiKey)
            .post(MiniJson.stringify(payload).toRequestBody(jsonMediaType))
            .build()
        httpClientProvider().newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("Gemini 生图 HTTP ${resp.code}: ${raw.take(240)}")
            val parsed = MiniJson.parseObject(raw)
            val cands = parsed["candidates"] as? List<*> ?: emptyList<Any?>()
            val first = cands.firstOrNull() as? Map<String, Any?> ?: error("Gemini 未返回候选结果")
            val parts = (first["content"] as? Map<String, Any?>)?.get("parts") as? List<*> ?: emptyList<Any?>()
            var caption = ""
            for (p in parts) {
                val pm = p as? Map<String, Any?> ?: continue
                pm["text"]?.toString()?.let { caption += it }
                val inline = (pm["inlineData"] ?: pm["inline_data"]) as? Map<String, Any?>
                if (inline != null) {
                    val mime = (inline["mimeType"] ?: inline["mime_type"])?.toString() ?: "image/png"
                    val b64 = inline["data"]?.toString().orEmpty()
                    if (b64.isNotBlank()) {
                        return GeneratedMediaResult("image", mime, Base64.getDecoder().decode(b64), modelId, caption.trim())
                    }
                }
            }
            throw IOException("Gemini 响应未包含图像数据")
        }
    }
}
