package com.oms.client.cockpit

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import com.oms.client.capability.NotificationExecutor
import com.oms.core.ai.EdgeAiRuntimeConfig
import com.oms.core.ai.RealtimeBackendMode
import com.oms.core.ai.RealtimeSessionListener
import com.oms.core.ai.RealtimeSessionOptions
import com.oms.core.ai.RealtimeVoiceSessionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

data class LiveVoiceUiState(
    val isActive: Boolean = false,
    val isConnecting: Boolean = false,
    val mode: RealtimeBackendMode = RealtimeBackendMode.OPENAI_WALK_TALK,
    val englishOnly: Boolean = false,
    val modelLabel: String = "",
    val micMuted: Boolean = false,
    val speakerBoostEnabled: Boolean = true,
    val usingSpeakerphone: Boolean = true,
    val videoFramesSent: Int = 0,
    val audioLevel: Float = 0f,
    val assistantSpeaking: Boolean = false,
    val latestUserTranscript: String = "",
    val latestAssistantTranscript: String = "",
    val transcriptTurns: List<Pair<String, String>> = emptyList(),
    val errorText: String? = null
)

/**
 * Milestone 4 (v2.0): Full-Duplex Hardware Audio + WebSocket Controller for:
 *  - Walk & Talk (`gpt-realtime-2.1` + AirPods / speakerphone + `[🇬🇧 English Only]` mode)
 *  - Google Glass Live Camera (`gemini-3.8-live` 1fps JPEG vision + bidirectional voice)
 */
