package com.oms.client.capability

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/**
 * Module 04: Native Xiaomi / Android Calendar & Reminder Executor
 */
object CalendarExecutor {

    private fun resolvePrimaryCalendarId(context: Context): Long {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME
        )
        var bestId = 1L
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.VISIBLE} = 1",
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val accountType = cursor.getString(1) ?: ""
                val isPrimary = cursor.getInt(2)
                bestId = id
                if (isPrimary == 1 || accountType.contains("xiaomi", ignoreCase = true) ||
                    accountType.equals("LOCAL", ignoreCase = true)
                ) {
                    return id
                }
            }
        }
        return bestId
    }

    /**
     * Expand recurring instances in [startMs, endMs] to detect schedule conflicts accurately
     */
    fun listEvents(context: Context, params: JSONObject): JSONObject {
        val now = System.currentTimeMillis()
        val startMs = params.optLong("startMs", now)
        val endMs = params.optLong("endMs", now + 3 * 24 * 3600 * 1000L)

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, startMs)
        ContentUris.appendId(builder, endMs)

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY
        )

        val eventsArr = JSONArray()
        context.contentResolver.query(
            builder.build(),
            projection,
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                eventsArr.put(
                    JSONObject().apply {
                        put("eventId", cursor.getLong(0))
                        put("title", cursor.getString(1) ?: "")
                        put("description", cursor.getString(2) ?: "")
                        put("location", cursor.getString(3) ?: "")
                        put("startMs", cursor.getLong(4))
                        put("endMs", cursor.getLong(5))
                        put("allDay", cursor.getInt(6) == 1)
                    }
                )
            }
        }

        return JSONObject().apply {
            put("events", eventsArr)
        }
    }

    fun insertEvent(context: Context, params: JSONObject): JSONObject {
        val calendarId = resolvePrimaryCalendarId(context)
        val title = params.optString("title", "AI 安排日程")
        val description = params.optString("description", "")
        val location = params.optString("location", "")
        val startMs = params.optLong("startMs", System.currentTimeMillis() + 3600_000L)
        val endMs = params.optLong("endMs", startMs + 3600_000L)
        val reminderMinutes = params.optInt("reminderMinutes", 15)

        val eventValues = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.HAS_ALARM, if (reminderMinutes >= 0) 1 else 0)
        }

        val eventUri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, eventValues)
            ?: throw IllegalStateException("Failed to insert event into CalendarContract")
        val eventId = ContentUris.parseId(eventUri)

        if (reminderMinutes >= 0) {
            val reminderValues = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, reminderMinutes)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues)
        }

        return JSONObject().apply {
            put("ok", true)
            put("eventId", eventId)
            put("title", title)
        }
    }

    fun deleteEvent(context: Context, params: JSONObject): JSONObject {
        val eventId = params.getLong("eventId")
        val deleteUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val rows = context.contentResolver.delete(deleteUri, null, null)
        return JSONObject().apply {
            put("ok", rows > 0)
            put("deletedRows", rows)
        }
    }
}
