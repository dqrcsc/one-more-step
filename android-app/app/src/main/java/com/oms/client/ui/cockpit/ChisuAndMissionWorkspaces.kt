package com.oms.client.ui.cockpit

import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.window.Dialog
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
import com.oms.client.OmsApplication
import com.oms.client.capability.NotificationExecutor
import com.oms.client.capability.SpeechAudioPolicy
import com.oms.client.cockpit.CockpitRuntimeCoordinator
import com.oms.client.cockpit.RemoteAgentSessionUi
import com.oms.client.data.StreamItemModel
import com.oms.client.ui.markdown.IncrementalMarkdownView
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Workspace 2 (`📖 尺素`): Segmented WeChat Article Digest Reader, Commute TTS Playlist & Remote Approval Stream.
 */
@Composable
fun ChisuWorkspaceView(
    streamItems: List<StreamItemModel>,
    onDiscussArticleInChat: (StreamItemModel) -> Unit,
    onSendArticleToMacAgent: (StreamItemModel) -> Unit
) {
    val context = LocalContext.current
    val app = OmsApplication.instance ?: return
    val isSpeaking by NotificationExecutor.isSpeakingFlow.collectAsState()
    var filterKey by remember { mutableStateOf("ALL") }
    var expandedItemId by remember { mutableStateOf<String?>(null) }

    val filteredItems = remember(streamItems, filterKey) {
        when (filterKey) {
            "ARTICLE" -> streamItems.filter {
                it.intentTag.contains("wechat", ignoreCase = true) ||
                    it.intentTag == "pkb_vault" ||
                    it.title?.contains("《") == true ||
                    it.textContent?.contains("脱水干货") == true
            }
            "EDGE_NIGHT" -> streamItems.filter { it.intentTag == "wechat_edge_standby" }
            "APPROVAL" -> streamItems.filter { it.itemType == "APPROVAL" }
            else -> streamItems
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Filter & Commute Broadcast Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0D1424))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterPill("全部 (${streamItems.size})", filterKey == "ALL") { filterKey = "ALL" }
                FilterPill("📄 尺素精读", filterKey == "ARTICLE") { filterKey = "ARTICLE" }
                FilterPill("🌙 手机抢答", filterKey == "EDGE_NIGHT") { filterKey = "EDGE_NIGHT" }
                FilterPill("🛡️ 远程审批", filterKey == "APPROVAL") { filterKey = "APPROVAL" }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isSpeaking) Color(0xFFDC2626) else Color(0xFF0284C7))
                    .clickable {
                        if (isSpeaking) {
                            NotificationExecutor.stopSpeaking(context)
                        } else {
                            val digestPlaylist = filteredItems
                                .filter { !it.textContent.isNullOrBlank() }
                                .take(5)
                                .joinToString("\n\n下一篇：\n") {
                                    "${it.title ?: "尺素简报"}。${it.textContent.orEmpty().take(700)}"
                                }
                            if (digestPlaylist.isNotBlank()) {
                                app.transportClient.speakTextSmart(
                                    digestPlaylist,
                                    useAlarmStream = false,
                                    audioPolicy = SpeechAudioPolicy.MEDIA_COMMUTE
                                )
                            } else {
                                Toast.makeText(context, "暂无可朗读的尺素内容", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (isSpeaking) "⏹ 停止联播" else "🎧 通勤联播",
                    color = Color.White,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (filteredItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "📖 转发微信公众号文章给「尺素」即可自动精析入库\n（Mac 休眠时由手机咫尺自动抢答）",
                    color = Color(0xFF64748B),
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(items = filteredItems, key = { it.itemId }) { item ->
                    val isExpanded = expandedItemId == item.itemId
                    ChisuDigestCard(
                        item = item,
                        expanded = isExpanded,
                        onToggleExpand = { expandedItemId = if (isExpanded) null else item.itemId },
                        onApproveDecision = { approved, opt ->
                            val meta = runCatching { JSONObject(item.metadataJson ?: "{}") }.getOrDefault(JSONObject())
                            val approvalId = meta.optString("approvalId", item.itemId)
                            app.transportClient.sendApprovalDecision(
                                itemId = item.itemId,
                                approvalId = approvalId,
                                approved = approved,
                                selectedOption = opt,
                                userComment = null
                            )
                        },
                        onDiscussInChat = { onDiscussArticleInChat(item) },
                        onSendToAgent = { onSendArticleToMacAgent(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChisuDigestCard(
    item: StreamItemModel,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onApproveDecision: (Boolean, String) -> Unit,
    onDiscussInChat: () -> Unit,
    onSendToAgent: () -> Unit
) {
    val context = LocalContext.current
    val timeLabel = remember(item.createdAtMs) {
        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(item.createdAtMs))
    }
    val isNightEdge = item.intentTag == "wechat_edge_standby"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF131C31))
            .border(
                1.dp,
                if (item.itemType == "APPROVAL" && item.syncStatus == "PENDING_APPROVAL") Color(0xFFF59E0B)
                else if (isNightEdge) Color(0xFF8B5CF6)
                else Color(0xFF1E293B),
                RoundedCornerShape(16.dp)
            )
            .clickable { onToggleExpand() }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when {
                    isNightEdge -> "🌙 手机夜间抢答 · $timeLabel"
                    item.itemType == "APPROVAL" -> "🛡️ 远程审批请求 · $timeLabel"
                    else -> "📖 ${item.intentTag} · $timeLabel"
                },
                color = if (isNightEdge) Color(0xFFC4B5FD) else Color(0xFF7DD3FC),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (expanded) "收起 ▲" else "展开精读 ▼",
                color = Color(0xFF64748B),
                fontSize = 11.sp
            )
        }

        if (!item.title.isNullOrBlank()) {
            Text(
                text = item.title,
                color = Color(0xFFF8FAFC),
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Bold
            )
        }

        val body = item.textContent ?: item.voiceTranscript ?: ""
        if (body.isNotBlank()) {
            if (expanded) {
                IncrementalMarkdownView(markdown = body)
            } else {
                Text(
                    text = body.replace(Regex("\\s+"), " ").take(160),
                    color = Color(0xFFCBD5E1),
                    fontSize = 13.5.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 20.sp
                )
            }
        }

        // Remote Approval Buttons if pending
        if (item.itemType == "APPROVAL" && item.syncStatus == "PENDING_APPROVAL") {
            val meta = runCatching { JSONObject(item.metadataJson ?: "{}") }.getOrDefault(JSONObject())
            val optsArr = meta.optJSONArray("options")
            val allowLabel = optsArr?.optString(0)?.takeIf { it.isNotBlank() } ?: "批准执行"
            val denyLabel = optsArr?.optString(1)?.takeIf { it.isNotBlank() } ?: "拒绝"
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                    onClick = { onApproveDecision(true, allowLabel) }
                ) {
                    Text("✅ $allowLabel", fontSize = 12.sp)
                }
                OutlinedButton(onClick = { onApproveDecision(false, denyLabel) }) {
                    Text("✕ $denyLabel", color = Color(0xFFFCA5A5), fontSize = 12.sp)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "🔊 朗读本篇",
                color = Color(0xFF94A3B8),
                fontSize = 11.5.sp,
                modifier = Modifier.clickable {
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
            Text(
                text = "💬 带着这篇问 AI",
                color = Color(0xFF38BDF8),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { onDiscussInChat() }
            )
            Text(
                text = "🚀 派单落地验证",
                color = Color(0xFF34D399),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { onSendToAgent() }
            )
        }
    }
}

/**
 * Workspace 3 (`🕹️ 任务舱`): Mac Heavy-Brain Hub for OpenAI Codex & Google Antigravity (`agy`).
 * Supports real-time desktop/mobile session monitoring, new task dispatch, and cross-device `resume` continuation!
 */
@Composable
fun MissionHubWorkspaceView(
    coordinator: CockpitRuntimeCoordinator,
    macConnected: Boolean,
    prefillAgentPrompt: String,
    onConsumePrefillAgentPrompt: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessions by coordinator.agentSessionsFlow.collectAsState()
    val projects by coordinator.agentProjectsFlow.collectAsState()
    val selectedSession by coordinator.selectedAgentSessionFlow.collectAsState()
    val selectedSteps by coordinator.selectedAgentStepsFlow.collectAsState()

    var backendFilter by remember { mutableStateOf("ALL") }
    var projectFilterPath by remember { mutableStateOf("") }
    var showNewTaskSheet by remember { mutableStateOf(false) }
    var newBackend by remember { mutableStateOf("codex") }
    var selectedWorkspacePath by remember { mutableStateOf("") }
    var useCustomWorkspaceInput by remember { mutableStateOf(false) }
    var showProjectPickerDialog by remember { mutableStateOf(false) }
    var projectSearchText by remember { mutableStateOf("") }

    // "" means "+ Create New Session in this project"; non-blank means "Continue this existing sessionId"
    var targetResumeSessionId by remember { mutableStateOf("") }
    var showSessionDropdown by remember { mutableStateOf(false) }

    var newPrompt by remember { mutableStateOf("") }
    var isDispatching by remember { mutableStateOf(false) }

    LaunchedEffect(Unit, macConnected) {
        if (macConnected) coordinator.refreshAgentSessions()
    }

    // Auto-select first project once catalog arrives
    LaunchedEffect(projects) {
        if (selectedWorkspacePath.isBlank() && projects.isNotEmpty() && !useCustomWorkspaceInput) {
            selectedWorkspacePath = projects.first().projectPath
        }
    }

    LaunchedEffect(prefillAgentPrompt) {
        if (prefillAgentPrompt.isNotBlank()) {
            newPrompt = prefillAgentPrompt
            showNewTaskSheet = true
            onConsumePrefillAgentPrompt()
        }
    }

    if (selectedSession != null) {
        AgentSessionDetailScreen(
            session = selectedSession!!,
            steps = selectedSteps,
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
                        Toast.makeText(context, "接续失败：${it.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
        return
    }

    val projectSessions = remember(sessions, selectedWorkspacePath) {
        val norm = selectedWorkspacePath.trim().trimEnd('/')
        if (norm.isEmpty()) sessions
        else sessions.filter { it.workspacePath.trim().trimEnd('/') == norm }
    }
    val chosenResumeSession = remember(projectSessions, targetResumeSessionId) {
        projectSessions.firstOrNull { it.sessionId == targetResumeSessionId }
    }

    val filtered = remember(sessions, backendFilter, projectFilterPath) {
        sessions.filter { s ->
            (backendFilter == "ALL" || s.backend == backendFilter) &&
                (projectFilterPath.isBlank() || s.workspacePath.trimEnd('/') == projectFilterPath.trimEnd('/'))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top Filter + Dispatch Action Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0D1424))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterPill("全部 (${sessions.size})", backendFilter == "ALL" && projectFilterPath.isBlank()) {
                    backendFilter = "ALL"
                    projectFilterPath = ""
                }
                FilterPill("🤖 Codex", backendFilter == "codex") { backendFilter = "codex" }
                FilterPill("🌌 Antigravity", backendFilter == "agy") { backendFilter = "agy" }
            }
            Spacer(modifier = Modifier.width(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "🔄",
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1E293B))
                        .clickable { coordinator.refreshAgentSessions() }
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0284C7))
                        .clickable { showNewTaskSheet = !showNewTaskSheet }
                        .padding(horizontal = 11.dp, vertical = 6.dp)
                ) {
                    Text(if (showNewTaskSheet) "收起派单" else "+ 远程派单", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (!macConnected) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF3B1D1D))
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text("⚠️ 当前未连通 Mac omsd，连接恢复后即可实时查看桌面会话并远程派单/接续。", color = Color(0xFFFECACA), fontSize = 11.5.sp)
            }
        }

        // Inline Smart Project & Session Dispatch Composer
        if (showNewTaskSheet) {
            val currentProjObj = projects.firstOrNull {
                it.projectPath.trimEnd('/') == selectedWorkspacePath.trimEnd('/')
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF131C31))
                    .border(1.dp, Color(0xFF0284C7), RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. Project Dropdown Selector
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("📁 派单目标项目", color = Color(0xFF93C5FD), fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = if (useCustomWorkspaceInput) "返回下拉选择列表" else "✏️ 手动输入路径",
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp,
                            modifier = Modifier.clickable { useCustomWorkspaceInput = !useCustomWorkspaceInput }
                        )
                    }

                    if (useCustomWorkspaceInput) {
                        OutlinedTextField(
                            value = selectedWorkspacePath,
                            onValueChange = {
                                selectedWorkspacePath = it
                                targetResumeSessionId = ""
                            },
                            placeholder = { Text("输入 Mac 目录路径（如 ~/x_project/one_more_step）", color = Color(0xFF64748B), fontSize = 12.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF0B1120))
                                .border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp))
                                .clickable { showProjectPickerDialog = true }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = currentProjObj?.let { "${it.displayName}  (${it.group})" }
                                        ?: selectedWorkspacePath.substringAfterLast('/').ifBlank { "点击选择 Mac 项目…" },
                                    color = Color.White,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (selectedWorkspacePath.isNotBlank()) {
                                    Text(
                                        text = selectedWorkspacePath,
                                        color = Color(0xFF64748B),
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Text(
                                text = "共 ${projects.size} 个项目 ▼",
                                color = Color(0xFF38BDF8),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // 2. Session Mode Selector: New Session vs Continue Existing Project Session!
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("💬 执行会话（新建会话 或 接续该项目已有会话）", color = Color(0xFF93C5FD), fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF0B1120))
                                .border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp))
                                .clickable { showSessionDropdown = true }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                if (chosenResumeSession == null) {
                                    Text("✨ 在此项目中开启新会话", color = Color(0xFF6EE7B7), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("该目录下现有 ${projectSessions.size} 个历史/活跃会话可供接续", color = Color(0xFF64748B), fontSize = 11.sp)
                                } else {
                                    Text(
                                        text = "🔄 接续：${chosenResumeSession.title}",
                                        color = Color(0xFFFDE047),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${if (chosenResumeSession.backend == "codex") "🤖 Codex" else "🌌 Antigravity"} · ${chosenResumeSession.lastSummary.take(46)}",
                                        color = Color(0xFF94A3B8),
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Text("选择 ▼", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }

                        DropdownMenu(
                            expanded = showSessionDropdown,
                            onDismissRequest = { showSessionDropdown = false },
                            modifier = Modifier
                                .background(Color(0xFF0F172A))
                                .width(330.dp)
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("✨ 在此项目中开启新会话", color = Color(0xFF6EE7B7), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        Text("不复用旧上下文，启动全新的独立 Agent 任务", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                    }
                                },
                                onClick = {
                                    targetResumeSessionId = ""
                                    showSessionDropdown = false
                                }
                            )
                            projectSessions.forEach { sess ->
                                DropdownMenuItem(
                                    text = {
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                text = "${if (sess.backend == "codex") "🤖 Codex" else "🌌 AGY"} · ${sess.title}",
                                                color = Color.White,
                                                fontSize = 12.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = sess.lastSummary.ifBlank { "点击选择接续此会话" },
                                                color = Color(0xFF94A3B8),
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
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
                }

                // 3. Agent Backend Picker (active when starting a new session)
                if (chosenResumeSession == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterPill("🤖 OpenAI Codex", newBackend == "codex") { newBackend = "codex" }
                        FilterPill("🌌 Google Antigravity", newBackend == "agy") { newBackend = "agy" }
                    }
                }

                // 4. Task Prompt Input
                OutlinedTextField(
                    value = newPrompt,
                    onValueChange = { newPrompt = it },
                    placeholder = {
                        Text(
                            text = if (chosenResumeSession != null) "输入接续该会话的追问、修改意见或下一步指令…"
                            else "输入要让 Mac Agent 自主完成的编码、检索或知识库治理任务…",
                            color = Color(0xFF64748B),
                            fontSize = 13.sp
                        )
                    },
                    minLines = 3,
                    maxLines = 6,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    enabled = macConnected && newPrompt.isNotBlank() && !isDispatching,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (chosenResumeSession != null) Color(0xFF059669) else Color(0xFF0284C7),
                        disabledContainerColor = Color(0xFF1E293B),
                        disabledContentColor = Color(0xFF94A3B8)
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        isDispatching = true
                        scope.launch {
                            try {
                                if (chosenResumeSession != null) {
                                    coordinator.continueRemoteAgentSession(
                                        backend = chosenResumeSession.backend,
                                        sessionId = chosenResumeSession.sessionId,
                                        prompt = newPrompt.trim(),
                                        workspacePath = selectedWorkspacePath.trim()
                                    )
                                } else {
                                    coordinator.startRemoteAgentSession(
                                        backend = newBackend,
                                        prompt = newPrompt.trim(),
                                        workspacePath = selectedWorkspacePath.trim()
                                    )
                                }
                                newPrompt = ""
                                showNewTaskSheet = false
                            } catch (e: Throwable) {
                                Toast.makeText(context, "派单失败：${e.message}", Toast.LENGTH_SHORT).show()
                            } finally {
                                isDispatching = false
                            }
                        }
                    }
                ) {
                    Text(
                        text = when {
                            isDispatching -> "🚀 正在派发至 Mac…"
                            chosenResumeSession != null -> "🚀 发送并接续所选会话"
                            newPrompt.isBlank() -> "🚀 请先输入任务内容后派发"
                            else -> "🚀 立即在所选项目启动新会话"
                        },
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Searchable Project Picker Modal
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(480.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF0F172A))
                        .border(1.dp, Color(0xFF334155), RoundedCornerShape(18.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📁 选择 Mac 项目仓库", color = Color.White, fontSize = 15.5.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "✕ 关闭",
                            color = Color(0xFF94A3B8),
                            fontSize = 13.sp,
                            modifier = Modifier.clickable { showProjectPickerDialog = false }
                        )
                    }
                    OutlinedTextField(
                        value = projectSearchText,
                        onValueChange = { projectSearchText = it },
                        placeholder = { Text("🔍 搜索项目名（如 one_more_step / work / vpn）", color = Color(0xFF64748B), fontSize = 12.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(items = visibleProjects, key = { it.projectPath }) { proj ->
                            val isSelected = proj.projectPath.trimEnd('/') == selectedWorkspacePath.trimEnd('/')
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) Color(0xFF1E3A8A) else Color(0xFF131C31))
                                    .clickable {
                                        selectedWorkspacePath = proj.projectPath
                                        targetResumeSessionId = ""
                                        showProjectPickerDialog = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "📁 ${proj.displayName}",
                                        color = Color.White,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${proj.group} · ${proj.projectPath}",
                                        color = Color(0xFF94A3B8),
                                        fontSize = 10.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (proj.sessionCount > 0) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color(0xFF064E3B))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text("${proj.sessionCount} 会话", color = Color(0xFF6EE7B7), fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("🕹️ 暂无活跃会话，点击右上角「+ 远程派单」或刷新桌面任务", color = Color(0xFF64748B), fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items = filtered, key = { "${it.backend}:${it.sessionId}" }) { session ->
                    AgentSessionCard(
                        session = session,
                        onClick = { coordinator.selectAndLoadAgentSession(session) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentSessionCard(session: RemoteAgentSessionUi, onClick: () -> Unit) {
    val timeStr = remember(session.updatedAtMs) {
        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(session.updatedAtMs))
    }
    val statusBadge = when (session.status) {
        "running" -> "🟢 运行中" to Color(0xFF34D399)
        "completed" -> "✅ 已完成" to Color(0xFF38BDF8)
        "failed" -> "⚠️ 异常/中止" to Color(0xFFF87171)
        else -> "⏸ 可接续" to Color(0xFF94A3B8)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(Color(0xFF131C31))
            .border(
                1.dp,
                if (session.status == "running") Color(0xFF10B981) else Color(0xFF1E293B),
                RoundedCornerShape(15.dp)
            )
            .clickable { onClick() }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = (if (session.backend == "codex") "🤖 Codex" else "🌌 Antigravity") +
                    (if (session.origin == "mobile") " · 📱手机派单" else " · 🖥桌面会话"),
                color = Color(0xFF7DD3FC),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold
            )
            Text(statusBadge.first, color = statusBadge.second, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(session.title, color = Color(0xFFF8FAFC), fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2)
        if (session.lastSummary.isNotBlank()) {
            Text(session.lastSummary, color = Color(0xFFCBD5E1), fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(session.workspacePath.substringAfterLast('/'), color = Color(0xFF64748B), fontSize = 11.sp)
            Text("$timeStr · 点击盯盘或接续 →", color = Color(0xFF64748B), fontSize = 11.sp)
        }
    }
}

@Composable
private fun AgentSessionDetailScreen(
    session: RemoteAgentSessionUi,
    steps: List<com.oms.client.cockpit.RemoteAgentStepUi>,
    macConnected: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
    onContinuePrompt: (String) -> Unit
) {
    var followUpText by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        // Detail Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0D1424))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "← 返回任务列表",
                color = Color(0xFF38BDF8),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onBack() }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (session.status == "running") {
                    Text(
                        text = "⏹ 中止",
                        color = Color(0xFFF87171),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onCancel() }
                    )
                }
                Text(
                    text = "🔄 刷新轨迹",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.5.sp,
                    modifier = Modifier.clickable { onRefresh() }
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF131C31))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = "${if (session.backend == "codex") "🤖 Codex" else "🌌 Antigravity"} · ${session.title}",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "ID: ${session.sessionId.take(20)} ｜ 目录: ${session.workspacePath}",
                color = Color(0xFF64748B),
                fontSize = 11.sp
            )
        }

        // Trajectory Step Timeline
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(items = steps, key = { "${it.stepIndex}_${it.timestampMs}" }) { st ->
                val bgColor = when (st.kind) {
                    "user" -> Color(0xFF0C4A6E)
                    "thinking" -> Color(0xFF1E1B4B)
                    "tool" -> Color(0xFF0F2922)
                    else -> Color(0xFF131C31)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(bgColor)
                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(st.title, color = Color(0xFF7DD3FC), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    IncrementalMarkdownView(markdown = st.detail)
                }
            }
        }

        // Cross-Device Session Resume Bar (`codex exec resume` / `agy --conversation`)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0D1424))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = followUpText,
                onValueChange = { followUpText = it },
                placeholder = {
                    Text(
                        text = if (session.backend == "codex") "接续此 Codex 会话继续追问或改代码…"
                        else "接续此 Antigravity 会话继续追问…",
                        color = Color(0xFF64748B),
                        fontSize = 13.sp
                    )
                },
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.weight(1f)
            )
            Button(
                enabled = macConnected && followUpText.isNotBlank() && session.status != "running",
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                onClick = {
                    val prompt = followUpText.trim()
                    followUpText = ""
                    onContinuePrompt(prompt)
                }
            ) {
                Text("接续")
            }
        }
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color(0xFF1E3A8A) else Color(0xFF162032))
            .border(1.dp, if (selected) Color(0xFF38BDF8) else Color(0xFF283548), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else Color(0xFF94A3B8),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
