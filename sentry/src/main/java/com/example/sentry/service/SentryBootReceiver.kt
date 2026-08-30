package com.example.sentry.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SentryBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SentryPersistentService.startService(context.applicationContext)
    }
}
