package com.oms.core.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

enum class RouteIntent {
    AUTO,
    DEEP_THINKING,
    WEB_SEARCH_GROUNDED,
    BOTANICAL_VISION,
    WECHAT_ARTICLE_TRIAGE
}

data class RouteDecision(
    val provider: ProviderEndpointConfig,
    val model: String,
    val effort: ReasoningEffort,
    val useWebSearch: Boolean,
    val protocol: String // "openai_responses" | "gemini_native" | "openai_chat"
) {
    val useGeminiSearchGrounding: Boolean
        get() = useWebSearch && protocol == "gemini_native"
}

class DualLlmEngine(
    private val httpClientProvider: () -> OkHttpClient,
    @Volatile var runtimeConfig: EdgeAiRuntimeConfig = EdgeAiRuntimeConfig(),
    @Volatile var roleOverrides: Map<ModelRoleCategory, String> = emptyMap()
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val searchTriggerRegex = Regex(
        """(今天|今日|最新|最近|今年|昨天|明天|后天|新闻|股价|汇率|天气|气温|下雨|比分|查一下|搜一下|核实|官网|发布了吗|多少钱|2026)""",
        RegexOption.IGNORE_CASE
    )

    private fun resolveExplicitOverride(
        category: ModelRoleCategory,
        effort: ReasoningEffort,
        enableWebSearch: Boolean
    ): RouteDecision? {
        val raw = roleOverrides[category]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val providerId = raw.substringBefore(':', "")
        val modelId = raw.substringAfter(':', raw).trim()
        if (modelId.isEmpty()) return null
        val cfg = runtimeConfig
        return when (providerId) {
            "openai" -> cfg.openai?.takeIf { it.apiKey.isNotBlank() }?.let { oai ->
                val proto = resolveOpenAiProtocol(oai, modelId)
                RouteDecision(oai, modelId, effort, enableWebSearch && proto == "openai_responses", proto)
            }
            "gemini" -> cfg.gemini?.takeIf { it.apiKey.isNotBlank() }?.let { gem ->
                RouteDecision(gem, modelId, effort, enableWebSearch, "gemini_native")
            }
            else -> null
        }
    }

    fun decideRoute(
        intent: RouteIntent,
        userText: String = "",
        hasImages: Boolean = false,
        forceWebSearch: Boolean = false
    ): RouteDecision {
        val cfg = runtimeConfig
        val oai = cfg.openai?.takeIf { it.apiKey.isNotBlank() }
        val gem = cfg.gemini?.takeIf { it.apiKey.isNotBlank() }
        val dom = cfg.domesticFallback?.takeIf { it.apiKey.isNotBlank() }
        val autoNeedsSearch = !hasImages && searchTriggerRegex.containsMatchIn(userText)
        val wantSearch = forceWebSearch || intent == RouteIntent.WEB_SEARCH_GROUNDED || (intent == RouteIntent.AUTO && autoNeedsSearch)

        when (intent) {
            RouteIntent.BOTANICAL_VISION ->
                resolveExplicitOverride(ModelRoleCategory.VISION_UNDERSTANDING, ReasoningEffort.MEDIUM, true)?.let { return it }
            RouteIntent.WECHAT_ARTICLE_TRIAGE ->
                resolveExplicitOverride(ModelRoleCategory.WECHAT_TRIAGE, ReasoningEffort.MEDIUM, false)?.let { return it }
            RouteIntent.DEEP_THINKING ->
                resolveExplicitOverride(ModelRoleCategory.DEEP_REASONING, ReasoningEffort.HIGH, wantSearch)?.let { return it }
            RouteIntent.AUTO -> {
                if (hasImages) {
                    resolveExplicitOverride(ModelRoleCategory.VISION_UNDERSTANDING, ReasoningEffort.LOW, wantSearch)?.let { return it }
                }
                if (!autoNeedsSearch && !forceWebSearch) {
                    resolveExplicitOverride(ModelRoleCategory.CHAT_PRIMARY, ReasoningEffort.LOW, false)?.let { return it }
                }
            }
            RouteIntent.WEB_SEARCH_GROUNDED -> {
                // If user explicitly pinned a Gemini or OpenAI Responses primary model, honor it WITH web search enabled;
                // otherwise fall through to fast search-grounded routing below.
                resolveExplicitOverride(ModelRoleCategory.CHAT_PRIMARY, ReasoningEffort.LOW, true)
                    ?.takeIf { it.useWebSearch }
                    ?.let { return it }
            }
        }

        return when (intent) {
            RouteIntent.BOTANICAL_VISION -> when {
                gem != null -> RouteDecision(gem, gem.flagshipModel.ifBlank { AiModelCatalog.GEMINI_ARGON }, ReasoningEffort.MEDIUM, true, "gemini_native")
                oai != null -> {
                    val m = oai.flagshipModel.ifBlank { AiModelCatalog.OPENAI_SOL }
                    val proto = resolveOpenAiProtocol(oai, m)
                    RouteDecision(oai, m, ReasoningEffort.MEDIUM, proto == "openai_responses", proto)
                }
                else -> error("未配置多模态模型密钥")
            }

            RouteIntent.WEB_SEARCH_GROUNDED -> when {
                gem != null -> RouteDecision(gem, gem.fastModel.ifBlank { AiModelCatalog.GEMINI_FLASH }, ReasoningEffort.LOW, true, "gemini_native")
                oai != null -> {
                    val m = oai.fastModel.ifBlank { AiModelCatalog.OPENAI_SOL }
                    val proto = resolveOpenAiProtocol(oai, m)
                    RouteDecision(oai, m, ReasoningEffort.LOW, proto == "openai_responses", proto)
                }
                dom != null -> RouteDecision(dom, dom.fastModel, ReasoningEffort.LOW, false, "openai_chat")
                else -> error("未配置模型密钥")
            }

            RouteIntent.WECHAT_ARTICLE_TRIAGE -> when {
                oai != null -> {
                    val m = oai.flagshipModel.ifBlank { AiModelCatalog.OPENAI_SOL }
                    RouteDecision(oai, m, ReasoningEffort.MEDIUM, false, resolveOpenAiProtocol(oai, m))
                }
                gem != null -> RouteDecision(gem, gem.flagshipModel.ifBlank { AiModelCatalog.GEMINI_ARGON }, ReasoningEffort.MEDIUM, false, "gemini_native")
                dom != null -> RouteDecision(dom, dom.flagshipModel, ReasoningEffort.MEDIUM, false, "openai_chat")
                else -> error("未配置初筛模型密钥")
            }

            RouteIntent.DEEP_THINKING -> when {
                oai != null -> {
                    val m = oai.reasoningModel.ifBlank { AiModelCatalog.OPENAI_ASTRA }
                    val proto = resolveOpenAiProtocol(oai, m)
                    RouteDecision(oai, m, ReasoningEffort.HIGH, wantSearch && proto == "openai_responses", proto)
                }
                gem != null -> RouteDecision(gem, gem.flagshipModel.ifBlank { AiModelCatalog.GEMINI_ARGON }, ReasoningEffort.HIGH, wantSearch, "gemini_native")
                dom != null -> RouteDecision(dom, dom.reasoningModel.ifBlank { dom.flagshipModel }, ReasoningEffort.HIGH, false, "openai_chat")
                else -> error("未配置深度推理模型密钥")
            }

            RouteIntent.AUTO -> {
                when {
                    wantSearch && gem != null -> RouteDecision(gem, gem.fastModel.ifBlank { AiModelCatalog.GEMINI_FLASH }, ReasoningEffort.LOW, true, "gemini_native")
                    oai != null -> {
                        val m = oai.fastModel.ifBlank { AiModelCatalog.OPENAI_SOL }
                        val proto = resolveOpenAiProtocol(oai, m)
                        RouteDecision(oai, m, ReasoningEffort.LOW, wantSearch && proto == "openai_responses", proto)
                    }
                    gem != null -> RouteDecision(gem, gem.fastModel.ifBlank { AiModelCatalog.GEMINI_FLASH }, ReasoningEffort.LOW, wantSearch, "gemini_native")
                    dom != null -> RouteDecision(dom, dom.fastModel, ReasoningEffort.LOW, false, "openai_chat")
                    else -> error("尚未配置 OpenAI 或 Gemini API Key")
                }
            }
        }
    }

    private fun resolveOpenAiProtocol(p: ProviderEndpointConfig, model: String): String {
        return if (AiModelCatalog.isOpenAiResponsesModel(model) &&
            !p.baseUrl.lowercase().contains("generativelanguage.googleapis.com")
        ) "openai_responses" else "openai_chat"
    }

    suspend fun completeSync(
        intent: RouteIntent,
        developerPrompt: String,
        turns: List<LlmTurnMessage>,
        jsonMode: Boolean = false,
        forceWebSearch: Boolean = false
    ): LlmStreamEvent.Completed = withContext(Dispatchers.IO) {
        val lastUser = turns.lastOrNull()?.content.orEmpty()
        val hasImgs = turns.any { it.images.isNotEmpty() }
        val primary = decideRoute(intent, lastUser, hasImgs, forceWebSearch)
        try {
            executeSyncDecision(primary, developerPrompt, turns, jsonMode)
        } catch (primaryErr: Throwable) {
            for (backup in buildBackupDecisions(primary, jsonMode)) {
                try {
                    return@withContext executeSyncDecision(backup, developerPrompt, turns, jsonMode)
                } catch (_: Throwable) {
                }
            }
            throw primaryErr
        }
    }

    private fun buildBackupDecisions(failed: RouteDecision, jsonMode: Boolean): List<RouteDecision> {
        val out = mutableListOf<RouteDecision>()
        val cfg = runtimeConfig
        cfg.gemini?.takeIf { it.apiKey.isNotBlank() && it.providerId != failed.provider.providerId }?.let { g ->
            out.add(RouteDecision(g, g.flagshipModel.ifBlank { AiModelCatalog.GEMINI_ARGON }, failed.effort, !jsonMode && failed.useWebSearch, "gemini_native"))
        }
        cfg.openai?.takeIf { it.apiKey.isNotBlank() && it.providerId != failed.provider.providerId }?.let { o ->
            val m = o.flagshipModel.ifBlank { AiModelCatalog.OPENAI_SOL }
            val proto = resolveOpenAiProtocol(o, m)
            out.add(RouteDecision(o, m, failed.effort, !jsonMode && failed.useWebSearch && proto == "openai_responses", proto))
        }
        cfg.domesticFallback?.takeIf { it.apiKey.isNotBlank() && it.providerId != failed.provider.providerId }?.let { d ->
            out.add(RouteDecision(d, d.flagshipModel.ifBlank { d.fastModel }, ReasoningEffort.LOW, false, "openai_chat"))
        }
        return out
    }

    private fun executeSyncDecision(
        decision: RouteDecision,
        developerPrompt: String,
        turns: List<LlmTurnMessage>,
        jsonMode: Boolean
    ): LlmStreamEvent.Completed = when (decision.protocol) {
        "openai_responses" -> callOpenAiResponsesSync(decision, developerPrompt, turns, jsonMode)
        "gemini_native" -> callGeminiNativeSync(decision, developerPrompt, turns, jsonMode)
        else -> callOpenAiChatSync(decision, developerPrompt, turns, jsonMode)
    }

    fun streamTurn(
        intent: RouteIntent,
        developerPrompt: String,
        turns: List<LlmTurnMessage>,
        forceWebSearch: Boolean = false
    ): Flow<LlmStreamEvent> = flow {
        val lastUser = turns.lastOrNull()?.content.orEmpty()
        val hasImgs = turns.any { it.images.isNotEmpty() }
        val primary = decideRoute(intent, lastUser, hasImgs, forceWebSearch)
        val candidates = listOf(primary) + buildBackupDecisions(primary, jsonMode = false)
        var lastError: Throwable? = null

        for ((idx, candidate) in candidates.withIndex()) {
            var emittedSubstantialText = false
            try {
                val emitWrapper: suspend (LlmStreamEvent) -> Unit = { ev ->
                    if (ev is LlmStreamEvent.TextDelta && ev.text.isNotEmpty()) {
                        emittedSubstantialText = true
                    }
                    emit(ev)
                }
                when (candidate.protocol) {
                    "openai_responses" -> streamOpenAiResponses(candidate, developerPrompt, turns, emitWrapper)
                    "gemini_native" -> streamGeminiNative(candidate, developerPrompt, turns, emitWrapper)
                    else -> {
                        val res = callOpenAiChatSync(candidate, developerPrompt, turns, false)
                        if (res.fullReasoning.isNotEmpty()) emit(LlmStreamEvent.ReasoningDelta(res.fullReasoning))
                        if (res.fullText.isNotEmpty()) {
                            emittedSubstantialText = true
                            emit(LlmStreamEvent.TextDelta(res.fullText))
                        }
                        emit(res)
                    }
                }
                return@flow
            } catch (err: Throwable) {
                if (err is kotlinx.coroutines.CancellationException) throw err
                lastError = err
                if (emittedSubstantialText || idx == candidates.lastIndex) {
                    throw err
                }
            }
        }
        throw (lastError ?: IOException("All configured AI providers failed"))
    }.flowOn(Dispatchers.IO)

    fun buildOpenAiResponsesPayload(
        decision: RouteDecision,
        developerPrompt: String,
        turns: List<LlmTurnMessage>,
        jsonMode: Boolean,
        stream: Boolean
    ): Map<String, Any?> {
        val inputItems = ArrayList<Map<String, Any?>>(turns.size + 1)
        if (developerPrompt.isNotBlank()) {
            inputItems.add(linkedMapOf("role" to "developer", "content" to developerPrompt))
        }
        for (t in turns) {
            if (t.images.isEmpty()) {
                inputItems.add(linkedMapOf("role" to t.role, "content" to t.content))
            } else {
                val parts = ArrayList<Map<String, Any?>>()
                if (t.content.isNotBlank()) parts.add(linkedMapOf("type" to "input_text", "text" to t.content))
                for (img in t.images) {
                    parts.add(linkedMapOf("type" to "input_image", "image_url" to "data:${img.mimeType};base64,${img.base64Data}"))
                }
                inputItems.add(linkedMapOf("role" to t.role, "content" to parts))
            }
        }
        val payload = linkedMapOf<String, Any?>(
            "model" to decision.model,
            "reasoning" to linkedMapOf("effort" to decision.effort.wireValue),
            "input" to inputItems
        )
        if (decision.useWebSearch && !jsonMode) {
            payload["tools"] = listOf(linkedMapOf("type" to "web_search"))
        }
        if (jsonMode) payload["text"] = linkedMapOf("format" to linkedMapOf("type" to "json_object"))
        if (stream) payload["stream"] = true
        return payload
    }

    fun extractOpenAiResponsesOutput(parsed: Map<String, Any?>): Pair<String, String> {
        val (t, r, _) = extractOpenAiResponsesOutputWithSources(parsed)
        return t to r
    }

    @Suppress("UNCHECKED_CAST")
    fun extractOpenAiResponsesOutputWithSources(parsed: Map<String, Any?>): Triple<String, String, List<GroundingSource>> {
        val topOutputText = parsed["output_text"]?.toString().orEmpty()
        val textSb = StringBuilder()
        val reasoningSb = StringBuilder()
        val sources = ArrayList<GroundingSource>()
        val seenUris = HashSet<String>()
        val outputArr = parsed["output"] as? List<*> ?: emptyList<Any?>()
        for (item in outputArr) {
            val m = item as? Map<String, Any?> ?: continue
            when (m["type"]?.toString()) {
                "reasoning" -> {
                    val summaryArr = m["summary"] as? List<*> ?: emptyList<Any?>()
                    for (s in summaryArr) {
                        val st = (s as? Map<String, Any?>)?.get("text")?.toString().orEmpty()
                        if (st.isNotEmpty()) {
                            if (reasoningSb.isNotEmpty()) reasoningSb.append('\n')
                            reasoningSb.append(st)
                        }
                    }
                }
                "message" -> {
                    val contentArr = m["content"] as? List<*> ?: emptyList<Any?>()
                    for (c in contentArr) {
                        val cm = c as? Map<String, Any?> ?: continue
                        val ct = cm["type"]?.toString()
                        if (ct == "output_text" || ct == "text") {
                            textSb.append(cm["text"]?.toString().orEmpty())
                            val annArr = cm["annotations"] as? List<*> ?: emptyList<Any?>()
                            for (ann in annArr) {
                                val am = ann as? Map<String, Any?> ?: continue
                                if (am["type"]?.toString() == "url_citation") {
                                    val uri = am["url"]?.toString()?.trim().orEmpty()
                                    val title = am["title"]?.toString()?.trim().orEmpty().ifBlank { uri }
                                    if (uri.isNotEmpty() && seenUris.add(uri)) {
                                        sources.add(GroundingSource(title, uri))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return Triple(if (textSb.isNotEmpty()) textSb.toString() else topOutputText, reasoningSb.toString(), sources)
    }

    private fun callOpenAiResponsesSync(
        decision: RouteDecision,
        developerPrompt: String,
        turns: List<LlmTurnMessage>,
        jsonMode: Boolean
    ): LlmStreamEvent.Completed {
        val url = "${decision.provider.baseUrl.trimEnd('/')}/responses"
        val body = MiniJson.stringify(buildOpenAiResponsesPayload(decision, developerPrompt, turns, jsonMode, false))
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${decision.provider.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(jsonMediaType))
            .build()
        httpClientProvider().newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("OpenAI Responses HTTP ${resp.code} (${decision.model}): ${raw.take(280)}")
            val (text, reasoning, sources) = extractOpenAiResponsesOutputWithSources(MiniJson.parseObject(raw))
            if (text.isBlank()) throw IOException("Empty output_text from OpenAI Responses (${decision.model})")
            val modelLabel = if (decision.useWebSearch || sources.isNotEmpty()) "${decision.model} + Search" else decision.model
            return LlmStreamEvent.Completed(text.trim(), reasoning.trim(), modelLabel, sources)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun streamOpenAiResponses(
        decision: RouteDecision,
        developerPrompt: String,
        turns: List<LlmTurnMessage>,
        emitEvent: suspend (LlmStreamEvent) -> Unit
    ) {
        val url = "${decision.provider.baseUrl.trimEnd('/')}/responses"
        val body = MiniJson.stringify(buildOpenAiResponsesPayload(decision, developerPrompt, turns, false, true))
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${decision.provider.apiKey}")
            .header("Accept", "text/event-stream")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(jsonMediaType))
            .build()

        val textAcc = StringBuilder()
        val reasoningAcc = StringBuilder()
        val allSources = LinkedHashMap<String, GroundingSource>()
        httpClientProvider().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("OpenAI SSE HTTP ${resp.code}: ${resp.body?.string()?.take(240)}")
            val source = resp.body?.source() ?: throw IOException("Missing SSE body")
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val dataStr = line.substring(5).trim()
                if (dataStr.isEmpty() || dataStr == "[DONE]") continue
                val evt = runCatching { MiniJson.parseObject(dataStr) }.getOrNull() ?: continue
                when (evt["type"]?.toString()) {
                    "response.output_text.delta" -> {
                        val delta = evt["delta"]?.toString().orEmpty()
                        if (delta.isNotEmpty()) { textAcc.append(delta); emitEvent(LlmStreamEvent.TextDelta(delta)) }
                    }
                    "response.reasoning_summary_text.delta", "response.reasoning.delta" -> {
                        val delta = evt["delta"]?.toString().orEmpty()
                        if (delta.isNotEmpty()) { reasoningAcc.append(delta); emitEvent(LlmStreamEvent.ReasoningDelta(delta)) }
                    }
                    "response.output_text.annotation.added" -> {
                        val ann = evt["annotation"] as? Map<String, Any?>
                        if (ann?.get("type")?.toString() == "url_citation") {
                            val uri = ann["url"]?.toString()?.trim().orEmpty()
                            val title = ann["title"]?.toString()?.trim().orEmpty().ifBlank { uri }
                            if (uri.isNotEmpty()) {
                                allSources[uri] = GroundingSource(title, uri)
                                emitEvent(LlmStreamEvent.Grounding(allSources.values.toList(), emptyList()))
                            }
                        }
                    }
                    "response.completed" -> {
                        val rObj = evt["response"] as? Map<String, Any?>
                        if (rObj != null) {
                            val (t, r, srcs) = extractOpenAiResponsesOutputWithSources(rObj)
                            if (textAcc.isEmpty() && t.isNotEmpty()) {
                                textAcc.append(t)
                                emitEvent(LlmStreamEvent.TextDelta(t))
                            }
                            if (r.isNotEmpty() && reasoningAcc.isEmpty()) reasoningAcc.append(r)
                            if (srcs.isNotEmpty()) {
                                srcs.forEach { allSources[it.uri] = it }
                                emitEvent(LlmStreamEvent.Grounding(allSources.values.toList(), emptyList()))
                            }
                        }
                    }
                }
            }
        }
        val srcList = allSources.values.toList()
        val modelLabel = if (decision.useWebSearch || srcList.isNotEmpty()) "${decision.model} + Search" else decision.model
        emitEvent(LlmStreamEvent.Completed(textAcc.toString().trim(), reasoningAcc.toString().trim(), modelLabel, srcList))
    }

    private fun buildGeminiPayload(
        decision: RouteDecision,
        developerPrompt: String,
        turns: List<LlmTurnMessage>,
        jsonMode: Boolean
    ): Map<String, Any?> {
        val contents = turns.map { t ->
            val parts = ArrayList<Map<String, Any?>>()
            if (t.content.isNotBlank()) parts.add(linkedMapOf("text" to t.content))
            for (img in t.images) {
                parts.add(linkedMapOf("inlineData" to linkedMapOf("mimeType" to img.mimeType, "data" to img.base64Data)))
            }
            linkedMapOf("role" to if (t.role == "assistant") "model" else "user", "parts" to parts)
        }
        val genCfg = linkedMapOf<String, Any?>("temperature" to if (jsonMode) 0.2 else 0.4)
        if (jsonMode) genCfg["responseMimeType"] = "application/json"
        val root = linkedMapOf<String, Any?>("contents" to contents, "generationConfig" to genCfg)
        if (developerPrompt.isNotBlank()) {
            root["systemInstruction"] = linkedMapOf("parts" to listOf(linkedMapOf("text" to developerPrompt)))
        }
        if (decision.useGeminiSearchGrounding && !jsonMode) {
            root["tools"] = listOf(linkedMapOf("google_search" to emptyMap<String, Any>()))
        }
        return root
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractGeminiCandidate(parsed: Map<String, Any?>): Triple<String, String, List<GroundingSource>> {
        val first = (parsed["candidates"] as? List<*>)?.firstOrNull() as? Map<String, Any?> ?: return Triple("", "", emptyList())
        val parts = (first["content"] as? Map<String, Any?>)?.get("parts") as? List<*> ?: emptyList<Any?>()
        val textSb = StringBuilder()
        val thoughtSb = StringBuilder()
        for (p in parts) {
            val pm = p as? Map<String, Any?> ?: continue
            val t = pm["text"]?.toString().orEmpty()
            if (pm["thought"] == true) thoughtSb.append(t) else textSb.append(t)
        }
        val sources = ArrayList<GroundingSource>()
        val seen = HashSet<String>()
        val chunks = (first["groundingMetadata"] as? Map<String, Any?>)?.get("groundingChunks") as? List<*> ?: emptyList<Any?>()
        for (ch in chunks) {
            val web = (ch as? Map<String, Any?>)?.get("web") as? Map<String, Any?> ?: continue
            val title = web["title"]?.toString()?.trim().orEmpty()
            val uri = web["uri"]?.toString()?.trim().orEmpty()
            if (title.isNotEmpty() && uri.isNotEmpty() && seen.add(uri)) sources.add(GroundingSource(title, uri))
        }
        return Triple(textSb.toString(), thoughtSb.toString(), sources)
    }

    private fun callGeminiNativeSync(
        decision: RouteDecision,
        developerPrompt: String,
        turns: List<LlmTurnMessage>,
        jsonMode: Boolean
    ): LlmStreamEvent.Completed {
        val base = decision.provider.baseUrl.trimEnd('/').removeSuffix("/openai")
        val cleanModel = decision.model.removePrefix("models/")
        val req = Request.Builder()
            .url("$base/models/$cleanModel:generateContent")
            .header("x-goog-api-key", decision.provider.apiKey)
            .header("Content-Type", "application/json")
            .post(MiniJson.stringify(buildGeminiPayload(decision, developerPrompt, turns, jsonMode)).toRequestBody(jsonMediaType))
            .build()
        httpClientProvider().newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("Gemini HTTP ${resp.code} ($cleanModel): ${raw.take(280)}")
            val (text, thoughts, sources) = extractGeminiCandidate(MiniJson.parseObject(raw))
            if (text.isBlank()) throw IOException("Empty Gemini response ($cleanModel)")
            return LlmStreamEvent.Completed(text.trim(), thoughts.trim(), if (sources.isNotEmpty()) "$cleanModel + Search" else cleanModel, sources)
        }
    }

    private suspend fun streamGeminiNative(
        decision: RouteDecision,
        developerPrompt: String,
        turns: List<LlmTurnMessage>,
        emitEvent: suspend (LlmStreamEvent) -> Unit
    ) {
        val base = decision.provider.baseUrl.trimEnd('/').removeSuffix("/openai")
        val cleanModel = decision.model.removePrefix("models/")
        val req = Request.Builder()
            .url("$base/models/$cleanModel:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", decision.provider.apiKey)
            .header("Accept", "text/event-stream")
            .header("Content-Type", "application/json")
            .post(MiniJson.stringify(buildGeminiPayload(decision, developerPrompt, turns, false)).toRequestBody(jsonMediaType))
            .build()
        val textAcc = StringBuilder()
        val thoughtAcc = StringBuilder()
        val allSources = LinkedHashMap<String, GroundingSource>()
        httpClientProvider().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Gemini SSE HTTP ${resp.code}: ${resp.body?.string()?.take(240)}")
            val source = resp.body?.source() ?: throw IOException("Missing SSE body")
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val dataStr = line.substring(5).trim()
                if (dataStr.isEmpty() || dataStr == "[DONE]") continue
                val evt = runCatching { MiniJson.parseObject(dataStr) }.getOrNull() ?: continue
                val (tDelta, rDelta, sources) = extractGeminiCandidate(evt)
                if (rDelta.isNotEmpty()) { thoughtAcc.append(rDelta); emitEvent(LlmStreamEvent.ReasoningDelta(rDelta)) }
                if (tDelta.isNotEmpty()) { textAcc.append(tDelta); emitEvent(LlmStreamEvent.TextDelta(tDelta)) }
                if (sources.isNotEmpty()) {
                    sources.forEach { allSources[it.uri] = it }
                    emitEvent(LlmStreamEvent.Grounding(allSources.values.toList(), emptyList()))
                }
            }
        }
        val srcList = allSources.values.toList()
        emitEvent(LlmStreamEvent.Completed(textAcc.toString().trim(), thoughtAcc.toString().trim(), if (srcList.isNotEmpty()) "$cleanModel + Search" else cleanModel, srcList))
    }

    @Suppress("UNCHECKED_CAST")
    private fun callOpenAiChatSync(
        decision: RouteDecision,
        developerPrompt: String,
        turns: List<LlmTurnMessage>,
        jsonMode: Boolean
    ): LlmStreamEvent.Completed {
        val url = "${decision.provider.baseUrl.trimEnd('/')}/chat/completions"
        val messages = ArrayList<Map<String, Any?>>(turns.size + 1)
        if (developerPrompt.isNotBlank()) messages.add(linkedMapOf("role" to "system", "content" to developerPrompt))
        for (t in turns) messages.add(linkedMapOf("role" to t.role, "content" to t.content))
        val payload = linkedMapOf<String, Any?>("model" to decision.model, "temperature" to 0.2, "messages" to messages)
        if (jsonMode) payload["response_format"] = linkedMapOf("type" to "json_object")
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${decision.provider.apiKey}")
            .header("Content-Type", "application/json")
            .post(MiniJson.stringify(payload).toRequestBody(jsonMediaType))
            .build()
        httpClientProvider().newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("ChatCompletions HTTP ${resp.code}: ${raw.take(240)}")
            val parsed = MiniJson.parseObject(raw)
            val msg = ((parsed["choices"] as? List<*>)?.firstOrNull() as? Map<String, Any?>)?.get("message") as? Map<String, Any?>
            val content = msg?.get("content")?.toString().orEmpty()
            val reasoning = msg?.get("reasoning_content")?.toString().orEmpty()
            if (content.isBlank()) throw IOException("Empty ChatCompletions content")
            return LlmStreamEvent.Completed(content.trim(), reasoning.trim(), decision.model)
        }
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun fetchAvailableModels(provider: ProviderEndpointConfig): List<String> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${provider.baseUrl.trimEnd('/')}/models")
            .header("Authorization", "Bearer ${provider.apiKey}")
            .get().build()
        httpClientProvider().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext emptyList()
            val data = MiniJson.parseObject(resp.body?.string().orEmpty())["data"] as? List<*> ?: return@withContext emptyList()
            data.mapNotNull { (it as? Map<String, Any?>)?.get("id")?.toString()?.removePrefix("models/") }
        }
    }
}
