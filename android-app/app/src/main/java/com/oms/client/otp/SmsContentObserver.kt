package com.oms.client.otp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Fallback SMS Inbox ContentObserver (`content://sms`).
 * Catches newly inserted verification SMS messages on Xiaomi HyperOS / MIUI
 * when ordered SMS broadcasts are intercepted by the system messaging app.
 */
class SmsContentObserver(private val context: Context) :
    ContentObserver(Handler(Looper.getMainLooper())) {

    private var registered = false
    private var highWaterSmsId: Long = -1L
    private val processedSmsIds = LinkedHashSet<Long>()

    fun startWatching() {
        if (registered) return
        try {
            seedCurrentHighWaterId()
            context.contentResolver.registerContentObserver(
                Uri.parse("content://sms"),
                true,
                this
            )
            registered = true
        } catch (_: Throwable) {
        }
    }

    private fun seedCurrentHighWaterId() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        runCatching {
            context.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id"),
                null,
                null,
                "_id DESC LIMIT 1"
            )?.use { c ->
                if (c.moveToFirst()) {
                    highWaterSmsId = maxOf(highWaterSmsId, c.getLong(0))
                }
            }
        }
    }

    fun stopWatching() {
        if (!registered) return
        try {
            context.contentResolver.unregisterContentObserver(this)
        } catch (_: Throwable) {
        }
        registered = false
    }

    override fun onChange(selfChange: Boolean) {
        super.onChange(selfChange)
        scanRecentInboxSms()
    }

    private fun scanRecentInboxSms() {
        val hasReadSms = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasReadSms) return

        try {
            val cutoffMs = System.currentTimeMillis() - 25_000L
            context.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id", "address", "body", "date"),
                if (highWaterSmsId >= 0L) "_id > ? AND date >= ?" else "date >= ?",
                if (highWaterSmsId >= 0L) arrayOf(highWaterSmsId.toString(), cutoffMs.toString()) else arrayOf(cutoffMs.toString()),
                "_id ASC LIMIT 5"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex("_id")
                val addrIdx = cursor.getColumnIndex("address")
                val bodyIdx = cursor.getColumnIndex("body")
                val dateIdx = cursor.getColumnIndex("date")
                while (cursor.moveToNext()) {
                    val smsId = if (idIdx >= 0) cursor.getLong(idIdx) else -1L
                    if (smsId >= 0L) {
                        highWaterSmsId = maxOf(highWaterSmsId, smsId)
                        if (!processedSmsIds.add(smsId)) continue
                        while (processedSmsIds.size > 64) {
                            val oldest = processedSmsIds.iterator().next()
                            processedSmsIds.remove(oldest)
                        }
                    }
                    val body = if (bodyIdx >= 0) cursor.getString(bodyIdx) else null
                    val addr = if (addrIdx >= 0) cursor.getString(addrIdx) else null
                    val smsDate = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L
                    if (!body.isNullOrBlank()) {
                        val matched = OtpExtractor.onCandidateTextReceived(
                            rawText = body,
                            senderHint = addr,
                            sourcePackage = "com.android.mms",
                            eventTimestampMs = smsDate.takeIf { it > 0L }
                        )
                        if (matched != null) break
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }
}
