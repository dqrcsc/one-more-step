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
import kotlinx.coroutines.CancellationException
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
    val workbuddy: RemoteAgentBackendQuotaUi? = null,
    val opencode: RemoteAgentBackendQuotaUi? = null,
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

data class OmniSearchHitUi(
    val source: String,
    val title: String,
    val pathOrRef: String,
    val snippet: String,
    val heading: String = "",
    val category: String = "",
    val articleId: Int? = null,
    val turnId: Int? = null
)

data class VaultDocPreviewUi(
    val relPath: String,
    val title: String,
    val category: String,
    val markdown: String,
    val truncated: Boolean = false
)

data class SecretaryCommitmentUi(
    val id: String,
    val title: String,
    val dueHint: String = "",
    val source: String = "",
    val status: String = "open",
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L
)

data class SecretaryNudgeUi(
    val id: String,
    val kind: String,
    val title: String,
    val subtitle: String,
    val actionLabel: String,
    val actionType: String,
    val actionPayload: String
)

data class LivingMemoryEntryUi(
    val id: String,
    val text: String,
    val source: String, // "manual" | "auto"
    val category: String, // "profile" | "working" | "vault"
    val originDetail: String = "",
    val observations: Int = 1,
    val createdAtMs: Long = 0L,
    val lastSeenAtMs: Long = 0L
)

data class MemoryCenterSnapshotUi(
    val profileEntries: List<LivingMemoryEntryUi> = emptyList(),
    val workingEntries: List<LivingMemoryEntryUi> = emptyList(),
    val vaultAnchors: List<LivingMemoryEntryUi> = emptyList(),
    val forgottenCount: Int = 0,
    val updatedAtMs: Long = 0L
)

data class MacInstalledAppUi(
    val name: String,
    val bundlePath: String,
    val running: Boolean
)

data class MacControlStatusUi(
    val macId: String = "",
    val macName: String = "Mac",
    val screenLocked: Boolean = false,
    val stayAwakeEnabled: Boolean = false,
    val unlockPasswordConfigured: Boolean = false,
    val accessibilityTrusted: Boolean = false,
    val volumePct: Int = 50,
    val muted: Boolean = false,
    val frontmostApp: String = "",
    val installedApps: List<MacInstalledAppUi> = emptyList(),
    val updatedAtMs: Long = 0L
)

data class SecretaryBriefUi(
    val greeting: String,
    val dateLabel: String,
    val headline: String,
    val profileItems: List<String> = emptyList(),
    val openLoops: List<SecretaryCommitmentUi> = emptyList(),
    val nudges: List<SecretaryNudgeUi> = emptyList(),
    val recentChisuCount: Int = 0,
    val activeAgentCount: Int = 0,
    val vaultFocusTitles: List<String> = emptyList(),
    val generatedAtMs: Long = 0L
)

/**
 * Application-scoped coordinator for 咫尺 v3.6:
 *  - Unlock TLS Prewarmer (`<800ms` TTFT)
 *  - Offline-resilient `BackgroundTurnExecutor` (`24ms` frame-aligned streaming + HyperOS haptics)
 *  - 3-Tier Memory Sync + Standalone Memory Center (`memory.center.*`, anti-resurrection tombstones)
 *  - Multi-Mac Heavy-Brain Hub (`Codex`, `Antigravity`, `WorkBuddy`, `OpenCode`) & Mac Remote OS Control
 */
