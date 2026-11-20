package com.oms.client.ui.cockpit

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oms.client.cockpit.CockpitRuntimeCoordinator
import com.oms.core.ai.ModelCatalogRegistry
import com.oms.core.ai.ModelRoleCategory
import com.oms.core.proxy.ProxyNodeStatusView
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SettingsStudioTab {
    PROXY_TUNNEL,
    AI_MODELS,
    SYSTEM_PAIRING
}

/**
 * v2.1 Unified Configuration Studio (`⚙️ 咫尺中枢配置台`):
 *  1) `✈️ 代理隧道`: Routing policy (`智能优选 美|新` / `手动锁定` / `仅直连`), batch node speed test, OpenAI/Gemini RTT probe, interactive node list
 *  2) `🧠 模型矩阵`: 10 categorized roles across OpenAI & Gemini (Chat, Reasoning, Vision, Triage, Live, Transcribe, Image, Video, Music) + 1-tap live `/models` refresh
 *  3) `🔗 配对与权限`: Full pairing QR, HyperOS setup wizard, OTA & connection diagnostics
 */
@Composable
fun CockpitSettingsModal(
    coordinator: CockpitRuntimeCoordinator,
    initialTab: SettingsStudioTab = SettingsStudioTab.PROXY_TUNNEL,
    onDismiss: () -> Unit,
    legacySystemConsoleContent: @Composable () -> Unit
) {
    var activeTab by remember { mutableStateOf(initialTab) }

    LaunchedEffect(Unit) {
        coordinator.refreshProxyUiState()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1120))
    ) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF090E1A))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("⚙️ 咫尺中枢配置台", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                text = "✕ 完成返回",
                color = Color(0xFF38BDF8),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF162032))
                    .clickable { onDismiss() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        // 3-Tab Switcher
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0D1424))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingsTabButton("✈️ 代理节点", activeTab == SettingsStudioTab.PROXY_TUNNEL, Modifier.weight(1f)) {
                activeTab = SettingsStudioTab.PROXY_TUNNEL
            }
            SettingsTabButton("🧠 模型矩阵", activeTab == SettingsStudioTab.AI_MODELS, Modifier.weight(1f)) {
                activeTab = SettingsStudioTab.AI_MODELS
            }
            SettingsTabButton("🔗 配对与权限", activeTab == SettingsStudioTab.SYSTEM_PAIRING, Modifier.weight(1f)) {
                activeTab = SettingsStudioTab.SYSTEM_PAIRING
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (activeTab) {
                SettingsStudioTab.PROXY_TUNNEL -> ProxyTunnelSettingsPane(coordinator)
                SettingsStudioTab.AI_MODELS -> AiModelMatrixSettingsPane(coordinator)
                SettingsStudioTab.SYSTEM_PAIRING -> legacySystemConsoleContent()
            }
        }
    }
}

