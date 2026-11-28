package com.oms.client.ui.cockpit

import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.Window
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.oms.client.capability.PhoneToolExecutor
import com.oms.client.capability.VoiceRecorderHelper
import com.oms.client.cockpit.CockpitRuntimeCoordinator
import com.oms.client.cockpit.MacControlStatusUi
import com.oms.client.cockpit.MacHidPacket
import com.oms.client.cockpit.MacHidSystemKey
import com.oms.client.cockpit.MacInstalledAppUi
import com.oms.client.cockpit.MacKeyCodes
import com.oms.client.ui.theme.OmsCircleIconButton
import com.oms.client.ui.theme.OmsFeedbackController
import com.oms.client.ui.theme.OmsIcons
import com.oms.client.ui.theme.OmsPrimaryButton
import com.oms.client.ui.theme.OmsSecondaryButton
import com.oms.client.ui.theme.OmsSegmentItem
import com.oms.client.ui.theme.OmsSlidingSegmentedBar
import com.oms.client.ui.theme.OmsSurfaceCard
import com.oms.client.ui.theme.OmsTheme
import com.oms.client.ui.theme.OmsToastKind
import com.oms.client.ui.theme.omsBounceClick
import com.oms.core.ai.ProposedPhoneToolAction
import kotlinx.coroutines.launch
import java.util.Locale

private enum class MacRemoteTab(val label: String) {
    TRACKPAD("触控板"),
    LASER_WAND("激光飞鼠"),
    KEYBOARD_VOICE("键盘语音"),
    SYSTEM_APPS("系统应用")
}

private class VolumeInterceptWindowCallback(
    val delegate: Window.Callback,
    private val interceptKeyEvent: (KeyEvent) -> Boolean
) : Window.Callback by delegate {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (interceptKeyEvent(event)) return true
        return delegate.dispatchKeyEvent(event)
    }

    override fun onProvideKeyboardShortcuts(
        data: MutableList<KeyboardShortcutGroup>?,
        menu: Menu?,
        deviceId: Int
    ) {
        delegate.onProvideKeyboardShortcuts(data, menu, deviceId)
    }

    override fun onPointerCaptureChanged(hasCapture: Boolean) {
        delegate.onPointerCaptureChanged(hasCapture)
    }
}

