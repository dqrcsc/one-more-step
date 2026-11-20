package com.oms.client.cockpit

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
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
import kotlin.math.sqrt

data class LiveVoiceUiState(
    val isActive: Boolean = false,
    val isConnecting: Boolean = false,
    val mode: RealtimeBackendMode = RealtimeBackendMode.OPENAI_WALK_TALK,
    val englishOnly: Boolean = false,
    val modelLabel: String = "",
    val micMuted: Boolean = false,
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

    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    private val playbackQueue = LinkedBlockingQueue<ByteArray>(512)
    private val currentAssistantText = StringBuilder()

    fun start(options: RealtimeSessionOptions) {
        stop()
        NotificationExecutor.stopSpeaking(appContext)
        _stateFlow.value = LiveVoiceUiState(
            isActive = true,
            isConnecting = true,
            mode = options.mode,
            englishOnly = options.englishOnlyFluencyMode
        )

        setupAudioRoute(enableCommunicationMode = true)
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

    fun sendCameraJpegFrameIfGlassActive(jpegBytes: ByteArray) {
        val st = _stateFlow.value
        if (st.isActive && !st.isConnecting && st.mode == RealtimeBackendMode.GEMINI_GLASS_LIVE) {
            engine.sendCameraJpegFrame(jpegBytes)
        }
    }

    fun stop() {
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
            audioTrack = track
            track.play()

            while (isActive) {
                val chunk = try {
                    playbackQueue.take()
                } catch (_: InterruptedException) {
                    break
                }
                if (chunk.isEmpty()) continue
                runCatching {
                    track.write(chunk, 0, chunk.size)
                }
            }
        }
    }

    private fun setupAudioRoute(enableCommunicationMode: Boolean) {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            if (enableCommunicationMode) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                val hasHeadset = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { dev ->
                    dev.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        dev.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        dev.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        dev.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        dev.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        dev.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
                }
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = !hasHeadset
            } else {
                am.mode = AudioManager.MODE_NORMAL
            }
        }
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
