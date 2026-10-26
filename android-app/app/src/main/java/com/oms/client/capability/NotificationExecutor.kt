package com.oms.client.capability

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.oms.client.R
import com.oms.client.ui.MainActivity
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Module 04: L1~L3 Graded Notification Dispatcher & Alarm-Stream TTS Speaker
 */
object NotificationExecutor {
    const val CHANNEL_SILENT = "oms_silent_stream"
    const val CHANNEL_STANDARD = "oms_standard_notify"
    const val CHANNEL_URGENT = "oms_urgent_alert"

    private data class PendingSpeech(
        val text: String,
        val useAlarmStream: Boolean,
        val speechRate: Float
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingSpeechQueue = ConcurrentLinkedQueue<PendingSpeech>()

    @Volatile
    private var ttsEngine: TextToSpeech? = null
    @Volatile
    private var ttsReady = false
    @Volatile
    private var ttsInitializing = false

    fun ensureChannelsAndTts(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val silentCh = NotificationChannel(
            CHANNEL_SILENT,
            "青简·咫尺 静默归档流 (L1)",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setSound(null, null)
            enableVibration(false)
        }

        val standardCh = NotificationChannel(
            CHANNEL_STANDARD,
            "青简·咫尺 常规协同通知 (L2)",
            NotificationManager.IMPORTANCE_DEFAULT
        )

        val urgentCh = NotificationChannel(
            CHANNEL_URGENT,
            "青简·咫尺 紧急强触达与远程审批 (L3)",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setBypassDnd(true)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 600, 200, 800)
        }

        nm.createNotificationChannels(listOf(silentCh, standardCh, urgentCh))

        if (ttsEngine == null && !ttsInitializing) {
            ttsInitializing = true
            val appCtx = context.applicationContext
            mainHandler.post {
                ttsEngine = TextToSpeech(appCtx) { status ->
                    ttsInitializing = false
                    if (status == TextToSpeech.SUCCESS) {
                        val res = ttsEngine?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                            ttsEngine?.language = Locale.getDefault()
                        }
                        ttsReady = true
                        flushPendingSpeechQueue()
                    }
                }
            }
        }
    }

    private fun flushPendingSpeechQueue() {
        val engine = ttsEngine ?: return
        if (!ttsReady) return
        while (true) {
            val item = pendingSpeechQueue.poll() ?: break
            val audioAttrs = AudioAttributes.Builder()
                .setUsage(if (item.useAlarmStream) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            engine.setAudioAttributes(audioAttrs)
            engine.setSpeechRate(item.speechRate)
            engine.speak(
                item.text,
                TextToSpeech.QUEUE_FLUSH,
                Bundle.EMPTY,
                "oms_tts_${System.currentTimeMillis()}"
            )
        }
    }

    fun sendNotification(context: Context, params: JSONObject): JSONObject {
        ensureChannelsAndTts(context)
        val title = params.optString("title", "Mac AI 提醒")
        val body = params.optString("body", "")
        val level = params.optString("level", "normal").lowercase()
        val ttsSpeak = params.optBoolean("ttsSpeak", false)
        val notificationId = (System.currentTimeMillis() and 0xFFFFFFF).toInt()

        if (level != "silent") {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = if (level == "urgent") CHANNEL_URGENT else CHANNEL_STANDARD

            val openIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pi = PendingIntent.getActivity(
                context,
                notificationId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_stat_oms)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)

            if (level == "urgent") {
                builder.setPriority(NotificationCompat.PRIORITY_MAX)
                builder.setCategory(NotificationCompat.CATEGORY_ALARM)
                builder.setFullScreenIntent(pi, true)
                triggerUrgentVibration(context)
            }

            nm.notify(notificationId, builder.build())
        }

        if (ttsSpeak) {
            val forceAlarmSpeaker = params.optBoolean("forceSpeakerAlarmStream", false)
            speakText(context, "$title。$body", useAlarmStream = forceAlarmSpeaker, speechRate = 1.05f)
        }

        return JSONObject().apply {
            put("notificationId", notificationId.toString())
            put("deliveredAtMs", System.currentTimeMillis())
        }
    }

    /**
     * Speak text via Android TTS. When [useAlarmStream] is true, binds to USAGE_ALARM so
     * L3 urgent alerts are audible even when the phone's media volume is muted!
     */
    fun speakText(
        context: Context,
        rawText: String,
        useAlarmStream: Boolean = false,
        speechRate: Float = 1.15f
    ) {
        val cleanText = rawText
            .replace(Regex("```[\\s\\S]*?```"), "，代码片段已略过，")
            .replace(Regex("[#*`>|\\[\\]]"), " ")
            .trim()
        if (cleanText.isEmpty()) return

        pendingSpeechQueue.clear()
        pendingSpeechQueue.offer(PendingSpeech(cleanText, useAlarmStream, speechRate))
        ensureChannelsAndTts(context)
        if (ttsReady) {
            mainHandler.post { flushPendingSpeechQueue() }
        }
    }

    fun stopSpeaking() {
        ttsEngine?.stop()
    }

    private fun triggerUrgentVibration(context: Context) {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val wave = VibrationEffect.createWaveform(longArrayOf(0, 350, 150, 500, 150, 650), -1)
            vibrator.vibrate(wave)
        } catch (_: Throwable) {
        }
    }
}
