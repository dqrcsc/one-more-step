package com.oms.client.share

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.oms.client.OmsApplication
import com.oms.client.capability.VoiceRecorderHelper
import java.io.File

/**
 * Module 03: Quick Voice / Text Idea Sheet ("我有一个想法" & Screenshot Voice Note)
 * Supports native AAC (.m4a) voice recording + automatic Mac SenseVoice offline ASR transcription.
 */
class VoiceIdeaActivity : Activity() {
    companion object {
        private const val REQ_SPEECH = 1001
        private const val REQ_AUDIO_PERM = 1002
    }

    private lateinit var inputBox: EditText
    private lateinit var statusView: TextView
    private lateinit var recordBtn: Button
    private var screenshotUri: Uri? = null
    private var recordedAudioFile: File? = null
    private var recordedDurationSec: Int = 0
    private var intentTag: String = "voice_idea"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recordingStartMs = 0L

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!VoiceRecorderHelper.isRecording()) return
            val elapsedSec = ((System.currentTimeMillis() - recordingStartMs) / 1000L).toInt()
            val amp = VoiceRecorderHelper.getMaxAmplitude()
            val bars = when {
                amp > 12000 -> "▂▅▇█"
                amp > 6000 -> "▂▅▇"
                amp > 2000 -> "▂▅"
                else -> "▂"
            }
            statusView.text = "🔴 正在录制语音 ${String.format("%02d:%02d", elapsedSec / 60, elapsedSec % 60)} $bars (Mac 收到后自动离线转写)"
            statusView.setTextColor(Color.parseColor("#F87171"))
            recordBtn.text = "⏹ 停止录音"
            mainHandler.postDelayed(this, 250L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        intent?.getStringExtra("screenshot_uri")?.let { screenshotUri = Uri.parse(it) }
        intent?.getStringExtra("intent_tag")?.let { intentTag = it }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val header = TextView(this).apply {
            text = if (screenshotUri != null) "🎙️ 为截屏添加语音或文字附言" else "💡 我有一个想法 (语音/文字直达 Mac)"
            textSize = 17f
            setPadding(0, 0, 0, 12)
        }

        statusView = TextView(this).apply {
            text = "可点击『🎙️ 录制语音』直接说话（Mac 端 SenseVoice 自动离线转文字），或输入文字"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, 16)
        }

        inputBox = EditText(this).apply {
            hint = "可选：输入文字说明，或直接录语音发送..."
            minLines = 3
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 24, 0, 0)
        }

        recordBtn = Button(this).apply {
            text = "🎙️ 录制语音"
            setOnClickListener { toggleVoiceRecording() }
        }

        val sendBtn = Button(this).apply {
            text = "🚀 发送到 Mac"
            setOnClickListener { finishAndSend() }
        }

        btnRow.addView(recordBtn)
        btnRow.addView(sendBtn)
        root.addView(header)
        root.addView(statusView)
        root.addView(inputBox)
        root.addView(btnRow)
        setContentView(root)
    }

    private fun toggleVoiceRecording() {
        if (VoiceRecorderHelper.isRecording()) {
            mainHandler.removeCallbacks(timerRunnable)
            val stopped = VoiceRecorderHelper.stopRecording()
            if (stopped != null) {
                recordedAudioFile = stopped.first
                recordedDurationSec = ((stopped.second + 500L) / 1000L).toInt().coerceAtLeast(1)
                statusView.text = "✅ 已录好语音 (${recordedDurationSec}秒)，点击『🚀 发送到 Mac』即可投递"
                statusView.setTextColor(Color.parseColor("#4ADE80"))
                recordBtn.text = "🎙️ 重新录音"
            } else {
                statusView.text = "⚠️ 录音时间过短或未录到声音，请重试"
                statusView.setTextColor(Color.parseColor("#FBBF24"))
                recordBtn.text = "🎙️ 录制语音"
            }
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO_PERM)
            return
        }

        val started = VoiceRecorderHelper.startRecording(this)
        if (started != null) {
            recordedAudioFile = null
            recordingStartMs = System.currentTimeMillis()
            mainHandler.post(timerRunnable)
        } else {
            // Fallback to system speech recognizer if hardware mic is busy
            startSystemSpeechRecognizer()
        }
    }

    private fun finishAndSend() {
        if (VoiceRecorderHelper.isRecording()) {
            mainHandler.removeCallbacks(timerRunnable)
            val stopped = VoiceRecorderHelper.stopRecording()
            if (stopped != null) {
                recordedAudioFile = stopped.first
                recordedDurationSec = ((stopped.second + 500L) / 1000L).toInt().coerceAtLeast(1)
            }
        }

        val content = inputBox.text.toString().trim()
        val audioFile = recordedAudioFile

        if (content.isEmpty() && screenshotUri == null && audioFile == null) {
            Toast.makeText(this, "请先录制语音或输入内容", Toast.LENGTH_SHORT).show()
            return
        }

        val primaryUri = screenshotUri ?: audioFile?.let { Uri.fromFile(it) }
        val title = when {
            screenshotUri != null && audioFile != null -> "截屏+语音附言 (${recordedDurationSec}s · #$intentTag)"
            screenshotUri != null -> "截屏附言 (#$intentTag)"
            audioFile != null -> "🎙️ 手机语音闪念 (${recordedDurationSec}秒 · #$intentTag)"
            else -> "语音闪念 (#$intentTag)"
        }

        OmsApplication.instance?.transportClient?.submitItemToMacOrQueue(
            intentTag = intentTag,
            itemType = if (screenshotUri != null) "MEDIA" else "VOICE_IDEA",
            title = title,
            textContent = content.ifEmpty { null },
            voiceTranscript = content.ifEmpty { null },
            fileUri = primaryUri,
            extraAudioUri = if (screenshotUri != null && audioFile != null) Uri.fromFile(audioFile) else null
        )
        Toast.makeText(this, "✅ 已加入待发送队列", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO_PERM && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            toggleVoiceRecording()
        }
    }

    private fun startSystemSpeechRecognizer() {
        try {
            val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_PROMPT, "请说出你的指令或想法...")
            }
            startActivityForResult(speechIntent, REQ_SPEECH)
        } catch (_: Throwable) {
            Toast.makeText(this, "无法启动麦克风录音，请检查录音权限", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SPEECH && resultCode == RESULT_OK && data != null) {
            val matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val best = matches?.firstOrNull()
            if (!best.isNullOrBlank()) {
                val current = inputBox.text.toString()
                inputBox.setText(if (current.isBlank()) best else "$current $best")
            }
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(timerRunnable)
        if (VoiceRecorderHelper.isRecording()) {
            VoiceRecorderHelper.cancelRecording()
        }
        super.onDestroy()
    }
}
