package com.oms.client.capability

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class OmsTtsStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        NotificationExecutor.stopSpeaking(context)
    }
}

/**
 * Three-tier audio routing policy for TTS & Neural speech:
 * 1. [FORCE_SPEAKER_ALARM]: Urgent L3 alert / Find My Phone / In-Call speech ->
 *    forces built-in speaker + STREAM_ALARM (restoring baseline alarm volume afterwards).
 * 2. [ALARM_RESPECT_HEADSET]: Default `oms phone say` & normal `oms notify --tts` ->
 *    uses connected headset privately via USAGE_MEDIA when headphones/AirPods are connected;
 *    otherwise uses STREAM_ALARM to pierce silent/DND mode and restores baseline alarm volume afterwards.
 * 3. [MEDIA_COMMUTE]: Timeline card `🎧 TTS朗读` or `--no-alarm` / `--media` ->
 *    uses USAGE_MEDIA without modifying alarm volume and without stopping on screen-off / volume keys.
 */
enum class SpeechAudioPolicy(val wireName: String) {
    FORCE_SPEAKER_ALARM("force_speaker_alarm"),
    ALARM_RESPECT_HEADSET("alarm_respect_headset"),
    MEDIA_COMMUTE("media_commute");

    companion object {
        fun fromWireName(
            rawPolicy: String?,
            forceSpeakerAlarmStream: Boolean = false,
            useAlarmStream: Boolean? = null,
            defaultPolicy: SpeechAudioPolicy = ALARM_RESPECT_HEADSET
        ): SpeechAudioPolicy {
            when (rawPolicy?.trim()?.lowercase(Locale.ROOT)) {
                "force_speaker_alarm" -> return FORCE_SPEAKER_ALARM
                "alarm_respect_headset" -> return ALARM_RESPECT_HEADSET
                "media_commute" -> return MEDIA_COMMUTE
            }
            if (forceSpeakerAlarmStream) {
                return FORCE_SPEAKER_ALARM
            }
            if (useAlarmStream != null) {
                return if (useAlarmStream) defaultPolicy else MEDIA_COMMUTE
            }
            return defaultPolicy
        }

        fun fromParams(
            params: JSONObject,
            defaultPolicy: SpeechAudioPolicy = ALARM_RESPECT_HEADSET
        ): SpeechAudioPolicy {
            val rawPolicy = params.optString("audioPolicy", "").takeIf { it.isNotBlank() }
            val forceSpeaker = params.optBoolean("forceSpeakerAlarmStream", false)
            val useAlarm = if (params.has("useAlarmStream")) {
                params.optBoolean("useAlarmStream", true)
            } else {
                null
            }
            return fromWireName(
                rawPolicy = rawPolicy,
                forceSpeakerAlarmStream = forceSpeaker,
                useAlarmStream = useAlarm,
                defaultPolicy = defaultPolicy
            )
        }

        fun resolveRouting(
            policy: SpeechAudioPolicy,
            externalHeadsetConnected: Boolean
        ): ResolvedSpeechRouting {
            return when (policy) {
                FORCE_SPEAKER_ALARM -> ResolvedSpeechRouting(
                    policy = policy,
                    useAlarmStream = true,
                    forceBuiltInSpeaker = true,
                    enableHardwareStop = true
                )
                ALARM_RESPECT_HEADSET -> {
                    if (externalHeadsetConnected) {
                        ResolvedSpeechRouting(
                            policy = policy,
                            useAlarmStream = false,
                            forceBuiltInSpeaker = false,
                            enableHardwareStop = false
                        )
                    } else {
                        ResolvedSpeechRouting(
                            policy = policy,
                            useAlarmStream = true,
                            forceBuiltInSpeaker = false,
                            enableHardwareStop = true
                        )
                    }
                }
                MEDIA_COMMUTE -> ResolvedSpeechRouting(
                    policy = policy,
                    useAlarmStream = false,
                    forceBuiltInSpeaker = false,
                    enableHardwareStop = false
                )
            }
        }

        fun extractGenFromUtteranceId(utteranceId: String?): Int? {
            if (utteranceId.isNullOrBlank()) return null
            val prefix = "oms_tts_g"
            if (!utteranceId.startsWith(prefix)) return null
            val rest = utteranceId.substring(prefix.length)
            val endIdx = rest.indexOf('_')
            if (endIdx <= 0) return null
            return rest.substring(0, endIdx).toIntOrNull()
        }
    }
}

