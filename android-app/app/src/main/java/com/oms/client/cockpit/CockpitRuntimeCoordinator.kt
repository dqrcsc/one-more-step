package com.oms.client.cockpit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Base64
import com.oms.client.data.StreamRepository
import com.oms.client.edge.EdgeSecureStore
import com.oms.client.transport.OmsFrameProtocol
import com.oms.client.transport.OmsTransportClient
import com.oms.core.ai.BotanicalGlassAnalyzer
import com.oms.core.ai.CockpitAiServices
import com.oms.core.ai.CreativeMediaEngine
import com.oms.core.ai.DualLlmEngine
import com.oms.core.ai.FieldGuideSpecimenCard
import com.oms.core.ai.GroundingSource
import com.oms.core.ai.LlmImageAttachment
import com.oms.core.ai.LlmStreamEvent
import com.oms.core.ai.LlmTurnMessage
import com.oms.core.ai.ModelCatalogItem
import com.oms.core.ai.ModelCatalogRefreshResult
import com.oms.core.ai.ModelCatalogRegistry
import com.oms.core.ai.ModelRoleCategory
import com.oms.core.ai.RouteIntent
import com.oms.core.proxy.AiConnectivityReport
import com.oms.core.proxy.OmsProxyManager
import com.oms.core.proxy.ProxyNodeStatusView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

data class StreamingTurnState(
    val isStreaming: Boolean = false,
    val threadId: String = "",
    val messageId: String = "",
    val reasoningText: String = "",
    val markdownText: String = "",
    val sources: List<GroundingSource> = emptyList(),
    val modelLabel: String = "",
    val startedAtMs: Long = 0L,
    val statusHint: String = ""
)

data class RemoteAgentSessionUi(
    val sessionId: String,
    val backend: String, // "codex" | "agy"
    val origin: String,  // "desktop" | "mobile"
    val title: String,
    val workspacePath: String,
    val projectDisplayName: String = "",
    val status: String,  // "running" | "idle" | "completed" | "failed"
    val lastSummary: String,
    val updatedAtMs: Long,
    val canContinue: Boolean
)

data class RemoteAgentQuotaWindowUi(
    val key: String,
    val label: String,
    val usedPercent: Int,
    val remainingPercent: Int,
    val windowMinutes: Int?,
    val resetsAtMs: Long?
)

data class RemoteAgentBackendQuotaUi(
    val backend: String,
    val available: Boolean,
    val planLabel: String,
    val activeModel: String,
    val windows: List<RemoteAgentQuotaWindowUi>,
    val creditsSummary: String,
    val statusNote: String,
    val capturedAtMs: Long
)

data class RemoteAgentQuotasUi(
    val codex: RemoteAgentBackendQuotaUi,
    val agy: RemoteAgentBackendQuotaUi,
    val fetchedAtMs: Long
)

data class RemoteAgentStepUi(
    val stepIndex: Int,
    val kind: String,
    val title: String,
    val detail: String,
    val timestampMs: Long
)

data class RemoteAgentProjectUi(
    val projectPath: String,
    val displayName: String,
    val group: String,
    val sessionCount: Int,
    val runningCount: Int,
    val lastActiveAtMs: Long
)

/**
 * Application-scoped coordinator for 咫尺 v2.0:
 *  - Unlock TLS Prewarmer (`<800ms` TTFT)
 *  - Offline-resilient `BackgroundTurnExecutor` (`24ms` frame-aligned streaming + HyperOS haptics)
 *  - 3-Tier Memory Sync (`chat.sync.batch`, L1 distillation, L2 Vault promotion)
 *  - Mac Heavy-Brain Hub (`Codex` & `Antigravity` monitor + dispatch + `resume` continuation)
 */
