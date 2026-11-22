package com.oms.client.ui.cockpit

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.oms.client.OmsApplication
import com.oms.client.capability.AlarmExecutor
import com.oms.client.capability.CalendarExecutor
import com.oms.client.capability.NotificationExecutor
import com.oms.client.capability.SpeechAudioPolicy
import com.oms.client.capability.VoiceRecorderHelper
import com.oms.client.cockpit.ChatMessageModel
import com.oms.client.cockpit.CockpitRuntimeCoordinator
import com.oms.client.share.AttachmentResolver
import com.oms.client.ui.markdown.IncrementalMarkdownView
import com.oms.client.ui.theme.OmsCircleIconButton
import com.oms.client.ui.theme.OmsFeedbackController
import com.oms.client.ui.theme.OmsIcons
import com.oms.client.ui.theme.OmsMorphIconAction
import com.oms.client.ui.theme.OmsPrimaryButton
import com.oms.client.ui.theme.OmsSecondaryButton
import com.oms.client.ui.theme.OmsSurfaceCard
import com.oms.client.ui.theme.OmsTheme
import com.oms.client.ui.theme.OmsToastKind
import com.oms.client.ui.theme.OmsTogglePill
import com.oms.client.ui.theme.omsBounceClick
import com.oms.client.ui.theme.omsCombinedBounce
import com.oms.core.ai.ModelCatalogRegistry
import com.oms.core.ai.ModelRoleCategory
import com.oms.core.ai.ProposedAlarmAction
import com.oms.core.ai.ProposedCalendarAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class ChatLaunchAction(
    val deepThinking: Boolean? = null,
    val forceWebSearch: Boolean? = null,
    val creativeCategory: ModelRoleCategory? = null,
    val openPlusSheet: Boolean = false,
    val prefillText: String? = null,
    val sendImmediately: Boolean = false,
    val nonce: Long = System.nanoTime()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatWorkspaceView(
    coordinator: CockpitRuntimeCoordinator,
    isWorkbenchHome: Boolean = false,
    showBottomNavBar: Boolean = false,
    onEnterChatThread: () -> Unit = {},
    prefillDraft: String,
    onConsumePrefillDraft: () -> Unit,
    launchAction: ChatLaunchAction? = null,
    onConsumeLaunchAction: () -> Unit = {},
    searchJumpRequest: SearchJumpRequest? = null,
    onConsumeSearchJump: () -> Unit = {},
    onOpenBotanicalCamera: () -> Unit,
    onOpenLiveVoice: (glassCameraMode: Boolean) -> Unit,
    onEscalateToMacAgent: (promptSummary: String) -> Unit,
    onSwitchTab: (CockpitTab) -> Unit = {},
    onOpenVaultDoc: (relPath: String) -> Unit = {},
    onOpenWebAppHub: (url: String?, title: String?) -> Unit = { _, _ -> },
    workbenchContent: (@Composable (shortModelTag: String) -> Unit)? = null
) {
    val context = LocalContext.current
    val palette = OmsTheme.colors
    val scope = rememberCoroutineScope()
    val messages by coordinator.chatRepo.activeMessagesFlow.collectAsState()
    val activeThreadId by coordinator.chatRepo.activeThreadIdFlow.collectAsState()
    val threads by coordinator.chatRepo.threadsFlow.collectAsState()
    val streaming by coordinator.streamingFlow.collectAsState()
    val roleSelections by coordinator.roleSelectionsFlow.collectAsState()
    val modelCatalog by coordinator.modelCatalogFlow.collectAsState()

    val activeThread = remember(threads, activeThreadId) {
        threads.firstOrNull { it.threadId == activeThreadId }
    }

    var inputText by remember { mutableStateOf("") }
    var deepThinking by remember { mutableStateOf(false) }
    var forceWebSearch by remember { mutableStateOf(false) }
    var creativeCategory by remember { mutableStateOf<ModelRoleCategory?>(null) }
    var attachedImageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var attachedImagePath by remember { mutableStateOf<String?>(null) }
    var attachedPreviewBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    var isDictating by remember { mutableStateOf(false) }
    var isTranscribing by remember { mutableStateOf(false) }
    var dictationSec by remember { mutableStateOf(0) }
    val waveformSamples = remember { mutableStateListOf<Float>().apply { repeat(20) { add(0.16f) } } }

    var showPlusSheet by remember { mutableStateOf(false) }
    var showModelQuickMenu by remember { mutableStateOf(false) }
    var fullScreenPreviewPath by remember { mutableStateOf<String?>(null) }
    var highlightedMessageId by remember { mutableStateOf("") }
    var highlightedKeyword by remember { mutableStateOf("") }

    LaunchedEffect(prefillDraft) {
        if (prefillDraft.isNotBlank()) {
            inputText = prefillDraft
            onConsumePrefillDraft()
        }
    }

    LaunchedEffect(launchAction?.nonce) {
        val act = launchAction ?: return@LaunchedEffect
        onConsumeLaunchAction()
        if (act.openPlusSheet) {
            showPlusSheet = true
            return@LaunchedEffect
        }
        act.deepThinking?.let { deepThinking = it }
        act.forceWebSearch?.let { forceWebSearch = it }
        creativeCategory = act.creativeCategory
        if (!act.prefillText.isNullOrBlank()) {
            if (act.sendImmediately) {
                if (isWorkbenchHome && messages.isNotEmpty()) {
                    coordinator.chatRepo.createNewThread(incognito = false)
                }
                onEnterChatThread()
                coordinator.vibrateTick()
                coordinator.sendChatTurn(
                    userText = act.prefillText.trim(),
                    deepThinking = act.deepThinking ?: deepThinking,
                    forceWebSearch = act.forceWebSearch ?: forceWebSearch
                )
            } else {
                inputText = act.prefillText
                if (isWorkbenchHome) onEnterChatThread()
            }
        } else if (act.creativeCategory != null || act.deepThinking == true || act.forceWebSearch == true) {
            if (isWorkbenchHome && messages.isNotEmpty()) {
                coordinator.chatRepo.createNewThread(incognito = false)
            }
            onEnterChatThread()
        }
    }

    // Live 60ms Audio Amplitude Sampler for In-Place Waveform Dictation
    LaunchedEffect(isDictating) {
        if (!isDictating) return@LaunchedEffect
        val startedMs = System.currentTimeMillis()
        while (isDictating && VoiceRecorderHelper.isRecording()) {
            dictationSec = ((System.currentTimeMillis() - startedMs) / 1000L).toInt()
            val rawAmp = VoiceRecorderHelper.getMaxAmplitude()
            val normalized = (rawAmp / 16000f).coerceIn(0.12f, 1.0f)
            if (waveformSamples.size >= 20) waveformSamples.removeAt(0)
            waveformSamples.add(normalized)
            delay(65L)
        }
    }

    val listState = rememberLazyListState()
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val total = layoutInfo.totalItemsCount
            if (total == 0) true
            else {
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
                if (lastVisible == null) true
                else {
                    val viewportEnd = layoutInfo.viewportEndOffset
                    lastVisible.index >= total - 1 &&
                        (lastVisible.offset + lastVisible.size - viewportEnd) <= 140
                }
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

    // Smooth scroll on new turn; gentle throttled follow during streaming without overriding active search jump!
    LaunchedEffect(messages.size, streaming.isStreaming) {
        if (autoFollowPinned && searchJumpRequest == null && highlightedMessageId.isEmpty()) {
            val count = messages.size + (if (streaming.isStreaming && streaming.threadId == activeThreadId) 1 else 0)
            if (count > 0) {
                runCatching { listState.animateScrollToItem(count - 1) }
            }
        }
    }

    // Exact Message Auto-Scroll & Highlight on FTS Search Hit Selection
    LaunchedEffect(searchJumpRequest?.nonce, activeThreadId, messages) {
        val req = searchJumpRequest ?: return@LaunchedEffect
        if (activeThreadId != req.threadId || messages.isEmpty()) return@LaunchedEffect
        val targetIdx = messages.indexOfFirst { it.messageId == req.messageId }
        if (targetIdx >= 0) {
            autoFollowPinned = false
            highlightedMessageId = req.messageId
            highlightedKeyword = req.keyword
            onConsumeSearchJump()
            runCatching {
                listState.scrollToItem(targetIdx, scrollOffset = -48)
            }
            delay(5000L)
            if (highlightedMessageId == req.messageId) {
                highlightedMessageId = ""
                highlightedKeyword = ""
            }
        }
    }

    val streamBucket = streaming.markdownText.length / 96
    LaunchedEffect(streamBucket) {
        if (autoFollowPinned && streaming.isStreaming && streaming.threadId == activeThreadId && streamBucket > 0) {
            val total = listState.layoutInfo.totalItemsCount
            if (total > 0) {
                runCatching { listState.scrollToItem(total - 1, scrollOffset = Int.MAX_VALUE / 4) }
            }
        }
    }

    fun attachUriAsChatImage(uri: Uri) {
        scope.launch {
            runCatching {
                val (bytes, path, previewBmp) = withContext(Dispatchers.IO) {
                    val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("读取图片失败")
                    val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: error("图片解码失败")
                    val maxDim = 1600
                    val longest = maxOf(bmp.width, bmp.height)
                    val scaled = if (longest > maxDim) {
                        val r = maxDim.toFloat() / longest.toFloat()
                        android.graphics.Bitmap.createScaledBitmap(
                            bmp,
                            (bmp.width * r).toInt().coerceAtLeast(1),
                            (bmp.height * r).toInt().coerceAtLeast(1),
                            true
                        )
                    } else bmp
                    val bos = ByteArrayOutputStream()
                    scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 86, bos)
                    val out = bos.toByteArray()
                    val f = File(File(context.filesDir, "chat_images").apply { mkdirs() }, "img_${System.currentTimeMillis()}.jpg")
                    f.writeBytes(out)
                    val thumb = android.graphics.Bitmap.createScaledBitmap(scaled, 180, 180, true).asImageBitmap()
                    Triple(out, f.absolutePath, thumb)
                }
                attachedImageBytes = bytes
                attachedImagePath = path
                attachedPreviewBitmap = previewBmp
            }.onFailure {
                OmsFeedbackController.show("附加图片失败：${it.message}", OmsToastKind.WARNING)
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) attachUriAsChatImage(uri)
    }

    // Direct Drop-to-Mac File/Photo Launchers (accessible directly from Chat `+` Sheet!)
    val macDropPhotoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9)
    ) { uris ->
        if (uris.isNotEmpty()) {
            val metas = uris.map { AttachmentResolver.describeUri(context, it) }
            val first = metas.first()
            val title = if (metas.size == 1) first.safeFileName else "${first.safeFileName} 等 ${metas.size} 张图片"
            OmsApplication.instance?.transportClient?.submitItemToMacOrQueue(
                intentTag = "default_inbox",
                itemType = AttachmentResolver.inferItemType(metas.map { it.mimeType }),
                title = title,
                textContent = inputText.trim().ifEmpty { null },
                voiceTranscript = null,
                fileUri = uris.first(),
                additionalFileUris = uris.drop(1)
            )
            inputText = ""
            OmsFeedbackController.show("已投递 ${uris.size} 张图片至 Mac 收件箱", OmsToastKind.SUCCESS)
        }
    }

    val macDropDocPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val metas = uris.map { AttachmentResolver.describeUri(context, it) }
            val first = metas.first()
            val title = if (metas.size == 1) first.safeFileName else "${first.safeFileName} 等 ${metas.size} 个文件"
            OmsApplication.instance?.transportClient?.submitItemToMacOrQueue(
                intentTag = "default_inbox",
                itemType = AttachmentResolver.inferItemType(metas.map { it.mimeType }),
                title = title,
                textContent = inputText.trim().ifEmpty { null },
                voiceTranscript = null,
                fileUri = uris.first(),
                additionalFileUris = uris.drop(1)
            )
            inputText = ""
            OmsFeedbackController.show("已投递 ${uris.size} 个文档至 Mac 收件箱", OmsToastKind.SUCCESS)
        }
    }

    val micPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (VoiceRecorderHelper.startRecording(context) != null) {
                dictationSec = 0
                isDictating = true
                coordinator.vibrateTick()
            }
        } else {
            OmsFeedbackController.show("需要麦克风权限以使用语音听写", OmsToastKind.WARNING)
        }
    }

    fun dispatchCurrentTurn(overrideText: String? = null) {
        val txt = (overrideText ?: inputText).trim()
        val img = attachedImageBytes
        val imgPath = attachedImagePath
        if (txt.isEmpty() && img == null) return
        val activeCreative = creativeCategory
        inputText = ""
        attachedImageBytes = null
        attachedImagePath = null
        attachedPreviewBitmap = null
        autoFollowPinned = true
        coordinator.vibrateTick()
        if (isWorkbenchHome) {
            if (messages.isNotEmpty()) {
                coordinator.chatRepo.createNewThread(incognito = false)
            }
            onEnterChatThread()
        }
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

    fun finishDictation(sendImmediately: Boolean) {
        isDictating = false
        val recorded = VoiceRecorderHelper.stopRecording() ?: return
        isTranscribing = true
        scope.launch {
            try {
                val transcript = coordinator.transcribeVoiceNote(recorded.first)
                if (transcript.isNotBlank()) {
                    val merged = if (inputText.isBlank()) transcript else "$inputText $transcript"
                    if (sendImmediately) {
                        dispatchCurrentTurn(overrideText = merged)
                    } else {
                        inputText = merged
                    }
                }
            } catch (e: Throwable) {
                OmsFeedbackController.show("转写未完成：${e.message}", OmsToastKind.WARNING)
            } finally {
                runCatching { recorded.first.delete() }
                isTranscribing = false
            }
        }
    }

    val activeRoleCategory = if (deepThinking) ModelRoleCategory.DEEP_REASONING else ModelRoleCategory.CHAT_PRIMARY
    val currentModelKey = roleSelections[activeRoleCategory] ?: activeRoleCategory.defaultSelection
    val shortModelTag = remember(currentModelKey) { prettifyModelShortName(currentModelKey) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Subtle Incognito Banner (only inside active Chat Thread)
            AnimatedVisibility(visible = !isWorkbenchHome && activeThread?.incognito == true) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.subtleSurface)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = OmsIcons.Incognito,
                        contentDescription = null,
                        tint = palette.textSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "无痕对话 · 不落全文索引、不同步 Mac、不参与记忆蒸馏",
                        color = palette.textSecondary,
                        fontSize = 11.5.sp
                    )
                }
            }

            // Main Stage: Workbench Home OR Focused Chat Thread
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (isWorkbenchHome && workbenchContent != null) {
                    workbenchContent(shortModelTag)
                } else if (messages.isEmpty() && !streaming.isStreaming) {
                    if (workbenchContent != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 28.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(palette.accentSoft)
                            ) {
                                Icon(
                                    imageVector = OmsIcons.Sparkles,
                                    contentDescription = null,
                                    tint = palette.accent,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = if (activeThread?.incognito == true) "开启无痕对话" else "随身秘书已就绪",
                                color = palette.textPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "$shortModelTag · 支持深度思考、实时联网、手机日程闹钟与多模态生图",
                                color = palette.textTertiary,
                                fontSize = 12.5.sp,
                                lineHeight = 19.sp
                            )
                        }
                    } else {
                        val secretaryBrief by coordinator.secretaryBriefFlow.collectAsState()
                        val isRefreshingBrief by coordinator.isRefreshingBriefFlow.collectAsState()
                        EmptyChatWelcomeHero(
                            shortModelName = shortModelTag,
                            brief = secretaryBrief,
                            isRefreshingBrief = isRefreshingBrief,
                            onRefreshBrief = { coordinator.refreshSecretaryBrief() },
                            onAddCommitment = { title, due -> coordinator.addCommitment(title, due) },
                            onToggleCommitment = { id -> coordinator.toggleCommitment(id) },
                            onSaveProfile = { items -> coordinator.saveSecretaryProfile(items) },
                            onDismissNudge = { id -> coordinator.dismissSecretaryNudge(id) },
                            onTriggerNudge = { nudge ->
                                when (nudge.actionType) {
                                    "open_chisu" -> onSwitchTab(CockpitTab.CHISU)
                                    "open_missions" -> onSwitchTab(CockpitTab.MISSION_HUB)
                                    "open_vault_doc" -> onOpenVaultDoc(nudge.actionPayload)
                                    else -> {
                                        val p = nudge.actionPayload.ifBlank { nudge.title }
                                        coordinator.sendChatTurn(p, deepThinking = false, forceWebSearch = false)
                                    }
                                }
                            },
                            onQuickPrompt = { prompt, deep, search ->
                                deepThinking = deep
                                forceWebSearch = search
                                coordinator.sendChatTurn(prompt, deepThinking = deep, forceWebSearch = search)
                            },
                            onOpenBotanicalCamera = onOpenBotanicalCamera,
                            onOpenLiveVoice = { onOpenLiveVoice(false) },
                            onOpenMacDrop = { showPlusSheet = true },
                            onOpenWebAppHub = { onOpenWebAppHub(null, null) }
                        )
                    }
                } else {
                    val lastAssistantId = remember(messages) {
                        messages.lastOrNull { it.role == "assistant" }?.messageId
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 18.dp,
                            end = 18.dp,
                            top = 14.dp,
                            bottom = 26.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(22.dp)
                    ) {
                        itemsIndexed(items = messages, key = { _, it -> it.messageId }) { _, msg ->
                            ChatMessageTurn(
                                msg = msg,
                                isLatestAssistant = msg.messageId == lastAssistantId && !streaming.isStreaming,
                                highlightKeyword = if (msg.messageId == highlightedMessageId) highlightedKeyword else "",
                                coordinator = coordinator,
                                onEditUserPrompt = { oldText -> inputText = oldText },
                                onPreviewImage = { path -> fullScreenPreviewPath = path },
                                onFollowUpSpecimen = { card ->
                                    inputText = "关于刚才识别的《${card.chineseName}》，我想进一步请教："
                                },
                                onRegenerate = {
                                    coordinator.regenerateLastAssistantTurn(
                                        deepThinking = deepThinking,
                                        forceWebSearch = forceWebSearch
                                    )
                                },
                                onEscalateToMacAgent = {
                                    val recentCtx = messages.takeLast(6)
                                        .joinToString("\n\n") { "[${it.role.uppercase()}]: ${it.content}" }
                                    onEscalateToMacAgent(
                                        "请基于我在手机咫尺讨论的以下方案直接推进实施并验证：\n\n$recentCtx"
                                    )
                                },
                                onOpenWebAppHub = onOpenWebAppHub
                            )
                        }

                        if (streaming.isStreaming && streaming.threadId == activeThreadId) {
                            item(key = "streaming_live_turn") {
                                StreamingAssistantTurn(state = streaming)
                            }
                        }
                    }
                }

                // Floating Circular Jump-to-Bottom Button (ChatGPT / Gemini style)
                androidx.compose.animation.AnimatedVisibility(
                    visible = !isWorkbenchHome && !isAtBottom && (messages.isNotEmpty() || streaming.isStreaming),
                    enter = scaleIn(spring(stiffness = 550f)) + fadeIn(tween(140)),
                    exit = scaleOut(tween(120)) + fadeOut(tween(120)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .shadow(elevation = 6.dp, shape = CircleShape, clip = false)
                            .omsBounceClick(scaleDown = 0.88f, shape = CircleShape) {
                                autoFollowPinned = true
                                scope.launch {
                                    val total = listState.layoutInfo.totalItemsCount
                                    if (total > 0) listState.animateScrollToItem(total - 1)
                                }
                            }
                            .background(palette.composer, CircleShape)
                            .border(0.7.dp, palette.strongBorder, CircleShape)
                    ) {
                        Icon(
                            imageVector = OmsIcons.ArrowDown,
                            contentDescription = "回到最新回复",
                            tint = palette.textPrimary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }

            // =====================================================================
            // Sculptural Floating Pill Omnibox Composer (120Hz IME-Synchronized)
            // =====================================================================
            val pillShape = RoundedCornerShape(26.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (!showBottomNavBar) Modifier.navigationBarsPadding() else Modifier)
                    .imePadding()
                    .padding(
                        start = 12.dp,
                        end = 12.dp,
                        top = 6.dp,
                        bottom = if (showBottomNavBar) 8.dp else 8.dp
                    )
                    .shadow(
                        elevation = if (palette.isDark) 3.dp else 7.dp,
                        shape = pillShape,
                        clip = false
                    )
                    .clip(pillShape)
                    .background(palette.composer, pillShape)
                    .border(0.65.dp, palette.strongBorder, pillShape)
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                // Conditional Attachment Thumbnail & Creative Mode Strip
                AnimatedVisibility(
                    visible = attachedPreviewBitmap != null || creativeCategory != null,
                    enter = expandVertically(tween(160)) + fadeIn(tween(140)),
                    exit = shrinkVertically(tween(140)) + fadeOut(tween(110))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        attachedPreviewBitmap?.let { bmp ->
                            Box(modifier = Modifier.size(58.dp)) {
                                Image(
                                    bitmap = bmp,
                                    contentDescription = "已附加图片",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(0.6.dp, palette.hairline, RoundedCornerShape(12.dp))
                                        .clickable { attachedImagePath?.let { fullScreenPreviewPath = it } }
                                )
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(3.dp)
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xCC000000))
                                        .clickable {
                                            attachedImageBytes = null
                                            attachedImagePath = null
                                            attachedPreviewBitmap = null
                                        }
                                ) {
                                    Icon(
                                        imageVector = OmsIcons.Close,
                                        contentDescription = "移除图片",
                                        tint = Color.White,
                                        modifier = Modifier.size(11.dp)
                                    )
                                }
                            }
                        }

                        creativeCategory?.let { cat ->
                            val (catIcon, catLabel) = when (cat) {
                                ModelRoleCategory.IMAGE_GENERATION -> OmsIcons.Palette to "视觉生图模式"
                                ModelRoleCategory.VIDEO_GENERATION -> OmsIcons.Video to "视频生成模式"
                                ModelRoleCategory.MUSIC_GENERATION -> OmsIcons.Music to "歌曲创作模式"
                                else -> OmsIcons.Sparkles to cat.title
                            }
                            OmsTogglePill(
                                label = "$catLabel  ×",
                                active = true,
                                icon = catIcon,
                                onClick = { creativeCategory = null }
                            )
                        }
                    }
                }

                // Borderless Multi-Line Editorial Text Field (hidden during full waveform dictation)
                if (!isDictating) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        if (inputText.isEmpty()) {
                            Text(
                                text = when {
                                    isTranscribing -> "正在极速转写语音…"
                                    creativeCategory == ModelRoleCategory.IMAGE_GENERATION -> "描述你想生成的画面构图与光影风格…"
                                    creativeCategory == ModelRoleCategory.VIDEO_GENERATION -> "描述你想生成的镜头运动与动态场景…"
                                    creativeCategory == ModelRoleCategory.MUSIC_GENERATION -> "描述曲风、情绪、乐器或歌词主题…"
                                    else -> "发消息、推演想法，或安排日程…"
                                },
                                color = palette.textTertiary,
                                fontSize = 15.5.sp,
                                lineHeight = 22.sp
                            )
                        }
                        BasicTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            textStyle = TextStyle(
                                color = palette.textPrimary,
                                fontSize = 15.5.sp,
                                lineHeight = 22.sp
                            ),
                            cursorBrush = SolidColor(palette.accent),
                            maxLines = 6,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // Bottom Tool & Action Row (Morphs smoothly into Live Waveform during dictation!)
                AnimatedContent(
                    targetState = isDictating,
                    transitionSpec = {
                        fadeIn(tween(160)) togetherWith fadeOut(tween(120))
                    },
                    label = "composerBottomMorph"
                ) { dictating ->
                    if (dictating) {
                        // In-Place 60fps Live Audio Waveform Dictation Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OmsCircleIconButton(
                                icon = OmsIcons.Close,
                                contentDescription = "取消录音",
                                size = 34.dp,
                                iconSize = 16.dp,
                                containerColor = palette.subtleSurface,
                                contentColor = palette.textSecondary,
                                onClick = {
                                    isDictating = false
                                    VoiceRecorderHelper.cancelRecording()
                                    coordinator.vibrateTick()
                                }
                            )

                            // Live Waveform Equalizer
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(28.dp)
                                    .padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally)
                            ) {
                                waveformSamples.forEach { sample ->
                                    val barFrac by animateFloatAsState(
                                        targetValue = sample,
                                        animationSpec = tween(70),
                                        label = "waveBar"
                                    )
                                    Box(
                                        modifier = Modifier
                                            .width(3.dp)
                                            .fillMaxHeight(barFrac.coerceIn(0.14f, 1f))
                                            .clip(CircleShape)
                                            .background(palette.accent)
                                    )
                                }
                            }

                            Text(
                                text = String.format(Locale.US, "%d:%02d", dictationSec / 60, dictationSec % 60),
                                color = palette.textSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )

                            // Confirm -> Transcribe into Input
                            OmsCircleIconButton(
                                icon = OmsIcons.Check,
                                contentDescription = "转写上屏",
                                size = 34.dp,
                                iconSize = 17.dp,
                                containerColor = palette.subtleSurface,
                                contentColor = palette.textPrimary,
                                onClick = { finishDictation(sendImmediately = false) }
                            )

                            // Confirm & Send Immediately
                            OmsCircleIconButton(
                                icon = OmsIcons.ArrowUp,
                                contentDescription = "转写并直接发送",
                                size = 36.dp,
                                iconSize = 18.dp,
                                containerColor = palette.sendButtonBg,
                                contentColor = palette.sendButtonFg,
                                onClick = { finishDictation(sendImmediately = true) }
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Left Tool Cluster: (+) | 思考 | 联网 | Model Selector
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .horizontalScroll(rememberScrollState())
                            ) {
                                OmsCircleIconButton(
                                    icon = OmsIcons.Plus,
                                    contentDescription = "添加附件与多模态能力",
                                    size = 33.dp,
                                    iconSize = 17.5.dp,
                                    containerColor = palette.subtleSurface,
                                    borderColor = palette.hairline,
                                    contentColor = palette.textPrimary,
                                    onClick = { showPlusSheet = true }
                                )

                                OmsTogglePill(
                                    label = "思考",
                                    active = deepThinking && creativeCategory == null,
                                    icon = OmsIcons.Lightbulb,
                                    onClick = {
                                        creativeCategory = null
                                        deepThinking = !deepThinking
                                    }
                                )

                                OmsTogglePill(
                                    label = "联网",
                                    active = forceWebSearch && creativeCategory == null,
                                    icon = OmsIcons.Globe,
                                    onClick = {
                                        creativeCategory = null
                                        forceWebSearch = !forceWebSearch
                                    }
                                )

                                // Inline Quick Model Selector Chip
                                Box {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                        modifier = Modifier
                                            .height(31.dp)
                                            .omsBounceClick(scaleDown = 0.94f, shape = RoundedCornerShape(15.dp)) {
                                                showModelQuickMenu = true
                                            }
                                            .padding(horizontal = 7.dp)
                                    ) {
                                        Text(
                                            text = shortModelTag,
                                            color = palette.textTertiary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1
                                        )
                                        Icon(
                                            imageVector = OmsIcons.ChevronDown,
                                            contentDescription = "切换模型",
                                            tint = palette.textTertiary,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = showModelQuickMenu,
                                        onDismissRequest = { showModelQuickMenu = false },
                                        modifier = Modifier
                                            .background(palette.composer)
                                            .width(265.dp)
                                    ) {
                                        val options = remember(modelCatalog, activeRoleCategory) {
                                            ModelCatalogRegistry.optionsForCategory(activeRoleCategory, modelCatalog)
                                        }
                                        options.forEach { opt ->
                                            val selected = opt.selectionKey == currentModelKey
                                            DropdownMenuItem(
                                                text = {
                                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                        ) {
                                                            Text(
                                                                text = opt.label,
                                                                color = if (selected) palette.accent else palette.textPrimary,
                                                                fontSize = 13.sp,
                                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                                                            )
                                                            if (selected) {
                                                                Icon(
                                                                    imageVector = OmsIcons.Check,
                                                                    contentDescription = null,
                                                                    tint = palette.accent,
                                                                    modifier = Modifier.size(13.dp)
                                                                )
                                                            }
                                                        }
                                                        Text(
                                                            text = opt.description,
                                                            color = palette.textTertiary,
                                                            fontSize = 11.sp,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                },
                                                onClick = {
                                                    showModelQuickMenu = false
                                                    coordinator.setModelRoleSelection(activeRoleCategory, opt.selectionKey)
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            // Right Action Cluster: Mic + Morphing Send / Stop / Live Voice Orb
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (isTranscribing) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.size(35.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            color = palette.accent,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                } else {
                                    OmsCircleIconButton(
                                        icon = OmsIcons.Mic,
                                        contentDescription = "语音听写",
                                        size = 35.dp,
                                        iconSize = 18.dp,
                                        contentColor = palette.textSecondary,
                                        onClick = {
                                            val hasPerm = ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.RECORD_AUDIO
                                            ) == PackageManager.PERMISSION_GRANTED
                                            if (hasPerm) {
                                                if (VoiceRecorderHelper.startRecording(context) != null) {
                                                    dictationSec = 0
                                                    isDictating = true
                                                    coordinator.vibrateTick()
                                                }
                                            } else {
                                                micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            }
                                        }
                                    )
                                }

                                val canSend = inputText.isNotBlank() || attachedImageBytes != null
                                val orbMode = when {
                                    streaming.isStreaming -> "STOP"
                                    canSend -> "SEND"
                                    else -> "LIVE"
                                }

                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(37.dp)
                                        .omsBounceClick(scaleDown = 0.88f, shape = CircleShape) {
                                            when (orbMode) {
                                                "STOP" -> coordinator.cancelActiveTurn()
                                                "SEND" -> dispatchCurrentTurn()
                                                else -> onOpenLiveVoice(false)
                                            }
                                        }
                                        .background(palette.sendButtonBg, CircleShape)
                                ) {
                                    AnimatedContent(
                                        targetState = orbMode,
                                        transitionSpec = {
                                            (scaleIn(spring(stiffness = 620f)) + fadeIn(tween(130))) togetherWith
                                                (scaleOut(tween(100)) + fadeOut(tween(100)))
                                        },
                                        label = "primaryOrbMorph"
                                    ) { mode ->
                                        val icon = when (mode) {
                                            "STOP" -> OmsIcons.StopSquare
                                            "SEND" -> OmsIcons.ArrowUp
                                            else -> OmsIcons.Waveform
                                        }
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = mode,
                                            tint = palette.sendButtonFg,
                                            modifier = Modifier.size(18.5.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // Multimodal & Mac Drop Half-Screen Action Sheet (`+` Button)
    // =========================================================================
    if (showPlusSheet) {
        ModalBottomSheet(
            onDismissRequest = { showPlusSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = palette.composer,
            contentColor = palette.textPrimary,
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
        ) {
            MultimodalPlusSheetContent(
                onAttachPhotoUri = { uri ->
                    showPlusSheet = false
                    attachUriAsChatImage(uri)
                },
                onPickAlbumForChat = {
                    showPlusSheet = false
                    imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onOpenBotanicalCamera = {
                    showPlusSheet = false
                    onOpenBotanicalCamera()
                },
                onOpenGlassLive = {
                    showPlusSheet = false
                    onOpenLiveVoice(true)
                },
                onDropPhotosToMac = {
                    showPlusSheet = false
                    macDropPhotoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                },
                onDropDocsToMac = {
                    showPlusSheet = false
                    macDropDocPicker.launch(arrayOf("*/*"))
                },
                onSelectCreative = { cat ->
                    showPlusSheet = false
                    deepThinking = false
                    forceWebSearch = false
                    creativeCategory = cat
                }
            )
        }
    }

    // Full-Screen Pinch-to-Zoom Image Lightbox
    fullScreenPreviewPath?.let { path ->
        OmsZoomableImageLightbox(
            imagePath = path,
            onDismiss = { fullScreenPreviewPath = null }
        )
    }
}

@Composable
private fun EmptyChatWelcomeHero(
    shortModelName: String,
    brief: com.oms.client.cockpit.SecretaryBriefUi?,
    isRefreshingBrief: Boolean,
    onRefreshBrief: () -> Unit,
    onAddCommitment: (title: String, dueHint: String) -> Unit,
    onToggleCommitment: (id: String) -> Unit,
    onSaveProfile: (items: List<String>) -> Unit,
    onDismissNudge: (id: String) -> Unit,
    onTriggerNudge: (com.oms.client.cockpit.SecretaryNudgeUi) -> Unit,
    onQuickPrompt: (prompt: String, deep: Boolean, search: Boolean) -> Unit,
    onOpenBotanicalCamera: () -> Unit,
    onOpenLiveVoice: () -> Unit,
    onOpenMacDrop: () -> Unit,
    onOpenWebAppHub: () -> Unit
) {
    val palette = OmsTheme.colors
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
    val subtitleLine = brief?.headline?.ifBlank { "$shortModelName · 随身秘书持续记忆中" }
        ?: "$shortModelName · 随身秘书与知识库按需共鸣"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Executive Header: Greeting + Transparent Living Memory + Refresh
        Row(
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
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
                Spacer(modifier = Modifier.height(3.dp))
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
                    label = "记忆${if ((brief?.profileItems?.size ?: 0) > 0) " (${brief?.profileItems?.size})" else ""}",
                    icon = OmsIcons.Bookmark,
                    height = 32.dp,
                    onClick = {
                        memoryEditorText = brief?.profileItems?.joinToString("\n") ?: ""
                        showMemoryDialog = true
                    }
                )
                OmsCircleIconButton(
                    icon = OmsIcons.Refresh,
                    contentDescription = "刷新今日简报",
                    size = 32.dp,
                    iconSize = 15.dp,
                    containerColor = palette.subtleSurface,
                    contentColor = if (isRefreshingBrief) palette.accent else palette.textSecondary,
                    borderColor = palette.hairline,
                    onClick = onRefreshBrief
                )
            }
        }

        // Active Open Loops (Commitments) Card
        OmsSurfaceCard(
            cornerRadius = 18.dp,
            contentPadding = PaddingValues(14.dp),
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()
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

        // Proactive Chief-of-Staff Nudges (Calm, 1-Tap Actionable, Dismissible)
        val nudges = brief?.nudges.orEmpty()
        if (nudges.isNotEmpty()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()
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

        // Compact 2x2 Quick Executive Actions
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                WelcomeCompactActionTile(
                    icon = OmsIcons.Globe,
                    title = "应用舱",
                    subtitle = "内置网页与AI伴读",
                    onClick = onOpenWebAppHub,
                    modifier = Modifier.weight(1f)
                )
                WelcomeCompactActionTile(
                    icon = OmsIcons.Waveform,
                    title = "散步语音",
                    subtitle = "全双工随行对谈",
                    onClick = onOpenLiveVoice,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                WelcomeCompactActionTile(
                    icon = OmsIcons.Leaf,
                    title = "博物镜头",
                    subtitle = "实景识物入图鉴",
                    onClick = onOpenBotanicalCamera,
                    modifier = Modifier.weight(1f)
                )
                WelcomeCompactActionTile(
                    icon = OmsIcons.Send,
                    title = "快传至 Mac",
                    subtitle = "图文文档加密直达",
                    onClick = onOpenMacDrop,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    if (showAddLoopDialog) {
        androidx.compose.material3.AlertDialog(
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
                androidx.compose.material3.TextButton(onClick = {
                    if (newLoopTitle.isNotBlank()) {
                        onAddCommitment(newLoopTitle.trim(), newLoopDue.trim())
                        showAddLoopDialog = false
                    }
                }) { Text("记下", color = palette.accent, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showAddLoopDialog = false }) {
                    Text("取消", color = palette.textTertiary)
                }
            }
        )
    }

    if (showMemoryDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showMemoryDialog = false },
            containerColor = palette.composer,
            title = { Text("随身秘书长期记忆（每行一条）", color = palette.textPrimary, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold) },
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
                androidx.compose.material3.TextButton(onClick = {
                    val lines = memoryEditorText.split('\n').map { it.trim() }.filter { it.isNotBlank() }
                    onSaveProfile(lines)
                    showMemoryDialog = false
                    OmsFeedbackController.show("已更新秘书长期记忆", OmsToastKind.SUCCESS)
                }) { Text("保存", color = palette.accent, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showMemoryDialog = false }) {
                    Text("取消", color = palette.textTertiary)
                }
            }
        )
    }
}

@Composable
private fun WelcomeCompactActionTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = OmsTheme.colors
    val shape = RoundedCornerShape(15.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        modifier = modifier
            .omsBounceClick(scaleDown = 0.965f, shape = shape, onClick = onClick)
            .background(palette.cardSurface, shape)
            .border(0.6.dp, palette.hairline, shape)
            .padding(horizontal = 11.dp, vertical = 10.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(palette.subtleSurface)
        ) {
            Icon(icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = palette.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(subtitle, color = palette.textTertiary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun WelcomeSuggestionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val palette = OmsTheme.colors
    val shape = RoundedCornerShape(16.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .omsBounceClick(scaleDown = 0.975f, shape = shape, onClick = onClick)
            .background(palette.cardSurface, shape)
            .border(0.6.dp, palette.hairline, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(palette.subtleSurface)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = palette.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                color = palette.textTertiary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = OmsIcons.ChevronRight,
            contentDescription = null,
            tint = palette.textTertiary,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun ChatMessageTurn(
    msg: ChatMessageModel,
    isLatestAssistant: Boolean,
    highlightKeyword: String = "",
    coordinator: CockpitRuntimeCoordinator,
    onEditUserPrompt: (String) -> Unit,
    onPreviewImage: (String) -> Unit,
    onFollowUpSpecimen: (com.oms.core.ai.FieldGuideSpecimenCard) -> Unit,
    onRegenerate: () -> Unit,
    onEscalateToMacAgent: () -> Unit,
    onOpenWebAppHub: (url: String?, title: String?) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val palette = OmsTheme.colors
    val scope = rememberCoroutineScope()
    val isUser = msg.role == "user"
    val isSearchTarget = highlightKeyword.isNotBlank()
    var showUserMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSearchTarget) {
                    Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(palette.accentSoft.copy(alpha = 0.45f))
                        .border(1.2.dp, palette.accent, RoundedCornerShape(18.dp))
                        .padding(10.dp)
                } else Modifier
            ),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (isSearchTarget) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.accentSoft)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Icon(OmsIcons.Search, contentDescription = null, tint = palette.accent, modifier = Modifier.size(12.dp))
                Text(
                    text = "检索命中 ·「$highlightKeyword」",
                    color = palette.accent,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        if (isUser) {
            // User Image Attachment Thumbnail (previously missing!)
            msg.imageLocalPath?.let { path ->
                val userBmp by produceState<ImageBitmap?>(initialValue = null, key1 = path) {
                    value = withContext(Dispatchers.IO) {
                        runCatching {
                            val f = File(path)
                            if (f.exists()) BitmapFactory.decodeFile(path)?.asImageBitmap() else null
                        }.getOrNull()
                    }
                }
                if (userBmp != null) {
                    Image(
                        bitmap = userBmp!!,
                        contentDescription = "用户附图",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .padding(bottom = 6.dp)
                            .widthIn(max = 220.dp)
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(0.6.dp, palette.hairline, RoundedCornerShape(16.dp))
                            .clickable { onPreviewImage(path) }
                    )
                }
            }

            Box {
                val bubbleShape = RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = 20.dp,
                    bottomEnd = 5.dp,
                    bottomStart = 20.dp
                )
                Box(
                    modifier = Modifier
                        .widthIn(max = 305.dp)
                        .omsCombinedBounce(
                            scaleDown = 0.98f,
                            shape = bubbleShape,
                            onLongClick = { showUserMenu = true },
                            onClick = {}
                        )
                        .background(palette.userBubble, bubbleShape)
                        .padding(horizontal = 15.dp, vertical = 10.5.dp)
                ) {
                    Text(
                        text = msg.content,
                        color = palette.userBubbleText,
                        fontSize = 15.5.sp,
                        lineHeight = 23.sp
                    )
                }

                DropdownMenu(
                    expanded = showUserMenu,
                    onDismissRequest = { showUserMenu = false },
                    modifier = Modifier.background(palette.composer)
                ) {
                    DropdownMenuItem(
                        leadingIcon = { Icon(OmsIcons.Copy, null, tint = palette.textSecondary, modifier = Modifier.size(16.dp)) },
                        text = { Text("复制文本", color = palette.textPrimary, fontSize = 13.5.sp) },
                        onClick = {
                            showUserMenu = false
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("prompt", msg.content))
                            OmsFeedbackController.show("已复制提问内容", OmsToastKind.SUCCESS)
                        }
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(OmsIcons.NewChat, null, tint = palette.textSecondary, modifier = Modifier.size(16.dp)) },
                        text = { Text("填入输入框重新编辑", color = palette.textPrimary, fontSize = 13.5.sp) },
                        onClick = {
                            showUserMenu = false
                            onEditUserPrompt(msg.content)
                        }
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(OmsIcons.Send, null, tint = palette.textSecondary, modifier = Modifier.size(16.dp)) },
                        text = { Text("投递至 Mac 收件箱", color = palette.textPrimary, fontSize = 13.5.sp) },
                        onClick = {
                            showUserMenu = false
                            OmsApplication.instance?.transportClient?.submitItemToMacOrQueue(
                                intentTag = "default_inbox",
                                itemType = "TEXT",
                                title = msg.content.take(32),
                                textContent = msg.content,
                                voiceTranscript = null,
                                fileUri = msg.imageLocalPath?.let { Uri.fromFile(File(it)) }
                            )
                            OmsFeedbackController.show("已发送至 Mac 收件箱", OmsToastKind.SUCCESS)
                        }
                    )
                }
            }
        } else {
            // =================================================================
            // Borderless Editorial Assistant Turn (ChatGPT / Gemini Canvas)
            // =================================================================
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Subtle Author / Model Eyebrow
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = OmsIcons.Sparkles,
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(13.5.dp)
                    )
                    Text(
                        text = prettifyModelShortName(msg.modelUsed.ifBlank { "AI" }),
                        color = palette.textTertiary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (msg.specimenCard != null) {
                    BotanicalSpecimenCardView(
                        card = msg.specimenCard,
                        photoPath = msg.imageLocalPath,
                        onPreviewPhoto = onPreviewImage,
                        onSaveVault = {
                            scope.launch {
                                val ok = coordinator.promoteSpecimenToVault(msg.specimenCard, msg.imageLocalPath)
                                OmsFeedbackController.show(
                                    if (ok) "已归档至 Mac 知识库 FieldGuide" else "已加入同步队列，连上 Mac 自动归档",
                                    OmsToastKind.SUCCESS
                                )
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
                        InlineGeneratedMediaPreview(
                            mediaPath = mediaPath,
                            messageId = msg.messageId,
                            onPreviewImage = onPreviewImage
                        )
                    }
                }

                // Inline Native Calendar, Alarm & Phone OS Super-Tool Action Cards
                msg.calendarActions.forEach { cal ->
                    InlineCalendarActionCard(action = cal)
                }
                msg.alarmActions.forEach { alarm ->
                    InlineAlarmActionCard(action = alarm)
                }
                msg.phoneToolActions.forEach { toolAct ->
                    InlinePhoneToolActionCard(
                        action = toolAct,
                        onOpenInWebAppHub = { url, title -> onOpenWebAppHub(url, title) }
                    )
                }

                // Clean Minimalist Vector Action Toolbar (zero text clutter!)
                val iconTint = if (isLatestAssistant) palette.textSecondary else palette.textTertiary
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OmsMorphIconAction(
                        icon = OmsIcons.Copy,
                        confirmedIcon = OmsIcons.Check,
                        contentDescription = "复制回复",
                        tint = iconTint,
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("reply", msg.content))
                        }
                    )

                    OmsCircleIconButton(
                        icon = OmsIcons.Volume,
                        contentDescription = "语音朗读",
                        size = 32.dp,
                        iconSize = 16.5.dp,
                        contentColor = iconTint,
                        onClick = {
                            val tc = OmsApplication.instance?.transportClient
                            if (tc != null) {
                                tc.speakTextSmart(msg.content, useAlarmStream = false, audioPolicy = SpeechAudioPolicy.MEDIA_COMMUTE)
                            } else {
                                NotificationExecutor.speakText(context, msg.content, useAlarmStream = false, audioPolicy = SpeechAudioPolicy.MEDIA_COMMUTE)
                            }
                        }
                    )

                    OmsMorphIconAction(
                        icon = OmsIcons.Bookmark,
                        confirmedIcon = OmsIcons.BookmarkFilled,
                        confirmedTint = palette.accent,
                        contentDescription = "提炼入知识库",
                        tint = iconTint,
                        onClick = {
                            scope.launch {
                                val title = msg.content.lineSequence().firstOrNull()?.replace("#", "")?.trim()?.take(36)
                                    ?: "随身对话高光笔记"
                                val ok = coordinator.promoteHighlightToVault(title, msg.content)
                                OmsFeedbackController.show(
                                    if (ok) "已提炼归档至个人知识库" else "已暂存，连上 Mac 后自动入库",
                                    OmsToastKind.SUCCESS
                                )
                            }
                        }
                    )

                    OmsCircleIconButton(
                        icon = OmsIcons.Laptop,
                        contentDescription = "转交 Mac Agent 推进",
                        size = 32.dp,
                        iconSize = 16.5.dp,
                        contentColor = iconTint,
                        onClick = onEscalateToMacAgent
                    )

                    if (isLatestAssistant) {
                        OmsCircleIconButton(
                            icon = OmsIcons.Refresh,
                            contentDescription = "重新生成",
                            size = 32.dp,
                            iconSize = 16.dp,
                            contentColor = iconTint,
                            onClick = onRegenerate
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamingAssistantTurn(state: com.oms.client.cockpit.StreamingTurnState) {
    val palette = OmsTheme.colors
    val infinite = rememberInfiniteTransition(label = "streamPulse")
    val pulse by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
        label = "dotScale"
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .scale(pulse)
                    .clip(CircleShape)
                    .background(palette.accent)
            )
            val label = state.statusHint
                .replace("💭 ", "")
                .replace("✦ ", "")
                .replace("🔍 ", "")
                .replace("📡 ", "")
                .ifBlank { prettifyModelShortName(state.modelLabel.ifBlank { "正在生成…" }) }
            Text(
                text = label,
                color = palette.textSecondary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium
            )
        }

        if (state.markdownText.isNotBlank() || state.reasoningText.isNotBlank()) {
            IncrementalMarkdownView(
                markdown = state.markdownText,
                reasoning = state.reasoningText,
                sources = state.sources,
                isStreaming = true
            )
        }
    }
}

@Composable
private fun InlinePhoneToolActionCard(
    action: com.oms.core.ai.ProposedPhoneToolAction,
    onOpenInWebAppHub: (url: String, title: String) -> Unit
) {
    val context = LocalContext.current
    val palette = OmsTheme.colors
    val scope = rememberCoroutineScope()
    var executed by remember { mutableStateOf(false) }
    var isRunning by remember { mutableStateOf(false) }
    var resultHint by remember { mutableStateOf("") }

    val icon = when (action.tool) {
        "launch_app" -> OmsIcons.Bolt
        "phone_call" -> OmsIcons.Volume
        "send_sms" -> OmsIcons.Send
        "open_in_browser" -> OmsIcons.Globe
        "navigate_map" -> OmsIcons.Globe
        "set_volume" -> OmsIcons.Volume
        "clipboard_write" -> OmsIcons.Copy
        "open_system_settings" -> OmsIcons.Sliders
        else -> OmsIcons.Bolt
    }
    val actionBtnLabel = when {
        isRunning -> "正在执行…"
        executed -> "已执行"
        action.tool == "open_in_browser" -> "应用舱内打开"
        action.tool == "phone_call" -> "确认拨出"
        action.tool == "send_sms" -> "打开短信草稿"
        action.tool == "launch_app" -> "立即打开"
        action.tool == "navigate_map" -> "开始导航"
        action.tool == "clipboard_write" -> "一键复制"
        else -> "立即执行"
    }

    OmsSurfaceCard(
        containerColor = palette.subtleSurface,
        borderColor = if (action.requiresConfirm) palette.warning.copy(alpha = 0.45f) else palette.hairline,
        cornerRadius = 16.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Icon(icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(16.dp))
                Text(
                    text = if (action.requiresConfirm) "手机操作 · 需确认" else "手机超级工具",
                    color = if (action.requiresConfirm) palette.warning else palette.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (executed) {
                Text("已完成", color = palette.success, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(action.title, color = palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (action.detail.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(action.detail, color = palette.textSecondary, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (resultHint.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(resultHint, color = if (executed) palette.success else palette.warning, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(10.dp))
        OmsPrimaryButton(
            label = actionBtnLabel,
            icon = if (executed) OmsIcons.Check else icon,
            enabled = !isRunning,
            height = 36.dp,
            onClick = {
                isRunning = true
                scope.launch {
                    val res = com.oms.client.capability.PhoneToolExecutor.execute(
                        context = context,
                        action = action,
                        onOpenInWebAppHub = onOpenInWebAppHub
                    )
                    isRunning = false
                    executed = res.ok
                    resultHint = res.message
                    OmsFeedbackController.show(
                        res.message,
                        if (res.ok) OmsToastKind.SUCCESS else OmsToastKind.WARNING
                    )
                }
            }
        )
    }
}

@Composable
private fun InlineCalendarActionCard(action: ProposedCalendarAction) {
    val context = LocalContext.current
    val palette = OmsTheme.colors
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
        SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(Date(action.startEpochMs))
    }

    OmsSurfaceCard(
        containerColor = palette.subtleSurface,
        borderColor = palette.hairline,
        cornerRadius = 16.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(OmsIcons.Calendar, contentDescription = null, tint = palette.accent, modifier = Modifier.size(16.dp))
            Text("系统日历确认", color = palette.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(action.title, color = palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text("$timeStr · ${action.durationMinutes} 分钟", color = palette.textSecondary, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(6.dp))
        if (!conflictTitle.isNullOrBlank()) {
            Text("时段存在已有日程：《$conflictTitle》", color = palette.warning, fontSize = 12.sp)
        } else {
            Text("已核验该时段无日程冲突", color = palette.success, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(10.dp))
        OmsPrimaryButton(
            label = if (committed) "已写入系统日历" else "写入小米日历",
            icon = if (committed) OmsIcons.Check else OmsIcons.Calendar,
            enabled = !committed,
            height = 36.dp,
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
                    OmsFeedbackController.show("已写入小米系统日历", OmsToastKind.SUCCESS)
                }.onFailure {
                    OmsFeedbackController.show("写入失败，请检查日历权限", OmsToastKind.WARNING)
                }
            }
        )
    }
}

@Composable
private fun InlineAlarmActionCard(action: ProposedAlarmAction) {
    val context = LocalContext.current
    val palette = OmsTheme.colors
    var committed by remember { mutableStateOf(false) }
    val timeLabel = String.format(Locale.US, "%02d:%02d", action.hour, action.minute)

    OmsSurfaceCard(
        containerColor = palette.subtleSurface,
        borderColor = palette.hairline,
        cornerRadius = 16.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(palette.accentSoft)
                ) {
                    Icon(OmsIcons.Clock, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
                }
                Column {
                    Text(timeLabel, color = palette.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(action.label.ifBlank { "系统闹钟" }, color = palette.textSecondary, fontSize = 12.5.sp)
                }
            }
            OmsPrimaryButton(
                label = if (committed) "已设定" else "设定闹钟",
                icon = if (committed) OmsIcons.Check else null,
                enabled = !committed,
                height = 36.dp,
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
                        OmsFeedbackController.show("已设定 $timeLabel 闹钟", OmsToastKind.SUCCESS)
                    }
                }
            )
        }
    }
}

@Composable
private fun InlineGeneratedMediaPreview(
    mediaPath: String,
    messageId: String,
    onPreviewImage: (String) -> Unit
) {
    val context = LocalContext.current
    val palette = OmsTheme.colors
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
                    contentDescription = "生成的图片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(0.6.dp, palette.hairline, RoundedCornerShape(16.dp))
                        .clickable { onPreviewImage(mediaPath) }
                )
            }
        }
        low.endsWith(".mp3") || low.endsWith(".wav") || low.endsWith(".m4a") -> {
            var isPlaying by remember { mutableStateOf(false) }
            val sizeKb = remember(file) { (file.length() / 1024L).coerceAtLeast(1L) }
            OmsSurfaceCard(
                containerColor = palette.subtleSurface,
                borderColor = if (isPlaying) palette.accent else palette.hairline,
                cornerRadius = 18.dp,
                contentPadding = PaddingValues(14.dp),
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
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(palette.accentSoft)
                        ) {
                            Icon(
                                imageVector = OmsIcons.Music,
                                contentDescription = null,
                                tint = palette.accent,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isPlaying) "正在播放 AI 原创曲目…" else "AI 原创音乐曲目",
                                color = palette.textPrimary,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${file.extension.uppercase()} · ${sizeKb} KB",
                                color = palette.textTertiary,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OmsPrimaryButton(
                            label = if (isPlaying) "停止" else "播放",
                            icon = if (isPlaying) OmsIcons.StopSquare else OmsIcons.Play,
                            height = 36.dp,
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
                        )
                        OmsCircleIconButton(
                            icon = OmsIcons.Send,
                            contentDescription = "用外部播放器打开或分享",
                            size = 36.dp,
                            iconSize = 16.5.dp,
                            containerColor = palette.cardSurface,
                            borderColor = palette.hairline,
                            onClick = {
                                runCatching {
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.ota", file)
                                    val mime = if (low.endsWith(".wav")) "audio/wav" else "audio/mpeg"
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW)
                                            .setDataAndType(uri, mime)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
        low.endsWith(".mp4") -> {
            val videoThumb by produceState<ImageBitmap?>(initialValue = null, key1 = mediaPath) {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        val mmr = MediaMetadataRetriever()
                        try {
                            mmr.setDataSource(file.absolutePath)
                            mmr.getFrameAtTime(0L)?.asImageBitmap()
                        } finally {
                            runCatching { mmr.release() }
                        }
                    }.getOrNull()
                }
            }
            val openVideo = {
                runCatching {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.ota", file)
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "video/mp4")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                }.onFailure {
                    OmsFeedbackController.show("视频保存在: ${file.absolutePath}", OmsToastKind.INFO)
                }
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(216.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF0A0D14))
                    .border(0.7.dp, palette.hairline, RoundedCornerShape(18.dp))
                    .clickable { openVideo() }
            ) {
                if (videoThumb != null) {
                    Image(
                        bitmap = videoThumb!!,
                        contentDescription = "生成的视频预览",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color(0xB8090D16))
                        .border(1.dp, Color(0x66FFFFFF), CircleShape)
                ) {
                    Icon(
                        imageVector = OmsIcons.Play,
                        contentDescription = "播放视频",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xB8090D16))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Icon(OmsIcons.Video, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                    Text(
                        text = "点击全屏播放有声视频 (${file.length() / 1024} KB)",
                        color = Color.White,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun MultimodalPlusSheetContent(
    onAttachPhotoUri: (Uri) -> Unit,
    onPickAlbumForChat: () -> Unit,
    onOpenBotanicalCamera: () -> Unit,
    onOpenGlassLive: () -> Unit,
    onDropPhotosToMac: () -> Unit,
    onDropDocsToMac: () -> Unit,
    onSelectCreative: (ModelRoleCategory) -> Unit
) {
    val context = LocalContext.current
    val palette = OmsTheme.colors
    var recentUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    LaunchedEffect(Unit) {
        recentUris = withContext(Dispatchers.IO) { queryRecentMediaUris(context, limit = 10) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (recentUris.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "最近相册 · 点选直接附图提问",
                    color = palette.textTertiary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(items = recentUris, key = { it.toString() }) { uri ->
                        val thumb by produceState<ImageBitmap?>(initialValue = null, key1 = uri) {
                            value = withContext(Dispatchers.IO) {
                                runCatching {
                                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                                    context.contentResolver.openInputStream(uri)?.use {
                                        BitmapFactory.decodeStream(it, null, opts)?.asImageBitmap()
                                    }
                                }.getOrNull()
                            }
                        }
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .omsBounceClick(scaleDown = 0.93f, shape = RoundedCornerShape(14.dp)) {
                                    onAttachPhotoUri(uri)
                                }
                                .background(palette.subtleSurface, RoundedCornerShape(14.dp))
                                .border(0.6.dp, palette.hairline, RoundedCornerShape(14.dp))
                        ) {
                            if (thumb != null) {
                                Image(
                                    bitmap = thumb!!,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2x4 Clean Action Tile Grid
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                SheetActionTile(
                    icon = OmsIcons.Image,
                    title = "相册附图",
                    subtitle = "选图发给 AI",
                    modifier = Modifier.weight(1f),
                    onClick = onPickAlbumForChat
                )
                SheetActionTile(
                    icon = OmsIcons.Camera,
                    title = "博物镜头",
                    subtitle = "识植物/展品",
                    modifier = Modifier.weight(1f),
                    onClick = onOpenBotanicalCamera
                )
                SheetActionTile(
                    icon = OmsIcons.Sparkles,
                    title = "Glass 实时",
                    subtitle = "边看边聊",
                    modifier = Modifier.weight(1f),
                    onClick = onOpenGlassLive
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                SheetActionTile(
                    icon = OmsIcons.Send,
                    title = "图片传 Mac",
                    subtitle = "多图直达桌面",
                    modifier = Modifier.weight(1f),
                    onClick = onDropPhotosToMac
                )
                SheetActionTile(
                    icon = OmsIcons.FileText,
                    title = "文档传 Mac",
                    subtitle = "PDF / Office",
                    modifier = Modifier.weight(1f),
                    onClick = onDropDocsToMac
                )
                SheetActionTile(
                    icon = OmsIcons.Palette,
                    title = "视觉生图",
                    subtitle = "AI 图像创作",
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectCreative(ModelRoleCategory.IMAGE_GENERATION) }
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                SheetActionTile(
                    icon = OmsIcons.Video,
                    title = "视频生成",
                    subtitle = "动态短片",
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectCreative(ModelRoleCategory.VIDEO_GENERATION) }
                )
                SheetActionTile(
                    icon = OmsIcons.Music,
                    title = "AI 写歌",
                    subtitle = "旋律与配乐",
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectCreative(ModelRoleCategory.MUSIC_GENERATION) }
                )
                Spacer(modifier = Modifier.weight(1f))
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
    }
}

@Composable
private fun SheetActionTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = OmsTheme.colors
    val shape = RoundedCornerShape(16.dp)
    Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .omsBounceClick(scaleDown = 0.95f, shape = shape, onClick = onClick)
            .background(palette.subtleSurface, shape)
            .border(0.6.dp, palette.hairline, shape)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = palette.accent,
            modifier = Modifier.size(20.dp)
        )
        Column {
            Text(title, color = palette.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(subtitle, color = palette.textTertiary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun queryRecentMediaUris(context: Context, limit: Int): List<Uri> {
    val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
    if (!granted) return emptyList()
    val out = ArrayList<Uri>()
    runCatching {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.IS_PENDING),
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                if (c.getInt(1) != 0) continue
                out.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)))
            }
        }
    }
    return out
}

internal fun prettifyModelShortName(raw: String): String {
    val clean = raw.substringAfter(':', raw).trim()
    return when {
        clean.equals("auto", ignoreCase = true) -> "Auto"
        clean.contains("gpt-6.2-astra", ignoreCase = true) -> "GPT-6.2 Astra"
        clean.contains("gpt-6.2-sol", ignoreCase = true) -> "GPT-6.2 Sol"
        clean.contains("gpt-6.1-terra", ignoreCase = true) -> "GPT-6.1 Terra"
        clean.contains("gemini-4-argon", ignoreCase = true) -> "Gemini 4 Argon"
        clean.contains("gemini-3.8-flash", ignoreCase = true) -> "Gemini 3.8 Flash"
        clean.contains("gemini-3.8-live", ignoreCase = true) -> "Gemini 3.8 Live"
        clean.contains("gpt-realtime", ignoreCase = true) -> "GPT Realtime"
        else -> clean.take(20)
    }
}
