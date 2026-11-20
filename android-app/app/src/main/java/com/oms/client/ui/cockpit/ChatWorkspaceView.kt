package com.oms.client.ui.cockpit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.core.content.FileProvider
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.oms.core.ai.ModelRoleCategory
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oms.client.capability.AlarmExecutor
import com.oms.client.capability.CalendarExecutor
import com.oms.client.capability.NotificationExecutor
import com.oms.client.capability.SpeechAudioPolicy
import com.oms.client.capability.VoiceRecorderHelper
import com.oms.client.cockpit.ChatMessageModel
import com.oms.client.cockpit.CockpitRuntimeCoordinator
import com.oms.client.ui.markdown.IncrementalMarkdownView
import com.oms.core.ai.ProposedAlarmAction
import com.oms.core.ai.ProposedCalendarAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatWorkspaceView(
    coordinator: CockpitRuntimeCoordinator,
    prefillDraft: String,
    onConsumePrefillDraft: () -> Unit,
    onOpenBotanicalCamera: () -> Unit,
    onOpenLiveVoice: (glassCameraMode: Boolean) -> Unit,
    onEscalateToMacAgent: (promptSummary: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages by coordinator.chatRepo.activeMessagesFlow.collectAsState()
    val activeThreadId by coordinator.chatRepo.activeThreadIdFlow.collectAsState()
    val threads by coordinator.chatRepo.threadsFlow.collectAsState()
    val streaming by coordinator.streamingFlow.collectAsState()
    val activeThread = remember(threads, activeThreadId) {
        threads.firstOrNull { it.threadId == activeThreadId }
    }

    var inputText by remember { mutableStateOf("") }
    var deepThinking by remember { mutableStateOf(false) }
    var forceWebSearch by remember { mutableStateOf(false) }
    var creativeCategory by remember { mutableStateOf<ModelRoleCategory?>(null) }
    var attachedImageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var attachedImagePath by remember { mutableStateOf<String?>(null) }
    var isDictating by remember { mutableStateOf(false) }
    var isTranscribing by remember { mutableStateOf(false) }

    LaunchedEffect(prefillDraft) {
        if (prefillDraft.isNotBlank()) {
            inputText = prefillDraft
            onConsumePrefillDraft()
        }
    }

    val listState = rememberLazyListState()
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val total = layoutInfo.totalItemsCount
            if (total == 0) true
            else {
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible >= total - 2
            }
        }
    }
    var autoFollowPinned by remember { mutableStateOf(true) }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && !isAtBottom) {
            autoFollowPinned = false
        } else if (isAtBottom) {
            autoFollowPinned = true
        }
    }

    // Physical-anchor smooth scroll on new message or streaming growth when pinned
    LaunchedEffect(messages.size, streaming.markdownText.length) {
        if (autoFollowPinned) {
            val count = messages.size + (if (streaming.isStreaming && streaming.threadId == activeThreadId) 1 else 0)
            if (count > 0) {
                runCatching { listState.scrollToItem(count - 1) }
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val (bytes, path) = withContext(Dispatchers.IO) {
                        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: error("读取图片失败")
                        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: error("图片解码失败")
                        val bos = ByteArrayOutputStream()
                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, bos)
                        val out = bos.toByteArray()
                        val f = File(File(context.filesDir, "chat_images").apply { mkdirs() }, "img_${System.currentTimeMillis()}.jpg")
                        f.writeBytes(out)
                        out to f.absolutePath
                    }
                    attachedImageBytes = bytes
                    attachedImagePath = path
                }.onFailure {
                    Toast.makeText(context, "附加图片失败：${it.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (activeThread?.incognito == true) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF31102F))
                        .padding(vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🕵️ 无痕对话模式：不写入全文索引、不同步至 Mac、不参与记忆蒸馏", color = Color(0xFFF5D0FE), fontSize = 11.sp)
                }
            }

            // Main Chat Canvas
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty() && !streaming.isStreaming) {
                    EmptyChatWelcomeHero(
                        onQuickPrompt = { prompt, deep, search ->
                            deepThinking = deep
                            forceWebSearch = search
                            coordinator.sendChatTurn(prompt, deepThinking = deep, forceWebSearch = search)
                        },
                        onOpenBotanicalCamera = onOpenBotanicalCamera,
                        onOpenLiveVoice = onOpenLiveVoice
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(items = messages, key = { it.messageId }) { msg ->
                            ChatMessageBubble(
                                msg = msg,
                                coordinator = coordinator,
                                onFollowUpSpecimen = { card ->
                                    inputText = "关于刚才识别的《${card.chineseName}》，我想进一步请教："
                                },
                                onEscalateToMacAgent = {
                                    val recentCtx = messages.takeLast(6)
                                        .joinToString("\n\n") { "[${it.role.uppercase()}]: ${it.content}" }
                                    onEscalateToMacAgent(
                                        "请基于我在手机咫尺讨论的以下方案直接推进实施并验证：\n\n$recentCtx"
                                    )
                                }
                            )
                        }

                        if (streaming.isStreaming && streaming.threadId == activeThreadId) {
                            item(key = "streaming_live_turn") {
                                StreamingAssistantBubble(state = streaming)
                            }
                        }
                    }
                }

                // Floating `↓` Jump-to-Bottom Pill when user scrolled up
                if (!isAtBottom && (messages.isNotEmpty() || streaming.isStreaming)) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 10.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xEE1E293B))
                            .border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(20.dp))
                            .clickable {
                                autoFollowPinned = true
                                scope.launch {
                                    val total = listState.layoutInfo.totalItemsCount
                                    if (total > 0) listState.animateScrollToItem(total - 1)
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text("↓ 回到最新回复", color = Color(0xFF7DD3FC), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Omnibox Bottom Composer
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D1424))
                    .border(0.5.dp, Color(0xFF1E293B))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                // Mode & Attachment Strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ToggleChip(
                        label = "💡 深度思考",
                        active = deepThinking && creativeCategory == null,
                        activeColor = Color(0xFF5B21B6),
                        onClick = {
                            creativeCategory = null
                            deepThinking = !deepThinking
                            coordinator.vibrateTick()
                        }
                    )
                    ToggleChip(
                        label = "🌐 联网核查",
                        active = forceWebSearch && creativeCategory == null,
                        activeColor = Color(0xFF0369A1),
                        onClick = {
                            creativeCategory = null
                            forceWebSearch = !forceWebSearch
                            coordinator.vibrateTick()
                        }
                    )
                    ToggleChip(
                        label = "🎨 生图",
                        active = creativeCategory == ModelRoleCategory.IMAGE_GENERATION,
                        activeColor = Color(0xFFBE185D),
                        onClick = {
                            creativeCategory = if (creativeCategory == ModelRoleCategory.IMAGE_GENERATION) null else ModelRoleCategory.IMAGE_GENERATION
                            coordinator.vibrateTick()
                        }
                    )
                    ToggleChip(
                        label = "🎬 视频",
                        active = creativeCategory == ModelRoleCategory.VIDEO_GENERATION,
                        activeColor = Color(0xFFB45309),
                        onClick = {
                            creativeCategory = if (creativeCategory == ModelRoleCategory.VIDEO_GENERATION) null else ModelRoleCategory.VIDEO_GENERATION
                            coordinator.vibrateTick()
                        }
                    )
                    ToggleChip(
                        label = "🎵 写歌",
                        active = creativeCategory == ModelRoleCategory.MUSIC_GENERATION,
                        activeColor = Color(0xFF0F766E),
                        onClick = {
                            creativeCategory = if (creativeCategory == ModelRoleCategory.MUSIC_GENERATION) null else ModelRoleCategory.MUSIC_GENERATION
                            coordinator.vibrateTick()
                        }
                    )
                    ToggleChip(
                        label = if (attachedImageBytes != null) "🖼️ 已附图 (移除)" else "🖼️ 附图",
                        active = attachedImageBytes != null,
                        activeColor = Color(0xFF047857),
                        onClick = {
                            if (attachedImageBytes != null) {
                                attachedImageBytes = null
                                attachedImagePath = null
                            } else {
                                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        }
                    )
                    ToggleChip(
                        label = "👓 Glass 实时镜头",
                        active = false,
                        activeColor = Color(0xFF059669),
                        onClick = { onOpenLiveVoice(true) }
                    )
                }

                // Input Row: 📷 Camera | Input Field | 🎙️ Dictation | 🎧 Live / ➤ Send
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Left Wing: CameraX Botanical Glass
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF132A26))
                            .border(1.dp, Color(0xFF10B981), CircleShape)
                            .clickable { onOpenBotanicalCamera() }
                    ) {
                        Text("📷", fontSize = 18.sp)
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = {
                            Text(
                                text = when {
                                    isDictating -> "🎙️ 正在录音听写，再点麦克风立即上屏…"
                                    isTranscribing -> "⚡ GPT Live Transcribe 极速转写中…"
                                    else -> "随时提问、聊想法、定日程…"
                                },
                                color = if (isDictating) Color(0xFFFCA5A5) else Color(0xFF64748B),
                                fontSize = 13.5.sp
                            )
                        },
                        maxLines = 5,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFFF8FAFC),
                            unfocusedTextColor = Color(0xFFF8FAFC),
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color(0xFF1E293B),
                            focusedContainerColor = Color(0xFF131C31),
                            unfocusedContainerColor = Color(0xFF131C31)
                        ),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.weight(1f)
                    )

                    // Dictation Mic (`gpt-live-transcribe`)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(if (isDictating) Color(0xFFDC2626) else Color(0xFF1E293B))
                            .clickable(enabled = !isTranscribing) {
                                if (!isDictating) {
                                    val started = VoiceRecorderHelper.startRecording(context)
                                    if (started != null) {
                                        isDictating = true
                                        coordinator.vibrateTick()
                                    } else {
                                        Toast.makeText(context, "请先授予麦克风权限", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    isDictating = false
                                    val recorded = VoiceRecorderHelper.stopRecording()
                                    if (recorded != null) {
                                        isTranscribing = true
                                        scope.launch {
                                            try {
                                                val transcript = coordinator.transcribeVoiceNote(recorded.first)
                                                if (transcript.isNotBlank()) {
                                                    inputText = if (inputText.isBlank()) transcript else "$inputText $transcript"
                                                }
                                            } catch (e: Throwable) {
                                                Toast.makeText(context, "语音转写失败：${e.message}", Toast.LENGTH_SHORT).show()
                                            } finally {
                                                runCatching { recorded.first.delete() }
                                                isTranscribing = false
                                            }
                                        }
                                    }
                                }
                            }
                    ) {
                        Text(if (isDictating) "⏹" else "🎙️", fontSize = 17.sp)
                    }

                    // Right Wing: Stop / Send / 🎧 Live
                    val canSend = inputText.isNotBlank() || attachedImageBytes != null
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    streaming.isStreaming -> Color(0xFFB91C1C)
                                    canSend -> Color(0xFF0284C7)
                                    else -> Color(0xFF312E81)
                                }
                            )
                            .clickable {
                                when {
                                    streaming.isStreaming -> coordinator.cancelActiveTurn()
                                    canSend -> {
                                        val txt = inputText
                                        val img = attachedImageBytes
                                        val imgPath = attachedImagePath
                                        val activeCreative = creativeCategory
                                        inputText = ""
                                        attachedImageBytes = null
                                        attachedImagePath = null
                                        autoFollowPinned = true
                                        coordinator.vibrateTick()
                                        if (activeCreative != null) {
                                            creativeCategory = null
                                            coordinator.sendCreativeTurn(activeCreative, txt)
                                        } else {
                                            coordinator.sendChatTurn(
                                                userText = txt,
                                                imageJpegBytes = img,
                                                imageLocalPath = imgPath,
                                                deepThinking = deepThinking,
                                                forceWebSearch = forceWebSearch
                                            )
                                        }
                                    }
                                    else -> onOpenLiveVoice(false)
                                }
                            }
                    ) {
                        Text(
                            text = when {
                                streaming.isStreaming -> "⏹"
                                canSend -> "↑"
                                else -> "🎧"
                            },
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyChatWelcomeHero(
    onQuickPrompt: (prompt: String, deep: Boolean, search: Boolean) -> Unit,
    onOpenBotanicalCamera: () -> Unit,
    onOpenLiveVoice: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("✦ 青简 · 咫尺 v2.0", color = Color(0xFFF8FAFC), fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "OpenAI GPT-6.2 & Gemini 4 双擎直连 · 个人知识库按需心有灵犀",
            color = Color(0xFF94A3B8),
            fontSize = 12.5.sp
        )
        Spacer(modifier = Modifier.height(22.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            QuickHeroCard(
                icon = "🌿",
                title = "博物之眼 · 拍植物识万物",
                subtitle = "Gemini 4 Argon + 联网权威学名与易混种一眼辨析",
                borderColor = Color(0xFF10B981),
                onClick = onOpenBotanicalCamera
            )
            QuickHeroCard(
                icon = "🎧",
                title = "散步语音对谈 & English Only",
                subtitle = "OpenAI Realtime 2.1 全双工打断 / Google Glass 实时镜头",
                borderColor = Color(0xFF6366F1),
                onClick = { onOpenLiveVoice(false) }
            )
            QuickHeroCard(
                icon = "🧠",
                title = "结合我的知识库梳理近期关注重点",
                subtitle = "自动触发 search_my_context 按需检索，绝不污染日常闲聊",
                borderColor = Color(0xFF38BDF8),
                onClick = {
                    onQuickPrompt("请结合我的知识库与近期工作记忆，帮我梳理当前最值得优先推进的 3 件事。", true, false)
                }
            )
        }
    }
}

@Composable
private fun QuickHeroCard(
    icon: String,
    title: String,
    subtitle: String,
    borderColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF131C31))
            .border(1.dp, borderColor.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 24.sp)
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(title, color = Color(0xFFF1F5F9), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color(0xFF94A3B8), fontSize = 11.5.sp)
        }
    }
}