@Composable
private fun ProxyTunnelSettingsPane(coordinator: CockpitRuntimeCoordinator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nodes by coordinator.proxyNodesFlow.collectAsState()
    val routingMode by coordinator.proxyRoutingModeFlow.collectAsState()
    val summary by coordinator.proxySummaryFlow.collectAsState()
    val connectivity by coordinator.aiConnectivityFlow.collectAsState()

    var isSpeedTesting by remember { mutableStateOf(false) }
    var isProbingAi by remember { mutableStateOf(false) }
    var isRefreshingSub by remember { mutableStateOf(false) }
    var testingSingleKey by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Current Active Route Card + Mode Switcher
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF131C31))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("✈️ 内置免 TUN 智能代理网关", color = Color(0xFF7DD3FC), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = summary.ifBlank { "尚未同步代理节点（连上 Mac 自动下发或点下方刷新订阅）" },
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )

                // Routing Mode Selector
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    ModeSelectChip(
                        label = "⚡ 智能优选(新|日|美)",
                        selected = routingMode == "rule" || routingMode == "proxy",
                        modifier = Modifier.weight(1f)
                    ) { coordinator.setProxyRoutingPolicy("rule", null) }

                    ModeSelectChip(
                        label = "📌 手动锁定节点",
                        selected = routingMode == "manual",
                        modifier = Modifier.weight(1f)
                    ) {
                        val defaultPin = nodes.firstOrNull { it.isPinned }?.key ?: nodes.firstOrNull()?.key
                        coordinator.setProxyRoutingPolicy("manual", defaultPin)
                    }

                    ModeSelectChip(
                        label = "🔗 仅直连",
                        selected = routingMode == "direct",
                        modifier = Modifier.weight(0.8f)
                    ) { coordinator.setProxyRoutingPolicy("direct", null) }
                }

                // Speed Test + AI Connectivity Probe + Subscription Refresh Buttons
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        enabled = !isSpeedTesting && nodes.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF0284C7),
                            disabledContainerColor = Color(0xFF1E293B),
                            disabledContentColor = Color(0xFF64748B)
                        ),
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
                    ) {
                        if (isSpeedTesting) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("测速中…", fontSize = 12.sp)
                        } else {
                            Text("⚡ 全节点测速 (${nodes.size})", fontSize = 12.sp)
                        }
                    }

                    Button(
                        enabled = !isProbingAi,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF059669),
                            disabledContainerColor = Color(0xFF1E293B),
                            disabledContentColor = Color(0xFF64748B)
                        ),
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
                    ) {
                        Text(if (isProbingAi) "🌐 验证中…" else "🌐 实测 AI 连通性", fontSize = 12.sp)
                    }
                }

                OutlinedButton(
                    enabled = !isRefreshingSub,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        isRefreshingSub = true
                        scope.launch {
                            try {
                                val total = coordinator.refreshProxySubscriptionsFromUrls()
                                Toast.makeText(context, "已同步并刷新节点库（共 $total 个节点）", Toast.LENGTH_SHORT).show()
                            } finally {
                                isRefreshingSub = false
                            }
                        }
                    }
                ) {
                    Text(
                        text = if (isRefreshingSub) "🔄 正在拉取最新订阅与 Mac 快照…" else "🔄 刷新订阅链接 / 从 Mac 重新拉取节点",
                        color = Color(0xFF93C5FD),
                        fontSize = 12.sp
                    )
                }

                // AI Connectivity Live Banner
                connectivity?.let { rep ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF090E1A))
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = rep.openAiStatusText.ifBlank {
                                if (rep.openAiReachable) "OpenAI: ✅ ${rep.openAiLatencyMs}ms" else "OpenAI: ❌ 不可达"
                            },
                            color = if (rep.openAiReachable) Color(0xFF34D399) else Color(0xFFF87171),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = rep.geminiStatusText.ifBlank {
                                if (rep.geminiReachable) "Gemini: ✅ ${rep.geminiLatencyMs}ms" else "Gemini: ❌ 不可达"
                            },
                            color = if (rep.geminiReachable) Color(0xFF34D399) else Color(0xFFF87171),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // 2. Node List Header
        item {
            Text(
                text = "📡 节点列表（点击任意节点可立即锁定切换，点右侧测速可单测）",
                color = Color(0xFF94A3B8),
                fontSize = 12.sp
            )
        }

        if (nodes.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF131C31))
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("暂无节点，请确保与 Mac 连通后点上方「🔄 从 Mac 重新拉取节点」", color = Color(0xFF64748B), fontSize = 13.sp)
                }
            }
        } else {
            items(items = nodes, key = { it.key }) { node ->
                ProxyNodeCard(
                    node = node,
                    isTesting = testingSingleKey == node.key,
                    onSelectPin = {
                        coordinator.setProxyRoutingPolicy("manual", node.key)
                        Toast.makeText(context, "📌 已切换至节点：${node.name}", Toast.LENGTH_SHORT).show()
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
    val lat = node.latencyMs
    val latencyText = when {
        isTesting -> "测速中…"
        !node.healthy && lat == null -> "超时"
        lat != null -> "$lat ms"
        else -> "待测"
    }
    val latencyColor = when {
        !node.healthy && lat == null -> Color(0xFFF87171)
        lat == null -> Color(0xFF94A3B8)
        lat < 350 -> Color(0xFF34D399)
        lat < 1200 -> Color(0xFFFBBF24)
        else -> Color(0xFFFB923C)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (node.isActive) Color(0xFF102A43) else Color(0xFF131C31))
            .border(
                1.dp,
                if (node.isActive) Color(0xFF38BDF8) else Color(0xFF1E293B),
                RoundedCornerShape(12.dp)
            )
            .clickable { onSelectPin() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (node.isActive) "✓ ${node.name}" else node.name,
                    color = Color.White,
                    fontSize = 13.5.sp,
                    fontWeight = if (node.isActive) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "${node.regionBadge} · ${node.protocol}" +
                    (if (node.aiGeoBlocked) " · 🚫AI锁区" else "") +
                    (if (node.isPinned) " · 📌手动锁定" else if (node.isActive) " · ⚡当前优选" else ""),
                color = if (node.aiGeoBlocked) Color(0xFFFB923C) else Color(0xFF94A3B8),
                fontSize = 11.sp
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(latencyText, color = latencyColor, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E293B))
                    .clickable { onSingleProbe() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("测速", color = Color(0xFFCBD5E1), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun AiModelMatrixSettingsPane(coordinator: CockpitRuntimeCoordinator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val catalog by coordinator.modelCatalogFlow.collectAsState()
    val refreshedAt by coordinator.modelRefreshedAtFlow.collectAsState()
    val selections by coordinator.roleSelectionsFlow.collectAsState()
    var isRefreshing by remember { mutableStateOf(false) }

    val refreshedTimeLabel = remember(refreshedAt) {
        if (refreshedAt <= 0L) "内置 2026-11 预设库（尚未联网刷新）"
        else "上次从官方接口刷新：" + SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(refreshedAt))
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Live Provider Model Discovery Header Card
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF131C31))
                    .border(1.dp, Color(0xFF3B82F6), RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("🧠 OpenAI & Google Gemini 全场景模型矩阵", color = Color(0xFF93C5FD), fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "$refreshedTimeLabel · 当前共收录 ${catalog.size} 款可选模型",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.5.sp
                )
                Button(
                    enabled = !isRefreshing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2563EB),
                        disabledContainerColor = Color(0xFF1E293B),
                        disabledContentColor = Color(0xFF64748B)
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        isRefreshing = true
                        scope.launch {
                            try {
                                val res = coordinator.refreshLiveProviderModels()
                                val msg = if (res.warnings.isEmpty()) {
                                    "✅ 已同步最新模型：OpenAI ${res.openAiDiscoveredCount} 款 + Gemini ${res.geminiDiscoveredCount} 款"
                                } else {
                                    "已部分刷新（OpenAI ${res.openAiDiscoveredCount} / Gemini ${res.geminiDiscoveredCount}）：${res.warnings.joinToString()}"
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            } finally {
                                isRefreshing = false
                            }
                        }
                    }
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("正在查询 OpenAI & Gemini /models 接口…", fontSize = 12.5.sp)
                    } else {
                        Text("🔄 一键刷新服务商最新可用模型列表", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 2. Categorized Role Selectors
        items(items = ModelRoleCategory.values().toList(), key = { it.name }) { category ->
            val options = remember(catalog, category) {
                ModelCatalogRegistry.optionsForCategory(category, catalog)
            }
            val currentSelKey = selections[category] ?: category.defaultSelection
            ModelCategorySelectorCard(
                category = category,
                currentSelectionKey = currentSelKey,
                options = options,
                onSelect = { chosenKey ->
                    coordinator.setModelRoleSelection(category, chosenKey)
                }
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
    var expanded by remember { mutableStateOf(false) }
    val selectedItem = options.firstOrNull { it.selectionKey == currentSelectionKey }
    val providerTag = currentSelectionKey.substringBefore(':', "openai")
    val modelIdOnly = currentSelectionKey.substringAfter(':', currentSelectionKey)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF131C31))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(category.title, color = Color(0xFFF8FAFC), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(category.subtitle, color = Color(0xFF94A3B8), fontSize = 11.5.sp)

        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0B1120))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    ProviderBadge(providerTag)
                    Column {
                        Text(
                            text = selectedItem?.label ?: modelIdOnly,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = modelIdOnly,
                            color = Color(0xFF64748B),
                            fontSize = 10.5.sp
                        )
                    }
                }
                Text("切换 ▼", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .background(Color(0xFF0F172A))
                    .width(330.dp)
            ) {
                options.forEach { opt ->
                    DropdownMenuItem(
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    ProviderBadge(opt.providerId)
                                    Text(
                                        text = opt.label + (if (opt.selectionKey == currentSelectionKey) " ✓" else ""),
                                        color = if (opt.selectionKey == currentSelectionKey) Color(0xFF38BDF8) else Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(opt.description, color = Color(0xFF94A3B8), fontSize = 11.sp)
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
    val isOpenAi = providerId.equals("openai", ignoreCase = true)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isOpenAi) Color(0xFF064E3B) else Color(0xFF1E3A8A))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = if (isOpenAi) "OpenAI" else "Gemini",
            color = if (isOpenAi) Color(0xFF6EE7B7) else Color(0xFF93C5FD),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SettingsTabButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(0xFF0284C7) else Color(0xFF162032))
            .clickable { onClick() }
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else Color(0xFF94A3B8),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun ModeSelectChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFF1E3A8A) else Color(0xFF0B1120))
            .border(1.dp, if (selected) Color(0xFF38BDF8) else Color(0xFF334155), RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 7.dp, horizontal = 4.dp)
    ) {
        Text(label, color = if (selected) Color.White else Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