class LiveAudioDuplexController(
    private val appContext: Context,
    httpClientProvider: () -> OkHttpClient,
    runtimeConfigProvider: () -> EdgeAiRuntimeConfig
) {
    private val engine = RealtimeVoiceSessionEngine(httpClientProvider, runtimeConfigProvider)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _stateFlow = MutableStateFlow(LiveVoiceUiState())
    val stateFlow: StateFlow<LiveVoiceUiState> = _stateFlow

    @Volatile
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var audioTrack: AudioTrack? = null
    @Volatile
    private var echoCanceler: AcousticEchoCanceler? = null
    @Volatile
    private var noiseSuppressor: NoiseSuppressor? = null
    @Volatile
    private var savedVoiceCallVolume: Int? = null
    @Volatile
    private var pendingLatestCameraJpeg: ByteArray? = null

    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    private val playbackQueue = LinkedBlockingQueue<ByteArray>(512)
    private val currentAssistantText = StringBuilder()

    fun start(options: RealtimeSessionOptions) {
        val keepBoost = _stateFlow.value.speakerBoostEnabled
        stop()
        NotificationExecutor.stopSpeaking(appContext)
        pendingLatestCameraJpeg = null
        val onSpeaker = setupAudioRoute(enableCommunicationMode = true)
        _stateFlow.value = LiveVoiceUiState(
            isActive = true,
            isConnecting = true,
            mode = options.mode,
            englishOnly = options.englishOnlyFluencyMode,
            speakerBoostEnabled = keepBoost,
            usingSpeakerphone = onSpeaker
        )

        startPlaybackPipeline()

        engine.startSession(
            options = options,
            listener = object : RealtimeSessionListener {
                override fun onSessionReady(mode: RealtimeBackendMode, modelId: String) {
                    _stateFlow.value = _stateFlow.value.copy(
                        isConnecting = false,
                        mode = mode,
                        modelLabel = modelId,
                        errorText = null
                    )
                    startMicCapturePipeline()
                    // Immediately flush any camera frame captured while WebSocket setup was completing!
                    if (mode == RealtimeBackendMode.GEMINI_GLASS_LIVE) {
                        pendingLatestCameraJpeg?.let { firstJpeg ->
                            pendingLatestCameraJpeg = null
                            engine.sendCameraJpegFrame(firstJpeg)
                            _stateFlow.value = _stateFlow.value.copy(
                                videoFramesSent = _stateFlow.value.videoFramesSent + 1
                            )
                        }
                    }
                }

                override fun onPcm24kAudioOut(pcm24kMonoLe: ByteArray) {
                    _stateFlow.value = _stateFlow.value.copy(assistantSpeaking = true)
                    playbackQueue.offer(pcm24kMonoLe)
                }

                override fun onUserTranscript(text: String, isFinal: Boolean) {
                    val turns = (_stateFlow.value.transcriptTurns + ("我" to text)).takeLast(24)
                    _stateFlow.value = _stateFlow.value.copy(
                        latestUserTranscript = text,
                        transcriptTurns = turns
                    )
                }

                override fun onAssistantTranscriptDelta(delta: String) {
                    currentAssistantText.append(delta)
                    _stateFlow.value = _stateFlow.value.copy(
                        latestAssistantTranscript = currentAssistantText.toString()
                    )
                }

                override fun onTurnComplete() {
                    val completedText = currentAssistantText.toString().trim()
                    currentAssistantText.setLength(0)
                    val turns = if (completedText.isNotEmpty()) {
                        (_stateFlow.value.transcriptTurns + ("AI" to completedText)).takeLast(24)
                    } else {
                        _stateFlow.value.transcriptTurns
                    }
                    _stateFlow.value = _stateFlow.value.copy(
                        assistantSpeaking = false,
                        transcriptTurns = turns
                    )
                }

                override fun onInterruptedBargeIn() {
                    // User spoke over AI -> instantly flush queued & buffered audio!
                    playbackQueue.clear()
                    runCatching {
                        audioTrack?.pause()
                        audioTrack?.flush()
                        audioTrack?.play()
                    }
                    currentAssistantText.setLength(0)
                    _stateFlow.value = _stateFlow.value.copy(assistantSpeaking = false)
                }

                override fun onError(message: String) {
                    _stateFlow.value = _stateFlow.value.copy(
                        isConnecting = false,
                        errorText = message
                    )
                }

                override fun onClosed() {
                    stopAudioHardwareOnly()
                    _stateFlow.value = _stateFlow.value.copy(
                        isActive = false,
                        isConnecting = false,
                        assistantSpeaking = false
                    )
                }
            }
        )
    }

    fun setMicMuted(muted: Boolean) {
        _stateFlow.value = _stateFlow.value.copy(micMuted = muted)
        if (muted) {
            engine.notifyMicStreamEnd()
        }
    }

    fun setSpeakerBoostEnabled(enabled: Boolean) {
        _stateFlow.value = _stateFlow.value.copy(speakerBoostEnabled = enabled)
    }

    fun sendCameraJpegFrameIfGlassActive(jpegBytes: ByteArray) {
        if (jpegBytes.isEmpty()) return
        val st = _stateFlow.value
        if (!st.isActive) return
        if (st.isConnecting) {
            // Buffer latest frame during handshake so millisecond-zero vision is available upon setupComplete
            pendingLatestCameraJpeg = jpegBytes
            return
        }
        if (st.mode == RealtimeBackendMode.GEMINI_GLASS_LIVE) {
            engine.sendCameraJpegFrame(jpegBytes)
            _stateFlow.value = _stateFlow.value.copy(videoFramesSent = _stateFlow.value.videoFramesSent + 1)
        }
    }

    fun stop() {
        pendingLatestCameraJpeg = null
        engine.stopSession()
        stopAudioHardwareOnly()
        _stateFlow.value = _stateFlow.value.copy(
            isActive = false,
            isConnecting = false,
            assistantSpeaking = false,
            audioLevel = 0f
        )
    }

    @SuppressLint("MissingPermission")
    private fun startMicCapturePipeline() {
        captureJob?.cancel()
        captureJob = scope.launch {
            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3200)

            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
            } catch (e: Throwable) {
                _stateFlow.value = _stateFlow.value.copy(errorText = "无法打开麦克风：${e.message}")
                return@launch
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { recorder.release() }
                _stateFlow.value = _stateFlow.value.copy(errorText = "请授予麦克风录音权限后再开启 Live")
                return@launch
            }
            audioRecord = recorder
            runCatching {
                if (AcousticEchoCanceler.isAvailable()) {
                    echoCanceler = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
                }
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true }
                }
            }
            recorder.startRecording()

            // 60ms chunk @ 16kHz PCM16 mono = 1920 bytes
            val buf = ByteArray(1920)
            while (isActive && engine.isActive()) {
                val read = recorder.read(buf, 0, buf.size)
                if (read > 0) {
                    if (!_stateFlow.value.micMuted) {
                        val slice = if (read == buf.size) buf.copyOf() else buf.copyOfRange(0, read)
                        val rms = computeNormalizedRms(slice)
                        _stateFlow.value = _stateFlow.value.copy(audioLevel = rms)
                        engine.sendMicPcm16(slice, sampleRate)
                    } else {
                        _stateFlow.value = _stateFlow.value.copy(audioLevel = 0f)
                    }
                }
            }
        }
    }

    private fun startPlaybackPipeline() {
        playbackJob?.cancel()
        playbackQueue.clear()
        playbackJob = scope.launch {
            val outRate = 24000
            val minOut = AudioTrack.getMinBufferSize(
                outRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4800)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(outRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minOut * 3)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            runCatching { track.setVolume(AudioTrack.getMaxVolume()) }
            audioTrack = track
            track.play()

            while (isActive) {
                val chunk = try {
                    playbackQueue.take()
                } catch (_: InterruptedException) {
                    break
                }
                if (chunk.isEmpty()) continue
                val st = _stateFlow.value
                val outChunk = if (st.speakerBoostEnabled) {
                    val gain = if (st.usingSpeakerphone) 2.35f else 1.45f
                    applyPcm16SoftLimiterGain(chunk, gain)
                } else {
                    chunk
                }
                val outRms = computeNormalizedRms(outChunk)
                if (st.assistantSpeaking) {
                    _stateFlow.value = st.copy(audioLevel = outRms)
                }
                runCatching {
                    track.write(outChunk, 0, outChunk.size)
                }
            }
        }
    }

    /**
     * Routes audio explicitly to `TYPE_BUILTIN_SPEAKER` on Android 12+ (`API 31+`) when no headset is worn
     * (fixing Xiaomi HyperOS earpiece low-volume routing) and aligns `STREAM_VOICE_CALL` loudness with `STREAM_MUSIC`.
     */
    private fun setupAudioRoute(enableCommunicationMode: Boolean): Boolean {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true
        var usingSpeaker = true
        runCatching {
            if (enableCommunicationMode) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                val outputDevices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val hasHeadset = outputDevices.any { dev ->
                    dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        dev.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                        dev.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        dev.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        dev.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }
                usingSpeaker = !hasHeadset
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (usingSpeaker) {
                        val speakerDev = am.availableCommunicationDevices.firstOrNull {
                            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                        }
                        if (speakerDev != null) {
                            am.setCommunicationDevice(speakerDev)
                        }
                    } else {
                        am.clearCommunicationDevice()
                    }
                }
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = usingSpeaker

                // Align hidden STREAM_VOICE_CALL volume when user already turned STREAM_MUSIC up on speakerphone
                if (usingSpeaker) {
                    val maxCall = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL).coerceAtLeast(1)
                    val curCall = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                    val maxMusic = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    val curMusic = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val musicFrac = curMusic.toFloat() / maxMusic.toFloat()
                    val targetFrac = maxOf(musicFrac, 0.85f)
                    val targetCall = (maxCall * targetFrac).roundToInt().coerceIn(1, maxCall)
                    if (curCall < targetCall) {
                        savedVoiceCallVolume = curCall
                        am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, targetCall, 0)
                    }
                }
            } else {
                savedVoiceCallVolume?.let { prevVol ->
                    savedVoiceCallVolume = null
                    runCatching { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, prevVol, 0) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching { am.clearCommunicationDevice() }
                }
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
                am.mode = AudioManager.MODE_NORMAL
            }
        }
        return usingSpeaker
    }

    /**
     * Clean soft-knee limiter for 16-bit LE mono PCM audio: boosts quiet cloud TTS speech (-16 dBFS)
     * while smoothly saturating peaks above ~68% full scale so speech never clips harshly.
     */
    private fun applyPcm16SoftLimiterGain(pcm16Le: ByteArray, gain: Float): ByteArray {
        if (gain <= 1.02f || pcm16Le.size < 2) return pcm16Le
        val out = ByteArray(pcm16Le.size)
        val samples = pcm16Le.size / 2
        val knee = 22000.0
        val ceiling = 32600.0
        val headroom = ceiling - knee
        for (i in 0 until samples) {
            val lo = pcm16Le[i * 2].toInt() and 0xFF
            val hi = pcm16Le[i * 2 + 1].toInt()
            val raw = (hi shl 8) or lo
            val boosted = raw.toDouble() * gain.toDouble()
            val shaped = when {
                boosted > knee -> knee + headroom * tanh((boosted - knee) / headroom)
                boosted < -knee -> -knee - headroom * tanh((-boosted - knee) / headroom)
                else -> boosted
            }
            val clamped = shaped.roundToInt().coerceIn(-32767, 32767)
            out[i * 2] = (clamped and 0xFF).toByte()
            out[i * 2 + 1] = ((clamped shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun stopAudioHardwareOnly() {
        captureJob?.cancel()
        captureJob = null
        playbackJob?.cancel()
        playbackJob = null
        playbackQueue.clear()

        runCatching { echoCanceler?.release() }
        echoCanceler = null
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null

        audioRecord?.let { rec ->
            audioRecord = null
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        audioTrack?.let { tr ->
            audioTrack = null
            runCatching { tr.pause() }
            runCatching { tr.flush() }
            runCatching { tr.release() }
        }
        setupAudioRoute(enableCommunicationMode = false)
    }

    private fun computeNormalizedRms(pcm16Le: ByteArray): Float {
        if (pcm16Le.size < 4) return 0f
        var sumSq = 0.0
        val count = pcm16Le.size / 2
        for (i in 0 until count) {
            val lo = pcm16Le[i * 2].toInt() and 0xFF
            val hi = pcm16Le[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            sumSq += sample.toDouble() * sample.toDouble()
        }
        val rms = sqrt(sumSq / count)
        return (rms / 8000.0).toFloat().coerceIn(0f, 1f)
    }
}
