package com.oms.client.capability

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.oms.client.OmsApplication
import com.oms.client.agent.OmsAccessibilityService
import com.oms.core.ai.ProposedPhoneToolAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PhoneToolExecResult(
    val ok: Boolean,
    val message: String,
    val needsPermissions: List<String> = emptyList()
)

data class ResolvedPhoneContact(
    val displayName: String,
    val number: String,
    val simHint: String = ""
)

/**
 * Unified Phone & Cross-Device OS Super-Tool Executor for the Personal Assistant ("随身助理"):
 * - Zero-click execution for safe native tools (launch_app, open_in_browser, navigate_map, set_volume, clipboard_write, open_system_settings, phone_gui_action)
 * - Real ContactsContract lookup + direct TelecomManager / ACTION_CALL dialing once user confirms target
 * - Remote Mac OS Control (`mac_control`: wake, lock, LAN instant unlock / WAN biometric unlock, open_app, volume, stay_awake)
 */
object PhoneToolExecutor {

    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun isLikelyRawPhoneNumber(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        val digits = trimmed.count { it.isDigit() }
        val letters = trimmed.count { it.isLetter() }
        return digits >= 3 && letters == 0
    }

    fun requiredPermissionsForAction(context: Context, action: ProposedPhoneToolAction): List<String> {
        if (action.tool != "phone_call") return emptyList()
        val missing = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.CALL_PHONE)
        }
        if (!isLikelyRawPhoneNumber(action.target) &&
            OmsApplication.instance?.edgeStore?.resolveContact(action.target) == null &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.READ_CONTACTS)
        }
        return missing
    }

    fun resolveContactCandidates(context: Context, rawTarget: String): List<ResolvedPhoneContact> {
        val cleanQuery = rawTarget.trim()
        if (cleanQuery.isEmpty()) return emptyList()

        // 1. Direct phone number
        if (isLikelyRawPhoneNumber(cleanQuery)) {
            val digits = cleanQuery.replace(Regex("""[^\d+*#]"""), "")
            if (digits.isNotBlank()) {
                return listOf(ResolvedPhoneContact(displayName = digits, number = digits))
            }
        }

        // 2. Synced Mac quick-contact alias (e.g. "老婆", "爸", etc.)
        OmsApplication.instance?.edgeStore?.resolveContact(cleanQuery)?.let { (num, sim) ->
            val cleanNum = num.replace(Regex("""[^\d+*#]"""), "")
            if (cleanNum.isNotBlank()) {
                return listOf(ResolvedPhoneContact(displayName = cleanQuery, number = cleanNum, simHint = sim))
            }
        }

        // 3. Real Android Phone Contacts (`ContactsContract.CommonDataKinds.Phone`)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }

        val exactMatches = mutableListOf<ResolvedPhoneContact>()
        val fuzzyMatches = mutableListOf<ResolvedPhoneContact>()
        val seenNumbers = hashSetOf<String>()
        val normTarget = cleanQuery.replace(Regex("""[\s\-_()（）]+"""), "").lowercase(Locale.ROOT)

        runCatching {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.STARRED} DESC, ${ContactsContract.CommonDataKinds.Phone.TIMES_CONTACTED} DESC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = (if (nameIdx >= 0) cursor.getString(nameIdx) else null)?.trim().orEmpty()
                    val rawNum = (if (numIdx >= 0) cursor.getString(numIdx) else null)?.trim().orEmpty()
                    val num = rawNum.replace(Regex("""[^\d+*#]"""), "")
                    if (name.isBlank() || num.isBlank()) continue
                    val normName = name.replace(Regex("""[\s\-_()（）]+"""), "").lowercase(Locale.ROOT)
                    val dedupKey = "$normName|$num"
                    if (!seenNumbers.add(dedupKey)) continue

                    when {
                        normName == normTarget -> exactMatches.add(ResolvedPhoneContact(name, num))
                        normTarget.isNotEmpty() && (normName.contains(normTarget) || normTarget.contains(normName)) ->
                            fuzzyMatches.add(ResolvedPhoneContact(name, num))
                    }
                }
            }
        }

        return if (exactMatches.isNotEmpty()) exactMatches else fuzzyMatches.take(5)
    }

    suspend fun execute(
        context: Context,
        action: ProposedPhoneToolAction,
        onOpenInWebAppHub: ((url: String, title: String) -> Unit)? = null
    ): PhoneToolExecResult {
        return when (action.tool) {
            "launch_app" -> {
                val res = AppLauncherHelper.launchApp(context, action.target)
                if (res.ok) {
                    PhoneToolExecResult(true, "已打开 ${res.appLabel}")
                } else {
                    PhoneToolExecResult(false, res.error ?: "未能找到或打开「${action.target}」")
                }
            }

            "phone_call" -> executePhoneCall(context, action.target, speaker = action.extra == "speaker")

            "send_sms" -> executeDraftSms(context, action.target, action.extra)

            "open_in_browser" -> {
                val url = action.target.trim()
                if (onOpenInWebAppHub != null && url.startsWith("http", ignoreCase = true)) {
                    withContext(Dispatchers.Main) {
                        onOpenInWebAppHub(url, action.extra.ifBlank { action.title })
                    }
                    PhoneToolExecResult(true, "已在免 VPN 应用舱打开网页")
                } else {
                    val (ok, err) = AppLauncherHelper.openUrl(context, url)
                    PhoneToolExecResult(ok, if (ok) "已在浏览器打开" else (err ?: "打开网页失败"))
                }
            }

            "navigate_map" -> executeNavigateMap(context, action.target)

            "set_volume" -> executeSetVolume(context, action.target, action.extra)

            "clipboard_write" -> withContext(Dispatchers.Main) {
                runCatching {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("oms_assistant", action.target))
                    PhoneToolExecResult(true, "已写入手机剪贴板")
                }.getOrElse { PhoneToolExecResult(false, "写入剪贴板失败：${it.message}") }
            }

            "open_system_settings" -> executeOpenSettings(context, action.target)

            "phone_gui_action" -> executeLocalGuiAction(action.target)

            "mac_control" -> executeMacControlAction(context, action.target, action.extra)

            else -> PhoneToolExecResult(false, "暂不支持工具：${action.tool}")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun executePhoneCall(
        context: Context,
        rawTarget: String,
        speaker: Boolean
    ): PhoneToolExecResult = withContext(Dispatchers.IO) {
        val candidates = resolveContactCandidates(context, rawTarget)
        val primary = candidates.firstOrNull()
        val number = primary?.number ?: rawTarget.replace(Regex("""[^\d+*#]"""), "")
        val displayLabel = primary?.displayName?.takeIf { it != number }?.let { "$it ($number)" } ?: number
        val simHint = primary?.simHint.orEmpty()

        if (number.isBlank()) {
            val hasContactsPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
            val msg = if (!hasContactsPerm) {
                "请先授予通讯录读取权限，以便查找「$rawTarget」的电话号码"
            } else {
                "手机通讯录中未找到「$rawTarget」的号码"
            }
            return@withContext PhoneToolExecResult(false, msg)
        }

        val hasCallPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        // Path 1 (Preferred & Most Reliable): Direct system call via TelecomManager / ACTION_CALL
        if (hasCallPerm) {
            val placedDirectly = withContext(Dispatchers.Main) {
                val uri = Uri.fromParts("tel", number, null)
                var placed = false
                runCatching {
                    val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                    if (tm != null && simHint.isBlank()) {
                        val extras = Bundle().apply {
                            if (speaker) {
                                putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true)
                            }
                        }
                        tm.placeCall(uri, extras)
                        placed = true
                    }
                }
                if (!placed) {
                    runCatching {
                        val callIntent = Intent(Intent.ACTION_CALL, uri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            if (speaker) {
                                putExtra(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true)
                            }
                        }
                        context.startActivity(callIntent)
                        placed = true
                    }
                }
                if (placed && speaker) {
                    bgScope.launch {
                        delay(1500L)
                        runCatching {
                            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                            @Suppress("DEPRECATION")
                            am?.isSpeakerphoneOn = true
                        }
                    }
                }
                placed
            }
            if (placedDirectly) {
                return@withContext PhoneToolExecResult(true, "正在直接呼出：$displayLabel")
            }
        }

        // Path 2: Accessibility-assisted dialer call (supports explicit SIM selection)
        val a11y = OmsAccessibilityService.instance
        if (a11y != null) {
            val params = JSONObject().apply {
                put("number", number)
                put("speaker", speaker)
                if (simHint.isNotBlank()) put("sim", simHint)
            }
            val res = runCatching { a11y.performPhoneCall(params) { true } }.getOrNull()
            if (res?.optBoolean("ok", false) == true) {
                return@withContext PhoneToolExecResult(true, "正在呼出：$displayLabel")
            }
        }

        // Path 3: Fallback pre-filled system dialer
        return@withContext withContext(Dispatchers.Main) {
            runCatching {
                val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                PhoneToolExecResult(true, "已打开拨号盘：$displayLabel（授予电话权限后可直接呼出）")
            }.getOrElse { PhoneToolExecResult(false, "拨号失败：${it.message}") }
        }
    }

    private suspend fun executeLocalGuiAction(encodedTarget: String): PhoneToolExecResult {
        val svc = OmsAccessibilityService.instance
            ?: return PhoneToolExecResult(false, "请先在系统设置中开启「青简·咫尺」无障碍服务以支持跨 App 自动化")

        val sepIdx = encodedTarget.indexOf('|')
        val rawAct = (if (sepIdx >= 0) encodedTarget.substring(0, sepIdx) else encodedTarget).trim().lowercase(Locale.ROOT)
        val query = (if (sepIdx >= 0) encodedTarget.substring(sepIdx + 1) else "").trim()

        return runCatching {
            when (rawAct) {
                "back", "home", "recents", "notifications", "quick_settings", "scroll_down", "scroll_up" -> {
                    val r = svc.executeGuiAction(
                        JSONObject().put("action", rawAct).put("deadlineMs", System.currentTimeMillis() + 8_000L)
                    ) { true }
                    val ok = r.optBoolean("ok", false)
                    PhoneToolExecResult(ok, if (ok) "已执行手机界面动作：$rawAct" else "界面动作未生效")
                }

                "tap_text", "click", "input_text" -> {
                    val obs = svc.observeScreen(includeScreenshot = false, somAnnotation = false) { _, _, _ -> null }
                    val obsId = obs.optString("observationId", "")
                    val nodes = obs.optJSONArray("nodes")
                    var matchedNodeId = ""
                    if (nodes != null) {
                        for (i in 0 until nodes.length()) {
                            val n = nodes.optJSONObject(i) ?: continue
                            val label = n.optString("label", "")
                            val text = n.optString("text", "")
                            if (rawAct == "input_text") {
                                if (n.optBoolean("editable", false)) {
                                    matchedNodeId = n.optString("id", "")
                                    if (query.isEmpty() || label.contains(query, true)) break
                                }
                            } else if (query.isNotEmpty() && (label.contains(query, true) || text.contains(query, true))) {
                                matchedNodeId = n.optString("id", "")
                                if (n.optBoolean("clickable", false)) break
                            }
                        }
                    }
                    if (matchedNodeId.isBlank()) {
                        return PhoneToolExecResult(false, "当前屏幕未找到匹配「$query」的可操作控件")
                    }
                    val guiAct = if (rawAct == "input_text") "input_text" else "click"
                    val req = JSONObject()
                        .put("action", guiAct)
                        .put("observationId", obsId)
                        .put("targetNodeId", matchedNodeId)
                        .put("text", query)
                        .put("deadlineMs", System.currentTimeMillis() + 10_000L)
                    val r = svc.executeGuiAction(req) { true }
                    val ok = r.optBoolean("ok", false)
                    PhoneToolExecResult(ok, if (ok) "已完成界面操作：$query" else "点击/输入未成功")
                }

                else -> PhoneToolExecResult(false, "不支持的界面动作：$rawAct")
            }
        }.getOrElse { err ->
            PhoneToolExecResult(false, "无障碍操控受限：${err.message}")
        }
    }

    private suspend fun executeMacControlAction(
        context: Context,
        encodedTarget: String,
        valueRaw: String
    ): PhoneToolExecResult {
        val app = OmsApplication.instance ?: return PhoneToolExecResult(false, "应用实例未就绪")
        val tc = app.transportClient
        val tier = tc.activeTierFlow.value
        if (tier == "DISCONNECTED") {
            return PhoneToolExecResult(false, "当前未连接到 Mac，请确认电脑在线后再试")
        }

        val sepIdx = encodedTarget.indexOf('|')
        val act = (if (sepIdx >= 0) encodedTarget.substring(0, sepIdx) else encodedTarget).trim()
        val target = (if (sepIdx >= 0) encodedTarget.substring(sepIdx + 1) else "").trim()

        var biometricOk = false
        if (act == "unlock" && tier == "WAN_WEBRTC") {
            val (passed, reason) = BiometricAuthHelper.authenticateForSensitiveRemoteAction(context)
            if (!passed) {
                return PhoneToolExecResult(false, reason)
            }
            biometricOk = true
        }

        return withContext(Dispatchers.IO) {
            val params = JSONObject().apply {
                put("action", act)
                if (target.isNotBlank()) put("target", target)
                put("transportTierHint", tier)
                if (biometricOk) put("biometricVerified", true)
                val intVal = valueRaw.toIntOrNull()
                val boolVal = when {
                    valueRaw.equals("true", true) || valueRaw.equals("on", true) -> true
                    valueRaw.equals("false", true) || valueRaw.equals("off", true) -> false
                    else -> null
                }
                if (intVal != null) put("value", intVal)
                else if (boolVal != null) put("value", boolVal)
            }
            val resp = tc.callHostRpc("mac.control.execute", params, timeoutMs = 12_000L)
                ?: return@withContext PhoneToolExecResult(false, "Mac 控制请求超时")
            if (resp.optBoolean("requiresBiometric", false) && !biometricOk) {
                val (passed, reason) = BiometricAuthHelper.authenticateForSensitiveRemoteAction(context)
                if (!passed) return@withContext PhoneToolExecResult(false, reason)
                params.put("biometricVerified", true)
                val retry = tc.callHostRpc("mac.control.execute", params, timeoutMs = 12_000L)
                    ?: return@withContext PhoneToolExecResult(false, "Mac 解锁响应超时")
                return@withContext PhoneToolExecResult(
                    retry.optBoolean("ok", false),
                    retry.optString("message", "已发送解锁指令")
                )
            }
            PhoneToolExecResult(
                resp.optBoolean("ok", false),
                resp.optString("message", if (resp.optBoolean("ok")) "Mac 操作已完成" else "Mac 操作未成功")
            )
        }
    }

    private suspend fun executeDraftSms(
        context: Context,
        rawTarget: String,
        body: String
    ): PhoneToolExecResult {
        val resolved = resolveContactCandidates(context, rawTarget).firstOrNull()
        val number = (resolved?.number ?: rawTarget).replace(Regex("""[^\d+*#]"""), "")
        return withContext(Dispatchers.Main) {
            runCatching {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
                    putExtra("sms_body", body)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                PhoneToolExecResult(true, "已打开短信草稿，确认即可发送")
            }.getOrElse { PhoneToolExecResult(false, "打开短信失败：${it.message}") }
        }
    }

    private suspend fun executeNavigateMap(context: Context, destination: String): PhoneToolExecResult {
        return withContext(Dispatchers.Main) {
            runCatching {
                val encoded = Uri.encode(destination)
                val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(geoIntent)
                PhoneToolExecResult(true, "已唤起地图导航：$destination")
            }.getOrElse { PhoneToolExecResult(false, "打开地图失败：${it.message}") }
        }
    }

    private suspend fun executeSetVolume(
        context: Context,
        streamName: String,
        valueOrAction: String
    ): PhoneToolExecResult = withContext(Dispatchers.Main) {
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val stream = when (streamName.lowercase(Locale.ROOT)) {
                "ring", "ringer" -> AudioManager.STREAM_RING
                "alarm" -> AudioManager.STREAM_ALARM
                "notification", "notify" -> AudioManager.STREAM_NOTIFICATION
                else -> AudioManager.STREAM_MUSIC
            }
            val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
            val cur = am.getStreamVolume(stream)
            val pct = valueOrAction.toIntOrNull()
            val targetVol = when {
                pct != null -> ((pct.coerceIn(0, 100) / 100f) * max).toInt()
                valueOrAction.equals("mute", ignoreCase = true) -> 0
                valueOrAction.equals("down", ignoreCase = true) -> (cur - (max / 6).coerceAtLeast(1)).coerceAtLeast(0)
                else -> (cur + (max / 6).coerceAtLeast(1)).coerceAtMost(max)
            }
            am.setStreamVolume(stream, targetVol, AudioManager.FLAG_SHOW_UI)
            val newPct = ((targetVol * 100f) / max).toInt()
            PhoneToolExecResult(true, "已将音量调整至 $newPct%")
        }.getOrElse { PhoneToolExecResult(false, "调节音量失败：${it.message}") }
    }

    private suspend fun executeOpenSettings(context: Context, section: String): PhoneToolExecResult =
        withContext(Dispatchers.Main) {
            runCatching {
                val action = when (section.lowercase(Locale.ROOT)) {
                    "wifi", "wlan" -> Settings.ACTION_WIFI_SETTINGS
                    "bluetooth", "bt" -> Settings.ACTION_BLUETOOTH_SETTINGS
                    "display", "screen" -> Settings.ACTION_DISPLAY_SETTINGS
                    "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS
                    "apps", "app" -> Settings.ACTION_APPLICATION_SETTINGS
                    "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                    "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                    else -> Settings.ACTION_SETTINGS
                }
                val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(intent)
                PhoneToolExecResult(true, "已打开系统设置")
            }.getOrElse { PhoneToolExecResult(false, "打开系统设置失败：${it.message}") }
        }

    fun readUpcomingCalendarSummary(context: Context, horizonHours: Int = 48): String {
        return runCatching {
            val now = System.currentTimeMillis()
            val startOfToday = now - 2 * 3600_000L
            val endMs = now + horizonHours * 3600_000L
            val res = CalendarExecutor.listEvents(
                context,
                JSONObject().put("startMs", startOfToday).put("endMs", endMs)
            )
            val arr = res.optJSONArray("events")
            if (arr == null || arr.length() == 0) {
                "【手机真实日历（未来 ${horizonHours} 小时）】：当前无已安排日程，时段完全空闲。"
            } else {
                val sdf = SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA)
                buildString {
                    appendLine("【手机真实日历（未来 ${horizonHours} 小时，共 ${arr.length()} 条）】：")
                    for (i in 0 until arr.length().coerceAtMost(20)) {
                        val e = arr.optJSONObject(i) ?: continue
                        val title = e.optString("title", "未命名日程")
                        val b = sdf.format(Date(e.optLong("startMs")))
                        val end = sdf.format(Date(e.optLong("endMs")))
                        val loc = e.optString("location", "").trim()
                        append("- $b ~ $end：$title")
                        if (loc.isNotBlank()) append("（地点：$loc）")
                        appendLine()
                    }
                }
            }
        }.getOrElse {
            "【手机日历读取受限】：暂无日历读取权限或读取异常（${it.message}）。"
        }
    }
}