class CockpitRuntimeCoordinator(
    private val appContext: Context,
    val chatRepo: ChatMemoryRepository,
    private val streamRepo: StreamRepository,
    private val edgeStore: EdgeSecureStore,
    private val proxyManager: OmsProxyManager,
    val llmEngine: DualLlmEngine,
    private val transportClient: OmsTransportClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val chatSync = ChatSyncQueue.withOutcome(
        chatRepo,
        { currentMacId() },
        { transportClient.activeTierFlow.value != "DISCONNECTED" }
    ) { method, payload, macId ->
        val resp = transportClient.callHostRpc(
            method,
            payload,
            if (method == "chat.sync.batch") 8000L else 10_000L,
            macId
        )
        ChatSyncQueue.classifyHostRpcResponse(resp)
    }
    private val macScope = MacRequestScope().apply { switchTo(streamRepo.getPairedMac()?.macId.orEmpty()) }
    private var detailJob: Job? = null
    private val detailGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private val catalogGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private fun currentMacId() = streamRepo.getPairedMac()?.macId.orEmpty()
    private fun accepts(ticket: MacRequestScope.Ticket) = macScope.accepts(ticket) && currentMacId() == ticket.macId
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

    // Omni-Search (Full Vault FTS5 + Chisu + Chat) & Vault Document Reader
    private val _omniSearchHitsFlow = MutableStateFlow<List<OmniSearchHitUi>>(emptyList())
    val omniSearchHitsFlow: StateFlow<List<OmniSearchHitUi>> = _omniSearchHitsFlow

    private val _isOmniSearchingFlow = MutableStateFlow(false)
    val isOmniSearchingFlow: StateFlow<Boolean> = _isOmniSearchingFlow

    private val _activeVaultDocFlow = MutableStateFlow<VaultDocPreviewUi?>(null)
    val activeVaultDocFlow: StateFlow<VaultDocPreviewUi?> = _activeVaultDocFlow

    private val _isLoadingVaultDocFlow = MutableStateFlow(false)
    val isLoadingVaultDocFlow: StateFlow<Boolean> = _isLoadingVaultDocFlow

    // Chief-of-Staff Executive Core (Today Brief + Living Memory + Open Loops)
    private val _secretaryBriefFlow = MutableStateFlow<SecretaryBriefUi?>(null)
    val secretaryBriefFlow: StateFlow<SecretaryBriefUi?> = _secretaryBriefFlow

    private val _isRefreshingBriefFlow = MutableStateFlow(false)
    val isRefreshingBriefFlow: StateFlow<Boolean> = _isRefreshingBriefFlow

    // Standalone Memory Center & Remote Mac OS Control Flows
    private val _memoryCenterFlow = MutableStateFlow(MemoryCenterSnapshotUi())
    val memoryCenterFlow: StateFlow<MemoryCenterSnapshotUi> = _memoryCenterFlow

    private val _isRefreshingMemoryCenterFlow = MutableStateFlow(false)
    val isRefreshingMemoryCenterFlow: StateFlow<Boolean> = _isRefreshingMemoryCenterFlow

    private val _macControlStatusFlow = MutableStateFlow<MacControlStatusUi?>(null)
    val macControlStatusFlow: StateFlow<MacControlStatusUi?> = _macControlStatusFlow

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

        loadCachedCatalogForMac(currentMacId())
        loadCachedSecretaryBrief()?.let { _secretaryBriefFlow.value = it }
        loadCachedMemoryCenter()?.let { _memoryCenterFlow.value = it } ?: syncLocalFallbackMemoryCenter()

        scope.launch {
            transportClient.activeTierFlow.collect { tier ->
                if (tier != "DISCONNECTED") {
                    if (macScope.ticket().macId != currentMacId()) withContext(Dispatchers.Main.immediate) { loadCachedCatalogForMac(currentMacId()) }
                    flushPendingSyncToMac()
                    flushPendingBrowserCurates()
                    refreshAgentSessions()
                    refreshMemoryCenter()
                    refreshMacControlStatus()
                    refreshChisuArticles()
                    refreshSecretaryBrief()
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
                refreshSecretaryBrief()
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
        val baseDevPrompt = CockpitAiServices.buildCockpitDeveloperPrompt(
            nowEpochMs = System.currentTimeMillis(),
            nowFormatted = nowFmt,
            deepThinking = deepThinking,
            webSearchEnabled = forceWebSearch
        )
        val activeThreadForPrompt = chatRepo.getThreadById(threadId)
        val secretaryContextSuffix = if (activeThreadForPrompt?.incognito == true) "" else buildSecretaryMemoryPromptBlock()

        // Detect pending unconfirmed `phone_call` from the immediately preceding assistant turn
        val prevAssistantMsg = history.dropLast(1).lastOrNull { it.role == "assistant" }
        val pendingCallAction = prevAssistantMsg?.phoneToolActions?.firstOrNull {
            it.tool == "phone_call" && it.requiresConfirm
        }
        val latestUserText = history.lastOrNull { it.role == "user" }?.content?.trim().orEmpty()
        val isDirectUserCallConfirm = pendingCallAction != null && (
            Regex("""^(确认|确定|打吧|拨吧|拨号|拨出去|好|好的|可以|行|直接打|直接拨|确认拨出|嗯|是的|是)[!！。.,，~～]*$""")
                .matches(latestUserText) ||
                latestUserText.startsWith("确认打") ||
                latestUserText.startsWith("确认拨")
            )
        val pendingCallPromptHint = if (pendingCallAction != null) {
            val resolved = com.oms.client.capability.PhoneToolExecutor
                .resolveContactCandidates(appContext, pendingCallAction.target)
                .firstOrNull()
            val numDesc = resolved?.let { "${it.displayName} (${it.number})" } ?: pendingCallAction.target
            "\n\n【待确认通话状态】：上一轮你正等待用户确认是否拨打「$numDesc」。如果用户本轮表达同意/确认，请直接输出 `{\"tool\":\"phone_call\",\"target\":\"${pendingCallAction.target}\",\"speaker\":${pendingCallAction.extra == "speaker"},\"confirmed\":true}` 立即直接呼出！"
        } else ""

        val devPrompt = buildString {
            append(baseDevPrompt)
            if (secretaryContextSuffix.isNotBlank()) {
                append("\n\n")
                append(secretaryContextSuffix)
            }
            if (pendingCallPromptHint.isNotBlank()) {
                append(pendingCallPromptHint)
            }
        }

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

            // Check if model invoked on-demand `search_my_context` or `read_today_calendar`!
            val finalCompletedAndActions = if (parsedFirst.contextSearchQuery != null || parsedFirst.readCalendarRequested) {
                val contextParts = ArrayList<String>()
                if (parsedFirst.contextSearchQuery != null) {
                    val query = parsedFirst.contextSearchQuery!!
                    _streamingFlow.value = _streamingFlow.value.copy(
                        statusHint = "正在按需检索个人知识库：$query",
                        markdownText = ""
                    )
                    contextParts.add(recallPersonalContext(query))
                }
                if (parsedFirst.readCalendarRequested) {
                    _streamingFlow.value = _streamingFlow.value.copy(
                        statusHint = "正在读取手机近期真实日历…",
                        markdownText = ""
                    )
                    contextParts.add(com.oms.client.capability.PhoneToolExecutor.readUpcomingCalendarSummary(appContext))
                }
                val recallBlock = contextParts.joinToString("\n\n")
                val augmentedTurns = turns + listOf(
                    LlmTurnMessage("assistant", firstPass.fullText),
                    LlmTurnMessage("user", "$recallBlock\n\n请基于以上检索到的真实个人背景与日程直接给出完整回答（无需再次调用 search_my_context 或 read_today_calendar）。")
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
            val rawCleanMd = parsedActions.cleanMarkdown.ifBlank { completed.fullText }
            val finalCleanMd = if (activeThreadForPrompt?.incognito == true) {
                rawCleanMd
            } else {
                extractAndApplySecretaryCommitments(rawCleanMd)
            }
            val mergedPhoneTools = if (isDirectUserCallConfirm && pendingCallAction != null) {
                val existingCalls = parsedActions.phoneToolActions.filter { it.tool == "phone_call" }
                if (existingCalls.isEmpty()) {
                    parsedActions.phoneToolActions + pendingCallAction.copy(
                        title = "正在呼叫：${pendingCallAction.target}",
                        detail = "已收到你的确认 · 正在直接拨出电话",
                        requiresConfirm = false
                    )
                } else {
                    parsedActions.phoneToolActions.map { act ->
                        if (act.tool == "phone_call") {
                            act.copy(
                                title = "正在呼叫：${act.target}",
                                detail = "已收到你的确认 · 正在直接拨出电话",
                                requiresConfirm = false
                            )
                        } else act
                    }
                }
            } else {
                parsedActions.phoneToolActions
            }
            chatRepo.upsertMessage(
                ChatMessageModel(
                    messageId = assistantMsgId,
                    threadId = threadId,
                    role = "assistant",
                    content = finalCleanMd,
                    reasoning = completed.fullReasoning,
                    modelUsed = completed.modelUsed,
                    sources = completed.sources,
                    calendarActions = parsedActions.calendarActions,
                    alarmActions = parsedActions.alarmActions,
                    phoneToolActions = mergedPhoneTools,
                    status = "DONE",
                    createdAtMs = System.currentTimeMillis()
                ),
                autoTitleFromFirstUserTurn = false
            )
            vibrateConfirm()
            _streamingFlow.value = StreamingTurnState()

            // Background L0 mirror & slow continuous living-memory evolution (every 3 turns)
            flushPendingSyncToMac()
            val thread = chatRepo.getThreadById(threadId)
            if (thread?.incognito == false && completedTurnsSinceDistill.incrementAndGet() >= 3) {
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
                if (recent.size < 2) return@runCatching
                val summary = recent.joinToString("\n") { "${it.role}: ${it.content.take(360)}" }
                val existingWm = (edgeStore.loadContextCapsule().recentWorkingMemory + chatRepo.getLocalWorkingMemory()).distinct()
                val existingProfile = _secretaryBriefFlow.value?.profileItems.orEmpty()
                val evolved = aiServices.distillMemoryEvolution(summary, existingWm, existingProfile)
                chatRepo.saveLocalWorkingMemory(evolved.workingMemory)
                flushPendingSyncToMac()

                // Slowly evolve long-term Living Memory & implicit commitments on Mac SecretaryCore
                if ((evolved.profileAdditions.isNotEmpty() || evolved.implicitCommitments.isNotEmpty()) &&
                    transportClient.activeTierFlow.value != "DISCONNECTED"
                ) {
                    val req = JSONObject().apply {
                        put("profileAdditions", JSONArray(evolved.profileAdditions))
                        val cArr = JSONArray()
                        evolved.implicitCommitments.forEach { (title, due) ->
                            cArr.put(JSONObject().put("title", title).put("dueHint", due))
                        }
                        put("implicitCommitments", cArr)
                    }
                    transportClient.callHostRpc("secretary.memory.evolve", req, 8_000L)
                    refreshSecretaryBrief()
                }
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
            val capturedMs = runCatching {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(card.capturedAtIso)?.time
            }.getOrNull() ?: System.currentTimeMillis()
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
                put("createdAtMs", capturedMs)
            }
            sendOrQueueVaultPromotion(itemId, payload)
        }

    private suspend fun sendOrQueueVaultPromotion(itemId: String, payload: JSONObject): Boolean {
        val targetMac = currentMacId()
        if (transportClient.activeTierFlow.value != "DISCONNECTED") {
            val outcome = runCatching {
                val res = transportClient.callHostRpc("memory.promote_vault", payload, 10_000L, targetMac)
                ChatSyncQueue.classifyHostRpcResponse(res)
            }.getOrDefault(SyncSendOutcome.RETRY_LATER)
            when (outcome) {
                SyncSendOutcome.DELIVERED -> {
                    vibrateConfirm()
                    return true
                }
                SyncSendOutcome.REJECTED_PERMANENT -> {
                    vibrateTick()
                    return false
                }
                SyncSendOutcome.RETRY_LATER -> Unit
            }
        }
        chatRepo.enqueuePendingVaultPromotion(itemId, payload, targetMac)
        vibrateTick()
        return false
    }

    fun flushPendingSyncToMac() {
        if (transportClient.activeTierFlow.value == "DISCONNECTED") return
        scope.launch { chatSync.flush() }
    }

    // =========================================================================
    // Chisu (尺素) Mac Library Sync, Dual-Doc Detail Cache & Inline Image Loader
    // =========================================================================

    private fun chisuCacheDir(macId: String = currentMacId()): File = MacRequestScope.directory(File(appContext.filesDir, "chisu_cache_v2"), macId)
    private fun chisuAssetCacheDir(macId: String = currentMacId()): File = MacRequestScope.directory(File(appContext.cacheDir, "chisu_assets_v2"), macId)

    fun refreshChisuArticles(limit: Int = 80) {
        if (transportClient.activeTierFlow.value == "DISCONNECTED") return
        val ticket = macScope.ticket()
        val generation = catalogGeneration.incrementAndGet()
        scope.launch(Dispatchers.Main.immediate) {
            _isRefreshingChisuFlow.value = true
            try {
                val resp = transportClient.callHostRpc("wechat.articles.list", JSONObject().put("limit", limit), 10_000L, ticket.macId)
                if (resp?.optBoolean("ok", false) == true && accepts(ticket) && generation == catalogGeneration.get()) {
                    File(chisuCacheDir(ticket.macId), "catalog.json").writeText(resp.toString())
                    applyChisuArticlesCatalogJson(resp, ticket.macId)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { }
            finally { if (accepts(ticket) && generation == catalogGeneration.get()) _isRefreshingChisuFlow.value = false }
        }
    }

    private fun applyChisuArticlesCatalogJson(resp: JSONObject, macId: String) {
        val arr = resp.optJSONArray("articles") ?: return
        val out = ArrayList<ChisuArticleItemUi>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("id", 0)
            val turnId = o.optInt("turnId", 0)
            if (id <= 0 && turnId <= 0) continue
            val entryKind = o.optString("entryKind", "").ifBlank { if (turnId > 0) "turn" else "article" }
            val mediaKind = o.optString("mediaKind", "").ifBlank { "article" }
            val rawTitle = o.optString("title", "")
            val summary = o.optString("shortSummary", "").trim()
            val digest = o.optString("digest", "").trim()
            val createdMs = ChisuDocumentParser.parseIsoToEpochMs(o.optString("createdAt", ""))
                .takeIf { it > 0L } ?: System.currentTimeMillis()
            val preview = ChisuDocumentParser.buildPlainExcerpt(summary.ifBlank { digest }, 220)
            val stableKey = if (turnId > 0 && entryKind == "turn") "turn_$turnId" else "wx_$id"
            val fallbackTitle = when {
                turnId > 0 && mediaKind == "video" -> "微信视频笔记 #$turnId"
                turnId > 0 && mediaKind == "image_note" -> "微信图片笔记 #$turnId"
                turnId > 0 && mediaKind == "idea" -> "微信灵感 #$turnId"
                turnId > 0 -> "微信记录 #$turnId"
                else -> "微信文章 #$id"
            }
            out.add(
                ChisuArticleItemUi(
                    stableKey = "$macId:$stableKey",
                    ownerMacId = macId,
                    articleId = id.takeIf { it > 0 },
                    turnId = turnId.takeIf { it > 0 },
                    entryKind = entryKind,
                    mediaKind = mediaKind,
                    title = ChisuDocumentParser.cleanTitle(rawTitle).ifBlank { fallbackTitle },
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
        if (currentMacId() == macId) _chisuArticlesFlow.value = out
    }

    fun openChisuArticle(item: ChisuArticleItemUi, forceRefresh: Boolean = false) {
        detailJob?.cancel()
        val generation = detailGeneration.incrementAndGet()
        val ticket = macScope.ticket()
        fun isCurrent() = accepts(ticket) && detailGeneration.get() == generation
        _chisuDetailErrorFlow.value = null
        val localSource = item.localSourceFilePath?.let { p ->
            runCatching { File(p).takeIf { it.exists() }?.readText(Charsets.UTF_8) }.getOrNull()
        }
        val ownsRemote = item.ownerMacId == ticket.macId && ticket.macId.isNotBlank()
        val cached = if (ownsRemote) loadCachedChisuDetail(item.articleId, item.turnId, ticket.macId) else null
        val initial = ChisuArticleDetailUi(
            stableKey = item.stableKey,
            ownerMacId = item.ownerMacId,
            articleId = item.articleId ?: cached?.articleId,
            turnId = item.turnId ?: cached?.turnId,
            entryKind = item.entryKind,
            mediaKind = item.mediaKind.ifBlank { cached?.mediaKind ?: "article" },
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

        val canQueryMac = ownsRemote && transportClient.activeTierFlow.value != "DISCONNECTED" &&
            (item.articleId != null || item.turnId != null || item.sourceUrl.isNotBlank() || item.title.isNotBlank())
        val needsRemote = forceRefresh || cached != null || initial.sourceMarkdown.isNullOrBlank() || initial.noteMarkdown.isNullOrBlank()
        if (!canQueryMac || !needsRemote) {
            _isLoadingChisuDetailFlow.value = false
            return
        }

        _isLoadingChisuDetailFlow.value = true
        detailJob = scope.launch(Dispatchers.Main.immediate) {
            try {
                val req = JSONObject().apply {
                    item.articleId?.let { put("articleId", it) }
                    item.turnId?.let { put("turnId", it) }
                    if (item.sourceUrl.isNotBlank()) put("sourceUrl", item.sourceUrl)
                    if (item.title.isNotBlank()) put("title", item.title)
                }
                val resp = transportClient.callHostRpc("wechat.article.detail", req, 15_000L, ticket.macId)
                if (!isCurrent()) return@launch
                if (resp != null && resp.optBoolean("ok", false)) {
                    val artObj = resp.optJSONObject("article")
                    val resolvedId = artObj?.optInt("id", 0)?.takeIf { it > 0 } ?: item.articleId
                    val resolvedTurnId = artObj?.optInt("turnId", 0)?.takeIf { it > 0 } ?: item.turnId
                    val resolvedEntryKind = artObj?.optString("entryKind", "").orEmpty().ifBlank { item.entryKind }
                    val resolvedMediaKind = artObj?.optString("mediaKind", "").orEmpty().ifBlank { item.mediaKind }
                    val noteMd = resp.optString("noteMarkdown", "").takeIf { it.isNotBlank() && it != "null" }
                    val sourceMd = resp.optString("sourceMarkdown", "").takeIf { it.isNotBlank() && it != "null" }
                    val summaryMd = resp.optString("summaryMarkdown", "").takeIf { it.isNotBlank() && it != "null" }
                    val remoteDetail = ChisuArticleDetailUi(
                        stableKey = item.stableKey,
                        ownerMacId = ticket.macId,
                        articleId = resolvedId,
                        turnId = resolvedTurnId,
                        entryKind = resolvedEntryKind,
                        mediaKind = resolvedMediaKind,
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
                    if (resolvedId != null || resolvedTurnId != null) {
                        saveCachedChisuDetail(resolvedId, resolvedTurnId, remoteDetail, ticket.macId)
                    }
                    if (_activeChisuDetailFlow.value?.stableKey == item.stableKey) {
                        _activeChisuDetailFlow.value = remoteDetail
                    }
                } else if (isCurrent() && initial.sourceMarkdown.isNullOrBlank() && initial.noteMarkdown.isNullOrBlank()) {
                    _chisuDetailErrorFlow.value = resp?.optString("error", "").orEmpty().ifBlank { "未能从 Mac 读取该记录详情" }
                }
            } catch (cancel: CancellationException) { throw cancel
            } catch (e: Exception) {
                if (isCurrent() && initial.sourceMarkdown.isNullOrBlank() && initial.noteMarkdown.isNullOrBlank()) {
                    _chisuDetailErrorFlow.value = e.message ?: "同步失败"
                }
            } finally {
                if (isCurrent()) _isLoadingChisuDetailFlow.value = false
            }
        }
    }

    fun closeChisuArticle() {
        detailGeneration.incrementAndGet()
        detailJob?.cancel()
        detailJob = null
        _activeChisuDetailFlow.value = null
        _isLoadingChisuDetailFlow.value = false
        _chisuDetailErrorFlow.value = null
    }

    suspend fun loadChisuInlineImageBytes(
        articleId: Int?,
        turnId: Int?,
        docKind: String,
        src: String,
        ownerMacId: String
    ): ByteArray? = withContext(Dispatchers.IO) {
        val ticket = macScope.ticket()
        val remoteAllowed = ownerMacId.isNotBlank() && ownerMacId == ticket.macId
        val trimmed = src.trim()
        if (trimmed.isEmpty()) return@withContext null
        val ownerTag = if (turnId != null && turnId > 0) "t$turnId" else "a${articleId ?: 0}"
        val cacheKey = "${ownerTag}_${docKind}_${MacRequestScope.key(trimmed)}"
        val cacheFile = File(chisuAssetCacheDir(ownerMacId), "$cacheKey.img")
        if (cacheFile.exists() && cacheFile.length() in 1..(4 * 1024 * 1024)) {
            val cached = runCatching { cacheFile.inputStream().use { it.readBounded(4 * 1024 * 1024) } }.getOrNull()
            return@withContext if (accepts(ticket)) cached else null
        }

        val bytes: ByteArray? = if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            runCatching {
                val client = proxyManager.getOrCreateOkHttpClient(connectTimeoutSec = 8, readTimeoutSec = 12)
                val req = okhttp3.Request.Builder().url(trimmed).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null
                    else resp.body?.let { body -> body.byteStream().use { it.readBounded(4 * 1024 * 1024, body.contentLength()) } }
                }
            }.getOrNull()
        } else if (remoteAllowed && ((turnId != null && turnId > 0) || (articleId != null && articleId > 0)) && transportClient.activeTierFlow.value != "DISCONNECTED") {
            runCatching {
                val reqObj = JSONObject().apply {
                    if (turnId != null && turnId > 0) {
                        put("turnId", turnId)
                    } else if (articleId != null && articleId > 0) {
                        put("articleId", articleId)
                        put("doc", if (docKind == "note") "note" else "source")
                    }
                    put("path", trimmed)
                }
                val resp = transportClient.callHostRpc("wechat.article.asset", reqObj, 10_000L, ticket.macId)
                val b64 = resp?.takeIf { it.optBoolean("ok", false) }?.optString("dataBase64", "").orEmpty()
                if (b64.isNotBlank() && b64.length <= 6 * 1024 * 1024) Base64.decode(b64, Base64.DEFAULT) else null
            }.getOrNull()
        } else {
            null
        }

        if (!accepts(ticket)) return@withContext null
        if (bytes != null && bytes.isNotEmpty()) {
            runCatching { cacheFile.writeBytes(bytes) }
        }
        bytes
    }

    private fun saveCachedChisuDetail(articleId: Int?, turnId: Int?, detail: ChisuArticleDetailUi, macId: String) {
        runCatching {
            val fileName = when {
                turnId != null && turnId > 0 -> "turn_$turnId.json"
                articleId != null && articleId > 0 -> "article_$articleId.json"
                else -> return
            }
            val file = File(chisuCacheDir(macId), fileName)
            val obj = JSONObject().apply {
                articleId?.takeIf { it > 0 }?.let { put("articleId", it) }
                turnId?.takeIf { it > 0 }?.let { put("turnId", it) }
                put("entryKind", detail.entryKind)
                put("mediaKind", detail.mediaKind)
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

    private fun loadCachedChisuDetail(articleId: Int?, turnId: Int? = null, macId: String): ChisuArticleDetailUi? {
        val fileName = when {
            turnId != null && turnId > 0 -> "turn_$turnId.json"
            articleId != null && articleId > 0 -> "article_$articleId.json"
            else -> return null
        }
        return runCatching {
            val file = File(chisuCacheDir(macId), fileName)
            if (!file.exists()) return null
            val o = JSONObject(file.readText(Charsets.UTF_8))
            val resolvedArticleId = o.optInt("articleId", 0).takeIf { it > 0 } ?: articleId
            val resolvedTurnId = o.optInt("turnId", 0).takeIf { it > 0 } ?: turnId
            ChisuArticleDetailUi(
                stableKey = if (resolvedTurnId != null && resolvedTurnId > 0) "turn_$resolvedTurnId" else "wx_${resolvedArticleId ?: 0}",
                articleId = resolvedArticleId,
                turnId = resolvedTurnId,
                entryKind = o.optString("entryKind", if (resolvedTurnId != null && resolvedTurnId > 0) "turn" else "article"),
                mediaKind = o.optString("mediaKind", "article"),
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
    // Omni-Search (Full Vault FTS5 + Chisu + Chat) & Executive Secretary Core
    // =========================================================================

    private fun secretaryBriefCacheFile(): File = File(appContext.filesDir, "secretary_brief_cache.json")

    private fun buildSecretaryMemoryPromptBlock(): String {
        val brief = _secretaryBriefFlow.value ?: return ""
        val parts = ArrayList<String>()
        if (brief.profileItems.isNotEmpty()) {
            parts.add("长期个人偏好：\n" + brief.profileItems.take(8).joinToString("\n") { "- $it" })
        }
        if (brief.openLoops.isNotEmpty()) {
            parts.add(
                "当前未闭环事项（Open Loops）：\n" +
                    brief.openLoops.take(6).joinToString("\n") {
                        "- ${it.title}${if (it.dueHint.isNotBlank()) "（${it.dueHint}）" else ""}"
                    }
            )
        }
        if (brief.vaultFocusTitles.isNotEmpty()) {
            parts.add("近期知识库聚焦主题：${brief.vaultFocusTitles.take(5).joinToString("、")}")
        }
        if (parts.isEmpty()) return ""
        return """
            |<PERSONAL_SECRETARY_MEMORY>
            |你是用户的长期随身助理，拥有跨对话的持续记忆。回答时自然承接以下背景，不要机械复述。
            |当用户明确表达某项待办/承诺（如“提醒我这周……”“记一下明天……”）时，可在回复末尾追加一行隐藏指令 `[COMMITMENT:精炼事项标题|时间提示]`（无时间提示可留空），系统会自动记入今日简报的未闭环事项。
            |${parts.joinToString("\n")}
            |</PERSONAL_SECRETARY_MEMORY>
        """.trimMargin()
    }

    private fun extractAndApplySecretaryCommitments(markdown: String): String {
        val re = Regex("""\[COMMITMENT:([^|\]]+)(?:\|([^\]]*))?\]""")
        val matches = re.findAll(markdown).toList()
        if (matches.isEmpty()) return markdown
        for (m in matches) {
            val title = m.groupValues.getOrNull(1)?.trim().orEmpty()
            val due = m.groupValues.getOrNull(2)?.trim().orEmpty()
            if (title.isNotBlank()) {
                addCommitment(title, due, source = "chat_auto")
            }
        }
        return markdown.replace(re, "").trimEnd()
    }

    fun searchOmniContext(query: String, limit: Int = 14) {
        val q = query.trim()
        if (q.length < 2) {
            _omniSearchHitsFlow.value = emptyList()
            _isOmniSearchingFlow.value = false
            return
        }
        if (transportClient.activeTierFlow.value == "DISCONNECTED") {
            _omniSearchHitsFlow.value = emptyList()
            _isOmniSearchingFlow.value = false
            return
        }
        scope.launch {
            _isOmniSearchingFlow.value = true
            try {
                val resp = transportClient.callHostRpc(
                    "memory.search_vault",
                    JSONObject().put("query", q).put("limit", limit),
                    6_000L
                )
                val arr = resp?.optJSONArray("hits")
                val out = ArrayList<OmniSearchHitUi>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val h = arr.optJSONObject(i) ?: continue
                        val src = h.optString("source", "vault")
                        if (src == "mobile_chat") continue // local chat FTS already covers mobile threads instantly
                        out.add(
                            OmniSearchHitUi(
                                source = src,
                                title = h.optString("title", "知识库文档"),
                                pathOrRef = h.optString("pathOrRef", ""),
                                snippet = h.optString("snippet", ""),
                                heading = h.optString("heading", ""),
                                category = h.optString("category", if (src == "chisu") "尺素" else "知识库"),
                                articleId = h.optInt("articleId", 0).takeIf { it > 0 },
                                turnId = h.optInt("turnId", 0).takeIf { it > 0 }
                            )
                        )
                    }
                }
                _omniSearchHitsFlow.value = out
            } catch (_: Throwable) {
            } finally {
                _isOmniSearchingFlow.value = false
            }
        }
    }

    fun openVaultDoc(relPath: String) {
        val clean = relPath.trim()
        if (clean.isEmpty() || transportClient.activeTierFlow.value == "DISCONNECTED") return
        scope.launch {
            _isLoadingVaultDocFlow.value = true
            try {
                val resp = transportClient.callHostRpc(
                    "memory.read_vault_doc",
                    JSONObject().put("relPath", clean),
                    10_000L
                )
                if (resp != null && resp.optBoolean("ok", false)) {
                    _activeVaultDocFlow.value = VaultDocPreviewUi(
                        relPath = resp.optString("relPath", clean),
                        title = resp.optString("title", clean.substringAfterLast('/')),
                        category = resp.optString("category", "知识库"),
                        markdown = resp.optString("markdown", ""),
                        truncated = resp.optBoolean("truncated", false)
                    )
                }
            } catch (_: Throwable) {
            } finally {
                _isLoadingVaultDocFlow.value = false
            }
        }
    }

    fun closeVaultDoc() {
        _activeVaultDocFlow.value = null
        _isLoadingVaultDocFlow.value = false
    }

    fun refreshSecretaryBrief() {
        if (transportClient.activeTierFlow.value == "DISCONNECTED") return
        scope.launch {
            _isRefreshingBriefFlow.value = true
            try {
                val resp = transportClient.callHostRpc("secretary.brief", JSONObject(), 8_000L)
                val briefObj = resp?.takeIf { it.optBoolean("ok", false) }?.optJSONObject("brief")
                if (briefObj != null) {
                    runCatching { secretaryBriefCacheFile().writeText(briefObj.toString(), Charsets.UTF_8) }
                    _secretaryBriefFlow.value = parseSecretaryBriefJson(briefObj)
                }
            } catch (_: Throwable) {
            } finally {
                _isRefreshingBriefFlow.value = false
            }
        }
    }

    fun addCommitment(title: String, dueHint: String = "", source: String = "mobile") {
        val clean = title.trim()
        if (clean.isEmpty()) return
        val localTemp = SecretaryCommitmentUi(
            id = "local_${System.currentTimeMillis()}",
            title = clean,
            dueHint = dueHint.trim(),
            source = source,
            status = "open",
            createdAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis()
        )
        _secretaryBriefFlow.value?.let { cur ->
            _secretaryBriefFlow.value = cur.copy(openLoops = listOf(localTemp) + cur.openLoops)
        }
        if (transportClient.activeTierFlow.value == "DISCONNECTED") return
        scope.launch {
            runCatching {
                transportClient.callHostRpc(
                    "secretary.commitment.upsert",
                    JSONObject().put("title", clean).put("dueHint", dueHint.trim()).put("source", source),
                    6_000L
                )
                refreshSecretaryBrief()
            }
        }
    }

    fun toggleCommitment(id: String) {
        _secretaryBriefFlow.value?.let { cur ->
            _secretaryBriefFlow.value = cur.copy(openLoops = cur.openLoops.filterNot { it.id == id })
        }
        if (transportClient.activeTierFlow.value == "DISCONNECTED") return
        scope.launch {
            runCatching {
                transportClient.callHostRpc("secretary.commitment.toggle", JSONObject().put("id", id), 6_000L)
                refreshSecretaryBrief()
            }
        }
    }

    fun saveSecretaryProfile(items: List<String>) {
        val cleaned = items.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(24)
        _secretaryBriefFlow.value?.let { cur ->
            _secretaryBriefFlow.value = cur.copy(profileItems = cleaned)
        }
        if (transportClient.activeTierFlow.value == "DISCONNECTED") return
        scope.launch {
            runCatching {
                val arr = JSONArray()
                cleaned.forEach { arr.put(it) }
                transportClient.callHostRpc("secretary.profile.save", JSONObject().put("items", arr), 6_000L)
                refreshSecretaryBrief()
            }
        }
    }

    fun dismissSecretaryNudge(id: String) {
        _secretaryBriefFlow.value?.let { cur ->
            _secretaryBriefFlow.value = cur.copy(nudges = cur.nudges.filterNot { it.id == id })
        }
        if (transportClient.activeTierFlow.value == "DISCONNECTED") return
        scope.launch {
            runCatching {
                transportClient.callHostRpc("secretary.nudge.dismiss", JSONObject().put("id", id), 5_000L)
            }
        }
    }

    private fun loadCachedSecretaryBrief(): SecretaryBriefUi? = runCatching {
        val f = secretaryBriefCacheFile()
        if (!f.exists()) return null
        parseSecretaryBriefJson(JSONObject(f.readText(Charsets.UTF_8)))
    }.getOrNull()

    private fun parseSecretaryBriefJson(o: JSONObject): SecretaryBriefUi {
        val profile = ArrayList<String>()
        o.optJSONArray("profileItems")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s.isNotBlank()) profile.add(s)
            }
        }
        val loops = ArrayList<SecretaryCommitmentUi>()
        o.optJSONArray("openLoops")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                loops.add(
                    SecretaryCommitmentUi(
                        id = c.optString("id", ""),
                        title = c.optString("title", ""),
                        dueHint = c.optString("dueHint", ""),
                        source = c.optString("source", ""),
                        status = c.optString("status", "open"),
                        createdAtMs = c.optLong("createdAtMs", 0L),
                        updatedAtMs = c.optLong("updatedAtMs", 0L)
                    )
                )
            }
        }
        val nudges = ArrayList<SecretaryNudgeUi>()
        o.optJSONArray("nudges")?.let { arr ->
            for (i in 0 until arr.length()) {
                val n = arr.optJSONObject(i) ?: continue
                nudges.add(
                    SecretaryNudgeUi(
                        id = n.optString("id", ""),
                        kind = n.optString("kind", ""),
                        title = n.optString("title", ""),
                        subtitle = n.optString("subtitle", ""),
                        actionLabel = n.optString("actionLabel", "查看"),
                        actionType = n.optString("actionType", "ask_chat"),
                        actionPayload = n.optString("actionPayload", "")
                    )
                )
            }
        }
        val focus = ArrayList<String>()
        o.optJSONArray("vaultFocusTitles")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s.isNotBlank()) focus.add(s)
            }
        }
        return SecretaryBriefUi(
            greeting = o.optString("greeting", "你好"),
            dateLabel = o.optString("dateLabel", ""),
            headline = o.optString("headline", ""),
            profileItems = profile,
            openLoops = loops,
            nudges = nudges,
            recentChisuCount = o.optInt("recentChisuCount", 0),
            activeAgentCount = o.optInt("activeAgentCount", 0),
            vaultFocusTitles = focus,
            generatedAtMs = o.optLong("generatedAtMs", 0L)
        )
    }

    // =========================================================================
    // Milestone 3: Mac Heavy-Brain Hub (Codex & Antigravity Monitor / Dispatch / Resume)
    // =========================================================================

    fun refreshAgentSessions() {
        if (transportClient.activeTierFlow.value == "DISCONNECTED") return
        val ticket = macScope.ticket()
        scope.launch {
            runCatching {
                val resp = transportClient.callHostRpc(
                    "agent.workspace_catalog",
                    JSONObject().put("limit", 96),
                    12_000L, ticket.macId
                ) ?: return@launch
                if (!accepts(ticket)) return@launch
                val activeMacId = ticket.macId
                edgeStore.saveCachedWorkspaceCatalogJson(resp.toString())
                if (activeMacId.isNotBlank()) {
                    runCatching {
                        File(appContext.filesDir, "agent_catalog_${activeMacId}.json").writeText(resp.toString(), Charsets.UTF_8)
                    }
                }
                withContext(Dispatchers.Main.immediate) { if (accepts(ticket)) applyWorkspaceCatalogJson(resp) }
            }
        }
    }

    fun loadCachedCatalogForMac(macId: String) {
        if (macScope.switchTo(macId)) {
            closeChisuArticle()
            catalogGeneration.incrementAndGet()
            _isRefreshingChisuFlow.value = false
            _chisuArticlesFlow.value = emptyList()
            _selectedAgentSessionFlow.value = null
            _selectedAgentStepsFlow.value = emptyList()
            _agentProjectsFlow.value = emptyList()
        }
        if (macId.isBlank()) return
        chatRepo.bindLegacyPromotions(macId)
        val catalog = File(chisuCacheDir(macId), "catalog.json")
        if (catalog.exists()) runCatching { applyChisuArticlesCatalogJson(JSONObject(catalog.readText()), macId) }
        val file = File(appContext.filesDir, "agent_catalog_${macId}.json")
        if (file.exists()) {
            runCatching { applyWorkspaceCatalogJson(JSONObject(file.readText(Charsets.UTF_8))) }
        } else {
            _agentSessionsFlow.value = emptyList()
            _agentQuotasFlow.value = null
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
            workbuddy = if (qObj.has("workbuddy")) parseBackend("workbuddy") else null,
            opencode = if (qObj.has("opencode")) parseBackend("opencode") else null,
            fetchedAtMs = qObj.optLong("fetchedAtMs", System.currentTimeMillis())
        )
    }

    // =========================================================================
    // v3.6 Standalone Memory Center & Remote Mac System Control
    // =========================================================================

    private fun memoryCenterCacheFile(): File = File(appContext.filesDir, "memory_center_cache.json")

    private fun parseLivingEntryJson(o: JSONObject, defaultCat: String): LivingMemoryEntryUi {
        return LivingMemoryEntryUi(
            id = o.optString("id", "mem_${o.optString("text").hashCode()}"),
            text = o.optString("text", "").trim(),
            source = o.optString("source", "manual"),
            category = o.optString("category", defaultCat),
            originDetail = o.optString("originDetail", ""),
            observations = o.optInt("observations", 1).coerceAtLeast(1),
            createdAtMs = o.optLong("createdAtMs", 0L),
            lastSeenAtMs = o.optLong("lastSeenAtMs", 0L)
        )
    }

    private fun parseMemoryCenterSnapshotJson(o: JSONObject): MemoryCenterSnapshotUi {
        fun parseList(key: String, defCat: String): List<LivingMemoryEntryUi> {
            val arr = o.optJSONArray(key) ?: return emptyList()
            val out = ArrayList<LivingMemoryEntryUi>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val parsed = parseLivingEntryJson(item, defCat)
                if (parsed.text.isNotBlank()) out.add(parsed)
            }
            return out
        }
        val remoteWm = parseList("workingEntries", "working")
        val localWmTexts = (edgeStore.loadContextCapsule().recentWorkingMemory + chatRepo.getLocalWorkingMemory()).distinct()
        val mergedWorking = if (remoteWm.isNotEmpty()) {
            val existingTexts = remoteWm.map { it.text }.toSet()
            val extras = localWmTexts.filterNot { existingTexts.contains(it) }.map { txt ->
                LivingMemoryEntryUi(
                    id = "wm_local_${txt.hashCode()}",
                    text = txt,
                    source = "auto",
                    category = "working",
                    originDetail = "手机端近期对话自动提炼 (L1)"
                )
            }
            remoteWm + extras
        } else {
            localWmTexts.map { txt ->
                LivingMemoryEntryUi(
                    id = "wm_local_${txt.hashCode()}",
                    text = txt,
                    source = "auto",
                    category = "working",
                    originDetail = "手机端近期对话自动提炼 (L1)"
                )
            }
        }
        return MemoryCenterSnapshotUi(
            profileEntries = parseList("profileEntries", "profile"),
            workingEntries = mergedWorking,
            vaultAnchors = parseList("vaultAnchors", "vault"),
            forgottenCount = o.optInt("forgottenCount", 0),
            updatedAtMs = o.optLong("updatedAtMs", System.currentTimeMillis())
        )
    }

    private fun loadCachedMemoryCenter(): MemoryCenterSnapshotUi? = runCatching {
        val f = memoryCenterCacheFile()
        if (!f.exists()) return null
        parseMemoryCenterSnapshotJson(JSONObject(f.readText(Charsets.UTF_8)))
    }.getOrNull()

    private fun syncLocalFallbackMemoryCenter() {
        val brief = _secretaryBriefFlow.value
        val profile = brief?.profileItems.orEmpty().map { txt ->
            LivingMemoryEntryUi(
                id = "prof_local_${txt.hashCode()}",
                text = txt,
                source = "manual",
                category = "profile",
                originDetail = "本地缓存长期画像"
            )
        }
        val wm = (edgeStore.loadContextCapsule().recentWorkingMemory + chatRepo.getLocalWorkingMemory())
            .distinct()
            .map { txt ->
                LivingMemoryEntryUi(
                    id = "wm_local_${txt.hashCode()}",
                    text = txt,
                    source = "auto",
                    category = "working",
                    originDetail = "手机端近期对话自动提炼 (L1)"
                )
            }
        val vault = brief?.vaultFocusTitles.orEmpty().map { title ->
            LivingMemoryEntryUi(
                id = "vault_local_${title.hashCode()}",
                text = title,
                source = "auto",
                category = "vault",
                originDetail = "个人知识库近期主题"
            )
        }
        _memoryCenterFlow.value = MemoryCenterSnapshotUi(
            profileEntries = profile,
            workingEntries = wm,
            vaultAnchors = vault,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    fun refreshMemoryCenter() {
        if (transportClient.activeTierFlow.value == "DISCONNECTED") {
            if (_memoryCenterFlow.value.profileEntries.isEmpty() && _memoryCenterFlow.value.workingEntries.isEmpty()) {
                syncLocalFallbackMemoryCenter()
            }
            return
        }
        scope.launch {
            _isRefreshingMemoryCenterFlow.value = true
            try {
                val resp = transportClient.callHostRpc("memory.center.get", JSONObject(), 8_000L)
                val snapObj = resp?.takeIf { it.optBoolean("ok", false) }?.optJSONObject("snapshot")
                if (snapObj != null) {
                    runCatching { memoryCenterCacheFile().writeText(snapObj.toString(), Charsets.UTF_8) }
                    _memoryCenterFlow.value = parseMemoryCenterSnapshotJson(snapObj)
                }
            } catch (_: Throwable) {
            } finally {
                _isRefreshingMemoryCenterFlow.value = false
            }
        }
    }

    fun upsertMemoryCenterEntry(
        id: String? = null,
        text: String,
        source: String = "manual",
        originDetail: String = "手动添加"
    ) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val now = System.currentTimeMillis()
        val localEntry = LivingMemoryEntryUi(
            id = id ?: "mem_${clean.hashCode()}_$now",
            text = clean,
            source = source,
            category = "profile",
            originDetail = originDetail,
            observations = 1,
            createdAtMs = now,
            lastSeenAtMs = now
        )
        val cur = _memoryCenterFlow.value
        val updatedProfile = if (id != null && cur.profileEntries.any { it.id == id }) {
            cur.profileEntries.map { if (it.id == id) localEntry else it }
        } else {
            listOf(localEntry) + cur.profileEntries.filterNot { it.text == clean }
        }
        _memoryCenterFlow.value = cur.copy(profileEntries = updatedProfile, updatedAtMs = now)
        _secretaryBriefFlow.value?.let { b ->
            _secretaryBriefFlow.value = b.copy(profileItems = updatedProfile.map { it.text })
        }

        if (transportClient.activeTierFlow.value == "DISCONNECTED") return
        scope.launch {
            runCatching {
                val req = JSONObject()
                    .put("text", clean)
                    .put("source", source)
                    .put("originDetail", originDetail)
                if (!id.isNullOrBlank()) req.put("id", id)
                transportClient.callHostRpc("memory.entry.upsert", req, 6_000L)
                refreshMemoryCenter()
                refreshSecretaryBrief()
            }
        }
    }

    fun deleteMemoryCenterEntry(entry: LivingMemoryEntryUi) {
        val cur = _memoryCenterFlow.value
        when (entry.category) {
            "working" -> {
                val keptWm = cur.workingEntries.filterNot { it.id == entry.id || it.text == entry.text }
                _memoryCenterFlow.value = cur.copy(workingEntries = keptWm)
                val localWm = chatRepo.getLocalWorkingMemory().filterNot { it == entry.text }
                chatRepo.saveLocalWorkingMemory(localWm)
            }
            "vault" -> {
                val keptVault = cur.vaultAnchors.filterNot { it.id == entry.id || it.text == entry.text }
                _memoryCenterFlow.value = cur.copy(
                    vaultAnchors = keptVault,
                    forgottenCount = cur.forgottenCount + 1
                )
            }
            else -> {
                val keptProf = cur.profileEntries.filterNot { it.id == entry.id || it.text == entry.text }
                _memoryCenterFlow.value = cur.copy(
                    profileEntries = keptProf,
                    forgottenCount = cur.forgottenCount + 1
                )
                _secretaryBriefFlow.value?.let { b ->
                    _secretaryBriefFlow.value = b.copy(profileItems = keptProf.map { it.text })
                }
            }
        }

        if (transportClient.activeTierFlow.value == "DISCONNECTED") return
        scope.launch {
            runCatching {
                transportClient.callHostRpc(
                    "memory.entry.delete",
                    JSONObject()
                        .put("id", entry.id)
                        .put("text", entry.text)
                        .put("category", entry.category),
                    6_000L
                )
                refreshMemoryCenter()
                refreshSecretaryBrief()
            }
        }
    }

    private fun parseMacControlStatusJson(o: JSONObject): MacControlStatusUi {
        val appsArr = o.optJSONArray("installedApps") ?: JSONArray()
        val apps = ArrayList<MacInstalledAppUi>(appsArr.length())
        for (i in 0 until appsArr.length()) {
            val a = appsArr.optJSONObject(i) ?: continue
            val name = a.optString("name", "").trim()
            if (name.isEmpty()) continue
            apps.add(
                MacInstalledAppUi(
                    name = name,
                    bundlePath = a.optString("bundlePath", ""),
                    running = a.optBoolean("running", false)
                )
            )
        }
        return MacControlStatusUi(
            macId = o.optString("macId", ""),
            macName = o.optString("macName", "Mac"),
            screenLocked = o.optBoolean("screenLocked", false),
            stayAwakeEnabled = o.optBoolean("stayAwakeEnabled", false),
            unlockPasswordConfigured = o.optBoolean("unlockPasswordConfigured", false),
            accessibilityTrusted = o.optBoolean("accessibilityTrusted", false),
            volumePct = o.optInt("volumePct", 50),
            muted = o.optBoolean("muted", false),
            frontmostApp = o.optString("frontmostApp", ""),
            installedApps = apps,
            updatedAtMs = o.optLong("updatedAtMs", System.currentTimeMillis())
        )
    }

    fun refreshMacControlStatus(refreshApps: Boolean = false) {
        if (transportClient.activeTierFlow.value == "DISCONNECTED") return
        scope.launch {
            runCatching {
                val resp = transportClient.callHostRpc(
                    "mac.control.status",
                    JSONObject().put("refreshApps", refreshApps),
                    6_000L
                )
                val stObj = resp?.takeIf { it.optBoolean("ok", false) }?.optJSONObject("status")
                if (stObj != null) {
                    _macControlStatusFlow.value = parseMacControlStatusJson(stObj)
                }
            }
        }
    }

    suspend fun saveMacUnlockPassword(password: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val clean = password.trim()
        if (clean.isEmpty()) return@withContext false to "密码不能为空"
        if (transportClient.activeTierFlow.value == "DISCONNECTED") {
            return@withContext false to "请先连接目标 Mac 后再保存钥匙串解锁密码"
        }
        val resp = transportClient.callHostRpc(
            "mac.control.set_unlock_password",
            JSONObject().put("password", clean),
            8_000L
        ) ?: return@withContext false to "Mac 响应超时"
        resp.optJSONObject("status")?.let { _macControlStatusFlow.value = parseMacControlStatusJson(it) }
        val ok = resp.optBoolean("ok", false)
        ok to (if (ok) "已安全加密保存至 Mac 系统钥匙串（不明文落盘）" else "钥匙串写入失败")
    }

    suspend fun clearMacUnlockPassword(): Boolean = withContext(Dispatchers.IO) {
        val resp = transportClient.callHostRpc("mac.control.clear_unlock_password", JSONObject(), 6_000L)
        resp?.optJSONObject("status")?.let { _macControlStatusFlow.value = parseMacControlStatusJson(it) }
        resp?.optBoolean("ok", false) == true
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

    // =========================================================================
    // v3.6.1 Browser Companion Auto-Curate -> Vault + Unified Cross-AI Context
    // =========================================================================

    fun canonicalizeBrowserUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ""
        return runCatching {
            val u = android.net.Uri.parse(trimmed)
            val host = (u.host ?: "").lowercase()
            if (host == "youtu.be") {
                val vid = u.pathSegments.firstOrNull()?.trim().orEmpty()
                if (vid.length in 6..20) return@runCatching "https://www.youtube.com/watch?v=$vid"
            }
            if (host == "youtube.com" || host.endsWith(".youtube.com")) {
                if (u.path == "/watch") {
                    val vid = u.getQueryParameter("v")?.trim().orEmpty()
                    if (vid.length in 6..20) return@runCatching "https://www.youtube.com/watch?v=$vid"
                }
                val segs = u.pathSegments
                if (segs.size >= 2 && (segs[0] == "shorts" || segs[0] == "live")) {
                    return@runCatching "https://www.youtube.com/${segs[0]}/${segs[1]}"
                }
            }
            u.buildUpon().fragment(null).build().toString()
        }.getOrDefault(trimmed)
    }

    fun curateBrowserContentToKnowledgeBase(
        url: String,
        title: String,
        author: String = "",
        extractedText: String = "",
        userNote: String = "",
        captureMode: String = "manual",
        onComplete: ((Boolean, String) -> Unit)? = null
    ) {
        val canonicalUrl = canonicalizeBrowserUrl(url)
        if (canonicalUrl.isBlank() || !canonicalUrl.startsWith("http")) {
            onComplete?.invoke(false, "当前页面不是可归档的网页或视频链接")
            return
        }
        val cleanTitle = title.replace(Regex("""\s*-\s*YouTube\s*$""", RegexOption.IGNORE_CASE), "").trim().ifBlank { canonicalUrl }
        val isVideo = canonicalUrl.contains("youtube.com/") || canonicalUrl.contains("bilibili.com/video") || canonicalUrl.contains("douyin.com/")
        val kindLabel = if (isVideo) "视频" else "网页"

        scope.launch {
            val reqJson = JSONObject().apply {
                put("url", canonicalUrl)
                put("title", cleanTitle)
                if (author.isNotBlank()) put("author", author.trim())
                if (extractedText.isNotBlank()) put("extractedText", extractedText.take(18000))
                if (userNote.isNotBlank()) put("userNote", userNote.trim())
                put("captureMode", captureMode)
            }

            if (transportClient.activeTierFlow.value != "DISCONNECTED") {
                val resp = transportClient.callHostRpc("chisu.curate_url", reqJson, 125_000L)
                if (resp != null && resp.optBoolean("ok", false)) {
                    val resolvedTitle = resp.optString("title", cleanTitle).ifBlank { cleanTitle }
                    val shortSummary = resp.optString("shortSummary", "").replace(Regex("\\s+"), " ").trim().take(130)
                    val relNote = resp.optString("relNotePath", "").trim()
                    val wmLine = buildString {
                        append("[伴读沉淀·$kindLabel] 《${resolvedTitle.take(48)}》")
                        if (shortSummary.isNotBlank()) append("：$shortSummary")
                        if (relNote.isNotBlank()) append(" (知识库: $relNote)")
                    }
                    prependPhoneWorkingMemory(wmLine, resolvedTitle.take(20))
                    edgeStore.markBrowserUrlCurated(canonicalUrl)
                    refreshChisuArticles()
                    refreshMemoryCenter()
                    onComplete?.invoke(true, "已提炼《${resolvedTitle.take(24)}》存入知识库，全端 AI 上下文已同步")
                    return@launch
                }
            }

            // Offline fallback: summarize locally on phone so phone AI knows immediately, and queue for Mac Vault sync!
            val localSummary = withContext(Dispatchers.IO) {
                runCatching {
                    val resp = llmEngine.completeSync(
                        intent = RouteIntent.AUTO,
                        developerPrompt = "请用不超过90字提炼用户刚刚查看的视频或文章核心价值点（纯中文一句话，不要标题或列表符号）。",
                        turns = listOf(
                            LlmTurnMessage(
                                "user",
                                "标题：$cleanTitle\n链接：$canonicalUrl\n作者：$author\n内容片段：\n${extractedText.take(3500)}"
                            )
                        )
                    )
                    resp.fullText.replace(Regex("\\s+"), " ").trim().take(110)
                }.getOrDefault(extractedText.replace(Regex("\\s+"), " ").trim().take(90))
            }

            val wmOffline = buildString {
                append("[伴读沉淀·$kindLabel] 《${cleanTitle.take(48)}》")
                if (localSummary.isNotBlank()) append("：$localSummary")
            }
            prependPhoneWorkingMemory(wmOffline, cleanTitle.take(20))
            edgeStore.markBrowserUrlCurated(canonicalUrl)
            edgeStore.enqueuePendingBrowserCurate(reqJson)
            syncLocalFallbackMemoryCenter()
            onComplete?.invoke(true, "随身助理已记住核心要点，连上电脑后将自动归入知识库")
        }
    }

    private fun prependPhoneWorkingMemory(entry: String, titleHint: String) {
        val existing = chatRepo.getLocalWorkingMemory()
        val hintLower = titleHint.trim().lowercase()
        val next = listOf(entry) + existing.filterNot { item ->
            item == entry || (hintLower.length >= 5 && item.lowercase().contains(hintLower))
        }
        chatRepo.saveLocalWorkingMemory(next.take(12))
    }

    private fun flushPendingBrowserCurates() {
        val pending = edgeStore.drainPendingBrowserCurates()
        if (pending.isEmpty()) return
        scope.launch {
            for (item in pending) {
                val resp = transportClient.callHostRpc("chisu.curate_url", item, 125_000L)
                if (resp == null || !resp.optBoolean("ok", false)) {
                    edgeStore.enqueuePendingBrowserCurate(item)
                }
            }
            refreshChisuArticles()
            refreshMemoryCenter()
        }
    }
}
