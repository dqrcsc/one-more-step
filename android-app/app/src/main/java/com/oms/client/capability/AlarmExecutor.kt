package com.oms.client.capability

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.oms.client.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Module 04: Native System Alarm Executor (`AlarmClock.ACTION_SET_ALARM` + `AlarmManager` fallback)
 */
object AlarmExecutor {

    fun setSystemAlarm(context: Context, params: JSONObject): JSONObject {
        val hour = params.optInt("hour", 8).coerceIn(0, 23)
        val minute = params.optInt("minute", 0).coerceIn(0, 59)
        val label = params.optString("label", "青简·咫尺 AI 提醒")
        val vibrate = params.optBoolean("vibrate", true)
        val daysArray: JSONArray? = params.optJSONArray("days") ?: params.optJSONArray("daysOfWeek")

        val calendarDays = ArrayList<Int>()
        if (daysArray != null) {
            for (i in 0 until daysArray.length()) {
                val isoDay = daysArray.optInt(i) // 1=Mon ... 7=Sun
                val calDay = when (isoDay) {
                    1 -> Calendar.MONDAY
                    2 -> Calendar.TUESDAY
                    3 -> Calendar.WEDNESDAY
                    4 -> Calendar.THURSDAY
                    5 -> Calendar.FRIDAY
                    6 -> Calendar.SATURDAY
                    7 -> Calendar.SUNDAY
                    else -> null
                }
                if (calDay != null) calendarDays.add(calDay)
            }
        }

        // Primary Path: Native Xiaomi / Android Clock App via ACTION_SET_ALARM (EXTRA_SKIP_UI = true)
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_VIBRATE, vibrate)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (calendarDays.isNotEmpty()) {
                    putExtra(AlarmClock.EXTRA_DAYS, calendarDays)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            JSONObject().apply {
                put("ok", true)
                put("mode", "system_alarm_intent")
                put("hour", hour)
                put("minute", minute)
                put("label", label)
            }
        } catch (e: Throwable) {
            // Fallback Path: AlarmManager.setAlarmClock()
            scheduleFallbackExactAlarm(context, hour, minute, label)
            JSONObject().apply {
                put("ok", true)
                put("mode", "alarm_manager_fallback")
                put("hour", hour)
                put("minute", minute)
                put("label", label)
            }
        }
    }

    private fun scheduleFallbackExactAlarm(context: Context, hour: Int, minute: Int, label: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        val showIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("alarm_label", label)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            context,
            (hour * 60 + minute),
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val clockInfo = AlarmManager.AlarmClockInfo(cal.timeInMillis, pi)
        alarmManager.setAlarmClock(clockInfo, pi)
    }
}
