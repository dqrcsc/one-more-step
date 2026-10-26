package com.oms.client.otp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Lockscreen-Penetrating Direct SMS Broadcast Receiver.
 * Parses raw SMS PDU messages directly upon arrival so verification codes are captured
 * even if the user configured the Xiaomi lockscreen to hide sensitive notification content.
 */
class OmsSmsBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) return

            val bodyBuilder = StringBuilder()
            var senderAddress: String? = null
            for (sms in messages) {
                if (sms == null) continue
                sms.displayMessageBody?.let { bodyBuilder.append(it) }
                if (senderAddress.isNullOrBlank()) {
                    senderAddress = sms.displayOriginatingAddress
                }
            }

            val fullBody = bodyBuilder.toString().trim()
            if (fullBody.isNotEmpty()) {
                OtpExtractor.onCandidateTextReceived(
                    rawText = fullBody,
                    senderHint = senderAddress,
                    sourcePackage = "com.android.mms"
                )
            }
        } catch (_: Throwable) {
        }
    }
}
