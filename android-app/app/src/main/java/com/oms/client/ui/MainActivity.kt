package com.oms.client.ui

import android.Manifest
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.oms.client.OmsApplication
import com.oms.client.capability.NotificationExecutor
import com.oms.client.service.OmsCoreForegroundService
import com.oms.client.ui.cockpit.OmsCockpitRootScreen
import com.oms.client.ui.theme.OmsFeedbackController
import com.oms.client.ui.theme.OmsTheme
import com.oms.client.ui.theme.OmsThemeController
import com.oms.client.ui.theme.OmsThemeRoot
import com.oms.client.ui.theme.OmsToastKind

/**
 * 青简·咫尺 v3.0 Flagship Host Activity:
 * Full Edge-to-Edge WindowInsets, 120Hz IME keyboard synchronization,
 * dynamic Light/Dark system bar icon contrast, and background dual-brain service host.
 */
class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        OmsApplication.instance?.transportClient?.startAutoConnect()
    }

    private fun applyLockscreenDisplayPolicy(incoming: Intent?) {
        val isUrgentDisplay = incoming?.action == "com.oms.client.ACTION_DISPLAY_URGENT_NOTIFICATION"
        runCatching {
            setShowWhenLocked(isUrgentDisplay)
            setTurnScreenOn(isUrgentDisplay)
        }
    }

    @Suppress("DEPRECATION")
    private fun configureEdgeToEdgeWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OmsThemeController.ensureLoaded(this)
        configureEdgeToEdgeWindow()
        applyLockscreenDisplayPolicy(intent)

        if (intent?.getBooleanExtra(NotificationExecutor.EXTRA_STOP_TTS, false) == true) {
            NotificationExecutor.stopSpeaking(this)
        }
        intent?.getStringExtra("alarm_label")?.takeIf { it.isNotBlank() }?.let { label ->
            OmsFeedbackController.show("系统闹钟需手动确认：$label", OmsToastKind.WARNING, durationMs = 3500L)
            intent?.removeExtra("alarm_label")
        }

        requestRuntimePermissionsIfNeeded()
        startForegroundCoreService()

        setContent {
            OmsThemeRoot(
                onDarkResolved = { isDark ->
                    runCatching {
                        WindowCompat.getInsetsController(window, window.decorView).apply {
                            isAppearanceLightStatusBars = !isDark
                            isAppearanceLightNavigationBars = !isDark
                        }
                    }
                }
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = OmsTheme.colors.canvas
                ) {
                    OmsCockpitRootScreen()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyLockscreenDisplayPolicy(intent)
        if (intent.getBooleanExtra(NotificationExecutor.EXTRA_STOP_TTS, false)) {
            NotificationExecutor.stopSpeaking(this)
        }
        intent.getStringExtra("alarm_label")?.takeIf { it.isNotBlank() }?.let { label ->
            OmsFeedbackController.show("系统闹钟需手动确认：$label", OmsToastKind.WARNING, durationMs = 3500L)
            intent.removeExtra("alarm_label")
        }
    }

    override fun onResume() {
        super.onResume()
        if (intent?.getBooleanExtra(NotificationExecutor.EXTRA_STOP_TTS, false) == true) {
            NotificationExecutor.stopSpeaking(this)
            intent?.removeExtra(NotificationExecutor.EXTRA_STOP_TTS)
        }
        OmsApplication.instance?.transportClient?.startAutoConnect()
        OmsApplication.instance?.transportClient?.onAppForegroundResume()
        OmsApplication.instance?.cockpitCoordinator?.onAppForeground()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) &&
            NotificationExecutor.isSpeakingFlow.value &&
            NotificationExecutor.isHardwareStopActive
        ) {
            NotificationExecutor.stopSpeaking(this)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun requestRuntimePermissionsIfNeeded() {
        val perms = mutableListOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun startForegroundCoreService() {
        try {
            val svcIntent = Intent(this, OmsCoreForegroundService::class.java)
            ContextCompat.startForegroundService(this, svcIntent)
        } catch (_: Throwable) {
        }
    }
}
