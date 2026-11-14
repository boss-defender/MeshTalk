package com.example.util

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Records a voice note using the platform AAC/MPEG-4 encoder. The original APK
 * prefers OGG/Opus when available on the device and falls back to MPEG-4/AAC.
 */
class VoiceNoteRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt = 0L

    fun start(): File? {
        stopInternal(discard = true)
        val directory = File(context.cacheDir, "voice_notes").apply { mkdirs() }
        val file = File(directory, "VN_${System.currentTimeMillis()}.m4a")
        val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return try {
            newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            newRecorder.setAudioEncodingBitRate(32_000)
            newRecorder.setAudioSamplingRate(16_000)
            newRecorder.setAudioChannels(1)
            newRecorder.setOutputFile(file.absolutePath)
            newRecorder.prepare()
            newRecorder.start()
            recorder = newRecorder
            outputFile = file
            startedAt = System.currentTimeMillis()
            file
        } catch (e: Exception) {
            try { newRecorder.release() } catch (_: Exception) {}
            file.delete()
            null
        }
    }

    fun stop(): File? {
        val file = outputFile
        val duration = System.currentTimeMillis() - startedAt
        recorder?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        recorder = null
        outputFile = null
        return if (file != null && file.exists() && file.length() > 0) {
            if (duration < 400L) {
                file.delete()
                null
            } else {
                file
            }
        } else {
            file?.delete()
            null
        }
    }

    fun cancel() {
        stopInternal(discard = true)
    }

    private fun stopInternal(discard: Boolean) {
        recorder?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        recorder = null
        if (discard) outputFile?.delete()
        outputFile = null
    }
}
