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
    val thumbnailBase64: String?
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

    val mediaItems = remember { mutableStateListOf<GalleryItem>() }
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
                                thumbnailBase64 = obj.optString("thumbnail").takeIf { !it.isNullOrBlank() }
                            )
                        )
                    }
                    withContext(Dispatchers.Main) {
                        mediaItems.clear()
                        mediaItems.addAll(list)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(deviceId) {
        fetchGallery()
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
    fun saveImageToGallery(item: GalleryItem, bitmap: Bitmap?) {
        if (bitmap == null) {
            Toast.makeText(context, "No image data to save", Toast.LENGTH_SHORT).show()
            return
        }
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val filename = "Kinetix_${System.currentTimeMillis()}_${item.name}"
                val fos: OutputStream?
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(MediaStore.MediaColumns.MIME_TYPE, if (item.name.endsWith(".png", true)) "image/png" else "image/jpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Kinetix")
                    }
                    val imageUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    fos = imageUri?.let { context.contentResolver.openOutputStream(it) }
                } else {
                    val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).toString() + "/Kinetix"
                    val file = File(imagesDir)
                    if (!file.exists()) file.mkdirs()
                    val imageFile = File(imagesDir, filename)
                    fos = FileOutputStream(imageFile)
                }

                fos?.use {
                    val format = if (item.name.endsWith(".png", true)) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                    bitmap.compress(format, 100, it)
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "✅ Saved to Gallery (Pictures/Kinetix)", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Helper to Share Image
    fun shareImage(item: GalleryItem, bitmap: Bitmap?) {
        if (bitmap == null) return
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val cachePath = File(context.cacheDir, "shared_images")
                cachePath.mkdirs()
                val file = File(cachePath, "share_${item.name}")
                val fos = FileOutputStream(file)
                val format = if (item.name.endsWith(".png", true)) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                bitmap.compress(format, 100, fos)
                fos.close()

                val contentUri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/*"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                withContext(Dispatchers.Main) {
                    context.startActivity(Intent.createChooser(shareIntent, "Share Photo via"))
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
        val bitmap = remember(item.id) { decodeBitmap(item.thumbnailBase64) }
        val currentIndex = filteredItems.indexOfFirst { it.id == item.id }

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
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
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

                    // Album Pill
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
                            // 1. SAVE TO PHONE BUTTON
                            Button(
                                onClick = { saveImageToGallery(item, bitmap) },
                                modifier = Modifier
                                    .weight(1.2f)
                                    .height(44.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Save to Phone", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            // 2. DELETE BUTTON
                            Button(
                                onClick = { showDeleteConfirmDialog = true },
                                modifier = Modifier
                                    .weight(1f)
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
                                onClick = { shareImage(item, bitmap) },
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