class CockpitRuntimeCoordinator(
    private val appContext: Context,
    val chatRepo: ChatMemoryRepository,
    private val streamRepo: StreamRepository,
    private val edgeStore: EdgeSecureStore,
    private val proxyManager: OmsProxyManager,
    private val llmEngine: DualLlmEngine,
    private val transportClient: OmsTransportClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val aiServices = CockpitAiServices({ proxyManager.getOrCreateOkHttpClient() }, llmEngine)
    val botanicalAnalyzer = BotanicalGlassAnalyzer(llmEngine)

    private val _streamingFlow = MutableStateFlow(StreamingTurnState())
    val streamingFlow: StateFlow<StreamingTurnState> = _streamingFlow

    private val _agentSessionsFlow = MutableStateFlow<List<RemoteAgentSessionUi>>(emptyList())
    val agentSessionsFlow: StateFlow<List<RemoteAgentSessionUi>> = _agentSessionsFlow

    private val _agentProjectsFlow = MutableStateFlow<List<RemoteAgentProjectUi>>(emptyList())
    val agentProjectsFlow: StateFlow<List<RemoteAgentProjectUi>> = _agentProjectsFlow

    private val _agentQuotasFlow = MutableStateFlow<RemoteAgentQuotasUi?>(null)
    val agentQuotasFlow: StateFlow<RemoteAgentQuotasUi?> = _agentQuotasFlow

    private val _selectedAgentStepsFlow = MutableStateFlow<List<RemoteAgentStepUi>>(emptyList())
    val selectedAgentStepsFlow: StateFlow<List<RemoteAgentStepUi>> = _selectedAgentStepsFlow

    private val _selectedAgentSessionFlow = MutableStateFlow<RemoteAgentSessionUi?>(null)
    val selectedAgentSessionFlow: StateFlow<RemoteAgentSessionUi?> = _selectedAgentSessionFlow

    private val _isLoadingAgentDetailFlow = MutableStateFlow(false)
    val isLoadingAgentDetailFlow: StateFlow<Boolean> = _isLoadingAgentDetailFlow

    // Chisu (尺素) Mac-Synced Article Library + Dual-Doc (Report / Summary / Original Source) Cache
    private val _chisuArticlesFlow = MutableStateFlow<List<ChisuArticleItemUi>>(emptyList())
    val chisuArticlesFlow: StateFlow<List<ChisuArticleItemUi>> = _chisuArticlesFlow

    private val _isRefreshingChisuFlow = MutableStateFlow(false)
    val isRefreshingChisuFlow: StateFlow<Boolean> = _isRefreshingChisuFlow

    private val _activeChisuDetailFlow = MutableStateFlow<ChisuArticleDetailUi?>(null)
    val activeChisuDetailFlow: StateFlow<ChisuArticleDetailUi?> = _activeChisuDetailFlow

    private val _isLoadingChisuDetailFlow = MutableStateFlow(false)
    val isLoadingChisuDetailFlow: StateFlow<Boolean> = _isLoadingChisuDetailFlow

    private val _chisuDetailErrorFlow = MutableStateFlow<String?>(null)
    val chisuDetailErrorFlow: StateFlow<String?> = _chisuDetailErrorFlow

    // Proxy & Model Studio Reactive Flows
    private val _proxyNodesFlow = MutableStateFlow<List<ProxyNodeStatusView>>(emptyList())
    val proxyNodesFlow: StateFlow<List<ProxyNodeStatusView>> = _proxyNodesFlow

    private val _proxyRoutingModeFlow = MutableStateFlow("rule")
    val proxyRoutingModeFlow: StateFlow<String> = _proxyRoutingModeFlow

    private val _proxySummaryFlow = MutableStateFlow("")
    val proxySummaryFlow: StateFlow<String> = _proxySummaryFlow

    private val _aiConnectivityFlow = MutableStateFlow<AiConnectivityReport?>(null)
    val aiConnectivityFlow: StateFlow<AiConnectivityReport?> = _aiConnectivityFlow

    private val _modelCatalogFlow = MutableStateFlow<List<ModelCatalogItem>>(ModelCatalogRegistry.BUILTIN_PRESETS)
    val modelCatalogFlow: StateFlow<List<ModelCatalogItem>> = _modelCatalogFlow

    private val _modelRefreshedAtFlow = MutableStateFlow(0L)
    val modelRefreshedAtFlow: StateFlow<Long> = _modelRefreshedAtFlow

    private val _roleSelectionsFlow = MutableStateFlow(ModelCatalogRegistry.defaultRoleSelections())
    val roleSelectionsFlow: StateFlow<Map<ModelRoleCategory, String>> = _roleSelectionsFlow

    val liveVoiceController = LiveAudioDuplexController(
        appContext = appContext,
        // readTimeoutSec = 0 disables socket idle read timeouts on persistent full-duplex WebSockets (relying on 15s WS ping)
        httpClientProvider = { proxyManager.getOrCreateOkHttpClient(connectTimeoutSec = 10, readTimeoutSec = 0) },
        runtimeConfigProvider = { llmEngine.runtimeConfig },
        roleSelectionProvider = { cat -> _roleSelectionsFlow.value[cat] ?: cat.defaultSelection },
        webSearchExecutor = { query -> executeLiveVoiceWebSearch(query) },
        onNodeHandshakeFailed = { host, geoBlocked ->
            proxyManager.penalizeActiveNodeForHost(host, geoBlocked)
            refreshProxyUiState()
        }
    )

    val creativeEngine = CreativeMediaEngine(
        // 600s read timeout for unary Lyria 3.5 music & Gemini Omni 1.2 Flash 5s HD video synthesis
        httpClientProvider = { proxyManager.getOrCreateOkHttpClient(connectTimeoutSec = 20, readTimeoutSec = 600, writeTimeoutSec = 60) },
        runtimeConfigProvider = { llmEngine.runtimeConfig },
        roleSelectionProvider = { cat -> _roleSelectionsFlow.value[cat] ?: cat.defaultSelection }
    )

    private var activeTurnJob: Job? = null
    private val completedTurnsSinceDistill = AtomicInteger(0)

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT || intent?.action == Intent.ACTION_SCREEN_ON) {
                proxyManager.prewarmAiEndpointsIfDue()
            }
        }
    }

    fun start() {
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(unlockReceiver, filter)
            }
        }
        proxyManager.prewarmAiEndpointsIfDue()

        // Hydrate cached model catalog, role selections, proxy views, and cached Mac workspace catalog immediately
        val (cachedModels, cachedRefreshedAt) = edgeStore.loadCachedModelCatalog()
        _modelCatalogFlow.value = cachedModels
        _modelRefreshedAtFlow.value = cachedRefreshedAt
        val roleSel = edgeStore.loadModelRoleSelections()
        _roleSelectionsFlow.value = roleSel
        llmEngine.roleOverrides = roleSel
        refreshProxyUiState()

        edgeStore.loadCachedWorkspaceCatalogJson()?.let { raw ->
            runCatching { applyWorkspaceCatalogJson(JSONObject(raw)) }
        }
        edgeStore.loadCachedChisuCatalogJson()?.let { raw ->
            runCatching { applyChisuArticlesCatalogJson(JSONObject(raw)) }
        }

        scope.launch {
            transportClient.activeTierFlow.collect { tier ->
                if (tier != "DISCONNECTED") {
                    flushPendingSyncToMac()
                    refreshAgentSessions()
                    refreshChisuArticles()
                }
            }
        }
    }

    fun onAppForeground() {
        proxyManager.prewarmAiEndpointsIfDue(minIntervalMs = 90_000L)
        if (transportClient.activeTierFlow.value != "DISCONNECTED") {
            scope.launch {
                flushPendingSyncToMac()
                refreshAgentSessions()
                refreshChisuArticles()
            }
        }
    }

    fun cancelActiveTurn() {
        activeTurnJob?.cancel()
        activeTurnJob = null
        val cur = _streamingFlow.value
        if (cur.isStreaming && cur.messageId.isNotBlank()) {
            val partial = cur.markdownText.ifBlank { "（已手动停止生成）" }
            chatRepo.upsertMessage(
                ChatMessageModel(
                    messageId = cur.messageId,
                    threadId = cur.threadId,
                    role = "assistant",
                    content = partial,
                    reasoning = cur.reasoningText,
                    modelUsed = cur.modelLabel,
                    sources = cur.sources,
                    status = "DONE",
                    createdAtMs = System.currentTimeMillis()
                ),
                autoTitleFromFirstUserTurn = false
            )
        }
        _streamingFlow.value = StreamingTurnState()
    }

    fun sendChatTurn(
        userText: String,
        imageJpegBytes: ByteArray? = null,
        imageLocalPath: String? = null,
        deepThinking: Boolean = false,
        forceWebSearch: Boolean = false
    ) {
        val trimmed = userText.trim()
        if (trimmed.isEmpty() && imageJpegBytes == null) return
        val threadId = chatRepo.activeThreadIdFlow.value.ifBlank {
            chatRepo.createNewThread(incognito = false).threadId
        }
        val now = System.currentTimeMillis()
        val userMsg = ChatMessageModel(
            messageId = "msg_${OmsFrameProtocol.generateUuidV7().takeLast(12)}",
            threadId = threadId,
            role = "user",
            content = trimmed.ifBlank { "📷 请分析这张图片" },
            imageLocalPath = imageLocalPath,
            status = "DONE",
            createdAtMs = now
        )
        chatRepo.upsertMessage(userMsg, autoTitleFromFirstUserTurn = true)

        val assistantMsgId = "msg_${OmsFrameProtocol.generateUuidV7().takeLast(12)}"
        activeTurnJob?.cancel()
        activeTurnJob = scope.launch {
            executeStreamingTurnWithResilience(
                threadId = threadId,
                assistantMsgId = assistantMsgId,
                latestImageBytes = imageJpegBytes,
                deepThinking = deepThinking,
                forceWebSearch = forceWebSearch
            )
        }
    }

    fun regenerateLastAssistantTurn(deepThinking: Boolean = false, forceWebSearch: Boolean = false) {
        val threadId = chatRepo.activeThreadIdFlow.value.takeIf { it.isNotBlank() } ?: return
        val history = chatRepo.loadMessagesForThread(threadId)
        val lastUser = history.lastOrNull { it.role == "user" } ?: return
        val imgBytes = lastUser.imageLocalPath?.let { p ->
            runCatching { File(p).takeIf { it.exists() }?.readBytes() }.getOrNull()
        }
        val assistantMsgId = "msg_${OmsFrameProtocol.generateUuidV7().takeLast(12)}"
        vibrateTick()
        activeTurnJob?.cancel()
        activeTurnJob = scope.launch {
            executeStreamingTurnWithResilience(
                threadId = threadId,
                assistantMsgId = assistantMsgId,
                latestImageBytes = imgBytes,
                deepThinking = deepThinking,
                forceWebSearch = forceWebSearch
            )
        }
    }

    private suspend fun executeStreamingTurnWithResilience(
        threadId: String,
        assistantMsgId: String,
        latestImageBytes: ByteArray?,
        deepThinking: Boolean,
        forceWebSearch: Boolean
    ) {
        val startedMs = System.currentTimeMillis()
        _streamingFlow.value = StreamingTurnState(
            isStreaming = true,
            threadId = threadId,
            messageId = assistantMsgId,
            startedAtMs = startedMs,
            statusHint = when {
                deepThinking && forceWebSearch -> "正在联网检索并深度推演…"
                forceWebSearch -> "正在实时联网检索…"
                deepThinking -> "正在深度思考…"
                else -> "正在连接…"
            }
        )

        val history = chatRepo.loadMessagesForThread(threadId)
            .filter { it.messageId != assistantMsgId && it.content.isNotBlank() }
            .takeLast(16)

        val turns = history.mapIndexed { idx, m ->
            val isLastUser = idx == history.lastIndex && m.role == "user" && latestImageBytes != null
            val imgs = if (isLastUser && latestImageBytes != null) {
                listOf(LlmImageAttachment("image/jpeg", Base64.encodeToString(latestImageBytes, Base64.NO_WRAP)))
            } else emptyList()
            LlmTurnMessage(
                role = if (m.role == "assistant") "assistant" else "user",
                content = m.content,
                images = imgs
            )
        }

        val intent = when {
            deepThinking -> RouteIntent.DEEP_THINKING
            forceWebSearch -> RouteIntent.WEB_SEARCH_GROUNDED
            else -> RouteIntent.AUTO
        }
        val nowFmt = SimpleDateFormat("yyyy-MM-dd HH:mm EEEE", Locale.CHINA).format(Date())
        val devPrompt = CockpitAiServices.buildCockpitDeveloperPrompt(
            nowEpochMs = System.currentTimeMillis(),
            nowFormatted = nowFmt,
            deepThinking = deepThinking,
            webSearchEnabled = forceWebSearch
        )

        try {
            val firstPass = streamSinglePassWithRetry(
                threadId = threadId,
                assistantMsgId = assistantMsgId,
                startedMs = startedMs,
                intent = intent,
                devPrompt = devPrompt,
                turns = turns,
                forceWebSearch = forceWebSearch
            )
            val parsedFirst = CockpitAiServices.parseAssistantActions(firstPass.fullText)

            // Check if model invoked on-demand `search_my_context`!
            val finalCompletedAndActions = if (parsedFirst.contextSearchQuery != null) {
                val query = parsedFirst.contextSearchQuery!!
                _streamingFlow.value = _streamingFlow.value.copy(
                    statusHint = "正在按需检索个人知识库：$query",
                    markdownText = ""
                )
                val recallBlock = recallPersonalContext(query)
                val augmentedTurns = turns + listOf(
                    LlmTurnMessage("assistant", firstPass.fullText),
                    LlmTurnMessage("user", "$recallBlock\n\n请基于以上检索到的个人背景直接给出完整回答（无需再次调用 search_my_context）。")
                )
                val secondPass = streamSinglePassWithRetry(
                    threadId = threadId,
                    assistantMsgId = assistantMsgId,
                    startedMs = startedMs,
                    intent = intent,
                    devPrompt = devPrompt,
                    turns = augmentedTurns,
                    forceWebSearch = forceWebSearch
                )
                secondPass to CockpitAiServices.parseAssistantActions(secondPass.fullText)
            } else {
                firstPass to parsedFirst
            }

            val (completed, parsedActions) = finalCompletedAndActions
            chatRepo.upsertMessage(
                ChatMessageModel(
                    messageId = assistantMsgId,
                    threadId = threadId,
                    role = "assistant",
                    content = parsedActions.cleanMarkdown.ifBlank { completed.fullText },
                    reasoning = completed.fullReasoning,
                    modelUsed = completed.modelUsed,
                    sources = completed.sources,
                    calendarActions = parsedActions.calendarActions,
                    alarmActions = parsedActions.alarmActions,
                    status = "DONE",
                    createdAtMs = System.currentTimeMillis()
                ),
                autoTitleFromFirstUserTurn = false
            )
            vibrateConfirm()
            _streamingFlow.value = StreamingTurnState()

            // Background L0 mirror & periodic L1 distillation
            flushPendingSyncToMac()
            val thread = chatRepo.getThreadById(threadId)
            if (thread?.incognito == false && completedTurnsSinceDistill.incrementAndGet() >= 5) {
                completedTurnsSinceDistill.set(0)
                distillWorkingMemoryQuietly(threadId)
            }
        } catch (err: Throwable) {
            if (err is kotlinx.coroutines.CancellationException) return
            val fallbackMsg = "⚠️ 请求未完成：${err.message ?: "网络异常，请检查内置节点或密钥配置"}"
            chatRepo.upsertMessage(
                ChatMessageModel(
                    messageId = assistantMsgId,
                    threadId = threadId,
                    role = "assistant",
                    content = _streamingFlow.value.markdownText.takeIf { it.length > 20 }
                        ?.let { "$it\n\n---\n*$fallbackMsg*" } ?: fallbackMsg,
                    reasoning = _streamingFlow.value.reasoningText,
                    status = "ERROR",
                    createdAtMs = System.currentTimeMillis()
                ),
                autoTitleFromFirstUserTurn = false
            )
            _streamingFlow.value = StreamingTurnState()
        }
    }

    private suspend fun streamSinglePassWithRetry(
        threadId: String,
        assistantMsgId: String,
        startedMs: Long,
        intent: RouteIntent,
        devPrompt: String,
        turns: List<LlmTurnMessage>,
        forceWebSearch: Boolean = false
    ): LlmStreamEvent.Completed {
        var lastErr: Throwable? = null
        for (attempt in 1..2) {
            val textBuf = StringBuilder()
            val reasonBuf = StringBuilder()
            var latestSources: List<GroundingSource> = emptyList()
            var firstTokenVibrated = false
            var reasoningDoneVibrated = false
            var lastUiFlushMs = 0L
            var completedEvent: LlmStreamEvent.Completed? = null

            try {
                llmEngine.streamTurn(intent, devPrompt, turns, forceWebSearch).collect { evt ->
                    val now = System.currentTimeMillis()
                    when (evt) {
                        is LlmStreamEvent.ReasoningDelta -> {
                            reasonBuf.append(evt.text)
                            if (now - lastUiFlushMs >= 24L) {
                                lastUiFlushMs = now
                                _streamingFlow.value = _streamingFlow.value.copy(
                                    reasoningText = reasonBuf.toString(),
                                    statusHint = "💭 正在推演…"
                                )
                            }
                        }
                        is LlmStreamEvent.TextDelta -> {
                            if (!firstTokenVibrated) {
                                firstTokenVibrated = true
                                if (reasonBuf.isNotEmpty() && !reasoningDoneVibrated) {
                                    reasoningDoneVibrated = true
                                }
                                vibrateTick()
                            }
                            textBuf.append(evt.text)
                            if (now - lastUiFlushMs >= 24L) {
                                lastUiFlushMs = now
                                _streamingFlow.value = _streamingFlow.value.copy(
                                    threadId = threadId,
                                    messageId = assistantMsgId,
                                    reasoningText = reasonBuf.toString(),
                                    markdownText = textBuf.toString(),
                                    sources = latestSources,
                                    startedAtMs = startedMs,
                                    statusHint = ""
                                )
                            }
                        }
                        is LlmStreamEvent.Grounding -> {
                            latestSources = evt.sources
                            _streamingFlow.value = _streamingFlow.value.copy(sources = latestSources)
                        }
                        is LlmStreamEvent.Completed -> {
                            completedEvent = evt
                            _streamingFlow.value = _streamingFlow.value.copy(
                                reasoningText = evt.fullReasoning,
                                markdownText = evt.fullText,
                                modelLabel = evt.modelUsed,
                                sources = evt.sources,
                                statusHint = ""
                            )
                        }
                    }
                }
                if (completedEvent != null) return completedEvent!!
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastErr = e
                // If network flickered in elevator before streaming substantial text, wait 1.2s and retry once
                if (attempt == 1 && textBuf.length < 32) {
                    _streamingFlow.value = _streamingFlow.value.copy(statusHint = "📡 网络波动，正在自动重连续收…")
                    delay(1200L)
                    continue
                }
                throw e
            }
        }
        throw (lastErr ?: IllegalStateException("Stream failed"))
    }

    private suspend fun recallPersonalContext(query: String): String {
        val capsule = edgeStore.loadContextCapsule()
        val mergedCapsule = capsule.copy(
            recentWorkingMemory = (capsule.recentWorkingMemory + chatRepo.getLocalWorkingMemory()).distinct()
        )
        val localRecall = chatRepo.buildPersonalContextRecall(query, mergedCapsule, streamRepo.itemsFlow.value)
        if (transportClient.activeTierFlow.value == "DISCONNECTED") {
            return localRecall
        }
        val remoteBlock = runCatching {
            val resp = transportClient.callHostRpc(
                "memory.search_vault",
                JSONObject().put("query", query).put("limit", 5),
                4000L
            )
            val hits = resp?.optJSONArray("hits")
            if (hits != null && hits.length() > 0) {
                buildString {
                    appendLine("- Mac 实时知识库命中：")
                    for (i in 0 until hits.length()) {
                        val h = hits.optJSONObject(i) ?: continue
                        appendLine("  * [${h.optString("title")}] (${h.optString("pathOrRef")}): ${h.optString("snippet")}")
                    }
                }
            } else ""
        }.getOrDefault("")
        return if (remoteBlock.isNotBlank()) "$localRecall\n$remoteBlock" else localRecall
    }

    private fun distillWorkingMemoryQuietly(threadId: String) {
        scope.launch {
            runCatching {
                val recent = chatRepo.loadMessagesForThread(threadId).takeLast(10)
                val summary = recent.joinToString("\n") { "${it.role}: ${it.content.take(300)}" }
                val existing = (edgeStore.loadContextCapsule().recentWorkingMemory + chatRepo.getLocalWorkingMemory()).distinct()
                val updated = aiServices.distillWorkingMemory(summary, existing)
                chatRepo.saveLocalWorkingMemory(updated)
                flushPendingSyncToMac()
            }
        }
    }

    suspend fun transcribeVoiceNote(audioFile: File): String {
        vibrateTick()
        return aiServices.transcribeAudioFile(audioFile, mimeType = "audio/mp4")
    }

    private suspend fun executeLiveVoiceWebSearch(query: String): String = withContext(Dispatchers.IO) {
        val nowFmt = SimpleDateFormat("yyyy-MM-dd HH:mm EEEE", Locale.CHINA).format(Date())
        val completed = llmEngine.completeSync(
            intent = RouteIntent.WEB_SEARCH_GROUNDED,
            developerPrompt = "当前北京时间：$nowFmt。请针对查询立即执行实时联网搜索，用 3~5 句精炼准确的中文要点返回最新事实、气温/数值与时间（不要寒暄，方便语音助手直接口语播报）。",
            turns = listOf(LlmTurnMessage("user", query)),
            jsonMode = false,
            forceWebSearch = true
        )
        val srcNote = if (completed.sources.isNotEmpty()) {
            "\n来源：" + completed.sources.take(3).joinToString("、") { it.title.ifBlank { it.uri } }
        } else ""
        (completed.fullText.trim() + srcNote).ifBlank { "未检索到相关公开网页信息。" }
    }

    suspend fun identifyBotanicalSpecimen(
        jpegBytes: ByteArray,
        savedLocalPath: String?,
        userQuestion: String = ""
    ): FieldGuideSpecimenCard = withContext(Dispatchers.IO) {
        val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())
        val specId = "spec_${OmsFrameProtocol.generateUuidV7().takeLast(10)}"
        val card = botanicalAnalyzer.identifySpecimen(
            jpegBase64 = b64,
            userQuestion = userQuestion,
            specimenId = specId,
            capturedAtIso = iso
        )
        // Automatically append specimen card into current conversation so user can follow up anytime!
        val tid = chatRepo.activeThreadIdFlow.value.ifBlank {
            chatRepo.createNewThread(incognito = false).threadId
        }
        val now = System.currentTimeMillis()
        chatRepo.upsertMessage(
            ChatMessageModel(
                messageId = "msg_${OmsFrameProtocol.generateUuidV7().takeLast(12)}",
                threadId = tid,
                role = "user",
                content = if (userQuestion.isNotBlank()) "📷 博物识物：$userQuestion" else "📷 拍了拍身边博物：这是什么？",
                imageLocalPath = savedLocalPath,
                createdAtMs = now
            )
        )
        chatRepo.upsertMessage(
            ChatMessageModel(
                messageId = "msg_${OmsFrameProtocol.generateUuidV7().takeLast(12)}",
                threadId = tid,
                role = "assistant",
                content = card.oneLineSummary.ifBlank { "已生成《${card.chineseName}》博物图鉴卡。" },
                modelUsed = card.modelUsed,
                sources = card.sources,
                specimenCard = card,
                imageLocalPath = savedLocalPath,
                createdAtMs = now + 1L
            ),
            autoTitleFromFirstUserTurn = false
        )
        vibrateConfirm()
        flushPendingSyncToMac()
        card
    }

    suspend fun promoteHighlightToVault(title: String, markdownContent: String): Boolean = withContext(Dispatchers.IO) {
        val itemId = "prom_${OmsFrameProtocol.generateUuidV7().takeLast(10)}"
        val payload = JSONObject().apply {
            put("itemId", itemId)
            put("kind", "chat_highlight")
            put("title", title.take(60))
            put("markdownContent", markdownContent)
            put("createdAtMs", System.currentTimeMillis())
        }
        sendOrQueueVaultPromotion(itemId, payload)
    }

    suspend fun promoteSpecimenToVault(card: FieldGuideSpecimenCard, imageLocalPath: String?): Boolean =
        withContext(Dispatchers.IO) {
            val itemId = card.specimenId.ifBlank { "spec_${OmsFrameProtocol.generateUuidV7().takeLast(10)}" }
            val jpegB64 = imageLocalPath?.let { p ->
                runCatching {
                    val f = File(p)
                    if (f.exists() && f.length() < 4 * 1024 * 1024) {
                        Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
                    } else null
                }.getOrNull()
            }
            val payload = JSONObject().apply {
                put("itemId", itemId)
                put("kind", "botanical_specimen")
                put("title", card.chineseName)
                put("slugHint", card.latinName.ifBlank { card.chineseName })
                put("markdownContent", card.toVaultMarkdown())
                if (!jpegB64.isNullOrBlank()) put("jpegBase64", jpegB64)
                put("createdAtMs", System.currentTimeMillis())
            }
            sendOrQueueVaultPromotion(itemId, payload)
        }

    private suspend fun sendOrQueueVaultPromotion(itemId: String, payload: JSONObject): Boolean {
        if (transportClient.activeTierFlow.value != "DISCONNECTED") {
            val ok = runCatching {
                val res = transportClient.callHostRpc("memory.promote_vault", payload, 10_000L)
                res?.optBoolean("ok", false) == true
            }.getOrDefault(false)
            if (ok) {
                vibrateConfirm()
                return true
            }
        }
        chatRepo.enqueuePendingVaultPromotion(itemId, payload)
        vibrateTick()
        return false
    }

    fun flushPendingSyncToMac() {
        if (transportClient.activeTierFlow.value == "DISCONNECTED") return
        scope.launch {
            runCatching {
                chatRepo.exportUnsyncedBatch(40)?.let { (payload, ids) ->
                    val res = transportClient.callHostRpc("chat.sync.batch", payload, 8000L)
                    if (res?.optBoolean("ok", false) == true) {
                        chatRepo.markBatchSynced(ids.first, ids.second)
                    }
                }
                for ((itemId, promPayload) in chatRepo.listPendingVaultPromotions()) {
                    val res = transportClient.callHostRpc("memory.promote_vault", promPayload, 10_000L)
                    if (res?.optBoolean("ok", false) == true) {
                        chatRepo.removePendingVaultPromotion(itemId)
                    }
                }
            }
        }
    }

    // =========================================================================
    // Chisu (尺素) Mac Library Sync, Dual-Doc Detail Cache & Inline Image Loader
    // =========================================================================

    private fun chisuCacheDir(): File = File(appContext.filesDir, "chisu_cache").apply { mkdirs() }
    private fun chisuAssetCacheDir(): File = File(appContext.cacheDir, "chisu_assets").apply { mkdirs() }

    fun refreshChisuArticles(limit: Int = 80) {
        if (transportClient.activeTierFlow.value == "DISCONNECTED") return
        scope.launch {
            _isRefreshingChisuFlow.value = true
            try {
                val resp = transportClient.callHostRpc(
                    "wechat.articles.list",
                    JSONObject().put("limit", limit),
                    10_000L
                ) ?: return@launch
                if (resp.optBoolean("ok", false)) {
                    edgeStore.saveCachedChisuCatalogJson(resp.toString())
                    applyChisuArticlesCatalogJson(resp)
                }
            } catch (_: Throwable) {
            } finally {
                _isRefreshingChisuFlow.value = false
            }
        }
    }

    private fun applyChisuArticlesCatalogJson(resp: JSONObject) {
        val arr = resp.optJSONArray("articles") ?: return
        val out = ArrayList<ChisuArticleItemUi>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("id", 0)
            if (id <= 0) continue
            val rawTitle = o.optString("title", "")
            val summary = o.optString("shortSummary", "").trim()
            val digest = o.optString("digest", "").trim()
            val createdMs = ChisuDocumentParser.parseIsoToEpochMs(o.optString("createdAt", ""))
                .takeIf { it > 0L } ?: System.currentTimeMillis()
            val preview = ChisuDocumentParser.buildPlainExcerpt(summary.ifBlank { digest }, 220)
            out.add(
                ChisuArticleItemUi(
                    stableKey = "wx_$id",
                    articleId = id,
                    title = ChisuDocumentParser.cleanTitle(rawTitle).ifBlank { "微信文章 #$id" },
                    author = o.optString("author", "").trim(),
                    sourceUrl = o.optString("url", "").trim(),
                    publishDate = o.optString("publishDate", "").trim(),
                    createdAtMs = createdMs,
                    status = o.optString("status", "").trim(),
                    processLevel = o.optString("processLevel", "").trim(),
                    triage = o.optString("triage", "").trim(),
                    userNotes = o.optString("userNotes", "").trim(),
                    shortSummary = summary.ifBlank { digest },
                    previewExcerpt = preview,
                    hasNote = o.optBoolean("hasNote", false) || summary.isNotBlank(),
                    hasSource = o.optBoolean("hasSource", false),
                    isFallbackNote = o.optBoolean("isFallbackNote", false)
                )
            )
        }
        _chisuArticlesFlow.value = out
    }

    fun openChisuArticle(item: ChisuArticleItemUi, forceRefresh: Boolean = false) {
        _chisuDetailErrorFlow.value = null
        val localSource = item.localSourceFilePath?.let { p ->
            runCatching { File(p).takeIf { it.exists() }?.readText(Charsets.UTF_8) }.getOrNull()
        }
        val cached = loadCachedChisuDetail(item.articleId)
        val initial = ChisuArticleDetailUi(
            stableKey = item.stableKey,
            articleId = item.articleId ?: cached?.articleId,
            title = cached?.title?.ifBlank { item.title } ?: item.title,
            author = item.author.ifBlank { cached?.author.orEmpty() },
            sourceUrl = item.sourceUrl.ifBlank { cached?.sourceUrl.orEmpty() },
            publishDate = item.publishDate.ifBlank { cached?.publishDate.orEmpty() },
            createdAtMs = item.createdAtMs,
            status = item.status.ifBlank { cached?.status.orEmpty() },
            processLevel = item.processLevel.ifBlank { cached?.processLevel.orEmpty() },
            triage = item.triage.ifBlank { cached?.triage.orEmpty() },
            userNotes = item.userNotes.ifBlank { cached?.userNotes.orEmpty() },
            noteMarkdown = cached?.noteMarkdown?.takeIf { it.isNotBlank() } ?: item.localNoteMarkdown,
            sourceMarkdown = cached?.sourceMarkdown?.takeIf { it.isNotBlank() } ?: localSource,
            summaryMarkdown = cached?.summaryMarkdown?.takeIf { it.isNotBlank() }
                ?: item.shortSummary.takeIf { it.isNotBlank() },
            noteTruncated = cached?.noteTruncated == true,
            sourceTruncated = cached?.sourceTruncated == true,
            fromCache = cached != null
        )
        _activeChisuDetailFlow.value = initial

        val canQueryMac = transportClient.activeTierFlow.value != "DISCONNECTED" &&
            (item.articleId != null || item.sourceUrl.isNotBlank() || item.title.isNotBlank())
        val needsRemote = forceRefresh || initial.sourceMarkdown.isNullOrBlank() || initial.noteMarkdown.isNullOrBlank()
        if (!canQueryMac || !needsRemote) {
            _isLoadingChisuDetailFlow.value = false
            return
        }

        _isLoadingChisuDetailFlow.value = true
        scope.launch {
            try {
                val req = JSONObject().apply {
                    item.articleId?.let { put("articleId", it) }
                    if (item.sourceUrl.isNotBlank()) put("sourceUrl", item.sourceUrl)
                    if (item.title.isNotBlank()) put("title", item.title)
                }
                val resp = transportClient.callHostRpc("wechat.article.detail", req, 15_000L)
                if (resp != null && resp.optBoolean("ok", false)) {
                    val artObj = resp.optJSONObject("article")
                    val resolvedId = artObj?.optInt("id", 0)?.takeIf { it > 0 } ?: item.articleId
                    val noteMd = resp.optString("noteMarkdown", "").takeIf { it.isNotBlank() && it != "null" }
                    val sourceMd = resp.optString("sourceMarkdown", "").takeIf { it.isNotBlank() && it != "null" }
                    val summaryMd = resp.optString("summaryMarkdown", "").takeIf { it.isNotBlank() && it != "null" }
                    val remoteDetail = ChisuArticleDetailUi(
                        stableKey = item.stableKey,
                        articleId = resolvedId,
                        title = ChisuDocumentParser.cleanTitle(artObj?.optString("title", "")).ifBlank { item.title },
                        author = artObj?.optString("author", "").orEmpty().ifBlank { initial.author },
                        sourceUrl = artObj?.optString("url", "").orEmpty().ifBlank { initial.sourceUrl },
                        publishDate = artObj?.optString("publishDate", "").orEmpty().ifBlank { initial.publishDate },
                        createdAtMs = item.createdAtMs,
                        status = artObj?.optString("status", "").orEmpty().ifBlank { initial.status },
                        processLevel = artObj?.optString("processLevel", "").orEmpty().ifBlank { initial.processLevel },
                        triage = artObj?.optString("triage", "").orEmpty().ifBlank { initial.triage },
                        userNotes = artObj?.optString("userNotes", "").orEmpty().ifBlank { initial.userNotes },
                        noteMarkdown = noteMd ?: initial.noteMarkdown,
                        sourceMarkdown = sourceMd ?: initial.sourceMarkdown,
                        summaryMarkdown = summaryMd ?: initial.summaryMarkdown,
                        noteTruncated = resp.optBoolean("noteTruncated", false),
                        sourceTruncated = resp.optBoolean("sourceTruncated", false),
                        fromCache = false
                    )
                    if (resolvedId != null) {
                        saveCachedChisuDetail(resolvedId, remoteDetail)
                    }
                    if (_activeChisuDetailFlow.value?.stableKey == item.stableKey) {
                        _activeChisuDetailFlow.value = remoteDetail
                    }
                } else if (initial.sourceMarkdown.isNullOrBlank() && initial.noteMarkdown.isNullOrBlank()) {
                    _chisuDetailErrorFlow.value = resp?.optString("error", "").orEmpty().ifBlank { "未能从 Mac 读取该文章详情" }
                }
            } catch (e: Throwable) {
                if (initial.sourceMarkdown.isNullOrBlank() && initial.noteMarkdown.isNullOrBlank()) {
                    _chisuDetailErrorFlow.value = e.message ?: "同步失败"
                }
            } finally {
                _isLoadingChisuDetailFlow.value = false
            }
        }
    }

    fun closeChisuArticle() {
        _activeChisuDetailFlow.value = null
        _isLoadingChisuDetailFlow.value = false
        _chisuDetailErrorFlow.value = null
    }

    suspend fun loadChisuInlineImageBytes(
        articleId: Int?,
        docKind: String,
        src: String
    ): ByteArray? = withContext(Dispatchers.IO) {
        val trimmed = src.trim()
        if (trimmed.isEmpty()) return@withContext null
        val cacheKey = "${articleId ?: 0}_${docKind}_${trimmed.hashCode().toUInt()}"
        val cacheFile = File(chisuAssetCacheDir(), "$cacheKey.img")
        if (cacheFile.exists() && cacheFile.length() > 0L) {
            return@withContext runCatching { cacheFile.readBytes() }.getOrNull()
        }

        val bytes: ByteArray? = if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            runCatching {
                val client = proxyManager.getOrCreateOkHttpClient(connectTimeoutSec = 8, readTimeoutSec = 12)
                val req = okhttp3.Request.Builder().url(trimmed).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null
                    else resp.body?.bytes()?.takeIf { it.isNotEmpty() && it.size <= 4 * 1024 * 1024 }
                }
            }.getOrNull()
        } else if (articleId != null && articleId > 0 && transportClient.activeTierFlow.value != "DISCONNECTED") {
            runCatching {
                val resp = transportClient.callHostRpc(
                    "wechat.article.asset",
                    JSONObject()
                        .put("articleId", articleId)
                        .put("doc", if (docKind == "note") "note" else "source")
                        .put("path", trimmed),
                    10_000L
                )
                val b64 = resp?.takeIf { it.optBoolean("ok", false) }?.optString("dataBase64", "").orEmpty()
                if (b64.isNotBlank()) Base64.decode(b64, Base64.DEFAULT) else null
            }.getOrNull()
        } else {
            null
        }

        if (bytes != null && bytes.isNotEmpty()) {
            runCatching { cacheFile.writeBytes(bytes) }
        }
        bytes
    }

    private fun saveCachedChisuDetail(articleId: Int, detail: ChisuArticleDetailUi) {
        runCatching {
            val file = File(chisuCacheDir(), "article_$articleId.json")
            val obj = JSONObject().apply {
                put("articleId", articleId)
                put("title", detail.title)
                put("author", detail.author)
                put("sourceUrl", detail.sourceUrl)
                put("publishDate", detail.publishDate)
                put("status", detail.status)
                put("processLevel", detail.processLevel)
                put("triage", detail.triage)
                put("userNotes", detail.userNotes)
                if (!detail.noteMarkdown.isNullOrBlank()) put("noteMarkdown", detail.noteMarkdown)
                if (!detail.sourceMarkdown.isNullOrBlank()) put("sourceMarkdown", detail.sourceMarkdown)
                if (!detail.summaryMarkdown.isNullOrBlank()) put("summaryMarkdown", detail.summaryMarkdown)
                put("noteTruncated", detail.noteTruncated)
                put("sourceTruncated", detail.sourceTruncated)
                put("cachedAtMs", System.currentTimeMillis())
            }
            file.writeText(obj.toString(), Charsets.UTF_8)
        }
    }

    private fun loadCachedChisuDetail(articleId: Int?): ChisuArticleDetailUi? {
        if (articleId == null || articleId <= 0) return null
        return runCatching {
            val file = File(chisuCacheDir(), "article_$articleId.json")
            if (!file.exists()) return null
            val o = JSONObject(file.readText(Charsets.UTF_8))
            ChisuArticleDetailUi(
                stableKey = "wx_$articleId",
                articleId = articleId,
                title = o.optString("title", ""),
                author = o.optString("author", ""),
                sourceUrl = o.optString("sourceUrl", ""),
                publishDate = o.optString("publishDate", ""),
                status = o.optString("status", ""),
                processLevel = o.optString("processLevel", ""),
                triage = o.optString("triage", ""),
                userNotes = o.optString("userNotes", ""),
                noteMarkdown = o.optString("noteMarkdown", "").takeIf { it.isNotBlank() },
                sourceMarkdown = o.optString("sourceMarkdown", "").takeIf { it.isNotBlank() },
                summaryMarkdown = o.optString("summaryMarkdown", "").takeIf { it.isNotBlank() },
                noteTruncated = o.optBoolean("noteTruncated", false),
                sourceTruncated = o.optBoolean("sourceTruncated", false),
                fromCache = true
            )
        }.getOrNull()
    }

    // =========================================================================
    // Milestone 3: Mac Heavy-Brain Hub (Codex & Antigravity Monitor / Dispatch / Resume)
    // =========================================================================

    fun refreshAgentSessions() {
        if (transportClient.activeTierFlow.value == "DISCONNECTED") return
        scope.launch {
            runCatching {
                val resp = transportClient.callHostRpc(
                    "agent.workspace_catalog",
                    JSONObject().put("limit", 48),
                    12_000L
                ) ?: return@launch
                edgeStore.saveCachedWorkspaceCatalogJson(resp.toString())
                applyWorkspaceCatalogJson(resp)
            }
        }
    }

    private fun applyWorkspaceCatalogJson(resp: JSONObject) {
        resp.optJSONArray("projects")?.let { pArr ->
            val pList = ArrayList<RemoteAgentProjectUi>(pArr.length())
            for (i in 0 until pArr.length()) {
                val o = pArr.optJSONObject(i) ?: continue
                pList.add(
                    RemoteAgentProjectUi(
                        projectPath = o.optString("projectPath"),
                        displayName = o.optString("displayName", o.optString("projectPath").substringAfterLast('/')),
                        group = o.optString("group", "项目"),
                        sessionCount = o.optInt("sessionCount", 0),
                        runningCount = o.optInt("runningCount", 0),
                        lastActiveAtMs = o.optLong("lastActiveAtMs", 0L)
                    )
                )
            }
            if (pList.isNotEmpty()) _agentProjectsFlow.value = pList
        }
        resp.optJSONArray("sessions")?.let { sArr ->
            val sList = ArrayList<RemoteAgentSessionUi>(sArr.length())
            for (i in 0 until sArr.length()) {
                val o = sArr.optJSONObject(i) ?: continue
                sList.add(parseSessionJson(o))
            }
            _agentSessionsFlow.value = sList
        }
        resp.optJSONObject("quotas")?.let { qObj ->
            _agentQuotasFlow.value = parseQuotasJson(qObj)
        }
    }

    // =========================================================================
    // v2.1: Proxy Node Selection, Speed Test, Model Matrix & Creative Media
    // =========================================================================

    fun refreshProxyUiState() {
        _proxyRoutingModeFlow.value = proxyManager.router.routing
        _proxyNodesFlow.value = proxyManager.router.getNodesStatusViews()
        _proxySummaryFlow.value = proxyManager.router.describeSelection()
    }

    fun setProxyRoutingPolicy(mode: String, pinnedNodeKey: String? = proxyManager.router.pinnedNodeKey) {
        proxyManager.setRoutingPolicyAndEvict(mode, pinnedNodeKey)
        edgeStore.saveProxyUserPolicy(mode, pinnedNodeKey)
        vibrateTick()
        refreshProxyUiState()
    }

    suspend fun runFullProxySpeedTest(): List<ProxyNodeStatusView> {
        val updated = proxyManager.runFullSpeedTest(concurrency = 14, timeoutMs = 4000)
        _proxyNodesFlow.value = updated
        _proxySummaryFlow.value = proxyManager.router.describeSelection()
        vibrateConfirm()
        return updated
    }

    suspend fun probeSingleProxyNode(nodeKey: String): Long? {
        val ms = proxyManager.probeSingleNodeByKey(nodeKey, timeoutMs = 4000)
        refreshProxyUiState()
        return ms
    }

    suspend fun probeAiConnectivity(): AiConnectivityReport {
        val cfg = llmEngine.runtimeConfig
        val rep = proxyManager.probeAiEndpointsConnectivity(
            openAiApiKey = cfg.openai?.apiKey.orEmpty(),
            openAiBaseUrl = cfg.openai?.baseUrl ?: "https://api.openai.com/v1",
            geminiApiKey = cfg.gemini?.apiKey.orEmpty(),
            geminiBaseUrl = cfg.gemini?.baseUrl ?: "https://generativelanguage.googleapis.com/v1beta"
        )
        _aiConnectivityFlow.value = rep
        refreshProxyUiState()
        vibrateConfirm()
        return rep
    }

    suspend fun refreshProxySubscriptionsFromUrls(): Int = withContext(Dispatchers.IO) {
        val (_, urls, _) = edgeStore.loadProxyBootstrapConfig()
        if (urls.isEmpty()) {
            // If phone has no subscription URLs yet, request fresh sync from Mac if connected
            if (transportClient.activeTierFlow.value != "DISCONNECTED") {
                transportClient.callHostRpc("edge.requestSync", JSONObject(), 12_000L)
                delay(800L)
            }
        }
        val (_, latestUrls, _) = edgeStore.loadProxyBootstrapConfig()
        val count = if (latestUrls.isNotEmpty()) proxyManager.refreshFromSubscriptionUrls(latestUrls) else 0
        if (count > 0) {
            proxyManager.runFullSpeedTest(concurrency = 12, timeoutMs = 4000)
        }
        refreshProxyUiState()
        proxyManager.router.allNodesSnapshot().size
    }

    fun setModelRoleSelection(category: ModelRoleCategory, selectionKey: String) {
        val updated = _roleSelectionsFlow.value.toMutableMap()
        updated[category] = selectionKey
        _roleSelectionsFlow.value = updated
        edgeStore.saveModelRoleSelections(updated)
        llmEngine.roleOverrides = updated
        vibrateTick()
    }

    suspend fun refreshLiveProviderModels(): ModelCatalogRefreshResult {
        val res = ModelCatalogRegistry.refreshLiveProviderCatalog(
            httpClient = proxyManager.getOrCreateOkHttpClient(connectTimeoutSec = 12, readTimeoutSec = 20),
            config = llmEngine.runtimeConfig
        )
        _modelCatalogFlow.value = res.items
        _modelRefreshedAtFlow.value = res.refreshedAtMs
        edgeStore.saveCachedModelCatalog(res.items, res.refreshedAtMs)
        // Refresh proxy UI state in case AiGeoBlockFailoverInterceptor flagged & bypassed a geo-blocked node!
        refreshProxyUiState()
        vibrateConfirm()
        return res
    }

    fun sendCreativeTurn(category: ModelRoleCategory, prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return
        val threadId = chatRepo.activeThreadIdFlow.value.ifBlank {
            chatRepo.createNewThread(incognito = false).threadId
        }
        val now = System.currentTimeMillis()
        val badge = when (category) {
            ModelRoleCategory.IMAGE_GENERATION -> "🎨 图片创作"
            ModelRoleCategory.VIDEO_GENERATION -> "🎬 视频创作"
            ModelRoleCategory.MUSIC_GENERATION -> "🎵 歌曲创作"
            else -> "✨ 创作"
        }
        chatRepo.upsertMessage(
            ChatMessageModel(
                messageId = "msg_${OmsFrameProtocol.generateUuidV7().takeLast(12)}",
                threadId = threadId,
                role = "user",
                content = "$badge：$trimmed",
                createdAtMs = now
            )
        )
        val assistantMsgId = "msg_${OmsFrameProtocol.generateUuidV7().takeLast(12)}"
        activeTurnJob?.cancel()
        activeTurnJob = scope.launch {
            _streamingFlow.value = StreamingTurnState(
                isStreaming = true,
                threadId = threadId,
                messageId = assistantMsgId,
                startedAtMs = now,
                statusHint = "$badge 生成中（视频/音乐可能需要 15~45 秒）…"
            )
            try {
                val res = creativeEngine.generateCreativeMedia(category, trimmed)
                val ext = when {
                    res.mimeType.contains("jpeg") -> "jpg"
                    res.mimeType.contains("png") -> "png"
                    res.mimeType.contains("mp4") || res.mediaKind == "video" -> "mp4"
                    res.mimeType.contains("wav") -> "wav"
                    res.mediaKind == "music" -> "mp3"
                    else -> "bin"
                }
                val outDir = File(appContext.filesDir, "creative_media").apply { mkdirs() }
                val outFile = File(outDir, "gen_${System.currentTimeMillis()}.$ext")
                outFile.writeBytes(res.bytes)

                val summaryMd = buildString {
                    appendLine("✅ **$badge 已生成**（模型：`${res.modelUsed}`）")
                    if (res.caption.isNotBlank()) {
                        appendLine()
                        appendLine(res.caption)
                    }
                    appendLine()
                    appendLine("📁 本地文件：`${outFile.name}` (${res.bytes.size / 1024} KB)")
                }
                chatRepo.upsertMessage(
                    ChatMessageModel(
                        messageId = assistantMsgId,
                        threadId = threadId,
                        role = "assistant",
                        content = summaryMd,
                        modelUsed = res.modelUsed,
                        imageLocalPath = outFile.absolutePath,
                        status = "DONE",
                        createdAtMs = System.currentTimeMillis()
                    ),
                    autoTitleFromFirstUserTurn = false
                )
                vibrateConfirm()
                flushPendingSyncToMac()
            } catch (e: Throwable) {
                chatRepo.upsertMessage(
                    ChatMessageModel(
                        messageId = assistantMsgId,
                        threadId = threadId,
                        role = "assistant",
                        content = "⚠️ $badge 未完成：${e.message}",
                        status = "ERROR",
                        createdAtMs = System.currentTimeMillis()
                    ),
                    autoTitleFromFirstUserTurn = false
                )
            } finally {
                _streamingFlow.value = StreamingTurnState()
            }
        }
    }

    fun selectAndLoadAgentSession(session: RemoteAgentSessionUi, preserveExistingStepsWhileLoading: Boolean = false) {
        _selectedAgentSessionFlow.value = session
        if (!preserveExistingStepsWhileLoading) {
            _selectedAgentStepsFlow.value = emptyList()
        }
        if (transportClient.activeTierFlow.value == "DISCONNECTED") return
        _isLoadingAgentDetailFlow.value = true
        scope.launch {
            try {
                val resp = transportClient.callHostRpc(
                    "agent.session.detail",
                    JSONObject().put("backend", session.backend).put("sessionId", session.sessionId),
                    15_000L
                )
                resp?.optJSONObject("session")?.let { parsedSess ->
                    val currentSel = _selectedAgentSessionFlow.value
                    if (currentSel != null && currentSel.backend == session.backend) {
                        _selectedAgentSessionFlow.value = parseSessionJson(parsedSess)
                    }
                }
                val stepsArr = resp?.optJSONArray("steps")
                if (stepsArr != null) {
                    val steps = ArrayList<RemoteAgentStepUi>(stepsArr.length())
                    for (i in 0 until stepsArr.length()) {
                        val s = stepsArr.optJSONObject(i) ?: continue
                        steps.add(parseStepJson(s))
                    }
                    if (steps.isNotEmpty() || !preserveExistingStepsWhileLoading) {
                        _selectedAgentStepsFlow.value = steps
                    }
                }
            } catch (_: Throwable) {
            } finally {
                _isLoadingAgentDetailFlow.value = false
            }
        }
    }

    fun closeSelectedAgentSession() {
        _selectedAgentSessionFlow.value = null
        _selectedAgentStepsFlow.value = emptyList()
        _isLoadingAgentDetailFlow.value = false
    }

    suspend fun startRemoteAgentSession(
        backend: String,
        prompt: String,
        workspacePath: String = "",
        titleHint: String = ""
    ): RemoteAgentSessionUi? = withContext(Dispatchers.IO) {
        val req = JSONObject().apply {
            put("clientRequestId", OmsFrameProtocol.generateUuidV7())
            put("backend", backend)
            put("prompt", prompt)
            if (workspacePath.isNotBlank()) put("workspacePath", workspacePath.trim())
            if (titleHint.isNotBlank()) put("titleHint", titleHint.trim())
        }
        val resp = transportClient.callHostRpc("agent.session.start", req, 10_000L)
        val sObj = resp?.optJSONObject("session") ?: return@withContext null
        val created = parseSessionJson(sObj)
        vibrateConfirm()
        refreshAgentSessions()
        selectAndLoadAgentSession(created)
        created
    }

    suspend fun continueRemoteAgentSession(
        backend: String,
        sessionId: String,
        prompt: String,
        workspacePath: String = ""
    ): RemoteAgentSessionUi? = withContext(Dispatchers.IO) {
        val req = JSONObject().apply {
            put("clientRequestId", OmsFrameProtocol.generateUuidV7())
            put("backend", backend)
            put("sessionId", sessionId)
            put("prompt", prompt)
            if (workspacePath.isNotBlank()) put("workspacePath", workspacePath.trim())
        }
        val resp = transportClient.callHostRpc("agent.session.continue", req, 10_000L)
        val sObj = resp?.optJSONObject("session") ?: return@withContext null
        val resumed = parseSessionJson(sObj)
        vibrateConfirm()
        refreshAgentSessions()
        selectAndLoadAgentSession(resumed)
        resumed
    }

    fun cancelRemoteAgentSession(backend: String, sessionId: String) {
        scope.launch {
            runCatching {
                transportClient.callHostRpc(
                    "agent.session.cancel",
                    JSONObject().put("backend", backend).put("sessionId", sessionId),
                    5000L
                )
                refreshAgentSessions()
            }
        }
    }

    fun onRemoteAgentSessionEvent(params: JSONObject) {
        val backend = params.optString("backend", "codex")
        val sessionId = params.optString("sessionId")
        val previousSessionId = params.optString("previousSessionId")
        val status = params.optString("status", "running")
        val finalSummary = params.optString("finalSummary")
        val updatedAtMs = params.optLong("updatedAtMs", System.currentTimeMillis())
        val stepObj = params.optJSONObject("step")
        val parsedStep = stepObj?.let(::parseStepJson)

        val currentList = _agentSessionsFlow.value.toMutableList()
        val idx = currentList.indexOfFirst {
            it.backend == backend && (
                it.sessionId == sessionId ||
                    (previousSessionId.isNotBlank() && it.sessionId == previousSessionId)
                )
        }
        if (idx >= 0) {
            val prev = currentList[idx]
            currentList[idx] = prev.copy(
                sessionId = sessionId.ifBlank { prev.sessionId },
                status = status,
                lastSummary = finalSummary.ifBlank {
                    parsedStep?.let { "${it.title}: ${it.detail}".take(120) } ?: prev.lastSummary
                },
                updatedAtMs = updatedAtMs
            )
            _agentSessionsFlow.value = currentList
        } else {
            refreshAgentSessions()
        }

        val sel = _selectedAgentSessionFlow.value
        val matchesSelected = sel != null && sel.backend == backend && (
            sel.sessionId == sessionId ||
                (previousSessionId.isNotBlank() && sel.sessionId == previousSessionId) ||
                sel.sessionId.startsWith("pending_")
            )
        if (matchesSelected && sel != null) {
            val updatedSel = sel.copy(
                sessionId = sessionId.ifBlank { sel.sessionId },
                status = status,
                lastSummary = finalSummary.ifBlank { sel.lastSummary },
                updatedAtMs = updatedAtMs
            )
            _selectedAgentSessionFlow.value = updatedSel
            if (parsedStep != null) {
                _selectedAgentStepsFlow.value = (_selectedAgentStepsFlow.value + parsedStep).takeLast(140)
            }
            if (status == "completed" || status == "failed") {
                refreshAgentSessions()
                selectAndLoadAgentSession(updatedSel, preserveExistingStepsWhileLoading = true)
            }
        }
    }

    private fun parseSessionJson(o: JSONObject): RemoteAgentSessionUi {
        val ws = o.optString("workspacePath", "")
        val projName = o.optString("projectDisplayName", "").ifBlank {
            ws.trimEnd('/').substringAfterLast('/').ifBlank { "工作区" }
        }
        return RemoteAgentSessionUi(
            sessionId = o.optString("sessionId"),
            backend = o.optString("backend", "codex"),
            origin = o.optString("origin", "desktop"),
            title = o.optString("title", "").ifBlank { "${if (o.optString("backend") == "agy") "Antigravity" else "Codex"} · $projName" },
            workspacePath = ws,
            projectDisplayName = projName,
            status = o.optString("status", "idle"),
            lastSummary = o.optString("lastSummary", ""),
            updatedAtMs = o.optLong("updatedAtMs", System.currentTimeMillis()),
            canContinue = o.optBoolean("canContinue", true)
        )
    }

    private fun parseQuotasJson(qObj: JSONObject): RemoteAgentQuotasUi {
        fun parseBackend(bKey: String): RemoteAgentBackendQuotaUi {
            val b = qObj.optJSONObject(bKey) ?: JSONObject()
            val wArr = b.optJSONArray("windows") ?: JSONArray()
            val winList = ArrayList<RemoteAgentQuotaWindowUi>(wArr.length())
            for (i in 0 until wArr.length()) {
                val w = wArr.optJSONObject(i) ?: continue
                winList.add(
                    RemoteAgentQuotaWindowUi(
                        key = w.optString("key", "primary"),
                        label = w.optString("label", "配额窗口"),
                        usedPercent = w.optInt("usedPercent", 0).coerceIn(0, 100),
                        remainingPercent = w.optInt("remainingPercent", 100).coerceIn(0, 100),
                        windowMinutes = if (w.has("windowMinutes")) w.optInt("windowMinutes") else null,
                        resetsAtMs = if (w.has("resetsAtMs") && w.optLong("resetsAtMs", 0L) > 0L) w.optLong("resetsAtMs") else null
                    )
                )
            }
            return RemoteAgentBackendQuotaUi(
                backend = b.optString("backend", bKey),
                available = b.optBoolean("available", false),
                planLabel = b.optString("planLabel", bKey.uppercase(Locale.US)),
                activeModel = b.optString("activeModel", ""),
                windows = winList,
                creditsSummary = b.optString("creditsSummary", ""),
                statusNote = b.optString("statusNote", ""),
                capturedAtMs = b.optLong("capturedAtMs", 0L)
            )
        }
        return RemoteAgentQuotasUi(
            codex = parseBackend("codex"),
            agy = parseBackend("agy"),
            fetchedAtMs = qObj.optLong("fetchedAtMs", System.currentTimeMillis())
        )
    }

    private fun parseStepJson(o: JSONObject) = RemoteAgentStepUi(
        stepIndex = o.optInt("stepIndex", 1),
        kind = o.optString("kind", "assistant"),
        title = o.optString("title", ""),
        detail = o.optString("detail", ""),
        timestampMs = o.optLong("timestampMs", System.currentTimeMillis())
    )

    fun vibrateTick() {
        runCatching {
            val vib = getVibrator() ?: return
            vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        }
    }

    fun vibrateConfirm() {
        runCatching {
            val vib = getVibrator() ?: return
            vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        }
    }

    private fun getVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
