package com.example.sentry.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class SentryRestartReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SentryRestartReceiver"
        const val ACTION_RESTART_SERVICE = "com.example.sentry.ACTION_RESTART_SERVICE"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: "UNKNOWN"
        Log.d(TAG, "Revival broadcast received ($action). Auto-restarting SentryPersistentService...")
        try {
            SentryPersistentService.startService(context.applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service from receiver: ${e.message}")
        }
    }
}
