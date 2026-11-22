package com.oms.client.ui.cockpit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oms.client.OmsApplication
import com.oms.client.cockpit.CockpitRuntimeCoordinator
import com.oms.client.otp.OtpMatch
import com.oms.client.transport.OtaUiState
import com.oms.client.ui.theme.OmsAppearanceMode
import com.oms.client.ui.theme.OmsCircleIconButton
import com.oms.client.ui.theme.OmsFeedbackController
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
import com.oms.client.xiaomi.XiaomiAdaptationHelper
import com.oms.core.ai.ModelCatalogRegistry
import com.oms.core.ai.ModelRoleCategory
import com.oms.core.proxy.ProxyNodeStatusView
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SettingsStudioTab {
    APPEARANCE,
    PROXY_TUNNEL,
    AI_MODELS,
    SYSTEM_PAIRING
}

/**
 * v3.0 Native Grouped Configuration & Appearance Studio (Zero legacy nested screen!).
 */
@Composable
fun CockpitSettingsModal(
    coordinator: CockpitRuntimeCoordinator,
    initialTab: SettingsStudioTab = SettingsStudioTab.APPEARANCE,
    onDismiss: () -> Unit
) {
    val palette = OmsTheme.colors
    var activeTab by remember { mutableStateOf(initialTab) }

    BackHandler { onDismiss() }

    LaunchedEffect(Unit) {
        coordinator.refreshProxyUiState()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Minimalist Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OmsCircleIconButton(
                    icon = OmsIcons.ArrowLeft,
                    contentDescription = "返回",
                    onClick = onDismiss
                )
                Text(
                    text = "偏好设置与连接中枢",
                    color = palette.textPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Smooth 4-Tab Sliding Segmented Bar
        OmsSlidingSegmentedBar(
            items = listOf(
                OmsSegmentItem(SettingsStudioTab.APPEARANCE, "外观主题"),
                OmsSegmentItem(SettingsStudioTab.PROXY_TUNNEL, "智能隧道"),
                OmsSegmentItem(SettingsStudioTab.AI_MODELS, "模型矩阵"),
                OmsSegmentItem(SettingsStudioTab.SYSTEM_PAIRING, "设备权限")
            ),
            selected = activeTab,
            onSelect = { activeTab = it },
            height = 38.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (activeTab) {
                SettingsStudioTab.APPEARANCE -> AppearanceThemeStudioPane(coordinator)
                SettingsStudioTab.PROXY_TUNNEL -> ProxyTunnelSettingsPane(coordinator)
                SettingsStudioTab.AI_MODELS -> AiModelMatrixSettingsPane(coordinator)
                SettingsStudioTab.SYSTEM_PAIRING -> DeviceAndPermissionsPane(coordinator)
            }
        }
    }
}

// =============================================================================
// Pane 1: Appearance & Multi-Theme Studio (`跟随系统 / 素昼浅色 / 玄夜深色` + 5 Palettes)
// =============================================================================
@Composable
private fun AppearanceThemeStudioPane(coordinator: CockpitRuntimeCoordinator) {
    val context = LocalContext.current
    val palette = OmsTheme.colors
    val settings by OmsThemeController.settingsFlow.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            OmsSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = if (palette.isDark) OmsIcons.Moon else OmsIcons.Sun,
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(18.dp)
                    )
                    Text("明暗显示模式", color = palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "支持跟随系统日夜自动切换，或手动锁定日间「素笺纸感」与夜间「玄墨 OLED」",
                    color = palette.textTertiary,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                OmsSlidingSegmentedBar(
                    items = listOf(
                        OmsSegmentItem(OmsAppearanceMode.SYSTEM, "跟随系统"),
                        OmsSegmentItem(OmsAppearanceMode.LIGHT, "素昼浅色"),
                        OmsSegmentItem(OmsAppearanceMode.DARK, "玄夜深色")
                    ),
                    selected = settings.mode,
                    onSelect = { chosen ->
                        coordinator.vibrateTick()
                        OmsThemeController.setAppearanceMode(context, chosen)
                    },
                    height = 38.dp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item {
            Text(
                text = "精选美学风格（点击即刻全局换肤，日夜双态自适应调色）",
                color = palette.textTertiary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }

        items(items = OmsThemeStyle.entries, key = { it.key }) { style ->
            val selected = settings.style == style
            OmsSurfaceCard(
                borderColor = if (selected) palette.accent else palette.hairline,
                onClick = {
                    coordinator.vibrateTick()
                    OmsThemeController.setThemeStyle(context, style)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        // Tactile Dual-Tone Swatch Preview
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy((-6).dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(style.swatchLight)
                                    .border(1.dp, Color(0x28000000), CircleShape)
                            )
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(style.swatchDark)
                                    .border(1.dp, Color(0x33FFFFFF), CircleShape)
                            )
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(style.swatchAccent)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = style.title,
                                color = palette.textPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = style.subtitle,
                                color = palette.textSecondary,
                                fontSize = 12.sp,
                                lineHeight = 17.5.sp
                            )
                        }
                    }

                    if (selected) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(palette.accent)
                        ) {
                            Icon(OmsIcons.Check, contentDescription = null, tint = palette.onAccent, modifier = Modifier.size(15.dp))
                        }
                    }
                }
            }
        }
    }
}

