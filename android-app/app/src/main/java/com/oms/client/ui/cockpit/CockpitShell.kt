package com.oms.client.ui.cockpit

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.oms.client.OmsApplication
import com.oms.client.agent.OmsAccessibilityService
import com.oms.client.capability.NotificationExecutor
import com.oms.client.capability.SpeechAudioPolicy
import com.oms.client.cockpit.ChatMessageModel
import com.oms.client.cockpit.ChatThreadModel
import com.oms.client.cockpit.ChisuArticleItemUi
import com.oms.client.cockpit.ChisuDocumentParser
import com.oms.client.cockpit.CockpitRuntimeCoordinator
import com.oms.client.cockpit.VaultDocPreviewUi
import com.oms.client.ui.markdown.IncrementalMarkdownView
import com.oms.client.edge.StandbyLeaseState
import com.oms.client.transport.OtaUiState
import com.oms.client.ui.theme.OmsAppearanceMode
import com.oms.client.ui.theme.OmsCircleIconButton
import com.oms.client.ui.theme.OmsFeedbackController
import com.oms.client.ui.theme.OmsFloatingToastHost
import com.oms.client.ui.theme.OmsIcons
import com.oms.client.ui.theme.OmsPrimaryButton
import com.oms.client.ui.theme.OmsSecondaryButton
import com.oms.client.ui.theme.OmsSegmentItem
import com.oms.client.ui.theme.OmsSlidingSegmentedBar
import com.oms.client.ui.theme.OmsSurfaceCard
import com.oms.client.ui.theme.OmsTheme
import com.oms.client.ui.theme.OmsThemeController
import com.oms.client.ui.theme.OmsThemeStyle
import com.oms.client.ui.theme.OmsToastKind
import com.oms.client.ui.theme.omsBounceClick
import com.oms.client.ui.theme.omsCombinedBounce
import com.oms.core.ai.FieldGuideSpecimenCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

enum class CockpitTab {
    CHAT,
    CHISU,
    MISSION_HUB,
    WEB_APPS
}

data class SearchJumpRequest(
    val threadId: String,
    val messageId: String,
    val keyword: String,
    val nonce: Long = System.nanoTime()
)

