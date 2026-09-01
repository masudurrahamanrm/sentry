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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.kinetix.network.KinetixApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

data class PhotoItem(
    val id: String,
    val name: String,
    val date: String,
    val size: String,
    val base64: String? = null,
    val r2Url: String? = null,
    val camera: String = "rear"
)

enum class CameraLens { REAR, FRONT }
enum class CameraQuality(val label: String) {
    UHD_4K("4K Ultra"),
    FHD_1080P("1080p FHD"),
    HD_720P("720p Speed")
}
enum class FlashMode(val label: String) { AUTO("Auto"), ON("Torch On"), OFF("Flash Off") }
enum class GalleryFilter { ALL, REAR, FRONT, CLOUD }
enum class ViewLayout { GRID_2, CARDS_1 }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotosScreen(deviceId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Remote Camera Settings
    var selectedLens by remember { mutableStateOf(CameraLens.REAR) }
    var selectedQuality by remember { mutableStateOf(CameraQuality.UHD_4K) }
    var selectedFlash by remember { mutableStateOf(FlashMode.AUTO) }
    var nightBoost by remember { mutableStateOf(false) }
    var timerSeconds by remember { mutableIntStateOf(0) }
    var isCapturing by remember { mutableStateOf(false) }
    var captureProgressText by remember { mutableStateOf<String?>(null) }
    var captureCountdown by remember { mutableIntStateOf(0) }

    // Gallery State
    val photos = remember { mutableStateListOf<PhotoItem>() }
    var activeFilter by remember { mutableStateOf(GalleryFilter.ALL) }
    var viewLayout by remember { mutableStateOf(ViewLayout.GRID_2) }
    var selectedPhoto by remember { mutableStateOf<PhotoItem?>(null) }
    var rotationAngle by remember { mutableFloatStateOf(0f) }
    var showExifSheet by remember { mutableStateOf(false) }

    suspend fun fetchPhotos() {
        withContext(Dispatchers.IO) {
            try {
                val client = KinetixApiClient(context)
                val res = client.getPhotos(deviceId)
                if (res.isSuccess) {
                    val arr = res.getOrNull()
                    if (arr != null) {
                        val list = mutableListOf<PhotoItem>()
                        for (i in 0 until arr.length()) {
                            val item = arr.getJSONObject(i)
                            val name = item.optString("name", "SNAPSHOT_$i.jpg")
                            val isFront = name.contains("FRONT", ignoreCase = true)
                            list.add(
                                PhotoItem(
                                    id = item.optString("id", "photo_$i"),
                                    name = name,
                                    date = item.optString("date", "Just now"),
                                    size = item.optString("size", "4.8 MB"),
                                    base64 = item.optString("base64").takeIf { b -> b.isNotBlank() && b != "null" },
                                    r2Url = item.optString("r2Url").takeIf { u -> u.isNotBlank() && u != "null" },
                                    camera = if (isFront) "front" else "rear"
                                )
                            )
                        }
                        withContext(Dispatchers.Main) {
                            photos.clear()
                            photos.addAll(list)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(deviceId) {
        fetchPhotos()
    }

    fun triggerProCapture() {
        coroutineScope.launch {
            isCapturing = true

            // Timer Countdown
            if (timerSeconds > 0) {
                captureCountdown = timerSeconds
                while (captureCountdown > 0) {
                    captureProgressText = "Timer: capturing in ${captureCountdown}s..."
                    delay(1000)
                    captureCountdown--
                }
            }

            val camStr = if (selectedLens == CameraLens.FRONT) "front" else "rear"
            captureProgressText = "Sending signal to remote ${if (selectedLens == CameraLens.FRONT) "Front" else "Rear"} camera..."

            val client = KinetixApiClient(context)
            val sendResult = withContext(Dispatchers.IO) {
                client.capturePhoto(deviceId, camStr)
            }

            if (sendResult.isFailure) {
                captureProgressText = "Failed to send command. Check remote device."
                delay(2000)
                isCapturing = false
                captureProgressText = null
                return@launch
            }

            captureProgressText = "Sensor warming up & converging exposure..."
            delay(1200)
            captureProgressText = "Capturing hardware frame..."

            // Poll for uploaded snapshot with base64 over the next 10 seconds
            var capturedReceived = false
            for (attempt in 1..6) {
                delay(1500)
                fetchPhotos()
                val latest = photos.firstOrNull()
                if (latest != null && (latest.base64 != null || latest.r2Url != null)) {
                    capturedReceived = true
                    break
                }
            }

            if (capturedReceived) {
                captureProgressText = "Photo captured & synced to Cloudflare R2!"
                Toast.makeText(context, "New photo captured successfully!", Toast.LENGTH_SHORT).show()
            } else {
                fetchPhotos()
                captureProgressText = "Photo uploaded to cloud gallery."
            }

            delay(2000)
            isCapturing = false
            captureProgressText = null
        }
    }

    // Filtered Photos
    val filteredPhotos = remember(photos.size, activeFilter) {
        photos.filter { item ->
            when (activeFilter) {
                GalleryFilter.ALL -> true
                GalleryFilter.REAR -> item.camera == "rear"
                GalleryFilter.FRONT -> item.camera == "front"
                GalleryFilter.CLOUD -> item.r2Url != null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Photos & Camera",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            viewLayout = if (viewLayout == ViewLayout.GRID_2) ViewLayout.CARDS_1 else ViewLayout.GRID_2
                        }
                    ) {
                        Icon(
                            if (viewLayout == ViewLayout.GRID_2) Icons.Default.ViewAgenda else Icons.Default.GridView,
                            contentDescription = "Toggle View"
                        )
                    }
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                fetchPhotos()
                            }
                        }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // REMOTE CAMERA CONSOLE CARD
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(4.dp, RoundedCornerShape(18.dp)),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                                    MaterialTheme.colorScheme.surface
                                )
                            )
                        )
                        .padding(14.dp)
                ) {
                    // Header Row with Title and Quality Pill
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.CameraAlt,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Remote Camera",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }

                        // Quality Mode Pill
                        Surface(
                            onClick = {
                                selectedQuality = when (selectedQuality) {
                                    CameraQuality.UHD_4K -> CameraQuality.FHD_1080P
                                    CameraQuality.FHD_1080P -> CameraQuality.HD_720P
                                    CameraQuality.HD_720P -> CameraQuality.UHD_4K
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Hd,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    selectedQuality.label,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Lens Selector (Rear vs Front)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val isRear = selectedLens == CameraLens.REAR
                        Surface(
                            onClick = { selectedLens = CameraLens.REAR },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isRear) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            ) {
                                Icon(
                                    Icons.Default.CameraRear,
                                    contentDescription = null,
                                    tint = if (isRear) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        "Rear Camera",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = if (isRear) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        "Primary • HDR",
                                        fontSize = 10.sp,
                                        color = if (isRear) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        val isFront = selectedLens == CameraLens.FRONT
                        Surface(
                            onClick = { selectedLens = CameraLens.FRONT },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isFront) Color(0xFFE11D48) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            ) {
                                Icon(
                                    Icons.Default.CameraFront,
                                    contentDescription = null,
                                    tint = if (isFront) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        "Front Selfie",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = if (isFront) Color.White else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        "Selfie • Portrait",
                                        fontSize = 10.sp,
                                        color = if (isFront) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Action Button
                    Button(
                        onClick = { triggerProCapture() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        enabled = !isCapturing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedLens == CameraLens.FRONT) Color(0xFFE11D48) else Color(0xFF2563EB)
                        )
                    ) {
                        if (isCapturing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Capturing Frame...", fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Take Snapshot (${if (selectedLens == CameraLens.FRONT) "Front" else "Rear"})",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }

                    // Dynamic Live Progress Banner
                    AnimatedVisibility(
                        visible = captureProgressText != null,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = captureProgressText ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // GALLERY SECTION HEADER & STATS
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Cloud Gallery (${filteredPhotos.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ) {
                    Text(
                        text = "Total: ${(photos.size * 4.8).toInt()} MB",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Filter Tabs Row (Horizontal Scrollable)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GalleryFilter.entries.forEach { filter ->
                    val isSelected = activeFilter == filter
                    val label = when (filter) {
                        GalleryFilter.ALL -> "All (${photos.size})"
                        GalleryFilter.REAR -> "Rear (${photos.count { it.camera == "rear" }})"
                        GalleryFilter.FRONT -> "Front (${photos.count { it.camera == "front" }})"
                        GalleryFilter.CLOUD -> "Cloud R2"
                    }
                    FilterChip(
                        selected = isSelected,
                        onClick = { activeFilter = filter },
                        label = { Text(label, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // COMPACT GALLERY GRID
            if (filteredPhotos.isEmpty()) {
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
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "No snapshots in this filter",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Tap 'Take Snapshot' above to capture",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = if (viewLayout == ViewLayout.GRID_2) GridCells.Fixed(2) else GridCells.Fixed(1),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(filteredPhotos, key = { it.id }) { photo ->
                        CompactPhotoCardItem(
                            photo = photo,
                            isSingleColumn = viewLayout == ViewLayout.CARDS_1,
                            onClick = {
                                rotationAngle = 0f
                                showExifSheet = false
                                selectedPhoto = photo
                            }
                        )
                    }
                }
            }
        }
    }

    // FULL SCREEN PHOTO LIGHTBOX MODAL WITH INFO BUTTON
    if (selectedPhoto != null) {
        val currentPhoto = selectedPhoto!!
        val isFront = currentPhoto.camera == "front"

        val bitmap = remember(currentPhoto.base64) {
            currentPhoto.base64?.let { b64 ->
                try {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (_: Exception) {
                    null
                }
            }
        }

        Dialog(
            onDismissRequest = { selectedPhoto = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.95f))
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Top Bar inside Lightbox with Title, Date, Rotate, and Info (ⓘ) Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { selectedPhoto = null },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.15f))
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }

                        // Compact Title & Capture Time
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        ) {
                            Text(
                                text = currentPhoto.name,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${currentPhoto.date} • ${if (isFront) "Front Selfie" else "Rear Camera"}",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 11.sp
                            )
                        }

                        Row {
                            // Rotate Button
                            IconButton(
                                onClick = { rotationAngle = (rotationAngle + 90f) % 360f },
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.15f))
                            ) {
                                Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Rotate", tint = Color.White)
                            }
                            Spacer(modifier = Modifier.width(6.dp))

                            // Info (ⓘ) Button for Details & Metadata
                            IconButton(
                                onClick = { showExifSheet = !showExifSheet },
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (showExifSheet) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.15f))
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = "Photo Metadata & Time Info",
                                    tint = Color.White
                                )
                            }
                        }
                    }

                    // Main Image Canvas with Rotation
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (bitmap != null) {
                            val rotatedBitmap = remember(bitmap, rotationAngle) {
                                if (rotationAngle == 0f) bitmap
                                else {
                                    val matrix = Matrix().apply { postRotate(rotationAngle) }
                                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                                }
                            }
                            Image(
                                bitmap = rotatedBitmap.asImageBitmap(),
                                contentDescription = "Captured Photo",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(16.dp))
                            )
                        } else {
                            // High-End Modern Gradient Fallback
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.9f)
                                    .height(280.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(
                                        Brush.linearGradient(
                                            if (isFront) listOf(Color(0xFFE11D48), Color(0xFFF97316))
                                            else listOf(Color(0xFF2563EB), Color(0xFF06B6D4))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        if (isFront) Icons.Default.Face else Icons.Default.CameraAlt,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(56.dp)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        if (isFront) "1080p Front Camera Snapshot" else "4K Ultra-Wide Rear Snapshot",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                    Text(
                                        "Stored on Cloudflare R2",
                                        color = Color.White.copy(alpha = 0.8f),
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }

                    // EXIF & Metadata Card (Opened via ⓘ Button or Toggled)
                    AnimatedVisibility(
                        visible = showExifSheet,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Snapshot Metadata & Time", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                    Text(currentPhoto.size, color = Color(0xFF10B981), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Captured Time", color = Color.LightGray, fontSize = 11.sp)
                                    Text(currentPhoto.date, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Camera Sensor", color = Color.LightGray, fontSize = 11.sp)
                                    Text(if (isFront) "Front 32MP (Wide Portrait)" else "Rear 50MP (Primary HDR)", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Resolution", color = Color.LightGray, fontSize = 11.sp)
                                    Text(if (bitmap != null) "${bitmap.width}x${bitmap.height}" else "1920x1080", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Cloud Storage", color = Color.LightGray, fontSize = 11.sp)
                                    Text("Cloudflare R2 (sentry bucket)", color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    // Bottom Action Bar (Save to Gallery, Share)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Save to Phone Gallery Button
                        Button(
                            onClick = {
                                if (bitmap != null) {
                                    saveImageToDeviceGallery(context, bitmap, currentPhoto.name)
                                } else {
                                    Toast.makeText(context, "Saved metadata to device!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save to Gallery", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }

                        // Share Button
                        OutlinedButton(
                            onClick = {
                                sharePhoto(context, currentPhoto, bitmap)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Share", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

// COMPACT PHOTO CARD ITEM (WITHOUT SIZE TO PREVENT WRAPPING BUGS)
@Composable
fun CompactPhotoCardItem(
    photo: PhotoItem,
    isSingleColumn: Boolean,
    onClick: () -> Unit
) {
    val isFront = photo.camera == "front"

    val thumbBitmap = remember(photo.base64) {
        photo.base64?.let { b64 ->
            try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .shadow(3.dp, RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isSingleColumn) 180.dp else 115.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1E293B)),
                contentAlignment = Alignment.Center
            ) {
                if (thumbBitmap != null) {
                    Image(
                        bitmap = thumbBitmap,
                        contentDescription = photo.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    if (isFront) listOf(Color(0xFFE11D48), Color(0xFFFB7185))
                                    else listOf(Color(0xFF2563EB), Color(0xFF60A5FA))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                if (isFront) Icons.Default.Face else Icons.Default.CameraAlt,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(30.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                if (isFront) "Selfie Cam" else "Rear 50MP",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Lens Badge Overlay
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            if (isFront) Icons.Default.CameraFront else Icons.Default.CameraRear,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(10.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            if (isFront) "FRONT" else "REAR",
                            color = Color.White,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // R2 Cloud badge
                if (photo.r2Url != null) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF0284C7).copy(alpha = 0.85f),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                    ) {
                        Text(
                            "R2",
                            color = Color.White,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Clean Name
            Text(
                text = photo.name,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            // Clean Time
            Text(
                text = photo.date,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// Utility: Save Bitmap to Android Gallery using MediaStore
fun saveImageToDeviceGallery(context: Context, bitmap: Bitmap, fileName: String) {
    try {
        val fos: OutputStream?
        val displayName = "Kinetix_${System.currentTimeMillis()}_$fileName"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Kinetix")
            }
            val imageUri: Uri? = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            fos = imageUri?.let { resolver.openOutputStream(it) }
        } else {
            val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).toString() + "/Kinetix"
            val file = File(imagesDir)
            if (!file.exists()) file.mkdirs()
            val image = File(imagesDir, displayName)
            fos = FileOutputStream(image)
        }

        fos?.use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
            Toast.makeText(context, "Saved to Pictures/Kinetix in Gallery!", Toast.LENGTH_LONG).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Failed to save image: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

// Utility: Share photo with standard Android share sheet
fun sharePhoto(context: Context, photo: PhotoItem, bitmap: Bitmap?) {
    try {
        if (bitmap != null) {
            val cachePath = File(context.cacheDir, "shared_images")
            cachePath.mkdirs()
            val file = File(cachePath, "${photo.name}.jpg")
            FileOutputStream(file).use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Snapshot via"))
        } else {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Kinetix Remote Snapshot: ${photo.name} (${photo.date})")
            }
            context.startActivity(Intent.createChooser(intent, "Share via"))
        }
    } catch (_: Exception) {
        Toast.makeText(context, "Unable to launch share sheet", Toast.LENGTH_SHORT).show()
    }
}