// =============================================================================
// Pane 2: Built-in Non-TUN Proxy Tunnel & Real-Key AI Connectivity Probe
// =============================================================================
@Composable
private fun ProxyTunnelSettingsPane(coordinator: CockpitRuntimeCoordinator) {
    val palette = OmsTheme.colors
    val scope = rememberCoroutineScope()
    val nodes by coordinator.proxyNodesFlow.collectAsState()
    val routingMode by coordinator.proxyRoutingModeFlow.collectAsState()
    val summary by coordinator.proxySummaryFlow.collectAsState()
    val connectivity by coordinator.aiConnectivityFlow.collectAsState()

    var isSpeedTesting by remember { mutableStateOf(false) }
    var isProbingAi by remember { mutableStateOf(false) }
    var isRefreshingSub by remember { mutableStateOf(false) }
    var testingSingleKey by remember { mutableStateOf<String?>(null) }

    val normalizedMode = if (routingMode == "proxy") "rule" else routingMode

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            OmsSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(OmsIcons.Globe, contentDescription = null, tint = palette.accent, modifier = Modifier.size(17.dp))
                    Text("内置免 VPN 加密分流网关", color = palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = summary.ifBlank { "连上 Mac 自动同步节点快照，或点下方刷新订阅" },
                    color = palette.textSecondary,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                OmsSlidingSegmentedBar(
                    items = listOf(
                        OmsSegmentItem("rule", "智能优选"),
                        OmsSegmentItem("manual", "手动锁定"),
                        OmsSegmentItem("direct", "仅直连")
                    ),
                    selected = normalizedMode,
                    onSelect = { m ->
                        if (m == "manual") {
                            val pin = nodes.firstOrNull { it.isPinned }?.key ?: nodes.firstOrNull()?.key
                            coordinator.setProxyRoutingPolicy("manual", pin)
                        } else {
                            coordinator.setProxyRoutingPolicy(m, null)
                        }
                    },
                    height = 36.dp,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OmsPrimaryButton(
                        label = if (isSpeedTesting) "测速中…" else "全节点测速 (${nodes.size})",
                        icon = OmsIcons.Bolt,
                        enabled = !isSpeedTesting && nodes.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            isSpeedTesting = true
                            scope.launch {
                                try {
                                    coordinator.runFullProxySpeedTest()
                                } finally {
                                    isSpeedTesting = false
                                }
                            }
                        }
                    )
                    OmsSecondaryButton(
                        label = if (isProbingAi) "验证中…" else "实测 AI 连通",
                        icon = OmsIcons.Sparkles,
                        enabled = !isProbingAi,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            isProbingAi = true
                            scope.launch {
                                try {
                                    coordinator.probeAiConnectivity()
                                } finally {
                                    isProbingAi = false
                                }
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                OmsSecondaryButton(
                    label = if (isRefreshingSub) "正在拉取订阅与快照…" else "刷新订阅链接 / 从 Mac 同步节点",
                    icon = OmsIcons.Refresh,
                    enabled = !isRefreshingSub,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        isRefreshingSub = true
                        scope.launch {
                            try {
                                val total = coordinator.refreshProxySubscriptionsFromUrls()
                                OmsFeedbackController.show("节点库已更新（共 $total 个节点）", OmsToastKind.SUCCESS)
                            } finally {
                                isRefreshingSub = false
                            }
                        }
                    }
                )

                connectivity?.let { rep ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.subtleSurface)
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = rep.openAiStatusText.ifBlank {
                                if (rep.openAiReachable) "OpenAI · ${rep.openAiLatencyMs}ms" else "OpenAI 不可达"
                            },
                            color = if (rep.openAiReachable) palette.success else palette.danger,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = rep.geminiStatusText.ifBlank {
                                if (rep.geminiReachable) "Gemini · ${rep.geminiLatencyMs}ms" else "Gemini 不可达"
                            },
                            color = if (rep.geminiReachable) palette.success else palette.danger,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        if (nodes.isNotEmpty()) {
            item {
                Text(
                    text = "可用节点（点选锁定切换，防锁区引擎会自动绕开受限节点）",
                    color = palette.textTertiary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            items(items = nodes, key = { it.key }) { node ->
                ProxyNodeCard(
                    node = node,
                    isTesting = testingSingleKey == node.key,
                    onSelectPin = {
                        coordinator.setProxyRoutingPolicy("manual", node.key)
                        OmsFeedbackController.show("已锁定节点：${node.name}", OmsToastKind.SUCCESS)
                    },
                    onSingleProbe = {
                        testingSingleKey = node.key
                        scope.launch {
                            try {
                                coordinator.probeSingleProxyNode(node.key)
                            } finally {
                                testingSingleKey = null
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ProxyNodeCard(
    node: ProxyNodeStatusView,
    isTesting: Boolean,
    onSelectPin: () -> Unit,
    onSingleProbe: () -> Unit
) {
    val palette = OmsTheme.colors
    val lat = node.latencyMs
    val latencyText = when {
        isTesting -> "测速中"
        !node.healthy && lat == null -> "超时"
        lat != null -> "${lat}ms"
        else -> "待测"
    }
    val latencyColor = when {
        !node.healthy && lat == null -> palette.danger
        lat == null -> palette.textTertiary
        lat < 360 -> palette.success
        lat < 1200 -> palette.warning
        else -> palette.danger
    }

    OmsSurfaceCard(
        cornerRadius = 15.dp,
        borderColor = if (node.isActive) palette.accent else palette.hairline,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp),
        onClick = onSelectPin,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (node.isActive) {
                        Box(modifier = Modifier.size(6.5.dp).background(palette.accent, CircleShape))
                    }
                    Text(
                        text = node.name,
                        color = palette.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = if (node.isActive) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${node.regionBadge} · ${node.protocol}" +
                        (if (node.aiGeoBlocked) " · AI 地域受限" else "") +
                        (if (node.isPinned) " · 手动锁定" else if (node.isActive) " · 当前优选" else ""),
                    color = if (node.aiGeoBlocked) palette.warning else palette.textTertiary,
                    fontSize = 11.5.sp
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(latencyText, color = latencyColor, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                OmsCircleIconButton(
                    icon = OmsIcons.Bolt,
                    contentDescription = "单节点测速",
                    size = 30.dp,
                    iconSize = 14.5.dp,
                    containerColor = palette.subtleSurface,
                    contentColor = palette.textSecondary,
                    onClick = onSingleProbe
                )
            }
        }
    }
}

// =============================================================================
// Pane 3: 10-Role OpenAI & Gemini Model Matrix
// =============================================================================
@Composable
private fun AiModelMatrixSettingsPane(coordinator: CockpitRuntimeCoordinator) {
    val palette = OmsTheme.colors
    val scope = rememberCoroutineScope()
    val catalog by coordinator.modelCatalogFlow.collectAsState()
    val refreshedAt by coordinator.modelRefreshedAtFlow.collectAsState()
    val selections by coordinator.roleSelectionsFlow.collectAsState()
    val quotas by coordinator.agentQuotasFlow.collectAsState()
    var isRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        coordinator.refreshAgentSessions()
    }

    val refreshedTimeLabel = remember(refreshedAt) {
        if (refreshedAt <= 0L) "内置预设模型库（尚未联网刷新）"
        else "上次同步：" + SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(refreshedAt))
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        quotas?.let { qSnap ->
            item(key = "settings_agent_quotas") {
                AgentQuotaDashboardCard(quotas = qSnap)
            }
        }

        item {
            OmsSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                Text("双擎全场景模型分配", color = palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("$refreshedTimeLabel · 共 ${catalog.size} 款模型可选", color = palette.textTertiary, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(12.dp))
                OmsPrimaryButton(
                    label = if (isRefreshing) "正在拉取官方 /models 列表…" else "一键刷新服务商最新可用模型",
                    icon = OmsIcons.Refresh,
                    enabled = !isRefreshing,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        isRefreshing = true
                        scope.launch {
                            try {
                                val res = coordinator.refreshLiveProviderModels()
                                OmsFeedbackController.show(
                                    "已同步 OpenAI ${res.openAiDiscoveredCount} 款 · Gemini ${res.geminiDiscoveredCount} 款",
                                    OmsToastKind.SUCCESS
                                )
                            } finally {
                                isRefreshing = false
                            }
                        }
                    }
                )
            }
        }

        items(items = ModelRoleCategory.entries, key = { it.name }) { category ->
            val options = remember(catalog, category) {
                ModelCatalogRegistry.optionsForCategory(category, catalog)
            }
            val currentSelKey = selections[category] ?: category.defaultSelection
            ModelCategorySelectorCard(
                category = category,
                currentSelectionKey = currentSelKey,
                options = options,
                onSelect = { chosenKey -> coordinator.setModelRoleSelection(category, chosenKey) }
            )
        }
    }
}

@Composable
private fun ModelCategorySelectorCard(
    category: ModelRoleCategory,
    currentSelectionKey: String,
    options: List<com.oms.core.ai.ModelCatalogItem>,
    onSelect: (String) -> Unit
) {
    val palette = OmsTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val selectedItem = options.firstOrNull { it.selectionKey == currentSelectionKey }
    val providerTag = currentSelectionKey.substringBefore(':', "openai")
    val modelIdOnly = currentSelectionKey.substringAfter(':', currentSelectionKey)

    OmsSurfaceCard(cornerRadius = 16.dp, modifier = Modifier.fillMaxWidth()) {
        Text(category.title, color = palette.textPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(2.dp))
        Text(category.subtitle, color = palette.textTertiary, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(10.dp))

        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .omsBounceClick(scaleDown = 0.98f, shape = RoundedCornerShape(12.dp)) { expanded = true }
                    .background(palette.subtleSurface, RoundedCornerShape(12.dp))
                    .border(0.6.dp, palette.hairline, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    ProviderBadge(providerTag)
                    Column {
                        Text(
                            text = selectedItem?.label ?: modelIdOnly,
                            color = palette.textPrimary,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(modelIdOnly, color = palette.textTertiary, fontSize = 11.sp)
                    }
                }
                Icon(OmsIcons.ChevronDown, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(16.dp))
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(palette.composer).width(310.dp)
            ) {
                options.forEach { opt ->
                    val sel = opt.selectionKey == currentSelectionKey
                    DropdownMenuItem(
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    ProviderBadge(opt.providerId)
                                    Text(
                                        text = opt.label,
                                        color = if (sel) palette.accent else palette.textPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium
                                    )
                                }
                                Text(opt.description, color = palette.textTertiary, fontSize = 11.sp)
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelect(opt.selectionKey)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderBadge(providerId: String) {
    val palette = OmsTheme.colors
    val isOpenAi = providerId.equals("openai", ignoreCase = true)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isOpenAi) palette.successSoft else palette.accentSoft)
            .padding(horizontal = 7.dp, vertical = 2.5.dp)
    ) {
        Text(
            text = if (isOpenAi) "OpenAI" else "Gemini",
            color = if (isOpenAi) palette.success else palette.accent,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// =============================================================================
// Pane 4: Clean Native Device Pairing, OTA & Collapsible HyperOS Health
// =============================================================================
@Composable
private fun DeviceAndPermissionsPane(coordinator: CockpitRuntimeCoordinator) {
    val context = LocalContext.current
    val palette = OmsTheme.colors
    val app = OmsApplication.instance ?: return

    val activeTier by app.transportClient.activeTierFlow.collectAsState()
    val connectionHint by app.transportClient.connectionHintFlow.collectAsState()
    val otaState by app.transportClient.otaStateFlow.collectAsState()

    val pairedMac = remember { app.repository.getPairedMac() }
    var showPairUriBox by remember { mutableStateOf(pairedMac?.pairingVersion != 2) }
    var pairUriInput by remember { mutableStateOf("") }
    var diagnosticReport by remember { mutableStateOf<String?>(null) }

    var checkingOta by remember { mutableStateOf(false) }
    var autoDownloadOta by remember { mutableStateOf(app.repository.isAutoDownloadOtaEnabled()) }
    var forceAwayStandby by remember { mutableStateOf(app.edgeStore.isForceAwayStandbyEnabled()) }
    var autoOtpEnabled by remember { mutableStateOf(app.repository.isAutoSyncOtpEnabled()) }
    var cameraPillEnabled by remember { mutableStateOf(app.repository.isCameraPillEnabled()) }

    var permRefreshTick by remember { mutableStateOf(0) }
    val health = remember(permRefreshTick) { XiaomiAdaptationHelper.inspectAll(context) }
    val permChecks = remember(health) {
        listOf(
            Triple("后台弹出界面与锁屏显示", health.backgroundPopupAllowed && health.lockScreenDisplayAllowed) {
                XiaomiAdaptationHelper.openXiaomiPermissionEditor(context)
            },
            Triple("电池省电策略设为「无限制」", health.batteryUnrestricted) {
                XiaomiAdaptationHelper.openBatteryUnrestrictedPrompt(context)
            },
            Triple("悬浮窗权限（截屏胶囊与急停护栏）", health.overlayAllowed) {
                XiaomiAdaptationHelper.openOverlayPermissionSettings(context)
            },
            Triple("无障碍服务（远程屏幕协助）", health.accessibilityEnabled) {
                XiaomiAdaptationHelper.openAccessibilitySettings(context)
            },
            Triple("麦克风录音权限", health.recordAudioGranted) {
                XiaomiAdaptationHelper.openXiaomiPermissionEditor(context)
            },
            Triple("通知使用权（验证码实时同步）", health.notificationListenerEnabled) {
                XiaomiAdaptationHelper.openNotificationListenerSettings(context)
            },
            Triple("短信与通知类短信读取", health.smsReadGranted && health.notificationSmsAllowed) {
                XiaomiAdaptationHelper.openXiaomiPermissionEditor(context)
            }
        )
    }
    val passedCount = permChecks.count { it.second }
    var expandPerms by remember { mutableStateOf(passedCount < permChecks.size) }

    val installedVerName = remember { app.transportClient.getInstalledApkInfo(includeSha256 = false).first }

    diagnosticReport?.let { rep ->
        AlertDialog(
            onDismissRequest = { diagnosticReport = null },
            containerColor = palette.composer,
            title = { Text("双端连接诊断报告", color = palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                SelectionContainer {
                    Text(
                        text = rep,
                        color = palette.textSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("OMS_DIAG", rep))
                    diagnosticReport = null
                    OmsFeedbackController.show("已复制诊断报告", OmsToastKind.SUCCESS)
                }) { Text("复制报告", color = palette.accent) }
            },
            dismissButton = {
                TextButton(onClick = { diagnosticReport = null }) { Text("关闭", color = palette.textTertiary) }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Mac Connection & v2 Pairing Card
        item {
            OmsSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                val (tierTitle, tierColor) = when (activeTier) {
                    "LAN_WS" -> "Mac 局域网高速直连 (Tier 1)" to palette.success
                    "BT_RFCOMM" -> "Mac 近场蓝牙连接 (Tier 2)" to palette.accent
                    "WAN_WEBRTC" -> "Mac 跨网加密中继在线 (Tier 3)" to palette.accent
                    else -> connectionHint.ifBlank { "当前未连接 Mac" } to palette.warning
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                        Box(modifier = Modifier.size(8.dp).background(tierColor, CircleShape))
                        Column {
                            Text(tierTitle, color = palette.textPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = if (pairedMac != null) "已绑定主机：${pairedMac.macName}" else "尚未绑定 Mac 主机",
                                color = palette.textTertiary,
                                fontSize = 12.sp
                            )
                        }
                    }
                    OmsSecondaryButton(
                        label = "诊断",
                        height = 32.dp,
                        onClick = {
                            diagnosticReport = "版本：v$installedVerName\n" + app.transportClient.connectionDiagnosticsText()
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OmsPrimaryButton(
                        label = "立即重连 Mac",
                        icon = OmsIcons.Refresh,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            coordinator.vibrateTick()
                            app.transportClient.reconnectNow { _, msg ->
                                OmsFeedbackController.show(msg, OmsToastKind.INFO)
                            }
                        }
                    )
                    OmsSecondaryButton(
                        label = if (showPairUriBox) "收起配对" else "更换绑定",
                        modifier = Modifier.weight(1f),
                        onClick = { showPairUriBox = !showPairUriBox }
                    )
                }

                AnimatedVisibility(
                    visible = showPairUriBox,
                    enter = expandVertically(tween(170)) + fadeIn(tween(140)),
                    exit = shrinkVertically(tween(150)) + fadeOut(tween(110))
                ) {
                    Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(palette.subtleSurface)
                                .border(0.6.dp, palette.hairline, RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            if (pairUriInput.isEmpty()) {
                                Text("粘贴 Mac 端生成的 oms://pair... 加密配对链接", color = palette.textTertiary, fontSize = 13.sp)
                            }
                            BasicTextField(
                                value = pairUriInput,
                                onValueChange = { pairUriInput = it },
                                textStyle = TextStyle(color = palette.textPrimary, fontSize = 13.sp),
                                cursorBrush = SolidColor(palette.accent),
                                maxLines = 3,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        OmsPrimaryButton(
                            label = "验证并完成加密绑定",
                            icon = OmsIcons.Check,
                            enabled = pairUriInput.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                app.transportClient.pairWithUri(pairUriInput.trim()) { ok, msg ->
                                    OmsFeedbackController.show(msg, if (ok) OmsToastKind.SUCCESS else OmsToastKind.WARNING)
                                    if (ok) {
                                        pairUriInput = ""
                                        showPairUriBox = false
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        // 2. Smart Switches (WeChat Standby, OTP Sync, Camera Pill)
        item {
            OmsSurfaceCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                SettingSwitchRow(
                    title = "离家 5G 强制接管微信尺素",
                    subtitle = "默认同 Wi-Fi 或中继确认 Mac 休眠时自动无感接管",
                    checked = forceAwayStandby,
                    onCheckedChange = { next ->
                        forceAwayStandby = next
                        app.edgeStore.setForceAwayStandbyEnabled(next)
                        app.wechatStandby.refreshState()
                    }
                )
                Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(palette.hairline))
                SettingSwitchRow(
                    title = "锁屏验证码实时推送至 Mac",
                    subtitle = "收到短信/通知验证码后自动加密送达桌面剪贴板",
                    checked = autoOtpEnabled,
                    onCheckedChange = { next ->
                        autoOtpEnabled = next
                        app.repository.setAutoSyncOtpEnabled(next)
                    }
                )
                Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(palette.hairline))
                SettingSwitchRow(
                    title = "相机拍照后浮现「发至 Mac」胶囊",
                    subtitle = "系统相机新拍照片时在屏幕边缘展示轻量快传入口",
                    checked = cameraPillEnabled,
                    onCheckedChange = { next ->
                        cameraPillEnabled = next
                        app.repository.setCameraPillEnabled(next)
                    }
                )
                Spacer(modifier = Modifier.height(6.dp))
                OmsSecondaryButton(
                    label = "发送模拟验证码测试桌面粘贴",
                    height = 34.dp,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val demoCode = (100000..999999).random().toString()
                        app.transportClient.submitOtpToMacOrQueue(
                            OtpMatch(
                                code = demoCode,
                                sender = "青简自检",
                                fullText = "【青简自检】测试验证码 $demoCode，5 分钟内有效。",
                                sourceApp = "com.oms.client"
                            )
                        )
                        OmsFeedbackController.show("已推送测试验证码 $demoCode 至 Mac", OmsToastKind.SUCCESS)
                    }
                )
            }
        }

        // 3. Collapsible Xiaomi HyperOS Permission Health Card
        item {
            OmsSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .omsBounceClick(scaleDown = 0.98f) { expandPerms = !expandPerms },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            imageVector = OmsIcons.Shield,
                            contentDescription = null,
                            tint = if (passedCount == permChecks.size) palette.success else palette.warning,
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text("HyperOS 系统权限健康度", color = palette.textPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = if (passedCount == permChecks.size) "全部 $passedCount 项关键权限均已就绪"
                                else "已完成 $passedCount / ${permChecks.size} 项 · 点击补齐缺失项",
                                color = if (passedCount == permChecks.size) palette.success else palette.warning,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OmsCircleIconButton(
                            icon = OmsIcons.Refresh,
                            contentDescription = "重新检测",
                            size = 30.dp,
                            iconSize = 14.5.dp,
                            onClick = { permRefreshTick++ }
                        )
                        Icon(
                            imageVector = if (expandPerms) OmsIcons.ChevronUp else OmsIcons.ChevronDown,
                            contentDescription = null,
                            tint = palette.textTertiary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                AnimatedVisibility(visible = expandPerms) {
                    Column(
                        modifier = Modifier.padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        permChecks.forEach { (title, ok, openAction) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .omsBounceClick(scaleDown = 0.98f, shape = RoundedCornerShape(10.dp), onClick = openAction)
                                    .background(palette.subtleSurface, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .background(if (ok) palette.success else palette.warning, CircleShape)
                                    )
                                    Text(title, color = palette.textPrimary, fontSize = 13.sp)
                                }
                                Text(
                                    text = if (ok) "已就绪" else "去开启 ›",
                                    color = if (ok) palette.textTertiary else palette.accent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .omsBounceClick(scaleDown = 0.98f, shape = RoundedCornerShape(10.dp)) {
                                    XiaomiAdaptationHelper.openXiaomiAutostartSettings(context)
                                }
                                .background(palette.subtleSurface, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("小米应用「自启动」管理", color = palette.textPrimary, fontSize = 13.sp)
                            Text("去核验 ›", color = palette.textSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // 4. Verified OTA Version Management Card
        item {
            OmsSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                val isReadyOta = otaState is OtaUiState.ReadyToInstall ||
                    otaState is OtaUiState.InstallerLaunched ||
                    otaState is OtaUiState.NeedsInstallPermission
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("应用版本 · v$installedVerName", color = palette.textPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                        Text("SHA-256 签名校验覆盖升级，保留全部本地会话与密钥", color = palette.textTertiary, fontSize = 11.5.sp)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                SettingSwitchRow(
                    title = "局域网自动静默预下载新版本",
                    subtitle = "下载验签就绪后在顶部轻提示一键安装",
                    checked = autoDownloadOta,
                    onCheckedChange = { enabled ->
                        autoDownloadOta = enabled
                        app.repository.setAutoDownloadOtaEnabled(enabled)
                        if (enabled) app.transportClient.triggerAutoOtaCheckIfEligible("toggle_on")
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                OmsPrimaryButton(
                    label = when {
                        otaState is OtaUiState.Downloading -> "正在下载更新…"
                        otaState is OtaUiState.NeedsInstallPermission -> "授权并安装已就绪更新"
                        isReadyOta -> "立即安装已就绪更新"
                        otaState is OtaUiState.UpdateAvailable -> "立即更新至 v${(otaState as OtaUiState.UpdateAvailable).ota.remoteVersionName}"
                        checkingOta -> "正在查询更新…"
                        else -> "检查新版本"
                    },
                    icon = OmsIcons.Refresh,
                    enabled = !checkingOta && otaState !is OtaUiState.Downloading,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (isReadyOta) {
                            app.transportClient.installOrResumeReadyOta { msg ->
                                OmsFeedbackController.show(msg, OmsToastKind.INFO)
                            }
                            return@OmsPrimaryButton
                        }
                        checkingOta = true
                        app.transportClient.checkOtaUpdate { res ->
                            checkingOta = false
                            if (!res.ok) {
                                OmsFeedbackController.show(res.message, OmsToastKind.WARNING)
                            } else if (!res.hasUpdate) {
                                OmsFeedbackController.show("当前已是最新版本 (v${res.localVersionName})", OmsToastKind.SUCCESS)
                            } else {
                                app.transportClient.installOtaUpdate(res) { msg ->
                                    OmsFeedbackController.show(msg, OmsToastKind.INFO)
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val palette = OmsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = palette.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = palette.textTertiary, fontSize = 11.5.sp, lineHeight = 16.5.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
