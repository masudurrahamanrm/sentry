package com.example.sentry.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class SentryPersistentService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startAsForeground()

        // Acquire partial wakelock so CPU keeps running when phone screen is locked
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sentry::CameraWakeLock")?.apply {
                acquire(24 * 60 * 60 * 1000L) // 24h safety timeout
            }
        } catch (_: Exception) {
        }

        // Start background Camera2, Microphone, Battery Telemetry, File Explorer, and Location listeners
        BackgroundCameraManager.startListening(applicationContext)
        BackgroundAudioManager.startListening(applicationContext)
        BatteryTelemetryManager.startSync(applicationContext)
        BackgroundFileManager.startListening(applicationContext)
        BackgroundLocationManager.startListening(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-ensure background listeners are active
        BackgroundCameraManager.startListening(applicationContext)
        BackgroundAudioManager.startListening(applicationContext)
        BatteryTelemetryManager.startSync(applicationContext)
        BackgroundFileManager.startListening(applicationContext)
        BackgroundLocationManager.startListening(applicationContext)
        return START_STICKY
    }

    private fun startAsForeground() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sentry Active Background Service")
            .setContentText("Background camera, audio, storage & health monitoring active")
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var flags = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val hasCamera = androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.CAMERA
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val hasMic = androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val hasLocation = androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            this, android.Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                if (hasCamera) flags = flags or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                if (hasMic) flags = flags or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (hasLocation) flags = flags or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            try {
                startForeground(NOTIFICATION_ID, notification, flags)
            } catch (_: SecurityException) {
                try {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } catch (_: Exception) {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (_: Exception) {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sentry Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Sentry background camera and telemetry listeners online when phone is locked"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "sentry_background_channel"
        private const val NOTIFICATION_ID = 1001

        fun startService(context: Context) {
            try {
                val intent = Intent(context, SentryPersistentService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
            }
        }

        fun updateLocationNotification(context: Context, address: String, lat: Double, lon: Double) {
            try {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

                // Generate rich mini-map snapshot preview for notification drawer
                val width = 640
                val height = 300
                val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)

                // Background Satellite / Terrain Gradient
                val bgPaint = android.graphics.Paint().apply {
                    shader = android.graphics.LinearGradient(
                        0f, 0f, width.toFloat(), height.toFloat(),
                        android.graphics.Color.rgb(33, 44, 32),
                        android.graphics.Color.rgb(20, 26, 20),
                        android.graphics.Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

                // Map Roads Grid
                val roadPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.rgb(90, 82, 72)
                    strokeWidth = 14f
                    style = android.graphics.Paint.Style.STROKE
                    isAntiAlias = true
                }
                canvas.drawLine(0f, 150f, width.toFloat(), 130f, roadPaint)
                canvas.drawLine(320f, 0f, 300f, height.toFloat(), roadPaint)

                val roadInner = android.graphics.Paint().apply {
                    color = android.graphics.Color.rgb(120, 110, 98)
                    strokeWidth = 8f
                    style = android.graphics.Paint.Style.STROKE
                    isAntiAlias = true
                }
                canvas.drawLine(0f, 150f, width.toFloat(), 130f, roadInner)
                canvas.drawLine(320f, 0f, 300f, height.toFloat(), roadInner)

                // Pulsing Blue Location Circle
                val pulsePaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(80, 33, 150, 243)
                    isAntiAlias = true
                }
                canvas.drawCircle(310f, 140f, 65f, pulsePaint)

                val pulseInner = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(140, 33, 150, 243)
                    isAntiAlias = true
                }
                canvas.drawCircle(310f, 140f, 38f, pulseInner)

                // Pin Center Card
                val pinCard = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    isAntiAlias = true
                    setShadowLayer(8f, 0f, 4f, android.graphics.Color.BLACK)
                }
                canvas.drawCircle(310f, 140f, 22f, pinCard)

                val pinDot = android.graphics.Paint().apply {
                    color = android.graphics.Color.rgb(25, 118, 210)
                    isAntiAlias = true
                }
                canvas.drawCircle(310f, 140f, 12f, pinDot)

                // Text Banner (Address & GPS)
                val bannerPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(190, 0, 0, 0)
                }
                canvas.drawRoundRect(16f, height - 60f, width - 16f, height - 12f, 12f, 12f, bannerPaint)

                val textPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 22f
                    isAntiAlias = true
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                canvas.drawText("📍 $address", 32f, height - 26f, textPaint)

                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle("Sentry • $address")
                    .setContentText("Live GPS: ${String.format("%.4f", lat)}° N, ${String.format("%.4f", lon)}° E • Active")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setStyle(
                        NotificationCompat.BigPictureStyle()
                            .bigPicture(bitmap)
                            .setSummaryText("Live Map Fix: $address (${String.format("%.4f", lat)}, ${String.format("%.4f", lon)})")
                    )
                    .build()

                manager.notify(NOTIFICATION_ID, notification)
            } catch (_: Exception) {
            }
        }
    }
}
