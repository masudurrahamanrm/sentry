package com.example.sentry.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.sentry.crypto.CryptoManager
import com.example.sentry.network.SentryApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class SentryNotificationService : NotificationListenerService() {
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            val active = activeNotifications ?: return
            for (sbn in active) {
                processNotification(sbn)
            }
        } catch (_: Exception) {
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        processNotification(sbn)
    }

    private fun processNotification(sbn: StatusBarNotification) {
        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return

        val title = extras.getCharSequence("android.title")?.toString()
            ?: extras.getCharSequence("android.title.big")?.toString()
            ?: notif.tickerText?.toString()
            ?: ""

        val text = extras.getCharSequence("android.text")?.toString()
            ?: extras.getCharSequence("android.bigText")?.toString()
            ?: extras.getCharSequence("android.summaryText")?.toString()
            ?: ""

        val pkg = sbn.packageName ?: "android"

        if (title.isBlank() && text.isBlank()) return

        scope.launch {
            try {
                val client = SentryApiClient(applicationContext)
                val deviceId = CryptoManager.getOrCreateDeviceId(applicationContext)
                val body = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("packageName", pkg)
                    put("title", title.ifBlank { "Notification" })
                    put("body", text.ifBlank { title })
                    put("timestamp", System.currentTimeMillis())
                }
                client.submitNotification(body)
            } catch (_: Exception) {
            }
        }
    }
}
