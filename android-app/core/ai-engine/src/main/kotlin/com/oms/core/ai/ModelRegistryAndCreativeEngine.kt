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

    private val DATE_SUFFIX_REGEX = Regex(
        """(?:-\d{4}-\d{2}-\d{2}|-\d{2}-\d{4}|-\d{4}|-0\d-\d{2})(?:-[a-z]+)?$""",
        RegexOption.IGNORE_CASE
    )

    fun defaultRoleSelections(): Map<ModelRoleCategory, String> =
        ModelRoleCategory.values().associateWith { it.defaultSelection }

    private val BUILTIN_KEYS: Set<String> by lazy {
        BUILTIN_PRESETS.map { it.selectionKey }.toSet()
    }

    /**
     * Returns true if a model ID is an outdated generation, a dated snapshot, or an experimental/preview
     * variant that should be hidden from the user-facing model selector.
     */
    fun shouldHideLegacyOrDatedModel(providerId: String, rawModelId: String): Boolean {
        val id = rawModelId.removePrefix("models/").trim()
        if (id.isEmpty()) return true
        if ("$providerId:$id" in BUILTIN_KEYS) return false

        val low = id.lowercase()
        if (DATE_SUFFIX_REGEX.containsMatchIn(low)) return true

        val noisyMarkers = listOf(
            "embedding", "moderation", "babbage", "davinci", "curie", "ada",
            "-preview", "-exp", "-experimental", "-customtools", "-latest",
            "-search-api", "-diarize", "-tts", "whisper", "tts-1", "dall-e",
            "chatgpt-", "chat-latest", "aqa", "bison", "decision-", "robotics", "genie-"
        )
        if (noisyMarkers.any { low.contains(it) }) return true

        if (providerId == "openai") {
            // Hide pre-GPT-6 chat/reasoning generations and superseded early audio/image snapshots
            val legacyOpenAiPrefixes = listOf(
                "gpt-3", "gpt-4", "gpt-5", "o1", "o3", "o4",
                "gpt-audio", "gpt-image-1", "gpt-image-2.5",
                "gpt-realtime-1", "gpt-realtime-mini", "gpt-realtime-whisper", "gpt-realtime-translate"
            )
            if (legacyOpenAiPrefixes.any { low.startsWith(it) }) return true
            if (low in setOf("gpt-image-2", "gpt-realtime", "gpt-realtime-2", "gpt-live-1")) return true
        } else if (providerId == "gemini") {
            val legacyGeminiPrefixes = listOf(
                "gemini-1", "gemini-2", "gemini-pro", "gemini-flash",
                "gemini-3-flash", "gemini-3.0", "gemini-3.6", "gemini-3.7",
                "gemini-omni-1.0", "gemini-omni-1.1",
                "lyria-1", "lyria-2", "lyria-3-clip", "lyria-3-pro",
                "imagen-2", "imagen-3", "gemma-"
            )
            if (legacyGeminiPrefixes.any { low.startsWith(it) }) return true
        }
        return false
    }

    fun filterCleanCatalog(items: List<ModelCatalogItem>): List<ModelCatalogItem> =
        items.filterNot { shouldHideLegacyOrDatedModel(it.providerId, it.modelId) }

    fun optionsForCategory(
        category: ModelRoleCategory,
        allItems: List<ModelCatalogItem>
    ): List<ModelCatalogItem> =
        filterCleanCatalog(allItems).filter { category in it.categories }

    /**
     * Classifies any newly released model ID returned by OpenAI `/v1/models` or Gemini `/v1beta/models`
     * into functional categories while filtering out obsolete, dated, or preview endpoints.
     */
    fun classifyDiscoveredModel(providerId: String, rawModelId: String, displayNameHint: String = ""): ModelCatalogItem? {
        val id = rawModelId.removePrefix("models/").trim()
        val low = id.lowercase()
        if (id.isEmpty() || shouldHideLegacyOrDatedModel(providerId, id)) return null

        val cats = LinkedHashSet<ModelRoleCategory>()
        when {
            low.contains("transcribe") -> {
                cats.add(ModelRoleCategory.SPEECH_TRANSCRIBE)
                if (low.contains("-live")) {
                    cats.add(ModelRoleCategory.LIVE_WALK_TALK)
                }
            }
            low.contains("lyria") || low.contains("music") -> cats.add(ModelRoleCategory.MUSIC_GENERATION)
            low.contains("omni-") || low.contains("veo") || low.contains("video") -> cats.add(ModelRoleCategory.VIDEO_GENERATION)
            low.contains("gpt-image") || low.contains("banana") || low.endsWith("-image") || low.contains("imagen") ->
                cats.add(ModelRoleCategory.IMAGE_GENERATION)
            low.contains("realtime") || low.contains("-live") -> {
                cats.add(ModelRoleCategory.LIVE_WALK_TALK)
                if (providerId == "gemini" && !low.contains("transcribe")) {
                    cats.add(ModelRoleCategory.LIVE_GLASS_CAMERA)
                }
            }
            providerId == "openai" && (low.startsWith("gpt-6") || low.startsWith("gpt-7") || low.startsWith("o5")) -> {
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

    internal fun formatProviderHttpError(providerLabel: String, statusCode: Int, rawBody: String): String {
        val low = rawBody.lowercase()
        if (low.contains("user location is not supported") ||
            low.contains("unsupported_country_region_territory") ||
            low.contains("region is not supported")
        ) {
            return "HTTP $statusCode（当前节点所在地区不受 $providerLabel 支持，请切回「智能优选」或改选新加坡/日本/美国节点）"
        }
        val detail = runCatching {
            val obj = MiniJson.parseObject(rawBody)
            (obj["error"] as? Map<*, *>)?.get("message")?.toString()?.trim().orEmpty()
        }.getOrDefault("").take(120)
        return if (detail.isNotEmpty()) "HTTP $statusCode ($detail)" else "HTTP $statusCode"
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
                    if (!resp.isSuccessful) throw IOException(formatProviderHttpError("OpenAI", resp.code, raw))
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
                    if (!resp.isSuccessful) throw IOException(formatProviderHttpError("Gemini", resp.code, raw))
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
        val cleanModelId = modelId.removePrefix("models/").trim()

        // Gemini Interactions v1beta API (`Api-Revision: 2026-05-20`) powers Lyria 3.5 music,
        // Gemini Omni 1.2 Flash native sound+video, and Nano Banana image generation.
        val createUrl = "$hostRoot/v1beta/interactions"
        val createBody = linkedMapOf<String, Any?>("model" to cleanModelId)
        when (expectedKind) {
            "video" -> {
                createBody["input"] = listOf(linkedMapOf("type" to "text", "text" to prompt))
                createBody["response_format"] = linkedMapOf(
                    "type" to "video",
                    "aspect_ratio" to "16:9",
                    "duration" to "5s"
                )
            }
            "music" -> {
                createBody["input"] = prompt
                createBody["response_format"] = linkedMapOf("type" to "audio")
            }
            else -> {
                createBody["input"] = prompt
                createBody["response_modality_set"] = linkedMapOf("modalities" to listOf("image", "text"))
            }
        }

        val createReq = Request.Builder()
            .url(createUrl)
            .header("x-goog-api-key", provider.apiKey)
            .header("Api-Revision", "2026-05-20")
            .post(MiniJson.stringify(createBody).toRequestBody(jsonMediaType))
            .build()

        var interactionObj: Map<String, Any?> = client.newCall(createReq).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                if (expectedKind == "image") {
                    return generateGeminiClassicImageFallback(provider, cleanModelId, prompt)
                }
                throw IOException(
                    "Gemini ${if (expectedKind == "music") "写歌" else "视频生成"} " +
                        ModelCatalogRegistry.formatProviderHttpError("Gemini", resp.code, raw)
                )
            }
            MiniJson.parseObject(raw)
        }

        val interactionId = interactionObj["id"]?.toString().orEmpty()
        var pollCount = 0
        while (interactionId.isNotBlank() && pollCount < 60) {
            val status = interactionObj["status"]?.toString().orEmpty().lowercase()
            if (status in setOf("completed", "succeeded", "done") || status.isEmpty()) break
            if (status in setOf("failed", "cancelled", "error")) {
                throw IOException("Gemini 创作任务失败 ($status): ${interactionObj["error"] ?: ""}")
            }
            // If unary response already embedded steps/outputs media, extract immediately without extra polling
            if (extractMediaFromInteraction(interactionObj, client, provider.apiKey, hostRoot, expectedKind) != null) {
                break
            }
            delay(3000L)
            pollCount++
            val pollReq = Request.Builder()
                .url("$hostRoot/v1beta/interactions/$interactionId")
                .header("x-goog-api-key", provider.apiKey)
                .header("Api-Revision", "2026-05-20")
                .get()
                .build()
            interactionObj = client.newCall(pollReq).execute().use { r ->
                val raw = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw IOException("轮询创作状态失败 HTTP ${r.code}")
                MiniJson.parseObject(raw)
            }
        }

        extractMediaFromInteraction(interactionObj, client, provider.apiKey, hostRoot, expectedKind)?.let { (mime, bytes, text) ->
            return GeneratedMediaResult(
                mediaKind = expectedKind,
                mimeType = mime,
                bytes = bytes,
                modelUsed = cleanModelId,
                caption = text
            )
        }

        if (expectedKind == "image") {
            return generateGeminiClassicImageFallback(provider, cleanModelId, prompt)
        }
        throw IOException("Gemini 未返回可解码的 $expectedKind 媒体数据")
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractMediaFromInteraction(
        obj: Map<String, Any?>,
        client: OkHttpClient,
        apiKey: String,
        hostRoot: String,
        expectedKind: String = ""
    ): Triple<String, ByteArray, String>? {
        val textSb = StringBuilder()
        var foundMedia: Pair<String, ByteArray>? = null
        val steps = (obj["steps"] as? List<*>) ?: (obj["outputs"] as? List<*>) ?: emptyList<Any?>()
        for (st in steps) {
            val sm = st as? Map<String, Any?> ?: continue
            val stepType = sm["type"]?.toString().orEmpty()
            if (stepType == "thought") continue
            val contentList = (sm["content"] as? List<*>) ?: listOf(sm)
            for (c in contentList) {
                val cm = c as? Map<String, Any?> ?: continue
                val cType = cm["type"]?.toString().orEmpty().lowercase()
                if (cType == "text") {
                    val rawTxt = cm["text"]?.toString().orEmpty()
                    // Strip internal Lyria instrumental structure markers like [[A0]] [[B1]] if standalone
                    val cleanedTxt = rawTxt.replace(Regex("""\[\[[A-Z]\d+\]\]"""), "").trim()
                    if (cleanedTxt.isNotEmpty()) {
                        if (textSb.isNotEmpty()) textSb.append('\n')
                        textSb.append(cleanedTxt)
                    }
                }
                if (foundMedia != null) continue

                val b64 = (cm["data"] ?: (cm["inline_data"] as? Map<*, *>)?.get("data")
                    ?: (cm["inlineData"] as? Map<*, *>)?.get("data"))?.toString().orEmpty()
                val rawMime = (cm["mime_type"] ?: cm["mimeType"]
                    ?: (cm["inline_data"] as? Map<*, *>)?.get("mime_type")
                    ?: (cm["inlineData"] as? Map<*, *>)?.get("mimeType"))?.toString().orEmpty()
                val inferredMime = when {
                    rawMime.isNotBlank() -> rawMime
                    cType == "audio" || expectedKind == "music" -> "audio/mpeg"
                    cType == "video" || expectedKind == "video" -> "video/mp4"
                    cType == "image" || expectedKind == "image" -> "image/png"
                    else -> ""
                }
                if (b64.isNotBlank() && (
                        inferredMime.startsWith("image/") ||
                            inferredMime.startsWith("video/") ||
                            inferredMime.startsWith("audio/")
                        )
                ) {
                    val cleanB64 = b64.replace("\n", "").replace("\r", "").trim()
                    foundMedia = inferredMime to Base64.getDecoder().decode(cleanB64)
                    continue
                }

                val fileUri = (cm["uri"] ?: cm["file_uri"] ?: (cm["file_data"] as? Map<*, *>)?.get("file_uri"))?.toString().orEmpty()
                if (fileUri.isNotBlank()) {
                    val dlUrl = when {
                        fileUri.contains("alt=media") -> fileUri
                        fileUri.startsWith("https://generativelanguage.googleapis.com/") && fileUri.contains("/files/") ->
                            if (fileUri.contains(":download")) "$fileUri?alt=media" else "${fileUri.trimEnd('/')}:download?alt=media"
                        fileUri.startsWith("http") -> fileUri
                        else -> "$hostRoot/v1beta/${fileUri.trimStart('/')}:download?alt=media"
                    }
                    val bytes = client.newCall(
                        Request.Builder().url(dlUrl).header("x-goog-api-key", apiKey).get().build()
                    ).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
                    if (bytes != null && bytes.isNotEmpty()) {
                        val fallbackMime = when {
                            inferredMime.isNotBlank() -> inferredMime
                            dlUrl.contains("mp4") || expectedKind == "video" -> "video/mp4"
                            dlUrl.contains("wav") || dlUrl.contains("mp3") || expectedKind == "music" -> "audio/mpeg"
                            else -> "application/octet-stream"
                        }
                        foundMedia = fallbackMime to bytes
                    }
                }
            }
        }
        return foundMedia?.let { (m, b) -> Triple(m, b, textSb.toString().trim()) }
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
