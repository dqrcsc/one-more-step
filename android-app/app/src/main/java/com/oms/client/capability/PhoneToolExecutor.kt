package com.oms.client.capability

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import com.oms.client.OmsApplication
import com.oms.client.agent.OmsAccessibilityService
import com.oms.core.ai.ProposedPhoneToolAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PhoneToolExecResult(
    val ok: Boolean,
    val message: String
)

/**
 * Unified Phone OS Super-Tool Executor for the AI Secretary:
 * Exposes native Android capabilities (launch apps, dial/call contacts, draft SMS,
 * navigate maps, adjust volume, write clipboard, open system settings, read calendar)
 * through one safe, auditable interface.
 */
object PhoneToolExecutor {

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
                    PhoneToolExecResult(true, "已在应用舱打开网页")
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
                    cm?.setPrimaryClip(ClipData.newPlainText("oms_secretary", action.target))
                    PhoneToolExecResult(true, "已写入手机剪贴板")
                }.getOrElse { PhoneToolExecResult(false, "写入剪贴板失败：${it.message}") }
            }

            "open_system_settings" -> executeOpenSettings(context, action.target)

            else -> PhoneToolExecResult(false, "暂不支持工具：${action.tool}")
        }
    }

    private suspend fun executePhoneCall(
        context: Context,
        rawTarget: String,
        speaker: Boolean
    ): PhoneToolExecResult {
        val resolved = OmsApplication.instance?.edgeStore?.resolveContact(rawTarget)
        val number = (resolved?.first ?: rawTarget).replace(Regex("""[^\d+*#]"""), "")
        val simHint = resolved?.second.orEmpty()
        if (number.isBlank()) {
            return PhoneToolExecResult(false, "未找到联系人「$rawTarget」的号码，可先在 Mac 运行 `oms contacts set` 同步")
        }

        // Prefer Accessibility-powered direct dial (supports speakerphone & dual SIM) when available
        val a11y = OmsAccessibilityService.instance
        if (a11y != null) {
            val params = JSONObject().apply {
                put("number", number)
                put("speaker", speaker)
                if (simHint.isNotBlank()) put("sim", simHint)
            }
            val res = runCatching { a11y.performPhoneCall(params) { true } }.getOrNull()
            if (res?.optBoolean("ok", false) == true) {
                return PhoneToolExecResult(true, "正在呼出 $rawTarget ($number)")
            }
        }

        // Universal fallback: launch system dialer pre-filled with the resolved phone number
        return withContext(Dispatchers.Main) {
            runCatching {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                PhoneToolExecResult(true, "已调起拨号盘：$rawTarget ($number)")
            }.getOrElse { PhoneToolExecResult(false, "调起拨号失败：${it.message}") }
        }
    }

    private suspend fun executeDraftSms(
        context: Context,
        rawTarget: String,
        body: String
    ): PhoneToolExecResult {
        val resolved = OmsApplication.instance?.edgeStore?.resolveContact(rawTarget)
        val number = (resolved?.first ?: rawTarget).replace(Regex("""[^\d+*#]"""), "")
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
