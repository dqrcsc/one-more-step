package com.oms.core.ai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.Executors

class AiEngineUnitTest {

    @Test
    fun gpt62PayloadUsesDeveloperRoleFirstAndValidReasoningEffort() {
        val cfg = EdgeAiRuntimeConfig(
            openai = ProviderEndpointConfig(
                providerId = "openai",
                baseUrl = "https://api.openai.com/v1",
                apiKey = "sk-test",
                fastModel = AiModelCatalog.OPENAI_SOL,
                flagshipModel = AiModelCatalog.OPENAI_SOL,
                reasoningModel = AiModelCatalog.OPENAI_ASTRA
            ),
            gemini = ProviderEndpointConfig(
                providerId = "gemini",
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                apiKey = "gm-test",
                fastModel = AiModelCatalog.GEMINI_FLASH,
                flagshipModel = AiModelCatalog.GEMINI_ARGON
            )
        )
        val engine = DualLlmEngine({ OkHttpClient() }, cfg)

        // 1. Daily AUTO routes to gpt-6.2-sol with effort = "low" (never "none")
        val autoDecision = engine.decideRoute(RouteIntent.AUTO, "帮我分析一下状态机设计")
        assertEquals("gpt-6.2-sol", autoDecision.model)
        assertEquals("low", autoDecision.effort.wireValue)
        assertEquals("openai_responses", autoDecision.protocol)

        // 2. Deep thinking routes to gpt-6.2-astra with effort = "high"
        val deepDecision = engine.decideRoute(RouteIntent.DEEP_THINKING, "证明分布式锁安全性")
        assertEquals("gpt-6.2-astra", deepDecision.model)
        assertEquals("high", deepDecision.effort.wireValue)

        // 3. Time-sensitive query in AUTO routes to gemini-3.8-flash + Google Search Grounding
        val searchDecision = engine.decideRoute(RouteIntent.AUTO, "查一下今天最新的汇率")
        assertEquals("gemini-3.8-flash", searchDecision.model)
        assertTrue(searchDecision.useGeminiSearchGrounding)

        // 4. Botanical camera routes to gemini-4-argon + Search Grounding
        val plantDecision = engine.decideRoute(RouteIntent.BOTANICAL_VISION, "这是什么植物", hasImages = true)
        assertEquals("gemini-4-argon", plantDecision.model)
        assertTrue(plantDecision.useGeminiSearchGrounding)

        // 5. Verify Responses API payload places developer prompt at input[0]
        val payload = engine.buildOpenAiResponsesPayload(
            autoDecision,
            "Reusable System Prompt",
            listOf(LlmTurnMessage("user", "Hello")),
            jsonMode = true,
            stream = false
        )
        @Suppress("UNCHECKED_CAST")
        val inputList = payload["input"] as List<Map<String, Any?>>
        assertEquals("developer", inputList[0]["role"])
        assertEquals("Reusable System Prompt", inputList[0]["content"])
        assertEquals("user", inputList[1]["role"])
    }

