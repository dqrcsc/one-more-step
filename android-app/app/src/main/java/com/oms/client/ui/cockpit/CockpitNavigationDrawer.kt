package com.oms.client.ui.cockpit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oms.client.OmsApplication
import com.oms.client.cockpit.ChatThreadModel
import com.oms.client.cockpit.ChisuArticleItemUi
import com.oms.client.cockpit.CockpitRuntimeCoordinator
import com.oms.client.cockpit.OmniSearchHitUi
import com.oms.client.cockpit.RemoteAgentProjectUi
import com.oms.client.cockpit.RemoteAgentQuotasUi
import com.oms.client.cockpit.RemoteAgentSessionUi
import com.oms.client.edge.StandbyLeaseState
import com.oms.client.ui.theme.OmsCircleIconButton
import com.oms.client.ui.theme.OmsIcons
import com.oms.client.ui.theme.OmsSurfaceCard
import com.oms.client.ui.theme.OmsTheme
import com.oms.client.ui.theme.OmsThemeController
import com.oms.client.ui.theme.OmsThemeSettings
import com.oms.client.ui.theme.omsBounceClick
import kotlinx.coroutines.delay
import java.util.Locale

internal data class ProjectSessionGroupUi(
    val normPath: String,
    val rawPath: String,
    val displayName: String,
    val sessions: List<RemoteAgentSessionUi>,
    val runningCount: Int,
    val lastActiveAtMs: Long
)

internal fun agentDisplayName(backend: String): String = when (backend) {
    "codex" -> "Codex"
    "agy" -> "Antigravity"
    "workbuddy" -> "WorkBuddy"
    "opencode" -> "OpenCode"
    else -> backend
}

internal fun agentShortBadge(backend: String): String = when (backend) {
    "codex" -> "Codex"
    "agy" -> "AGY"
    "workbuddy" -> "WB"
    "opencode" -> "OC"
    else -> backend.take(3).uppercase(Locale.ROOT)
}

internal fun buildProjectSessionGroups(
    projects: List<RemoteAgentProjectUi>,
    sessions: List<RemoteAgentSessionUi>,
    backendFilter: String = "ALL"
): List<ProjectSessionGroupUi> {
    val filteredSessions = if (backendFilter == "ALL") {
        sessions
    } else {
        sessions.filter { it.backend == backendFilter }
    }

    val sessionsByNormPath = filteredSessions.groupBy { s ->
        s.workspacePath.trim().trimEnd('/').ifBlank { "__unassigned__" }
    }
    val projectsByNormPath = projects.associateBy { p ->
        p.projectPath.trim().trimEnd('/')
    }

    val allKeys = linkedSetOf<String>()
    sessionsByNormPath.keys.forEach { allKeys.add(it) }
    if (backendFilter == "ALL") {
        projects.filter { it.sessionCount > 0 || it.runningCount > 0 }.forEach {
            val k = it.projectPath.trim().trimEnd('/')
            if (k.isNotBlank()) allKeys.add(k)
        }
    }

    return allKeys.mapNotNull { norm ->
        val groupSessions = sessionsByNormPath[norm].orEmpty().sortedByDescending { it.updatedAtMs }
        val proj = projectsByNormPath[norm]
        if (groupSessions.isEmpty() && proj == null) return@mapNotNull null

        val rawPath = proj?.projectPath?.takeIf { it.isNotBlank() }
            ?: groupSessions.firstOrNull()?.workspacePath.orEmpty()
        val displayName = proj?.displayName?.takeIf { it.isNotBlank() }
            ?: groupSessions.firstOrNull()?.projectDisplayName?.takeIf { it.isNotBlank() }
            ?: rawPath.trimEnd('/').substringAfterLast('/').ifBlank { "未归属工作区" }
        val running = groupSessions.count { it.status == "running" }.coerceAtLeast(
            if (backendFilter == "ALL") (proj?.runningCount ?: 0) else 0
        )
        val lastMs = maxOf(
            groupSessions.maxOfOrNull { it.updatedAtMs } ?: 0L,
            proj?.lastActiveAtMs ?: 0L
        )
        ProjectSessionGroupUi(
            normPath = norm,
            rawPath = rawPath,
            displayName = displayName,
            sessions = groupSessions,
            runningCount = running,
            lastActiveAtMs = lastMs
        )
    }.sortedWith(
        compareByDescending<ProjectSessionGroupUi> { it.runningCount > 0 }
            .thenByDescending { it.lastActiveAtMs }
            .thenBy { it.displayName.lowercase(Locale.ROOT) }
    )
}

