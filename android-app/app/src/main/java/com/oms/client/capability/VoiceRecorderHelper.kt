package com.oms.client.capability

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import java.io.File

/**
 * Native AAC (.m4a) Voice Recorder & Audio Player for OneMoreStep
 * Works across all Xiaomi HyperOS / MIUI ROMs without relying on Google Voice Search.
 */
object VoiceRecorderHelper {
    @Volatile
    private var recorder: MediaRecorder? = null

    @Volatile
    private var currentFile: File? = null

    @Volatile
    private var startTimeMs: Long = 0L

    @Volatile
    private var mediaPlayer: MediaPlayer? = null

    @Volatile
    var playingItemId: String? = null
        private set

    fun isRecording(): Boolean = recorder != null

    fun startRecording(context: Context): File? {
        stopRecordingInternal(deleteFile = true)
        stopPlayback()

        return try {
            val dir = File(context.filesDir, "voice_notes").apply { mkdirs() }
            val outFile = File(dir, "voice_${System.currentTimeMillis()}.m4a")
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(16000)
            mr.setAudioEncodingBitRate(64000)
            mr.setAudioChannels(1)
            mr.setOutputFile(outFile.absolutePath)
            mr.prepare()
            mr.start()

            recorder = mr
            currentFile = outFile
            startTimeMs = System.currentTimeMillis()
            outFile
        } catch (_: Throwable) {
            stopRecordingInternal(deleteFile = true)
            null
        }
    }

    /**
     * Stop active recording and return (audioFile, durationMs).
     */
    fun stopRecording(): Pair<File, Long>? {
        val file = currentFile
        val durationMs = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(0L)
        val ok = stopRecordingInternal(deleteFile = false)
        if (!ok || file == null || !file.exists() || file.length() < 256L) {
            try {
                file?.delete()
            } catch (_: Throwable) {
            }
            return null
        }
        return file to durationMs
    }

    fun cancelRecording() {
        stopRecordingInternal(deleteFile = true)
    }

    fun getMaxAmplitude(): Int {
        return try {
            recorder?.maxAmplitude ?: 0
        } catch (_: Throwable) {
            0
        }
    }

    private fun stopRecordingInternal(deleteFile: Boolean): Boolean {
        val mr = recorder
        val f = currentFile
        recorder = null
        currentFile = null
        startTimeMs = 0L

        var stoppedCleanly = false
        if (mr != null) {
            try {
                mr.stop()
                stoppedCleanly = true
            } catch (_: Throwable) {
            } finally {
                try {
                    mr.reset()
                } catch (_: Throwable) {
                }
                try {
                    mr.release()
                } catch (_: Throwable) {
                }
            }
        }
        if (deleteFile && f != null) {
            try {
                f.delete()
            } catch (_: Throwable) {
            }
        }
        return stoppedCleanly
    }

    fun playAudio(
        context: Context,
        itemId: String,
        uriString: String,
        onFinish: () -> Unit
    ): Boolean {
        stopPlayback()
        return try {
            val uri = Uri.parse(uriString)
            val mp = MediaPlayer()
            if (uri.scheme == "file" && uri.path != null) {
                mp.setDataSource(uri.path!!)
            } else {
                mp.setDataSource(context, uri)
            }
            mp.setOnCompletionListener {
                stopPlayback()
                onFinish()
            }
            mp.setOnErrorListener { _, _, _ ->
                stopPlayback()
                onFinish()
                true
            }
            mp.prepare()
            mp.start()
            mediaPlayer = mp
            playingItemId = itemId
            true
        } catch (_: Throwable) {
            stopPlayback()
            onFinish()
            false
        }
    }

    fun stopPlayback() {
        val mp = mediaPlayer
        mediaPlayer = null
        playingItemId = null
        if (mp != null) {
            try {
                if (mp.isPlaying) mp.stop()
            } catch (_: Throwable) {
            }
            try {
                mp.release()
            } catch (_: Throwable) {
            }
        }
    }
}
