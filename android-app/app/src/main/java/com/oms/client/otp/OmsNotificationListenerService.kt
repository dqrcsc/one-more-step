package com.oms.client.otp

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

/**
 * Primary Real-Time Verification Code (OTP) Listener.
 * Captures verification codes from system SMS (`com.android.mms`), Google Messages,
 * WeChat, Feishu/Lark, DingTalk, Email clients, and authentication apps even while the screen is locked.
 */
class OmsNotificationListenerService : NotificationListenerService() {

    private val seenNotificationCodes = ConcurrentHashMap<String, Pair<String, Long>>()

    companion object {
        private const val SEEN_KEY_TTL_MS = OtpExtractor.EXTENDED_UNLOCK_DEDUP_MS

        @Volatile
        var isConnected: Boolean = false
            private set
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
    }

    override fun onListenerDisconnected() {
        isConnected = false
        super.onListenerDisconnected()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        // Keep seenNotificationCodes until TTL so clear/repost bursts don't re-trigger
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName || sbn.isOngoing) return

        val notif = sbn.notification ?: return
        // Skip Android/MIUI group summary notifications which re-emit old child previews
        if ((notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val now = System.currentTimeMillis()
        val notifWhen = notif.`when`
        // When Xiaomi HyperOS un-redacts or re-posts an unread lockscreen notification upon unlock
        // more than 5 minutes after the message arrived, `notif.when` preserves the original arrival time.
        if (notifWhen in 1L until (now - OtpExtractor.OTP_MAX_AGE_MS)) return

        seenNotificationCodes.entries.removeIf { now - it.value.second > SEEN_KEY_TTL_MS }
        val extras = notif.extras ?: Bundle.EMPTY

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val ticker = notif.tickerText?.toString()

        // Also extract MessagingStyle messages (used by Xiaomi MMS, Google Messages, WeChat/Lark)
        val messagingTexts = mutableListOf<String>()
        var messagingSender: String? = null
        try {
            @Suppress("DEPRECATION")
            val msgBundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (!msgBundles.isNullOrEmpty()) {
                for (parcel in msgBundles) {
                    val b = parcel as? Bundle ?: continue
                    val msgTxt = b.getCharSequence("text")?.toString()
                    val senderTxt = b.getCharSequence("sender")?.toString()
                    if (!msgTxt.isNullOrBlank()) {
                        messagingTexts.add(msgTxt)
                    }
                    if (!senderTxt.isNullOrBlank()) {
                        messagingSender = senderTxt
                    }
                }
            }
        } catch (_: Throwable) {
        }

        val candidates = listOfNotNull(
            bigText,
            messagingTexts.lastOrNull(),
            text,
            ticker,
            subText
        )
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        if (candidates.isEmpty()) return

        val effectiveSenderHint = messagingSender ?: title
        val sbnKey = sbn.key ?: "$pkg:${sbn.id}"
        for (candidate in candidates) {
            val preview = OtpExtractor.extractOtp(candidate, effectiveSenderHint, pkg) ?: continue
            val prevForKey = seenNotificationCodes[sbnKey]
            if (prevForKey != null && prevForKey.first.equals(preview.code, ignoreCase = true)) {
                return
            }
            seenNotificationCodes[sbnKey] = preview.code to now
            val matched = OtpExtractor.onCandidateTextReceived(
                rawText = candidate,
                senderHint = effectiveSenderHint,
                sourcePackage = pkg,
                eventTimestampMs = notifWhen.takeIf { it > 0L }
            )
            if (matched != null) break
        }
    }
}
