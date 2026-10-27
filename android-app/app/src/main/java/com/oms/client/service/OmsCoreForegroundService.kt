package com.oms.client.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.oms.client.OmsApplication
import com.oms.client.R
import com.oms.client.capability.NotificationExecutor
import com.oms.client.otp.SmsContentObserver
import com.oms.client.share.ClipboardCatcherActivity
import com.oms.client.share.ScreenshotPillController
import com.oms.client.share.VoiceIdeaActivity
import com.oms.client.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Module 04: Persistent Foreground Keep-Alive Service for Xiaomi HyperOS / MIUI
 * Maintains multi-tier connection (LAN / Bluetooth / WAN), watches MediaStore screenshots,
 * and monitors SMS inbox changes for real-time OTP sync.
 */
class OmsCoreForegroundService : Service() {
    companion object {
        private const val FG_NOTIFICATION_ID = 19527
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var screenshotWatcher: ScreenshotPillController? = null
    private var smsObserver: SmsContentObserver? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private fun ensureWifiLockAcquired() {
        try {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(mode, "OneMoreStep::LanKeepAliveLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        } catch (_: Throwable) {
        }
    }

    override fun onCreate() {
        super.onCreate()
        NotificationExecutor.ensureChannelsAndTts(this)
        safeStartForeground("正在连接 Mac...")
        ensureWifiLockAcquired()

        screenshotWatcher = ScreenshotPillController(this).also {
            it.startWatching()
        }
        smsObserver = SmsContentObserver(this).also {
            it.startWatching()
        }

        val client = OmsApplication.instance?.transportClient
        client?.startAutoConnect()

        if (client != null) {
            serviceScope.launch {
                client.activeTierFlow.collect { tier ->
                    updateNotificationForTier(tier)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureWifiLockAcquired()
        val client = OmsApplication.instance?.transportClient
        client?.startAutoConnect()
        val tier = client?.activeTierFlow?.value ?: "DISCONNECTED"
        updateNotificationForTier(tier)
        return START_STICKY
    }

    private fun statusTextForTier(tier: String): String = when (tier) {
        "LAN_WS" -> "🟢 Mac 已连接 (局域网高速直连)"
        "BT_RFCOMM" -> "🔵 Mac 已连接 (近场蓝牙直连)"
        "WAN_WEBRTC" -> "🟣 Mac 已连接 (远程跨网通道)"
        else -> "⏳ 等待连接 Mac (支持离线暂存)"
    }

    private fun safeStartForeground(statusText: String) {
        try {
            val notif = buildStatusNotification(statusText)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    FG_NOTIFICATION_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(FG_NOTIFICATION_ID, notif)
            }
        } catch (_: Throwable) {
        }
    }

    private fun updateNotificationForTier(tier: String) {
        val statusText = statusTextForTier(tier)
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(FG_NOTIFICATION_ID, buildStatusNotification(statusText))
        } catch (_: Throwable) {
        }
    }

    private fun buildStatusNotification(statusText: String) =
        NotificationCompat.Builder(this, NotificationExecutor.CHANNEL_SILENT)
            .setSmallIcon(R.drawable.ic_stat_oms)
            .setContentTitle("青简·咫尺 · OneMoreStep")
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    1,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(
                android.R.drawable.ic_menu_edit,
                "📋 发剪贴板",
                PendingIntent.getActivity(
                    this,
                    2,
                    Intent(this, ClipboardCatcherActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(
                android.R.drawable.ic_btn_speak_now,
                "🎙️ 语音闪念",
                PendingIntent.getActivity(
                    this,
                    3,
                    Intent(this, VoiceIdeaActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    override fun onDestroy() {
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (_: Throwable) {
        }
        wifiLock = null
        serviceScope.cancel()
        screenshotWatcher?.stopWatching()
        smsObserver?.stopWatching()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
