package com.example.kinetix.ui.features

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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
            list.add(
                GalleryItem(
                    id = obj.optString("id", "media_$i"),
                    name = obj.optString("name", "IMG_$i.jpg"),
                    album = obj.optString("album", "Camera"),
                    mimeType = obj.optString("mimeType", "image/jpeg"),
                    size = obj.optString("size", "3.2 MB"),
                    date = obj.optString("date", "Today"),
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                    width = obj.optInt("width", 1080),
                    height = obj.optInt("height", 1920),
                    thumbnailBase64 = obj.optString("thumbnail").takeIf { !it.isNullOrBlank() },
                    previewBase64 = obj.optString("preview").takeIf { !it.isNullOrBlank() }
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

    suspend fun fetchGallery() {
        withContext(Dispatchers.IO) {
            try {
                val client = KinetixApiClient(context)
                val res = client.getGalleryMedia(deviceId)
                if (res.isSuccess) {
                    val arr = res.getOrNull() ?: JSONArray()
                    if (arr.length() > 0) {
                        com.example.kinetix.cache.KinetixDeviceCache.saveCachedGallery(context, deviceId, arr)
                    }
                    val list = parseGalleryJson(arr)
                    withContext(Dispatchers.Main) {
                        if (mediaItems.isEmpty()) {
                            mediaItems.addAll(list)
                        } else if (list.isNotEmpty()) {
                            mediaItems.clear()
                            mediaItems.addAll(list)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(deviceId) {
        fetchGallery()
        if (mediaItems.isEmpty()) {
            delay(1200)
            fetchGallery()
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
                            fetchGallery()
                            delay(500)
                            isRefreshing = false
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
                    fetchGallery()
                    delay(600)
                    isRefreshing = false
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
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                // Main Interactive Image with Pinch-to-Zoom & Pan & Rotation
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 140.dp)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                if (scale > 1f) {
                                    offsetX += pan.x
                                    offsetY += pan.y
                                } else {
                                    offsetX = 0f
                                    offsetY = 0f
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
                            Icon(Icons.Default.BrokenImage, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(64.dp))
                        }
                    }
                }

                // Previous Photo Chevron
                if (currentIndex > 0) {
                    IconButton(
                        onClick = {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                            rotationAngle = 0f
                            selectedItemForModal = filteredItems[currentIndex - 1]
                        },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 12.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                }

                // Next Photo Chevron
                if (currentIndex >= 0 && currentIndex < filteredItems.size - 1) {
                    IconButton(
                        onClick = {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                            rotationAngle = 0f
                            selectedItemForModal = filteredItems[currentIndex + 1]
                        },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 12.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                }

                // Top Floating Action Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 42.dp, start = 16.dp, end = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Close Button
                    IconButton(
                        onClick = { selectedItemForModal = null },
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }

                    // Resolution Status / Album Pill
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color.Black.copy(alpha = 0.65f)
                        ) {
                            Text(
                                text = "${item.album} (${currentIndex + 1}/${filteredItems.size})",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }

                        if (fullBitmap != null) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color(0xFF059669).copy(alpha = 0.9f)
                            ) {
                                Text(
                                    text = "✨ 100% Original",
                                    color = Color.White,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                )
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color(0xFF0284C7).copy(alpha = 0.9f)
                            ) {
                                Text(
                                    text = "40% Preview",
                                    color = Color.White,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }

                    // Top Action Tools: Rotate & Reset & Info
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(
                            onClick = { rotationAngle = (rotationAngle + 90f) % 360f },
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Rotate", tint = Color.White)
                        }

                        IconButton(
                            onClick = { showDetailsSheet = !showDetailsSheet },
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        ) {
                            Icon(Icons.Default.Info, contentDescription = "Details", tint = Color.White)
                        }
                    }
                }

                // Bottom Pro Toolbar & Metadata Sheet
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    color = Color(0xFF1E293B),
                    shadowElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 14.dp)
                    ) {
                        // Title & Specs Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.5.sp,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${item.date} • ${item.size}",
                                    fontSize = 11.5.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF334155)
                            ) {
                                Text(
                                    text = "${item.width} x ${item.height}",
                                    fontSize = 11.5.sp,
                                    color = Color(0xFF38BDF8),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Action Buttons Row: Save, Delete, Share, Copy
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // 1. SAVE / DOWNLOAD ORIGINAL TO PHONE BUTTON
                            Button(
                                onClick = {
                                    // Step 1: Immediately save the current photo to disk
                                    saveImageToGallery(item, displayBitmap, fullResolutionBase64)

                                    // Step 2: In the background, fetch full 100% original and upgrade
                                    if (fullResolutionBase64.isNullOrBlank()) {
                                        coroutineScope.launch {
                                            isDownloadingOriginal = true
                                            try {
                                                val client = KinetixApiClient(context)
                                                client.requestFullGalleryImage(deviceId, item.id)
                                                var attempts = 0
                                                var fetchedBase64: String? = null
                                                while (attempts < 15 && fetchedBase64.isNullOrBlank()) {
                                                    delay(1000)
                                                    attempts++
                                                    val pollRes = client.getFullGalleryImage(deviceId, item.id)
                                                    val polled = pollRes.getOrNull()?.optString("fullBase64")
                                                    if (!polled.isNullOrBlank()) {
                                                        fetchedBase64 = polled
                                                        break
                                                    }
                                                }
                                                if (!fetchedBase64.isNullOrBlank()) {
                                                    fullResolutionBase64 = fetchedBase64
                                                    fullBitmap = decodeBitmap(fetchedBase64)
                                                    saveImageToGallery(item, fullBitmap, fetchedBase64) {
                                                        Toast.makeText(context, "✨ Upgraded to 100% Original Quality!", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            } catch (_: Exception) {
                                            } finally {
                                                isDownloadingOriginal = false
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1.3f)
                                    .height(44.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) {
                                if (isDownloadingOriginal) {
                                    CircularProgressIndicator(
                                        color = Color.White,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Fetching HD...", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                } else {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Save to Phone", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            // 2. DELETE BUTTON
                            Button(
                                onClick = { showDeleteConfirmDialog = true },
                                modifier = Modifier
                                    .weight(0.9f)
                                    .height(44.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Delete", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            // 3. SHARE BUTTON
                            IconButton(
                                onClick = { shareImage(item, displayBitmap, fullResolutionBase64) },
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF334155))
                            ) {
                                Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                // Delete Confirmation Dialog
                if (showDeleteConfirmDialog) {
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirmDialog = false },
                        title = { Text("Delete Photo?", fontWeight = FontWeight.Bold) },
                        text = { Text("This will remove '${item.name}' from your gallery records.") },
                        confirmButton = {
                            TextButton(
                                onClick = { deleteImage(item) },
                                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFDC2626))
                            ) {
                                Text("Delete", fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteConfirmDialog = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                // Detailed EXIF Info Dialog
                if (showDetailsSheet) {
                    AlertDialog(
                        onDismissRequest = { showDetailsSheet = false },
                        title = { Text("Image Information", fontWeight = FontWeight.Bold) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("File Name: ${item.name}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text("Album: ${item.album}", fontSize = 13.sp)
                                Text("Resolution: ${item.width} x ${item.height} px", fontSize = 13.sp)
                                Text("File Size: ${item.size}", fontSize = 13.sp)
                                Text("MIME Type: ${item.mimeType}", fontSize = 13.sp)
                                Text("Captured: ${item.date}", fontSize = 13.sp)
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                clipboardManager.setText(AnnotatedString("${item.name}\n${item.width}x${item.height}\n${item.size}"))
                                Toast.makeText(context, "Copied details to clipboard", Toast.LENGTH_SHORT).show()
                                showDetailsSheet = false
                            }) {
                                Text("Copy Details")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDetailsSheet = false }) {
                                Text("Close")
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
