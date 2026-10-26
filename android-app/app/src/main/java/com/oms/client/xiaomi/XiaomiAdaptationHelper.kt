package com.oms.client.xiaomi

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.oms.client.agent.OmsAccessibilityService
import com.oms.client.otp.OmsNotificationListenerService
import org.json.JSONObject

data class XiaomiHealthChecklist(
    val isXiaomiDevice: Boolean,
    val backgroundPopupAllowed: Boolean,   // OpCode 10021 (MIUI/HyperOS 后台弹出界面)
    val lockScreenDisplayAllowed: Boolean, // OpCode 10008 (MIUI/HyperOS 锁屏显示)
    val notificationSmsAllowed: Boolean = true, // OpCode 10018 (MIUI/HyperOS 读取通知类短信/验证码)
    val batteryUnrestricted: Boolean,
    val overlayAllowed: Boolean,
    val accessibilityEnabled: Boolean,
    val notificationListenerEnabled: Boolean = false, // 系统「通知使用权」
    val smsReadGranted: Boolean = false, // RECEIVE_SMS & READ_SMS
    val calendarGranted: Boolean,
    val mediaReadGranted: Boolean,
    val recordAudioGranted: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("xiaomiBackgroundPopupAllowed", backgroundPopupAllowed)
        put("xiaomiLockScreenDisplayAllowed", lockScreenDisplayAllowed)
        put("xiaomiNotificationSmsAllowed", notificationSmsAllowed)
        put("batteryOptimizationIgnored", batteryUnrestricted)
        put("overlayAllowed", overlayAllowed)
        put("accessibilityEnabled", accessibilityEnabled)
        put("notificationListenerEnabled", notificationListenerEnabled)
        put("smsReadGranted", smsReadGranted)
        put("calendarGranted", calendarGranted)
        put("mediaReadGranted", mediaReadGranted)
        put("recordAudioGranted", recordAudioGranted)
    }
}

/**
 * Module 04: Xiaomi HyperOS / MIUI Specific Permission Inspector & One-Tap Settings Jumper
 */
object XiaomiAdaptationHelper {
    private const val OP_BACKGROUND_START_ACTIVITY = 10021
    private const val OP_SHOW_WHEN_LOCKED = 10008
    private const val OP_READ_NOTIFICATION_SMS = 10018

    fun isXiaomiRom(): Boolean {
        val brand = Build.BRAND.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        return brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") ||
            manufacturer.contains("xiaomi")
    }

    /**
     * Reflectively query MIUI/HyperOS custom AppOps (10021 = Background Popup, 10008 = LockScreen Display)
     */
    fun checkMiuiCustomOp(context: Context, opCode: Int): Boolean {
        if (!isXiaomiRom()) return true
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val method = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            val mode = method.invoke(appOps, opCode, Process.myUid(), context.packageName) as Int
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            true
        }
    }

    fun inspectAll(context: Context): XiaomiHealthChecklist {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryUnrestricted = pm.isIgnoringBatteryOptimizations(context.packageName)
        val overlayAllowed = Settings.canDrawOverlays(context)
        val accessibilityEnabled = OmsAccessibilityService.instance != null || isAccessibilityEnabledInSettings(context)

        val calendarGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED

        val mediaPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val mediaGranted = ContextCompat.checkSelfPermission(context, mediaPerm) == PackageManager.PERMISSION_GRANTED
        val audioGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val smsGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED

        val notifListenerEnabled = isNotificationListenerEnabled(context)

        return XiaomiHealthChecklist(
            isXiaomiDevice = isXiaomiRom(),
            backgroundPopupAllowed = checkMiuiCustomOp(context, OP_BACKGROUND_START_ACTIVITY),
            lockScreenDisplayAllowed = checkMiuiCustomOp(context, OP_SHOW_WHEN_LOCKED),
            notificationSmsAllowed = checkMiuiCustomOp(context, OP_READ_NOTIFICATION_SMS),
            batteryUnrestricted = batteryUnrestricted,
            overlayAllowed = overlayAllowed,
            accessibilityEnabled = accessibilityEnabled,
            notificationListenerEnabled = notifListenerEnabled,
            smsReadGranted = smsGranted,
            calendarGranted = calendarGranted,
            mediaReadGranted = mediaGranted,
            recordAudioGranted = audioGranted
        )
    }

    fun isNotificationListenerEnabled(context: Context): Boolean {
        if (OmsNotificationListenerService.isConnected) return true
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return enabledListeners.contains(context.packageName)
    }

    private fun isAccessibilityEnabledInSettings(context: Context): Boolean {
        val expected = "${context.packageName}/${OmsAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(expected)
    }

    /**
     * Open Xiaomi HyperOS / MIUI specific permission editor (for 后台弹出界面 & 锁屏显示)
     */
    fun openXiaomiPermissionEditor(context: Context) {
        try {
            val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                putExtra("extra_pkgname", context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Throwable) {
            val fallback = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(fallback)
        }
    }

    /**
     * Open Xiaomi Security Center Autostart management page
     */
    fun openXiaomiAutostartSettings(context: Context) {
        try {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Throwable) {
            openXiaomiPermissionEditor(context)
        }
    }

    fun openBatteryUnrestrictedPrompt(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Throwable) {
            val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(fallback)
        }
    }

    fun openOverlayPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openNotificationListenerSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Throwable) {
            openXiaomiPermissionEditor(context)
        }
    }
}
