package com.oms.client.ui.cockpit

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.oms.client.capability.VoiceRecorderHelper
import com.oms.client.cockpit.CockpitRuntimeCoordinator
import com.oms.client.cockpit.MacHidModMask
import com.oms.client.cockpit.MacHidPacket
import com.oms.client.cockpit.MacHidSystemKey
import com.oms.client.cockpit.MacKeyCodes
import com.oms.client.ui.theme.OmsFeedbackController
import com.oms.client.ui.theme.OmsIcons
import com.oms.client.ui.theme.OmsPrimaryButton
import com.oms.client.ui.theme.OmsSecondaryButton
import com.oms.client.ui.theme.OmsSurfaceCard
import com.oms.client.ui.theme.OmsTheme
import com.oms.client.ui.theme.OmsToastKind
import com.oms.client.ui.theme.OmsTogglePill
import com.oms.client.ui.theme.omsBounceClick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lossless Unicode Text Injector, Hold/Tap-to-Speak Voice-to-Mac Dictation,
 * and Eyes-Free Tactile Shortcut / Navigation / Media / Brightness Deck.
 */
@Composable
fun MacKeyboardAndVoiceDeckView(
    coordinator: CockpitRuntimeCoordinator,
    macConnected: Boolean,
    focusInputNonce: Long = 0L,
    modifier: Modifier = Modifier
) {
    val palette = OmsTheme.colors
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    var textInput by remember { mutableStateOf("") }
    var autoSubmitReturn by remember { mutableStateOf(false) }
    var isSendingText by remember { mutableStateOf(false) }
    var stickyMods by remember { mutableIntStateOf(MacHidModMask.NONE) }

    LaunchedEffect(focusInputNonce) {
        if (focusInputNonce > 0L) {
            delay(80L)
            runCatching { focusRequester.requestFocus() }
        }
    }

    fun toggleStickyMod(mask: Int) {
        stickyMods = stickyMods xor mask
        coordinator.vibrateTick()
    }

    fun tapKeyWithSticky(keyCode: Int, extraMods: Int = MacHidModMask.NONE, clearSticky: Boolean = true) {
        if (!macConnected) {
            OmsFeedbackController.show("当前未连通 Mac，请确认电脑在线", OmsToastKind.WARNING)
            return
        }
        val combinedMods = stickyMods or extraMods
        coordinator.sendMacHid(MacHidPacket.keyTap(keyCode, combinedMods), flushImmediate = true)
        coordinator.vibrateTick()
        if (clearSticky && stickyMods != MacHidModMask.NONE) {
            stickyMods = MacHidModMask.NONE
        }
    }

    fun tapSystemKey(sysKey: Int) {
        if (!macConnected) {
            OmsFeedbackController.show("当前未连通 Mac，请确认电脑在线", OmsToastKind.WARNING)
            return
        }
        coordinator.sendMacHid(MacHidPacket.systemKey(sysKey), flushImmediate = true)
        coordinator.vibrateTick()
    }

    fun submitUnicodeText() {
        val payload = textInput
        if (payload.isEmpty() || isSendingText) return
        if (!macConnected) {
            OmsFeedbackController.show("当前未连通 Mac，请确认电脑在线", OmsToastKind.WARNING)
            return
        }
        isSendingText = true
        coordinator.vibrateTick()
        scope.launch {
            val (ok, msg) = coordinator.typeTextToMac(payload, submitReturn = autoSubmitReturn)
            isSendingText = false
            if (ok) {
                textInput = ""
            }
            OmsFeedbackController.show(msg, if (ok) OmsToastKind.SUCCESS else OmsToastKind.WARNING)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        // 1. Lossless Unicode Text & Voice-to-Mac Card
        OmsSurfaceCard(
            cornerRadius = 18.dp,
            contentPadding = PaddingValues(13.dp),
            modifier = Modifier.fillMaxWidth()
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
                    Icon(
                        imageVector = OmsIcons.Keyboard,
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "无损文字与语音直输 Mac",
                        color = palette.textPrimary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                OmsTogglePill(
                    label = "⏎ 自动回车",
                    active = autoSubmitReturn,
                    onClick = {
                        autoSubmitReturn = !autoSubmitReturn
                        coordinator.vibrateTick()
                    }
                )
            }

            Spacer(modifier = Modifier.height(9.dp))

            // Multi-line Unicode Input Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.subtleSurface)
                    .border(0.65.dp, palette.hairline, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                if (textInput.isEmpty()) {
                    Text(
                        text = "输入任意中英文、代码、标点或 Emoji，一键无损注入 Mac 当前光标处…",
                        color = palette.textTertiary,
                        fontSize = 13.5.sp,
                        lineHeight = 19.sp
                    )
                }
                BasicTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    textStyle = TextStyle(
                        color = palette.textPrimary,
                        fontSize = 14.2.sp,
                        lineHeight = 20.sp
                    ),
                    cursorBrush = SolidColor(palette.accent),
                    maxLines = 5,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .focusRequester(focusRequester)
                )
            }

            Spacer(modifier = Modifier.height(9.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OmsSecondaryButton(
                    label = "清空",
                    enabled = textInput.isNotEmpty() && !isSendingText,
                    height = 38.dp,
                    onClick = {
                        textInput = ""
                        coordinator.vibrateTick()
                    }
                )

                OmsPrimaryButton(
                    label = if (isSendingText) "正在输入 Mac…" else "发送至 Mac 光标",
                    icon = OmsIcons.Send,
                    enabled = macConnected && textInput.isNotEmpty() && !isSendingText,
                    height = 38.dp,
                    onClick = ::submitUnicodeText,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Hold-to-Speak / Tap-to-Speak Voice Dictation Bar straight into Mac!
            MacInlineVoiceDictatePill(
                coordinator = coordinator,
                macConnected = macConnected,
                autoSubmitReturn = autoSubmitReturn,
                compact = false,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 2. Tactile Blind-Control Shortcut & Navigation Deck
        OmsSurfaceCard(
            cornerRadius = 18.dp,
            contentPadding = PaddingValues(13.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "盲操快捷键与导航矩阵",
                    color = palette.textPrimary,
                    fontSize = 13.2.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (stickyMods != MacHidModMask.NONE) {
                    Text(
                        text = "修饰键已锁 · 敲击下个键自动释放",
                        color = palette.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    Text(
                        text = "退格与方向键支持长按连发",
                        color = palette.textTertiary,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(9.dp))

            // Row A: Sticky Modifier Toggles (`⌘ Cmd`, `⇧ Shift`, `⌥ Opt`, `⌃ Ctrl`)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DeckModifierKey(
                    label = "⌘ Cmd",
                    active = (stickyMods and MacHidModMask.COMMAND) != 0,
                    onClick = { toggleStickyMod(MacHidModMask.COMMAND) },
                    modifier = Modifier.weight(1f)
                )
                DeckModifierKey(
                    label = "⇧ Shift",
                    active = (stickyMods and MacHidModMask.SHIFT) != 0,
                    onClick = { toggleStickyMod(MacHidModMask.SHIFT) },
                    modifier = Modifier.weight(1f)
                )
                DeckModifierKey(
                    label = "⌥ Opt",
                    active = (stickyMods and MacHidModMask.OPTION) != 0,
                    onClick = { toggleStickyMod(MacHidModMask.OPTION) },
                    modifier = Modifier.weight(1f)
                )
                DeckModifierKey(
                    label = "⌃ Ctrl",
                    active = (stickyMods and MacHidModMask.CONTROL) != 0,
                    onClick = { toggleStickyMod(MacHidModMask.CONTROL) },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Row B: Core Editing Row (`Esc` · `Tab` · `Space` · `⌫ 退格` [hold-to-repeat!] · `⏎ 回车`)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DeckTapKey(
                    label = "Esc",
                    enabled = macConnected,
                    onClick = { tapKeyWithSticky(MacKeyCodes.ESCAPE) },
                    modifier = Modifier.weight(0.88f)
                )
                DeckTapKey(
                    label = "Tab",
                    enabled = macConnected,
                    onClick = { tapKeyWithSticky(MacKeyCodes.TAB) },
                    modifier = Modifier.weight(0.88f)
                )
                DeckTapKey(
                    label = "Space",
                    enabled = macConnected,
                    onClick = { tapKeyWithSticky(MacKeyCodes.SPACE) },
                    modifier = Modifier.weight(1.35f)
                )
                DeckRepeatableKey(
                    label = "⌫ 退格",
                    enabled = macConnected,
                    onStep = { tapKeyWithSticky(MacKeyCodes.DELETE, clearSticky = false) },
                    onRelease = {
                        if (stickyMods != MacHidModMask.NONE) stickyMods = MacHidModMask.NONE
                    },
                    modifier = Modifier.weight(1.05f)
                )
                DeckTapKey(
                    label = "⏎ 回车",
                    accent = true,
                    enabled = macConnected,
                    onClick = { tapKeyWithSticky(MacKeyCodes.RETURN) },
                    modifier = Modifier.weight(1.05f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Row C: Productivity Shortcuts (`⌘Space (聚焦)` · `⌘Tab (切应用)` · `⌘C` · `⌘V` · `⌘Z` · `⌘W`)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DeckTapKey(
                    label = "⌘Space 聚焦",
                    enabled = macConnected,
                    onClick = { tapKeyWithSticky(MacKeyCodes.SPACE, MacHidModMask.COMMAND) },
                    modifier = Modifier.weight(1f)
                )
                DeckTapKey(
                    label = "⌘Tab 切应用",
                    enabled = macConnected,
                    onClick = { tapKeyWithSticky(MacKeyCodes.TAB, MacHidModMask.COMMAND) },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DeckTapKey(
                    label = "⌘C",
                    enabled = macConnected,
                    onClick = { tapKeyWithSticky(MacKeyCodes.ANSI_C, MacHidModMask.COMMAND) },
                    modifier = Modifier.weight(1f)
                )
                DeckTapKey(
                    label = "⌘V",
                    enabled = macConnected,
                    onClick = { tapKeyWithSticky(MacKeyCodes.ANSI_V, MacHidModMask.COMMAND) },
                    modifier = Modifier.weight(1f)
                )
                DeckTapKey(
                    label = "⌘Z",
                    enabled = macConnected,
                    onClick = { tapKeyWithSticky(MacKeyCodes.ANSI_Z, MacHidModMask.COMMAND) },
                    modifier = Modifier.weight(1f)
                )
                DeckTapKey(
                    label = "⌘W",
                    enabled = macConnected,
                    onClick = { tapKeyWithSticky(MacKeyCodes.ANSI_W, MacHidModMask.COMMAND) },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Row D: Inverted-T D-Pad + Desktop Spaces Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                DeckTapKey(
                    label = "⌃◀ 左空间",
                    height = 44.dp,
                    enabled = macConnected,
                    onClick = { tapKeyWithSticky(MacKeyCodes.LEFT, MacHidModMask.CONTROL) },
                    modifier = Modifier.weight(1.12f)
                )

                DeckRepeatableKey(
                    label = "◀",
                    height = 44.dp,
                    enabled = macConnected,
                    onStep = { tapKeyWithSticky(MacKeyCodes.LEFT, clearSticky = false) },
                    onRelease = {
                        if (stickyMods != MacHidModMask.NONE) stickyMods = MacHidModMask.NONE
                    },
                    modifier = Modifier.weight(0.78f)
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(0.84f)
                ) {
                    DeckRepeatableKey(
                        label = "▲",
                        height = 34.dp,
                        enabled = macConnected,
                        onStep = { tapKeyWithSticky(MacKeyCodes.UP, clearSticky = false) },
                        onRelease = {
                            if (stickyMods != MacHidModMask.NONE) stickyMods = MacHidModMask.NONE
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    DeckRepeatableKey(
                        label = "▼",
                        height = 34.dp,
                        enabled = macConnected,
                        onStep = { tapKeyWithSticky(MacKeyCodes.DOWN, clearSticky = false) },
                        onRelease = {
                            if (stickyMods != MacHidModMask.NONE) stickyMods = MacHidModMask.NONE
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                DeckRepeatableKey(
                    label = "▶",
                    height = 44.dp,
                    enabled = macConnected,
                    onStep = { tapKeyWithSticky(MacKeyCodes.RIGHT, clearSticky = false) },
                    onRelease = {
                        if (stickyMods != MacHidModMask.NONE) stickyMods = MacHidModMask.NONE
                    },
                    modifier = Modifier.weight(0.78f)
                )

                DeckTapKey(
                    label = "右空间 ⌃▶",
                    height = 44.dp,
                    enabled = macConnected,
                    onClick = { tapKeyWithSticky(MacKeyCodes.RIGHT, MacHidModMask.CONTROL) },
                    modifier = Modifier.weight(1.12f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Row E: System Media & Display Brightness (`🔆-`, `🔆+`, `⏮`, `⏯`, `⏭`, `🔇`)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DeckTapKey(
                    label = "🔆-",
                    height = 38.dp,
                    enabled = macConnected,
                    onClick = { tapSystemKey(MacHidSystemKey.BRIGHTNESS_DOWN) },
                    modifier = Modifier.weight(1f)
                )
                DeckTapKey(
                    label = "🔆+",
                    height = 38.dp,
                    enabled = macConnected,
                    onClick = { tapSystemKey(MacHidSystemKey.BRIGHTNESS_UP) },
                    modifier = Modifier.weight(1f)
                )
                DeckTapKey(
                    label = "⏮",
                    height = 38.dp,
                    enabled = macConnected,
                    onClick = { tapSystemKey(MacHidSystemKey.PREV_TRACK) },
                    modifier = Modifier.weight(1f)
                )
                DeckTapKey(
                    label = "⏯",
                    height = 38.dp,
                    enabled = macConnected,
                    onClick = { tapSystemKey(MacHidSystemKey.PLAY_PAUSE) },
                    modifier = Modifier.weight(1f)
                )
                DeckTapKey(
                    label = "⏭",
                    height = 38.dp,
                    enabled = macConnected,
                    onClick = { tapSystemKey(MacHidSystemKey.NEXT_TRACK) },
                    modifier = Modifier.weight(1f)
                )
                DeckTapKey(
                    label = "🔇",
                    height = 38.dp,
                    enabled = macConnected,
                    onClick = { tapSystemKey(MacHidSystemKey.MUTE) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * Shared Hold-to-Speak & Tap-to-Speak Voice Dictation Pill that records `.m4a` via `VoiceRecorderHelper`,
 * transcribes via `coordinator.aiServices.transcribeAudioFile`, and types directly into Mac via `typeTextToMac`.
 */
@Composable
internal fun MacInlineVoiceDictatePill(
    coordinator: CockpitRuntimeCoordinator,
    macConnected: Boolean,
    autoSubmitReturn: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val palette = OmsTheme.colors
    val scope = rememberCoroutineScope()

    var isRecording by remember { mutableStateOf(false) }
    var isTapModeRecording by remember { mutableStateOf(false) }
    var isTranscribing by remember { mutableStateOf(false) }
    var elapsedSec by remember { mutableIntStateOf(0) }

    val currentMacConnected by rememberUpdatedState(macConnected)
    val currentAutoSubmit by rememberUpdatedState(autoSubmitReturn)
    val currentIsRecording by rememberUpdatedState(isRecording)
    val currentIsTapMode by rememberUpdatedState(isTapModeRecording)
    val currentIsTranscribing by rememberUpdatedState(isTranscribing)

    DisposableEffect(Unit) {
        onDispose {
            if (currentIsRecording) {
                VoiceRecorderHelper.cancelRecording()
            }
        }
    }

    LaunchedEffect(isRecording) {
        if (!isRecording) {
            elapsedSec = 0
            return@LaunchedEffect
        }
        val startedAt = SystemClock.elapsedRealtime()
        while (isRecording && VoiceRecorderHelper.isRecording()) {
            elapsedSec = ((SystemClock.elapsedRealtime() - startedAt) / 1000L).toInt()
            delay(250L)
        }
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (!currentMacConnected) {
                OmsFeedbackController.show("当前未连通 Mac，请确认电脑在线", OmsToastKind.WARNING)
                return@rememberLauncherForActivityResult
            }
            if (VoiceRecorderHelper.startRecording(context) != null) {
                isRecording = true
                isTapModeRecording = true
                coordinator.vibrateTick()
            }
        } else {
            OmsFeedbackController.show("需要麦克风权限才能语音直输 Mac", OmsToastKind.WARNING)
        }
    }

    fun finishAndTranscribeToMac() {
        isRecording = false
        isTapModeRecording = false
        val recorded = VoiceRecorderHelper.stopRecording()
        if (recorded == null) {
            OmsFeedbackController.show("录音过短，请按住说话或轻点开始听写", OmsToastKind.INFO)
            return
        }
        isTranscribing = true
        coordinator.vibrateTick()
        scope.launch {
            try {
                val transcript = withContext(Dispatchers.IO) {
                    coordinator.aiServices.transcribeAudioFile(recorded.first, mimeType = "audio/mp4")
                }.trim()
                if (transcript.isBlank()) {
                    OmsFeedbackController.show("未识别到清晰语音，请重试", OmsToastKind.INFO)
                } else {
                    val (ok, msg) = coordinator.typeTextToMac(transcript, submitReturn = currentAutoSubmit)
                    val feedback = if (ok) {
                        val preview = if (transcript.length > 22) "${transcript.take(22)}…" else transcript
                        "🎙️ 已上屏 Mac：$preview"
                    } else {
                        msg
                    }
                    OmsFeedbackController.show(feedback, if (ok) OmsToastKind.SUCCESS else OmsToastKind.WARNING)
                }
            } catch (e: Throwable) {
                OmsFeedbackController.show("语音识别未完成：${e.message ?: "请检查网络或模型配置"}", OmsToastKind.WARNING)
            } finally {
                runCatching { recorded.first.delete() }
                isTranscribing = false
            }
        }
    }

    val pulse = rememberInfiniteTransition(label = "voiceToMacPulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(480), RepeatMode.Reverse),
        label = "voiceAlpha"
    )

    val pillHeight = if (compact) 38.dp else 44.dp
    val shape = RoundedCornerShape(pillHeight / 2)
    val bgColor = when {
        isRecording -> palette.dangerSoft
        isTranscribing -> palette.accentSoft
        compact -> palette.subtleSurface
        else -> palette.accentSoft
    }
    val borderColor = when {
        isRecording -> palette.danger.copy(alpha = pulseAlpha)
        isTranscribing -> palette.accent.copy(alpha = 0.6f)
        compact -> palette.hairline
        else -> palette.accent.copy(alpha = 0.45f)
    }
    val contentColor = when {
        isRecording -> palette.danger
        isTranscribing -> palette.accent
        compact -> palette.textPrimary
        else -> palette.accent
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .height(pillHeight)
            .clip(shape)
            .background(bgColor, shape)
            .border(0.8.dp, borderColor, shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()

                    if (currentIsTranscribing) return@awaitEachGesture

                    if (!currentMacConnected) {
                        OmsFeedbackController.show("当前未连通 Mac，请确认电脑在线", OmsToastKind.WARNING)
                        return@awaitEachGesture
                    }

                    // If already recording in Tap-to-Speak mode, tapping finishes and sends to Mac
                    if (currentIsRecording && currentIsTapMode) {
                        while (true) {
                            val ev = awaitPointerEvent()
                            ev.changes.forEach { it.consume() }
                            if (ev.changes.none { it.pressed }) break
                        }
                        finishAndTranscribeToMac()
                        return@awaitEachGesture
                    }

                    val hasMicPerm = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!hasMicPerm) {
                        micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        return@awaitEachGesture
                    }

                    val downMs = SystemClock.uptimeMillis()
                    if (VoiceRecorderHelper.startRecording(context) == null) {
                        OmsFeedbackController.show("无法启动麦克风录音", OmsToastKind.WARNING)
                        return@awaitEachGesture
                    }
                    isRecording = true
                    isTapModeRecording = false
                    coordinator.vibrateTick()

                    var releasedCleanly = false
                    try {
                        while (true) {
                            val ev = awaitPointerEvent()
                            ev.changes.forEach { it.consume() }
                            if (ev.changes.none { it.pressed }) {
                                releasedCleanly = true
                                break
                            }
                        }
                    } finally {
                        if (!releasedCleanly && isRecording && !isTapModeRecording) {
                            VoiceRecorderHelper.cancelRecording()
                            isRecording = false
                        }
                    }

                    if (releasedCleanly) {
                        val heldMs = SystemClock.uptimeMillis() - downMs
                        if (heldMs < 280L) {
                            // Short tap seamlessly transitions into Tap-to-Speak mode!
                            isTapModeRecording = true
                        } else {
                            finishAndTranscribeToMac()
                        }
                    }
                }
            }
            .padding(horizontal = if (compact) 10.dp else 14.dp)
    ) {
        if (isTranscribing) {
            CircularProgressIndicator(
                color = contentColor,
                strokeWidth = 2.dp,
                modifier = Modifier.size(14.dp)
            )
        } else {
            Icon(
                imageVector = if (isRecording) OmsIcons.Waveform else OmsIcons.Mic,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(if (compact) 15.dp else 16.5.dp)
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        val textLabel = when {
            isTranscribing -> if (compact) "识别上屏中…" else "⏳ 正在识别并输入 Mac…"
            isRecording && isTapModeRecording -> if (compact) {
                "聆听 ${elapsedSec}s · 点此上屏"
            } else {
                "🎙️ 正在聆听 (${elapsedSec}s)，轻点立即上屏 Mac…"
            }
            isRecording -> if (compact) {
                "松手即上屏 (${elapsedSec}s)…"
            } else {
                "🎙️ 正在聆听，松手即上屏 Mac…"
            }
            compact -> "按住语音直输 Mac"
            else -> "按住说话（或轻点开始）· 语音直输 Mac"
        }
        Text(
            text = textLabel,
            color = contentColor,
            fontSize = if (compact) 12.sp else 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DeckModifierKey(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = OmsTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val bg = if (active) palette.accentSoft else palette.subtleSurface
    val border = if (active) palette.accent.copy(alpha = 0.65f) else palette.hairline
    val fg = if (active) palette.accent else palette.textSecondary

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(36.dp)
            .omsBounceClick(scaleDown = 0.94f, shape = shape, haptic = false, onClick = onClick)
            .background(bg, shape)
            .border(0.7.dp, border, shape)
            .padding(horizontal = 4.dp)
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun DeckTapKey(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    height: Dp = 40.dp
) {
    val palette = OmsTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val bg = if (accent) palette.accentSoft else palette.subtleSurface
    val border = if (accent) palette.accent.copy(alpha = 0.55f) else palette.hairline
    val fg = if (accent) palette.accent else palette.textPrimary

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(height)
            .omsBounceClick(enabled = enabled, scaleDown = 0.94f, shape = shape, haptic = false, onClick = onClick)
            .background(bg, shape)
            .border(0.65.dp, border, shape)
            .padding(horizontal = 6.dp)
    ) {
        Text(
            text = label,
            color = if (enabled) fg else palette.textTertiary,
            fontSize = 12.2.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DeckRepeatableKey(
    label: String,
    enabled: Boolean,
    onStep: () -> Unit,
    onRelease: () -> Unit = {},
    modifier: Modifier = Modifier,
    height: Dp = 40.dp
) {
    val palette = OmsTheme.colors
    val scope = rememberCoroutineScope()
    var isPressed by remember { mutableStateOf(false) }
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnStep by rememberUpdatedState(onStep)
    val currentOnRelease by rememberUpdatedState(onRelease)

    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.93f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 660f),
        label = "repeatKeyScale"
    )
    val shape = RoundedCornerShape(12.dp)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.45f
            }
            .clip(shape)
            .background(if (isPressed) palette.accentSoft else palette.subtleSurface, shape)
            .border(0.65.dp, if (isPressed) palette.accent.copy(alpha = 0.55f) else palette.hairline, shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    if (!currentEnabled) return@awaitEachGesture

                    isPressed = true
                    currentOnStep()

                    val repeatJob: Job = scope.launch {
                        delay(350L)
                        while (isActive) {
                            currentOnStep()
                            delay(72L)
                        }
                    }

                    try {
                        while (true) {
                            val ev = awaitPointerEvent()
                            ev.changes.forEach { it.consume() }
                            if (ev.changes.none { it.pressed }) break
                        }
                    } finally {
                        repeatJob.cancel()
                        isPressed = false
                        currentOnRelease()
                    }
                }
            }
            .padding(horizontal = 6.dp)
    ) {
        Text(
            text = label,
            color = if (enabled) palette.textPrimary else palette.textTertiary,
            fontSize = 12.2.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