@Composable
private fun ChatMessageBubble(
    msg: ChatMessageModel,
    coordinator: CockpitRuntimeCoordinator,
    onFollowUpSpecimen: (com.oms.core.ai.FieldGuideSpecimenCard) -> Unit,
    onEscalateToMacAgent: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isUser = msg.role == "user"

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (isUser) {
            Box(
                modifier = Modifier
                    .widthIn(max = 310.dp)
                    .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .background(Color(0xFF0369A1))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(msg.content, color = Color.White, fontSize = 14.5.sp, lineHeight = 21.sp)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF131C31))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (msg.modelUsed.isNotBlank()) {
                    Text(
                        text = "✦ ${msg.modelUsed}",
                        color = Color(0xFF64748B),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (msg.specimenCard != null) {
                    BotanicalSpecimenCardView(
                        card = msg.specimenCard,
                        onSaveVault = {
                            scope.launch {
                                val ok = coordinator.promoteSpecimenToVault(msg.specimenCard, msg.imageLocalPath)
                                Toast.makeText(
                                    context,
                                    if (ok) "🌿 已归档至 Mac 知识库 FieldGuide" else "🌿 已排队，连上 Mac 自动归档",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onFollowUp = { onFollowUpSpecimen(msg.specimenCard) }
                    )
                } else {
                    IncrementalMarkdownView(
                        markdown = msg.content,
                        reasoning = msg.reasoning,
                        sources = msg.sources,
                        isStreaming = false
                    )
                    msg.imageLocalPath?.let { mediaPath ->
                        InlineGeneratedMediaPreview(mediaPath = mediaPath, messageId = msg.messageId)
                    }
                }

                // Inline Native Calendar Cards (with real-time Xiaomi Calendar conflict check!)
                msg.calendarActions.forEach { cal ->
                    InlineCalendarActionCard(action = cal)
                }

                // Inline Native Alarm Cards
                msg.alarmActions.forEach { alarm ->
                    InlineAlarmActionCard(action = alarm)
                }

                // Action Bar: Copy | TTS | Promote to Vault | Escalate to Mac Codex/AGY
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ActionLink("📋 复制") {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("reply", msg.content))
                        Toast.makeText(context, "已复制内容", Toast.LENGTH_SHORT).show()
                    }
                    ActionLink("🔊 朗读") {
                        val tc = com.oms.client.OmsApplication.instance?.transportClient
                        if (tc != null) {
                            tc.speakTextSmart(msg.content, useAlarmStream = false, audioPolicy = SpeechAudioPolicy.MEDIA_COMMUTE)
                        } else {
                            NotificationExecutor.speakText(context, msg.content, useAlarmStream = false, audioPolicy = SpeechAudioPolicy.MEDIA_COMMUTE)
                        }
                    }
                    ActionLink("📚 提炼入库") {
                        scope.launch {
                            val title = msg.content.lineSequence().firstOrNull()?.replace("#", "")?.trim()?.take(36)
                                ?: "随身对话高光笔记"
                            val ok = coordinator.promoteHighlightToVault(title, msg.content)
                            Toast.makeText(
                                context,
                                if (ok) "📚 已存入 Mac 知识库 MobileHighlights" else "📚 已排队，连上 Mac 自动入库",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    ActionLink("🚀 转交 Mac 执行") {
                        onEscalateToMacAgent()
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamingAssistantBubble(state: com.oms.client.cockpit.StreamingTurnState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF131C31))
            .border(1.dp, Color(0xFF0284C7), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (state.statusHint.isNotBlank()) {
            Text(state.statusHint, color = Color(0xFF7DD3FC), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        IncrementalMarkdownView(
            markdown = state.markdownText,
            reasoning = state.reasoningText,
            sources = state.sources,
            isStreaming = true
        )
    }
}

@Composable
private fun InlineCalendarActionCard(action: ProposedCalendarAction) {
    val context = LocalContext.current
    var committed by remember { mutableStateOf(false) }
    val endMs = action.startEpochMs + action.durationMinutes * 60_000L
    val conflictTitle = remember(action.startEpochMs, action.durationMinutes) {
        runCatching {
            val res = CalendarExecutor.listEvents(
                context,
                JSONObject().put("startMs", action.startEpochMs).put("endMs", endMs)
            )
            val arr = res.optJSONArray("events")
            if (arr != null && arr.length() > 0) arr.optJSONObject(0)?.optString("title") else null
        }.getOrNull()
    }
    val timeStr = remember(action.startEpochMs) {
        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(action.startEpochMs))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0F2942))
            .border(1.dp, Color(0xFF0284C7), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("📅 小米系统日历确认卡", color = Color(0xFF7DD3FC), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("${action.title} · $timeStr (${action.durationMinutes} 分钟)", color = Color.White, fontSize = 13.5.sp)
        if (!conflictTitle.isNullOrBlank()) {
            Text("⚠️ 检测到时段已有日程：《$conflictTitle》", color = Color(0xFFFDE047), fontSize = 11.5.sp)
        } else {
            Text("✅ 已核验该时段无日程冲突", color = Color(0xFF6EE7B7), fontSize = 11.5.sp)
        }
        Button(
            enabled = !committed,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
            onClick = {
                runCatching {
                    CalendarExecutor.insertEvent(
                        context,
                        JSONObject()
                            .put("title", action.title)
                            .put("startMs", action.startEpochMs)
                            .put("endMs", endMs)
                            .put("location", action.location)
                            .put("description", action.description)
                    )
                    committed = true
                    Toast.makeText(context, "✅ 已写入小米系统日历", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, "写入日历失败，请检查日历权限", Toast.LENGTH_SHORT).show()
                }
            }
        ) {
            Text(if (committed) "✅ 已写入日历" else "写入小米日历", fontSize = 12.sp)
        }
    }
}

@Composable
private fun InlineAlarmActionCard(action: ProposedAlarmAction) {
    val context = LocalContext.current
    var committed by remember { mutableStateOf(false) }
    val timeLabel = String.format(Locale.US, "%02d:%02d", action.hour, action.minute)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1F1638))
            .border(1.dp, Color(0xFF7C3AED), RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("⏰ 系统闹钟 · $timeLabel", color = Color(0xFFDDD6FE), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(action.label, color = Color(0xFF94A3B8), fontSize = 12.sp)
        }
        Button(
            enabled = !committed,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6D28D9)),
            onClick = {
                val res = AlarmExecutor.setSystemAlarm(
                    context,
                    JSONObject()
                        .put("hour", action.hour)
                        .put("minute", action.minute)
                        .put("label", action.label)
                        .put("days", JSONArray(action.daysOfWeek))
                )
                if (res.optBoolean("ok", false)) {
                    committed = true
                    Toast.makeText(context, "⏰ 已设定 $timeLabel 系统闹钟", Toast.LENGTH_SHORT).show()
                }
            }
        ) {
            Text(if (committed) "已设定" else "一键定闹钟", fontSize = 12.sp)
        }
    }
}

@Composable
private fun ToggleChip(label: String, active: Boolean, activeColor: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) activeColor else Color(0xFF162032))
            .border(1.dp, if (active) Color.White.copy(alpha = 0.5f) else Color(0xFF283548), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(label, color = if (active) Color.White else Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ActionLink(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Color(0xFF94A3B8),
        fontSize = 11.5.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.clickable { onClick() }
    )
}

@Composable
private fun InlineGeneratedMediaPreview(mediaPath: String, messageId: String) {
    val context = LocalContext.current
    val file = remember(mediaPath) { File(mediaPath) }
    if (!file.exists()) return
    val low = file.name.lowercase()
    when {
        low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".webp") -> {
            val bmp = remember(mediaPath) {
                runCatching { BitmapFactory.decodeFile(mediaPath)?.asImageBitmap() }.getOrNull()
            }
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = "Generated image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(230.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            }
        }
        low.endsWith(".mp3") || low.endsWith(".wav") || low.endsWith(".m4a") -> {
            var isPlaying by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = {
                    if (isPlaying) {
                        VoiceRecorderHelper.stopPlayback()
                        isPlaying = false
                    } else {
                        isPlaying = VoiceRecorderHelper.playAudio(context, messageId, Uri.fromFile(file).toString()) {
                            isPlaying = false
                        }
                    }
                }
            ) {
                Text(if (isPlaying) "⏹ 停止播放生成曲目" else "🎵 播放生成的音乐曲目", color = Color(0xFF6EE7B7))
            }
        }
        low.endsWith(".mp4") -> {
            OutlinedButton(
                onClick = {
                    runCatching {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.ota", file)
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW)
                                .setDataAndType(uri, "video/mp4")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        )
                    }.onFailure {
                        Toast.makeText(context, "视频已保存在: ${file.absolutePath}", Toast.LENGTH_LONG).show()
                    }
                }
            ) {
                Text("🎬 播放生成的视频 (${file.name})", color = Color(0xFFFBBF24))
            }
        }
    }
}