data class ResolvedSpeechRouting(
    val policy: SpeechAudioPolicy,
    val useAlarmStream: Boolean,
    val forceBuiltInSpeaker: Boolean,
    val enableHardwareStop: Boolean
)

/**
 * Module 04: L1~L3 Graded Notification Dispatcher & Neural + Local Multi-Policy TTS Speaker
 */
object NotificationExecutor {
    const val CHANNEL_SILENT = "oms_silent_stream"
    const val CHANNEL_STANDARD = "oms_standard_notify"
    const val CHANNEL_URGENT = "oms_urgent_alert"
    const val ACTION_STOP_TTS = "com.oms.client.ACTION_STOP_TTS"
    const val EXTRA_STOP_TTS = "stop_tts_on_open"

    private data class PendingSpeech(
        val gen: Int,
        val text: String,
        val useAlarmStream: Boolean,
        val speechRate: Float,
        val repeatCount: Int
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "oms-tts-io").apply { isDaemon = true }
    }
    private val pendingSpeechQueue = ConcurrentLinkedQueue<PendingSpeech>()
    private val playbackGeneration = AtomicInteger(0)

    private val _isSpeakingFlow = MutableStateFlow(false)
    val isSpeakingFlow: StateFlow<Boolean> = _isSpeakingFlow.asStateFlow()

    private val _speakingTextFlow = MutableStateFlow("")
    val speakingTextFlow: StateFlow<String> = _speakingTextFlow.asStateFlow()

    private val alarmVolumeLock = Any()
    private var savedBaselineAlarmVolume: Int? = null
    private var alarmVolumeOwnerGen: Int = 0

    private val audioFocusLock = Any()
    private var activeAudioFocusRequest: AudioFocusRequest? = null

    @Volatile
    private var activeMediaPlayer: MediaPlayer? = null
    @Volatile
    private var activeTtsNotificationId: Int = 0
    @Volatile
    private var playbackStartedElapsedMs: Long = 0L
    @Volatile
    private var hardwareStopReceiverRegistered = false
    @Volatile
    private var activeHardwareStopEnabled = false

    val isHardwareStopActive: Boolean
        get() = activeHardwareStopEnabled && !ignoreHardwareStopForInCallSpeech
    @Volatile
    private var appContextRef: Context? = null

    @Volatile
    private var ttsEngine: TextToSpeech? = null
    @Volatile
    private var ttsReady = false
    @Volatile
    private var ttsInitializing = false
    @Volatile
    private var ignoreHardwareStopForInCallSpeech = false

    internal fun resolvePlaybackRouting(
        policy: SpeechAudioPolicy,
        externalHeadsetConnected: Boolean
    ): ResolvedSpeechRouting = SpeechAudioPolicy.resolveRouting(policy, externalHeadsetConnected)

    internal fun extractGenFromUtteranceId(utteranceId: String?): Int? =
        SpeechAudioPolicy.extractGenFromUtteranceId(utteranceId)

    fun isExternalHeadsetConnected(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { dev ->
                when (dev.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE -> true
                    else -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                                dev.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                                dev.type == AudioDeviceInfo.TYPE_BLE_BROADCAST
                        } else {
                            false
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            false
        }
    }

    private val hardwareStopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val action = intent?.action ?: return
            if (!_isSpeakingFlow.value || !activeHardwareStopEnabled || ignoreHardwareStopForInCallSpeech) return
            val elapsedSinceStart = SystemClock.elapsedRealtime() - playbackStartedElapsedMs
            when (action) {
                Intent.ACTION_USER_PRESENT -> {
                    if (elapsedSinceStart > 500L) {
                        stopSpeaking(context)
                    }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    if (elapsedSinceStart > 700L) {
                        stopSpeaking(context)
                    }
                }
                "android.media.VOLUME_CHANGED_ACTION" -> {
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
        appContextRef = context.applicationContext
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
                                    val uttGen = extractGenFromUtteranceId(utteranceId)
                                    if (uttGen != null && uttGen == playbackGeneration.get()) {
                                        _isSpeakingFlow.value = true
                                    }
                                }

                                override fun onDone(utteranceId: String?) {
                                    val uttGen = extractGenFromUtteranceId(utteranceId) ?: return
                                    if (utteranceId?.endsWith("_last") == true) {
                                        onPlaybackFinished(uttGen)
                                    }
                                }

                                @Deprecated("Deprecated in Java")
                                override fun onError(utteranceId: String?) {
                                    val uttGen = extractGenFromUtteranceId(utteranceId) ?: return
                                    onPlaybackFinished(uttGen)
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

    private fun applyOrTransferAlarmVolume(
        context: Context,
        gen: Int,
        useAlarmStream: Boolean,
        minVolumeRatio: Float
    ) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        synchronized(alarmVolumeLock) {
            if (!useAlarmStream) {
                val baseline = savedBaselineAlarmVolume
                if (baseline != null) {
                    savedBaselineAlarmVolume = null
                    alarmVolumeOwnerGen = 0
                    try {
                        am.setStreamVolume(AudioManager.STREAM_ALARM, baseline, 0)
                    } catch (_: Throwable) {
                    }
                }
                return
            }

            try {
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM).coerceAtLeast(1)
                val curVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
                val minTarget = (maxVol * minVolumeRatio).toInt().coerceIn(1, maxVol)
                if (savedBaselineAlarmVolume == null) {
                    if (curVol < minTarget) {
                        savedBaselineAlarmVolume = curVol
                        alarmVolumeOwnerGen = gen
                        am.setStreamVolume(AudioManager.STREAM_ALARM, minTarget, 0)
                    }
                } else {
                    // Preempted an ongoing alarm-stream playback: transfer ownership without overwriting baseline!
                    alarmVolumeOwnerGen = gen
                    if (curVol < minTarget) {
                        am.setStreamVolume(AudioManager.STREAM_ALARM, minTarget, 0)
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun restoreAlarmVolumeIfOwned(context: Context?, gen: Int) {
        val ctx = context ?: appContextRef ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        synchronized(alarmVolumeLock) {
            val baseline = savedBaselineAlarmVolume
            if (baseline != null && alarmVolumeOwnerGen == gen) {
                savedBaselineAlarmVolume = null
                alarmVolumeOwnerGen = 0
                try {
                    am.setStreamVolume(AudioManager.STREAM_ALARM, baseline, 0)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun restoreAlarmVolumeForce(context: Context?) {
        val ctx = context ?: appContextRef ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        synchronized(alarmVolumeLock) {
            val baseline = savedBaselineAlarmVolume
            if (baseline != null) {
                savedBaselineAlarmVolume = null
                alarmVolumeOwnerGen = 0
                try {
                    am.setStreamVolume(AudioManager.STREAM_ALARM, baseline, 0)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun requestSpeechAudioFocus(context: Context, useAlarmStream: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(if (useAlarmStream) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        synchronized(audioFocusLock) {
            activeAudioFocusRequest?.let { prev ->
                runCatching { am.abandonAudioFocusRequest(prev) }
                activeAudioFocusRequest = null
            }
            runCatching {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener { }
                    .build()
                am.requestAudioFocus(req)
                activeAudioFocusRequest = req
            }
        }
    }

    private fun abandonSpeechAudioFocus(context: Context?) {
        val ctx = context ?: appContextRef ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        synchronized(audioFocusLock) {
            activeAudioFocusRequest?.let { req ->
                activeAudioFocusRequest = null
                runCatching { am.abandonAudioFocusRequest(req) }
            }
        }
    }

    private fun onPlaybackFinished(gen: Int) {
        if (playbackGeneration.get() != gen) return
        _isSpeakingFlow.value = false
        _speakingTextFlow.value = ""
        activeHardwareStopEnabled = false
        restoreAlarmVolumeIfOwned(appContextRef, gen)
        abandonSpeechAudioFocus(appContextRef)
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
            if (playbackGeneration.get() != item.gen) continue
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
                        "oms_tts_g${item.gen}_pause_${ts}_$i"
                    )
                }
                val suffix = if (i == count - 1) "_last" else "_$i"
                val utteranceParams = Bundle().apply {
                    putInt(
                        TextToSpeech.Engine.KEY_PARAM_STREAM,
                        if (item.useAlarmStream) AudioManager.STREAM_ALARM else AudioManager.STREAM_MUSIC
                    )
                }
                engine.speak(
                    item.text,
                    queueMode,
                    utteranceParams,
                    "oms_tts_g${item.gen}_${ts}$suffix"
                )
            }
        }
    }

    fun sendNotification(context: Context, params: JSONObject): JSONObject {
        ensureChannelsAndTts(context)
        val title = params.optString("title", "Mac AI 提醒")
        val body = params.optString("body", "")
        val level = params.optString("level", "normal").lowercase(Locale.ROOT)
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

        var resolvedPolicyWire = SpeechAudioPolicy.ALARM_RESPECT_HEADSET.wireName
        if (ttsSpeak) {
            val defaultPolicy = if (level == "urgent") {
                SpeechAudioPolicy.FORCE_SPEAKER_ALARM
            } else {
                SpeechAudioPolicy.ALARM_RESPECT_HEADSET
            }
            val policy = SpeechAudioPolicy.fromParams(params, defaultPolicy = defaultPolicy)
            resolvedPolicyWire = policy.wireName
            val fallbackText = if (audioBase64 != null && body.isNotBlank()) body else "$title。$body"
            speakText(
                context = context,
                rawText = fallbackText,
                audioPolicy = policy,
                speechRate = 1.0f,
                audioBase64 = audioBase64,
                repeatCount = repeatCount
            )
        }

        return JSONObject().apply {
            put("notificationId", notificationId.toString())
            put("deliveredAtMs", System.currentTimeMillis())
            put("ttsEngine", if (audioBase64 != null) "edge_neural" else "android_local")
            if (ttsSpeak) {
                put("audioPolicy", resolvedPolicyWire)
            }
        }
    }

    /**
     * Speak text via Neural TTS MP3 (when [audioBase64] is provided by Mac) or local Android TTS fallback,
     * applying the 3-tier [SpeechAudioPolicy] and automatically saving/restoring STREAM_ALARM volume.
     */
    fun speakText(
        context: Context,
        rawText: String,
        useAlarmStream: Boolean = true,
        speechRate: Float = 1.0f,
        audioBase64: String? = null,
        repeatCount: Int = 1,
        audioPolicy: SpeechAudioPolicy? = null,
        minAlarmVolumeRatio: Float = 0.75f
    ): Int {
        val appCtx = context.applicationContext
        appContextRef = appCtx
        val cleanText = rawText
            .replace(Regex("```[\\s\\S]*?```"), "，代码片段已略过，")
            .replace(Regex("[#*`>|\\[\\]]"), " ")
            .trim()

        val effectivePolicy = audioPolicy
            ?: if (useAlarmStream) SpeechAudioPolicy.ALARM_RESPECT_HEADSET else SpeechAudioPolicy.MEDIA_COMMUTE
        val routing = resolvePlaybackRouting(
            policy = effectivePolicy,
            externalHeadsetConnected = isExternalHeadsetConnected(appCtx)
        )

        val gen = playbackGeneration.incrementAndGet()
        pendingSpeechQueue.clear()
        ttsEngine?.stop()
        releaseActiveMediaPlayer()

        if (cleanText.isEmpty() && audioBase64.isNullOrBlank()) {
            _isSpeakingFlow.value = false
            _speakingTextFlow.value = ""
            activeHardwareStopEnabled = false
            restoreAlarmVolumeForce(appCtx)
            abandonSpeechAudioFocus(appCtx)
            return 0
        }

        activeHardwareStopEnabled = routing.enableHardwareStop
        if (routing.enableHardwareStop) {
            registerHardwareStopReceiverIfNeeded(appCtx)
        }
        applyOrTransferAlarmVolume(
            context = appCtx,
            gen = gen,
            useAlarmStream = routing.useAlarmStream,
            minVolumeRatio = minAlarmVolumeRatio
        )
        requestSpeechAudioFocus(appCtx, routing.useAlarmStream)

        playbackStartedElapsedMs = SystemClock.elapsedRealtime()
        _isSpeakingFlow.value = true
        _speakingTextFlow.value = cleanText.ifBlank { "语音播报中" }

        if (!audioBase64.isNullOrBlank()) {
            ioExecutor.execute {
                if (playbackGeneration.get() != gen) return@execute
                val preparedPlayer = prepareNeuralAudioPlayerOffMain(
                    context = appCtx,
                    audioBase64 = audioBase64,
                    routing = routing,
                    repeatCount = repeatCount.coerceIn(1, 20),
                    gen = gen
                )
                mainHandler.post {
                    if (playbackGeneration.get() != gen) {
                        preparedPlayer?.let { mp -> runCatching { mp.release() } }
                        return@post
                    }
                    if (preparedPlayer != null) {
                        try {
                            activeMediaPlayer = preparedPlayer
                            preparedPlayer.start()
                        } catch (_: Throwable) {
                            releaseActiveMediaPlayer()
                            if (cleanText.isNotEmpty()) {
                                enqueueLocalSpeech(appCtx, gen, cleanText, routing.useAlarmStream, speechRate, repeatCount)
                            } else {
                                onPlaybackFinished(gen)
                            }
                        }
                    } else if (cleanText.isNotEmpty()) {
                        enqueueLocalSpeech(appCtx, gen, cleanText, routing.useAlarmStream, speechRate, repeatCount)
                    } else {
                        onPlaybackFinished(gen)
                    }
                }
            }
            return gen
        }

        enqueueLocalSpeech(appCtx, gen, cleanText, routing.useAlarmStream, speechRate, repeatCount)
        return gen
    }

    /**
     * Speak text on the phone (over FORCE_SPEAKER_ALARM when [useAlarmStream] is true, for in-call speech)
     * and suspend until playback finishes or [shouldContinue] returns false.
     */
    suspend fun speakTextAndAwait(
        context: Context,
        rawText: String,
        useAlarmStream: Boolean = true,
        speechRate: Float = 1.0f,
        audioBase64: String? = null,
        repeatCount: Int = 1,
        maxWaitMs: Long = 45_000L,
        ignoreHardwareStop: Boolean = true,
        shouldContinue: () -> Boolean = { true }
    ): Boolean {
        try {
            ignoreHardwareStopForInCallSpeech = ignoreHardwareStop
            val policy = if (useAlarmStream) {
                SpeechAudioPolicy.FORCE_SPEAKER_ALARM
            } else {
                SpeechAudioPolicy.MEDIA_COMMUTE
            }
            val gen = speakText(
                context = context,
                rawText = rawText,
                useAlarmStream = useAlarmStream,
                speechRate = speechRate,
                audioBase64 = audioBase64,
                repeatCount = repeatCount,
                audioPolicy = policy,
                minAlarmVolumeRatio = 0.85f
            )
            if (gen == 0) return false
            val startedAt = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - startedAt < maxWaitMs) {
                delay(100L)
                if (!shouldContinue()) {
                    stopSpeaking(context)
                    return false
                }
                if (playbackGeneration.get() != gen) {
                    return false
                }
                if (!_isSpeakingFlow.value) {
                    return shouldContinue()
                }
            }
            stopSpeaking(context)
            return false
        } catch (e: CancellationException) {
            stopSpeaking(context)
            throw e
        } finally {
            ignoreHardwareStopForInCallSpeech = false
        }
    }

    private fun prepareNeuralAudioPlayerOffMain(
        context: Context,
        audioBase64: String,
        routing: ResolvedSpeechRouting,
        repeatCount: Int,
        gen: Int
    ): MediaPlayer? {
        var player: MediaPlayer? = null
        return try {
            val mp3Bytes = Base64.decode(audioBase64, Base64.DEFAULT)
            if (mp3Bytes.size < 128) return null
            val ttsCacheDir = File(context.cacheDir, "tts").apply { mkdirs() }
            runCatching { File(context.cacheDir, "oms_neural_tts_active.mp3").delete() }
            pruneStaleTtsCacheFiles(ttsCacheDir)

            val cacheFile = File(ttsCacheDir, "oms_neural_${gen and 3}.mp3")
            FileOutputStream(cacheFile).use { fos ->
                fos.write(mp3Bytes)
            }

            val mp = MediaPlayer()
            player = mp
            val audioAttrs = AudioAttributes.Builder()
                .setUsage(if (routing.useAlarmStream) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            mp.setAudioAttributes(audioAttrs)
            if (routing.forceBuiltInSpeaker) {
                try {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    val speakerDev = am
                        ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speakerDev != null) {
                        mp.setPreferredDevice(speakerDev)
                    }
                } catch (_: Throwable) {
                }
            }
            FileInputStream(cacheFile).use { fis ->
                mp.setDataSource(fis.fd)
            }
            mp.prepare()

            var remainingRepeats = (repeatCount - 1).coerceAtLeast(0)
            mp.setOnCompletionListener { completedMp ->
                if (playbackGeneration.get() == gen && remainingRepeats > 0) {
                    remainingRepeats -= 1
                    mainHandler.postDelayed({
                        if (playbackGeneration.get() == gen && activeMediaPlayer === completedMp) {
                            try {
                                completedMp.seekTo(0)
                                completedMp.start()
                            } catch (_: Throwable) {
                                releaseActiveMediaPlayer()
                                onPlaybackFinished(gen)
                            }
                        }
                    }, 420L)
                } else {
                    if (activeMediaPlayer === completedMp) {
                        releaseActiveMediaPlayer()
                    } else {
                        runCatching { completedMp.release() }
                    }
                    onPlaybackFinished(gen)
                }
            }
            mp.setOnErrorListener { errMp, _, _ ->
                if (activeMediaPlayer === errMp) {
                    releaseActiveMediaPlayer()
                } else {
                    runCatching { errMp.release() }
                }
                onPlaybackFinished(gen)
                true
            }
            mp
        } catch (_: Throwable) {
            player?.let { runCatching { it.release() } }
            null
        }
    }

    fun pruneStaleTtsCacheFiles(ttsCacheDir: File, maxAgeMs: Long = 24L * 3600_000L) {
        try {
            val cutoff = System.currentTimeMillis() - maxAgeMs
            ttsCacheDir.listFiles()?.forEach { f ->
                if (f.isFile && f.lastModified() < cutoff) {
                    runCatching { f.delete() }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun enqueueLocalSpeech(
        context: Context,
        gen: Int,
        cleanText: String,
        useAlarmStream: Boolean,
        speechRate: Float,
        repeatCount: Int
    ) {
        pendingSpeechQueue.offer(
            PendingSpeech(
                gen = gen,
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
        activeHardwareStopEnabled = false
        val ctx = context ?: appContextRef
        restoreAlarmVolumeForce(ctx)
        abandonSpeechAudioFocus(ctx)
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
