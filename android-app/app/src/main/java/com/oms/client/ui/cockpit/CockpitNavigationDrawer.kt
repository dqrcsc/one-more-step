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
        else -> {
            val d = java.text.SimpleDateFormat("MM/dd", Locale.CHINA).format(java.util.Date(timestampMs))
            d
        }
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
    val edgeStore = OmsApplication.instance?.edgeStore
    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(focusSearchNonce) {
        if (focusSearchNonce > 0L) {
            delay(180L)
            runCatching { searchFocusRequester.requestFocus() }
        }
    }

    var macSectionExpanded by remember { mutableStateOf(true) }
    var chatSectionExpanded by remember { mutableStateOf(true) }
    var backendFilter by remember { mutableStateOf("ALL") }

    val projectGroups = remember(agentProjects, agentSessions, backendFilter) {
        buildProjectSessionGroups(agentProjects, agentSessions, backendFilter)
    }

    // Smart default project expand state (persisted once toggled by user)
    var userSavedExpandedPaths by remember {
        mutableStateOf(edgeStore?.loadExpandedProjectPaths("drawer"))
    }
    val expandedProjects = remember(projectGroups, userSavedExpandedPaths) {
        val saved = userSavedExpandedPaths
        if (saved != null) {
            saved
        } else {
            // Auto-expand projects with running sessions + top 2 most recently active
            buildSet {
                projectGroups.forEachIndexed { idx, g ->
                    if (g.runningCount > 0 || idx < 2) add(g.normPath)
                }
            }
        }
    }
    val showAllSessionsMap = remember { mutableStateMapOf<String, Boolean>() }

    fun toggleProjectExpanded(normPath: String) {
        val next = expandedProjects.toMutableSet()
        if (!next.add(normPath)) next.remove(normPath)
        userSavedExpandedPaths = next
        edgeStore?.saveExpandedProjectPaths("drawer", next)
    }

    val miniQuota = remember(agentQuotas) { formatMiniQuotaSummary(agentQuotas) }

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
                                text = "搜对话·项目·尺素·知识库…",
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

            // 2. Compact 2x2 Quick Navigation Grid (Theme swatch moved to Top Bar / Footer!)
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
                        icon = OmsIcons.BookOpen,
                        label = "尺素伴读",
                        active = currentTab == CockpitTab.CHISU,
                        onClick = { onSelectTab(CockpitTab.CHISU) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                    DrawerQuickNavPill(
                        icon = OmsIcons.Globe,
                        label = "应用舱",
                        active = currentTab == CockpitTab.WEB_APPS,
                        onClick = { onSelectTab(CockpitTab.WEB_APPS) },
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

            // 3. Main Scroll Area: Search Results OR Unified IDE Project Tree + Chat History
            if (searchQuery.isNotBlank()) {
                val qLower = searchQuery.trim().lowercase(Locale.ROOT)
                val matchedAgents = remember(searchQuery, agentSessions) {
                    agentSessions.filter { s ->
                        s.title.lowercase(Locale.ROOT).contains(qLower) ||
                            s.projectDisplayName.lowercase(Locale.ROOT).contains(qLower) ||
                            s.workspacePath.lowercase(Locale.ROOT).contains(qLower) ||
                            s.lastSummary.lowercase(Locale.ROOT).contains(qLower)
                    }.take(12)
                }
                val chatHits = remember(searchQuery, threads) {
                    coordinator.chatRepo.searchGlobalMessages(searchQuery, limit = 15)
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "协同 ${matchedAgents.size} · 对话 ${chatHits.size} · 文库 ${omniSearchHits.size}",
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
                    if (matchedAgents.isNotEmpty()) {
                        item(key = "sec_search_agents") {
                            DrawerSearchSectionLabel("Mac 双脑协同会话")
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
                                        text = if (sess.backend == "codex") "Codex" else "AGY",
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

                    if (chatHits.isNotEmpty()) {
                        item(key = "sec_search_chat") {
                            DrawerSearchSectionLabel("随身秘书对话")
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
                }
            } else {
                val groupedThreads = remember(threads) { groupThreadsByRecency(threads) }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // =============================================================
                    // Section A: MAC 双脑协同 (Flat Collapsible Project Tree)
                    // =============================================================
                    item(key = "drawer_sec_mac_header") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp, bottom = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(9.dp))
                                    .clickable { macSectionExpanded = !macSectionExpanded }
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
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
                                        text = "MAC 双脑协同",
                                        color = palette.textSecondary,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.4.sp
                                    )
                                    if (miniQuota.isNotBlank()) {
                                        Text(
                                            text = miniQuota,
                                            color = palette.accent,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(palette.accentSoft)
                                                .padding(horizontal = 5.dp, vertical = 1.5.dp)
                                        )
                                    }
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OmsCircleIconButton(
                                        icon = OmsIcons.Refresh,
                                        contentDescription = "刷新协同项目",
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
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                        DrawerMiniFilterChip("全部", backendFilter == "ALL") { backendFilter = "ALL" }
                                        DrawerMiniFilterChip("Codex", backendFilter == "codex") { backendFilter = "codex" }
                                        DrawerMiniFilterChip("AGY", backendFilter == "agy") { backendFilter = "agy" }
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

                    if (macSectionExpanded) {
                        if (projectGroups.isEmpty()) {
                            item(key = "drawer_mac_empty") {
                                Text(
                                    text = if (activeTier == "DISCONNECTED") "连上 Mac 后将平铺展示各项目会话" else "暂无协同会话，点右上角 + 派发任务",
                                    color = palette.textTertiary,
                                    fontSize = 11.5.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        } else {
                            projectGroups.forEach { group ->
                                val isExpanded = expandedProjects.contains(group.normPath)
                                val showAll = showAllSessionsMap[group.normPath] == true
                                val visibleSessions = if (showAll) group.sessions else group.sessions.take(4)
                                val hiddenCount = (group.sessions.size - visibleSessions.size).coerceAtLeast(0)

                                item(key = "drawer_proj_${group.normPath}") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable { toggleProjectExpanded(group.normPath) }
                                            .padding(horizontal = 6.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = if (isExpanded) OmsIcons.ChevronDown else OmsIcons.ChevronRight,
                                                contentDescription = null,
                                                tint = palette.textTertiary,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Icon(
                                                imageVector = OmsIcons.Folder,
                                                contentDescription = null,
                                                tint = if (group.runningCount > 0) palette.success else palette.accent,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = group.displayName,
                                                color = palette.textPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (group.runningCount > 0) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(6.dp)
                                                        .clip(CircleShape)
                                                        .background(palette.success)
                                                )
                                            }
                                            Text(
                                                text = "${group.sessions.size}",
                                                color = palette.textTertiary,
                                                fontSize = 11.sp
                                            )
                                        }

                                        if (group.rawPath.isNotBlank()) {
                                            OmsCircleIconButton(
                                                icon = OmsIcons.Plus,
                                                contentDescription = "向 ${group.displayName} 派发任务",
                                                size = 24.dp,
                                                iconSize = 12.5.dp,
                                                contentColor = palette.textSecondary,
                                                onClick = { onDispatchForProject(group.rawPath) }
                                            )
                                        }
                                    }
                                }

                                if (isExpanded) {
                                    items(
                                        items = visibleSessions,
                                        key = { "ds_${group.normPath}_${it.backend}:${it.sessionId}" }
                                    ) { sess ->
                                        DrawerAgentSessionRow(
                                            session = sess,
                                            onClick = { onSelectAgentSession(sess) }
                                        )
                                    }

                                    if (hiddenCount > 0 || (showAll && group.sessions.size > 4)) {
                                        item(key = "drawer_proj_more_${group.normPath}") {
                                            Text(
                                                text = if (hiddenCount > 0) "··· 展开其余 $hiddenCount 条会话" else "收起多余会话",
                                                color = palette.accent,
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable { showAllSessionsMap[group.normPath] = !showAll }
                                                    .padding(start = 28.dp, top = 4.dp, bottom = 6.dp)
                                            )
                                        }
                                    }
                                }
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
                    // Section B: 随身秘书对话 (Collapsible Recency Tree)
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
                                    text = "随身秘书对话 (${threads.size})",
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
                        Text("青简 · 咫尺", color = palette.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        val sub = when {
                            activeTier == "LAN_WS" -> "Mac 局域网直连"
                            activeTier == "WAN_WEBRTC" -> "Mac 跨网远程在线"
                            activeTier == "BT_RFCOMM" -> "Mac 蓝牙近场在线"
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
    val backendTag = if (session.backend == "codex") "C" else "A"
    val timeHint = remember(session.updatedAtMs) { formatCompactRelativeTime(session.updatedAtMs) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp)
            .omsBounceClick(scaleDown = 0.98f, shape = RoundedCornerShape(9.dp), onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(statusDotColor)
        )
        Text(
            text = backendTag,
            color = if (session.backend == "codex") palette.textSecondary else palette.accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(palette.subtleSurface)
                .padding(horizontal = 4.dp, vertical = 0.5.dp)
        )
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