internal fun formatCompactRelativeTime(timestampMs: Long): String {
    if (timestampMs <= 0L) return ""
    val diffMs = (System.currentTimeMillis() - timestampMs).coerceAtLeast(0L)
    val mins = diffMs / 60_000L
    return when {
        mins < 2L -> "刚刚"
        mins < 60L -> "${mins}分前"
        mins < 24L * 60L -> "${mins / 60L}小时前"
        mins < 48L * 60L -> "昨天"
        mins < 7L * 24L * 60L -> "${mins / (24L * 60L)}天前"
        else -> java.text.SimpleDateFormat("MM/dd", Locale.CHINA).format(java.util.Date(timestampMs))
    }
}

internal fun formatMiniQuotaSummary(quotas: RemoteAgentQuotasUi?): String {
    if (quotas == null) return ""
    val codexRem = quotas.codex.windows.firstOrNull()?.remainingPercent
    val agyRem = quotas.agy.windows.firstOrNull()?.remainingPercent
    val agyLimited = quotas.agy.statusNote.contains("上限") ||
        quotas.agy.statusNote.contains("受限") ||
        (quotas.agy.statusNote.contains("触发") && !quotas.agy.statusNote.contains("未触发"))

    val parts = buildList {
        if (codexRem != null) add("C:${codexRem}%")
        if (agyRem != null) add("A:${agyRem}%")
        else if (quotas.agy.available) add(if (agyLimited) "A:限流" else "A:正常")
    }
    return parts.joinToString(" · ")
}

