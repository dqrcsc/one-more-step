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

data class ProposedPhoneToolAction(
    val tool: String, // "launch_app" | "phone_call" | "send_sms" | "open_in_browser" | "set_volume" | "clipboard_write" | "navigate_map" | "open_system_settings"
    val title: String,
    val target: String = "",
    val extra: String = "",
    val detail: String = "",
    val requiresConfirm: Boolean = false
)

data class MemoryEvolutionResult(
    val workingMemory: List<String>,
    val profileAdditions: List<String> = emptyList(),
    val implicitCommitments: List<Pair<String, String>> = emptyList()
)

data class ParsedAssistantActions(
    val cleanMarkdown: String,
    val contextSearchQuery: String? = null,
    val readCalendarRequested: Boolean = false,
    val calendarActions: List<ProposedCalendarAction> = emptyList(),
    val alarmActions: List<ProposedAlarmAction> = emptyList(),
    val phoneToolActions: List<ProposedPhoneToolAction> = emptyList()
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

    /**
     * Dual-speed continuous memory evolution distiller:
     * - Fast layer (`working_memory`): updates active 1-2 week focus items.
     * - Slow layer (`profile_additions`): conservatively extracts durable user traits, preferences,
     *   communication styles, roles, or recurring habits (0-2 high-confidence items max).
     * - Open loops (`implicit_commitments`): captures unclosed promises/todos mentioned naturally.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun distillMemoryEvolution(
        recentDialogueSummary: String,
        existingWorkingMemory: List<String>,
        existingProfileMemories: List<String>
    ): MemoryEvolutionResult {
        if (recentDialogueSummary.isBlank()) {
            return MemoryEvolutionResult(workingMemory = existingWorkingMemory)
        }
        val systemPrompt = """
            你是用户的随身秘书持续记忆演化引擎（Dual-Speed Living Memory Distiller）。
            请审阅最近的手机对话，缓慢、克制、高质量地演化三层记忆：
            1. `working_memory`（近期焦点，最多 8 条）：合并保留已有焦点，更新用户近期正在推进的项目、研究主题或阅读关注；忽略查天气/定闹钟/寒暄。
            2. `profile_additions`（长期稳定画像增量，0~2 条，宁缺毋滥）：仅提取跨月仍成立的稳定事实、工作习惯、表达偏好、技术栈、兴趣领域或重要人际称呼（如“偏好直接先给结论”“深度关注 AI Agent 记忆与本体工程”“习惯通勤时听语音总结”）。若没有新发现或已在已有画像中存在，必须输出空数组 `[]`！
            3. `implicit_commitments`（隐性未闭环事项，0~2 条）：仅提取用户明确流露要去做、尚未完成的真实待办（格式 `{"title":"精炼事项","due":"时间提示或空"}`）；若无则输出 `[]`。
            严格只输出 JSON：
            {"working_memory":["..."],"profile_additions":["..."],"implicit_commitments":[{"title":"...","due":""}]}
        """.trimIndent()
        val userInput = buildString {
            appendLine("【已有长期画像】")
            if (existingProfileMemories.isEmpty()) appendLine("（暂无）")
            else existingProfileMemories.forEach { appendLine("- $it") }
            appendLine("\n【已有近期焦点】")
            if (existingWorkingMemory.isEmpty()) appendLine("（暂无）")
            else existingWorkingMemory.forEach { appendLine("- $it") }
            appendLine("\n【最近对话片段】")
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

        val wm = (parsed["working_memory"] as? List<*>)
            ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            ?.take(8)
            ?.takeIf { it.isNotEmpty() }
            ?: existingWorkingMemory
        val profileAdds = (parsed["profile_additions"] as? List<*>)
            ?.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.length >= 4 } }
            ?.take(2)
            ?: emptyList()
        val commitments = (parsed["implicit_commitments"] as? List<*>)
            ?.mapNotNull { item ->
                val m = item as? Map<*, *> ?: return@mapNotNull null
                val title = m["title"]?.toString()?.trim().orEmpty()
                val due = m["due"]?.toString()?.trim().orEmpty()
                if (title.length >= 3) title to due else null
            }
            ?.take(2)
            ?: emptyList()

        return MemoryEvolutionResult(
            workingMemory = wm,
            profileAdditions = profileAdds,
            implicitCommitments = commitments
        )
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
                appendLine("你是「青简·咫尺 v3.4」随身秘书与超级移动工作台，运行在用户的小米手机端，拥有全机原生工具调用能力、内嵌网页应用舱，并与 Mac 个人知识库及 Codex/Antigravity 双脑协同。")
                appendLine("当前本地时间：$nowFormatted (epochMs=$nowEpochMs)。")
                appendLine("回答准则：")
                appendLine("1. 简洁直接、信息密度高、排版清晰；像一位可靠懂你的 Chief of Staff 随身秘书，能直接调用手机工具完成的事尽量给出可执行工具卡片。")
                appendLine("2. 【按需检索个人上下文】：当且仅当用户提到「我的项目/我的知识库/书库/我们之前聊过/尺素文章/我的观点」，而当前尚未提供背景时，单独输出：")
                appendLine("```oms-action")
                appendLine("{\"tool\":\"search_my_context\",\"query\":\"精炼检索关键词\"}")
                appendLine("```")
                appendLine("3. 【读取手机真实日程】：当用户询问「我今天/明天有什么安排」「帮我找空闲时间」时，单独输出（系统会自动读取真实日历并喂回给你再作答）：")
                appendLine("```oms-action")
                appendLine("{\"tool\":\"read_today_calendar\"}")
                appendLine("```")
                appendLine("4. 【全机手机工具与动作卡片】：当用户要求执行手机操作时，简短回应并在末尾附带 `oms-action` JSON 块（手机会自动渲染可一键执行/安全确认卡）：")
                appendLine("- 写入日历：`{\"tool\":\"propose_calendar\",\"title\":\"项目复盘\",\"startEpochMs\":${nowEpochMs + 3600_000L},\"durationMinutes\":60,\"location\":\"\",\"description\":\"\"}`")
                appendLine("- 设置闹钟：`{\"tool\":\"propose_alarm\",\"hour\":7,\"minute\":30,\"label\":\"起床晨跑\",\"days\":[1,2,3,4,5]}`")
                appendLine("- 打开任意 App：`{\"tool\":\"launch_app\",\"app\":\"微信\"}`（支持微信/飞书/钉钉/高德地图/小红书/淘宝/抖音/微信读书/设置等任意已安装 App）")
                appendLine("- 拨打电话（需用户点击确认）：`{\"tool\":\"phone_call\",\"target\":\"老婆或号码\",\"speaker\":true}`")
                appendLine("- 起草短信（需用户点击确认）：`{\"tool\":\"send_sms\",\"target\":\"联系人或号码\",\"body\":\"短信正文\"}`")
                appendLine("- 在内嵌应用舱打开网页/H5 工具：`{\"tool\":\"open_in_browser\",\"url\":\"https://github.com\",\"title\":\"GitHub\"}`")
                appendLine("- 地图导航：`{\"tool\":\"navigate_map\",\"destination\":\"上海虹桥火车站\"}`")
                appendLine("- 调节手机音量：`{\"tool\":\"set_volume\",\"stream\":\"music\",\"level\":50}`（或 `\"action\":\"mute\"|\"up\"|\"down\"`）")
                appendLine("- 写入手机剪贴板：`{\"tool\":\"clipboard_write\",\"text\":\"要复制的内容\"}`")
                appendLine("- 打开系统设置页：`{\"tool\":\"open_system_settings\",\"section\":\"wifi|bluetooth|display|battery|sound|apps|main\"}`")
                if (webSearchEnabled) {
                    appendLine("5. 【实时联网搜索已开启】：遇到天气、新闻、股价、最新发布或时效性问题，请直接使用内置搜索工具作答。")
                }
                if (deepThinking) {
                    appendLine("6. 当前已开启「深度思考」旗舰推理模式，请充分推演边界条件与权衡取舍后给出结论。")
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        fun parseAssistantActions(rawText: String): ParsedAssistantActions {
            var contextQuery: String? = null
            var readCalendar = false
            val calendars = mutableListOf<ProposedCalendarAction>()
            val alarms = mutableListOf<ProposedAlarmAction>()
            val phoneTools = mutableListOf<ProposedPhoneToolAction>()

            for (match in ACTION_BLOCK_REGEX.findAll(rawText)) {
                val jsonBody = match.groupValues[1].trim()
                val obj = runCatching { MiniJson.parseObject(jsonBody) }.getOrNull() ?: continue
                when (val tool = obj["tool"]?.toString()?.trim().orEmpty()) {
                    "search_my_context" -> {
                        val q = obj["query"]?.toString()?.trim().orEmpty()
                        if (q.isNotEmpty()) contextQuery = q
                    }
                    "read_today_calendar" -> {
                        readCalendar = true
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
                    "launch_app" -> {
                        val appName = obj["app"]?.toString()?.trim().orEmpty()
                        if (appName.isNotEmpty()) {
                            phoneTools.add(
                                ProposedPhoneToolAction(
                                    tool = tool,
                                    title = "打开应用：$appName",
                                    target = appName,
                                    detail = "调用手机系统启动「$appName」",
                                    requiresConfirm = false
                                )
                            )
                        }
                    }
                    "phone_call" -> {
                        val target = obj["target"]?.toString()?.trim().orEmpty()
                        val speaker = obj["speaker"] == true || obj["speaker"]?.toString()?.equals("true", ignoreCase = true) == true
                        if (target.isNotEmpty()) {
                            phoneTools.add(
                                ProposedPhoneToolAction(
                                    tool = tool,
                                    title = "拨打电话：$target",
                                    target = target,
                                    extra = if (speaker) "speaker" else "",
                                    detail = if (speaker) "已开启免提 · 点击确认后拨出" else "点击确认后呼出电话",
                                    requiresConfirm = true
                                )
                            )
                        }
                    }
                    "send_sms" -> {
                        val target = obj["target"]?.toString()?.trim().orEmpty()
                        val body = obj["body"]?.toString()?.trim().orEmpty()
                        if (target.isNotEmpty() || body.isNotEmpty()) {
                            phoneTools.add(
                                ProposedPhoneToolAction(
                                    tool = tool,
                                    title = "起草短信：${target.ifBlank { "收件人" }}",
                                    target = target,
                                    extra = body,
                                    detail = body.take(80).ifBlank { "打开系统短信编辑页" },
                                    requiresConfirm = true
                                )
                            )
                        }
                    }
                    "open_in_browser" -> {
                        val url = obj["url"]?.toString()?.trim().orEmpty()
                        val title = obj["title"]?.toString()?.trim().orEmpty().ifBlank { url }
                        if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
                            phoneTools.add(
                                ProposedPhoneToolAction(
                                    tool = tool,
                                    title = "打开网页：$title",
                                    target = url,
                                    extra = title,
                                    detail = "在青简内置应用舱打开（支持 AI 伴读总结）",
                                    requiresConfirm = false
                                )
                            )
                        }
                    }
                    "navigate_map" -> {
                        val dest = obj["destination"]?.toString()?.trim().orEmpty()
                        if (dest.isNotEmpty()) {
                            phoneTools.add(
                                ProposedPhoneToolAction(
                                    tool = tool,
                                    title = "地图导航：$dest",
                                    target = dest,
                                    detail = "唤起手机地图规划前往「$dest」的路线",
                                    requiresConfirm = false
                                )
                            )
                        }
                    }
                    "set_volume" -> {
                        val stream = obj["stream"]?.toString()?.trim().orEmpty().ifBlank { "music" }
                        val level = (obj["level"] as? Number)?.toInt() ?: obj["level"]?.toString()?.toIntOrNull()
                        val act = obj["action"]?.toString()?.trim().orEmpty()
                        val targetVal = level?.coerceIn(0, 100)?.toString() ?: act.ifBlank { "up" }
                        phoneTools.add(
                            ProposedPhoneToolAction(
                                tool = tool,
                                title = "调节音量（$stream → $targetVal）",
                                target = stream,
                                extra = targetVal,
                                detail = "立即调整手机 $stream 声道音量",
                                requiresConfirm = false
                            )
                        )
                    }
                    "clipboard_write" -> {
                        val text = obj["text"]?.toString().orEmpty()
                        if (text.isNotBlank()) {
                            phoneTools.add(
                                ProposedPhoneToolAction(
                                    tool = tool,
                                    title = "写入手机剪贴板",
                                    target = text,
                                    detail = text.take(80),
                                    requiresConfirm = false
                                )
                            )
                        }
                    }
                    "open_system_settings" -> {
                        val section = obj["section"]?.toString()?.trim().orEmpty().ifBlank { "main" }
                        phoneTools.add(
                            ProposedPhoneToolAction(
                                tool = tool,
                                title = "打开系统设置（$section）",
                                target = section,
                                detail = "直达小米系统对应设置面板",
                                requiresConfirm = false
                            )
                        )
                    }
                }
            }

            val clean = ACTION_BLOCK_REGEX.replace(rawText, "").trim()
            return ParsedAssistantActions(
                cleanMarkdown = clean,
                contextSearchQuery = contextQuery,
                readCalendarRequested = readCalendar,
                calendarActions = calendars,
                alarmActions = alarms,
                phoneToolActions = phoneTools
            )
        }
    }
}
