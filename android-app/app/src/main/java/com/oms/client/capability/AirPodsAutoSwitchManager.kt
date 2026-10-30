package com.oms.client.capability

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.oms.client.otp.OmsNotificationListenerService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.atomic.AtomicInteger

data class PairedAirPodsEntry(
    val name: String,
    val mac: String,
    val connected: Boolean,
    val device: BluetoothDevice
)

/**
 * Seamless AirPods Auto-Switch & Cross-Device Media State Coordinator (Xiaomi Android <-> macOS)
 *
 * - Watches real-time audio output on Android via AudioPlaybackCallback + MediaSessionManager + Call mode.
 * - Filters out brief notification/system chimes (>320ms sustained media/call rising-edge trigger).
 * - Temporarily mutes phone speaker during the ~1.5s handoff from Mac to Phone to prevent audio leakage.
 * - Coordinates with Mac `omsd` + `oms-bt-bridge` so the yielding device pauses active playback and
 *   releases its Bluetooth link while the claiming device connects A2DP + HFP.
 */
@SuppressLint("MissingPermission")
class AirPodsAutoSwitchManager(
    private val context: Context,
    private val onClaimByPhone: suspend (targetMac: String, targetName: String, reason: String) -> (() -> Boolean),
    private val onStatusChanged: (JSONObject) -> Unit
) {
    companion object {
        fun normalizeMac(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            return raw.trim().replace("-", ":").uppercase()
        }
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> }
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    @Volatile
    var autoSwitchEnabled: Boolean = true

    @Volatile
    var pauseOtherOnSwitch: Boolean = true

    @Volatile
    var preferredAirpodsMac: String = ""

    @Volatile
    var macAirpodsConnected: Boolean = false

    @Volatile
    var macAirpodsMac: String = ""

    @Volatile
    var macAirpodsName: String = ""

    @Volatile
    private var lastActiveAirpodsMac: String = ""

    @Volatile
    private var a2dpProxy: BluetoothA2dp? = null

    @Volatile
    private var headsetProxy: BluetoothHeadset? = null

    @Volatile
    private var suppressAutoGrabUntilElapsed: Long = 0L

    fun suppressAutoGrab(durationMs: Long) {
        val target = SystemClock.elapsedRealtime() + durationMs.coerceAtLeast(0L)
        if (target > suppressAutoGrabUntilElapsed) {
            suppressAutoGrabUntilElapsed = target
        }
    }

    @Volatile
    private var wasPhonePlayingAudio: Boolean = false

    @Volatile
    private var tempMutedMusicStream: Boolean = false

    private val autoSwitchTrace = AirPodsAttemptTrace { SystemClock.elapsedRealtime() }
    @Volatile private var playbackCallbackCount = 0L
    @Volatile private var lastPlaybackCallbackElapsed = 0L
    @Volatile private var lastAutoDecision = "not_evaluated"

    private val connectGeneration = AtomicInteger(0)
    private var lastReportedSignature: String = ""
    private var started = false

    private val tempUnmuteRunnable = Runnable {
        restoreTempMuteOnPhone()
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP && proxy is BluetoothA2dp) {
                a2dpProxy = proxy
            } else if (profile == BluetoothProfile.HEADSET && proxy is BluetoothHeadset) {
                headsetProxy = proxy
            }
            reportStatusIfChanged(force = false)
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) {
                a2dpProxy = null
            } else if (profile == BluetoothProfile.HEADSET) {
                headsetProxy = null
            }
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            playbackCallbackCount += 1
            lastPlaybackCallbackElapsed = SystemClock.elapsedRealtime()
            evaluatePhoneAudioState()
        }
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    val dev = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (state == BluetoothProfile.STATE_CONNECTED && dev != null && isAirpodsDevice(dev)) {
                        lastActiveAirpodsMac = normalizeMac(dev.address)
                        restoreTempMuteOnPhone()
                    }
                    reportStatusIfChanged(force = true)
                }
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val dev = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (dev != null && isAirpodsDevice(dev)) {
                        if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
                            lastActiveAirpodsMac = normalizeMac(dev.address)
                        }
                        mainHandler.postDelayed({ reportStatusIfChanged(force = true) }, 250L)
                    }
                }
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                HiddenApiBypass.addHiddenApiExemptions(
                    "Landroid/bluetooth/",
                    "Landroid/media/"
                )
            }
        } catch (_: Throwable) {
        }

        try {
            bluetoothAdapter?.getProfileProxy(context, profileListener, BluetoothProfile.A2DP)
            bluetoothAdapter?.getProfileProxy(context, profileListener, BluetoothProfile.HEADSET)
        } catch (_: Throwable) {
        }

        try {
            audioManager.registerAudioPlaybackCallback(playbackCallback, mainHandler)
        } catch (_: Throwable) {
        }

        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(btReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(btReceiver, filter)
            }
        } catch (_: Throwable) {
        }

        mainHandler.postDelayed({ reportStatusIfChanged(force = true) }, 600L)
    }

    private fun hasBtConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isAirpodsDevice(device: BluetoothDevice): Boolean {
        if (!hasBtConnectPermission()) return false
        val name = try { device.name ?: "" } catch (_: Throwable) { "" }
        val mac = normalizeMac(try { device.address } catch (_: Throwable) { "" })
        if (mac.isEmpty()) return false
        return name.contains("airpods", ignoreCase = true) ||
            (preferredAirpodsMac.isNotEmpty() && mac == normalizeMac(preferredAirpodsMac)) ||
            (macAirpodsMac.isNotEmpty() && mac == normalizeMac(macAirpodsMac))
    }

    private fun isDeviceConnectedOnPhone(device: BluetoothDevice): Boolean {
        if (!hasBtConnectPermission()) return false
        val mac = normalizeMac(device.address)
        try {
            val a2dp = a2dpProxy
            if (a2dp != null && a2dp.connectedDevices.any { normalizeMac(it.address) == mac }) {
                return true
            }
        } catch (_: Throwable) {
        }
        try {
            val hfp = headsetProxy
            if (hfp != null && hfp.connectedDevices.any { normalizeMac(it.address) == mac }) {
                return true
            }
        } catch (_: Throwable) {
        }
        try {
            val outs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            if (outs.any {
                    (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) &&
                        normalizeMac(it.address) == mac
                }
            ) {
                return true
            }
        } catch (_: Throwable) {
        }
        return false
    }

    fun listPairedAirpods(): List<PairedAirPodsEntry> {
        if (!hasBtConnectPermission()) return emptyList()
        val adapter = bluetoothAdapter ?: return emptyList()
        val bonded = try { adapter.bondedDevices ?: emptySet() } catch (_: Throwable) { emptySet() }
        val prefNorm = normalizeMac(preferredAirpodsMac)
        val macHostNorm = normalizeMac(macAirpodsMac)
        val lastNorm = normalizeMac(lastActiveAirpodsMac)

        val entries = mutableListOf<PairedAirPodsEntry>()
        for (dev in bonded) {
            if (!isAirpodsDevice(dev)) continue
            val name = try { dev.name ?: "AirPods" } catch (_: Throwable) { "AirPods" }
            val mac = normalizeMac(dev.address)
            val connected = isDeviceConnectedOnPhone(dev)
            if (connected) {
                lastActiveAirpodsMac = mac
            }
            entries.add(PairedAirPodsEntry(name = name, mac = mac, connected = connected, device = dev))
        }

        entries.sortByDescending { item ->
            var score = 0
            if (item.connected) score += 1000
            if (macHostNorm.isNotEmpty() && item.mac == macHostNorm) score += 500
            if (prefNorm.isNotEmpty() && item.mac == prefNorm) score += 300
            if (lastNorm.isNotEmpty() && item.mac == lastNorm) score += 200
            if (item.name.trim().equals("AirPods", ignoreCase = true)) score += 100
            if (item.mac == "B4:59:76:DB:BF:81") score += 50
            score
        }
        return entries
    }

    fun selectTargetAirpods(explicitMac: String? = null): PairedAirPodsEntry? {
        val list = listPairedAirpods()
        if (list.isEmpty()) return null
        val norm = normalizeMac(explicitMac)
        if (norm.isNotEmpty()) {
            return list.firstOrNull { it.mac == norm }
        }
        return list.first()
    }

    fun isPhoneInCall(): Boolean {
        return try {
            val mode = audioManager.mode
            mode == AudioManager.MODE_IN_CALL ||
                mode == AudioManager.MODE_IN_COMMUNICATION ||
                mode == AudioManager.MODE_RINGTONE
        } catch (_: Throwable) {
            false
        }
    }

    private fun hasActiveMediaSessionPlaying(): Boolean {
        try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return false
            val cn = ComponentName(context, OmsNotificationListenerService::class.java)
            val controllers = msm.getActiveSessions(cn)
            for (ctrl in controllers) {
                if (ctrl.packageName == context.packageName) continue
                val state = ctrl.playbackState?.state
                if (state == PlaybackState.STATE_PLAYING) {
                    return true
                }
            }
        } catch (_: Throwable) {
        }
        return false
    }

    private fun hasActiveMediaPlaybackConfig(configs: List<AudioPlaybackConfiguration>?): Boolean {
        val list = configs ?: try { audioManager.activePlaybackConfigurations } catch (_: Throwable) { emptyList() }
        if (list.isEmpty()) return false
        for (cfg in list) {
            try {
                val attr = cfg.audioAttributes ?: continue
                val usage = attr.usage
                val isMediaOrVoiceUsage =
                    usage == AudioAttributes.USAGE_MEDIA ||
                        usage == AudioAttributes.USAGE_GAME ||
                        usage == AudioAttributes.USAGE_VOICE_COMMUNICATION ||
                        usage == AudioAttributes.USAGE_UNKNOWN
                if (!isMediaOrVoiceUsage) continue

                // Check player state via reflection (PLAYER_STATE_STARTED == 2) if available
                val stateMethod = try {
                    AudioPlaybackConfiguration::class.java.getDeclaredMethod("getPlayerState").apply {
                        isAccessible = true
                    }
                } catch (_: Throwable) {
                    null
                }
                if (stateMethod != null) {
                    val playerState = stateMethod.invoke(cfg) as? Int ?: -1
                    if (playerState == 2) {
                        return true
                    }
                } else if (audioManager.isMusicActive) {
                    return true
                }
            } catch (_: Throwable) {
            }
        }
        return false
    }

    fun isPhoneMediaOrCallActive(): Boolean {
        // Never treat our own Find-My-Phone or TTS voice broadcast as a reason to grab AirPods from Mac!
        return AirPodsMediaActivity.isActive(
            NotificationExecutor.isSpeakingFlow.value, isPhoneInCall(),
            hasActiveMediaSessionPlaying(), audioManager.isMusicActive
        )
    }

    private fun tempMutePhoneMusicIfNeeded(maxDurationMs: Long = 2600L) {
        mainHandler.post {
            try {
                if (!tempMutedMusicStream && !audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                    tempMutedMusicStream = true
                }
                mainHandler.removeCallbacks(tempUnmuteRunnable)
                mainHandler.postDelayed(tempUnmuteRunnable, maxDurationMs)
            } catch (_: Throwable) {
            }
        }
    }

    private fun restoreTempMuteOnPhone() {
        mainHandler.post {
            mainHandler.removeCallbacks(tempUnmuteRunnable)
            if (tempMutedMusicStream) {
                try {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                } catch (_: Throwable) {
                }
                tempMutedMusicStream = false
            }
        }
    }

    private fun autoSwitchDiagnostics(): JSONObject = JSONObject().apply {
        put("sampledAtMs", System.currentTimeMillis())
        put("mediaSessionPlaying", hasActiveMediaSessionPlaying())
        put("playbackConfigActive", hasActiveMediaPlaybackConfig(null))
        put("musicActive", runCatching { audioManager.isMusicActive }.getOrDefault(false))
        put("inCall", isPhoneInCall())
        put("omsSpeaking", NotificationExecutor.isSpeakingFlow.value)
        put("callbackCount", playbackCallbackCount)
        put("lastCallbackAgeMs", if (lastPlaybackCallbackElapsed == 0L) -1L else SystemClock.elapsedRealtime() - lastPlaybackCallbackElapsed)
        put("lastDecision", lastAutoDecision)
        put("lastEvaluatedPlaying", wasPhonePlayingAudio)
        put("suppressionRemainingMs", (suppressAutoGrabUntilElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        put("macConnected", macAirpodsConnected)
        put("trace", JSONObject(autoSwitchTrace.snapshot()))
        try {
            val sessions = context.getSystemService(MediaSessionManager::class.java)
                ?.getActiveSessions(ComponentName(context, OmsNotificationListenerService::class.java)) ?: emptyList()
            put("sessions", JSONArray().apply {
                sessions.take(16).forEach { ctrl -> put(JSONObject().apply {
                    put("package", ctrl.packageName)
                    put("state", ctrl.playbackState?.state ?: -1)
                }) }
            })
        } catch (e: Exception) { put("sessionReadError", e.javaClass.simpleName) }
        try {
            put("players", JSONArray().apply {
                audioManager.activePlaybackConfigurations.take(16).forEach { cfg -> put(JSONObject().apply {
                    put("usage", cfg.audioAttributes.usage)
                    try {
                        val method = AudioPlaybackConfiguration::class.java.getDeclaredMethod("getPlayerState").apply { isAccessible = true }
                        put("state", method.invoke(cfg) as? Int ?: -1)
                    } catch (e: Exception) { put("stateReadError", e.javaClass.simpleName) }
                }) }
            })
        } catch (e: Exception) { put("playerReadError", e.javaClass.simpleName) }
    }

    private fun evaluatePhoneAudioState() {
        val currentlyPlaying = isPhoneMediaOrCallActive()
        val risingEdge = (!wasPhonePlayingAudio && currentlyPlaying)
        wasPhonePlayingAudio = currentlyPlaying
        lastAutoDecision = when {
            !currentlyPlaying -> "not_playing"
            !risingEdge -> "no_rising_edge"
            !autoSwitchEnabled -> "disabled"
            SystemClock.elapsedRealtime() < suppressAutoGrabUntilElapsed -> "suppressed"
            else -> "rising_edge"
        }
        autoSwitchTrace.record(lastAutoDecision, mapOf("playing" to currentlyPlaying, "callbackCount" to playbackCallbackCount))

        if (risingEdge &&
            autoSwitchEnabled &&
            SystemClock.elapsedRealtime() >= suppressAutoGrabUntilElapsed
        ) {
            val paired = listPairedAirpods()
            val alreadyConnectedOnPhone = paired.any { it.connected }
            if (paired.isEmpty() || alreadyConnectedOnPhone) {
                lastAutoDecision = if (paired.isEmpty()) "no_paired_target" else "already_connected"
                autoSwitchTrace.record(lastAutoDecision)
            }
            if (paired.isNotEmpty() && !alreadyConnectedOnPhone) {
                // If AirPods are currently connected on Mac, immediately mute phone speaker to prevent leakage
                if (macAirpodsConnected) {
                    tempMutePhoneMusicIfNeeded(2800L)
                }

                // Debounce 320ms to confirm sustained media/call playback (ignores brief notification chimes)
                mainHandler.postDelayed({
                    val stillPlaying = isPhoneMediaOrCallActive()
                    val curPaired = listPairedAirpods()
                    val nowConnected = curPaired.any { it.connected }
                    if (!autoSwitchEnabled ||
                        !stillPlaying ||
                        nowConnected ||
                        SystemClock.elapsedRealtime() < suppressAutoGrabUntilElapsed
                    ) {
                        lastAutoDecision = "debounce_cancelled"
                        autoSwitchTrace.record(lastAutoDecision, mapOf("stillPlaying" to stillPlaying, "connected" to nowConnected))
                        if (!nowConnected) restoreTempMuteOnPhone()
                        return@postDelayed
                    }

                    val target = selectTargetAirpods(macAirpodsMac.ifBlank { preferredAirpodsMac })
                    if (target == null) {
                        lastAutoDecision = "target_not_found"
                        autoSwitchTrace.record(lastAutoDecision)
                        restoreTempMuteOnPhone()
                        return@postDelayed
                    }

                    suppressAutoGrabUntilElapsed = SystemClock.elapsedRealtime() + 5000L
                    val reason = if (isPhoneInCall()) "phone_call_started" else "phone_audio_started"
                    lastAutoDecision = "claim_sent"
                    autoSwitchTrace.record(lastAutoDecision, mapOf("reason" to reason))
                    val claimGeneration = connectGeneration.incrementAndGet()
                    scope.launch {
                        try {
                            val ok = AirPodsAutoClaim.run(
                                stillWanted = {
                                    connectGeneration.get() == claimGeneration && autoSwitchEnabled &&
                                        isPhoneMediaOrCallActive()
                                },
                                release = { onClaimByPhone(target.mac, target.name, reason) },
                                connect = { valid ->
                                    connectAirpodsOnPhone(target.mac, sessionStillValid = valid,
                                        reservedGeneration = claimGeneration).first
                                }
                            )
                            lastAutoDecision = if (ok) "claim_connected" else "claim_cancelled_or_unconfirmed"
                            autoSwitchTrace.record(lastAutoDecision)
                        } catch (e: Exception) {
                            lastAutoDecision = "claim_failed"
                            autoSwitchTrace.record(lastAutoDecision, mapOf("error" to e.javaClass.simpleName,
                                "message" to (e.message ?: "").take(200)))
                            if (e is CancellationException) throw e
                        } finally {
                            if (connectGeneration.get() == claimGeneration) restoreTempMuteOnPhone()
                        }
                    }
                }, 320L)
            }
        }

        reportStatusIfChanged(force = false)
    }

    /**
     * Explicitly pauses active media playback on the phone without ever toggling a paused player on.
     */
    fun pausePhoneActiveMedia() {
        suppressAutoGrabUntilElapsed = SystemClock.elapsedRealtime() + 5500L
        wasPhonePlayingAudio = false

        // 1. Pause via MediaSessionManager (exact controller pause via OmsNotificationListenerService)
        try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            if (msm != null) {
                val cn = ComponentName(context, OmsNotificationListenerService::class.java)
                val controllers = msm.getActiveSessions(cn)
                for (ctrl in controllers) {
                    val state = ctrl.playbackState?.state
                    if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING) {
                        ctrl.transportControls.pause()
                    }
                }
            }
        } catch (_: Throwable) {
        }

        // 2. Dispatch explicit KEYCODE_MEDIA_PAUSE (127) — strictly pauses, never starts playback
        try {
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0)
            val up = KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0)
            audioManager.dispatchMediaKeyEvent(down)
            audioManager.dispatchMediaKeyEvent(up)
        } catch (_: Throwable) {
        }

        // 3. Transient AudioFocus pulse to pause apps like Douyin / WeChat voice that only respect AudioFocus
        try {
            if (audioManager.isMusicActive) {
                val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { }
                    .build()
                audioManager.requestAudioFocus(focusReq)
                mainHandler.postDelayed({
                    try {
                        audioManager.abandonAudioFocusRequest(focusReq)
                    } catch (_: Throwable) {
                    }
                }, 380L)
            }
        } catch (_: Throwable) {
        }
    }

    private fun traceCall(trace: AirPodsAttemptTrace?, stage: String, body: () -> Any?): Any? {
        return try {
            body().also { trace?.record(stage, mapOf("result" to (it?.toString() ?: "null"))) }
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            trace?.record(stage, mapOf("error" to cause.javaClass.simpleName, "message" to (cause.message ?: "").take(300)))
            null
        }
    }

    private fun invokeProfileConnect(proxy: Any?, device: BluetoothDevice, trace: AirPodsAttemptTrace? = null): Boolean {
        val kind = proxy?.javaClass?.simpleName ?: "profile_unavailable"
        if (proxy == null) { trace?.record(kind); return false }
        return traceCall(trace, "$kind.connect") {
            proxy.javaClass.getDeclaredMethod("connect", BluetoothDevice::class.java)
                .apply { isAccessible = true }.invoke(proxy, device)
        } == true
    }

    private fun invokeProfileDisconnect(proxy: Any?, device: BluetoothDevice, trace: AirPodsAttemptTrace? = null): Boolean {
        if (proxy == null) { trace?.record("disconnect_profile_unavailable"); return false }
        return traceCall(trace, "${proxy.javaClass.simpleName}.disconnect") {
            proxy.javaClass.getDeclaredMethod("disconnect", BluetoothDevice::class.java)
                .apply { isAccessible = true }.invoke(proxy, device)
        } == true
    }

    private fun invokeDeviceDisconnect(device: BluetoothDevice, trace: AirPodsAttemptTrace? = null): Boolean {
        val result = traceCall(trace, "BluetoothDevice.disconnect") {
            BluetoothDevice::class.java.getDeclaredMethod("disconnect").apply { isAccessible = true }.invoke(device)
        }
        return result == true || result == 0
    }

    private fun invokeSetActiveDevice(device: BluetoothDevice, trace: AirPodsAttemptTrace? = null) {
        val proxy: Any = (if (isPhoneInCall()) headsetProxy else a2dpProxy) ?: return
        traceCall(trace, "${proxy.javaClass.simpleName}.setActiveDevice") {
            proxy.javaClass.getDeclaredMethod("setActiveDevice", BluetoothDevice::class.java)
                .apply { isAccessible = true }.invoke(proxy, device)
        }
    }

    private fun desiredProfileState(device: BluetoothDevice): Int = try {
        if (isPhoneInCall()) headsetProxy?.getConnectionState(device) ?: -1
        else a2dpProxy?.getConnectionState(device) ?: -1
    } catch (_: Throwable) { -1 }

    private fun isTargetActive(device: BluetoothDevice): Boolean {
        val mac = normalizeMac(device.address)
        // A connected ACL or an available audio output is not proof of the active route.
        val proxy: Any = (if (isPhoneInCall()) headsetProxy else a2dpProxy) ?: return false
        return try {
            val active = proxy.javaClass.getMethod("getActiveDevice").invoke(proxy) as? BluetoothDevice
            active != null && normalizeMac(active.address) == mac
        } catch (_: Throwable) { false }
    }

    suspend fun connectAirpodsOnPhone(
        explicitMac: String? = null,
        deadlineMs: Long = System.currentTimeMillis() + 12000L,
        sessionStillValid: () -> Boolean = { true },
        reservedGeneration: Int? = null
    ): Triple<Boolean, String, JSONObject> {
        val trace = AirPodsAttemptTrace { SystemClock.elapsedRealtime() }
        val gen = reservedGeneration ?: connectGeneration.incrementAndGet()
        if (connectGeneration.get() != gen || !sessionStillValid()) {
            trace.record("cancelled_before_connect")
            return Triple(false, "AirPods 请求已失效", JSONObject(trace.snapshot()))
        }
        trace.record("started", mapOf("generation" to gen, "permission" to hasBtConnectPermission()))
        val budget = (deadlineMs - System.currentTimeMillis()).coerceIn(0L, 12000L)
        val deadlineElapsed = SystemClock.elapsedRealtime() + budget
        suppressAutoGrabUntilElapsed = deadlineElapsed + 1000L
        val target = selectTargetAirpods(explicitMac)
        if (target == null) {
            restoreTempMuteOnPhone()
            trace.record("target_not_found")
            return Triple(false, "手机已配对蓝牙列表中未找到指定 AirPods", JSONObject(trace.snapshot()))
        }
        lastActiveAirpodsMac = target.mac
        var previousProfileState: Int? = null
        fun observedProfileState(): Int {
            val state = desiredProfileState(target.device)
            if (state != previousProfileState) {
                trace.record("profile_state", mapOf("state" to state))
                previousProfileState = state
            }
            return state
        }
        return try {
            val ok = AirPodsConnectionAttempt.run(
                budgetMs = (deadlineElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L),
                now = { SystemClock.elapsedRealtime() },
                stillValid = { connectGeneration.get() == gen && sessionStillValid() },
                connected = { observedProfileState() == BluetoothProfile.STATE_CONNECTED },
                active = { isTargetActive(target.device) },
                connect = {
                    trace.record("connect_requested", mapOf("profileState" to desiredProfileState(target.device), "active" to isTargetActive(target.device)))
                    // Request only the relevant profile; policy changes/device-wide calls
                    // require privileged system permissions and are not connection fallbacks.
                    invokeProfileConnect(if (isPhoneInCall()) headsetProxy else a2dpProxy, target.device, trace)
                },
                activate = { invokeSetActiveDevice(target.device, trace) },
                pause = { delay(it) },
                connecting = { observedProfileState() == BluetoothProfile.STATE_CONNECTING }
            )
            trace.record("finished", mapOf("ok" to ok, "sessionValid" to sessionStillValid(), "generationValid" to (gen == connectGeneration.get()), "connected" to isDeviceConnectedOnPhone(target.device), "active" to isTargetActive(target.device)))
            Triple(ok, if (ok) "已将 ${target.name} 切换到手机并确认活动音频设备"
                else "AirPods 切换未确认，请查看连接诊断", JSONObject(trace.snapshot()))
        } finally {
            if (connectGeneration.get() == gen) restoreTempMuteOnPhone()
            reportStatusIfChanged(force = true)
        }
    }

    suspend fun handleHandoffToMac(
        targetMac: String?,
        pausePhoneMedia: Boolean,
        disconnectOnPhone: Boolean,
        deadlineMs: Long = System.currentTimeMillis() + 3000L,
        sessionStillValid: () -> Boolean = { true }
    ): JSONObject {
        val trace = AirPodsAttemptTrace { SystemClock.elapsedRealtime() }
        val gen = connectGeneration.incrementAndGet()
        trace.record("handoff_started", mapOf("generation" to gen))
        val deadline = SystemClock.elapsedRealtime() +
            (deadlineMs - System.currentTimeMillis()).coerceIn(0L, 3000L)
        fun valid() = gen == connectGeneration.get() && sessionStillValid() &&
            SystemClock.elapsedRealtime() < deadline
        suppressAutoGrabUntilElapsed = SystemClock.elapsedRealtime() + 26000L
        restoreTempMuteOnPhone()
        val norm = normalizeMac(targetMac)
        val targets = listPairedAirpods().filter { if (norm.isNotEmpty()) it.mac == norm else it.connected }
        var disconnected = !disconnectOnPhone
        try {
            currentCoroutineContext().ensureActive()
            if (valid()) {
                if (pausePhoneMedia && pauseOtherOnSwitch) pausePhoneActiveMedia()
                if (disconnectOnPhone) {
                    for (item in targets) {
                        currentCoroutineContext().ensureActive()
                        if (!valid()) break
                        invokeProfileDisconnect(a2dpProxy, item.device, trace)
                        invokeProfileDisconnect(headsetProxy, item.device, trace)
                        invokeDeviceDisconnect(item.device, trace)
                    }
                    while (valid()) {
                        currentCoroutineContext().ensureActive()
                        val released = hasBtConnectPermission() && targets.all { item ->
                            try {
                                a2dpProxy?.getConnectionState(item.device) == BluetoothProfile.STATE_DISCONNECTED &&
                                    headsetProxy?.getConnectionState(item.device) == BluetoothProfile.STATE_DISCONNECTED
                            } catch (_: Throwable) { false }
                        }
                        if (released) {
                            disconnected = true
                            break
                        }
                        delay(100L)
                    }
                }
            }
            return JSONObject().apply {
                trace.record("handoff_finished", mapOf("ok" to (valid() && disconnected), "disconnected" to disconnected))
                put("diagnostics", JSONObject(trace.snapshot()))
                put("ok", valid() && disconnected)
                put("disconnected", disconnected)
                put("message", if (disconnected) "手机音频连接已释放" else "手机音频连接释放未确认")
            }
        } finally { reportStatusIfChanged(force = true) }
    }

    fun updateSyncConfig(params: JSONObject) {
        if (params.has("autoSwitchAirpods")) {
            autoSwitchEnabled = params.optBoolean("autoSwitchAirpods", true)
        }
        if (params.has("pauseOtherOnAirpodsSwitch")) {
            pauseOtherOnSwitch = params.optBoolean("pauseOtherOnAirpodsSwitch", true)
        }
        if (params.has("preferredAirpodsMac")) {
            preferredAirpodsMac = normalizeMac(params.optString("preferredAirpodsMac", ""))
        }
        if (params.has("macAirpodsConnected")) {
            val nowMacConn = params.optBoolean("macAirpodsConnected", false)
            macAirpodsConnected = nowMacConn
        }
        if (params.has("macAirpodsMac")) {
            val m = normalizeMac(params.optString("macAirpodsMac", ""))
            if (m.isNotEmpty()) {
                macAirpodsMac = m
                lastActiveAirpodsMac = m
            }
        }
        if (params.has("macAirpodsName")) {
            macAirpodsName = params.optString("macAirpodsName", "")
        }
    }

    fun buildStatusJson(): JSONObject {
        val paired = listPairedAirpods()
        val connectedEntry = paired.firstOrNull { it.connected }
        val firstEntry = connectedEntry ?: paired.firstOrNull()
        val mediaPlaying = isPhoneMediaOrCallActive()
        val inCall = isPhoneInCall()

        val pairedArr = JSONArray()
        for (item in paired) {
            pairedArr.put(
                JSONObject().apply {
                    put("name", item.name)
                    put("mac", item.mac)
                    put("connected", item.connected)
                }
            )
        }

        return JSONObject().apply {
            put("autoSwitchEnabled", autoSwitchEnabled)
            put("autoSwitchDiagnostics", autoSwitchDiagnostics())
            put("connected", connectedEntry != null)
            put("deviceName", firstEntry?.name ?: "")
            put("deviceMac", firstEntry?.mac ?: "")
            put("mediaPlaying", mediaPlaying)
            put("inCall", inCall)
            put("pairedAirpods", pairedArr)
        }
    }

    fun reportStatusIfChanged(force: Boolean) {
        val json = buildStatusJson()
        val sig = "${json.optBoolean("connected")}|${json.optString("deviceMac")}|${json.optBoolean("mediaPlaying")}|${json.optBoolean("inCall")}|${json.optJSONArray("pairedAirpods")}"
        if (!force && sig == lastReportedSignature) return
        lastReportedSignature = sig
        onStatusChanged(json)
    }
}
