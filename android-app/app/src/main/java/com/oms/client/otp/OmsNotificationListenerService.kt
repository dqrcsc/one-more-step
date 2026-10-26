package com.oms.client.otp

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Primary Real-Time Verification Code (OTP) Listener.
 * Captures verification codes from system SMS (`com.android.mms`), Google Messages,
 * WeChat, Feishu/Lark, DingTalk, Email clients, and authentication apps even while the screen is locked.
 */
class OmsNotificationListenerService : NotificationListenerService() {

    companion object {
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

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName || sbn.isOngoing) return

        val notif = sbn.notification ?: return
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
        for (candidate in candidates) {
            val matched = OtpExtractor.onCandidateTextReceived(
                rawText = candidate,
                senderHint = effectiveSenderHint,
                sourcePackage = pkg
            )
            if (matched != null) break
        }
    }
}
