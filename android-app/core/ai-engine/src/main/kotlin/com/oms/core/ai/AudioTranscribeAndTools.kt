package com.oms.core.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.Base64

data class ProposedCalendarAction(
    val title: String,
    val startEpochMs: Long,
    val durationMinutes: Int = 60,
    val location: String = "",
    val description: String = ""
)

data class ProposedAlarmAction(
    val hour: Int,
    val minute: Int,
    val label: String,
    val daysOfWeek: List<Int> = emptyList()
)

data class ParsedAssistantActions(
    val cleanMarkdown: String,
    val contextSearchQuery: String? = null,
    val calendarActions: List<ProposedCalendarAction> = emptyList(),
    val alarmActions: List<ProposedAlarmAction> = emptyList()
)

/**
 * High-speed bilingual speech-to-text (`gpt-live-transcribe` / `gpt-transcribe` + Gemini audio fallback),
 * lightweight local tool directive protocol (`search_my_context`, `propose_calendar`, `propose_alarm`),
 * and L1 Working Memory distiller.
 */
class CockpitAiServices(
    private val httpClientProvider: () -> OkHttpClient,
    private val llmEngine: DualLlmEngine
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun transcribeAudioFile(
        audioFile: File,
        mimeType: String = "audio/mp4",
        languageHint: String = ""
    ): String = withContext(Dispatchers.IO) {
        require(audioFile.exists() && audioFile.length() > 0L) { "录音文件为空" }
        val cfg = llmEngine.runtimeConfig
        val oai = cfg.openai?.takeIf { it.apiKey.isNotBlank() }
        val gem = cfg.gemini?.takeIf { it.apiKey.isNotBlank() }

        var lastError: Throwable? = null
        if (oai != null) {
            val candidateModels = listOf(
                oai.transcribeModel.ifBlank { AiModelCatalog.OPENAI_LIVE_TRANSCRIBE },
                AiModelCatalog.OPENAI_TRANSCRIBE,
                "gpt-4o-mini-transcribe"
            ).distinct()
            for (model in candidateModels) {
                try {
                    val text = callOpenAiTranscription(oai, audioFile, mimeType, model, languageHint)
                    if (text.isNotBlank()) return@withContext text.trim()
                } catch (err: Throwable) {
                    lastError = err
                }
            }
        }

        if (gem != null) {
            try {
                val text = callGeminiAudioTranscription(gem, audioFile, mimeType)
                if (text.isNotBlank()) return@withContext text.trim()
            } catch (err: Throwable) {
                lastError = err
            }
        }

        throw (lastError ?: IOException("未配置可用的语音转写 API Key"))
    }

    private fun callOpenAiTranscription(
        provider: ProviderEndpointConfig,
        audioFile: File,
        mimeType: String,
        model: String,
        languageHint: String
    ): String {
        val url = "${provider.baseUrl.trimEnd('/')}/audio/transcriptions"
        val multipartBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .addFormDataPart(
                "prompt",
                "以下是中文或中英混排语音输入（可能包含 AI、Codex、Antigravity、尺素、咫尺、OpenAI、Gemini 等术语），请准确转写并添加自然标点。"
            )
            .addFormDataPart(
                "file",
                audioFile.name,
                audioFile.asRequestBody(mimeType.toMediaType())
            )
        if (languageHint.isNotBlank()) {
            multipartBuilder.addFormDataPart("language", languageHint)
        }

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${provider.apiKey}")
            .post(multipartBuilder.build())
            .build()

        httpClientProvider().newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("OpenAI Transcribe HTTP ${resp.code}: ${raw.take(240)}")
            }
            val parsed = MiniJson.parseObject(raw)
            return parsed["text"]?.toString()?.trim().orEmpty()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun callGeminiAudioTranscription(
        provider: ProviderEndpointConfig,
        audioFile: File,
        mimeType: String
    ): String {
        val model = provider.fastModel.ifBlank { AiModelCatalog.GEMINI_FLASH }
        val base = provider.baseUrl.trimEnd('/').let {
            if (it.endsWith("/v1beta") || it.endsWith("/v1")) it else "$it/v1beta"
        }
        val url = "$base/models/$model:generateContent"
        val b64 = Base64.getEncoder().encodeToString(audioFile.readBytes())
        val payload = linkedMapOf(
            "contents" to listOf(
                linkedMapOf(
                    "role" to "user",
                    "parts" to listOf(
                        linkedMapOf(
                            "text" to "请逐字准确转写这段语音内容，保留中英文专业词汇并加上自然标点，只输出转写后的纯文本，不要任何解释。"
                        ),
                        linkedMapOf(
                            "inlineData" to linkedMapOf(
                                "mimeType" to mimeType,
                                "data" to b64
                            )
                        )
                    )
                )
            ),
            "generationConfig" to linkedMapOf("temperature" to 0.1)
        )
        val req = Request.Builder()
            .url(url)
            .header("x-goog-api-key", provider.apiKey)
            .post(MiniJson.stringify(payload).toRequestBody(jsonMediaType))
            .build()

        httpClientProvider().newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("Gemini Audio Transcribe HTTP ${resp.code}: ${raw.take(240)}")
            }
            val parsed = MiniJson.parseObject(raw)
            val candidates = parsed["candidates"] as? List<*> ?: emptyList<Any?>()
            val first = candidates.firstOrNull() as? Map<String, Any?> ?: return ""
            val content = first["content"] as? Map<String, Any?> ?: return ""
            val parts = content["parts"] as? List<*> ?: emptyList<Any?>()
            return parts.mapNotNull { (it as? Map<String, Any?>)?.get("text")?.toString() }
                .joinToString("").trim()
        }
    }

    /**
     * Distills recent substantive mobile conversations into L1 Working Memory bullet points,
     * strictly discarding trivial daily small talk (weather, timers, food, casual greetings).
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun distillWorkingMemory(
        recentDialogueSummary: String,
        existingWorkingMemory: List<String>
    ): List<String> {
        if (recentDialogueSummary.isBlank()) return existingWorkingMemory
        val systemPrompt = """
            你是用户的随身第二大脑记忆蒸馏器（L1 Working Memory Distiller）。
            请审阅用户最近在手机端的对话片段，并与已有近期关注列表合并：
            1. 【严格过滤噪音】：彻底忽略查天气、设闹钟、问菜谱、闲聊寒暄、一次性翻译或琐碎情绪宣泄；
            2. 【精准提炼高价值焦点】：仅提取用户正在推进的技术课题、工程架构思考、阅读研究兴趣、长期计划或偏好决策；
            3. 每条控制在 18~48 字以内，总数不超过 8 条，去重合并旧条目；
            4. 如果本批对话全是日常琐事，原样保留已有条目即可。
            严格只输出 JSON：{"working_memory": ["条目1", "条目2"]}
        """.trimIndent()
        val userInput = buildString {
            appendLine("【已有工作记忆】")
            if (existingWorkingMemory.isEmpty()) appendLine("（暂无）")
            else existingWorkingMemory.forEach { appendLine("- $it") }
            appendLine("\n【新增对话片段】")
            appendLine(recentDialogueSummary.take(8000))
        }
        val completed = llmEngine.completeSync(
            intent = RouteIntent.AUTO,
            developerPrompt = systemPrompt,
            turns = listOf(LlmTurnMessage("user", userInput)),
            jsonMode = true
        )
        val jsonText = MiniJson.extractJsonObject(completed.fullText)
        val parsed = MiniJson.parseObject(jsonText)
        val arr = parsed["working_memory"] as? List<*> ?: return existingWorkingMemory
        val cleaned = arr.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }.take(8)
        return if (cleaned.isNotEmpty()) cleaned else existingWorkingMemory
    }

    companion object {
        private val ACTION_BLOCK_REGEX = Regex(
            """```oms-action\s*(\{[\s\S]*?\})\s*```""",
            RegexOption.IGNORE_CASE
        )

        fun buildCockpitDeveloperPrompt(
            nowEpochMs: Long,
            nowFormatted: String,
            deepThinking: Boolean,
            webSearchEnabled: Boolean = false
        ): String {
            return buildString {
                appendLine("你是「青简·咫尺 v3.1」随身认知座舱助手，运行在用户的小米手机端并与用户的 Mac 个人知识库及 Codex/Antigravity 双脑协同。")
                appendLine("当前本地时间：$nowFormatted (epochMs=$nowEpochMs)。")
                appendLine("回答准则：")
                appendLine("1. 简洁直接、信息密度高、排版清晰（善用小标题、列表、代码块、表格）；日常轻松话题自然回应，技术与研究问题给出扎实深度。")
                appendLine("2. 【按需检索个人上下文】：当且仅当用户提到「我的项目/我的知识库/我们之前聊过/尺素某篇文章/我的观点/结合我的进展」，而当前对话尚未提供相关背景时，你只需单独输出一行：")
                appendLine("```oms-action")
                appendLine("{\"tool\":\"search_my_context\",\"query\":\"精炼检索关键词\"}")
                appendLine("```")
                appendLine("系统会立即把检索到的个人知识库与历史上下文喂回给你，你再给出最终回答。日常通用提问（如查天气、查新闻、技术问答）绝不要调用 search_my_context。")
                appendLine("3. 【原生日程与闹钟卡片】：当用户明确让你安排日程/提醒或定闹钟时，除了用自然语言简短回应，请在回答末尾附带结构化动作块（手机会自动渲染防冲突确认卡片）：")
                appendLine("- 日程示例：")
                appendLine("```oms-action")
                appendLine("{\"tool\":\"propose_calendar\",\"title\":\"项目复盘\",\"startEpochMs\":${nowEpochMs + 3600_000L},\"durationMinutes\":60,\"location\":\"\",\"description\":\"\"}")
                appendLine("```")
                appendLine("- 闹钟示例：")
                appendLine("```oms-action")
                appendLine("{\"tool\":\"propose_alarm\",\"hour\":7,\"minute\":30,\"label\":\"起床晨跑\",\"days\":[1,2,3,4,5]}")
                appendLine("```")
                if (webSearchEnabled) {
                    appendLine("4. 【实时联网搜索已开启】：当前对话已挂载实时网页检索工具（web_search / google_search）。遇到天气、气温、新闻、股价、最新发布或任何时效性事实问题，请直接使用内置搜索工具检索最新信息作答，切勿声称自己无法联网。")
                }
                if (deepThinking) {
                    appendLine("5. 当前已开启「深度思考」旗舰推理模式，请充分推演边界条件、权衡取舍后再给出高水准结论。")
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        fun parseAssistantActions(rawText: String): ParsedAssistantActions {
            var contextQuery: String? = null
            val calendars = mutableListOf<ProposedCalendarAction>()
            val alarms = mutableListOf<ProposedAlarmAction>()

            for (match in ACTION_BLOCK_REGEX.findAll(rawText)) {
                val jsonBody = match.groupValues[1].trim()
                val obj = runCatching { MiniJson.parseObject(jsonBody) }.getOrNull() ?: continue
                when (obj["tool"]?.toString()?.trim()) {
                    "search_my_context" -> {
                        val q = obj["query"]?.toString()?.trim().orEmpty()
                        if (q.isNotEmpty()) contextQuery = q
                    }
                    "propose_calendar" -> {
                        val title = obj["title"]?.toString()?.trim().orEmpty().ifBlank { "AI 日程安排" }
                        val startMs = (obj["startEpochMs"] as? Number)?.toLong()
                            ?: obj["startEpochMs"]?.toString()?.toLongOrNull()
                            ?: (System.currentTimeMillis() + 3600_000L)
                        val dur = (obj["durationMinutes"] as? Number)?.toInt()
                            ?: obj["durationMinutes"]?.toString()?.toIntOrNull()
                            ?: 60
                        calendars.add(
                            ProposedCalendarAction(
                                title = title,
                                startEpochMs = startMs,
                                durationMinutes = dur.coerceIn(5, 1440),
                                location = obj["location"]?.toString().orEmpty(),
                                description = obj["description"]?.toString().orEmpty()
                            )
                        )
                    }
                    "propose_alarm" -> {
                        val hour = ((obj["hour"] as? Number)?.toInt()
                            ?: obj["hour"]?.toString()?.toIntOrNull() ?: 8).coerceIn(0, 23)
                        val minute = ((obj["minute"] as? Number)?.toInt()
                            ?: obj["minute"]?.toString()?.toIntOrNull() ?: 0).coerceIn(0, 59)
                        val label = obj["label"]?.toString()?.trim().orEmpty().ifBlank { "咫尺提醒" }
                        val days = (obj["days"] as? List<*>)?.mapNotNull {
                            (it as? Number)?.toInt() ?: it?.toString()?.toIntOrNull()
                        }?.filter { it in 1..7 } ?: emptyList()
                        alarms.add(ProposedAlarmAction(hour = hour, minute = minute, label = label, daysOfWeek = days))
                    }
                }
            }

            val clean = ACTION_BLOCK_REGEX.replace(rawText, "").trim()
            return ParsedAssistantActions(
                cleanMarkdown = clean,
                contextSearchQuery = contextQuery,
                calendarActions = calendars,
                alarmActions = alarms
            )
        }
    }
}
