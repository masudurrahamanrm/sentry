package com.example.kinetix.ui.features

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.kinetix.network.KinetixApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

data class GalleryItem(
    val id: String,
    val name: String,
    val album: String,
    val mimeType: String,
    val size: String,
    val date: String,
    val timestamp: Long,
    val width: Int,
    val height: Int,
    val thumbnailBase64: String?,
    val previewBase64: String? = null
)

object GalleryBackgroundDownloader {
    private val activeDownloads = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    val downloadingMediaIds = mutableStateMapOf<String, Boolean>()

    fun startDownload(
        context: Context,
        deviceId: String,
        item: GalleryItem,
        onSuccess: ((String) -> Unit)? = null
    ) {
        if (activeDownloads.containsKey(item.id)) {
            Toast.makeText(context, "📥 Download already running in background...", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(context, "📥 Downloading ${item.name} in background...", Toast.LENGTH_SHORT).show()
        downloadingMediaIds[item.id] = true

        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = KinetixApiClient(context)
                client.requestFullGalleryImage(deviceId, item.id)

                var attempts = 0
                var fetchedBase64: String? = null
                while (attempts < 45 && fetchedBase64.isNullOrBlank()) {
                    delay(1000)
                    attempts++
                    val pollRes = client.getFullGalleryImage(deviceId, item.id)
                    val polled = pollRes.getOrNull()?.optString("fullBase64")
                    if (!polled.isNullOrBlank() && polled != "null") {
                        fetchedBase64 = polled
                        break
                    }
                }

                if (!fetchedBase64.isNullOrBlank()) {
                    val cleanName = item.name.replace("[^a-zA-Z0-9._-]".toRegex(), "_").let {
                        if (!it.contains(".")) "$it.jpg" else it
                    }
                    val filename = "Kinetix_Original_${cleanName}"
                    val mime = if (cleanName.endsWith(".png", true)) "image/png" else "image/jpeg"
                    val rawBytes = Base64.decode(fetchedBase64, Base64.DEFAULT)
                    var saved = false

                    // 1. Android MediaStore
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            val contentValues = ContentValues().apply {
                                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                                put(MediaStore.Images.Media.MIME_TYPE, mime)
                                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Kinetix")
                                put(MediaStore.Images.Media.IS_PENDING, 1)
                            }
                            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                            if (uri != null) {
                                context.contentResolver.openOutputStream(uri)?.use { out ->
                                    out.write(rawBytes)
                                }
                                contentValues.clear()
                                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                                context.contentResolver.update(uri, contentValues, null, null)
                                saved = true
                            }
                        } catch (_: Exception) {}
                    }

                    // 2. Direct File I/O Fallback
                    if (!saved) {
                        try {
                            val picturesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Kinetix")
                            if (!picturesDir.exists()) picturesDir.mkdirs()
                            val targetFile = File(picturesDir, filename)
                            FileOutputStream(targetFile).use { out ->
                                out.write(rawBytes)
                            }
                            android.media.MediaScannerConnection.scanFile(
                                context,
                                arrayOf(targetFile.absolutePath),
                                arrayOf(mime),
                                null
                            )
                            saved = true
                        } catch (_: Exception) {}
                    }

                    withContext(Dispatchers.Main) {
                        if (saved) {
                            val sizeMb = String.format(java.util.Locale.US, "%.2f MB", rawBytes.size / (1024f * 1024f))
                            Toast.makeText(context, "✅ Download Complete: ${item.name} ($sizeMb) saved to Gallery!", Toast.LENGTH_LONG).show()
                            onSuccess?.invoke(fetchedBase64)
                        } else {
                            Toast.makeText(context, "❌ Could not save ${item.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "⚠️ SentrY took too long to send ${item.name}. Please check connection.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Download error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                activeDownloads.remove(item.id)
                downloadingMediaIds.remove(item.id)
            }
        }
        activeDownloads[item.id] = job
    }

    fun isDownloading(mediaId: String): Boolean = downloadingMediaIds.containsKey(mediaId)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    deviceId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    fun parseGalleryJson(arr: JSONArray): List<GalleryItem> {
        val list = mutableListOf<GalleryItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val thumb = obj.optString("thumbnail").takeIf { !it.isNullOrBlank() && it != "null" && it.length > 50 }
            val size = obj.optString("size", "3.2 MB")
            // Filter out corrupted / placeholder 3 B items
            if (thumb == null || (size.endsWith(" B") && (size.removeSuffix(" B").toIntOrNull() ?: 0) < 500)) {
                continue
            }
            list.add(
                GalleryItem(
                    id = obj.optString("id", "media_$i"),
                    name = obj.optString("name", "IMG_$i.jpg"),
                    album = obj.optString("album", "Camera"),
                    mimeType = obj.optString("mimeType", "image/jpeg"),
                    size = size,
                    date = obj.optString("date", "Today"),
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                    width = obj.optInt("width", 1080),
                    height = obj.optInt("height", 1920),
                    thumbnailBase64 = thumb,
                    previewBase64 = obj.optString("preview").takeIf { !it.isNullOrBlank() && it != "null" }
                )
            )
        }
        return list
    }

    // Media Items (Initialized from 0ms Local Cache)
    val mediaItems = remember(deviceId) {
        mutableStateListOf<GalleryItem>().apply {
            val cachedArr = com.example.kinetix.cache.KinetixDeviceCache.getCachedGallery(context, deviceId)
            if (cachedArr.length() > 0) {
                addAll(parseGalleryJson(cachedArr))
            }
        }
    }
    var selectedAlbum by remember { mutableStateOf("All") }
    var selectedItemForModal by remember { mutableStateOf<GalleryItem?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showDetailsSheet by remember { mutableStateOf(false) }
    var rotationAngle by remember { mutableFloatStateOf(0f) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    suspend fun fetchGallery(notifyUser: Boolean = false) {
        withContext(Dispatchers.IO) {
            try {
                val client = KinetixApiClient(context)
                if (notifyUser) {
                    // Send instant sync command to SentrY
                    client.requestGallerySync(deviceId)
                }

                val res = client.getGalleryMedia(deviceId)
                if (res.isSuccess) {
                    val arr = res.getOrNull() ?: JSONArray()
                    val incomingList = parseGalleryJson(arr)
                    if (incomingList.isNotEmpty()) {
                        com.example.kinetix.cache.KinetixDeviceCache.saveCachedGallery(context, deviceId, arr)
                    }
                    withContext(Dispatchers.Main) {
                        val map = mediaItems.associateBy { it.id }.toMutableMap()
                        for (item in incomingList) {
                            map[item.id] = item
                        }
                        val merged = map.values.sortedByDescending { it.timestamp }.take(300)
                        mediaItems.clear()
                        mediaItems.addAll(merged)
                    }
                }

                // If user manually refreshed, re-check after 1.5s to capture SentrY's live upload
                if (notifyUser) {
                    delay(1500)
                    val secondRes = client.getGalleryMedia(deviceId)
                    if (secondRes.isSuccess) {
                        val secondArr = secondRes.getOrNull() ?: JSONArray()
                        val secondList = parseGalleryJson(secondArr)
                        if (secondList.isNotEmpty()) {
                            com.example.kinetix.cache.KinetixDeviceCache.saveCachedGallery(context, deviceId, secondArr)
                        }
                        withContext(Dispatchers.Main) {
                            val map = mediaItems.associateBy { it.id }.toMutableMap()
                            for (item in secondList) {
                                map[item.id] = item
                            }
                            val merged = map.values.sortedByDescending { it.timestamp }.take(300)
                            mediaItems.clear()
                            mediaItems.addAll(merged)
                            Toast.makeText(context, "🔄 Gallery updated • ${merged.size} photos synced", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                if (notifyUser) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Refresh error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isRefreshing = false
                }
            }
        }
    }

    LaunchedEffect(deviceId) {
        fetchGallery(notifyUser = false)
        if (mediaItems.isEmpty()) {
            delay(1200)
            fetchGallery(notifyUser = false)
        }
    }

    // Helper to decode Base64 Bitmap
    fun decodeBitmap(base64Str: String?): Bitmap? {
        if (base64Str.isNullOrBlank()) return null
        return try {
            val decoded = Base64.decode(base64Str, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
        } catch (_: Exception) {
            null
        }
    }

    // Helper to Save 100% Original Photo Downloaded from SentrY
    fun saveOriginalPhotoFromSentry(item: GalleryItem, originalBase64: String) {
        if (originalBase64.isBlank()) {
            Toast.makeText(context, "No original image data received from SentrY", Toast.LENGTH_SHORT).show()
            return
        }
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val cleanName = item.name.replace("[^a-zA-Z0-9._-]".toRegex(), "_").let {
                    if (!it.contains(".")) "$it.jpg" else it
                }
                val filename = "Kinetix_Original_${cleanName}"
                val mime = if (cleanName.endsWith(".png", true)) "image/png" else "image/jpeg"
                val rawBytes = Base64.decode(originalBase64, Base64.DEFAULT)
                var saved = false

                // 1. Save directly to Android MediaStore (Gallery app indexing)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        val contentValues = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                            put(MediaStore.Images.Media.MIME_TYPE, mime)
                            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Kinetix")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                        if (uri != null) {
                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                out.write(rawBytes)
                            }
                            contentValues.clear()
                            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                            context.contentResolver.update(uri, contentValues, null, null)
                            saved = true
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("GalleryScreen", "MediaStore original save failed: ${e.message}")
                    }
                }

                // 2. Direct File I/O Fallback into Pictures/Kinetix
                if (!saved) {
                    try {
                        val picturesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Kinetix")
                        if (!picturesDir.exists()) picturesDir.mkdirs()
                        val targetFile = File(picturesDir, filename)
                        FileOutputStream(targetFile).use { out ->
                            out.write(rawBytes)
                        }
                        android.media.MediaScannerConnection.scanFile(
                            context,
                            arrayOf(targetFile.absolutePath),
                            arrayOf(mime),
                            null
                        )
                        saved = true
                    } catch (e: Exception) {
                        android.util.Log.e("GalleryScreen", "File original save failed: ${e.message}")
                    }
                }

                withContext(Dispatchers.Main) {
                    if (saved) {
                        val sizeMb = String.format(java.util.Locale.US, "%.2f MB", rawBytes.size / (1024f * 1024f))
                        Toast.makeText(context, "✅ Downloaded 100% Original Photo ($sizeMb) to Pictures/Kinetix!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "❌ Failed to write photo to storage", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Save error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Helper to Save Image to Controller Device Storage
    fun saveImageToGallery(item: GalleryItem, bitmap: Bitmap?, rawBase64: String? = null, onSaved: (() -> Unit)? = null) {
        val targetBitmap = bitmap ?: decodeBitmap(rawBase64) ?: decodeBitmap(item.previewBase64 ?: item.thumbnailBase64)
        if (targetBitmap == null && rawBase64.isNullOrBlank()) {
            Toast.makeText(context, "No image data available", Toast.LENGTH_SHORT).show()
            return
        }

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val cleanName = item.name.replace("[^a-zA-Z0-9._-]".toRegex(), "_").let {
                    if (!it.contains(".")) "$it.jpg" else it
                }
                val filename = "Kinetix_${System.currentTimeMillis()}_$cleanName"
                val mime = if (cleanName.endsWith(".png", true)) "image/png" else "image/jpeg"
                var saved = false

                // Method 1: MediaStore on Android Q+ (Standard system photos provider)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        val contentValues = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                            put(MediaStore.Images.Media.MIME_TYPE, mime)
                            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Kinetix")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                        if (uri != null) {
                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                if (!rawBase64.isNullOrBlank()) {
                                    out.write(Base64.decode(rawBase64, Base64.DEFAULT))
                                } else if (targetBitmap != null) {
                                    val format = if (mime == "image/png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                                    targetBitmap.compress(format, 100, out)
                                }
                            }
                            contentValues.clear()
                            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                            context.contentResolver.update(uri, contentValues, null, null)
                            saved = true
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("GalleryScreen", "MediaStore insert failed: ${e.message}")
                    }
                }

                // Method 2: Public Pictures Directory Direct File
                if (!saved) {
                    try {
                        val picturesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Kinetix")
                        if (!picturesDir.exists()) picturesDir.mkdirs()
                        val targetFile = File(picturesDir, filename)
                        FileOutputStream(targetFile).use { out ->
                            if (!rawBase64.isNullOrBlank()) {
                                out.write(Base64.decode(rawBase64, Base64.DEFAULT))
                            } else if (targetBitmap != null) {
                                val format = if (mime == "image/png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                                targetBitmap.compress(format, 100, out)
                            }
                        }
                        android.media.MediaScannerConnection.scanFile(
                            context,
                            arrayOf(targetFile.absolutePath),
                            arrayOf(mime),
                            null
                        )
                        saved = true
                    } catch (e: Exception) {
                        android.util.Log.e("GalleryScreen", "Public dir save failed: ${e.message}")
                    }
                }

                // Method 3: Public Downloads Directory Direct File
                if (!saved) {
                    try {
                        val downloadsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Kinetix")
                        if (!downloadsDir.exists()) downloadsDir.mkdirs()
                        val targetFile = File(downloadsDir, filename)
                        FileOutputStream(targetFile).use { out ->
                            if (!rawBase64.isNullOrBlank()) {
                                out.write(Base64.decode(rawBase64, Base64.DEFAULT))
                            } else if (targetBitmap != null) {
                                val format = if (mime == "image/png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                                targetBitmap.compress(format, 100, out)
                            }
                        }
                        android.media.MediaScannerConnection.scanFile(
                            context,
                            arrayOf(targetFile.absolutePath),
                            arrayOf(mime),
                            null
                        )
                        saved = true
                    } catch (e: Exception) {
                        android.util.Log.e("GalleryScreen", "Downloads dir save failed: ${e.message}")
                    }
                }

                withContext(Dispatchers.Main) {
                    if (saved) {
                        Toast.makeText(context, "✅ Photo saved to Pictures/Kinetix!", Toast.LENGTH_SHORT).show()
                        onSaved?.invoke()
                    } else {
                        Toast.makeText(context, "❌ Error saving photo", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Save error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Helper to Share Image
    fun shareImage(item: GalleryItem, bitmap: Bitmap?, rawBase64: String? = null) {
        if (bitmap == null && rawBase64.isNullOrBlank()) return
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val cachePath = File(context.cacheDir, "shared_images")
                cachePath.mkdirs()
                val file = File(cachePath, "share_${item.name}")
                val fos = FileOutputStream(file)
                if (!rawBase64.isNullOrBlank()) {
                    val rawBytes = Base64.decode(rawBase64, Base64.DEFAULT)
                    fos.write(rawBytes)
                } else if (bitmap != null) {
                    val format = if (item.name.endsWith(".png", true)) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                    bitmap.compress(format, 100, fos)
                }
                fos.close()

                val contentUri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = if (item.name.endsWith(".png", true)) "image/png" else "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                withContext(Dispatchers.Main) {
                    context.startActivity(Intent.createChooser(shareIntent, "Share Original Photo via"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Helper to Delete Image
    fun deleteImage(item: GalleryItem) {
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val client = KinetixApiClient(context)
                    client.deleteGalleryMedia(deviceId, item.id)
                } catch (_: Exception) {}
            }
            mediaItems.removeIf { it.id == item.id }
            selectedItemForModal = null
            showDeleteConfirmDialog = false
            Toast.makeText(context, "🗑️ Photo removed from gallery", Toast.LENGTH_SHORT).show()
        }
    }

    // Available albums
    val albums = remember(mediaItems.size) {
        val set = mutableSetOf("All")
        mediaItems.forEach { set.add(it.album) }
        set.toList()
    }

    val filteredItems = remember(selectedAlbum, mediaItems.size) {
        if (selectedAlbum == "All") mediaItems
        else mediaItems.filter { it.album.equals(selectedAlbum, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Gallery Media", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            "${mediaItems.size} photos & screenshots synced",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        coroutineScope.launch {
                            isRefreshing = true
                            fetchGallery(notifyUser = true)
                        }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                coroutineScope.launch {
                    isRefreshing = true
                    fetchGallery(notifyUser = true)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Album Filter Chips (Horizontally scrollable)
                if (albums.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        albums.forEach { album ->
                            val isSelected = selectedAlbum == album
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectedAlbum = album },
                                label = { Text(album, fontSize = 12.5.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                                shape = RoundedCornerShape(14.dp)
                            )
                        }
                    }
                }

                if (filteredItems.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Outlined.PhotoLibrary,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "No media available in this folder",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Syncing media from remote phone...",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        items(filteredItems, key = { it.id }) { item ->
                            GalleryGridCard(
                                item = item,
                                onClick = {
                                    scale = 1f
                                    offsetX = 0f
                                    offsetY = 0f
                                    rotationAngle = 0f
                                    selectedItemForModal = item
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Full-screen Image / Video Details Lightbox Modal
    selectedItemForModal?.let { item ->
        val previewBitmap = remember(item.id) { decodeBitmap(item.previewBase64 ?: item.thumbnailBase64) }
        var fullResolutionBase64 by remember(item.id) { mutableStateOf<String?>(null) }
        var fullBitmap by remember(item.id) { mutableStateOf<Bitmap?>(null) }
        var isDownloadingOriginal by remember(item.id) { mutableStateOf(false) }
        val currentIndex = filteredItems.indexOfFirst { it.id == item.id }

        val displayBitmap = fullBitmap ?: previewBitmap

        Dialog(
            onDismissRequest = { selectedItemForModal = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            val view = LocalView.current
            DisposableEffect(view) {
                val window = (view.parent as? DialogWindowProvider)?.window ?: (context as? Activity)?.window
                if (window != null) {
                    WindowCompat.setDecorFitsSystemWindows(window, false)
                    window.statusBarColor = android.graphics.Color.WHITE
                    window.navigationBarColor = android.graphics.Color.WHITE
                    val controller = WindowCompat.getInsetsController(window, view)
                    controller.isAppearanceLightStatusBars = true
                    controller.isAppearanceLightNavigationBars = true
                }
                onDispose {}
            }

            var accumulatedSwipeX by remember(item.id) { mutableFloatStateOf(0f) }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White)
            ) {
                // Main Interactive Image with Pinch-to-Zoom, Pan, Rotation & Swipe-to-Change Photo
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 90.dp, top = 65.dp)
                        .pointerInput(item.id) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                if (scale > 1.05f) {
                                    offsetX += pan.x
                                    offsetY += pan.y
                                } else {
                                    offsetX = 0f
                                    offsetY = 0f
                                    accumulatedSwipeX += pan.x
                                    if (accumulatedSwipeX < -70f && currentIndex < filteredItems.size - 1) {
                                        scale = 1f
                                        offsetX = 0f
                                        offsetY = 0f
                                        rotationAngle = 0f
                                        accumulatedSwipeX = 0f
                                        selectedItemForModal = filteredItems[currentIndex + 1]
                                    } else if (accumulatedSwipeX > 70f && currentIndex > 0) {
                                        scale = 1f
                                        offsetX = 0f
                                        offsetY = 0f
                                        rotationAngle = 0f
                                        accumulatedSwipeX = 0f
                                        selectedItemForModal = filteredItems[currentIndex - 1]
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (displayBitmap != null) {
                        Image(
                            bitmap = displayBitmap.asImageBitmap(),
                            contentDescription = item.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offsetX,
                                    translationY = offsetY,
                                    rotationZ = rotationAngle
                                )
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.BrokenImage, contentDescription = null, tint = Color(0xFFC7C7CC), modifier = Modifier.size(64.dp))
                        }
                    }
                }

                // 1. iOS Top Navigation Header (Frosted Glass Aesthetic, Flush with Status Bar)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    color = Color.White.copy(alpha = 0.95f),
                    shadowElevation = 1.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // iOS Back / Close Button
                        IconButton(
                            onClick = { selectedItemForModal = null },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color(0xFF007AFF),
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Center Apple Date & Time Subtitle
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = item.date.ifBlank { "Photo" },
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF1C1C1E)
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "${item.album} • ${currentIndex + 1} of ${filteredItems.size}",
                                    fontSize = 11.5.sp,
                                    color = Color(0xFF8E8E93),
                                    fontWeight = FontWeight.Medium
                                )
                                if (fullBitmap != null) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFFE8F5E9)
                                    ) {
                                        Text(
                                            text = "RAW",
                                            color = Color(0xFF2E7D32),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Right Top Tools: Rotate & Exif
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(
                                onClick = { rotationAngle = (rotationAngle + 90f) % 360f },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.RotateRight,
                                    contentDescription = "Rotate",
                                    tint = Color(0xFF007AFF),
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            IconButton(
                                onClick = { showDetailsSheet = !showDetailsSheet },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.Info,
                                    contentDescription = "Info",
                                    tint = Color(0xFF007AFF),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }

                // 2. iOS Floating Dynamic Bottom Bar (Apple Photos Style)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                    color = Color.White.copy(alpha = 0.98f),
                    shadowElevation = 12.dp,
                    border = BorderStroke(0.5.dp, Color(0xFFE5E5EA))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 22.dp, start = 16.dp, end = 16.dp)
                    ) {
                        val isBgDownloading = GalleryBackgroundDownloader.isDownloading(item.id)

                        // Main Action Bar Row with Equal iOS Spacing
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 1. Share Button (iOS Style)
                            IconButton(
                                onClick = { shareImage(item, displayBitmap, fullResolutionBase64) },
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.Share,
                                    contentDescription = "Share",
                                    tint = Color(0xFF007AFF),
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            // 2. Center Primary: Download Original Button (iOS Pill Action)
                            Button(
                                onClick = {
                                    if (!fullResolutionBase64.isNullOrBlank()) {
                                        saveOriginalPhotoFromSentry(item, fullResolutionBase64!!)
                                    } else {
                                        GalleryBackgroundDownloader.startDownload(
                                            context = context,
                                            deviceId = deviceId,
                                            item = item,
                                            onSuccess = { newFullBase64 ->
                                                fullResolutionBase64 = newFullBase64
                                                fullBitmap = decodeBitmap(newFullBase64)
                                            }
                                        )
                                    }
                                },
                                enabled = !isBgDownloading,
                                shape = RoundedCornerShape(22.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF007AFF),
                                    disabledContainerColor = Color(0xFFF2F2F7)
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                modifier = Modifier
                                    .height(44.dp)
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                            ) {
                                if (isBgDownloading) {
                                    CircularProgressIndicator(
                                        color = Color(0xFF007AFF),
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Downloading...",
                                        color = Color(0xFF007AFF),
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Download,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        if (fullBitmap != null) "Save to Photos" else "Download Original",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            // 3. Delete Trash Button (iOS Style Red)
                            IconButton(
                                onClick = { showDeleteConfirmDialog = true },
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = "Delete",
                                    tint = Color(0xFFFF3B30),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }

                // iOS Delete Action Sheet Dialog
                if (showDeleteConfirmDialog) {
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirmDialog = false },
                        shape = RoundedCornerShape(20.dp),
                        containerColor = Color.White,
                        title = { Text("Delete Photo", fontWeight = FontWeight.Bold, color = Color(0xFF1C1C1E)) },
                        text = { Text("This photo will be permanently deleted from your remote gallery.", color = Color(0xFF3A3A3C), fontSize = 13.5.sp) },
                        confirmButton = {
                            TextButton(
                                onClick = { deleteImage(item) }
                            ) {
                                Text("Delete Photo", color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteConfirmDialog = false }) {
                                Text("Cancel", color = Color(0xFF007AFF), fontWeight = FontWeight.Medium)
                            }
                        }
                    )
                }

                // iOS Inspector / Details Sheet
                if (showDetailsSheet) {
                    AlertDialog(
                        onDismissRequest = { showDetailsSheet = false },
                        shape = RoundedCornerShape(22.dp),
                        containerColor = Color.White,
                        title = {
                            Column {
                                Text("Photo Information", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFF1C1C1E))
                                Text(item.name, fontSize = 12.sp, color = Color(0xFF8E8E93), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        },
                        text = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFFF2F2F7),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("Dimensions", fontSize = 12.5.sp, color = Color(0xFF8E8E93))
                                            Text("${item.width} × ${item.height}", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1C1C1E))
                                        }
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("File Size", fontSize = 12.5.sp, color = Color(0xFF8E8E93))
                                            Text(item.size, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1C1C1E))
                                        }
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("Album", fontSize = 12.5.sp, color = Color(0xFF8E8E93))
                                            Text(item.album, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1C1C1E))
                                        }
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("Format", fontSize = 12.5.sp, color = Color(0xFF8E8E93))
                                            Text(item.mimeType.removePrefix("image/").uppercase(), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1C1C1E))
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                clipboardManager.setText(AnnotatedString("${item.name}\n${item.width}x${item.height}\n${item.size}"))
                                Toast.makeText(context, "Copied details to clipboard", Toast.LENGTH_SHORT).show()
                                showDetailsSheet = false
                            }) {
                                Text("Copy Details", color = Color(0xFF007AFF), fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDetailsSheet = false }) {
                                Text("Done", color = Color(0xFF007AFF), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun GalleryGridCard(
    item: GalleryItem,
    onClick: () -> Unit
) {
    val bitmap = remember(item.id) {
        item.thumbnailBase64?.let { base64Str ->
            try {
                val decoded = Base64.decode(base64Str, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
            } catch (_: Exception) {
                null
            }
        }
    }

    Card(
        modifier = Modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Photo,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            // Background Downloading Badge
            if (GalleryBackgroundDownloader.isDownloading(item.id)) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .size(24.dp)
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFF38BDF8),
                        strokeWidth = 2.dp,
                        modifier = Modifier.padding(4.dp)
                    )
                }
            }

            // Bottom Gradient with Size & Date
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                        )
                    )
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Text(
                    text = item.size,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
