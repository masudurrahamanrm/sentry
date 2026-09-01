package com.example.sentry.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

object SentryWakeManager {
    private const val TAG = "SentryWakeManager"
    private const val WAKE_INTERVAL_MS = 20_000L // 20s heartbeat pulse

    fun scheduleWakePulse(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, SentryRestartReceiver::class.java).apply {
                action = SentryRestartReceiver.ACTION_RESTART_SERVICE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                8888,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val triggerAtMillis = SystemClock.elapsedRealtime() + WAKE_INTERVAL_MS

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
            Log.d(TAG, "Scheduled next Sentry wake pulse in 20s")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule wake pulse: ${e.message}")
        }
    }
}