@Composable
fun MacRemoteControlModal(
    coordinator: CockpitRuntimeCoordinator,
    activeTier: String,
    pairedMacName: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val palette = OmsTheme.colors
    val scope = rememberCoroutineScope()
    val status by coordinator.macControlStatusFlow.collectAsState()
    val macConnected = activeTier != "DISCONNECTED"

    var currentTab by remember { mutableStateOf(MacRemoteTab.TRACKPAD) }
    var keyboardFocusNonce by remember { mutableLongStateOf(0L) }
    var appSearchQuery by remember { mutableStateOf("") }
    var showKeychainPasswordDialog by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var volumeSlider by remember(status?.volumePct) {
        mutableFloatStateOf((status?.volumePct ?: 50).toFloat())
    }

    val currentMacConnected by rememberUpdatedState(macConnected)
    val currentActiveTab by rememberUpdatedState(currentTab)

    // Clean fail-safe reset of held drags, sticky state, voice recorder & laser overlay on modal close
    DisposableEffect(Unit) {
        onDispose {
            VoiceRecorderHelper.cancelRecording()
            coordinator.resetMacHidState()
        }
    }

    LaunchedEffect(Unit, macConnected) {
        if (macConnected) {
            coordinator.refreshMacControlStatus(refreshApps = true)
        }
    }

    fun triggerMacAction(action: String, target: String = "", value: String = "") {
        if (!macConnected) {
            OmsFeedbackController.show("当前未连通 Mac，请确认电脑在线", OmsToastKind.WARNING)
            return
        }
        isBusy = true
        scope.launch {
            val res = PhoneToolExecutor.execute(
                context = context,
                action = ProposedPhoneToolAction(
                    tool = "mac_control",
                    title = action,
                    target = "$action|$target",
                    extra = value
                )
            )
            isBusy = false
            coordinator.refreshMacControlStatus(refreshApps = action == "open_app" || action == "quit_app")
            OmsFeedbackController.show(
                res.message,
                if (res.ok) OmsToastKind.SUCCESS else OmsToastKind.WARNING
            )
        }
    }

    fun requestUnlockOrConfigure() {
        if (status?.unlockPasswordConfigured == false) {
            passwordInput = ""
            showKeychainPasswordDialog = true
        } else {
            triggerMacAction("unlock")
        }
    }

    fun handleHardwareVolumeKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }
        if (!currentMacConnected) {
            return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val isUp = code == KeyEvent.KEYCODE_VOLUME_UP
            if (currentActiveTab == MacRemoteTab.LASER_WAND) {
                // Single-slide step per press in Laser Presenter mode (ignore key-repeat burst)
                if (event.repeatCount == 0) {
                    val slideKey = if (isUp) MacKeyCodes.LEFT else MacKeyCodes.RIGHT
                    coordinator.sendMacHid(MacHidPacket.keyTap(slideKey), flushImmediate = true)
                    coordinator.vibrateLightTick()
                }
            } else {
                val sysKey = if (isUp) MacHidSystemKey.SOUND_UP else MacHidSystemKey.SOUND_DOWN
                coordinator.sendMacHid(MacHidPacket.systemKey(sysKey), flushImmediate = true)
                coordinator.vibrateLightTick()
            }
        }
        // Consume both ACTION_DOWN & ACTION_UP so Android's phone volume HUD never steals focus
        return true
    }

    val effectiveMacName = status?.macName?.ifBlank { pairedMacName } ?: pairedMacName
    val filteredApps = remember(status?.installedApps, appSearchQuery) {
        val list = status?.installedApps.orEmpty()
        val q = appSearchQuery.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) list else list.filter { it.name.lowercase(Locale.ROOT).contains(q) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val localView = LocalView.current
        val dialogWindow = (localView.parent as? DialogWindowProvider)?.window

        DisposableEffect(dialogWindow) {
            val win = dialogWindow ?: return@DisposableEffect onDispose {}
            val prevCallback = win.callback
            if (prevCallback != null) {
                val wrapper = VolumeInterceptWindowCallback(prevCallback) { ev ->
                    handleHardwareVolumeKey(ev)
                }
                win.callback = wrapper
                onDispose {
                    if (win.callback === wrapper) {
                        win.callback = prevCallback
                    }
                }
            } else {
                onDispose {}
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.canvas)
                .statusBarsPadding()
                .navigationBarsPadding()
                .onPreviewKeyEvent { keyEvt ->
                    handleHardwareVolumeKey(keyEvt.nativeKeyEvent)
                }
        ) {
            // 1. Top Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.topBar)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                ) {
                    OmsCircleIconButton(
                        icon = OmsIcons.ArrowLeft,
                        contentDescription = "关闭",
                        onClick = onDismiss
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "远程操控 · $effectiveMacName",
                            color = palette.textPrimary,
                            fontSize = 16.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val tierLabel = when (activeTier) {
                            "LAN_WS" -> "局域网 120Hz"
                            "WAN_WEBRTC" -> "跨网远程在线"
                            "BT_RFCOMM" -> "近场蓝牙在线"
                            else -> "离线状态 · 等待电脑上线"
                        }
                        val frontAppSuffix = if (macConnected) {
                            if (status?.screenLocked == true) {
                                " · 已锁屏"
                            } else {
                                " · 前台：${status?.frontmostApp?.ifBlank { "桌面" } ?: "桌面"}"
                            }
                        } else ""
                        Text(
                            text = "$tierLabel$frontAppSuffix",
                            color = when {
                                !macConnected -> palette.warning
                                status?.screenLocked == true -> palette.warning
                                else -> palette.success
                            },
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OmsSecondaryButton(
                        label = if (status?.unlockPasswordConfigured == true) "钥匙串已配" else "配解锁密码",
                        icon = OmsIcons.Shield,
                        height = 32.dp,
                        onClick = {
                            passwordInput = ""
                            showKeychainPasswordDialog = true
                        }
                    )
                    OmsCircleIconButton(
                        icon = OmsIcons.Refresh,
                        contentDescription = "刷新状态",
                        size = 34.dp,
                        iconSize = 16.dp,
                        onClick = { coordinator.refreshMacControlStatus(refreshApps = true) }
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(palette.hairline))

            // 2. Smart Context Banners (Screen Locked 1-Tap Unlock & Accessibility Grant)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                AnimatedVisibility(
                    visible = macConnected && status?.screenLocked == true,
                    enter = expandVertically(tween(170)) + fadeIn(tween(140)),
                    exit = shrinkVertically(tween(140)) + fadeOut(tween(110))
                ) {
                    val lockShape = RoundedCornerShape(14.dp)
                    Row(
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .fillMaxWidth()
                            .omsBounceClick(
                                enabled = !isBusy,
                                scaleDown = 0.98f,
                                shape = lockShape,
                                onClick = ::requestUnlockOrConfigure
                            )
                            .background(palette.warningSoft, lockShape)
                            .border(0.75.dp, palette.warning.copy(alpha = 0.55f), lockShape)
                            .padding(horizontal = 13.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                        ) {
                            Icon(
                                imageVector = OmsIcons.Lock,
                                contentDescription = null,
                                tint = palette.warning,
                                modifier = Modifier.size(17.dp)
                            )
                            Column {
                                Text(
                                    text = "🔒 Mac 屏幕当前已锁定 · 点此秒解屏幕",
                                    color = palette.textPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (status?.unlockPasswordConfigured == true) {
                                        if (activeTier == "WAN_WEBRTC") "跨网连接将自动核验手机指纹/面容" else "局域网直连一键唤醒并输入钥匙串凭据"
                                    } else {
                                        "首次使用请点此安全配置 Mac 解锁密码"
                                    },
                                    color = palette.textSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Icon(
                            imageVector = OmsIcons.ChevronRight,
                            contentDescription = null,
                            tint = palette.warning,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                AnimatedVisibility(
                    visible = macConnected && status != null && status?.accessibilityTrusted == false,
                    enter = expandVertically(tween(170)) + fadeIn(tween(140)),
                    exit = shrinkVertically(tween(140)) + fadeOut(tween(110))
                ) {
                    val axShape = RoundedCornerShape(14.dp)
                    Row(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .clip(axShape)
                            .background(palette.warning.copy(alpha = 0.12f))
                            .border(0.65.dp, palette.warning.copy(alpha = 0.45f), axShape)
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = "需开启「青简控机助手」辅助功能",
                                color = palette.textPrimary,
                                fontSize = 12.2.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "触控指针、快捷键与远程解锁需在 Mac 勾选一次即永久生效",
                                color = palette.textSecondary,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                        OmsSecondaryButton(
                            label = "电脑弹出授权",
                            height = 29.dp,
                            onClick = { triggerMacAction("request_accessibility") }
                        )
                    }
                }

                // 3. 4-Mode Segmented Selector (`触控板 · 激光飞鼠 · 键盘语音 · 系统应用`)
                OmsSlidingSegmentedBar(
                    items = MacRemoteTab.entries.map { tab ->
                        OmsSegmentItem(value = tab, label = tab.label)
                    },
                    selected = currentTab,
                    onSelect = { chosen ->
                        if (chosen != currentTab) {
                            VoiceRecorderHelper.cancelRecording()
                            coordinator.resetMacHidState()
                            coordinator.vibrateTick()
                            currentTab = chosen
                        }
                    },
                    height = 38.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 4.dp)
                )
            }

            // 4. Active Mode Stage
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Crossfade(
                    targetState = currentTab,
                    animationSpec = tween(durationMillis = 160),
                    label = "macRemoteModeCrossfade"
                ) { mode ->
                    when (mode) {
                        MacRemoteTab.TRACKPAD -> MacTrackpadModeView(
                            coordinator = coordinator,
                            macConnected = macConnected,
                            onJumpToKeyboardVoice = {
                                VoiceRecorderHelper.cancelRecording()
                                coordinator.resetMacHidState()
                                coordinator.vibrateTick()
                                keyboardFocusNonce = System.nanoTime()
                                currentTab = MacRemoteTab.KEYBOARD_VOICE
                            }
                        )

                        MacRemoteTab.LASER_WAND -> MacLaserAirWandView(
                            coordinator = coordinator,
                            macConnected = macConnected
                        )

                        MacRemoteTab.KEYBOARD_VOICE -> MacKeyboardAndVoiceDeckView(
                            coordinator = coordinator,
                            macConnected = macConnected,
                            focusInputNonce = keyboardFocusNonce
                        )

                        MacRemoteTab.SYSTEM_APPS -> MacSystemAndAppsDeckView(
                            status = status,
                            activeTier = activeTier,
                            macConnected = macConnected,
                            isBusy = isBusy,
                            volumeSlider = volumeSlider,
                            onVolumeSliderChange = { volumeSlider = it },
                            onVolumeSliderCommit = {
                                triggerMacAction("set_volume", value = volumeSlider.toInt().toString())
                            },
                            appSearchQuery = appSearchQuery,
                            onAppSearchQueryChange = { appSearchQuery = it },
                            filteredApps = filteredApps,
                            onTriggerAction = ::triggerMacAction,
                            onUnlockClick = ::requestUnlockOrConfigure
                        )
                    }
                }
            }
        }
    }

    if (showKeychainPasswordDialog) {
        AlertDialog(
            onDismissRequest = { showKeychainPasswordDialog = false },
            containerColor = palette.composer,
            title = {
                Text(
                    "配置 Mac 远程解锁凭据",
                    color = palette.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "密码仅通过端到端加密通道写入 Mac 本机系统钥匙串（macOS Keychain），绝不明文落盘。局域网直连时一键秒解，跨网外网连接时会自动要求验证手机指纹或面容。",
                        color = palette.textTertiary,
                        fontSize = 12.sp,
                        lineHeight = 17.5.sp
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.subtleSurface)
                            .border(0.6.dp, palette.hairline, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        if (passwordInput.isEmpty()) {
                            Text("输入这台 Mac 的锁屏登录密码…", color = palette.textTertiary, fontSize = 13.5.sp)
                        }
                        BasicTextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            textStyle = TextStyle(color = palette.textPrimary, fontSize = 14.sp),
                            cursorBrush = SolidColor(palette.accent),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val pwd = passwordInput
                    if (pwd.isNotBlank()) {
                        scope.launch {
                            val (ok, msg) = coordinator.saveMacUnlockPassword(pwd)
                            passwordInput = ""
                            if (ok) showKeychainPasswordDialog = false
                            OmsFeedbackController.show(msg, if (ok) OmsToastKind.SUCCESS else OmsToastKind.WARNING)
                        }
                    }
                }) {
                    Text("安全存入钥匙串", color = palette.accent, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                Row {
                    if (status?.unlockPasswordConfigured == true) {
                        TextButton(onClick = {
                            scope.launch {
                                coordinator.clearMacUnlockPassword()
                                showKeychainPasswordDialog = false
                                OmsFeedbackController.show("已清除钥匙串中的解锁凭据", OmsToastKind.INFO)
                            }
                        }) {
                            Text("清除已存密码", color = palette.danger)
                        }
                    }
                    TextButton(onClick = { showKeychainPasswordDialog = false }) {
                        Text("取消", color = palette.textTertiary)
                    }
                }
            }
        )
    }
}

@Composable
private fun MacSystemAndAppsDeckView(
    status: MacControlStatusUi?,
    activeTier: String,
    macConnected: Boolean,
    isBusy: Boolean,
    volumeSlider: Float,
    onVolumeSliderChange: (Float) -> Unit,
    onVolumeSliderCommit: () -> Unit,
    appSearchQuery: String,
    onAppSearchQueryChange: (String) -> Unit,
    filteredApps: List<MacInstalledAppUi>,
    onTriggerAction: (action: String, target: String, value: String) -> Unit,
    onUnlockClick: () -> Unit
) {
    val palette = OmsTheme.colors

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Live Screen & System Status Card + Primary Power Actions (`亮屏 / 解锁 / 锁屏 / 防休眠`)
        OmsSurfaceCard(
            cornerRadius = 18.dp,
            contentPadding = PaddingValues(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(if (status?.screenLocked == true) palette.warning else palette.success)
                    )
                    Text(
                        text = when {
                            !macConnected -> "电脑未连接"
                            status?.screenLocked == true -> "当前屏幕已锁定"
                            else -> "屏幕已解锁 · 前台：${status?.frontmostApp?.ifBlank { "桌面" } ?: "桌面"}"
                        },
                        color = palette.textPrimary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (status?.stayAwakeEnabled == true) {
                    Text(
                        text = "防休眠驻守中",
                        color = palette.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(palette.accentSoft)
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                MacQuickActionTile(
                    icon = OmsIcons.Sun,
                    label = "点亮屏幕",
                    subtitle = "唤醒显示器",
                    enabled = macConnected && !isBusy,
                    onClick = { onTriggerAction("wake", "", "") },
                    modifier = Modifier.weight(1f)
                )
                MacQuickActionTile(
                    icon = OmsIcons.Shield,
                    label = "远程解锁",
                    subtitle = if (activeTier == "WAN_WEBRTC") "指纹确认秒解" else "局域网秒解",
                    accentHighlight = true,
                    enabled = macConnected && !isBusy,
                    onClick = onUnlockClick,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                MacQuickActionTile(
                    icon = OmsIcons.Moon,
                    label = "立即锁屏",
                    subtitle = "离开工位锁机",
                    enabled = macConnected && !isBusy,
                    onClick = { onTriggerAction("lock", "", "") },
                    modifier = Modifier.weight(1f)
                )
                MacQuickActionTile(
                    icon = OmsIcons.Bolt,
                    label = if (status?.stayAwakeEnabled == true) "关闭防休眠" else "开启防休眠",
                    subtitle = "锁屏不断网",
                    enabled = macConnected && !isBusy,
                    onClick = {
                        val next = status?.stayAwakeEnabled != true
                        onTriggerAction("stay_awake", "", next.toString())
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 2. Volume & Media Strip
        OmsSurfaceCard(
            cornerRadius = 16.dp,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(OmsIcons.Volume, contentDescription = null, tint = palette.accent, modifier = Modifier.size(16.dp))
                    Text(
                        text = if (status?.muted == true) "Mac 音量（已静音）" else "Mac 音量 ${volumeSlider.toInt()}%",
                        color = palette.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OmsSecondaryButton(
                        label = if (status?.muted == true) "取消静音" else "静音",
                        height = 28.dp,
                        onClick = { onTriggerAction("toggle_mute", "", "") }
                    )
                    OmsSecondaryButton(
                        label = "播放/暂停",
                        icon = OmsIcons.Music,
                        height = 28.dp,
                        onClick = { onTriggerAction("media_play_pause", "", "") }
                    )
                }
            }
            Slider(
                value = volumeSlider,
                onValueChange = onVolumeSliderChange,
                onValueChangeFinished = onVolumeSliderCommit,
                valueRange = 0f..100f,
                enabled = macConnected,
                colors = SliderDefaults.colors(
                    thumbColor = palette.accent,
                    activeTrackColor = palette.accent
                )
            )
        }

        // 3. Installed Mac Applications Launcher Grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "一键启动 / 切换 Mac 应用 (${filteredApps.size})",
                color = palette.textSecondary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(palette.subtleSurface)
                .border(0.6.dp, palette.hairline, RoundedCornerShape(18.dp))
                .padding(horizontal = 12.dp)
        ) {
            Icon(OmsIcons.Search, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(14.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (appSearchQuery.isEmpty()) {
                    Text(
                        "搜索 Mac 应用（如 WeChat / Xcode / Chrome / WorkBuddy）…",
                        color = palette.textTertiary,
                        fontSize = 12.2.sp,
                        maxLines = 1
                    )
                }
                BasicTextField(
                    value = appSearchQuery,
                    onValueChange = onAppSearchQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(color = palette.textPrimary, fontSize = 13.sp),
                    cursorBrush = SolidColor(palette.accent),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (appSearchQuery.isNotEmpty()) {
                OmsPrimaryButton(
                    label = "直接打开",
                    height = 26.dp,
                    onClick = { onTriggerAction("open_app", appSearchQuery.trim(), "") }
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            items(items = filteredApps, key = { it.bundlePath.ifBlank { it.name } }) { appItem ->
                OmsSurfaceCard(
                    cornerRadius = 14.dp,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    onClick = { onTriggerAction("open_app", appItem.name, "") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.5.dp)
                                    .clip(CircleShape)
                                    .background(if (appItem.running) palette.success else palette.textTertiary.copy(alpha = 0.35f))
                            )
                            Text(
                                text = appItem.name,
                                color = palette.textPrimary,
                                fontSize = 13.2.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (appItem.running) {
                            Text("运行中", color = palette.success, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MacQuickActionTile(
    icon: ImageVector,
    label: String,
    subtitle: String,
    enabled: Boolean,
    accentHighlight: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = OmsTheme.colors
    OmsSurfaceCard(
        containerColor = if (accentHighlight) palette.accentSoft else palette.subtleSurface,
        borderColor = if (accentHighlight) palette.accent.copy(alpha = 0.45f) else palette.hairline,
        cornerRadius = 14.dp,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 11.dp),
        onClick = { if (enabled) onClick() },
        modifier = modifier
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (accentHighlight) palette.accent else palette.textPrimary,
                modifier = Modifier.size(18.dp)
            )
            Column {
                Text(
                    text = label,
                    color = if (enabled) palette.textPrimary else palette.textTertiary,
                    fontSize = 13.8.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    color = palette.textTertiary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
