package com.oms.client.capability

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.oms.client.R
import com.oms.client.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class OmsTtsStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        NotificationExecutor.stopSpeaking(context)
    }
}

/**
 * Module 04: L1~L3 Graded Notification Dispatcher & Neural + Local Alarm-Stream TTS Speaker
 */
object NotificationExecutor {
    const val CHANNEL_SILENT = "oms_silent_stream"
    const val CHANNEL_STANDARD = "oms_standard_notify"
    const val CHANNEL_URGENT = "oms_urgent_alert"
    const val ACTION_STOP_TTS = "com.oms.client.ACTION_STOP_TTS"
    const val EXTRA_STOP_TTS = "stop_tts_on_open"

    private data class PendingSpeech(
        val text: String,
        val useAlarmStream: Boolean,
        val speechRate: Float,
        val repeatCount: Int
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingSpeechQueue = ConcurrentLinkedQueue<PendingSpeech>()
    private val playbackGeneration = AtomicInteger(0)

    private val _isSpeakingFlow = MutableStateFlow(false)
    val isSpeakingFlow: StateFlow<Boolean> = _isSpeakingFlow.asStateFlow()

    private val _speakingTextFlow = MutableStateFlow("")
    val speakingTextFlow: StateFlow<String> = _speakingTextFlow.asStateFlow()

    @Volatile
    private var activeMediaPlayer: MediaPlayer? = null
    @Volatile
    private var activeTtsNotificationId: Int = 0
    @Volatile
    private var playbackStartedElapsedMs: Long = 0L
    @Volatile
    private var hardwareStopReceiverRegistered = false
    @Volatile
    private var appContextRef: Context? = null

    @Volatile
    private var ttsEngine: TextToSpeech? = null
    @Volatile
    private var ttsReady = false
    @Volatile
    private var ttsInitializing = false

    private val hardwareStopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val action = intent?.action ?: return
            if (!_isSpeakingFlow.value) return
            val elapsedSinceStart = SystemClock.elapsedRealtime() - playbackStartedElapsedMs
            when (action) {
                Intent.ACTION_USER_PRESENT -> {
                    // User unlocked the phone after finding it -> automatically stop ringing
                    if (elapsedSinceStart > 500L) {
                        stopSpeaking(context)
                    }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    // User pressed the physical Power button -> automatically stop ringing
                    if (elapsedSinceStart > 700L) {
                        stopSpeaking(context)
                    }
                }
                "android.media.VOLUME_CHANGED_ACTION" -> {
                    // User pressed physical Volume Up / Volume Down key -> stop ringing immediately
                    if (elapsedSinceStart > 750L) {
                        stopSpeaking(context)
                    }
                }
            }
        }
    }

    private fun registerHardwareStopReceiverIfNeeded(context: Context) {
        val appCtx = context.applicationContext
        appContextRef = appCtx
        if (hardwareStopReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction("android.media.VOLUME_CHANGED_ACTION")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appCtx.registerReceiver(hardwareStopReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                appCtx.registerReceiver(hardwareStopReceiver, filter)
            }
            hardwareStopReceiverRegistered = true
        } catch (_: Throwable) {
        }
    }

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
                        val engine = ttsEngine
                        val res = engine?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                            engine?.language = Locale.getDefault()
                        }
                        try {
                            val bestVoice = engine?.voices
                                ?.filter { v ->
                                    v.locale?.language?.lowercase() == "zh" &&
                                        !v.isNetworkConnectionRequired
                                }
                                ?.maxWithOrNull(
                                    compareBy<android.speech.tts.Voice> { it.quality }
                                        .thenBy { -it.latency }
                                        .thenBy { it.name.contains("female", ignoreCase = true) || it.name.contains("local", ignoreCase = true) }
                                )
                            if (bestVoice != null) {
                                engine.voice = bestVoice
                            }
                            engine?.setPitch(1.0f)
                            engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                override fun onStart(utteranceId: String?) {
                                    _isSpeakingFlow.value = true
                                }

                                override fun onDone(utteranceId: String?) {
                                    if (utteranceId?.endsWith("_last") == true) {
                                        _isSpeakingFlow.value = false
                                        _speakingTextFlow.value = ""
                                    }
                                }

                                @Deprecated("Deprecated in Java")
                                override fun onError(utteranceId: String?) {
                                    _isSpeakingFlow.value = false
                                    _speakingTextFlow.value = ""
                                }
                            })
                        } catch (_: Throwable) {
                        }
                        ttsReady = true
                        flushPendingSpeechQueue()
                    }
                }
            }
        }
    }

    private fun ensureAlarmStreamAudible(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val curVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val minTarget = (maxVol * 0.75f).toInt().coerceAtLeast(1)
            if (curVol < minTarget) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, minTarget, 0)
            }
        } catch (_: Throwable) {
        }
    }

    private fun releaseActiveMediaPlayer() {
        val player = activeMediaPlayer
        activeMediaPlayer = null
        if (player != null) {
            try {
                if (player.isPlaying) {
                    player.stop()
                }
            } catch (_: Throwable) {
            }
            try {
                player.release()
            } catch (_: Throwable) {
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
            engine.setSpeechRate(item.speechRate.coerceIn(0.8f, 1.3f))
            engine.setPitch(1.0f)
            val count = item.repeatCount.coerceIn(1, 20)
            val ts = System.currentTimeMillis()
            for (i in 0 until count) {
                val queueMode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                if (i > 0) {
                    engine.playSilentUtterance(
                        420L,
                        TextToSpeech.QUEUE_ADD,
                        "oms_tts_pause_${ts}_$i"
                    )
                }
                val suffix = if (i == count - 1) "_last" else "_$i"
                engine.speak(
                    item.text,
                    queueMode,
                    Bundle.EMPTY,
                    "oms_tts_${ts}$suffix"
                )
            }
        }
    }

    fun sendNotification(context: Context, params: JSONObject): JSONObject {
        ensureChannelsAndTts(context)
        val title = params.optString("title", "Mac AI 提醒")
        val body = params.optString("body", "")
        val level = params.optString("level", "normal").lowercase()
        val ttsSpeak = params.optBoolean("ttsSpeak", false)
        val audioBase64 = params.optString("audioBase64", "").takeIf { it.isNotBlank() }
        val repeatCount = params.optInt("repeatCount", 1).coerceIn(1, 20)
        val notificationId = (System.currentTimeMillis() and 0xFFFFFFF).toInt()

        if (level != "silent") {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = if (level == "urgent") CHANNEL_URGENT else CHANNEL_STANDARD

            val openIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (ttsSpeak) {
                    putExtra(EXTRA_STOP_TTS, true)
                }
            }
            val pi = PendingIntent.getActivity(
                context,
                notificationId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val stopIntent = Intent(context, OmsTtsStopReceiver::class.java).apply {
                action = ACTION_STOP_TTS
            }
            val stopPi = PendingIntent.getBroadcast(
                context,
                notificationId + 1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val displayBody = if (ttsSpeak && level == "urgent") {
                "$body\n💡 点击此通知、上滑清除或按手机音量键即可立即停止声音"
            } else {
                body
            }

            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_stat_oms)
                .setContentTitle(title)
                .setContentText(displayBody)
                .setStyle(NotificationCompat.BigTextStyle().bigText(displayBody))
                .setContentIntent(pi)
                .setDeleteIntent(stopPi)
                .setAutoCancel(true)

            if (ttsSpeak) {
                activeTtsNotificationId = notificationId
                builder.addAction(0, "⏹ 我找到了 · 停止声音", stopPi)
            }

            if (level == "urgent") {
                builder.setPriority(NotificationCompat.PRIORITY_MAX)
                builder.setCategory(NotificationCompat.CATEGORY_ALARM)
                // Full-screen display may be launched by Android without a user tap.
                // Give it a distinct PendingIntent identity (extras alone are ignored)
                // and never attach the content-click stop command to this path.
                val displayIntent = Intent(context, MainActivity::class.java).apply {
                    action = "com.oms.client.ACTION_DISPLAY_URGENT_NOTIFICATION"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                val displayPi = PendingIntent.getActivity(
                    context, notificationId, displayIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.setFullScreenIntent(displayPi, true)
                triggerUrgentVibration(context)
            }

            nm.notify(notificationId, builder.build())
        }

        if (ttsSpeak) {
            val forceAlarmSpeaker = params.optBoolean("forceSpeakerAlarmStream", false)
            val fallbackText = if (audioBase64 != null && body.isNotBlank()) body else "$title。$body"
            speakText(
                context,
                fallbackText,
                useAlarmStream = forceAlarmSpeaker,
                speechRate = 1.0f,
                audioBase64 = audioBase64,
                repeatCount = repeatCount
            )
        }

        return JSONObject().apply {
            put("notificationId", notificationId.toString())
            put("deliveredAtMs", System.currentTimeMillis())
            put("ttsEngine", if (audioBase64 != null) "edge_neural" else "android_local")
        }
    }

    /**
     * Speak text via Neural TTS MP3 (when [audioBase64] is provided by Mac) or local Android TTS fallback.
     * When [useAlarmStream] is true, binds to USAGE_ALARM so L3 urgent alerts and Find My Phone are
     * audible even when the phone is in silent/DND mode or media volume is muted.
     */
    fun speakText(
        context: Context,
        rawText: String,
        useAlarmStream: Boolean = false,
        speechRate: Float = 1.0f,
        audioBase64: String? = null,
        repeatCount: Int = 1
    ) {
        val cleanText = rawText
            .replace(Regex("```[\\s\\S]*?```"), "，代码片段已略过，")
            .replace(Regex("[#*`>|\\[\\]]"), " ")
            .trim()

        val gen = playbackGeneration.incrementAndGet()
        pendingSpeechQueue.clear()
        ttsEngine?.stop()
        releaseActiveMediaPlayer()

        registerHardwareStopReceiverIfNeeded(context)
        if (useAlarmStream) {
            ensureAlarmStreamAudible(context)
        }
        playbackStartedElapsedMs = SystemClock.elapsedRealtime()
        _isSpeakingFlow.value = true
        _speakingTextFlow.value = cleanText.ifBlank { "语音播报中" }

        if (!audioBase64.isNullOrBlank()) {
            val appCtx = context.applicationContext
            mainHandler.post {
                if (playbackGeneration.get() != gen) return@post
                val played = playNeuralAudioInternal(
                    context = appCtx,
                    audioBase64 = audioBase64,
                    useAlarmStream = useAlarmStream,
                    repeatCount = repeatCount.coerceIn(1, 20),
                    gen = gen
                )
                if (!played && cleanText.isNotEmpty() && playbackGeneration.get() == gen) {
                    enqueueLocalSpeech(appCtx, cleanText, useAlarmStream, speechRate, repeatCount)
                } else if (!played && playbackGeneration.get() == gen) {
                    _isSpeakingFlow.value = false
                    _speakingTextFlow.value = ""
                }
            }
            return
        }

        if (cleanText.isEmpty()) {
            _isSpeakingFlow.value = false
            _speakingTextFlow.value = ""
            return
        }
        enqueueLocalSpeech(context, cleanText, useAlarmStream, speechRate, repeatCount)
    }

    private fun playNeuralAudioInternal(
        context: Context,
        audioBase64: String,
        useAlarmStream: Boolean,
        repeatCount: Int,
        gen: Int
    ): Boolean {
        return try {
            val mp3Bytes = Base64.decode(audioBase64, Base64.DEFAULT)
            if (mp3Bytes.size < 128) return false
            val cacheFile = File(context.cacheDir, "oms_neural_tts_active.mp3")
            FileOutputStream(cacheFile).use { fos ->
                fos.write(mp3Bytes)
                fos.fd.sync()
            }

            val player = MediaPlayer()
            val audioAttrs = AudioAttributes.Builder()
                .setUsage(if (useAlarmStream) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            player.setAudioAttributes(audioAttrs)
            FileInputStream(cacheFile).use { fis ->
                player.setDataSource(fis.fd)
            }
            player.prepare()

            var remainingRepeats = (repeatCount - 1).coerceAtLeast(0)
            player.setOnCompletionListener { mp ->
                if (playbackGeneration.get() == gen && remainingRepeats > 0) {
                    remainingRepeats -= 1
                    mainHandler.postDelayed({
                        if (playbackGeneration.get() == gen && activeMediaPlayer === mp) {
                            try {
                                mp.seekTo(0)
                                mp.start()
                            } catch (_: Throwable) {
                                releaseActiveMediaPlayer()
                                _isSpeakingFlow.value = false
                                _speakingTextFlow.value = ""
                            }
                        }
                    }, 420L)
                } else {
                    if (activeMediaPlayer === mp) {
                        releaseActiveMediaPlayer()
                    } else {
                        try {
                            mp.release()
                        } catch (_: Throwable) {
                        }
                    }
                    if (playbackGeneration.get() == gen) {
                        _isSpeakingFlow.value = false
                        _speakingTextFlow.value = ""
                    }
                }
            }
            player.setOnErrorListener { mp, _, _ ->
                if (activeMediaPlayer === mp) {
                    releaseActiveMediaPlayer()
                } else {
                    try {
                        mp.release()
                    } catch (_: Throwable) {
                    }
                }
                if (playbackGeneration.get() == gen) {
                    _isSpeakingFlow.value = false
                    _speakingTextFlow.value = ""
                }
                true
            }
            activeMediaPlayer = player
            player.start()
            true
        } catch (_: Throwable) {
            releaseActiveMediaPlayer()
            false
        }
    }

    private fun enqueueLocalSpeech(
        context: Context,
        cleanText: String,
        useAlarmStream: Boolean,
        speechRate: Float,
        repeatCount: Int
    ) {
        pendingSpeechQueue.offer(
            PendingSpeech(
                text = cleanText,
                useAlarmStream = useAlarmStream,
                speechRate = speechRate,
                repeatCount = repeatCount.coerceIn(1, 20)
            )
        )
        ensureChannelsAndTts(context)
        if (ttsReady) {
            mainHandler.post { flushPendingSpeechQueue() }
        }
    }

    fun stopSpeaking(context: Context? = null) {
        playbackGeneration.incrementAndGet()
        pendingSpeechQueue.clear()
        _isSpeakingFlow.value = false
        _speakingTextFlow.value = ""
        val ctx = context ?: appContextRef
        val notifId = activeTtsNotificationId
        activeTtsNotificationId = 0
        if (ctx != null) {
            try {
                val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.cancel()
            } catch (_: Throwable) {
            }
            if (notifId != 0) {
                try {
                    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(notifId)
                } catch (_: Throwable) {
                }
            }
        }
        mainHandler.post {
            releaseActiveMediaPlayer()
            ttsEngine?.stop()
        }
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