@Composable
internal fun CockpitUnifiedDrawerSheet(
    coordinator: CockpitRuntimeCoordinator,
    currentTab: CockpitTab,
    isChatThreadOpen: Boolean,
    activeTier: String,
    standbyLease: StandbyLeaseState,
    connectionDotColor: Color,
    themeSettings: OmsThemeSettings,
    threads: List<ChatThreadModel>,
    activeThreadId: String,
    agentProjects: List<RemoteAgentProjectUi>,
    agentSessions: List<RemoteAgentSessionUi>,
    agentQuotas: RemoteAgentQuotasUi?,
    omniSearchHits: List<OmniSearchHitUi>,
    isOmniSearching: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    focusSearchNonce: Long,
    onOpenWorkbench: () -> Unit,
    onSelectTab: (CockpitTab) -> Unit,
    onOpenSpecimenGallery: () -> Unit,
    onOpenMemoryCenter: () -> Unit = {},
    onOpenDropzone: () -> Unit,
    onStartIncognitoChat: () -> Unit,
    onStartNewChat: () -> Unit,
    onSelectThread: (ChatThreadModel) -> Unit,
    onRenameThread: (ChatThreadModel) -> Unit,
    onSelectSearchChatHit: (threadId: String, messageId: String, keyword: String) -> Unit,
    onSelectChisuHit: (ChisuArticleItemUi) -> Unit,
    onSelectVaultDocHit: (relPath: String) -> Unit,
    onSelectAgentSession: (RemoteAgentSessionUi) -> Unit,
    onDispatchForProject: (projectPath: String) -> Unit,
    onOpenSettings: (SettingsStudioTab) -> Unit
) {
    val context = LocalContext.current
    val palette = OmsTheme.colors
    val app = OmsApplication.instance
    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(focusSearchNonce) {
        if (focusSearchNonce > 0L) {
            delay(180L)
            runCatching { searchFocusRequester.requestFocus() }
        }
    }

    // Priority 1: Everyday Personal Assistant Conversations ALWAYS expanded at top!
    // Priority 2: Computer Collaboration collapsed by default (`false`) at bottom!
    var chatSectionExpanded by remember { mutableStateOf(true) }
    var macSectionExpanded by remember { mutableStateOf(false) }
    var selectedAgentBackend by remember { mutableStateOf("codex") }

    val pairedMacName = remember(activeTier) {
        app?.repository?.getPairedMac()?.macName?.ifBlank { "我的 Mac" } ?: "我的 Mac"
    }
    val runningAgentCount = remember(agentSessions) {
        agentSessions.count { it.status == "running" }
    }
    val selectedBackendSessions = remember(agentSessions, selectedAgentBackend) {
        agentSessions.filter { it.backend == selectedAgentBackend }.sortedWith(
            compareByDescending<RemoteAgentSessionUi> { it.status == "running" }
                .thenByDescending { it.updatedAtMs }
        )
    }
    val showAllDrawerAgentSessions = remember { mutableStateMapOf<String, Boolean>() }

    // Omni-search debounce trigger
    LaunchedEffect(searchQuery, activeTier) {
        val q = searchQuery.trim()
        if (q.length < 2) {
            coordinator.searchOmniContext("")
        } else {
            delay(260L)
            coordinator.searchOmniContext(q)
        }
    }

    ModalDrawerSheet(
        drawerContainerColor = palette.drawer,
        drawerContentColor = palette.textPrimary,
        modifier = Modifier.width(316.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 13.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            // 1. Unified Omni-Search Pill + 1-Tap Incognito Button
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
                        modifier = Modifier.size(15.5.dp)
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "搜随身助理对话·记忆·尺素·协同…",
                                color = palette.textTertiary,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            singleLine = true,
                            textStyle = TextStyle(color = palette.textPrimary, fontSize = 13.5.sp),
                            cursorBrush = SolidColor(palette.accent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester)
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        OmsCircleIconButton(
                            icon = OmsIcons.Close,
                            contentDescription = "清空",
                            size = 24.dp,
                            iconSize = 13.dp,
                            contentColor = palette.textTertiary,
                            onClick = { onSearchQueryChange("") }
                        )
                    }
                }

                OmsCircleIconButton(
                    icon = OmsIcons.Incognito,
                    contentDescription = "开启无痕对话",
                    size = 40.dp,
                    iconSize = 18.5.dp,
                    containerColor = palette.subtleSurface,
                    borderColor = palette.hairline,
                    onClick = onStartIncognitoChat
                )
            }

            // 2. Compact 2x2 Quick Navigation Grid (Includes dedicated `记忆中心`!)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                    DrawerQuickNavPill(
                        icon = OmsIcons.LayoutGrid,
                        label = "工作台",
                        active = currentTab == CockpitTab.CHAT && !isChatThreadOpen,
                        onClick = onOpenWorkbench,
                        modifier = Modifier.weight(1f)
                    )
                    DrawerQuickNavPill(
                        icon = OmsIcons.Bookmark,
                        label = "记忆中心",
                        active = false,
                        onClick = onOpenMemoryCenter,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                    DrawerQuickNavPill(
                        icon = OmsIcons.BookOpen,
                        label = "尺素伴读",
                        active = currentTab == CockpitTab.CHISU,
                        onClick = { onSelectTab(CockpitTab.CHISU) },
                        modifier = Modifier.weight(1f)
                    )
                    DrawerQuickNavPill(
                        icon = OmsIcons.Leaf,
                        label = "图鉴册",
                        active = false,
                        onClick = onOpenSpecimenGallery,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 3. Main Scroll Area: Search Results OR Everyday Assistant Dialogues (Top) + Collapsible Collaboration (Bottom)
            if (searchQuery.isNotBlank()) {
                val qLower = searchQuery.trim().lowercase(Locale.ROOT)
                val chatHits = remember(searchQuery, threads) {
                    coordinator.chatRepo.searchGlobalMessages(searchQuery, limit = 15)
                }
                val matchedAgents = remember(searchQuery, agentSessions) {
                    agentSessions.filter { s ->
                        s.title.lowercase(Locale.ROOT).contains(qLower) ||
                            s.projectDisplayName.lowercase(Locale.ROOT).contains(qLower) ||
                            s.workspacePath.lowercase(Locale.ROOT).contains(qLower) ||
                            s.lastSummary.lowercase(Locale.ROOT).contains(qLower)
                    }.take(12)
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "对话 ${chatHits.size} · 文库 ${omniSearchHits.size} · 协同 ${matchedAgents.size}",
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
                    // Search Priority 1: Everyday Assistant Chats
                    if (chatHits.isNotEmpty()) {
                        item(key = "sec_search_chat") {
                            DrawerSearchSectionLabel("随身助理对话")
                        }
                        items(items = chatHits, key = { it.messageId }) { hit ->
                            OmsSurfaceCard(
                                cornerRadius = 13.dp,
                                contentPadding = PaddingValues(10.dp),
                                onClick = {
                                    onSelectSearchChatHit(hit.threadId, hit.messageId, searchQuery.trim())
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(hit.threadTitle, color = palette.accent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(hit.snippet, color = palette.textSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }

                    // Search Priority 2: Vault / Chisu
                    if (omniSearchHits.isNotEmpty()) {
                        item(key = "sec_search_omni") {
                            DrawerSearchSectionLabel("个人知识库、书库与尺素")
                        }
                        items(items = omniSearchHits, key = { "omni_${it.source}_${it.pathOrRef}" }) { hit ->
                            OmsSurfaceCard(
                                cornerRadius = 13.dp,
                                contentPadding = PaddingValues(10.dp),
                                onClick = {
                                    if (hit.source == "chisu" && (hit.articleId != null || hit.turnId != null)) {
                                        onSelectChisuHit(
                                            ChisuArticleItemUi(
                                                stableKey = hit.turnId?.let { "turn_$it" } ?: "wx_${hit.articleId ?: 0}",
                                                articleId = hit.articleId,
                                                turnId = hit.turnId,
                                                entryKind = if (hit.turnId != null) "turn" else "article",
                                                title = hit.title,
                                                createdAtMs = System.currentTimeMillis()
                                            )
                                        )
                                    } else if (hit.source == "vault" && hit.pathOrRef.isNotBlank()) {
                                        onSelectVaultDocHit(hit.pathOrRef)
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

                    // Search Priority 3: Mac Collaboration Sessions
                    if (matchedAgents.isNotEmpty()) {
                        item(key = "sec_search_agents") {
                            DrawerSearchSectionLabel("电脑协同会话")
                        }
                        items(items = matchedAgents, key = { "sa_${it.backend}:${it.sessionId}" }) { sess ->
                            val projLabel = sess.projectDisplayName.ifBlank {
                                sess.workspacePath.trimEnd('/').substringAfterLast('/').ifBlank { "工作区" }
                            }
                            OmsSurfaceCard(
                                cornerRadius = 13.dp,
                                contentPadding = PaddingValues(10.dp),
                                onClick = { onSelectAgentSession(sess) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = agentShortBadge(sess.backend),
                                        color = palette.accent,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(5.dp))
                                            .background(palette.accentSoft)
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                    Text(
                                        text = projLabel,
                                        color = palette.textTertiary,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = formatCompactRelativeTime(sess.updatedAtMs),
                                        color = palette.textTertiary,
                                        fontSize = 10.5.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = sess.title.ifBlank { "未命名任务" },
                                    color = palette.textPrimary,
                                    fontSize = 12.8.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
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
                    // =============================================================
                    // Section 1 (TOP, Default Expanded): 随身助理对话
                    // =============================================================
                    item(key = "drawer_sec_chat_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(9.dp))
                                .clickable { chatSectionExpanded = !chatSectionExpanded }
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = if (chatSectionExpanded) OmsIcons.ChevronDown else OmsIcons.ChevronRight,
                                    contentDescription = null,
                                    tint = palette.textTertiary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = "随身助理对话 (${threads.size})",
                                    color = palette.textSecondary,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.4.sp
                                )
                            }
                            OmsCircleIconButton(
                                icon = OmsIcons.NewChat,
                                contentDescription = "新建对话",
                                size = 24.dp,
                                iconSize = 13.5.dp,
                                contentColor = palette.accent,
                                onClick = onStartNewChat
                            )
                        }
                    }

                    if (chatSectionExpanded) {
                        groupedThreads.forEach { (sectionTitle, sectionItems) ->
                            item(key = "sec_$sectionTitle") {
                                Text(
                                    text = sectionTitle,
                                    color = palette.textTertiary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(start = 8.dp, top = 7.dp, bottom = 3.dp)
                                )
                            }
                            items(items = sectionItems, key = { it.threadId }) { thr ->
                                DrawerThreadItemRow(
                                    thread = thr,
                                    selected = isChatThreadOpen && thr.threadId == activeThreadId,
                                    onSelect = { onSelectThread(thr) },
                                    onTogglePin = { coordinator.chatRepo.togglePinThread(thr.threadId) },
                                    onRename = { onRenameThread(thr) },
                                    onDelete = { coordinator.chatRepo.deleteThread(thr.threadId) }
                                )
                            }
                        }
                    }

                    item(key = "drawer_divider_mid") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .height(0.5.dp)
                                .background(palette.hairline)
                        )
                    }

                    // =============================================================
                    // Section 2 (BOTTOM, Default Collapsed!): 电脑协同 (Mac -> Agent -> 会话)
                    // =============================================================
                    item(key = "drawer_sec_mac_header") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp, bottom = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(9.dp))
                                    .clickable { macSectionExpanded = !macSectionExpanded }
                                    .padding(horizontal = 4.dp, vertical = 5.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = if (macSectionExpanded) OmsIcons.ChevronDown else OmsIcons.ChevronRight,
                                        contentDescription = null,
                                        tint = palette.textTertiary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = "电脑协同 · $pairedMacName",
                                        color = palette.textSecondary,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (runningAgentCount > 0) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(palette.successSoft)
                                                .padding(horizontal = 5.dp, vertical = 1.5.dp)
                                        ) {
                                            Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(palette.success))
                                            Text(
                                                text = "${runningAgentCount}运行中",
                                                color = palette.success,
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    } else {
                                        Text(
                                            text = "${agentSessions.size}",
                                            color = palette.textTertiary,
                                            fontSize = 10.5.sp
                                        )
                                    }
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OmsCircleIconButton(
                                        icon = OmsIcons.Refresh,
                                        contentDescription = "刷新协同会话",
                                        size = 24.dp,
                                        iconSize = 13.dp,
                                        contentColor = palette.textTertiary,
                                        onClick = { coordinator.refreshAgentSessions() }
                                    )
                                    OmsCircleIconButton(
                                        icon = OmsIcons.Plus,
                                        contentDescription = "派发新任务",
                                        size = 24.dp,
                                        iconSize = 13.5.dp,
                                        contentColor = palette.accent,
                                        onClick = { onDispatchForProject("") }
                                    )
                                }
                            }

                            AnimatedVisibility(
                                visible = macSectionExpanded,
                                enter = expandVertically(tween(150)) + fadeIn(tween(120)),
                                exit = shrinkVertically(tween(120)) + fadeOut(tween(100))
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    // Level 2: 4 Agent Selectors (Codex / Antigravity / WorkBuddy / OpenCode)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState())
                                            .padding(horizontal = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        listOf("codex", "agy", "workbuddy", "opencode").forEach { b ->
                                            val cnt = agentSessions.count { it.backend == b }
                                            DrawerMiniFilterChip(
                                                label = "${agentDisplayName(b)} ($cnt)",
                                                active = selectedAgentBackend == b,
                                                onClick = { selectedAgentBackend = b }
                                            )
                                        }
                                        Text(
                                            text = "投递箱 ↗",
                                            color = palette.textTertiary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable { onOpenDropzone() }
                                                .padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (macSectionExpanded) {
                        if (selectedBackendSessions.isEmpty()) {
                            item(key = "drawer_mac_empty") {
                                Text(
                                    text = if (activeTier == "DISCONNECTED") {
                                        "连上电脑后将同步 ${agentDisplayName(selectedAgentBackend)} 会话"
                                    } else {
                                        "暂无 ${agentDisplayName(selectedAgentBackend)} 会话，点 + 派发任务"
                                    },
                                    color = palette.textTertiary,
                                    fontSize = 11.5.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        } else {
                            val showAll = showAllDrawerAgentSessions[selectedAgentBackend] == true
                            val visible = if (showAll) selectedBackendSessions else selectedBackendSessions.take(6)
                            val hiddenCount = (selectedBackendSessions.size - visible.size).coerceAtLeast(0)

                            items(
                                items = visible,
                                key = { "ds_${it.backend}:${it.sessionId}" }
                            ) { sess ->
                                DrawerAgentSessionRow(
                                    session = sess,
                                    onClick = { onSelectAgentSession(sess) }
                                )
                            }

                            if (hiddenCount > 0 || (showAll && selectedBackendSessions.size > 6)) {
                                item(key = "drawer_agent_more_$selectedAgentBackend") {
                                    Text(
                                        text = if (hiddenCount > 0) "··· 展开其余 $hiddenCount 条会话" else "收起多余会话",
                                        color = palette.accent,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { showAllDrawerAgentSessions[selectedAgentBackend] = !showAll }
                                            .padding(start = 16.dp, top = 4.dp, bottom = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(palette.hairline))

            // 4. Clean Drawer Footer: Connection Status + 1-Tap Day/Night + Settings
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 1.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .omsBounceClick(scaleDown = 0.97f, shape = RoundedCornerShape(12.dp)) {
                            onOpenSettings(SettingsStudioTab.SYSTEM_PAIRING)
                        }
                        .padding(vertical = 4.dp, horizontal = 4.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(palette.accentSoft)
                    ) {
                        Icon(OmsIcons.Sparkles, contentDescription = null, tint = palette.accent, modifier = Modifier.size(15.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("青简 · 随身助理", color = palette.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        val sub = when {
                            activeTier == "LAN_WS" -> "$pairedMacName · 局域网直连"
                            activeTier == "WAN_WEBRTC" -> "$pairedMacName · 跨网在线"
                            activeTier == "BT_RFCOMM" -> "$pairedMacName · 蓝牙近场"
                            standbyLease == StandbyLeaseState.ACTIVE_POLLING -> "手机值守尺素中"
                            else -> "随身独立云端模式"
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(modifier = Modifier.size(5.5.dp).background(connectionDotColor, CircleShape))
                            Text(sub, color = palette.textTertiary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OmsCircleIconButton(
                        icon = if (palette.isDark) OmsIcons.Moon else OmsIcons.Sun,
                        contentDescription = "切换日夜模式 (${themeSettings.mode.label})",
                        size = 36.dp,
                        iconSize = 17.dp,
                        containerColor = palette.subtleSurface,
                        borderColor = palette.hairline,
                        contentColor = palette.accent,
                        onClick = {
                            coordinator.vibrateTick()
                            OmsThemeController.cycleAppearanceMode(context)
                        }
                    )
                    OmsCircleIconButton(
                        icon = OmsIcons.Sliders,
                        contentDescription = "打开偏好设置",
                        size = 36.dp,
                        iconSize = 17.5.dp,
                        containerColor = palette.subtleSurface,
                        borderColor = palette.hairline,
                        onClick = { onOpenSettings(SettingsStudioTab.APPEARANCE) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerQuickNavPill(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = OmsTheme.colors
    val shape = RoundedCornerShape(11.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = modifier
            .height(36.dp)
            .omsBounceClick(scaleDown = 0.96f, shape = shape, onClick = onClick)
            .background(if (active) palette.accentSoft else palette.cardSurface, shape)
            .border(
                width = 0.6.dp,
                color = if (active) palette.accent.copy(alpha = 0.45f) else palette.hairline,
                shape = shape
            )
            .padding(horizontal = 10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) palette.accent else palette.textSecondary,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = label,
            color = if (active) palette.accent else palette.textPrimary,
            fontSize = 12.5.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DrawerMiniFilterChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    val palette = OmsTheme.colors
    Text(
        text = label,
        color = if (active) palette.accent else palette.textTertiary,
        fontSize = 11.sp,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) palette.accentSoft else palette.subtleSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun DrawerSearchSectionLabel(title: String) {
    val palette = OmsTheme.colors
    Text(
        text = title,
        color = palette.textTertiary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp)
    )
}

@Composable
private fun DrawerAgentSessionRow(
    session: RemoteAgentSessionUi,
    onClick: () -> Unit
) {
    val palette = OmsTheme.colors
    val hasDraft = remember(session.backend, session.sessionId, session.updatedAtMs) {
        OmsApplication.instance?.edgeStore?.hasAgentDraft("${session.backend}:${session.sessionId}") == true
    }
    val statusDotColor = when (session.status) {
        "running" -> palette.success
        "failed" -> palette.danger
        "completed" -> palette.accent
        else -> palette.textTertiary.copy(alpha = 0.55f)
    }
    val projHint = session.projectDisplayName.ifBlank {
        session.workspacePath.trimEnd('/').substringAfterLast('/')
    }
    val timeHint = remember(session.updatedAtMs) { formatCompactRelativeTime(session.updatedAtMs) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp)
            .omsBounceClick(scaleDown = 0.98f, shape = RoundedCornerShape(9.dp), onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(statusDotColor)
        )
        if (projHint.isNotBlank()) {
            Text(
                text = projHint.take(10),
                color = palette.accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(palette.subtleSurface)
                    .padding(horizontal = 4.5.dp, vertical = 0.5.dp)
            )
        }
        Text(
            text = session.title.ifBlank { "未命名会话" },
            color = palette.textSecondary,
            fontSize = 12.8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (hasDraft) {
            Text(
                text = "草稿",
                color = palette.warning,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(palette.warningSoft)
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        } else if (timeHint.isNotBlank()) {
            Text(
                text = timeHint,
                color = palette.textTertiary,
                fontSize = 10.5.sp
            )
        }
    }
}
