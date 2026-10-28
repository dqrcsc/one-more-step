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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.UUID
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
    private val onClaimByPhone: (targetMac: String, targetName: String, reason: String) -> Unit,
    private val onStatusChanged: (JSONObject) -> Unit
) {
    companion object {
        private val AAP_UUID = UUID.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")
        private val HFP_UUID = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb")
        private val A2DP_SINK_UUID = UUID.fromString("0000110b-0000-1000-8000-00805f9b34fb")

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

    @Volatile
    private var wasPhonePlayingAudio: Boolean = false

    @Volatile
    private var tempMutedMusicStream: Boolean = false

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
            evaluatePhoneAudioState(configs)
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
                        (normalizeMac(it.address) == mac ||
                            it.productName.toString().contains("airpods", ignoreCase = true))
                }
            ) {
                return true
            }
        } catch (_: Throwable) {
        }
        try {
            val m = BluetoothDevice::class.java.getDeclaredMethod("isConnected")
            m.isAccessible = true
            if (m.invoke(device) as? Boolean == true) {
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
            list.firstOrNull { it.mac == norm }?.let { return it }
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

    fun isPhoneMediaOrCallActive(configs: List<AudioPlaybackConfiguration>? = null): Boolean {
        if (isPhoneInCall()) return true
        if (hasActiveMediaSessionPlaying()) return true
        if (hasActiveMediaPlaybackConfig(configs)) return true
        if (audioManager.isMusicActive) return true
        return false
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

    private fun evaluatePhoneAudioState(configs: List<AudioPlaybackConfiguration>?) {
        val currentlyPlaying = isPhoneMediaOrCallActive(configs)
        val risingEdge = (!wasPhonePlayingAudio && currentlyPlaying)
        wasPhonePlayingAudio = currentlyPlaying

        if (risingEdge &&
            autoSwitchEnabled &&
            SystemClock.elapsedRealtime() >= suppressAutoGrabUntilElapsed
        ) {
            val paired = listPairedAirpods()
            val alreadyConnectedOnPhone = paired.any { it.connected }
            if (paired.isNotEmpty() && !alreadyConnectedOnPhone) {
                // If AirPods are currently connected on Mac, immediately mute phone speaker to prevent leakage
                if (macAirpodsConnected) {
                    tempMutePhoneMusicIfNeeded(2800L)
                }

                // Debounce 320ms to confirm sustained media/call playback (ignores brief notification chimes)
                mainHandler.postDelayed({
                    val stillPlaying = isPhoneMediaOrCallActive(null)
                    val curPaired = listPairedAirpods()
                    val nowConnected = curPaired.any { it.connected }
                    if (!autoSwitchEnabled ||
                        !stillPlaying ||
                        nowConnected ||
                        SystemClock.elapsedRealtime() < suppressAutoGrabUntilElapsed
                    ) {
                        if (!nowConnected) restoreTempMuteOnPhone()
                        return@postDelayed
                    }

                    val target = selectTargetAirpods(macAirpodsMac.ifBlank { preferredAirpodsMac })
                    if (target == null) {
                        restoreTempMuteOnPhone()
                        return@postDelayed
                    }

                    suppressAutoGrabUntilElapsed = SystemClock.elapsedRealtime() + 5000L
                    val reason = if (isPhoneInCall()) "phone_call_started" else "phone_audio_started"
                    onClaimByPhone(target.mac, target.name, reason)
                    connectAirpodsOnPhone(target.mac)
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

    private fun invokeProfileConnect(proxy: Any?, device: BluetoothDevice): Boolean {
        if (proxy == null) return false
        try {
            val setPolicy = proxy.javaClass.methods.firstOrNull {
                it.name == "setConnectionPolicy" && it.parameterTypes.size == 2
            }
            setPolicy?.isAccessible = true
            setPolicy?.invoke(proxy, device, 100)
        } catch (_: Throwable) {
        }
        return try {
            val m = proxy.javaClass.getDeclaredMethod("connect", BluetoothDevice::class.java)
            m.isAccessible = true
            (m.invoke(proxy, device) as? Boolean) == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun invokeProfileDisconnect(proxy: Any?, device: BluetoothDevice): Boolean {
        if (proxy == null) return false
        return try {
            val m = proxy.javaClass.getDeclaredMethod("disconnect", BluetoothDevice::class.java)
            m.isAccessible = true
            (m.invoke(proxy, device) as? Boolean) == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun invokeDeviceConnect(device: BluetoothDevice): Boolean {
        return try {
            val m = BluetoothDevice::class.java.getDeclaredMethod("connect")
            m.isAccessible = true
            val res = m.invoke(device)
            (res as? Boolean == true) || (res as? Int == 0)
        } catch (_: Throwable) {
            false
        }
    }

    private fun invokeDeviceDisconnect(device: BluetoothDevice): Boolean {
        return try {
            val m = BluetoothDevice::class.java.getDeclaredMethod("disconnect")
            m.isAccessible = true
            val res = m.invoke(device)
            (res as? Boolean == true) || (res as? Int == 0)
        } catch (_: Throwable) {
            false
        }
    }

    private fun invokeSetActiveDevice(device: BluetoothDevice) {
        try {
            val a2dp = a2dpProxy
            if (a2dp != null) {
                val m = a2dp.javaClass.getDeclaredMethod("setActiveDevice", BluetoothDevice::class.java)
                m.isAccessible = true
                m.invoke(a2dp, device)
            }
        } catch (_: Throwable) {
        }
        try {
            val adapter = bluetoothAdapter ?: return
            val m = adapter.javaClass.getDeclaredMethod(
                "setActiveDevice",
                BluetoothDevice::class.java,
                Int::class.javaPrimitiveType
            )
            m.isAccessible = true
            m.invoke(adapter, device, 0) // ACTIVE_DEVICE_ALL = 0
        } catch (_: Throwable) {
        }
    }

    private fun wakeAirpodsAclViaRfcomm(device: BluetoothDevice) {
        for (uuid in listOf(AAP_UUID, HFP_UUID, A2DP_SINK_UUID)) {
            var sock: android.bluetooth.BluetoothSocket? = null
            try {
                sock = device.createInsecureRfcommSocketToServiceRecord(uuid)
                sock.connect()
                Thread.sleep(150L)
                sock.close()
                return
            } catch (_: Throwable) {
                try { sock?.close() } catch (_: Throwable) {}
            }
        }
    }

    fun connectAirpodsOnPhone(explicitMac: String? = null, onResult: ((Boolean, String) -> Unit)? = null) {
        val gen = connectGeneration.incrementAndGet()
        suppressAutoGrabUntilElapsed = SystemClock.elapsedRealtime() + 5000L

        val target = selectTargetAirpods(explicitMac)
        if (target == null) {
            restoreTempMuteOnPhone()
            onResult?.invoke(false, "手机已配对蓝牙列表中未找到 AirPods")
            return
        }

        lastActiveAirpodsMac = target.mac
        if (target.connected) {
            invokeSetActiveDevice(target.device)
            restoreTempMuteOnPhone()
            reportStatusIfChanged(force = true)
            onResult?.invoke(true, "AirPods 已连接在手机上")
            return
        }

        scope.launch {
            var connected = false
            val waitDelays = listOf(120L, 550L, 750L, 900L)
            for ((idx, delayMs) in waitDelays.withIndex()) {
                if (connectGeneration.get() != gen) return@launch
                delay(delayMs)
                if (connectGeneration.get() != gen) return@launch

                if (isDeviceConnectedOnPhone(target.device)) {
                    connected = true
                    break
                }

                val c1 = invokeProfileConnect(a2dpProxy, target.device)
                val c2 = invokeProfileConnect(headsetProxy, target.device)
                val c3 = invokeDeviceConnect(target.device)

                if (!c1 && !c2 && !c3 && idx >= 1) {
                    wakeAirpodsAclViaRfcomm(target.device)
                }

                // Poll briefly for connection state
                repeat(4) {
                    delay(140L)
                    if (isDeviceConnectedOnPhone(target.device)) {
                        connected = true
                        return@repeat
                    }
                }
                if (connected) break
            }

            if (connectGeneration.get() == gen) {
                if (connected || isDeviceConnectedOnPhone(target.device)) {
                    connected = true
                    invokeSetActiveDevice(target.device)
                }
                restoreTempMuteOnPhone()
                reportStatusIfChanged(force = true)
                onResult?.invoke(
                    connected,
                    if (connected) "已将 ${target.name} 切换到手机" else "已请求连接 ${target.name}，请确认耳机在耳中"
                )
            }
        }
    }

    fun handleHandoffToMac(
        targetMac: String?,
        pausePhoneMedia: Boolean,
        disconnectOnPhone: Boolean
    ): JSONObject {
        connectGeneration.incrementAndGet()
        suppressAutoGrabUntilElapsed = SystemClock.elapsedRealtime() + 6000L
        restoreTempMuteOnPhone()

        if (pausePhoneMedia && pauseOtherOnSwitch) {
            pausePhoneActiveMedia()
        }

        var disconnectedAny = false
        if (disconnectOnPhone) {
            val paired = listPairedAirpods()
            val norm = normalizeMac(targetMac)
            val targets = paired.filter { it.connected || (norm.isNotEmpty() && it.mac == norm) }
            scope.launch {
                for (item in targets) {
                    val d1 = invokeProfileDisconnect(a2dpProxy, item.device)
                    val d2 = invokeProfileDisconnect(headsetProxy, item.device)
                    val d3 = invokeDeviceDisconnect(item.device)
                    if (d1 || d2 || d3) disconnectedAny = true
                }
                delay(300L)
                reportStatusIfChanged(force = true)
            }
        }

        return JSONObject().apply {
            put("ok", true)
            put("pausedMedia", pausePhoneMedia && pauseOtherOnSwitch)
            put("disconnected", disconnectedAny || disconnectOnPhone)
        }
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
        val mediaPlaying = isPhoneMediaOrCallActive(null)
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
