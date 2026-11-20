package com.oms.client.ui.cockpit

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.oms.client.OmsApplication
import com.oms.client.capability.NotificationExecutor
import com.oms.client.capability.SpeechAudioPolicy
import com.oms.client.capability.VoiceRecorderHelper
import com.oms.client.cockpit.CockpitRuntimeCoordinator
import com.oms.client.cockpit.RemoteAgentBackendQuotaUi
import com.oms.client.cockpit.RemoteAgentQuotasUi
import com.oms.client.cockpit.RemoteAgentSessionUi
import com.oms.client.cockpit.RemoteAgentStepUi
import com.oms.client.data.StreamItemModel
import com.oms.client.otp.OtpExtractor
import com.oms.client.share.AttachmentBudget
import com.oms.client.share.AttachmentResolver
import com.oms.client.ui.markdown.IncrementalMarkdownView
import com.oms.client.ui.theme.OmsCircleIconButton
import com.oms.client.ui.theme.OmsFeedbackController
import com.oms.client.ui.theme.OmsIcons
import com.oms.client.ui.theme.OmsMorphIconAction
import com.oms.client.ui.theme.OmsPrimaryButton
import com.oms.client.ui.theme.OmsSecondaryButton
import com.oms.client.ui.theme.OmsSegmentItem
import com.oms.client.ui.theme.OmsSlidingSegmentedBar
import com.oms.client.ui.theme.OmsSurfaceCard
import com.oms.client.ui.theme.OmsTheme
import com.oms.client.ui.theme.OmsToastKind
import com.oms.client.ui.theme.OmsTogglePill
import com.oms.client.ui.theme.omsBounceClick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// =============================================================================
// Workspace 2 (`尺素`): Magazine Digest Stream + Immersive Paper Reader
// =============================================================================
@Composable
fun ChisuWorkspaceView(
    streamItems: List<StreamItemModel>,
    onDiscussArticleInChat: (StreamItemModel) -> Unit,
    onSendArticleToMacAgent: (StreamItemModel) -> Unit
) {
    val context = LocalContext.current
    val palette = OmsTheme.colors
    val app = OmsApplication.instance ?: return
    val isSpeaking by NotificationExecutor.isSpeakingFlow.collectAsState()
    var filterKey by remember { mutableStateOf("ALL") }
    var activeReaderItemId by remember { mutableStateOf<String?>(null) }

    val chisuItems = remember(streamItems) {
        streamItems.filter {
            it.intentTag.contains("wechat", ignoreCase = true) ||
                it.intentTag == "pkb_vault" ||
                it.title?.contains("《") == true ||
                it.textContent?.contains("脱水干货") == true ||
                (it.direction == "MAC_TO_PHONE" && !it.textContent.isNullOrBlank() && it.itemType != "APPROVAL")
        }
    }

    val filteredItems = remember(chisuItems, filterKey) {
        when (filterKey) {
            "EDGE_NIGHT" -> chisuItems.filter { it.intentTag == "wechat_edge_standby" }
            else -> chisuItems
        }
    }

    val activeReaderItem = remember(streamItems, activeReaderItemId) {
        activeReaderItemId?.let { id -> streamItems.firstOrNull { it.itemId == id } }
    }

    if (activeReaderItem != null) {
        BackHandler { activeReaderItemId = null }
        ChisuImmersiveReaderScreen(
            item = activeReaderItem,
            onBack = { activeReaderItemId = null },
            onDiscussInChat = {
                activeReaderItemId = null
                onDiscussArticleInChat(activeReaderItem)
            },
            onSendToAgent = {
                activeReaderItemId = null
                onSendArticleToMacAgent(activeReaderItem)
            }
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Minimalist Filter & Commute Radio Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OmsTogglePill(
                    label = "全部精读 (${chisuItems.size})",
                    active = filterKey == "ALL",
                    icon = OmsIcons.BookOpen,
                    onClick = { filterKey = "ALL" }
                )
                OmsTogglePill(
                    label = "手机休眠抢答",
                    active = filterKey == "EDGE_NIGHT",
                    icon = OmsIcons.Moon,
                    onClick = { filterKey = "EDGE_NIGHT" }
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            OmsPrimaryButton(
                label = if (isSpeaking) "停止联播" else "通勤联播",
                icon = if (isSpeaking) OmsIcons.StopSquare else OmsIcons.Waveform,
                height = 33.dp,
                containerColor = if (isSpeaking) palette.danger else palette.sendButtonBg,
                contentColor = if (isSpeaking) Color.White else palette.sendButtonFg,
                onClick = {
                    if (isSpeaking) {
                        NotificationExecutor.stopSpeaking(context)
                    } else {
                        val playlist = filteredItems
                            .filter { !it.textContent.isNullOrBlank() }
                            .take(5)
                            .joinToString("\n\n下一篇：\n") {
                                "${it.title ?: "尺素简报"}。${it.textContent.orEmpty().take(750)}"
                            }
                        if (playlist.isNotBlank()) {
                            app.transportClient.speakTextSmart(
                                playlist,
                                useAlarmStream = false,
                                audioPolicy = SpeechAudioPolicy.MEDIA_COMMUTE
                            )
                            OmsFeedbackController.show("已开启尺素通勤语音联播", OmsToastKind.INFO)
                        } else {
                            OmsFeedbackController.show("暂无可朗读的伴读文章", OmsToastKind.WARNING)
                        }
                    }
                }
            )
        }

        if (filteredItems.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(palette.subtleSurface)
                ) {
                    Icon(OmsIcons.BookOpen, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(24.dp))
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text("暂无尺素伴读笔记", color = palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "在微信将公众号文章转发给「尺素」即可自动精析入库\nMac 休眠时将由手机咫尺自动无缝接管",
                    color = palette.textTertiary,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(items = filteredItems, key = { it.itemId }) { item ->
                    ChisuMagazineDigestCard(
                        item = item,
                        onClick = { activeReaderItemId = item.itemId }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChisuMagazineDigestCard(
    item: StreamItemModel,
    onClick: () -> Unit
) {
    val palette = OmsTheme.colors
    val timeLabel = remember(item.createdAtMs) {
        SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(Date(item.createdAtMs))
    }
    val isNightEdge = item.intentTag == "wechat_edge_standby"
    val body = remember(item.textContent, item.voiceTranscript) {
        (item.textContent ?: item.voiceTranscript ?: "")
            .replace(Regex("[#*>`\\-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
    val estReadMin = remember(body) { (body.length / 360).coerceAtLeast(1) }

    OmsSurfaceCard(
        cornerRadius = 18.dp,
        contentPadding = PaddingValues(16.dp),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = if (isNightEdge) OmsIcons.Moon else OmsIcons.BookOpen,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(13.5.dp)
                )
                Text(
                    text = if (isNightEdge) "手机夜间直连初筛" else "尺素伴读",
                    color = palette.accent,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "· 约 $estReadMin 分钟",
                    color = palette.textTertiary,
                    fontSize = 11.5.sp
                )
            }
            Text(timeLabel, color = palette.textTertiary, fontSize = 11.5.sp)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = item.title?.takeIf { it.isNotBlank() } ?: "未命名伴读笔记",
            color = palette.textPrimary,
            fontSize = 16.5.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 23.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        if (body.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = body.take(180),
                color = palette.textSecondary,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ChisuImmersiveReaderScreen(
    item: StreamItemModel,
    onBack: () -> Unit,
    onDiscussInChat: () -> Unit,
    onSendToAgent: () -> Unit
) {
    val context = LocalContext.current
    val palette = OmsTheme.colors
    val body = item.textContent ?: item.voiceTranscript ?: ""
    val timeLabel = remember(item.createdAtMs) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(item.createdAtMs))
    }

    Box(modifier = Modifier.fillMaxSize().background(palette.canvas)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Sticky Minimalist Editorial Header
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
                    contentDescription = "返回列表",
                    onClick = onBack
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OmsMorphIconAction(
                        icon = OmsIcons.Copy,
                        contentDescription = "复制全文",
                        tint = palette.textPrimary,
                        iconSize = 18.dp,
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("chisu", body))
                            OmsFeedbackController.show("已复制伴读笔记全文", OmsToastKind.SUCCESS)
                        }
                    )
                    OmsCircleIconButton(
                        icon = OmsIcons.Volume,
                        contentDescription = "朗读本篇",
                        onClick = {
                            if (body.isNotBlank()) {
                                val speakStr = "${item.title.orEmpty()}。$body"
                                val tc = OmsApplication.instance?.transportClient
                                if (tc != null) {
                                    tc.speakTextSmart(speakStr, useAlarmStream = false, audioPolicy = SpeechAudioPolicy.MEDIA_COMMUTE)
                                } else {
                                    NotificationExecutor.speakText(context, speakStr, useAlarmStream = false, audioPolicy = SpeechAudioPolicy.MEDIA_COMMUTE)
                                }
                            }
                        }
                    )
                    OmsCircleIconButton(
                        icon = OmsIcons.Laptop,
                        contentDescription = "派发至 Mac 验证",
                        onClick = onSendToAgent
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(palette.hairline))

            // Paper-Grade Long-Form Reading Scroll
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 96.dp)
            ) {
                if (!item.title.isNullOrBlank()) {
                    Text(
                        text = item.title,
                        color = palette.textPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 31.sp,
                        letterSpacing = (-0.3).sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(
                    text = "$timeLabel · 青简尺素归档",
                    color = palette.textTertiary,
                    fontSize = 12.5.sp
                )
                Spacer(modifier = Modifier.height(20.dp))

                IncrementalMarkdownView(
                    markdown = body,
                    bodyFontSize = 16.sp,
                    bodyLineHeight = 27.5.sp
                )
            }
        }

        // Floating Contextual Ask Pill at Bottom
        val askShape = RoundedCornerShape(24.dp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .shadow(elevation = 8.dp, shape = askShape, clip = false)
                .omsBounceClick(scaleDown = 0.96f, shape = askShape, onClick = onDiscussInChat)
                .background(palette.composer, askShape)
                .border(0.7.dp, palette.strongBorder, askShape)
                .padding(horizontal = 18.dp, vertical = 11.dp)
        ) {
            Icon(
                imageVector = OmsIcons.Sparkles,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "带着这篇文章继续向 AI 深度追问",
                color = palette.textPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium
            )
            Icon(
                imageVector = OmsIcons.ChevronRight,
                contentDescription = null,
                tint = palette.textTertiary,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

// =============================================================================
// Workspace 3 (`协同`): Unified Mac Heavy-Brain (Codex / AGY) + Cross-Device Dropzone
// =============================================================================
private enum class HubSubView {
    AGENTS,
    DROPZONE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MissionHubWorkspaceView(
    coordinator: CockpitRuntimeCoordinator,
    macConnected: Boolean,
    streamItems: List<StreamItemModel>,
    prefillAgentPrompt: String,
    onConsumePrefillAgentPrompt: () -> Unit
) {
    val context = LocalContext.current
    val palette = OmsTheme.colors
    val scope = rememberCoroutineScope()
    val app = OmsApplication.instance ?: return

    val sessions by coordinator.agentSessionsFlow.collectAsState()
    val projects by coordinator.agentProjectsFlow.collectAsState()
    val quotas by coordinator.agentQuotasFlow.collectAsState()
    val selectedSession by coordinator.selectedAgentSessionFlow.collectAsState()
    val selectedSteps by coordinator.selectedAgentStepsFlow.collectAsState()
    val isLoadingDetail by coordinator.isLoadingAgentDetailFlow.collectAsState()

    var activeSubView by remember { mutableStateOf(HubSubView.AGENTS) }
    var backendFilter by remember { mutableStateOf("ALL") }
    var projectFilterPath by remember { mutableStateOf("ALL") }
    var showDispatchSheet by remember { mutableStateOf(false) }
    var showMacDropSheet by remember { mutableStateOf(false) }
    var dispatchInitialPrompt by remember { mutableStateOf("") }

    LaunchedEffect(Unit, macConnected) {
        if (macConnected) coordinator.refreshAgentSessions()
    }

    LaunchedEffect(prefillAgentPrompt) {
        if (prefillAgentPrompt.isNotBlank()) {
            dispatchInitialPrompt = prefillAgentPrompt
            activeSubView = HubSubView.AGENTS
            showDispatchSheet = true
            onConsumePrefillAgentPrompt()
        }
    }

    if (selectedSession != null) {
        BackHandler { coordinator.closeSelectedAgentSession() }
        AgentSessionDetailScreen(
            session = selectedSession!!,
            steps = selectedSteps,
            isLoadingDetail = isLoadingDetail,
            macConnected = macConnected,
            onBack = { coordinator.closeSelectedAgentSession() },
            onRefresh = { coordinator.selectAndLoadAgentSession(selectedSession!!) },
            onCancel = { coordinator.cancelRemoteAgentSession(selectedSession!!.backend, selectedSession!!.sessionId) },
            onContinuePrompt = { followUp ->
                scope.launch {
                    runCatching {
                        coordinator.continueRemoteAgentSession(
                            backend = selectedSession!!.backend,
                            sessionId = selectedSession!!.sessionId,
                            prompt = followUp,
                            workspacePath = selectedSession!!.workspacePath
                        )
                    }.onFailure {
                        OmsFeedbackController.show("接续失败：${it.message}", OmsToastKind.WARNING)
                    }
                }
            }
        )
        return
    }

    val pendingApprovals = remember(streamItems) {
        streamItems.filter { it.itemType == "APPROVAL" && it.syncStatus == "PENDING_APPROVAL" }
    }

    val activeProjectsWithSessions = remember(projects, sessions) {
        val countsByPath = sessions.groupingBy { it.workspacePath.trim().trimEnd('/') }.eachCount()
        projects.filter { p ->
            val norm = p.projectPath.trim().trimEnd('/')
            (countsByPath[norm] ?: p.sessionCount) > 0
        }
    }

    val filteredSessions = remember(sessions, backendFilter, projectFilterPath) {
        val normProj = projectFilterPath.trim().trimEnd('/')
        sessions.filter { s ->
            val matchBackend = backendFilter == "ALL" || s.backend == backendFilter
            val matchProj = projectFilterPath == "ALL" || s.workspacePath.trim().trimEnd('/') == normProj
            matchBackend && matchProj
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Sub-Navigation Header: [ Agent 会话 | 跨端投递箱 ]  +  [刷新] [+ 派单 / + 投递]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OmsSlidingSegmentedBar(
                items = listOf(
                    OmsSegmentItem(HubSubView.AGENTS, "Agent 会话"),
                    OmsSegmentItem(HubSubView.DROPZONE, "跨端投递箱")
                ),
                selected = activeSubView,
                onSelect = { activeSubView = it },
                height = 34.dp,
                modifier = Modifier.width(206.dp)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (activeSubView == HubSubView.AGENTS) {
                    OmsCircleIconButton(
                        icon = OmsIcons.Refresh,
                        contentDescription = "刷新会话",
                        size = 34.dp,
                        iconSize = 16.5.dp,
                        containerColor = palette.subtleSurface,
                        borderColor = palette.hairline,
                        onClick = { coordinator.refreshAgentSessions() }
                    )
                    OmsPrimaryButton(
                        label = "派发任务",
                        icon = OmsIcons.Plus,
                        height = 34.dp,
                        onClick = { showDispatchSheet = true }
                    )
                } else {
                    OmsPrimaryButton(
                        label = "投递至 Mac",
                        icon = OmsIcons.Send,
                        height = 34.dp,
                        onClick = { showMacDropSheet = true }
                    )
                }
            }
        }

        // Pinned High-Priority Remote Approval Cards
        if (pendingApprovals.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pendingApprovals.forEach { appr ->
                    PinnedRemoteApprovalCard(
                        item = appr,
                        onDecision = { approved, opt ->
                            val meta = runCatching { JSONObject(appr.metadataJson ?: "{}") }.getOrDefault(JSONObject())
                            app.transportClient.sendApprovalDecision(
                                itemId = appr.itemId,
                                approvalId = meta.optString("approvalId", appr.itemId),
                                approved = approved,
                                selectedOption = opt,
                                userComment = null
                            )
                        }
                    )
                }
            }
        }

        if (activeSubView == HubSubView.AGENTS) {
            // Backend Filter Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OmsTogglePill("全部 (${sessions.size})", backendFilter == "ALL", onClick = { backendFilter = "ALL" })
                OmsTogglePill("Codex", backendFilter == "codex", icon = OmsIcons.Terminal, onClick = { backendFilter = "codex" })
                OmsTogglePill("Antigravity", backendFilter == "agy", icon = OmsIcons.Sparkles, onClick = { backendFilter = "agy" })
            }

            // Project Quick-Filter Strip (human project names + session counts)
            if (activeProjectsWithSessions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OmsTogglePill(
                        label = "全部项目",
                        active = projectFilterPath == "ALL",
                        onClick = { projectFilterPath = "ALL" }
                    )
                    activeProjectsWithSessions.forEach { proj ->
                        val norm = proj.projectPath.trim().trimEnd('/')
                        val cnt = sessions.count { it.workspacePath.trim().trimEnd('/') == norm }.coerceAtLeast(proj.sessionCount)
                        OmsTogglePill(
                            label = "${proj.displayName} · $cnt",
                            active = projectFilterPath == proj.projectPath,
                            icon = OmsIcons.Folder,
                            onClick = {
                                projectFilterPath = if (projectFilterPath == proj.projectPath) "ALL" else proj.projectPath
                            }
                        )
                    }
                }
            }

            if (!macConnected) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.warningSoft)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(OmsIcons.Laptop, contentDescription = null, tint = palette.warning, modifier = Modifier.size(15.dp))
                    Text(
                        text = "当前未连通 Mac，展示上次缓存快照；恢复连接后可实时刷新额度与会话",
                        color = palette.textSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                quotas?.let { qSnap ->
                    item(key = "agent_quotas_dashboard") {
                        AgentQuotaDashboardCard(quotas = qSnap)
                    }
                }

                if (filteredSessions.isEmpty()) {
                    item(key = "empty_agent_sessions") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(52.dp).clip(CircleShape).background(palette.subtleSurface)
                            ) {
                                Icon(OmsIcons.Terminal, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(24.dp))
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                            Text("当前筛选下暂无会话", color = palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "可切换上方项目胶囊，或点击右上角「派发任务」在 Mac 仓库启动新会话",
                                color = palette.textTertiary,
                                fontSize = 13.sp
                            )
                        }
                    }
                } else {
                    items(items = filteredSessions, key = { "${it.backend}:${it.sessionId}" }) { session ->
                        AgentSessionCard(
                            session = session,
                            onClick = { coordinator.selectAndLoadAgentSession(session) }
                        )
                    }
                }
            }
        } else {
            // Sub-view 2: Cross-Device Dropzone (Replaces Legacy MainActivity Timeline!)
            CrossDeviceDropzoneList(streamItems = streamItems)
        }
    }

    // Half-Screen Agent Task Dispatch Sheet
    if (showDispatchSheet) {
        ModalBottomSheet(
            onDismissRequest = { showDispatchSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = palette.composer,
            contentColor = palette.textPrimary,
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
        ) {
            AgentDispatchSheetContent(
                coordinator = coordinator,
                macConnected = macConnected,
                projects = projects,
                sessions = sessions,
                initialPrompt = dispatchInitialPrompt,
                onDispatched = {
                    dispatchInitialPrompt = ""
                    showDispatchSheet = false
                }
            )
        }
    }

    // Half-Screen Cross-Device Drop-to-Mac Sheet
    if (showMacDropSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMacDropSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = palette.composer,
            contentColor = palette.textPrimary,
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
        ) {
            DropToMacSheetContent(
                onSubmitted = { showMacDropSheet = false }
            )
        }
    }
}

@Composable
private fun PinnedRemoteApprovalCard(
    item: StreamItemModel,
    onDecision: (Boolean, String) -> Unit
) {
    val palette = OmsTheme.colors
    val meta = remember(item.metadataJson) { runCatching { JSONObject(item.metadataJson ?: "{}") }.getOrDefault(JSONObject()) }
    val optsArr = meta.optJSONArray("options")
    val allowLabel = optsArr?.optString(0)?.takeIf { it.isNotBlank() } ?: "批准执行"
    val denyLabel = optsArr?.optString(1)?.takeIf { it.isNotBlank() } ?: "拒绝"

    OmsSurfaceCard(
        containerColor = palette.warningSoft,
        borderColor = palette.warning.copy(alpha = 0.55f),
        cornerRadius = 18.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(OmsIcons.Shield, contentDescription = null, tint = palette.warning, modifier = Modifier.size(16.dp))
            Text("远程敏感操作待确认", color = palette.warning, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        }
        if (!item.title.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(item.title, color = palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        if (!item.textContent.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(item.textContent, color = palette.textSecondary, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OmsPrimaryButton(
                label = allowLabel,
                icon = OmsIcons.Check,
                height = 36.dp,
                containerColor = palette.success,
                contentColor = Color.White,
                onClick = { onDecision(true, allowLabel) }
            )
            OmsSecondaryButton(
                label = denyLabel,
                icon = OmsIcons.Close,
                height = 36.dp,
                contentColor = palette.danger,
                onClick = { onDecision(false, denyLabel) }
            )
        }
    }
}

private fun cleanAgentPreviewSnippet(raw: String): String {
    return raw
        .replace(Regex("""\[([^\]]+)\]\([^)]+\)"""), "$1")
        .replace(Regex("""[#*>`~]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private fun formatResetCountdown(resetsAtMs: Long): String {
    val dateStr = SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(Date(resetsAtMs))
    val diffMs = resetsAtMs - System.currentTimeMillis()
    if (diffMs <= 0L) return "$dateStr（即将重置）"
    val totalMin = (diffMs / 60_000L).toInt()
    val rel = when {
        totalMin < 60 -> "约 ${totalMin.coerceAtLeast(1)} 分钟后"
        totalMin < 24 * 60 -> {
            val h = totalMin / 60
            val m = totalMin % 60
            if (m > 0) "约 ${h}小时${m}分后" else "约 ${h} 小时后"
        }
        else -> {
            val d = totalMin / (24 * 60)
            val h = (totalMin % (24 * 60)) / 60
            if (h > 0) "约 ${d}天${h}小时后" else "约 ${d} 天后"
        }
    }
    return "$dateStr 重置 · $rel"
}

@Composable
internal fun AgentQuotaDashboardCard(quotas: RemoteAgentQuotasUi) {
    val palette = OmsTheme.colors
    var expanded by remember { mutableStateOf(true) }

    OmsSurfaceCard(
        cornerRadius = 18.dp,
        contentPadding = PaddingValues(horizontal = 15.dp, vertical = 13.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .omsBounceClick(scaleDown = 0.985f) { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(OmsIcons.Cpu, contentDescription = null, tint = palette.accent, modifier = Modifier.size(15.dp))
                Text(
                    text = "Mac 双脑额度余量与重置周期",
                    color = palette.textPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val codexRem = quotas.codex.windows.firstOrNull()?.remainingPercent
                if (!expanded && codexRem != null) {
                    Text(
                        text = "Codex 剩 $codexRem%",
                        color = if (codexRem <= 20) palette.warning else palette.textSecondary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Icon(
                    imageVector = if (expanded) OmsIcons.ChevronUp else OmsIcons.ChevronDown,
                    contentDescription = null,
                    tint = palette.textTertiary,
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(180)) + fadeIn(tween(140)),
            exit = shrinkVertically(tween(150)) + fadeOut(tween(110))
        ) {
            Column(
                modifier = Modifier.padding(top = 11.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                AgentBackendQuotaRow(
                    title = "OpenAI Codex",
                    icon = OmsIcons.Terminal,
                    quota = quotas.codex
                )
                Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(palette.hairline))
                AgentBackendQuotaRow(
                    title = "Antigravity",
                    icon = OmsIcons.Sparkles,
                    quota = quotas.agy
                )
            }
        }
    }
}

@Composable
private fun AgentBackendQuotaRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    quota: RemoteAgentBackendQuotaUi
) {
    val palette = OmsTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(14.dp))
                Text(title, color = palette.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(palette.subtleSurface)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = quota.activeModel.ifBlank { quota.planLabel },
                        color = palette.textSecondary,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (quota.windows.isNotEmpty()) {
                val primary = quota.windows.first()
                val remColor = when {
                    primary.remainingPercent <= 12 -> palette.danger
                    primary.remainingPercent <= 25 -> palette.warning
                    else -> palette.success
                }
                Text(
                    text = "剩余 ${primary.remainingPercent}%",
                    color = remColor,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Text(
                    text = if (quota.statusNote.contains("限流")) "触发限流" else "额度充足",
                    color = if (quota.statusNote.contains("限流")) palette.warning else palette.success,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (quota.windows.isNotEmpty()) {
            quota.windows.forEach { win ->
                val barColor = when {
                    win.remainingPercent <= 12 -> palette.danger
                    win.remainingPercent <= 25 -> palette.warning
                    else -> palette.accent
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Remaining quota progress bar (filled portion = remaining %)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(palette.subtleSurface)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((win.remainingPercent / 100f).coerceIn(0.03f, 1f))
                                .height(6.dp)
                                .clip(CircleShape)
                                .background(barColor)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${win.label}（已用 ${win.usedPercent}%）",
                            color = palette.textSecondary,
                            fontSize = 11.5.sp
                        )
                        win.resetsAtMs?.let { rMs ->
                            Text(
                                text = formatResetCountdown(rMs),
                                color = palette.textTertiary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }

        if (quota.creditsSummary.isNotBlank()) {
            Text(
                text = quota.creditsSummary,
                color = palette.textSecondary,
                fontSize = 12.sp
            )
        }
        if (quota.statusNote.isNotBlank() && quota.windows.isEmpty()) {
            Text(
                text = quota.statusNote,
                color = palette.textTertiary,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun AgentSessionCard(session: RemoteAgentSessionUi, onClick: () -> Unit) {
    val palette = OmsTheme.colors
    val timeStr = remember(session.updatedAtMs) {
        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(session.updatedAtMs))
    }
    val projectLabel = remember(session.projectDisplayName, session.workspacePath) {
        session.projectDisplayName.ifBlank {
            session.workspacePath.trimEnd('/').substringAfterLast('/').ifBlank { "工作区" }
        }
    }
    val cleanSummary = remember(session.lastSummary) {
        cleanAgentPreviewSnippet(session.lastSummary)
    }
    val (statusText, statusColor) = when (session.status) {
        "running" -> "运行中" to palette.success
        "completed" -> "已完成" to palette.accent
        "failed" -> "异常中止" to palette.danger
        else -> "可接续" to palette.textTertiary
    }

    OmsSurfaceCard(
        cornerRadius = 18.dp,
        borderColor = if (session.status == "running") palette.success.copy(alpha = 0.5f) else palette.hairline,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (session.backend == "codex") OmsIcons.Terminal else OmsIcons.Sparkles,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = if (session.backend == "codex") "Codex" else "Antigravity",
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                // Prominent Project Name Pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.subtleSurface)
                        .border(0.5.dp, palette.hairline, RoundedCornerShape(8.dp))
                        .padding(horizontal = 7.dp, vertical = 2.5.dp)
                ) {
                    Icon(OmsIcons.Folder, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(11.dp))
                    Text(
                        text = projectLabel,
                        color = palette.textPrimary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(modifier = Modifier.size(6.dp).background(statusColor, CircleShape))
                Text(statusText, color = statusColor, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = session.title.ifBlank { "${if (session.backend == "codex") "Codex" else "Antigravity"} · $projectLabel" },
            color = palette.textPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 22.5.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        if (cleanSummary.isNotBlank()) {
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = cleanSummary,
                color = palette.textSecondary,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (session.origin == "mobile") "手机派单 · ${session.sessionId.take(8)}" else "Mac 桌面 · ${session.sessionId.take(8)}",
                color = palette.textTertiary,
                fontSize = 11.sp
            )
            Text(
                text = timeStr,
                color = palette.textTertiary,
                fontSize = 11.5.sp
            )
        }
    }
}

private sealed class TrajectoryDisplayItem {
    data class SingleTurn(val step: RemoteAgentStepUi) : TrajectoryDisplayItem()
    data class ToolAccordionGroup(val groupKey: String, val steps: List<RemoteAgentStepUi>) : TrajectoryDisplayItem()
}

private fun groupAgentTrajectorySteps(steps: List<RemoteAgentStepUi>): List<TrajectoryDisplayItem> {
    val result = ArrayList<TrajectoryDisplayItem>()
    val pendingTools = ArrayList<RemoteAgentStepUi>()

    fun flushTools() {
        if (pendingTools.isEmpty()) return
        val copy = pendingTools.toList()
        result.add(
            TrajectoryDisplayItem.ToolAccordionGroup(
                groupKey = "grp_${copy.first().stepIndex}_${copy.last().stepIndex}",
                steps = copy
            )
        )
        pendingTools.clear()
    }

    for (st in steps) {
        if (st.kind == "tool" || st.kind == "thinking") {
            pendingTools.add(st)
        } else {
            flushTools()
            result.add(TrajectoryDisplayItem.SingleTurn(st))
        }
    }
    flushTools()
    return result
}

@Composable
private fun AgentSessionDetailScreen(
    session: RemoteAgentSessionUi,
    steps: List<RemoteAgentStepUi>,
    isLoadingDetail: Boolean,
    macConnected: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
    onContinuePrompt: (String) -> Unit
) {
    val palette = OmsTheme.colors
    var followUpText by remember { mutableStateOf("") }
    val groupedItems = remember(steps) { groupAgentTrajectorySteps(steps) }
    val listState = rememberLazyListState()

    LaunchedEffect(session.sessionId, groupedItems.size) {
        if (groupedItems.isNotEmpty()) {
            runCatching { listState.scrollToItem(groupedItems.lastIndex) }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(palette.canvas)) {
        // Minimalist Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.topBar)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                OmsCircleIconButton(
                    icon = OmsIcons.ArrowLeft,
                    contentDescription = "返回列表",
                    onClick = onBack
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.title,
                        color = palette.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${if (session.backend == "codex") "Codex" else "Antigravity"} · ${session.projectDisplayName.ifBlank { session.workspacePath.substringAfterLast('/') }} · 桌面同步可见",
                        color = palette.textTertiary,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (session.status == "running") {
                    OmsSecondaryButton(
                        label = "中止",
                        icon = OmsIcons.StopSquare,
                        height = 32.dp,
                        contentColor = palette.danger,
                        onClick = onCancel
                    )
                }
                OmsCircleIconButton(
                    icon = OmsIcons.Refresh,
                    contentDescription = "刷新轨迹",
                    size = 34.dp,
                    iconSize = 17.dp,
                    onClick = onRefresh
                )
            }
        }

        Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(palette.hairline))

        if (isLoadingDetail && groupedItems.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        color = palette.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                    Text("正在从 Mac 加载历史对话消息…", color = palette.textSecondary, fontSize = 13.5.sp)
                }
            }
        } else if (groupedItems.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (macConnected) "当前会话暂无历史消息记录，可在下方直接发送指令开始对话" else "未连通 Mac，恢复连接后可查看完整历史消息",
                    color = palette.textTertiary,
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp
                )
            }
        } else {
            // Grouped Trajectory Stream (User Bubble + Collapsible Tool Accordion + Editorial Summary)
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(
                    items = groupedItems,
                    key = {
                        when (it) {
                            is TrajectoryDisplayItem.SingleTurn -> "turn_${it.step.stepIndex}_${it.step.timestampMs}"
                            is TrajectoryDisplayItem.ToolAccordionGroup -> it.groupKey
                        }
                    }
                ) { item ->
                    when (item) {
                        is TrajectoryDisplayItem.SingleTurn -> {
                            val st = item.step
                            when (st.kind) {
                                "user" -> {
                                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                                        Box(
                                            modifier = Modifier
                                                .widthIn(max = 306.dp)
                                                .clip(RoundedCornerShape(20.dp, 20.dp, 5.dp, 20.dp))
                                                .background(palette.userBubble)
                                                .padding(horizontal = 15.dp, vertical = 10.dp)
                                        ) {
                                            Text(st.detail.ifBlank { st.title }, color = palette.userBubbleText, fontSize = 15.sp, lineHeight = 22.sp)
                                        }
                                    }
                                }
                                "status" -> {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(palette.subtleSurface)
                                            .padding(horizontal = 12.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(OmsIcons.Sparkles, contentDescription = null, tint = palette.accent, modifier = Modifier.size(14.dp))
                                        Text("${st.title} · ${st.detail}", color = palette.textSecondary, fontSize = 12.sp)
                                    }
                                }
                                else -> {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (st.title.isNotBlank()) {
                                            Text(st.title, color = palette.textTertiary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                        }
                                        IncrementalMarkdownView(markdown = st.detail)
                                    }
                                }
                            }
                        }

                        is TrajectoryDisplayItem.ToolAccordionGroup -> {
                            ToolStepAccordionView(steps = item.steps)
                        }
                    }
                }
            }
        }

        if (session.status == "failed" && session.lastSummary.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.dangerSoft)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(OmsIcons.Close, contentDescription = null, tint = palette.danger, modifier = Modifier.size(14.dp))
                Text(
                    text = session.lastSummary,
                    color = palette.danger,
                    fontSize = 12.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Sculptural Floating Pill Resume Composer
        val pillShape = RoundedCornerShape(24.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .shadow(elevation = if (palette.isDark) 2.dp else 6.dp, shape = pillShape, clip = false)
                .clip(pillShape)
                .background(palette.composer, pillShape)
                .border(0.65.dp, palette.strongBorder, pillShape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
                if (followUpText.isEmpty()) {
                    Text(
                        text = if (session.status == "running") "任务执行中，完成后可直接追问接续…" else "接续当前会话追问或下达下一步指令…",
                        color = palette.textTertiary,
                        fontSize = 14.5.sp
                    )
                }
                BasicTextField(
                    value = followUpText,
                    onValueChange = { followUpText = it },
                    textStyle = TextStyle(color = palette.textPrimary, fontSize = 15.sp, lineHeight = 21.sp),
                    cursorBrush = SolidColor(palette.accent),
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            val canResume = macConnected && followUpText.isNotBlank() && session.status != "running"
            OmsCircleIconButton(
                icon = OmsIcons.ArrowUp,
                contentDescription = "发送接续指令",
                enabled = canResume,
                size = 36.dp,
                iconSize = 18.dp,
                containerColor = palette.sendButtonBg,
                contentColor = palette.sendButtonFg,
                onClick = {
                    val prompt = followUpText.trim()
                    followUpText = ""
                    onContinuePrompt(prompt)
                }
            )
        }
    }
}

@Composable
private fun ToolStepAccordionView(steps: List<RemoteAgentStepUi>) {
    val palette = OmsTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val toolCount = steps.count { it.kind == "tool" }
    val thinkCount = steps.count { it.kind == "thinking" }
    val summary = buildString {
        append("已执行 ${steps.size} 步过程")
        if (toolCount > 0) append(" · $toolCount 次工具操作")
        if (thinkCount > 0) append(" · $thinkCount 段推演")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.subtleSurface)
            .border(0.6.dp, palette.hairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 13.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .omsBounceClick(scaleDown = 0.98f) { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(OmsIcons.Bolt, contentDescription = null, tint = palette.accent, modifier = Modifier.size(15.dp))
                Text(summary, color = palette.textSecondary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
            }
            Icon(
                imageVector = if (expanded) OmsIcons.ChevronUp else OmsIcons.ChevronDown,
                contentDescription = null,
                tint = palette.textTertiary,
                modifier = Modifier.size(15.dp)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(180)) + fadeIn(tween(140)),
            exit = shrinkVertically(tween(150)) + fadeOut(tween(110))
        ) {
            Column(
                modifier = Modifier.padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                steps.forEach { st ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(st.title, color = palette.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = st.detail.trim(),
                            color = palette.textSecondary,
                            fontSize = 12.5.sp,
                            lineHeight = 18.5.sp,
                            maxLines = 12,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentDispatchSheetContent(
    coordinator: CockpitRuntimeCoordinator,
    macConnected: Boolean,
    projects: List<com.oms.client.cockpit.RemoteAgentProjectUi>,
    sessions: List<RemoteAgentSessionUi>,
    initialPrompt: String,
    onDispatched: () -> Unit
) {
    val palette = OmsTheme.colors
    val scope = rememberCoroutineScope()
    var selectedWorkspacePath by remember(projects) {
        mutableStateOf(projects.firstOrNull()?.projectPath.orEmpty())
    }
    var showProjectPickerDialog by remember { mutableStateOf(false) }
    var projectSearchText by remember { mutableStateOf("") }
    var targetResumeSessionId by remember { mutableStateOf("") }
    var showSessionDropdown by remember { mutableStateOf(false) }
    var newBackend by remember { mutableStateOf("codex") }
    var promptText by remember(initialPrompt) { mutableStateOf(initialPrompt) }
    var isDispatching by remember { mutableStateOf(false) }

    val projectSessions = remember(sessions, selectedWorkspacePath) {
        val norm = selectedWorkspacePath.trim().trimEnd('/')
        if (norm.isEmpty()) sessions
        else sessions.filter { it.workspacePath.trim().trimEnd('/') == norm }
    }
    val chosenResumeSession = remember(projectSessions, targetResumeSessionId) {
        projectSessions.firstOrNull { it.sessionId == targetResumeSessionId }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("远程派单至 Mac 重脑", color = palette.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)

        // 1. Target Project Selector
        OmsSurfaceCard(
            containerColor = palette.subtleSurface,
            cornerRadius = 14.dp,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            onClick = { showProjectPickerDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val matchedProj = projects.firstOrNull {
                        it.projectPath.trim().trimEnd('/') == selectedWorkspacePath.trim().trimEnd('/')
                    }
                    Text("目标工作区", color = palette.textTertiary, fontSize = 11.sp)
                    Text(
                        text = matchedProj?.displayName
                            ?: selectedWorkspacePath.substringAfterLast('/').ifBlank { "点击选择 Mac 仓库…" },
                        color = palette.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${projects.size} 个仓库", color = palette.accent, fontSize = 12.sp)
                    Icon(OmsIcons.ChevronDown, null, tint = palette.textTertiary, modifier = Modifier.size(14.dp))
                }
            }
        }

        // 2. Session Mode Selector (New vs Resume)
        Box(modifier = Modifier.fillMaxWidth()) {
            OmsSurfaceCard(
                containerColor = palette.subtleSurface,
                cornerRadius = 14.dp,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                onClick = { showSessionDropdown = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("会话模式", color = palette.textTertiary, fontSize = 11.sp)
                        Text(
                            text = if (chosenResumeSession == null) "开启全新独立会话"
                            else "接续：${chosenResumeSession.title}",
                            color = palette.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(OmsIcons.ChevronDown, null, tint = palette.textTertiary, modifier = Modifier.size(14.dp))
                }
            }

            DropdownMenu(
                expanded = showSessionDropdown,
                onDismissRequest = { showSessionDropdown = false },
                modifier = Modifier.background(palette.composer).width(310.dp)
            ) {
                DropdownMenuItem(
                    text = { Text("开启全新独立会话", color = palette.accent, fontWeight = FontWeight.SemiBold) },
                    onClick = {
                        targetResumeSessionId = ""
                        showSessionDropdown = false
                    }
                )
                projectSessions.forEach { sess ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = "${if (sess.backend == "codex") "Codex" else "AGY"} · ${sess.title}",
                                    color = palette.textPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (sess.lastSummary.isNotBlank()) {
                                    Text(sess.lastSummary, color = palette.textTertiary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        },
                        onClick = {
                            targetResumeSessionId = sess.sessionId
                            newBackend = sess.backend
                            showSessionDropdown = false
                        }
                    )
                }
            }
        }

        if (chosenResumeSession == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OmsTogglePill("OpenAI Codex", newBackend == "codex", icon = OmsIcons.Terminal, onClick = { newBackend = "codex" })
                OmsTogglePill("Google Antigravity", newBackend == "agy", icon = OmsIcons.Sparkles, onClick = { newBackend = "agy" })
            }
        }

        // 3. Borderless Task Prompt Input
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp, max = 180.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(palette.subtleSurface)
                .border(0.6.dp, palette.hairline, RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            if (promptText.isEmpty()) {
                Text(
                    text = "描述要交由 Mac 自主完成的编码、排查或知识库治理任务…",
                    color = palette.textTertiary,
                    fontSize = 14.5.sp
                )
            }
            BasicTextField(
                value = promptText,
                onValueChange = { promptText = it },
                textStyle = TextStyle(color = palette.textPrimary, fontSize = 14.5.sp, lineHeight = 21.sp),
                cursorBrush = SolidColor(palette.accent),
                modifier = Modifier.fillMaxWidth()
            )
        }

        OmsPrimaryButton(
            label = if (isDispatching) "正在派发至 Mac…" else if (chosenResumeSession != null) "发送并接续会话" else "立即启动任务",
            icon = OmsIcons.ArrowUp,
            enabled = macConnected && promptText.isNotBlank() && !isDispatching,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                isDispatching = true
                scope.launch {
                    try {
                        if (chosenResumeSession != null) {
                            coordinator.continueRemoteAgentSession(
                                backend = chosenResumeSession.backend,
                                sessionId = chosenResumeSession.sessionId,
                                prompt = promptText.trim(),
                                workspacePath = selectedWorkspacePath.trim()
                            )
                        } else {
                            coordinator.startRemoteAgentSession(
                                backend = newBackend,
                                prompt = promptText.trim(),
                                workspacePath = selectedWorkspacePath.trim()
                            )
                        }
                        onDispatched()
                    } catch (e: Throwable) {
                        OmsFeedbackController.show("派单失败：${e.message}", OmsToastKind.WARNING)
                    } finally {
                        isDispatching = false
                    }
                }
            }
        )
        Spacer(modifier = Modifier.height(6.dp))
    }

    if (showProjectPickerDialog) {
        Dialog(onDismissRequest = { showProjectPickerDialog = false }) {
            val visibleProjects = remember(projects, projectSearchText) {
                val q = projectSearchText.trim().lowercase()
                if (q.isEmpty()) projects
                else projects.filter {
                    it.displayName.lowercase().contains(q) ||
                        it.projectPath.lowercase().contains(q) ||
                        it.group.lowercase().contains(q)
                }
            }
            OmsSurfaceCard(
                containerColor = palette.composer,
                borderColor = palette.strongBorder,
                cornerRadius = 22.dp,
                modifier = Modifier.fillMaxWidth().height(460.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("选择 Mac 工作区", color = palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    OmsCircleIconButton(OmsIcons.Close, "关闭", size = 32.dp, iconSize = 16.dp, onClick = { showProjectPickerDialog = false })
                }
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.subtleSurface)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    if (projectSearchText.isEmpty()) {
                        Text("搜索仓库名或路径…", color = palette.textTertiary, fontSize = 13.5.sp)
                    }
                    BasicTextField(
                        value = projectSearchText,
                        onValueChange = { projectSearchText = it },
                        singleLine = true,
                        textStyle = TextStyle(color = palette.textPrimary, fontSize = 13.5.sp),
                        cursorBrush = SolidColor(palette.accent),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(items = visibleProjects, key = { it.projectPath }) { proj ->
                        val isSel = proj.projectPath.trimEnd('/') == selectedWorkspacePath.trimEnd('/')
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .omsBounceClick(scaleDown = 0.98f, shape = RoundedCornerShape(12.dp)) {
                                    selectedWorkspacePath = proj.projectPath
                                    targetResumeSessionId = ""
                                    showProjectPickerDialog = false
                                }
                                .background(if (isSel) palette.accentSoft else palette.subtleSurface, RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(proj.displayName, color = palette.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text(proj.projectPath, color = palette.textTertiary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (proj.sessionCount > 0) {
                                Text("${proj.sessionCount} 会话", color = palette.accent, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
    }
}

// =============================================================================
// Cross-Device Dropzone (Unified Bidirectional Phone ⇄ Mac Stream)
// =============================================================================
@Composable
private fun CrossDeviceDropzoneList(streamItems: List<StreamItemModel>) {
    val context = LocalContext.current
    val palette = OmsTheme.colors
    val app = OmsApplication.instance ?: return
    var dirFilter by remember { mutableStateOf("ALL") }

    val dropItems = remember(streamItems, dirFilter) {
        streamItems.filter { item ->
            when (dirFilter) {
                "OUT" -> item.direction == "PHONE_TO_MAC"
                "IN" -> item.direction == "MAC_TO_PHONE"
                else -> true
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OmsTogglePill("全部 (${streamItems.size})", dirFilter == "ALL", onClick = { dirFilter = "ALL" })
                OmsTogglePill("发往 Mac", dirFilter == "OUT", onClick = { dirFilter = "OUT" })
                OmsTogglePill("Mac 推来", dirFilter == "IN", onClick = { dirFilter = "IN" })
            }
            OmsCircleIconButton(
                icon = OmsIcons.Trash,
                contentDescription = "清理已同步历史",
                size = 32.dp,
                iconSize = 15.5.dp,
                contentColor = palette.textTertiary,
                onClick = {
                    val removed = app.repository.clearDeliveredHistory()
                    OmsFeedbackController.show("已清理 $removed 条已送达记录", OmsToastKind.INFO)
                }
            )
        }

        if (dropItems.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(52.dp).clip(CircleShape).background(palette.subtleSurface)
                ) {
                    Icon(OmsIcons.Send, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(22.dp))
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text("跨端投递箱为空", color = palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                Text("支持将多张照片、PDF/Office、剪贴板或闪念一键加密投递至 Mac", color = palette.textTertiary, fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items = dropItems, key = { it.itemId }) { item ->
                    RefinedDropzoneItemCard(item = item, context = context)
                }
            }
        }
    }
}

@Composable
private fun RefinedDropzoneItemCard(item: StreamItemModel, context: Context) {
    val palette = OmsTheme.colors
    val app = OmsApplication.instance ?: return
    val isFromPhone = item.direction == "PHONE_TO_MAC"
    var expanded by remember(item.itemId) { mutableStateOf(false) }

    val isAudio = remember(item.localFileUri) {
        val u = item.localFileUri?.lowercase().orEmpty()
        u.endsWith(".m4a") || u.endsWith(".mp3") || u.endsWith(".wav") || u.endsWith(".aac")
    }
    var isPlayingAudio by remember(item.itemId) { mutableStateOf(VoiceRecorderHelper.playingItemId == item.itemId) }

    val timeStr = remember(item.createdAtMs) {
        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(item.createdAtMs))
    }
    val (statusLabel, statusColor) = when (item.syncStatus) {
        "DELIVERED" -> "已送达" to palette.success
        "QUEUED" -> "待连接补发" to palette.warning
        "FAILED" -> "发送失败" to palette.danger
        else -> item.syncStatus to palette.textTertiary
    }

    val otpCode = remember(item.metadataJson, item.intentTag, item.textContent) {
        runCatching {
            item.metadataJson?.let { JSONObject(it).optString("otpCode", "") }?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: if (item.intentTag == "sms_otp") {
            OtpExtractor.extractOtp(item.textContent, item.title)?.code
        } else null
    }

    OmsSurfaceCard(
        cornerRadius = 16.dp,
        contentPadding = PaddingValues(14.dp),
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    imageVector = if (isFromPhone) OmsIcons.Send else OmsIcons.Laptop,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(13.5.dp)
                )
                Text(
                    text = if (isFromPhone) "手机 ➔ Mac · #${item.intentTag}" else "Mac ➔ 手机",
                    color = palette.textSecondary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Text("$statusLabel · $timeStr", color = statusColor, fontSize = 11.sp)
        }

        if (!item.title.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = item.title,
                color = palette.textPrimary,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = if (expanded) 4 else 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        val body = item.textContent ?: item.voiceTranscript ?: item.macSavedPath ?: ""
        if (body.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = body,
                color = palette.textSecondary,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                maxLines = if (expanded) 30 else 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (item.syncStatus == "FAILED") {
            val failedMeta = runCatching { JSONObject(item.metadataJson ?: "{}") }.getOrDefault(JSONObject())
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(failedMeta.optString("failureReason", "发送未成功"), color = palette.danger, fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (failedMeta.optString("failureCode") == "INBOX_QUOTA") {
                    OmsSecondaryButton(
                        label = "重试",
                        height = 30.dp,
                        onClick = { app.transportClient.retryQuotaRejectedItem(item.itemId) }
                    )
                }
            }
        }

        // Compact Icon Action Strip
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val copyable = otpCode ?: item.textContent ?: item.voiceTranscript ?: item.macSavedPath
                if (!copyable.isNullOrBlank()) {
                    OmsMorphIconAction(
                        icon = OmsIcons.Copy,
                        contentDescription = "复制",
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("OMS", copyable))
                            OmsFeedbackController.show(
                                if (otpCode != null) "已复制验证码 $otpCode" else "已复制内容",
                                OmsToastKind.SUCCESS
                            )
                        }
                    )
                }
                if (isAudio && item.localFileUri != null) {
                    OmsCircleIconButton(
                        icon = if (isPlayingAudio) OmsIcons.StopSquare else OmsIcons.Play,
                        contentDescription = "播放语音",
                        size = 30.dp,
                        iconSize = 15.dp,
                        contentColor = palette.accent,
                        onClick = {
                            if (isPlayingAudio) {
                                VoiceRecorderHelper.stopPlayback()
                                isPlayingAudio = false
                            } else {
                                isPlayingAudio = VoiceRecorderHelper.playAudio(context, item.itemId, item.localFileUri) {
                                    isPlayingAudio = false
                                }
                            }
                        }
                    )
                }
            }
            OmsCircleIconButton(
                icon = OmsIcons.Trash,
                contentDescription = "删除记录",
                size = 30.dp,
                iconSize = 15.dp,
                contentColor = palette.textTertiary,
                onClick = { app.repository.deleteStreamItem(item.itemId) }
            )
        }
    }
}

@Composable
private fun DropToMacSheetContent(onSubmitted: () -> Unit) {
    val context = LocalContext.current
    val palette = OmsTheme.colors
    val app = OmsApplication.instance ?: return

    var noteText by remember { mutableStateOf("") }
    var selectedTag by remember { mutableStateOf("default_inbox") }
    val stagedUris = remember { mutableStateListOf<Uri>() }
    var isRecording by remember { mutableStateOf(false) }

    val photoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(AttachmentBudget.MAX_COUNT)
    ) { uris ->
        val next = (stagedUris + uris).distinct()
        val err = AttachmentResolver.selectionError(context, next)
        if (err != null) OmsFeedbackController.show(err, OmsToastKind.WARNING)
        else {
            stagedUris.clear()
            stagedUris.addAll(next)
        }
    }

    val docLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val next = (stagedUris + uris).distinct()
        val err = AttachmentResolver.selectionError(context, next)
        if (err != null) OmsFeedbackController.show(err, OmsToastKind.WARNING)
        else {
            stagedUris.clear()
            stagedUris.addAll(next)
        }
    }

    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok && VoiceRecorderHelper.startRecording(context) != null) isRecording = true
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("加密投递至 Mac 收件箱", color = palette.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                "default_inbox" to "默认收件箱",
                "pkb_vault" to "存个人知识库",
                "voice_idea" to "灵感闪念",
                "ui_bug" to "界面问题"
            ).forEach { (tag, label) ->
                OmsTogglePill(label, selectedTag == tag, onClick = { selectedTag = tag })
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OmsSecondaryButton(
                label = "相册多选",
                icon = OmsIcons.Image,
                modifier = Modifier.weight(1f),
                onClick = { photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
            )
            OmsSecondaryButton(
                label = "选择文档",
                icon = OmsIcons.FileText,
                modifier = Modifier.weight(1f),
                onClick = { docLauncher.launch(arrayOf("*/*")) }
            )
        }

        if (stagedUris.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                stagedUris.forEach { uri ->
                    val meta = remember(uri) { AttachmentResolver.describeUri(context, uri) }
                    OmsTogglePill(
                        label = "${meta.safeFileName.take(18)} ×",
                        active = true,
                        icon = OmsIcons.Paperclip,
                        onClick = { stagedUris.remove(uri) }
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 84.dp, max = 160.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(palette.subtleSurface)
                .border(0.6.dp, palette.hairline, RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            if (noteText.isEmpty()) {
                Text("输入文字或补充附件说明…", color = palette.textTertiary, fontSize = 14.5.sp)
            }
            BasicTextField(
                value = noteText,
                onValueChange = { noteText = it },
                textStyle = TextStyle(color = palette.textPrimary, fontSize = 14.5.sp, lineHeight = 21.sp),
                cursorBrush = SolidColor(palette.accent),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OmsSecondaryButton(
                label = if (isRecording) "结束并发送语音" else "录制语音闪念",
                icon = if (isRecording) OmsIcons.StopSquare else OmsIcons.Mic,
                contentColor = if (isRecording) palette.danger else palette.textPrimary,
                onClick = {
                    if (!isRecording) {
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            if (VoiceRecorderHelper.startRecording(context) != null) isRecording = true
                        } else micPerm.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        isRecording = false
                        val stopped = VoiceRecorderHelper.stopRecording()
                        if (stopped != null) {
                            val sec = ((stopped.second + 500L) / 1000L).toInt().coerceAtLeast(1)
                            app.transportClient.submitItemToMacOrQueue(
                                intentTag = if (selectedTag == "default_inbox") "voice_idea" else selectedTag,
                                itemType = "VOICE_IDEA",
                                title = "手机语音消息 (${sec}s)",
                                textContent = noteText.trim().ifEmpty { null },
                                voiceTranscript = null,
                                fileUri = Uri.fromFile(stopped.first)
                            )
                            OmsFeedbackController.show("已发送 ${sec}s 语音至 Mac", OmsToastKind.SUCCESS)
                            onSubmitted()
                        }
                    }
                }
            )

            OmsPrimaryButton(
                label = if (stagedUris.isNotEmpty()) "发送 (${stagedUris.size})" else "立即投递",
                icon = OmsIcons.ArrowUp,
                enabled = noteText.isNotBlank() || stagedUris.isNotEmpty(),
                modifier = Modifier.weight(1f),
                onClick = {
                    val txt = noteText.trim()
                    val attached = stagedUris.toList()
                    val metas = attached.map { AttachmentResolver.describeUri(context, it) }
                    val itemType = if (attached.isNotEmpty()) AttachmentResolver.inferItemType(metas.map { it.mimeType }) else "TEXT"
                    val title = when {
                        metas.size == 1 -> metas.first().safeFileName
                        metas.size > 1 -> "${metas.first().safeFileName} 等 ${metas.size} 个附件"
                        else -> txt.take(28)
                    }
                    app.transportClient.submitItemToMacOrQueue(
                        intentTag = selectedTag,
                        itemType = itemType,
                        title = title,
                        textContent = txt.ifEmpty { null },
                        voiceTranscript = null,
                        fileUri = attached.firstOrNull(),
                        additionalFileUris = if (attached.size > 1) attached.drop(1) else emptyList()
                    )
                    OmsFeedbackController.show("已投递至 Mac 收件箱", OmsToastKind.SUCCESS)
                    onSubmitted()
                }
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
    }
}
