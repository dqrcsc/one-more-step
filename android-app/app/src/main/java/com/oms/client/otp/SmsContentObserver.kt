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

    fun startWatching() {
        if (registered) return
        try {
            context.contentResolver.registerContentObserver(
                Uri.parse("content://sms"),
                true,
                this
            )
            registered = true
        } catch (_: Throwable) {
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
                arrayOf("address", "body", "date"),
                "date >= ?",
                arrayOf(cutoffMs.toString()),
                "date DESC LIMIT 3"
            )?.use { cursor ->
                val addrIdx = cursor.getColumnIndex("address")
                val bodyIdx = cursor.getColumnIndex("body")
                while (cursor.moveToNext()) {
                    val body = if (bodyIdx >= 0) cursor.getString(bodyIdx) else null
                    val addr = if (addrIdx >= 0) cursor.getString(addrIdx) else null
                    if (!body.isNullOrBlank()) {
                        val matched = OtpExtractor.onCandidateTextReceived(
                            rawText = body,
                            senderHint = addr,
                            sourcePackage = "com.android.mms"
                        )
                        if (matched != null) break
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }
}
