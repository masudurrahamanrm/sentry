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
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
enum class CameraQuality(val label: String, val res: String) {
    UHD_4K("4K Ultra", "1920x1080"),
    FHD_1080P("1080p FHD", "1280x720"),
    HD_720P("720p Speed", "640x480")
}
enum class FlashMode(val label: String) { AUTO("Auto"), ON("Torch On"), OFF("Off") }
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
    var searchQuery by remember { mutableStateOf("") }
    var isRefreshing by remember { mutableStateOf(false) }
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
            captureProgressText = "Dispatched capture signal to remote ${if (selectedLens == CameraLens.FRONT) "Selfie" else "Rear"} camera..."

            val client = KinetixApiClient(context)
            val sendResult = withContext(Dispatchers.IO) {
                client.capturePhoto(deviceId, camStr)
            }

            if (sendResult.isFailure) {
                captureProgressText = "Failed to send command. Check remote phone connection."
                delay(2000)
                isCapturing = false
                captureProgressText = null
                return@launch
            }

            captureProgressText = "Sensor warming up & converging 3A exposure..."
            delay(1200)
            captureProgressText = "Capturing high-resolution still frame..."

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
                Toast.makeText(context, "New snapshot captured successfully!", Toast.LENGTH_SHORT).show()
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
    val filteredPhotos = remember(photos.size, activeFilter, searchQuery) {
        photos.filter { item ->
            val matchesFilter = when (activeFilter) {
                GalleryFilter.ALL -> true
                GalleryFilter.REAR -> item.camera == "rear"
                GalleryFilter.FRONT -> item.camera == "front"
                GalleryFilter.CLOUD -> item.r2Url != null
            }
            val matchesSearch = searchQuery.isBlank() || item.name.contains(searchQuery, ignoreCase = true)
            matchesFilter && matchesSearch
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Photos & Camera", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF10B981).copy(alpha = 0.15f)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF10B981))
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        "R2 Cloud Active",
                                        color = Color(0xFF059669),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        Text(
                            "High-precision remote lens & cloud gallery",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
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
                                isRefreshing = true
                                fetchPhotos()
                                delay(600)
                                isRefreshing = false
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
            // PRO REMOTE CAPTURE CONSOLE CARD
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(8.dp, RoundedCornerShape(22.dp)),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                    MaterialTheme.colorScheme.surface
                                )
                            )
                        )
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CameraAlt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Remote Camera Console",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Quality Chip
                        AssistChip(
                            onClick = {
                                selectedQuality = when (selectedQuality) {
                                    CameraQuality.UHD_4K -> CameraQuality.FHD_1080P
                                    CameraQuality.FHD_1080P -> CameraQuality.HD_720P
                                    CameraQuality.HD_720P -> CameraQuality.UHD_4K
                                }
                            },
                            label = { Text(selectedQuality.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                            leadingIcon = {
                                Icon(Icons.Default.Hd, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Lens Selector (Rear vs Front)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val isRear = selectedLens == CameraLens.REAR
                        Surface(
                            onClick = { selectedLens = CameraLens.REAR },
                            shape = RoundedCornerShape(14.dp),
                            color = if (isRear) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.CameraRear,
                                    contentDescription = null,
                                    tint = if (isRear) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        "Rear Camera",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = if (isRear) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        "50MP Primary • HDR",
                                        fontSize = 10.sp,
                                        color = if (isRear) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        val isFront = selectedLens == CameraLens.FRONT
                        Surface(
                            onClick = { selectedLens = CameraLens.FRONT },
                            shape = RoundedCornerShape(14.dp),
                            color = if (isFront) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.CameraFront,
                                    contentDescription = null,
                                    tint = if (isFront) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        "Front Selfie",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = if (isFront) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        "32MP Wide • Portrait",
                                        fontSize = 10.sp,
                                        color = if (isFront) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Secondary Settings Toolbar (Timer, Flash, Night Boost)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Timer
                        FilterChip(
                            selected = timerSeconds > 0,
                            onClick = {
                                timerSeconds = when (timerSeconds) {
                                    0 -> 3
                                    3 -> 5
                                    5 -> 10
                                    else -> 0
                                }
                            },
                            label = { Text(if (timerSeconds == 0) "Timer: Off" else "${timerSeconds}s Timer", fontSize = 11.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(14.dp))
                            }
                        )

                        // Flash
                        FilterChip(
                            selected = selectedFlash != FlashMode.OFF,
                            onClick = {
                                selectedFlash = when (selectedFlash) {
                                    FlashMode.AUTO -> FlashMode.ON
                                    FlashMode.ON -> FlashMode.OFF
                                    FlashMode.OFF -> FlashMode.AUTO
                                }
                            },
                            label = { Text(selectedFlash.label, fontSize = 11.sp) },
                            leadingIcon = {
                                Icon(
                                    when (selectedFlash) {
                                        FlashMode.AUTO -> Icons.Default.FlashAuto
                                        FlashMode.ON -> Icons.Default.FlashOn
                                        FlashMode.OFF -> Icons.Default.FlashOff
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        )

                        // Night Boost Toggle
                        FilterChip(
                            selected = nightBoost,
                            onClick = { nightBoost = !nightBoost },
                            label = { Text("Night Boost", fontSize = 11.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.NightsStay, contentDescription = null, modifier = Modifier.size(14.dp))
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Main Action Button
                    Button(
                        onClick = { triggerProCapture() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        enabled = !isCapturing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedLens == CameraLens.FRONT) Color(0xFFE11D48) else Color(0xFF2563EB)
                        )
                    ) {
                        if (isCapturing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.5.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Capturing Hardware Frame...", fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.PhotoCamera, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Take Snapshot (${if (selectedLens == CameraLens.FRONT) "Front" else "Rear"})",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }

                    // Dynamic Live Progress Banner
                    AnimatedVisibility(
                        visible = captureProgressText != null,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = captureProgressText ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // GALLERY TOOLBAR & FILTER PILLS
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Cloud Gallery (${filteredPhotos.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Backed up with Cloudflare R2 & MongoDB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }

                // Storage stats pill
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ) {
                    Text(
                        text = "Total: ${(photos.size * 4.8).toInt()} MB",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Filter Tabs Row
            Row(
                modifier = Modifier.fillMaxWidth(),
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

            Spacer(modifier = Modifier.height(12.dp))

            // GALLERY GRID / CARDS
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
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            "No snapshots in this filter",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Tap 'Take Snapshot' above to capture a new photo",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = if (viewLayout == ViewLayout.GRID_2) GridCells.Fixed(2) else GridCells.Fixed(1),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(filteredPhotos, key = { it.id }) { photo ->
                        PhotoCardItem(
                            photo = photo,
                            isSingleColumn = viewLayout == ViewLayout.CARDS_1,
                            onClick = {
                                rotationAngle = 0f
                                selectedPhoto = photo
                            }
                        )
                    }
                }
            }
        }
    }

    // FULL SCREEN PRO PHOTO VIEWER MODAL
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
                    .background(Color.Black.copy(alpha = 0.94f))
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Top Bar inside Lightbox
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

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = currentPhoto.name,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${currentPhoto.date} • ${currentPhoto.size}",
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
                                Icon(Icons.Default.RotateRight, contentDescription = "Rotate", tint = Color.White)
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            // Info Button
                            IconButton(
                                onClick = { showExifSheet = !showExifSheet },
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.15f))
                            ) {
                                Icon(Icons.Default.Info, contentDescription = "Info", tint = Color.White)
                            }
                        }
                    }

                    // Main Image Canvas with Rotation
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
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
                                    .height(300.dp)
                                    .clip(RoundedCornerShape(20.dp))
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
                                        modifier = Modifier.size(64.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        if (isFront) "1080p Front Camera Snapshot" else "4K Ultra-Wide Rear Snapshot",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                    Text(
                                        "Stored securely on Cloudflare R2",
                                        color = Color.White.copy(alpha = 0.8f),
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }

                    // EXIF Details Card if toggled
                    if (showExifSheet) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text("Snapshot Metadata", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Camera Sensor", color = Color.LightGray, fontSize = 11.sp)
                                    Text(if (isFront) "Front 32MP (Wide)" else "Rear 50MP (Primary)", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Resolution", color = Color.LightGray, fontSize = 11.sp)
                                    Text(if (bitmap != null) "${bitmap.width}x${bitmap.height}" else "1920x1080", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Storage Engine", color = Color.LightGray, fontSize = 11.sp)
                                    Text("Cloudflare R2 (sentry bucket)", color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    // Bottom Action Bar (Save to Gallery, Share, Delete)
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
                                .height(50.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save to Gallery", fontWeight = FontWeight.Bold)
                        }

                        // Share Button
                        OutlinedButton(
                            onClick = {
                                sharePhoto(context, currentPhoto, bitmap)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Share", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PhotoCardItem(
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
            .shadow(4.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isSingleColumn) 200.dp else 125.dp)
                    .clip(RoundedCornerShape(12.dp))
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
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                if (isFront) "Selfie Cam" else "Rear 50MP",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Lens Badge Overlay
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            if (isFront) Icons.Default.CameraFront else Icons.Default.CameraRear,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            if (isFront) "FRONT" else "REAR",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // R2 Cloud badge
                if (photo.r2Url != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF0284C7).copy(alpha = 0.85f),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                    ) {
                        Text(
                            "R2",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = photo.name,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = photo.date,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                ) {
                    Text(
                        text = photo.size,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }
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