    @Test
    fun sseStreamingAndWeChatValidationContractRoundTripAgainstMockResponsesServer() = runBlocking {
        val pool = Executors.newCachedThreadPool()
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        pool.execute {
            runCatching {
                while (!server.isClosed) {
                    val sock = server.accept()
                    val buf = ByteArray(16384)
                    val n = sock.getInputStream().read(buf)
                    val reqStr = if (n > 0) String(buf, 0, n, Charsets.UTF_8) else ""
                    if (reqStr.contains("\"stream\":true")) {
                        val sseBody = buildString {
                            append("data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"先核对约束…\"}\n\n")
                            append("data: {\"type\":\"response.output_text.delta\",\"delta\":\"你好，\"}\n\n")
                            append("data: {\"type\":\"response.output_text.delta\",\"delta\":\"世界！\"}\n\n")
                            append("data: [DONE]\n\n")
                        }.toByteArray(Charsets.UTF_8)
                        val hdr = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${sseBody.size}\r\nConnection: close\r\n\r\n"
                        sock.getOutputStream().write(hdr.toByteArray(Charsets.US_ASCII) + sseBody)
                    } else {
                        val triageJson = MiniJson.stringify(
                            linkedMapOf(
                                "english_slug" to "agent_memory_architecture",
                                "triage" to "process_later",
                                "validation_contract" to linkedMapOf(
                                    "metrics" to listOf(
                                        linkedMapOf(
                                            "name" to "相关上下文召回率",
                                            "numerator" to "命中的历史有效决策数",
                                            "denominator" to "测试集总关联问题数",
                                            "direction" to "increase",
                                            "minimum_gain" to linkedMapOf("unit" to "percentage_points", "value" to 10)
                                        )
                                    )
                                ),
                                "wechat_reply" to "💧 脱水干货：文章提出分层记忆。\n💡 观点碰撞：补充按需工具化召回。\n🏢 业务与规划启发：可在咫尺对话模块做 A/B 对照验证。\n{{VALIDATION_CONTRACT}}\n🔍 边界与待验证：依赖摘要质量。\n🚦 分诊与下一步：建议 process_later。",
                                "companion_note_markdown" to "## 💧 脱水干货\n分层记忆架构。\n\n## 💡 观点碰撞\n按需召回。\n\n## 🏢 业务与规划启发\nA/B 验证方案。\n{{VALIDATION_CONTRACT}}\n\n## 🔍 边界与待验证\n需核对延迟。\n\n## 🚦 分诊与下一步\nprocess_later"
                            )
                        )
                        val respPayload = MiniJson.stringify(
                            linkedMapOf(
                                "output" to listOf(
                                    linkedMapOf(
                                        "type" to "message",
                                        "content" to listOf(linkedMapOf("type" to "output_text", "text" to triageJson))
                                    )
                                )
                            )
                        ).toByteArray(Charsets.UTF_8)
                        val hdr = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${respPayload.size}\r\nConnection: close\r\n\r\n"
                        sock.getOutputStream().write(hdr.toByteArray(Charsets.US_ASCII) + respPayload)
                    }
                    sock.getOutputStream().flush()
                    sock.close()
                }
            }
        }

        try {
            val directClient = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
            val cfg = EdgeAiRuntimeConfig(
                openai = ProviderEndpointConfig(
                    providerId = "openai",
                    baseUrl = "http://127.0.0.1:$port/v1",
                    apiKey = "sk-local-test",
                    fastModel = AiModelCatalog.OPENAI_SOL,
                    flagshipModel = AiModelCatalog.OPENAI_SOL,
                    reasoningModel = AiModelCatalog.OPENAI_ASTRA
                )
            )
            val engine = DualLlmEngine({ directClient }, cfg)

            // Test 1: Streaming SSE
            val events = engine.streamTurn(RouteIntent.AUTO, "Dev", listOf(LlmTurnMessage("user", "Hi"))).toList()
            val completed = events.filterIsInstance<LlmStreamEvent.Completed>().single()
            assertEquals("你好，世界！", completed.fullText)
            assertEquals("先核对约束…", completed.fullReasoning)

            // Test 2: Full WeChat article triage + {{VALIDATION_CONTRACT}} expansion
            val triageRes = WeChatArticleAnalyzer.analyzeArticle(
                engine = engine,
                article = EdgeArticleInput(
                    url = "https://mp.weixin.qq.com/s/test123",
                    title = "分层记忆架构实践",
                    author = "测试公众号",
                    markdownBody = "这是一篇关于智能体三级记忆漏斗与上下文治理的技术长文。".repeat(8)
                ),
                userNotes = "",
                capsule = ContextCapsule(nowSummary = "正在设计咫尺 v2.0")
            )
            assertFalse(triageRes.isFallbackNote)
            assertEquals("process_later", triageRes.triage)
            assertTrue(triageRes.wechatReply.contains("相关上下文召回率 = 命中的历史有效决策数 / 测试集总关联问题数"))
            assertTrue(triageRes.wechatReply.contains("提升 ≥ 10 个百分点"))
            assertFalse(triageRes.wechatReply.contains("{{VALIDATION_CONTRACT}}"))
        } finally {
            runCatching { server.close() }
            pool.shutdownNow()
        }
    }

