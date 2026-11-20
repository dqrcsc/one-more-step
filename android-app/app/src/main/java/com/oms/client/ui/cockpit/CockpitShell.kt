package com.oms.client.ui.cockpit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.oms.client.OmsApplication
import com.oms.client.edge.StandbyLeaseState
import kotlinx.coroutines.launch

enum class CockpitTab {
    CHAT,
    CHISU,
    MISSION_HUB
}

/**
 * Root 3-Workspace Cockpit Shell for 青简·咫尺 v2.0 (`✦ 对话 | 📖 尺素 | 🕹️ 任务舱`),
 * plus swipeable Global FTS Search & Thread History Drawer, Glass Camera / Live Voice Modals,
 * and 1-tap System Pairing & Diagnostics Console Sheet.
 */
@Composable
fun OmsCockpitRootScreen(legacySystemConsoleContent: @Composable () -> Unit) {
    val app = OmsApplication.instance ?: return
    val coordinator = app.cockpitCoordinator
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val activeTier by app.transportClient.activeTierFlow.collectAsState()
    val streamItems by app.repository.itemsFlow.collectAsState()
    val standbyLease by app.wechatStandby.leaseStateFlow.collectAsState()
    val threads by coordinator.chatRepo.threadsFlow.collectAsState()
    val activeThreadId by coordinator.chatRepo.activeThreadIdFlow.collectAsState()
    val agentSessions by coordinator.agentSessionsFlow.collectAsState()

    var currentTab by remember { mutableStateOf(CockpitTab.CHAT) }
    var showBotanicalCamera by remember { mutableStateOf(false) }
    var showLiveVoiceModal by remember { mutableStateOf(false) }
    var liveModalInitialGlass by remember { mutableStateOf(false) }
    var showSystemConsoleDialog by remember { mutableStateOf(false) }
    var initialSettingsTab by remember { mutableStateOf(SettingsStudioTab.PROXY_TUNNEL) }

    var searchQuery by remember { mutableStateOf("") }
    var chatPrefillDraft by remember { mutableStateOf("") }
    var agentPrefillDraft by remember { mutableStateOf("") }
    var forceAwayStandby by remember { mutableStateOf(app.edgeStore.isForceAwayStandbyEnabled()) }

    val pendingApprovalsCount = remember(streamItems) {
        streamItems.count { it.itemType == "APPROVAL" && it.syncStatus == "PENDING_APPROVAL" }
    }
    val runningAgentsCount = remember(agentSessions) {
        agentSessions.count { it.status == "running" }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color(0xFF0B1120),
                modifier = Modifier.width(305.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("✦ 咫尺记忆与历史", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)

                    // New Thread + Incognito Row
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                coordinator.chatRepo.createNewThread(incognito = false)
                                currentTab = CockpitTab.CHAT
                                scope.launch { drawerState.close() }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("+ 新对话", fontSize = 12.5.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                coordinator.chatRepo.createNewThread(incognito = true)
                                currentTab = CockpitTab.CHAT
                                scope.launch { drawerState.close() }
                            }
                        ) {
                            Text("🕵️ 无痕", color = Color(0xFFF5D0FE), fontSize = 12.5.sp)
                        }
                    }

                    // Instant SQLite FTS Global Search
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("🔍 全文检索历史对话…", color = Color(0xFF64748B), fontSize = 12.5.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (searchQuery.isNotBlank()) {
                        val hits = remember(searchQuery, threads) {
                            coordinator.chatRepo.searchGlobalMessages(searchQuery, limit = 25)
                        }
                        Text("命中 ${hits.size} 条记录", color = Color(0xFF7DD3FC), fontSize = 11.sp)
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(items = hits, key = { it.messageId }) { hit ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF131C31))
                                        .clickable {
                                            coordinator.chatRepo.selectThread(hit.threadId)
                                            currentTab = CockpitTab.CHAT
                                            scope.launch { drawerState.close() }
                                        }
                                        .padding(10.dp)
                                ) {
                                    Text(hit.threadTitle, color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(hit.snippet, color = Color(0xFFCBD5E1), fontSize = 11.5.sp, maxLines = 2)
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(items = threads, key = { it.threadId }) { thr ->
                                val isSel = thr.threadId == activeThreadId
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isSel) Color(0xFF1E3A8A) else Color(0xFF131C31))
                                        .clickable {
                                            coordinator.chatRepo.selectThread(thr.threadId)
                                            currentTab = CockpitTab.CHAT
                                            scope.launch { drawerState.close() }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 9.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = (if (thr.pinned) "📌 " else "") + thr.title,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            text = "📌",
                                            fontSize = 11.sp,
                                            modifier = Modifier.clickable { coordinator.chatRepo.togglePinThread(thr.threadId) }
                                        )
                                        Text(
                                            text = "✕",
                                            color = Color(0xFF64748B),
                                            fontSize = 12.sp,
                                            modifier = Modifier.clickable { coordinator.chatRepo.deleteThread(thr.threadId) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Away-from-Home WeChat Takeover Toggle & System Console Entry
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF131C31))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("🌙 离家 5G 接管尺素", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text("默认同 Wi-Fi 确认 Mac 休眠后接管", color = Color(0xFF64748B), fontSize = 10.5.sp)
                        }
                        Switch(
                            checked = forceAwayStandby,
                            onCheckedChange = { checked ->
                                forceAwayStandby = checked
                                app.edgeStore.setForceAwayStandbyEnabled(checked)
                                app.wechatStandby.refreshState()
                            }
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                scope.launch { drawerState.close() }
                                initialSettingsTab = SettingsStudioTab.PROXY_TUNNEL
                                showSystemConsoleDialog = true
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("✈️ 代理节点", color = Color(0xFF7DD3FC), fontSize = 11.5.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch { drawerState.close() }
                                initialSettingsTab = SettingsStudioTab.AI_MODELS
                                showSystemConsoleDialog = true
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("🧠 模型矩阵", color = Color(0xFFC4B5FD), fontSize = 11.5.sp)
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch { drawerState.close() }
                            initialSettingsTab = SettingsStudioTab.SYSTEM_PAIRING
                            showSystemConsoleDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🔗 设备配对 · 权限向导 · OTA 控制台", color = Color(0xFF94A3B8), fontSize = 11.5.sp)
                    }
                }
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B1120))
        ) {
            // Top 3-Workspace Navigation Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF090E1A))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Drawer Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF162032))
                        .clickable { scope.launch { drawerState.open() } }
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Text("☰", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }

                // Center 3-Segment Workspace Switcher
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF131C31))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    WorkspaceSegButton("✦ 对话", currentTab == CockpitTab.CHAT) { currentTab = CockpitTab.CHAT }
                    WorkspaceSegButton(
                        label = if (pendingApprovalsCount > 0) "📖 尺素($pendingApprovalsCount)" else "📖 尺素",
                        selected = currentTab == CockpitTab.CHISU
                    ) { currentTab = CockpitTab.CHISU }
                    WorkspaceSegButton(
                        label = if (runningAgentsCount > 0) "🕹️ 任务($runningAgentsCount)" else "🕹️ 任务舱",
                        selected = currentTab == CockpitTab.MISSION_HUB
                    ) { currentTab = CockpitTab.MISSION_HUB }
                }

                // Right Status + Settings Capsule
                val statusLabel = when {
                    activeTier != "DISCONNECTED" -> "🟢 Mac · ⚙️"
                    standbyLease == StandbyLeaseState.ACTIVE_POLLING -> "🌙 接管 · ⚙️"
                    app.proxyManager.hasAvailableNodes() -> "✈️ 云端 · ⚙️"
                    else -> "⚙️ 配置"
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF162032))
                        .border(
                            1.dp,
                            if (activeTier != "DISCONNECTED") Color(0xFF10B981) else Color(0xFF334155),
                            RoundedCornerShape(14.dp)
                        )
                        .clickable {
                            initialSettingsTab = SettingsStudioTab.PROXY_TUNNEL
                            showSystemConsoleDialog = true
                        }
                        .padding(horizontal = 9.dp, vertical = 6.dp)
                ) {
                    Text(statusLabel, color = Color(0xFFE2E8F0), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // Active Workspace Body
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (currentTab) {
                    CockpitTab.CHAT -> ChatWorkspaceView(
                        coordinator = coordinator,
                        prefillDraft = chatPrefillDraft,
                        onConsumePrefillDraft = { chatPrefillDraft = "" },
                        onOpenBotanicalCamera = { showBotanicalCamera = true },
                        onOpenLiveVoice = { glass ->
                            liveModalInitialGlass = glass
                            showLiveVoiceModal = true
                        },
                        onEscalateToMacAgent = { summary ->
                            agentPrefillDraft = summary
                            currentTab = CockpitTab.MISSION_HUB
                        }
                    )

                    CockpitTab.CHISU -> ChisuWorkspaceView(
                        streamItems = streamItems,
                        onDiscussArticleInChat = { item ->
                            chatPrefillDraft = "请结合这篇尺素文章《${item.title ?: "文章"}》帮我进一步深入分析：\n${item.textContent.orEmpty().take(1500)}"
                            currentTab = CockpitTab.CHAT
                        },
                        onSendArticleToMacAgent = { item ->
                            agentPrefillDraft = "请根据尺素文章《${item.title ?: "文章"}》中的核心启发，在对应项目中设计原型或写入知识库深读笔记：\n${item.textContent.orEmpty().take(2000)}"
                            currentTab = CockpitTab.MISSION_HUB
                        }
                    )

                    CockpitTab.MISSION_HUB -> MissionHubWorkspaceView(
                        coordinator = coordinator,
                        macConnected = activeTier != "DISCONNECTED",
                        prefillAgentPrompt = agentPrefillDraft,
                        onConsumePrefillAgentPrompt = { agentPrefillDraft = "" }
                    )
                }
            }
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
                                com.oms.client.cockpit.ChatMessageModel(
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

    // Unified Configuration Studio (`⚙️ 代理节点 | 🧠 模型矩阵 | 🔗 配对与权限`)
    if (showSystemConsoleDialog) {
        Dialog(
            onDismissRequest = { showSystemConsoleDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            CockpitSettingsModal(
                coordinator = coordinator,
                initialTab = initialSettingsTab,
                onDismiss = { showSystemConsoleDialog = false },
                legacySystemConsoleContent = legacySystemConsoleContent
            )
        }
    }
}

@Composable
private fun WorkspaceSegButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(15.dp))
            .background(if (selected) Color(0xFF0284C7) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else Color(0xFF94A3B8),
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}
