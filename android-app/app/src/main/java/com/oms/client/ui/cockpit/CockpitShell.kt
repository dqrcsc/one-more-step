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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
    MISSION_HUB
}

data class SearchJumpRequest(
    val threadId: String,
    val messageId: String,
    val keyword: String,
    val nonce: Long = System.nanoTime()
)

/**
 * v3.0 Root Flagship Shell (`对话 · 尺素 · 协同`), Form A Minimalist TopBar,
 * ChatGPT-Grade Gesture History Drawer, Ambient Status Pill, and Global Commute Mini-Player.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmsCockpitRootScreen() {
    val context = LocalContext.current
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
    val agentSessions by coordinator.agentSessionsFlow.collectAsState()
    val otaState by app.transportClient.otaStateFlow.collectAsState()
    val isSpeakingTts by NotificationExecutor.isSpeakingFlow.collectAsState()
    val speakingTtsText by NotificationExecutor.speakingTextFlow.collectAsState()
    val omniSearchHits by coordinator.omniSearchHitsFlow.collectAsState()
    val isOmniSearching by coordinator.isOmniSearchingFlow.collectAsState()
    val activeVaultDoc by coordinator.activeVaultDocFlow.collectAsState()
    val isLoadingVaultDoc by coordinator.isLoadingVaultDocFlow.collectAsState()

    var currentTab by remember { mutableStateOf(CockpitTab.CHAT) }
    var showBotanicalCamera by remember { mutableStateOf(false) }
    var showLiveVoiceModal by remember { mutableStateOf(false) }
    var liveModalInitialGlass by remember { mutableStateOf(false) }
    var showSettingsModal by remember { mutableStateOf(false) }
    var initialSettingsTab by remember { mutableStateOf(SettingsStudioTab.APPEARANCE) }
    var showQuickThemeSheet by remember { mutableStateOf(false) }
    var showSpecimenGallery by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    var pendingSearchJump by remember { mutableStateOf<SearchJumpRequest?>(null) }
    var chatPrefillDraft by remember { mutableStateOf("") }
    var agentPrefillDraft by remember { mutableStateOf("") }
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

    // Predictive Back Gesture handling for Drawer and Secondary Tabs
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    BackHandler(enabled = !drawerState.isOpen && currentTab != CockpitTab.CHAT) {
        currentTab = CockpitTab.CHAT
    }

    val pendingApprovalsCount = remember(streamItems) {
        streamItems.count { it.itemType == "APPROVAL" && it.syncStatus == "PENDING_APPROVAL" }
    }
    val runningAgentsCount = remember(agentSessions) {
        agentSessions.count { it.status == "running" }
    }

    val connectionDotColor = when {
        activeTier != "DISCONNECTED" -> palette.success
        standbyLease == StandbyLeaseState.ACTIVE_POLLING -> palette.accent
        app.proxyManager.hasAvailableNodes() -> palette.textTertiary
        else -> palette.warning
    }

    Box(modifier = Modifier.fillMaxSize().background(palette.canvas)) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = palette.drawer,
                    drawerContentColor = palette.textPrimary,
                    modifier = Modifier.width(308.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 1. Top Search Pill + 1-Tap Incognito Button
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(palette.subtleSurface)
                                    .border(0.6.dp, palette.hairline, RoundedCornerShape(20.dp))
                                    .padding(horizontal = 12.dp)
                            ) {
                                Icon(
                                    imageVector = OmsIcons.Search,
                                    contentDescription = null,
                                    tint = palette.textTertiary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Box(modifier = Modifier.weight(1f)) {
                                    if (searchQuery.isEmpty()) {
                                        Text("搜索对话、尺素与个人知识库…", color = palette.textTertiary, fontSize = 13.5.sp)
                                    }
                                    BasicTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        singleLine = true,
                                        textStyle = TextStyle(color = palette.textPrimary, fontSize = 13.5.sp),
                                        cursorBrush = SolidColor(palette.accent),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                if (searchQuery.isNotEmpty()) {
                                    OmsCircleIconButton(
                                        icon = OmsIcons.Close,
                                        contentDescription = "清空",
                                        size = 24.dp,
                                        iconSize = 13.dp,
                                        contentColor = palette.textTertiary,
                                        onClick = { searchQuery = "" }
                                    )
                                }
                            }

                            OmsCircleIconButton(
                                icon = OmsIcons.Incognito,
                                contentDescription = "开启无痕对话",
                                size = 40.dp,
                                iconSize = 19.dp,
                                containerColor = palette.subtleSurface,
                                borderColor = palette.hairline,
                                onClick = {
                                    coordinator.chatRepo.createNewThread(incognito = true, initialTitle = "无痕对话")
                                    currentTab = CockpitTab.CHAT
                                    scope.launch { drawerState.close() }
                                }
                            )
                        }

                        // 2. Quick Curated Library & Instant Theme Bar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .omsBounceClick(scaleDown = 0.96f, shape = RoundedCornerShape(12.dp)) {
                                        scope.launch { drawerState.close() }
                                        showSpecimenGallery = true
                                    }
                                    .background(palette.cardSurface, RoundedCornerShape(12.dp))
                                    .border(0.6.dp, palette.hairline, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 11.dp)
                            ) {
                                Icon(OmsIcons.Leaf, contentDescription = null, tint = palette.accent, modifier = Modifier.size(16.dp))
                                Text("博物图鉴册", color = palette.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }

                            // 1-Tap Appearance Mode Cycle (`跟随 / 素昼 / 玄夜`)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .height(38.dp)
                                    .omsBounceClick(scaleDown = 0.95f, shape = RoundedCornerShape(12.dp)) {
                                        coordinator.vibrateTick()
                                        OmsThemeController.cycleAppearanceMode(context)
                                    }
                                    .background(palette.cardSurface, RoundedCornerShape(12.dp))
                                    .border(0.6.dp, palette.hairline, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 11.dp)
                            ) {
                                Icon(
                                    imageVector = if (palette.isDark) OmsIcons.Moon else OmsIcons.Sun,
                                    contentDescription = "切换日夜模式",
                                    tint = palette.accent,
                                    modifier = Modifier.size(15.5.dp)
                                )
                                Text(themeSettings.mode.label, color = palette.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                        }

                        // 5-Palette Quick Swatch Strip right inside Drawer!
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(palette.subtleSurface)
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("主题", color = palette.textTertiary, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                OmsThemeStyle.entries.forEach { st ->
                                    val isSel = themeSettings.style == st
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(22.dp)
                                            .omsBounceClick(scaleDown = 0.85f, shape = CircleShape) {
                                                coordinator.vibrateTick()
                                                OmsThemeController.setThemeStyle(context, st)
                                            }
                                            .border(
                                                width = if (isSel) 1.8.dp else 0.7.dp,
                                                color = if (isSel) palette.accent else palette.strongBorder,
                                                shape = CircleShape
                                            )
                                            .padding(2.5.dp)
                                            .clip(CircleShape)
                                            .background(if (palette.isDark) st.swatchDark else st.swatchLight)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(st.swatchAccent)
                                        )
                                    }
                                }
                            }
                        }

                        // 3. Omni-Search Results (Local Chat + Mac Full-Vault FTS5 & Chisu) OR Grouped Conversation History
                        LaunchedEffect(searchQuery, activeTier) {
                            val q = searchQuery.trim()
                            if (q.length < 2) {
                                coordinator.searchOmniContext("")
                            } else {
                                delay(260L)
                                coordinator.searchOmniContext(q)
                            }
                        }

                        if (searchQuery.isNotBlank()) {
                            val hits = remember(searchQuery, threads) {
                                coordinator.chatRepo.searchGlobalMessages(searchQuery, limit = 15)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "对话 ${hits.size} 条 · 知识库/尺素 ${omniSearchHits.size} 条",
                                    color = palette.textTertiary,
                                    fontSize = 11.5.sp
                                )
                                if (isOmniSearching) {
                                    Text("检索中…", color = palette.accent, fontSize = 11.sp)
                                }
                            }
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (omniSearchHits.isNotEmpty()) {
                                    item(key = "sec_omni_remote") {
                                        Text(
                                            text = "个人知识库、书库与尺素",
                                            color = palette.textTertiary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp)
                                        )
                                    }
                                    items(
                                        items = omniSearchHits,
                                        key = { "omni_${it.source}_${it.pathOrRef}" }
                                    ) { hit ->
                                        OmsSurfaceCard(
                                            cornerRadius = 13.dp,
                                            contentPadding = PaddingValues(11.dp),
                                            onClick = {
                                                if (hit.source == "chisu" && (hit.articleId != null || hit.turnId != null)) {
                                                    coordinator.openChisuArticle(
                                                        ChisuArticleItemUi(
                                                            stableKey = hit.turnId?.let { "turn_$it" } ?: "wx_${hit.articleId ?: 0}",
                                                            articleId = hit.articleId,
                                                            turnId = hit.turnId,
                                                            entryKind = if (hit.turnId != null) "turn" else "article",
                                                            title = hit.title,
                                                            createdAtMs = System.currentTimeMillis()
                                                        )
                                                    )
                                                    currentTab = CockpitTab.CHISU
                                                    scope.launch { drawerState.close() }
                                                } else if (hit.source == "vault" && hit.pathOrRef.isNotBlank()) {
                                                    coordinator.openVaultDoc(hit.pathOrRef)
                                                    scope.launch { drawerState.close() }
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = hit.category.ifBlank { if (hit.source == "chisu") "尺素" else "知识库" },
                                                    color = palette.accent,
                                                    fontSize = 10.5.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(5.dp))
                                                        .background(palette.accentSoft)
                                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                                )
                                                Text(
                                                    text = hit.title,
                                                    color = palette.textPrimary,
                                                    fontSize = 12.5.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                            if (hit.snippet.isNotBlank()) {
                                                Spacer(modifier = Modifier.height(3.dp))
                                                Text(
                                                    text = hit.snippet,
                                                    color = palette.textSecondary,
                                                    fontSize = 11.5.sp,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                                if (hits.isNotEmpty()) {
                                    item(key = "sec_local_chat_hits") {
                                        Text(
                                            text = "手机历史对话",
                                            color = palette.textTertiary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp)
                                        )
                                    }
                                    items(items = hits, key = { it.messageId }) { hit ->
                                        OmsSurfaceCard(
                                            cornerRadius = 13.dp,
                                            contentPadding = PaddingValues(11.dp),
                                            onClick = {
                                                val kw = searchQuery.trim()
                                                coordinator.chatRepo.selectThread(hit.threadId)
                                                pendingSearchJump = SearchJumpRequest(
                                                    threadId = hit.threadId,
                                                    messageId = hit.messageId,
                                                    keyword = kw
                                                )
                                                currentTab = CockpitTab.CHAT
                                                scope.launch { drawerState.close() }
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(hit.threadTitle, color = palette.accent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(hit.snippet, color = palette.textSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        } else {
                            val groupedThreads = remember(threads) { groupThreadsByRecency(threads) }
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                groupedThreads.forEach { (sectionTitle, sectionItems) ->
                                    item(key = "sec_$sectionTitle") {
                                        Text(
                                            text = sectionTitle,
                                            color = palette.textTertiary,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp)
                                        )
                                    }
                                    items(items = sectionItems, key = { it.threadId }) { thr ->
                                        DrawerThreadItemRow(
                                            thread = thr,
                                            selected = thr.threadId == activeThreadId,
                                            onSelect = {
                                                coordinator.chatRepo.selectThread(thr.threadId)
                                                currentTab = CockpitTab.CHAT
                                                scope.launch { drawerState.close() }
                                            },
                                            onTogglePin = { coordinator.chatRepo.togglePinThread(thr.threadId) },
                                            onRename = {
                                                renamingThread = thr
                                                renameInput = thr.title
                                            },
                                            onDelete = { coordinator.chatRepo.deleteThread(thr.threadId) }
                                        )
                                    }
                                }
                            }
                        }

                        Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(palette.hairline))

                        // 4. Minimalist Drawer Footer (Status + Settings Studio Button)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(9.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .omsBounceClick(scaleDown = 0.97f, shape = RoundedCornerShape(12.dp)) {
                                        scope.launch { drawerState.close() }
                                        initialSettingsTab = SettingsStudioTab.SYSTEM_PAIRING
                                        showSettingsModal = true
                                    }
                                    .padding(vertical = 4.dp, horizontal = 4.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(palette.accentSoft)
                                ) {
                                    Icon(OmsIcons.Sparkles, contentDescription = null, tint = palette.accent, modifier = Modifier.size(16.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("青简 · 咫尺", color = palette.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                    val sub = when {
                                        activeTier == "LAN_WS" -> "Mac 局域网直连在线"
                                        activeTier == "WAN_WEBRTC" -> "Mac 跨网远程在线"
                                        activeTier == "BT_RFCOMM" -> "Mac 蓝牙近场在线"
                                        standbyLease == StandbyLeaseState.ACTIVE_POLLING -> "手机接管尺素轮询中"
                                        else -> "随身独立云端模式"
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Box(modifier = Modifier.size(5.5.dp).background(connectionDotColor, CircleShape))
                                        Text(sub, color = palette.textTertiary, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }

                            OmsCircleIconButton(
                                icon = OmsIcons.Sliders,
                                contentDescription = "打开偏好设置",
                                size = 38.dp,
                                iconSize = 18.5.dp,
                                containerColor = palette.subtleSurface,
                                borderColor = palette.hairline,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    initialSettingsTab = SettingsStudioTab.APPEARANCE
                                    showSettingsModal = true
                                }
                            )
                        }
                    }
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(palette.canvas)
            ) {
                // =================================================================
                // Top 52dp Minimalist App Bar (Form A: Menu | 对话·尺素·协同 | Dot + NewChat)
                // =================================================================
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.topBar)
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: Vector Menu Button
                    OmsCircleIconButton(
                        icon = OmsIcons.Menu,
                        contentDescription = "打开历史与记忆抽屉",
                        size = 38.dp,
                        iconSize = 20.dp,
                        onClick = { scope.launch { drawerState.open() } }
                    )

                    // Center: Silky Sliding 3-Domain Switcher (Zero Emoji!)
                    val hubDot = when {
                        pendingApprovalsCount > 0 -> palette.warning
                        runningAgentsCount > 0 -> palette.success
                        else -> null
                    }
                    OmsSlidingSegmentedBar(
                        items = listOf(
                            OmsSegmentItem(CockpitTab.CHAT, "对话"),
                            OmsSegmentItem(CockpitTab.CHISU, "尺素"),
                            OmsSegmentItem(CockpitTab.MISSION_HUB, "协同", dotColor = hubDot)
                        ),
                        selected = currentTab,
                        onSelect = { chosen ->
                            coordinator.vibrateTick()
                            currentTab = chosen
                        },
                        height = 35.dp,
                        modifier = Modifier.width(204.dp)
                    )

                    // Right: Subtle Ambient Status Orb + 1-Tap New Chat Button
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        OmsCircleIconButton(
                            icon = OmsIcons.Palette,
                            contentDescription = "主题与连接速览",
                            size = 36.dp,
                            iconSize = 18.dp,
                            contentColor = palette.textSecondary,
                            badgeColor = connectionDotColor,
                            onClick = { showQuickThemeSheet = true }
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
                                currentTab = CockpitTab.CHAT
                            }
                        )
                    }
                }

                // Zero-Ops Global Ambient Status Pill (0px when healthy!)
                GlobalAmbientStatusBanner(
                    isGuiEmergencyStopped = isGuiEmergencyStopped,
                    pendingApprovalsCount = if (currentTab == CockpitTab.MISSION_HUB) 0 else pendingApprovalsCount,
                    otaState = otaState,
                    onResumeGui = {
                        OmsAccessibilityService.instance?.resumeFromPhoneUi()
                        isGuiEmergencyStopped = false
                        OmsFeedbackController.show("已解除急停，恢复远程协助", OmsToastKind.SUCCESS)
                    },
                    onOpenApprovals = { currentTab = CockpitTab.MISSION_HUB },
                    onInstallOta = {
                        app.transportClient.installOrResumeReadyOta { msg ->
                            OmsFeedbackController.show(msg, OmsToastKind.INFO)
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
                                prefillDraft = chatPrefillDraft,
                                onConsumePrefillDraft = { chatPrefillDraft = "" },
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
                                onOpenVaultDoc = { relPath -> coordinator.openVaultDoc(relPath) }
                            )

                            CockpitTab.CHISU -> ChisuWorkspaceView(
                                coordinator = coordinator,
                                macConnected = activeTier != "DISCONNECTED",
                                streamItems = streamItems,
                                onDiscussArticleInChat = { title, cleanBody ->
                                    chatPrefillDraft = "请结合这篇尺素文章《${title.ifBlank { "文章" }}》帮我进一步深入分析：\n${cleanBody.take(1800)}"
                                    currentTab = CockpitTab.CHAT
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
                                onConsumePrefillAgentPrompt = { agentPrefillDraft = "" }
                            )
                        }
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
                                    chatPrefillDraft = "请结合我知识库里的《${doc.title}》（${doc.relPath}）帮我深入分析与延展：\n${clean.take(1800)}"
                                    coordinator.closeVaultDoc()
                                    currentTab = CockpitTab.CHAT
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
                    chatPrefillDraft = "关于《${card.chineseName}》（${card.latinName}），我还想请教："
                    currentTab = CockpitTab.CHAT
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
                    chatPrefillDraft = "关于《${card.chineseName}》（${card.latinName}），我还想了解："
                    currentTab = CockpitTab.CHAT
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
private fun DrawerThreadItemRow(
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
        is OtaUiState.ReadyToInstall -> otaState.ota
        is OtaUiState.InstallerLaunched -> otaState.ota
        is OtaUiState.UpdateAvailable -> otaState.ota
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                        .clip(shape)
                        .background(palette.successSoft)
                        .border(0.7.dp, palette.success.copy(alpha = 0.45f), shape)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                        Icon(OmsIcons.Bolt, null, tint = palette.success, modifier = Modifier.size(15.dp))
                        Text("新版本 v${readyOta.remoteVersionName} 已就绪", color = palette.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    OmsPrimaryButton(
                        label = "安装",
                        height = 29.dp,
                        containerColor = palette.success,
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

private fun groupThreadsByRecency(threads: List<ChatThreadModel>): List<Pair<String, List<ChatThreadModel>>> {
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