    @Test
    fun cockpitToolsBotanicalGlassAndRealtimeProtocolsParseCleanly() {
        // 1. Cockpit tool directive extraction (search_my_context + calendar + alarm)
        val rawAssistant = """
            好的，我已为您准备好明天上午的日程和早晨闹钟：
            ```oms-action
            {"tool":"propose_calendar","title":"架构评审","startEpochMs":1795136400000,"durationMinutes":45,"location":"会议室A"}
            ```
            ```oms-action
            {"tool":"propose_alarm","hour":7,"minute":20,"label":"早起散步","days":[1,2,3,4,5]}
            ```
        """.trimIndent()
        val parsedActions = CockpitAiServices.parseAssistantActions(rawAssistant)
        assertEquals("好的，我已为您准备好明天上午的日程和早晨闹钟：", parsedActions.cleanMarkdown)
        assertEquals(1, parsedActions.calendarActions.size)
        assertEquals("架构评审", parsedActions.calendarActions[0].title)
        assertEquals(45, parsedActions.calendarActions[0].durationMinutes)
        assertEquals(1, parsedActions.alarmActions.size)
        assertEquals(7, parsedActions.alarmActions[0].hour)
        assertEquals(20, parsedActions.alarmActions[0].minute)
        assertEquals(listOf(1, 2, 3, 4, 5), parsedActions.alarmActions[0].daysOfWeek)

        val ctxDirective = CockpitAiServices.parseAssistantActions(
            "```oms-action\n{\"tool\":\"search_my_context\",\"query\":\"本体知识工程\"}\n```"
        )
        assertEquals("本体知识工程", ctxDirective.contextSearchQuery)

        // 2. Botanical Glass specimen card parsing + Vault Markdown rendering
        val engine = DualLlmEngine({ OkHttpClient() })
        val botanical = BotanicalGlassAnalyzer(engine)
        val specimen = botanical.parseSpecimenResponse(
            rawText = """
                ```json
                {
                  "chinese_name": "染井吉野樱",
                  "common_aliases": "东京樱花",
                  "latin_name": "Prunus × yedoensis",
                  "family_genus": "蔷薇科 李属",
                  "category": "花卉",
                  "confidence_percent": 96,
                  "one_line_summary": "先花后叶、花瓣先端具明显缺刻的春季代表性樱花。",
                  "key_morphology": ["花瓣5枚白色微粉，顶端有清晰凹缺", "花梗与萼筒被疏柔毛", "伞形总状花序簇生"],
                  "lookalike_comparison": "与垂丝海棠相比，樱花花瓣尖端有小缺口且树皮具横纹皮孔；梅花则无长花梗。",
                  "bloom_and_habitat": "3月下旬至4月上旬盛放，喜阳光充足排水良好环境。",
                  "safety_or_care_note": "忌积水涝根。"
                }
                ```
            """.trimIndent(),
            specimenId = "spec_1",
            modelUsed = AiModelCatalog.GEMINI_ARGON,
            sources = listOf(GroundingSource("iPlant 植物智", "https://www.iplant.cn/info/Prunus")),
            capturedAtIso = "2026-11-20T10:15:00+08:00"
        )
        assertEquals("染井吉野樱", specimen.chineseName)
        assertEquals("Prunus × yedoensis", specimen.latinName)
        assertEquals(96, specimen.confidencePercent)
        assertEquals(3, specimen.keyMorphology.size)
        val md = specimen.toVaultMarkdown("cherry_20261120.jpg")
        assertTrue(md.contains("latin_name: \"Prunus × yedoensis\""))
        assertTrue(md.contains("![[cherry_20261120.jpg]]"))
        assertTrue(md.contains("## ⚖️ 易混物种辨析"))

        // 3. Gemini 3.8 Live & OpenAI Realtime 2.1 wire event parsing + PCM resampling
        val gemSetup = RealtimeVoiceSessionEngine.buildGeminiLiveSetupPayload(
            AiModelCatalog.GEMINI_LIVE,
            "Glass Prompt",
            "Puck"
        )
        val setupInner = gemSetup["setup"] as Map<*, *>
        assertEquals("models/gemini-3.8-live", setupInner["model"])

        var readyCalled = false
        var interruptedCalled = false
        var audioBytesCount = 0
        val transcripts = mutableListOf<String>()
        val listener = object : RealtimeSessionListener {
            override fun onSessionReady(mode: RealtimeBackendMode, modelId: String) { readyCalled = true }
            override fun onPcm24kAudioOut(pcm24kMonoLe: ByteArray) { audioBytesCount += pcm24kMonoLe.size }
            override fun onUserTranscript(text: String, isFinal: Boolean) { transcripts.add("U:$text") }
            override fun onAssistantTranscriptDelta(delta: String) { transcripts.add("A:$delta") }
            override fun onTurnComplete() {}
            override fun onInterruptedBargeIn() { interruptedCalled = true }
            override fun onError(message: String) { fail(message) }
            override fun onClosed() {}
        }
        RealtimeVoiceSessionEngine.parseGeminiLiveServerEvent(
            """{"setupComplete":{}}""",
            AiModelCatalog.GEMINI_LIVE,
            onSetupAck = {},
            listener = listener
        )
        assertTrue(readyCalled)
        RealtimeVoiceSessionEngine.parseGeminiLiveServerEvent(
            """{"serverContent":{"interrupted":true,"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AAAA"}}]},"outputTranscription":{"text":"这是银杏叶"}}}""",
            AiModelCatalog.GEMINI_LIVE,
            onSetupAck = {},
            listener = listener
        )
        assertTrue(interruptedCalled)
        assertEquals(3, audioBytesCount)
        assertTrue(transcripts.contains("A:这是银杏叶"))

        val pcm16k = ByteArray(320) // 160 samples (10ms at 16kHz)
        val pcm24k = PcmAudioCodec.resamplePcm16Mono(pcm16k, 16000, 24000)
        assertEquals(480, pcm24k.size) // 240 samples (10ms at 24kHz)

        // 4. Live Model Catalog Classifier & Explicit Role Override Routing
        val futureGpt = ModelCatalogRegistry.classifyDiscoveredModel("openai", "gpt-7.0-astra")
        assertNotNull(futureGpt)
        assertTrue(ModelRoleCategory.DEEP_REASONING in futureGpt!!.categories)
        assertTrue(ModelRoleCategory.CHAT_PRIMARY in futureGpt.categories)

        val futureImg = ModelCatalogRegistry.classifyDiscoveredModel("openai", "gpt-image-3.0-sunburst")
        assertNotNull(futureImg)
        assertTrue(ModelRoleCategory.IMAGE_GENERATION in futureImg!!.categories)

        val futureMusic = ModelCatalogRegistry.classifyDiscoveredModel("gemini", "models/lyria-4.0-pro", "Lyria 4 Pro")
        assertNotNull(futureMusic)
        assertTrue(ModelRoleCategory.MUSIC_GENERATION in futureMusic!!.categories)

        val liveTranscribe = ModelCatalogRegistry.classifyDiscoveredModel("gemini", "models/gemini-3.5-transcribe-live", "Gemini 3.5 Transcribe Live")
        assertNotNull(liveTranscribe)
        assertTrue(ModelRoleCategory.SPEECH_TRANSCRIBE in liveTranscribe!!.categories)

        val friendlyGeoErr = ModelCatalogRegistry.formatProviderHttpError(
            "Gemini",
            400,
            """{"error":{"code":400,"message":"User location is not supported for the API use."}}"""
        )
        assertTrue(friendlyGeoErr.contains("地区不受 Gemini 支持"))

        // Deprecated & embedding models are strictly filtered out
        assertNull(ModelCatalogRegistry.classifyDiscoveredModel("openai", "text-embedding-3-small"))
        assertNull(ModelCatalogRegistry.classifyDiscoveredModel("gemini", "models/gemini-1.5-pro"))
    }
}