/**
 * v3.5 Root Flagship Shell:
 * - Minimalist Contextual Top Bar + Bottom 4-Tab Bar (`工作台 · 尺素 · 协同 · 应用舱`)
 * - Auto-hiding Bottom Bar on deep immersive screens (Chat Thread, Chisu Reader, Agent Detail, Active WebView)
 * - IDE-Grade Unified Navigation Drawer (Flat Collapsible Mac Project Tree + Chat History)
 * - Decoupled Full-Capability Workbench Home (`工作台`) + 120Hz Floating Pill Composer
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmsCockpitRootScreen() {
    val context = LocalContext.current
    val density = LocalDensity.current
    val palette = OmsTheme.colors
    val app = OmsApplication.instance ?: return
    val coordinator = app.cockpitCoordinator
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val themeSettings by OmsThemeController.settingsFlow.collectAsState()

    val activeTier by app.transportClient.activeTierFlow.collectAsState()
    val streamItems by app.repository.itemsFlow.collectAsState()
    val standbyLease by app.wechatStandby.leaseStateFlow.collectAsState()
    val threads by coordinator.chatRepo.threadsFlow.collectAsState()
    val activeThreadId by coordinator.chatRepo.activeThreadIdFlow.collectAsState()
    val activeMessages by coordinator.chatRepo.activeMessagesFlow.collectAsState()
    val agentProjects by coordinator.agentProjectsFlow.collectAsState()
    val agentSessions by coordinator.agentSessionsFlow.collectAsState()
    val agentQuotas by coordinator.agentQuotasFlow.collectAsState()
    val selectedAgentSession by coordinator.selectedAgentSessionFlow.collectAsState()
    val activeChisuDetail by coordinator.activeChisuDetailFlow.collectAsState()
    val chisuArticles by coordinator.chisuArticlesFlow.collectAsState()
    val secretaryBrief by coordinator.secretaryBriefFlow.collectAsState()
    val isRefreshingBrief by coordinator.isRefreshingBriefFlow.collectAsState()
    val otaState by app.transportClient.otaStateFlow.collectAsState()
    val isSpeakingTts by NotificationExecutor.isSpeakingFlow.collectAsState()
    val speakingTtsText by NotificationExecutor.speakingTextFlow.collectAsState()
    val omniSearchHits by coordinator.omniSearchHitsFlow.collectAsState()
    val isOmniSearching by coordinator.isOmniSearchingFlow.collectAsState()
    val activeVaultDoc by coordinator.activeVaultDocFlow.collectAsState()
    val isLoadingVaultDoc by coordinator.isLoadingVaultDocFlow.collectAsState()

    var currentTab by remember { mutableStateOf(CockpitTab.CHAT) }
    var isChatThreadOpen by remember { mutableStateOf(false) }
    val webAppHubState = remember {
        WebAppHubState(
            onPersistTabs = { json -> app.edgeStore.saveOpenBrowserTabsJson(json) }
        ).apply {
            restoreFromJson(app.edgeStore.loadOpenBrowserTabsJson())
        }
    }
    DisposableEffect(Unit) {
        onDispose { webAppHubState.destroyWebView() }
    }

    var showBotanicalCamera by remember { mutableStateOf(false) }
    var showLiveVoiceModal by remember { mutableStateOf(false) }
    var liveModalInitialGlass by remember { mutableStateOf(false) }
    var showSettingsModal by remember { mutableStateOf(false) }
    var initialSettingsTab by remember { mutableStateOf(SettingsStudioTab.APPEARANCE) }
    var showQuickThemeSheet by remember { mutableStateOf(false) }
    var showSpecimenGallery by remember { mutableStateOf(false) }
    var showMemoryCenter by remember { mutableStateOf(false) }
    var showMacRemoteControl by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    var drawerFocusSearchNonce by remember { mutableLongStateOf(0L) }
    var pendingSearchJump by remember { mutableStateOf<SearchJumpRequest?>(null) }
    var chatPrefillDraft by remember { mutableStateOf("") }
    var chatLaunchAction by remember { mutableStateOf<ChatLaunchAction?>(null) }
    var agentPrefillDraft by remember { mutableStateOf("") }
    var missionDispatchProjectPath by remember { mutableStateOf("") }
    var missionDispatchNonce by remember { mutableLongStateOf(0L) }
    var missionSubViewTarget by remember { mutableStateOf<String?>(null) }
    var missionSubViewNonce by remember { mutableLongStateOf(0L) }
    var webAppAddDialogNonce by remember { mutableLongStateOf(0L) }
    var renamingThread by remember { mutableStateOf<ChatThreadModel?>(null) }
    var renameInput by remember { mutableStateOf("") }

    var isGuiEmergencyStopped by remember {
        mutableStateOf(OmsAccessibilityService.instance?.isEmergencyStopped() == true)
    }
    LaunchedEffect(Unit) {
        while (true) {
            isGuiEmergencyStopped = OmsAccessibilityService.instance?.isEmergencyStopped() == true
            delay(1200L)
        }
    }

    val activeThread = remember(threads, activeThreadId) {
        threads.firstOrNull { it.threadId == activeThreadId }
    }
    val mergedChisuPreview = remember(chisuArticles, streamItems) {
        ChisuDocumentParser.mergeChisuLibrary(chisuArticles, streamItems)
    }

    val pendingApprovalsCount = remember(streamItems) {
        streamItems.count { it.itemType == "APPROVAL" && it.syncStatus == "PENDING_APPROVAL" }
    }
    val runningAgents = remember(agentSessions) {
        agentSessions.filter { it.status == "running" }
    }
    val runningAgentsCount = runningAgents.size

    val connectionDotColor = when {
        activeTier != "DISCONNECTED" -> palette.success
        standbyLease == StandbyLeaseState.ACTIVE_POLLING -> palette.accent
        app.proxyManager.hasAvailableNodes() -> palette.textTertiary
        else -> palette.warning
    }

    // Immersive state detection: hide bottom bar on deep views (including active WebView!) so YouTube/pages get 100% bottom height!
    val isDeepChisuReader = currentTab == CockpitTab.CHISU && activeChisuDetail != null
    val isDeepAgentDetail = currentTab == CockpitTab.MISSION_HUB && selectedAgentSession != null
    val isDeepWebBrowser = currentTab == CockpitTab.WEB_APPS && webAppHubState.isBrowsing
    val isTopLevelHome = when (currentTab) {
        CockpitTab.CHAT -> !isChatThreadOpen
        CockpitTab.CHISU -> activeChisuDetail == null
        CockpitTab.MISSION_HUB -> selectedAgentSession == null
        CockpitTab.WEB_APPS -> !webAppHubState.isBrowsing
    }
    val isImeVisible = WindowInsets.ime.getBottom(density) > 0
    val showBottomBar = isTopLevelHome && !isImeVisible
    val hideShellTopBar = isDeepChisuReader || isDeepAgentDetail || isDeepWebBrowser

    // Predictive Back Gesture Hierarchy: Drawer -> Chat Thread -> Secondary Tab -> System Exit
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    BackHandler(enabled = !drawerState.isOpen && currentTab == CockpitTab.CHAT && isChatThreadOpen) {
        isChatThreadOpen = false
    }
    BackHandler(enabled = !drawerState.isOpen && currentTab != CockpitTab.CHAT) {
        currentTab = CockpitTab.CHAT
        isChatThreadOpen = false
    }

    fun openChatWithPrefill(text: String) {
        chatPrefillDraft = text
        currentTab = CockpitTab.CHAT
        isChatThreadOpen = true
    }

    fun openWebAppUrl(url: String?, title: String?) {
        if (!url.isNullOrBlank()) {
            webAppHubState.openUrl(url, title.orEmpty())
        }
        currentTab = CockpitTab.WEB_APPS
    }

    Box(modifier = Modifier.fillMaxSize().background(palette.canvas)) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = drawerState.isOpen || !(currentTab == CockpitTab.WEB_APPS && webAppHubState.isBrowsing),
            drawerContent = {
                CockpitUnifiedDrawerSheet(
                    coordinator = coordinator,
                    currentTab = currentTab,
                    isChatThreadOpen = isChatThreadOpen,
                    activeTier = activeTier,
                    standbyLease = standbyLease,
                    connectionDotColor = connectionDotColor,
                    themeSettings = themeSettings,
                    threads = threads,
                    activeThreadId = activeThreadId,
                    agentProjects = agentProjects,
                    agentSessions = agentSessions,
                    agentQuotas = agentQuotas,
                    omniSearchHits = omniSearchHits,
                    isOmniSearching = isOmniSearching,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    focusSearchNonce = drawerFocusSearchNonce,
                    onOpenWorkbench = {
                        currentTab = CockpitTab.CHAT
                        isChatThreadOpen = false
                        scope.launch { drawerState.close() }
                    },
                    onSelectTab = { target ->
                        currentTab = target
                        scope.launch { drawerState.close() }
                    },
                    onOpenSpecimenGallery = {
                        scope.launch { drawerState.close() }
                        showSpecimenGallery = true
                    },
                    onOpenMemoryCenter = {
                        scope.launch { drawerState.close() }
                        showMemoryCenter = true
                    },
                    onOpenDropzone = {
                        missionSubViewTarget = "DROPZONE"
                        missionSubViewNonce = System.nanoTime()
                        currentTab = CockpitTab.MISSION_HUB
                        scope.launch { drawerState.close() }
                    },
                    onStartIncognitoChat = {
                        coordinator.chatRepo.createNewThread(incognito = true, initialTitle = "无痕对话")
                        currentTab = CockpitTab.CHAT
                        isChatThreadOpen = true
                        scope.launch { drawerState.close() }
                    },
                    onStartNewChat = {
                        coordinator.chatRepo.createNewThread(incognito = false)
                        currentTab = CockpitTab.CHAT
                        isChatThreadOpen = true
                        scope.launch { drawerState.close() }
                    },
                    onSelectThread = { thr ->
                        coordinator.chatRepo.selectThread(thr.threadId)
                        currentTab = CockpitTab.CHAT
                        isChatThreadOpen = true
                        scope.launch { drawerState.close() }
                    },
                    onRenameThread = { thr ->
                        renamingThread = thr
                        renameInput = thr.title
                    },
                    onSelectSearchChatHit = { threadId, messageId, keyword ->
                        coordinator.chatRepo.selectThread(threadId)
                        pendingSearchJump = SearchJumpRequest(
                            threadId = threadId,
                            messageId = messageId,
                            keyword = keyword
                        )
                        currentTab = CockpitTab.CHAT
                        isChatThreadOpen = true
                        scope.launch { drawerState.close() }
                    },
                    onSelectChisuHit = { article ->
                        coordinator.openChisuArticle(article)
                        currentTab = CockpitTab.CHISU
                        scope.launch { drawerState.close() }
                    },
                    onSelectVaultDocHit = { relPath ->
                        coordinator.openVaultDoc(relPath)
                        scope.launch { drawerState.close() }
                    },
                    onSelectAgentSession = { sess ->
                        coordinator.selectAndLoadAgentSession(sess)
                        currentTab = CockpitTab.MISSION_HUB
                        scope.launch { drawerState.close() }
                    },
                    onDispatchForProject = { projectPath ->
                        coordinator.closeSelectedAgentSession()
                        missionDispatchProjectPath = projectPath
                        missionDispatchNonce = System.nanoTime()
                        currentTab = CockpitTab.MISSION_HUB
                        scope.launch { drawerState.close() }
                    },
                    onOpenSettings = { tab ->
                        scope.launch { drawerState.close() }
                        initialSettingsTab = tab
                        showSettingsModal = true
                    }
                )
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(palette.canvas)
            ) {
                // =================================================================
                // Top Contextual Header Bar (Collapses cleanly on deep screens!)
                // =================================================================
                if (hideShellTopBar) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(palette.topBar)
                            .statusBarsPadding()
                    )
                } else {
                    val drawerBadgeColor = when {
                        pendingApprovalsCount > 0 -> palette.warning
                        runningAgentsCount > 0 -> palette.success
                        else -> null
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(palette.topBar)
                            .statusBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (currentTab == CockpitTab.CHAT && isChatThreadOpen) {
                            // Chat Thread Immersive Top Bar: [←] [Thread Title] [☰] [✎]
                            OmsCircleIconButton(
                                icon = OmsIcons.ArrowLeft,
                                contentDescription = "返回工作台",
                                size = 38.dp,
                                iconSize = 19.dp,
                                onClick = { isChatThreadOpen = false }
                            )

                            val threadTitle = remember(activeThread?.title) {
                                activeThread?.title?.removePrefix("🕵️ ")?.trim()?.ifBlank { "新对话" } ?: "新对话"
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        activeThread?.let {
                                            renamingThread = it
                                            renameInput = it.title
                                        }
                                    }
                                    .padding(horizontal = 6.dp, vertical = 5.dp)
                            ) {
                                if (activeThread?.incognito == true) {
                                    Icon(OmsIcons.Incognito, null, tint = palette.textTertiary, modifier = Modifier.size(15.dp))
                                }
                                Text(
                                    text = threadTitle,
                                    color = palette.textPrimary,
                                    fontSize = 15.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                OmsCircleIconButton(
                                    icon = OmsIcons.Menu,
                                    contentDescription = "打开项目与历史侧边栏",
                                    size = 36.dp,
                                    iconSize = 18.5.dp,
                                    badgeColor = drawerBadgeColor,
                                    onClick = { scope.launch { drawerState.open() } }
                                )
                                OmsCircleIconButton(
                                    icon = OmsIcons.NewChat,
                                    contentDescription = "开启新对话",
                                    size = 38.dp,
                                    iconSize = 19.dp,
                                    contentColor = palette.textPrimary,
                                    onClick = {
                                        coordinator.vibrateTick()
                                        coordinator.chatRepo.createNewThread(incognito = false)
                                        isChatThreadOpen = true
                                    }
                                )
                            }
                        } else {
                            // Top-Level Domain Header: [☰] [Contextual Title + Status] [Actions]
                            OmsCircleIconButton(
                                icon = OmsIcons.Menu,
                                contentDescription = "打开项目与历史侧边栏",
                                size = 38.dp,
                                iconSize = 20.dp,
                                badgeColor = drawerBadgeColor,
                                onClick = { scope.launch { drawerState.open() } }
                            )

                            val (domainTitle, domainSub) = when (currentTab) {
                                CockpitTab.CHAT -> "青简 · 工作台" to when {
                                    activeTier == "LAN_WS" -> "Mac 局域网直连"
                                    activeTier == "WAN_WEBRTC" -> "Mac 远程在线"
                                    activeTier == "BT_RFCOMM" -> "Mac 蓝牙在线"
                                    standbyLease == StandbyLeaseState.ACTIVE_POLLING -> "手机值守中"
                                    else -> "随身云端模式"
                                }
                                CockpitTab.CHISU -> "尺素 · 伴读" to "${mergedChisuPreview.size} 篇已收录"
                                CockpitTab.MISSION_HUB -> "双脑 · 协同" to if (runningAgentsCount > 0) {
                                    "$runningAgentsCount 个任务运行中"
                                } else {
                                    "${agentProjects.size} 个项目 · ${agentSessions.size} 会话"
                                }
                                CockpitTab.WEB_APPS -> "青简 · 应用舱" to "内嵌网页与 AI 伴读"
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.5.dp)
                                            .clip(CircleShape)
                                            .background(connectionDotColor)
                                    )
                                    Text(
                                        text = domainTitle,
                                        color = palette.textPrimary,
                                        fontSize = 15.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                }
                                Text(
                                    text = domainSub,
                                    color = palette.textTertiary,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                if (currentTab == CockpitTab.CHAT) {
                                    OmsCircleIconButton(
                                        icon = OmsIcons.Search,
                                        contentDescription = "全局检索对话与知识库",
                                        size = 36.dp,
                                        iconSize = 17.5.dp,
                                        contentColor = palette.textSecondary,
                                        onClick = {
                                            drawerFocusSearchNonce = System.nanoTime()
                                            scope.launch { drawerState.open() }
                                        }
                                    )
                                }
                                OmsCircleIconButton(
                                    icon = OmsIcons.Palette,
                                    contentDescription = "主题与连接速览",
                                    size = 36.dp,
                                    iconSize = 18.dp,
                                    contentColor = palette.textSecondary,
                                    badgeColor = connectionDotColor,
                                    onClick = { showQuickThemeSheet = true }
                                )
                                if (currentTab == CockpitTab.WEB_APPS) {
                                    OmsCircleIconButton(
                                        icon = OmsIcons.Plus,
                                        contentDescription = "添加网页应用",
                                        size = 38.dp,
                                        iconSize = 19.dp,
                                        contentColor = palette.textPrimary,
                                        onClick = { webAppAddDialogNonce = System.nanoTime() }
                                    )
                                } else {
                                    OmsCircleIconButton(
                                        icon = OmsIcons.NewChat,
                                        contentDescription = "开启新对话",
                                        size = 38.dp,
                                        iconSize = 19.dp,
                                        contentColor = palette.textPrimary,
                                        onClick = {
                                            coordinator.vibrateTick()
                                            if (activeMessages.isNotEmpty() || activeThread?.incognito == true) {
                                                coordinator.chatRepo.createNewThread(incognito = false)
                                            }
                                            currentTab = CockpitTab.CHAT
                                            isChatThreadOpen = true
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Zero-Ops Global Ambient Status Pill (0px when healthy!)
                GlobalAmbientStatusBanner(
                    isGuiEmergencyStopped = isGuiEmergencyStopped,
                    pendingApprovalsCount = if (currentTab == CockpitTab.MISSION_HUB || (currentTab == CockpitTab.CHAT && !isChatThreadOpen)) 0 else pendingApprovalsCount,
                    otaState = otaState,
                    onResumeGui = {
                        OmsAccessibilityService.instance?.resumeFromPhoneUi()
                        isGuiEmergencyStopped = false
                        OmsFeedbackController.show("已解除急停，恢复远程协助", OmsToastKind.SUCCESS)
                    },
                    onOpenApprovals = { currentTab = CockpitTab.MISSION_HUB },
                    onInstallOta = {
                        val cur = otaState
                        if (cur is OtaUiState.UpdateAvailable || cur is OtaUiState.Error) {
                            val ver = (cur as? OtaUiState.UpdateAvailable)?.ota?.remoteVersionName
                                ?: (cur as? OtaUiState.Error)?.ota?.remoteVersionName.orEmpty()
                            OmsFeedbackController.show("开始下载新版本 v$ver…", OmsToastKind.INFO)
                        }
                        app.transportClient.installOrResumeReadyOta { msg ->
                            val kind = if (msg.startsWith("更新未安装")) OmsToastKind.WARNING else OmsToastKind.INFO
                            OmsFeedbackController.show(msg, kind)
                        }
                    }
                )

                // Active Workspace Stage with Smooth Crossfade
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Crossfade(
                        targetState = currentTab,
                        animationSpec = tween(durationMillis = 180),
                        label = "workspaceCrossfade"
                    ) { tab ->
                        when (tab) {
                            CockpitTab.CHAT -> ChatWorkspaceView(
                                coordinator = coordinator,
                                isWorkbenchHome = !isChatThreadOpen,
                                showBottomNavBar = showBottomBar,
                                onEnterChatThread = { isChatThreadOpen = true },
                                prefillDraft = chatPrefillDraft,
                                onConsumePrefillDraft = { chatPrefillDraft = "" },
                                launchAction = chatLaunchAction,
                                onConsumeLaunchAction = { chatLaunchAction = null },
                                searchJumpRequest = pendingSearchJump,
                                onConsumeSearchJump = { pendingSearchJump = null },
                                onOpenBotanicalCamera = { showBotanicalCamera = true },
                                onOpenLiveVoice = { glass ->
                                    liveModalInitialGlass = glass
                                    showLiveVoiceModal = true
                                },
                                onEscalateToMacAgent = { summary ->
                                    agentPrefillDraft = summary
                                    currentTab = CockpitTab.MISSION_HUB
                                },
                                onSwitchTab = { targetTab -> currentTab = targetTab },
                                onOpenVaultDoc = { relPath -> coordinator.openVaultDoc(relPath) },
                                onOpenWebAppHub = { url, title -> openWebAppUrl(url, title) },
                                workbenchContent = { shortModelTag ->
                                    val latestNonEmptyThread = remember(threads) {
                                        threads.firstOrNull { !it.incognito && it.title != "新对话" } ?: threads.firstOrNull()
                                    }
                                    WorkbenchHomeView(
                                        shortModelName = shortModelTag,
                                        brief = secretaryBrief,
                                        isRefreshingBrief = isRefreshingBrief,
                                        pendingApprovalsCount = pendingApprovalsCount,
                                        runningAgents = runningAgents,
                                        recentThread = latestNonEmptyThread,
                                        latestAgentSession = agentSessions.firstOrNull(),
                                        recentChisuArticles = mergedChisuPreview,
                                        onRefreshBrief = { coordinator.refreshSecretaryBrief() },
                                        onAddCommitment = { title, due -> coordinator.addCommitment(title, due) },
                                        onToggleCommitment = { id -> coordinator.toggleCommitment(id) },
                                        onSaveProfile = { items -> coordinator.saveSecretaryProfile(items) },
                                        onDismissNudge = { id -> coordinator.dismissSecretaryNudge(id) },
                                        onTriggerNudge = { nudge ->
                                            when (nudge.actionType) {
                                                "open_chisu" -> currentTab = CockpitTab.CHISU
                                                "open_missions" -> currentTab = CockpitTab.MISSION_HUB
                                                "open_vault_doc" -> coordinator.openVaultDoc(nudge.actionPayload)
                                                else -> {
                                                    val p = nudge.actionPayload.ifBlank { nudge.title }
                                                    chatLaunchAction = ChatLaunchAction(prefillText = p, sendImmediately = true)
                                                }
                                            }
                                        },
                                        onOpenLiveVoice = { glass ->
                                            liveModalInitialGlass = glass
                                            showLiveVoiceModal = true
                                        },
                                        onOpenBotanicalCamera = { showBotanicalCamera = true },
                                        onOpenSpecimenGallery = { showSpecimenGallery = true },
                                        onLaunchDeepThinking = {
                                            chatLaunchAction = ChatLaunchAction(deepThinking = true)
                                        },
                                        onLaunchCreativeMode = { cat ->
                                            chatLaunchAction = ChatLaunchAction(
                                                deepThinking = false,
                                                forceWebSearch = false,
                                                creativeCategory = cat
                                            )
                                        },
                                        onPrefillPhoneCommand = { prompt, sendNow ->
                                            chatLaunchAction = ChatLaunchAction(
                                                prefillText = prompt,
                                                sendImmediately = sendNow
                                            )
                                        },
                                        onOpenMacDrop = {
                                            chatLaunchAction = ChatLaunchAction(openPlusSheet = true)
                                        },
                                        onOpenOmniSearch = {
                                            drawerFocusSearchNonce = System.nanoTime()
                                            scope.launch { drawerState.open() }
                                        },
                                        onOpenMacDispatch = {
                                            coordinator.closeSelectedAgentSession()
                                            missionDispatchProjectPath = ""
                                            missionDispatchNonce = System.nanoTime()
                                            currentTab = CockpitTab.MISSION_HUB
                                        },
                                        onOpenMemoryCenter = { showMemoryCenter = true },
                                        onOpenMacRemoteControl = { showMacRemoteControl = true },
                                        onOpenApprovals = { currentTab = CockpitTab.MISSION_HUB },
                                        onResumeThread = { thr ->
                                            coordinator.chatRepo.selectThread(thr.threadId)
                                            isChatThreadOpen = true
                                        },
                                        onOpenAgentSession = { sess ->
                                            coordinator.selectAndLoadAgentSession(sess)
                                            currentTab = CockpitTab.MISSION_HUB
                                        },
                                        onOpenChisuTab = { currentTab = CockpitTab.CHISU }
                                    )
                                }
                            )

                            CockpitTab.CHISU -> ChisuWorkspaceView(
                                coordinator = coordinator,
                                macConnected = activeTier != "DISCONNECTED",
                                streamItems = streamItems,
                                onDiscussArticleInChat = { title, cleanBody ->
                                    openChatWithPrefill(
                                        "请结合这篇尺素文章《${title.ifBlank { "文章" }}》帮我进一步深入分析：\n${cleanBody.take(1800)}"
                                    )
                                },
                                onSendArticleToMacAgent = { title, cleanBody ->
                                    agentPrefillDraft = "请根据尺素文章《${title.ifBlank { "文章" }}》中的核心启发，在对应项目中设计原型或写入知识库深读笔记：\n${cleanBody.take(2200)}"
                                    currentTab = CockpitTab.MISSION_HUB
                                }
                            )

                            CockpitTab.MISSION_HUB -> MissionHubWorkspaceView(
                                coordinator = coordinator,
                                macConnected = activeTier != "DISCONNECTED",
                                streamItems = streamItems,
                                prefillAgentPrompt = agentPrefillDraft,
                                onConsumePrefillAgentPrompt = { agentPrefillDraft = "" },
                                externalDispatchProjectPath = missionDispatchProjectPath,
                                externalDispatchNonce = missionDispatchNonce,
                                externalSubViewTarget = missionSubViewTarget,
                                externalSubViewNonce = missionSubViewNonce,
                                onOpenMacPairingSettings = {
                                    initialSettingsTab = SettingsStudioTab.SYSTEM_PAIRING
                                    showSettingsModal = true
                                }
                            )

                            CockpitTab.WEB_APPS -> Box(modifier = Modifier.fillMaxSize())
                        }
                    }

                    // Persistent Multi-Tab Browser Host Layer:
                    // Stays mounted across tab switches when tabs are open so YouTube/WebViews never detach or reload!
                    if (currentTab == CockpitTab.WEB_APPS || webAppHubState.hasOpenTabs) {
                        WebAppHubWorkspace(
                            state = webAppHubState,
                            isForegroundVisible = currentTab == CockpitTab.WEB_APPS,
                            openAddDialogNonce = webAppAddDialogNonce,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onSwitchToAssistant = {
                                webAppHubState.pauseActiveVideosForJumpOut()
                                currentTab = CockpitTab.CHAT
                                isChatThreadOpen = false
                            },
                            onAskSecretaryWithPage = { pageTitle, pageUrl, body ->
                                webAppHubState.pauseActiveVideosForJumpOut()
                                openChatWithPrefill(
                                    buildString {
                                        appendLine("请作为我的随身助理，帮我提炼这篇内容的核心要点并结合我的知识库给出建议：")
                                        appendLine("- 标题：$pageTitle")
                                        appendLine("- 链接：$pageUrl")
                                        if (body.isNotBlank()) {
                                            appendLine()
                                            append(body.take(2600))
                                        }
                                    }
                                )
                            },
                            onCuratePageToKnowledgeBase = { pageTitle, pageUrl, pageAuthor, body, mode, onDone ->
                                coordinator.curateBrowserContentToKnowledgeBase(
                                    url = pageUrl,
                                    title = pageTitle,
                                    author = pageAuthor,
                                    extractedText = body,
                                    captureMode = mode,
                                    onComplete = onDone
                                )
                            }
                        )
                    }

                    // Global Floating Commute Audio Mini-Player Bar (when TTS is speaking)
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isSpeakingTts,
                        enter = expandVertically(tween(180)) + fadeIn(tween(150)),
                        exit = shrinkVertically(tween(150)) + fadeOut(tween(120)),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        GlobalMiniAudioPlayerPill(
                            title = speakingTtsText.ifBlank { "正在语音播报…" },
                            onStop = { NotificationExecutor.stopSpeaking(context) }
                        )
                    }
                }

                // =================================================================
                // Sleek 4-Domain Bottom Navigation Bar (Auto-hides on immersive views!)
                // =================================================================
                AnimatedVisibility(
                    visible = showBottomBar,
                    enter = expandVertically(tween(180)) + fadeIn(tween(150)),
                    exit = shrinkVertically(tween(140)) + fadeOut(tween(110))
                ) {
                    val missionDotColor = when {
                        pendingApprovalsCount > 0 -> palette.warning
                        runningAgentsCount > 0 -> palette.success
                        else -> null
                    }
                    CockpitBottomNavBar(
                        currentTab = currentTab,
                        missionDotColor = missionDotColor,
                        webAppActiveDot = webAppHubState.hasOpenTabs,
                        onSelectTab = { chosen ->
                            coordinator.vibrateTick()
                            if (chosen == CockpitTab.CHAT) {
                                isChatThreadOpen = false
                            } else if (chosen == CockpitTab.WEB_APPS && currentTab == CockpitTab.WEB_APPS && webAppHubState.isBrowsing) {
                                webAppHubState.exitToDock()
                            }
                            currentTab = chosen
                        }
                    )
                }
            }
        }

        // Non-Intrusive Top Dynamic Pill Toast Host
        OmsFloatingToastHost()
    }

    // Vault Document Fullscreen Reader Modal (from Omni-Search or Today Brief Book/Note Nudges)
    if (activeVaultDoc != null || isLoadingVaultDoc) {
        Dialog(
            onDismissRequest = { coordinator.closeVaultDoc() },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            val doc = activeVaultDoc
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(palette.canvas)
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.topBar)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OmsCircleIconButton(
                        icon = OmsIcons.ArrowLeft,
                        contentDescription = "关闭文档",
                        onClick = { coordinator.closeVaultDoc() }
                    )
                    Text(
                        text = doc?.category?.ifBlank { "知识库文档" } ?: "正在读取知识库…",
                        color = palette.textSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (doc != null) {
                            OmsCircleIconButton(
                                icon = if (isSpeakingTts) OmsIcons.StopSquare else OmsIcons.Volume,
                                contentDescription = "朗读本篇",
                                contentColor = if (isSpeakingTts) palette.danger else palette.textPrimary,
                                onClick = {
                                    if (isSpeakingTts) {
                                        NotificationExecutor.stopSpeaking(context)
                                    } else {
                                        val speakText = ChisuDocumentParser.cleanForTts(doc.markdown, doc.title)
                                        app.transportClient.speakTextSmart(
                                            speakText,
                                            useAlarmStream = false,
                                            audioPolicy = SpeechAudioPolicy.MEDIA_COMMUTE
                                        )
                                    }
                                }
                            )
                            OmsPrimaryButton(
                                label = "追问",
                                icon = OmsIcons.Sparkles,
                                height = 34.dp,
                                onClick = {
                                    val clean = ChisuDocumentParser.parse(doc.markdown, fallbackTitle = doc.title).bodyMarkdown
                                    coordinator.closeVaultDoc()
                                    openChatWithPrefill(
                                        "请结合我知识库里的《${doc.title}》（${doc.relPath}）帮我深入分析与延展：\n${clean.take(1800)}"
                                    )
                                }
                            )
                        }
                    }
                }
                Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(palette.hairline))
                if (doc == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.CircularProgressIndicator(
                            color = palette.accent,
                            strokeWidth = 2.4.dp,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                } else {
                    val parsed = remember(doc.markdown, doc.title) {
                        ChisuDocumentParser.parse(doc.markdown, fallbackTitle = doc.title, stripHeaderBlock = true)
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 18.dp)
                    ) {
                        Text(
                            text = parsed.cleanTitle.ifBlank { doc.title },
                            color = palette.textPrimary,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 29.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = doc.relPath,
                            color = palette.textTertiary,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(palette.hairline))
                        Spacer(modifier = Modifier.height(14.dp))
                        IncrementalMarkdownView(
                            markdown = parsed.bodyMarkdown.ifBlank { doc.markdown },
                            bodyFontSize = 16.sp,
                            bodyLineHeight = 27.sp
                        )
                    }
                }
            }
        }
    }

    // Rename Thread Dialog
    if (showMemoryCenter) {
        MemoryCenterModal(
            coordinator = coordinator,
            macConnected = activeTier != "DISCONNECTED",
            onOpenVaultDoc = { relPath -> coordinator.openVaultDoc(relPath) },
            onDismiss = { showMemoryCenter = false }
        )
    }

    if (showMacRemoteControl) {
        val macName = app.repository.getPairedMac()?.macName?.ifBlank { "我的 Mac" } ?: "我的 Mac"
        MacRemoteControlModal(
            coordinator = coordinator,
            activeTier = activeTier,
            pairedMacName = macName,
            onDismiss = { showMacRemoteControl = false }
        )
    }

    renamingThread?.let { target ->
        AlertDialog(
            onDismissRequest = { renamingThread = null },
            containerColor = palette.composer,
            title = { Text("重命名对话", color = palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.subtleSurface)
                        .border(0.6.dp, palette.hairline, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    BasicTextField(
                        value = renameInput,
                        onValueChange = { renameInput = it },
                        singleLine = true,
                        textStyle = TextStyle(color = palette.textPrimary, fontSize = 14.5.sp),
                        cursorBrush = SolidColor(palette.accent),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    coordinator.chatRepo.renameThread(target.threadId, renameInput)
                    renamingThread = null
                }) { Text("保存", color = palette.accent, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { renamingThread = null }) { Text("取消", color = palette.textTertiary) }
            }
        )
    }

    // Quick Theme & Connection Popover Sheet (Top-Right Palette Button)
    if (showQuickThemeSheet) {
        ModalBottomSheet(
            onDismissRequest = { showQuickThemeSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = palette.composer,
            contentColor = palette.textPrimary,
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("外观主题与双脑链路", color = palette.textPrimary, fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold)

                OmsSlidingSegmentedBar(
                    items = listOf(
                        OmsSegmentItem(OmsAppearanceMode.SYSTEM, "跟随系统"),
                        OmsSegmentItem(OmsAppearanceMode.LIGHT, "素昼浅色"),
                        OmsSegmentItem(OmsAppearanceMode.DARK, "玄夜深色")
                    ),
                    selected = themeSettings.mode,
                    onSelect = { mode ->
                        coordinator.vibrateTick()
                        OmsThemeController.setAppearanceMode(context, mode)
                    },
                    height = 36.dp,
                    modifier = Modifier.fillMaxWidth()
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OmsThemeStyle.entries.forEach { st ->
                        val isSel = themeSettings.style == st
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .omsBounceClick(scaleDown = 0.98f, shape = RoundedCornerShape(14.dp)) {
                                    coordinator.vibrateTick()
                                    OmsThemeController.setThemeStyle(context, st)
                                }
                                .background(if (isSel) palette.accentSoft else palette.subtleSurface, RoundedCornerShape(14.dp))
                                .border(0.6.dp, if (isSel) palette.accent else palette.hairline, RoundedCornerShape(14.dp))
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(if (palette.isDark) st.swatchDark else st.swatchLight)
                                        .border(1.dp, st.swatchAccent, CircleShape)
                                )
                                Column {
                                    Text(st.title, color = palette.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    Text(st.subtitle, color = palette.textTertiary, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (isSel) {
                                Icon(OmsIcons.Check, contentDescription = null, tint = palette.accent, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OmsSecondaryButton(
                        label = "代理网关",
                        icon = OmsIcons.Globe,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            showQuickThemeSheet = false
                            initialSettingsTab = SettingsStudioTab.PROXY_TUNNEL
                            showSettingsModal = true
                        }
                    )
                    OmsSecondaryButton(
                        label = "更多设置",
                        icon = OmsIcons.Sliders,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            showQuickThemeSheet = false
                            initialSettingsTab = SettingsStudioTab.SYSTEM_PAIRING
                            showSettingsModal = true
                        }
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }

    // Botanical Specimen Field Guide Gallery Modal
    if (showSpecimenGallery) {
        Dialog(
            onDismissRequest = { showSpecimenGallery = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            SpecimenFieldGuideGalleryModal(
                coordinator = coordinator,
                onDismiss = { showSpecimenGallery = false },
                onAskInChat = { card ->
                    showSpecimenGallery = false
                    openChatWithPrefill("关于《${card.chineseName}》（${card.latinName}），我还想请教：")
                }
            )
        }
    }

    // Full-Screen Botanical Glass Camera Overlay
    if (showBotanicalCamera) {
        Dialog(
            onDismissRequest = { showBotanicalCamera = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            BotanicalGlassCameraModal(
                coordinator = coordinator,
                onDismiss = { showBotanicalCamera = false },
                onAskFollowUpInChat = { card ->
                    showBotanicalCamera = false
                    openChatWithPrefill("关于《${card.chineseName}》（${card.latinName}），我还想了解：")
                }
            )
        }
    }

    // Full-Screen Live Walk & Talk / Google Glass 1fps Camera Overlay
    if (showLiveVoiceModal) {
        Dialog(
            onDismissRequest = { showLiveVoiceModal = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            LiveVoiceAndGlassModal(
                coordinator = coordinator,
                initialGlassCameraMode = liveModalInitialGlass,
                onDismissAndArchiveSummary = { summaryMarkdown ->
                    showLiveVoiceModal = false
                    if (!summaryMarkdown.isNullOrBlank()) {
                        val tid = coordinator.chatRepo.activeThreadIdFlow.value
                        if (tid.isNotBlank()) {
                            coordinator.chatRepo.upsertMessage(
                                ChatMessageModel(
                                    messageId = "live_${System.currentTimeMillis()}",
                                    threadId = tid,
                                    role = "assistant",
                                    content = summaryMarkdown,
                                    modelUsed = "Live Session",
                                    createdAtMs = System.currentTimeMillis()
                                ),
                                autoTitleFromFirstUserTurn = false
                            )
                            coordinator.flushPendingSyncToMac()
                            currentTab = CockpitTab.CHAT
                            isChatThreadOpen = true
                        }
                    }
                }
            )
        }
    }

    // Full-Screen Native Grouped Settings Studio
    if (showSettingsModal) {
        Dialog(
            onDismissRequest = { showSettingsModal = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            CockpitSettingsModal(
                coordinator = coordinator,
                initialTab = initialSettingsTab,
                onDismiss = { showSettingsModal = false }
            )
        }
    }
}

@Composable
private fun CockpitBottomNavBar(
    currentTab: CockpitTab,
    missionDotColor: Color?,
    webAppActiveDot: Boolean,
    onSelectTab: (CockpitTab) -> Unit
) {
    val palette = OmsTheme.colors
    data class NavEntry(
        val tab: CockpitTab,
        val label: String,
        val icon: ImageVector,
        val badgeDot: Color? = null
    )
    val items = listOf(
        NavEntry(CockpitTab.CHAT, "工作台", OmsIcons.LayoutGrid),
        NavEntry(CockpitTab.CHISU, "尺素", OmsIcons.BookOpen),
        NavEntry(CockpitTab.MISSION_HUB, "协同", OmsIcons.Bolt, badgeDot = missionDotColor),
        NavEntry(CockpitTab.WEB_APPS, "应用舱", OmsIcons.Globe, badgeDot = if (webAppActiveDot) palette.accent else null)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.topBar)
            .navigationBarsPadding()
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(palette.hairline))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { entry ->
                val selected = currentTab == entry.tab
                val tint = if (selected) palette.accent else palette.textTertiary
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier
                        .weight(1f)
                        .omsBounceClick(scaleDown = 0.94f, shape = RoundedCornerShape(14.dp)) {
                            onSelectTab(entry.tab)
                        }
                        .padding(vertical = 3.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .width(46.dp)
                            .height(27.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) palette.accentSoft else Color.Transparent)
                    ) {
                        Icon(
                            imageVector = entry.icon,
                            contentDescription = entry.label,
                            tint = tint,
                            modifier = Modifier.size(18.5.dp)
                        )
                        if (entry.badgeDot != null) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 3.dp, end = 8.dp)
                                    .size(6.5.dp)
                                    .clip(CircleShape)
                                    .background(entry.badgeDot)
                            )
                        }
                    }
                    Text(
                        text = entry.label,
                        color = tint,
                        fontSize = 10.8.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
internal fun DrawerThreadItemRow(
    thread: ChatThreadModel,
    selected: Boolean,
    onSelect: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val palette = OmsTheme.colors
    var showMenu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(11.dp)
    val cleanTitle = remember(thread.title) {
        thread.title.removePrefix("🕵️ ").trim().ifBlank { "新对话" }
    }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .omsCombinedBounce(
                    scaleDown = 0.985f,
                    shape = shape,
                    onLongClick = { showMenu = true },
                    onClick = onSelect
                )
                .background(if (selected) palette.subtleSurface else Color.Transparent, shape)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (thread.incognito) {
                    Icon(OmsIcons.Incognito, null, tint = palette.textTertiary, modifier = Modifier.size(13.5.dp))
                } else if (thread.pinned) {
                    Icon(OmsIcons.Pin, null, tint = palette.accent, modifier = Modifier.size(13.dp))
                }
                Text(
                    text = cleanTitle,
                    color = if (selected) palette.textPrimary else palette.textSecondary,
                    fontSize = 13.8.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (selected) {
                OmsCircleIconButton(
                    icon = OmsIcons.MoreHorizontal,
                    contentDescription = "会话操作",
                    size = 24.dp,
                    iconSize = 15.dp,
                    contentColor = palette.textTertiary,
                    onClick = { showMenu = true }
                )
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            modifier = Modifier.background(palette.composer)
        ) {
            DropdownMenuItem(
                leadingIcon = { Icon(OmsIcons.Pin, null, tint = palette.textSecondary, modifier = Modifier.size(16.dp)) },
                text = { Text(if (thread.pinned) "取消置顶" else "置顶对话", color = palette.textPrimary, fontSize = 13.5.sp) },
                onClick = {
                    showMenu = false
                    onTogglePin()
                }
            )
            DropdownMenuItem(
                leadingIcon = { Icon(OmsIcons.NewChat, null, tint = palette.textSecondary, modifier = Modifier.size(16.dp)) },
                text = { Text("重命名", color = palette.textPrimary, fontSize = 13.5.sp) },
                onClick = {
                    showMenu = false
                    onRename()
                }
            )
            DropdownMenuItem(
                leadingIcon = { Icon(OmsIcons.Trash, null, tint = palette.danger, modifier = Modifier.size(16.dp)) },
                text = { Text("删除对话", color = palette.danger, fontSize = 13.5.sp) },
                onClick = {
                    showMenu = false
                    onDelete()
                }
            )
        }
    }
}

@Composable
private fun GlobalAmbientStatusBanner(
    isGuiEmergencyStopped: Boolean,
    pendingApprovalsCount: Int,
    otaState: OtaUiState,
    onResumeGui: () -> Unit,
    onOpenApprovals: () -> Unit,
    onInstallOta: () -> Unit
) {
    val palette = OmsTheme.colors
    val readyOta = when (otaState) {
        is OtaUiState.Downloading -> otaState.ota
        is OtaUiState.ReadyToInstall -> otaState.ota
        is OtaUiState.InstallerLaunched -> otaState.ota
        is OtaUiState.NeedsInstallPermission -> otaState.ota
        is OtaUiState.UpdateAvailable -> otaState.ota
        is OtaUiState.Error -> otaState.ota
        else -> null
    }

    val visible = isGuiEmergencyStopped || pendingApprovalsCount > 0 || readyOta != null
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(tween(180)) + fadeIn(tween(150)),
        exit = shrinkVertically(tween(150)) + fadeOut(tween(120))
    ) {
        val shape = RoundedCornerShape(16.dp)
        when {
            isGuiEmergencyStopped -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                        .clip(shape)
                        .background(palette.dangerSoft)
                        .border(0.7.dp, palette.danger.copy(alpha = 0.5f), shape)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                        Icon(OmsIcons.Shield, null, tint = palette.danger, modifier = Modifier.size(16.dp))
                        Text("AI 界面协助已急停锁存", color = palette.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    OmsPrimaryButton(
                        label = "解除恢复",
                        height = 30.dp,
                        containerColor = palette.danger,
                        contentColor = Color.White,
                        onClick = onResumeGui
                    )
                }
            }

            pendingApprovalsCount > 0 -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                        .omsBounceClick(scaleDown = 0.98f, shape = shape, onClick = onOpenApprovals)
                        .background(palette.warningSoft, shape)
                        .border(0.7.dp, palette.warning.copy(alpha = 0.5f), shape)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(OmsIcons.Shield, null, tint = palette.warning, modifier = Modifier.size(16.dp))
                        Text("有 $pendingApprovalsCount 项远程敏感操作待确认", color = palette.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    Icon(OmsIcons.ChevronRight, null, tint = palette.warning, modifier = Modifier.size(16.dp))
                }
            }

            readyOta != null -> {
                val isDownloading = otaState is OtaUiState.Downloading
                val isError = otaState is OtaUiState.Error
                val (bannerTitle, actionLabel) = when (otaState) {
                    is OtaUiState.Downloading -> {
                        val pctSuffix = if (otaState.totalBytes > 0L) " ${otaState.percent}%" else "…"
                        "正在下载新版本 v${readyOta.remoteVersionName}$pctSuffix" to
                            (if (otaState.totalBytes > 0L) "${otaState.percent}%" else "下载中")
                    }
                    is OtaUiState.ReadyToInstall, is OtaUiState.InstallerLaunched ->
                        "新版本 v${readyOta.remoteVersionName} 已就绪" to "安装"
                    is OtaUiState.NeedsInstallPermission ->
                        "新版本 v${readyOta.remoteVersionName} 待开启安装权限" to "去开启"
                    is OtaUiState.Error ->
                        "v${readyOta.remoteVersionName} 下载未完成：${otaState.message.take(18)}" to "重试"
                    else ->
                        "发现新版本 v${readyOta.remoteVersionName}" to "更新"
                }
                val accentColor = if (isError) palette.warning else palette.success
                val bgColor = if (isError) palette.warningSoft else palette.successSoft
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                        .clip(shape)
                        .background(bgColor)
                        .border(0.7.dp, accentColor.copy(alpha = 0.45f), shape)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                        Icon(OmsIcons.Bolt, null, tint = accentColor, modifier = Modifier.size(15.dp))
                        Text(bannerTitle, color = palette.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    OmsPrimaryButton(
                        label = actionLabel,
                        height = 29.dp,
                        enabled = !isDownloading,
                        containerColor = accentColor,
                        contentColor = Color.White,
                        onClick = onInstallOta
                    )
                }
            }
        }
    }
}

@Composable
private fun GlobalMiniAudioPlayerPill(
    title: String,
    onStop: () -> Unit
) {
    val palette = OmsTheme.colors
    val infinite = rememberInfiniteTransition(label = "miniAudio")
    val barPulse by infinite.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(460), RepeatMode.Reverse),
        label = "audioPulse"
    )
    val shape = RoundedCornerShape(22.dp)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 7.dp, shape = shape, clip = false)
            .clip(shape)
            .background(palette.composer)
            .border(0.7.dp, palette.strongBorder, shape)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Icon(
            imageVector = OmsIcons.Waveform,
            contentDescription = null,
            tint = palette.accent.copy(alpha = barPulse),
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = title.replace(Regex("\\s+"), " "),
            color = palette.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        OmsCircleIconButton(
            icon = OmsIcons.StopSquare,
            contentDescription = "停止朗读",
            size = 30.dp,
            iconSize = 15.dp,
            containerColor = palette.dangerSoft,
            contentColor = palette.danger,
            onClick = onStop
        )
    }
}

@Composable
private fun SpecimenFieldGuideGalleryModal(
    coordinator: CockpitRuntimeCoordinator,
    onDismiss: () -> Unit,
    onAskInChat: (FieldGuideSpecimenCard) -> Unit
) {
    val context = LocalContext.current
    val palette = OmsTheme.colors
    val scope = rememberCoroutineScope()
    val specimens = remember { coordinator.chatRepo.loadRecentSpecimens(limit = 60) }
    var selectedDetail by remember { mutableStateOf<Pair<FieldGuideSpecimenCard, String?>?>(null) }
    var zoomLightboxTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    BackHandler {
        when {
            zoomLightboxTarget != null -> zoomLightboxTarget = null
            selectedDetail != null -> selectedDetail = null
            else -> onDismiss()
        }
    }

    zoomLightboxTarget?.let { (path, title) ->
        OmsZoomableImageLightbox(
            imagePath = path,
            titleHint = title,
            onDismiss = { zoomLightboxTarget = null }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        if (selectedDetail != null) {
            val (card, photoPath) = selectedDetail!!
            val capturedDateLabel = remember(card.capturedAtIso) {
                card.capturedAtIso.substringBefore('T').takeIf { it.isNotBlank() } ?: "近期拍摄标本"
            }

            // Detail Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.topBar)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    OmsCircleIconButton(
                        icon = OmsIcons.ArrowLeft,
                        contentDescription = "返回图鉴列表",
                        onClick = { selectedDetail = null }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = card.chineseName,
                            color = palette.textPrimary,
                            fontSize = 16.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "$capturedDateLabel · 随身博物标本档案",
                            color = palette.textTertiary,
                            fontSize = 11.5.sp,
                            maxLines = 1
                        )
                    }
                }

                if (!photoPath.isNullOrBlank()) {
                    OmsSecondaryButton(
                        label = "放大原图",
                        icon = OmsIcons.Search,
                        height = 33.dp,
                        onClick = { zoomLightboxTarget = photoPath to card.chineseName }
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(palette.hairline))

            // Full Museum Specimen Plate Scroll
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (!photoPath.isNullOrBlank()) {
                    val heroBmp by produceState<ImageBitmap?>(initialValue = null, key1 = photoPath) {
                        value = withContext(Dispatchers.IO) {
                            runCatching {
                                val f = File(photoPath)
                                if (f.exists()) BitmapFactory.decodeFile(photoPath)?.asImageBitmap() else null
                            }.getOrNull()
                        }
                    }
                    if (heroBmp != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(250.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .border(0.7.dp, palette.strongBorder, RoundedCornerShape(20.dp))
                                .clickable { zoomLightboxTarget = photoPath to card.chineseName }
                        ) {
                            Image(
                                bitmap = heroBmp!!,
                                contentDescription = card.chineseName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(12.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0xB8090D16))
                                    .padding(horizontal = 11.dp, vertical = 6.dp)
                            ) {
                                Icon(OmsIcons.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.5.dp))
                                Text("点击全屏双指缩放查验细部", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }

                // Taxonomy Title & Confidence Block
                OmsSurfaceCard(
                    cornerRadius = 18.dp,
                    contentPadding = PaddingValues(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(card.chineseName, color = palette.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            if (card.latinName.isNotBlank()) {
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = card.latinName,
                                    color = palette.textSecondary,
                                    fontSize = 14.5.sp,
                                    fontFamily = FontFamily.Serif,
                                    fontStyle = FontStyle.Italic
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(palette.successSoft)
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "${card.confidencePercent}% 吻合",
                                color = palette.success,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (card.familyGenus.isNotBlank() || card.commonAliases.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = listOfNotNull(
                                card.familyGenus.takeIf { it.isNotBlank() }?.let { "科属：$it" },
                                card.commonAliases.takeIf { it.isNotBlank() }?.let { "俗名/别名：$it" }
                            ).joinToString("  ·  "),
                            color = palette.textTertiary,
                            fontSize = 12.5.sp
                        )
                    }

                    if (card.oneLineSummary.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = card.oneLineSummary,
                            color = palette.textPrimary,
                            fontSize = 15.sp,
                            lineHeight = 23.sp
                        )
                    }
                }

                // Detailed Morphology & Comparison Sections
                if (card.keyMorphology.isNotEmpty()) {
                    OmsSurfaceCard(cornerRadius = 18.dp, contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(OmsIcons.Leaf, contentDescription = null, tint = palette.accent, modifier = Modifier.size(16.dp))
                            Text("核心形态鉴定特征", color = palette.textPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        card.keyMorphology.forEach { item ->
                            Text(
                                text = "•  $item",
                                color = palette.textSecondary,
                                fontSize = 14.sp,
                                lineHeight = 22.sp,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }

                if (card.lookalikeComparison.isNotBlank()) {
                    OmsSurfaceCard(cornerRadius = 18.dp, contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(OmsIcons.Sparkles, contentDescription = null, tint = palette.warning, modifier = Modifier.size(16.dp))
                            Text("易混近似种辨析", color = palette.textPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(card.lookalikeComparison, color = palette.textSecondary, fontSize = 14.sp, lineHeight = 22.sp)
                    }
                }

                if (card.bloomAndHabitat.isNotBlank() || card.safetyOrCareNote.isNotBlank()) {
                    OmsSurfaceCard(cornerRadius = 18.dp, contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxWidth()) {
                        if (card.bloomAndHabitat.isNotBlank()) {
                            Text("花果期与生境分布", color = palette.accent, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(card.bloomAndHabitat, color = palette.textSecondary, fontSize = 14.sp, lineHeight = 21.5.sp)
                        }
                        if (card.bloomAndHabitat.isNotBlank() && card.safetyOrCareNote.isNotBlank()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(palette.hairline))
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        if (card.safetyOrCareNote.isNotBlank()) {
                            Text("养护要点与安全提示", color = palette.success, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(card.safetyOrCareNote, color = palette.textSecondary, fontSize = 14.sp, lineHeight = 21.5.sp)
                        }
                    }
                }

                if (card.sources.isNotEmpty()) {
                    OmsSurfaceCard(cornerRadius = 16.dp, contentPadding = PaddingValues(14.dp), modifier = Modifier.fillMaxWidth()) {
                        Text("参考检索来源", color = palette.textTertiary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))
                        card.sources.forEach { src ->
                            Text(
                                text = "↗  ${src.title.ifBlank { src.uri }}",
                                color = palette.accent,
                                fontSize = 12.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(src.uri))
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        }
                                    }
                                    .padding(vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            // Bottom Action Bar: Save to Vault + Ask Follow-up in Chat
            Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(palette.hairline))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.topBar)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OmsSecondaryButton(
                    label = "同步至知识库",
                    icon = OmsIcons.Bookmark,
                    height = 40.dp,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        scope.launch {
                            val ok = coordinator.promoteSpecimenToVault(card, photoPath)
                            OmsFeedbackController.show(
                                if (ok) "已归档至 Mac 知识库 FieldGuide" else "已加入同步队列，连上 Mac 后自动归档",
                                OmsToastKind.SUCCESS
                            )
                        }
                    }
                )
                OmsPrimaryButton(
                    label = "带着标本追问",
                    icon = OmsIcons.Sparkles,
                    height = 40.dp,
                    modifier = Modifier.weight(1f),
                    onClick = { onAskInChat(card) }
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OmsCircleIconButton(OmsIcons.ArrowLeft, "返回", onClick = onDismiss)
                Column {
                    Text("随身博物图鉴册 (${specimens.size})", color = palette.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("点选缩略图可双指放大原图 · 点选卡片查阅完整鉴定档案", color = palette.textTertiary, fontSize = 11.5.sp)
                }
            }

            if (specimens.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(OmsIcons.Leaf, null, tint = palette.textTertiary, modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("尚未收录博物标本", color = palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("在对话页打开「博物镜头」拍摄植物或展品，鉴定卡将自动沉淀于此", color = palette.textTertiary, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = specimens,
                        key = { it.first.specimenId.ifBlank { it.first.chineseName + it.first.capturedAtIso } }
                    ) { (card, photoPath) ->
                        OmsSurfaceCard(
                            cornerRadius = 18.dp,
                            onClick = { selectedDetail = card to photoPath },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(13.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (!photoPath.isNullOrBlank()) {
                                    val bmp by produceState<ImageBitmap?>(initialValue = null, key1 = photoPath) {
                                        value = withContext(Dispatchers.IO) {
                                            runCatching {
                                                val f = File(photoPath)
                                                if (f.exists()) BitmapFactory.decodeFile(photoPath)?.asImageBitmap() else null
                                            }.getOrNull()
                                        }
                                    }
                                    if (bmp != null) {
                                        Box(
                                            modifier = Modifier
                                                .size(82.dp)
                                                .clip(RoundedCornerShape(14.dp))
                                                .border(0.6.dp, palette.hairline, RoundedCornerShape(14.dp))
                                                .clickable { zoomLightboxTarget = photoPath to card.chineseName }
                                        ) {
                                            Image(
                                                bitmap = bmp!!,
                                                contentDescription = card.chineseName,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier
                                                    .align(Alignment.BottomEnd)
                                                    .padding(4.dp)
                                                    .size(20.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xAD090D16))
                                            ) {
                                                Icon(
                                                    imageVector = OmsIcons.Search,
                                                    contentDescription = "放大原图",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(11.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = card.chineseName,
                                            color = palette.textPrimary,
                                            fontSize = 16.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(palette.successSoft)
                                                .padding(horizontal = 7.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "${card.confidencePercent}%",
                                                color = palette.success,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                    if (card.latinName.isNotBlank()) {
                                        Text(
                                            text = card.latinName,
                                            color = palette.textSecondary,
                                            fontSize = 12.5.sp,
                                            fontFamily = FontFamily.Serif,
                                            fontStyle = FontStyle.Italic,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (card.familyGenus.isNotBlank()) {
                                        Text(card.familyGenus, color = palette.textTertiary, fontSize = 11.5.sp, maxLines = 1)
                                    }
                                    if (card.oneLineSummary.isNotBlank()) {
                                        Text(
                                            text = card.oneLineSummary,
                                            color = palette.textSecondary,
                                            fontSize = 12.5.sp,
                                            lineHeight = 18.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(palette.hairline))
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "查看完整形态特征与养护档案",
                                        color = palette.accent,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Icon(
                                        imageVector = OmsIcons.ChevronRight,
                                        contentDescription = null,
                                        tint = palette.accent,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                                OmsSecondaryButton(
                                    label = "追问",
                                    height = 30.dp,
                                    onClick = { onAskInChat(card) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun groupThreadsByRecency(threads: List<ChatThreadModel>): List<Pair<String, List<ChatThreadModel>>> {
    val pinned = threads.filter { it.pinned }
    val unpinned = threads.filter { !it.pinned }

    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val startOfToday = cal.timeInMillis
    val sevenDaysAgo = startOfToday - 7L * 86_400_000L

    val today = unpinned.filter { it.updatedAtMs >= startOfToday }
    val week = unpinned.filter { it.updatedAtMs in sevenDaysAgo until startOfToday }
    val older = unpinned.filter { it.updatedAtMs < sevenDaysAgo }

    return buildList {
        if (pinned.isNotEmpty()) add("置顶对话" to pinned)
        if (today.isNotEmpty()) add("今天" to today)
        if (week.isNotEmpty()) add("近 7 天" to week)
        if (older.isNotEmpty()) add("更早" to older)
    }
}
