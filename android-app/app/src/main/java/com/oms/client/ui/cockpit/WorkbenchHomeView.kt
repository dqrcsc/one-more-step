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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oms.client.OmsApplication
import com.oms.client.cockpit.ChatThreadModel
import com.oms.client.cockpit.ChisuArticleItemUi
import com.oms.client.cockpit.RemoteAgentSessionUi
import com.oms.client.cockpit.SecretaryBriefUi
import com.oms.client.cockpit.SecretaryNudgeUi
import com.oms.client.ui.theme.OmsCircleIconButton
import com.oms.client.ui.theme.OmsFeedbackController
import com.oms.client.ui.theme.OmsIcons
import com.oms.client.ui.theme.OmsSecondaryButton
import com.oms.client.ui.theme.OmsSurfaceCard
import com.oms.client.ui.theme.OmsTheme
import com.oms.client.ui.theme.OmsToastKind
import com.oms.client.ui.theme.omsBounceClick
import com.oms.core.ai.ModelRoleCategory
import java.util.Calendar

private data class WorkbenchToolSpec(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit
)

private data class PhoneToolQuickPrompt(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val prompt: String,
    val sendImmediately: Boolean = false
)

/**
 * v3.5 Decoupled Workbench Home (`工作台`):
 * 1. Executive Brief & Living Memory Header
 * 2. Conditional Live Alerts (Pending Approvals + Running Mac Agents; 0px when calm)
 * 3. Open Loops (Commitments) & Proactive Nudges
 * 4. Full Capability Matrix (全平铺 by default, 3 groups × 4 cols = 12 capabilities, collapsible)
 * 5. Recent Activity Continuity Strip (Resume Chat / Mac Agent / Chisu without hunting tabs)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkbenchHomeView(
    shortModelName: String,
    brief: SecretaryBriefUi?,
    isRefreshingBrief: Boolean,
    pendingApprovalsCount: Int,
    runningAgents: List<RemoteAgentSessionUi>,
    recentThread: ChatThreadModel?,
    latestAgentSession: RemoteAgentSessionUi?,
    recentChisuArticles: List<ChisuArticleItemUi>,
    onRefreshBrief: () -> Unit,
    onAddCommitment: (title: String, dueHint: String) -> Unit,
    onToggleCommitment: (id: String) -> Unit,
    onSaveProfile: (items: List<String>) -> Unit,
    onDismissNudge: (id: String) -> Unit,
    onTriggerNudge: (SecretaryNudgeUi) -> Unit,
    onOpenLiveVoice: (glassCamera: Boolean) -> Unit,
    onOpenBotanicalCamera: () -> Unit,
    onOpenSpecimenGallery: () -> Unit,
    onLaunchDeepThinking: () -> Unit,
    onLaunchCreativeMode: (ModelRoleCategory) -> Unit,
    onPrefillPhoneCommand: (prompt: String, sendImmediately: Boolean) -> Unit,
    onOpenMacDrop: () -> Unit,
    onOpenOmniSearch: () -> Unit,
    onOpenMacDispatch: () -> Unit,
    onOpenMemoryCenter: () -> Unit = {},
    onOpenMacRemoteControl: () -> Unit = {},
    onOpenApprovals: () -> Unit,
    onResumeThread: (ChatThreadModel) -> Unit,
    onOpenAgentSession: (RemoteAgentSessionUi) -> Unit,
    onOpenChisuTab: () -> Unit
) {
    val palette = OmsTheme.colors
    val edgeStore = OmsApplication.instance?.edgeStore

    var matrixExpanded by remember {
        mutableStateOf(edgeStore?.isWorkbenchMatrixExpanded() ?: true)
    }
    var showPhoneToolsSheet by remember { mutableStateOf(false) }
    var showAddLoopDialog by remember { mutableStateOf(false) }
    var newLoopTitle by remember { mutableStateOf("") }
    var newLoopDue by remember { mutableStateOf("") }
    var showMemoryDialog by remember { mutableStateOf(false) }
    var memoryEditorText by remember { mutableStateOf("") }

    val fallbackGreeting = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when {
            hour < 6 -> "夜深了"
            hour < 12 -> "早上好"
            hour < 18 -> "下午好"
            else -> "晚上好"
        }
    }
    val greetingTitle = brief?.let { "${it.greeting} · ${it.dateLabel}" } ?: fallbackGreeting
    val subtitleLine = brief?.headline?.ifBlank { "$shortModelName · 随身助理持续记忆中" }
        ?: "$shortModelName · 随身助理与知识库按需共鸣"

    val groupRealtime = listOf(
        WorkbenchToolSpec(OmsIcons.Waveform, "散步语音") { onOpenLiveVoice(false) },
        WorkbenchToolSpec(OmsIcons.Video, "Glass镜头") { onOpenLiveVoice(true) },
        WorkbenchToolSpec(OmsIcons.Leaf, "博物识物") { onOpenBotanicalCamera() },
        WorkbenchToolSpec(OmsIcons.Bookmark, "博物图鉴") { onOpenSpecimenGallery() }
    )
    val groupCreation = listOf(
        WorkbenchToolSpec(OmsIcons.Lightbulb, "深度思考") { onLaunchDeepThinking() },
        WorkbenchToolSpec(OmsIcons.Palette, "视觉生图") { onLaunchCreativeMode(ModelRoleCategory.IMAGE_GENERATION) },
        WorkbenchToolSpec(OmsIcons.Video, "视频生成") { onLaunchCreativeMode(ModelRoleCategory.VIDEO_GENERATION) },
        WorkbenchToolSpec(OmsIcons.Music, "AI 写歌") { onLaunchCreativeMode(ModelRoleCategory.MUSIC_GENERATION) }
    )
    val groupCrossDevice = listOf(
        WorkbenchToolSpec(OmsIcons.Smartphone, "手机工具") { showPhoneToolsSheet = true },
        WorkbenchToolSpec(OmsIcons.Laptop, "遥控 Mac") { onOpenMacRemoteControl() },
        WorkbenchToolSpec(OmsIcons.Send, "快传 Mac") { onOpenMacDrop() },
        WorkbenchToolSpec(OmsIcons.Terminal, "Mac 派单") { onOpenMacDispatch() }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Executive Header: Greeting + Transparent Living Memory + Refresh
        Row(
            modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = greetingTitle,
                    color = palette.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitleLine,
                    color = palette.textTertiary,
                    fontSize = 12.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OmsSecondaryButton(
                    label = "记忆中心${if ((brief?.profileItems?.size ?: 0) > 0) " ${brief?.profileItems?.size}" else ""}",
                    icon = OmsIcons.Bookmark,
                    height = 31.dp,
                    onClick = onOpenMemoryCenter
                )
                OmsCircleIconButton(
                    icon = OmsIcons.Refresh,
                    contentDescription = "刷新简报",
                    size = 31.dp,
                    iconSize = 15.dp,
                    containerColor = palette.subtleSurface,
                    contentColor = if (isRefreshingBrief) palette.accent else palette.textSecondary,
                    borderColor = palette.hairline,
                    onClick = onRefreshBrief
                )
            }
        }

        // 2. Conditional Live Status Strip (Approvals & Running Mac Agents — 0px when calm!)
        if (pendingApprovalsCount > 0 || runningAgents.isNotEmpty()) {
            Column(
                modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                if (pendingApprovalsCount > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .omsBounceClick(scaleDown = 0.98f, shape = RoundedCornerShape(14.dp), onClick = onOpenApprovals)
                            .background(palette.warningSoft, RoundedCornerShape(14.dp))
                            .border(0.65.dp, palette.warning.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 13.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(OmsIcons.Shield, null, tint = palette.warning, modifier = Modifier.size(16.dp))
                            Text(
                                text = "$pendingApprovalsCount 项远程敏感操作待审批",
                                color = palette.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("去确认", color = palette.warning, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Icon(OmsIcons.ChevronRight, null, tint = palette.warning, modifier = Modifier.size(14.dp))
                        }
                    }
                }

                runningAgents.take(2).forEach { sess ->
                    val projName = sess.projectDisplayName.ifBlank {
                        sess.workspacePath.trimEnd('/').substringAfterLast('/').ifBlank { "Mac" }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .omsBounceClick(scaleDown = 0.98f, shape = RoundedCornerShape(14.dp)) {
                                onOpenAgentSession(sess)
                            }
                            .background(palette.successSoft, RoundedCornerShape(14.dp))
                            .border(0.65.dp, palette.success.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 13.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(palette.success))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "运行中 · $projName (${agentDisplayName(sess.backend)})",
                                    color = palette.success,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = sess.title.ifBlank { "正在执行远程任务…" },
                                    color = palette.textPrimary,
                                    fontSize = 12.8.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Icon(OmsIcons.ChevronRight, null, tint = palette.success, modifier = Modifier.size(15.dp))
                    }
                }
            }
        }

        // 3. Full Capability Matrix (全平铺 by default, 3 semantic groups × 4 cols)
        OmsSurfaceCard(
            cornerRadius = 18.dp,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 13.dp),
            modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth()
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
                    Icon(OmsIcons.Sparkles, contentDescription = null, tint = palette.accent, modifier = Modifier.size(15.dp))
                    Text(
                        text = "能力工作台",
                        color = palette.textPrimary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            matrixExpanded = !matrixExpanded
                            edgeStore?.setWorkbenchMatrixExpanded(matrixExpanded)
                        }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (matrixExpanded) "收起" else "展开全部 (12)",
                        color = palette.textTertiary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        imageVector = if (matrixExpanded) OmsIcons.ChevronUp else OmsIcons.ChevronDown,
                        contentDescription = null,
                        tint = palette.textTertiary,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (!matrixExpanded) {
                // Compact 1-row essentials strip when user manually collapses
                WorkbenchToolRow(
                    items = listOf(groupRealtime[0], groupRealtime[1], groupRealtime[2], groupCrossDevice[0])
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    WorkbenchToolGroupSection(title = "多模态与实时", items = groupRealtime)
                    WorkbenchToolGroupSection(title = "创作与深思", items = groupCreation)
                    WorkbenchToolGroupSection(title = "跨端与手机超能力", items = groupCrossDevice)
                }
            }
        }

        // 4. Active Open Loops (Commitments) Card
        OmsSurfaceCard(
            cornerRadius = 18.dp,
            contentPadding = PaddingValues(14.dp),
            modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth()
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
                    Icon(OmsIcons.Check, contentDescription = null, tint = palette.accent, modifier = Modifier.size(15.dp))
                    Text(
                        text = "未闭环事项 (${brief?.openLoops?.size ?: 0})",
                        color = palette.textPrimary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                OmsSecondaryButton(
                    label = "记一件",
                    icon = OmsIcons.Plus,
                    height = 28.dp,
                    onClick = {
                        newLoopTitle = ""
                        newLoopDue = ""
                        showAddLoopDialog = true
                    }
                )
            }

            val loops = brief?.openLoops.orEmpty()
            if (loops.isEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "暂无待跟进事项。对话中随口说「提醒我这周……」，秘书会自动为你记入这里。",
                    color = palette.textTertiary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    loops.take(4).forEach { loop ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.subtleSurface)
                                .clickable { onToggleCommitment(loop.id) }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .border(1.4.dp, palette.accent, CircleShape)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = loop.title,
                                    color = palette.textPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (loop.dueHint.isNotBlank()) {
                                    Text(
                                        text = loop.dueHint,
                                        color = palette.accent,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            Text("完成", color = palette.textTertiary, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // 5. Proactive Chief-of-Staff Nudges
        val nudges = brief?.nudges.orEmpty()
        if (nudges.isNotEmpty()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth()
            ) {
                nudges.take(3).forEach { nudge ->
                    val nudgeIcon = when (nudge.kind) {
                        "commute_chisu" -> OmsIcons.BookOpen
                        "agent_review" -> OmsIcons.Laptop
                        "book_reading" -> OmsIcons.FileText
                        else -> OmsIcons.Sparkles
                    }
                    OmsSurfaceCard(
                        cornerRadius = 16.dp,
                        contentPadding = PaddingValues(13.dp),
                        onClick = { onTriggerNudge(nudge) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(palette.accentSoft)
                            ) {
                                Icon(nudgeIcon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(17.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = nudge.title,
                                    color = palette.textPrimary,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = nudge.subtitle,
                                    color = palette.textSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            OmsCircleIconButton(
                                icon = OmsIcons.Close,
                                contentDescription = "暂不提示",
                                size = 24.dp,
                                iconSize = 12.dp,
                                contentColor = palette.textTertiary,
                                onClick = { onDismissNudge(nudge.id) }
                            )
                        }
                    }
                }
            }
        }

        // 6. Recent Active Continuity Strip (`刚才在进行`)
        val hasContinuity = recentThread != null || latestAgentSession != null || recentChisuArticles.isNotEmpty()
        if (hasContinuity) {
            OmsSurfaceCard(
                cornerRadius = 18.dp,
                contentPadding = PaddingValues(14.dp),
                modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(OmsIcons.Clock, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(14.dp))
                    Text(
                        text = "刚才在进行",
                        color = palette.textSecondary,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    recentThread?.let { thr ->
                        WorkbenchContinuityRow(
                            icon = OmsIcons.MessageCircle,
                            badge = "对话",
                            title = thr.title.removePrefix("🕵️ ").ifBlank { "继续上次对话" },
                            trailing = formatCompactRelativeTime(thr.updatedAtMs),
                            onClick = { onResumeThread(thr) }
                        )
                    }
                    latestAgentSession?.let { sess ->
                        val proj = sess.projectDisplayName.ifBlank {
                            sess.workspacePath.trimEnd('/').substringAfterLast('/').ifBlank { "Mac" }
                        }
                        WorkbenchContinuityRow(
                            icon = when (sess.backend) {
                                "codex" -> OmsIcons.Terminal
                                "workbuddy" -> OmsIcons.Cpu
                                "opencode" -> OmsIcons.Bolt
                                else -> OmsIcons.Sparkles
                            },
                            badge = proj,
                            title = sess.title.ifBlank { "协同会话" },
                            trailing = formatCompactRelativeTime(sess.updatedAtMs),
                            onClick = { onOpenAgentSession(sess) }
                        )
                    }
                    if (recentChisuArticles.isNotEmpty()) {
                        val latest = recentChisuArticles.first()
                        WorkbenchContinuityRow(
                            icon = OmsIcons.BookOpen,
                            badge = "尺素",
                            title = latest.title.ifBlank { "最近 ${recentChisuArticles.size} 篇伴读" },
                            trailing = "${recentChisuArticles.size} 篇",
                            onClick = onOpenChisuTab
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
    }

    // Phone OS 11-Tool Quick Action Sheet
    if (showPhoneToolsSheet) {
        val phonePrompts = remember {
            listOf(
                PhoneToolQuickPrompt(OmsIcons.Calendar, "查今天与近期日程", "读取系统日历并提醒冲突", "帮我查一下今天和明天手机日历里的所有日程安排", sendImmediately = true),
                PhoneToolQuickPrompt(OmsIcons.Clock, "设一个闹钟或提醒", "直接写入系统闹钟 / 倒计时", "帮我设一个明天早上 7:30 的闹钟，备注：", sendImmediately = false),
                PhoneToolQuickPrompt(OmsIcons.Smartphone, "查手机状态与电量", "电量、网络与剪贴板速览", "帮我查看一下当前手机的电量、网络状态和剪贴板内容", sendImmediately = true),
                PhoneToolQuickPrompt(OmsIcons.Send, "起草短信 / 联系通讯录", "自动匹配常用联系人并起草短信", "帮我给", sendImmediately = false),
                PhoneToolQuickPrompt(OmsIcons.Globe, "联网深度核查", "实时检索最新动态并附引用来源", "请帮我联网核查一下：", sendImmediately = false),
                PhoneToolQuickPrompt(OmsIcons.Bolt, "打开手机指定应用", "通过语音或自然语言拉起 App", "帮我打开手机里的", sendImmediately = false)
            )
        }
        ModalBottomSheet(
            onDismissRequest = { showPhoneToolsSheet = false },
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
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("手机原生超能力助手", color = palette.textPrimary, fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold)
                    Text("直接通过自然语言调用日历、闹钟、拨号、短信、电量、应用启动等 11 项手机原生工具", color = palette.textTertiary, fontSize = 12.sp)
                }
                phonePrompts.forEach { item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(11.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .omsBounceClick(scaleDown = 0.98f, shape = RoundedCornerShape(14.dp)) {
                                showPhoneToolsSheet = false
                                onPrefillPhoneCommand(item.prompt, item.sendImmediately)
                            }
                            .background(palette.subtleSurface, RoundedCornerShape(14.dp))
                            .border(0.6.dp, palette.hairline, RoundedCornerShape(14.dp))
                            .padding(horizontal = 13.dp, vertical = 11.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.accentSoft)
                        ) {
                            Icon(item.icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(17.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.title, color = palette.textPrimary, fontSize = 13.8.sp, fontWeight = FontWeight.SemiBold)
                            Text(item.subtitle, color = palette.textTertiary, fontSize = 11.5.sp)
                        }
                        Icon(OmsIcons.ChevronRight, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(15.dp))
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }

    if (showAddLoopDialog) {
        AlertDialog(
            onDismissRequest = { showAddLoopDialog = false },
            containerColor = palette.composer,
            title = { Text("记录未闭环事项", color = palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.subtleSurface)
                            .border(0.6.dp, palette.hairline, RoundedCornerShape(12.dp))
                            .padding(11.dp)
                    ) {
                        if (newLoopTitle.isEmpty()) {
                            Text("例如：整理 EPUB 电子书库并同步手机", color = palette.textTertiary, fontSize = 13.5.sp)
                        }
                        BasicTextField(
                            value = newLoopTitle,
                            onValueChange = { newLoopTitle = it },
                            textStyle = TextStyle(color = palette.textPrimary, fontSize = 14.sp),
                            cursorBrush = SolidColor(palette.accent),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.subtleSurface)
                            .border(0.6.dp, palette.hairline, RoundedCornerShape(12.dp))
                            .padding(11.dp)
                    ) {
                        if (newLoopDue.isEmpty()) {
                            Text("时间提示（可选，如：本周五前 / 明早）", color = palette.textTertiary, fontSize = 13.sp)
                        }
                        BasicTextField(
                            value = newLoopDue,
                            onValueChange = { newLoopDue = it },
                            singleLine = true,
                            textStyle = TextStyle(color = palette.textPrimary, fontSize = 13.5.sp),
                            cursorBrush = SolidColor(palette.accent),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newLoopTitle.isNotBlank()) {
                        onAddCommitment(newLoopTitle.trim(), newLoopDue.trim())
                        showAddLoopDialog = false
                    }
                }) { Text("记下", color = palette.accent, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showAddLoopDialog = false }) {
                    Text("取消", color = palette.textTertiary)
                }
            }
        )
    }

    if (showMemoryDialog) {
        AlertDialog(
            onDismissRequest = { showMemoryDialog = false },
            containerColor = palette.composer,
            title = { Text("随身助理长期记忆（每行一条）", color = palette.textPrimary, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "所有个人偏好与工作原则完全透明可控，会在非无痕对话中自动注入，让秘书更懂你的节奏。",
                        color = palette.textTertiary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 220.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.subtleSurface)
                            .border(0.6.dp, palette.hairline, RoundedCornerShape(12.dp))
                            .padding(11.dp)
                    ) {
                        if (memoryEditorText.isEmpty()) {
                            Text("例如：\n回答先给结论再展开细节\n关注 AI Agent 记忆与知识工程\n通勤时偏好 3 分钟语音摘要", color = palette.textTertiary, fontSize = 13.sp)
                        }
                        BasicTextField(
                            value = memoryEditorText,
                            onValueChange = { memoryEditorText = it },
                            textStyle = TextStyle(color = palette.textPrimary, fontSize = 13.5.sp, lineHeight = 20.sp),
                            cursorBrush = SolidColor(palette.accent),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val lines = memoryEditorText.split('\n').map { it.trim() }.filter { it.isNotBlank() }
                    onSaveProfile(lines)
                    showMemoryDialog = false
                    OmsFeedbackController.show("已更新秘书长期记忆", OmsToastKind.SUCCESS)
                }) { Text("保存", color = palette.accent, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showMemoryDialog = false }) {
                    Text("取消", color = palette.textTertiary)
                }
            }
        )
    }
}

@Composable
private fun WorkbenchToolGroupSection(
    title: String,
    items: List<WorkbenchToolSpec>
) {
    val palette = OmsTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            text = title,
            color = palette.textTertiary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.3.sp
        )
        WorkbenchToolRow(items = items)
    }
}

@Composable
private fun WorkbenchToolRow(items: List<WorkbenchToolSpec>) {
    val palette = OmsTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { spec ->
            val shape = RoundedCornerShape(13.dp)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .weight(1f)
                    .omsBounceClick(scaleDown = 0.94f, shape = shape, onClick = spec.onClick)
                    .background(palette.subtleSurface, shape)
                    .border(0.55.dp, palette.hairline, shape)
                    .padding(vertical = 10.dp, horizontal = 4.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(palette.accentSoft)
                ) {
                    Icon(
                        imageVector = spec.icon,
                        contentDescription = spec.label,
                        tint = palette.accent,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = spec.label,
                    color = palette.textPrimary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun WorkbenchContinuityRow(
    icon: ImageVector,
    badge: String,
    title: String,
    trailing: String,
    onClick: () -> Unit
) {
    val palette = OmsTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        modifier = Modifier
            .fillMaxWidth()
            .omsBounceClick(scaleDown = 0.98f, shape = RoundedCornerShape(11.dp), onClick = onClick)
            .background(palette.subtleSurface, RoundedCornerShape(11.dp))
            .padding(horizontal = 10.dp, vertical = 8.5.dp)
    ) {
        Icon(icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(15.dp))
        Text(
            text = badge,
            color = palette.accent,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(5.dp))
                .background(palette.accentSoft)
                .padding(horizontal = 5.dp, vertical = 1.5.dp)
        )
        Text(
            text = title,
            color = palette.textPrimary,
            fontSize = 12.8.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (trailing.isNotBlank()) {
            Text(
                text = trailing,
                color = palette.textTertiary,
                fontSize = 11.sp
            )
        }
        Icon(OmsIcons.ChevronRight, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(13.dp))
    }
}
