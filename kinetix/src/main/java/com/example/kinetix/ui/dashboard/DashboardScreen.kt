package com.example.kinetix.ui.dashboard

import android.annotation.SuppressLint
import android.os.Build
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.kinetix.network.KinetixApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class PairedDeviceItem(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val osVersion: String,
    val isOnline: Boolean,
    val lastSeenText: String,
    val isThisDevice: Boolean = false,
    val latitude: Double = 22.5726,
    val longitude: Double = 88.3639,
    val address: String = "Kadampukur - Jhalgachi Rd"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToDiscovery: () -> Unit,
    onNavigateToDeviceDetail: (String) -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val pairedDevices = remember { mutableStateListOf<PairedDeviceItem>() }
    var selectedDevice by remember { mutableStateOf<PairedDeviceItem?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var isSatelliteMode by remember { mutableStateOf(true) }
    var activeTab by remember { mutableStateOf(0) } // 0 = Devices, 1 = People
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Gestures for interactive map navigation
    var mapOffsetX by remember { mutableStateOf(0f) }
    var mapOffsetY by remember { mutableStateOf(0f) }
    var mapScale by remember { mutableStateOf(1f) }

    suspend fun fetchDevices() {
        withContext(Dispatchers.IO) {
            try {
                val client = KinetixApiClient(context)
                client.registerDevice()

                val devicesRes = client.listAvailableDevices()
                val locationsRes = client.getAllLocations()
                val locationsMap = mutableMapOf<String, JSONObject>()

                if (locationsRes.isSuccess) {
                    val locObj = locationsRes.getOrNull()
                    if (locObj != null) {
                        val keys = locObj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            try {
                                locationsMap[key] = locObj.optJSONObject(key) ?: JSONObject()
                            } catch (_: Exception) {}
                        }
                    }
                }

                val newList = mutableListOf<PairedDeviceItem>()

                // This device (Controller device)
                val thisModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                val thisDeviceName = if (thisModel.isNotBlank()) thisModel else "realme 15 Pro 5G"
                newList.add(
                    PairedDeviceItem(
                        deviceId = "THIS_DEVICE",
                        deviceName = thisDeviceName,
                        platform = "Android",
                        osVersion = "Android ${Build.VERSION.RELEASE}",
                        isOnline = true,
                        lastSeenText = "This device",
                        isThisDevice = true,
                        latitude = 22.5726,
                        longitude = 88.3639,
                        address = "Kadampukur - Jhalgachi Rd"
                    )
                )

                if (devicesRes.isSuccess) {
                    val arr = devicesRes.getOrNull()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val item = arr.getJSONObject(i)
                            val devId = if (item.has("deviceId")) item.getString("deviceId") else item.optString("device_id", "")
                            val name = if (item.has("deviceName")) item.getString("deviceName") else item.optString("device_name", "Sentry Device")
                            val platform = if (item.has("platform")) item.getString("platform") else "Android"
                            val osVer = if (item.has("osVersion")) item.getString("osVersion") else item.optString("os_version", "Android 14")
                            val status = if (item.has("status")) item.getString("status") else "ONLINE"

                            if (devId.startsWith("SN")) {
                                val loc = locationsMap[devId]
                                val lat = loc?.optDouble("latitude", 22.5726) ?: 22.5726
                                val lon = loc?.optDouble("longitude", 88.3639) ?: 88.3639
                                val addr = loc?.optString("address", "Kadampukur - Jhalgachi Rd") ?: "Kadampukur - Jhalgachi Rd"

                                newList.add(
                                    PairedDeviceItem(
                                        deviceId = devId,
                                        deviceName = name,
                                        platform = platform,
                                        osVersion = osVer,
                                        isOnline = status.equals("ONLINE", ignoreCase = true),
                                        lastSeenText = "Online now",
                                        isThisDevice = false,
                                        latitude = lat,
                                        longitude = lon,
                                        address = addr
                                    )
                                )
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    pairedDevices.clear()
                    pairedDevices.addAll(newList)
                    if (selectedDevice == null && newList.isNotEmpty()) {
                        selectedDevice = newList.first()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            fetchDevices()
            delay(4000)
        }
    }

    // Marker Pulse Animation
    val infiniteTransition = rememberInfiniteTransition(label = "marker_pulse")
    val pulseSize by infiniteTransition.animateFloat(
        initialValue = 70f,
        targetValue = 130f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_size"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_alpha"
    )

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFFF3F2F8),
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    icon = {
                        Icon(
                            Icons.Default.PermDeviceInformation,
                            contentDescription = "Devices",
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    label = { Text("Devices", fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF1D1B20),
                        selectedTextColor = Color(0xFF1D1B20),
                        indicatorColor = Color(0xFFE8DEF8)
                    )
                )
                NavigationBarItem(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    icon = {
                        Icon(
                            Icons.Default.People,
                            contentDescription = "People",
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    label = { Text("People", fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF1D1B20),
                        selectedTextColor = Color(0xFF1D1B20),
                        indicatorColor = Color(0xFFE8DEF8)
                    )
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF1A1C1E))
        ) {
            val currentTarget = selectedDevice ?: pairedDevices.firstOrNull()

            // 1. Interactive Satellite Map Layer (Top 54% of Screen)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.56f)
                    .clip(RoundedCornerShape(0.dp))
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            mapScale = (mapScale * zoom).coerceIn(0.6f, 3.5f)
                            mapOffsetX += pan.x
                            mapOffsetY += pan.y
                        }
                    }
            ) {
                // High-Fidelity Satellite Terrain & Vector Roads Map Canvas
                Canvas(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val w = size.width
                    val h = size.height

                    if (isSatelliteMode) {
                        // Satellite imagery background gradient
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF3F4D3C),
                                    Color(0xFF2C382A),
                                    Color(0xFF1E281D),
                                    Color(0xFF151D14)
                                ),
                                center = Offset(w * 0.5f + mapOffsetX, h * 0.45f + mapOffsetY),
                                radius = w * 1.2f * mapScale
                            )
                        )

                        // Satellite Building Rooftops & Complex Textures
                        val buildings = listOf(
                            Offset(w * 0.25f, h * 0.25f) to Offset(120f, 90f),
                            Offset(w * 0.52f, h * 0.28f) to Offset(160f, 130f),
                            Offset(w * 0.28f, h * 0.55f) to Offset(140f, 110f),
                            Offset(w * 0.65f, h * 0.52f) to Offset(110f, 140f),
                            Offset(w * 0.12f, h * 0.38f) to Offset(90f, 80f),
                            Offset(w * 0.72f, h * 0.22f) to Offset(130f, 95f)
                        )

                        for ((pos, dims) in buildings) {
                            val bX = (pos.x - w / 2) * mapScale + w / 2 + mapOffsetX
                            val bY = (pos.y - h / 2) * mapScale + h / 2 + mapOffsetY
                            val bW = dims.x * mapScale
                            val bH = dims.y * mapScale

                            // Rooftop surface
                            drawRect(
                                color = Color(0xFF4A443B),
                                topLeft = Offset(bX, bY),
                                size = androidx.compose.ui.geometry.Size(bW, bH)
                            )
                            // Roof details
                            drawRect(
                                color = Color(0xFF2A2D34),
                                topLeft = Offset(bX + 8f * mapScale, bY + 8f * mapScale),
                                size = androidx.compose.ui.geometry.Size(bW * 0.6f, bH * 0.6f)
                            )
                            // Roof border
                            drawRect(
                                color = Color(0xFF6B6254),
                                topLeft = Offset(bX, bY),
                                size = androidx.compose.ui.geometry.Size(bW, bH),
                                style = Stroke(width = 2f * mapScale)
                            )
                        }

                        // Satellite Roads
                        val roadPath = Path().apply {
                            val rY = (h * 0.42f - h / 2) * mapScale + h / 2 + mapOffsetY
                            moveTo(-200f, rY)
                            cubicTo(
                                w * 0.35f + mapOffsetX, rY - 30f * mapScale,
                                w * 0.65f + mapOffsetX, rY + 40f * mapScale,
                                w + 200f, rY - 20f * mapScale
                            )
                        }
                        drawPath(roadPath, color = Color(0xFF59524A), style = Stroke(width = 28f * mapScale))
                        drawPath(roadPath, color = Color(0xFF756C62), style = Stroke(width = 22f * mapScale))
                    } else {
                        // Clean Vector Google Map Mode
                        drawRect(color = Color(0xFFF2EFE9))

                        // Vector Roads
                        val vRoad = Path().apply {
                            val rY = (h * 0.42f - h / 2) * mapScale + h / 2 + mapOffsetY
                            moveTo(-200f, rY)
                            cubicTo(
                                w * 0.35f + mapOffsetX, rY - 30f * mapScale,
                                w * 0.65f + mapOffsetX, rY + 40f * mapScale,
                                w + 200f, rY - 20f * mapScale
                            )
                        }
                        drawPath(vRoad, color = Color(0xFFFFD54F), style = Stroke(width = 26f * mapScale))
                        drawPath(vRoad, color = Color.White, style = Stroke(width = 20f * mapScale))
                    }
                }

                // Center Pin Position
                val pinCenterX = mapOffsetX
                val pinCenterY = mapOffsetY

                // Live Pulsing Aura Ring around Device Location Pin
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(x = pinCenterX.dp / 2.5f, y = pinCenterY.dp / 2.5f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(pulseSize.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2196F3).copy(alpha = pulseAlpha))
                            .align(Alignment.Center)
                    )

                    // Accuracy solid circle
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2196F3).copy(alpha = 0.22f))
                            .border(1.5.dp, Color(0xFF2196F3).copy(alpha = 0.6f), CircleShape)
                            .align(Alignment.Center)
                    )

                    // Google Find My Device Circle Pin
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color.White,
                            shadowElevation = 8.dp,
                            modifier = Modifier
                                .size(50.dp)
                                .border(2.5.dp, Color(0xFF1976D2), CircleShape)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                val isWatch = currentTarget?.deviceName?.contains("Watch", true) == true
                                val isBuds = currentTarget?.deviceName?.contains("Buds", true) == true

                                Icon(
                                    when {
                                        isWatch -> Icons.Default.Watch
                                        isBuds -> Icons.Default.Headphones
                                        else -> Icons.Default.Smartphone
                                    },
                                    contentDescription = null,
                                    tint = Color(0xFF1976D2),
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }

                        // Bottom Pin Point Dot
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1976D2))
                                .border(1.5.dp, Color.White, CircleShape)
                        )
                    }
                }

                // Road Name Street Tag Overlay (Top Center)
                Surface(
                    color = Color.Black.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 44.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Navigation,
                            contentDescription = null,
                            tint = Color(0xFF64B5F6),
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = currentTarget?.address ?: "Kadampukur - Jhalgachi Rd",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Top-Right Floating Controls (Profile Avatar & Layer Switcher)
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 16.dp, end = 16.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Profile Avatar with green status ring
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(2.5.dp, Color(0xFF4CAF50), CircleShape)
                            .clickable { onNavigateToSettings() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.AccountCircle,
                            contentDescription = "Profile",
                            tint = Color(0xFF5C6BC0),
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    // Map Layer Switcher Button (Satellite / Vector)
                    Surface(
                        onClick = { isSatelliteMode = !isSatelliteMode },
                        shape = CircleShape,
                        color = Color.White,
                        shadowElevation = 6.dp,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Layers,
                                contentDescription = "Toggle Satellite",
                                tint = if (isSatelliteMode) Color(0xFF1976D2) else Color(0xFF555555),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                // Bottom-Right Recenter Crosshair Button
                Surface(
                    onClick = {
                        mapOffsetX = 0f
                        mapOffsetY = 0f
                        mapScale = 1f
                    },
                    shape = CircleShape,
                    color = Color.White,
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 28.dp)
                        .size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.MyLocation,
                            contentDescription = "Recenter",
                            tint = Color(0xFF1976D2),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Bottom-Left Watermark
                Text(
                    text = "Google",
                    color = Color.White.copy(alpha = 0.9f),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, bottom = 32.dp)
                )
            }

            // 2. Google Find My Device Draggable Bottom Sheet (Bottom 48% of Screen)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.48f)
                    .align(Alignment.BottomCenter),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = Color.White,
                shadowElevation = 16.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    // Draggable Pill Handle
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE0E0E0))
                            .align(Alignment.CenterHorizontally)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // "✓ My devices" Header & Refresh Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = Color(0xFFECE6F8),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color(0xFF21005D),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "My devices",
                                    color = Color(0xFF21005D),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                coroutineScope.launch {
                                    isRefreshing = true
                                    fetchDevices()
                                    delay(500)
                                    isRefreshing = false
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = Color(0xFF49454F),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Devices List
                    if (pairedDevices.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Searching for Sentry devices...",
                                color = Color.Gray,
                                fontSize = 14.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(pairedDevices) { device ->
                                val isSelected = selectedDevice?.deviceId == device.deviceId

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(
                                            if (isSelected) Color(0xFFF3EDF7) else Color.Transparent
                                        )
                                        .clickable {
                                            selectedDevice = device
                                            mapOffsetX = 0f
                                            mapOffsetY = 0f
                                            mapScale = 1f
                                            if (!device.isThisDevice) {
                                                onNavigateToDeviceDetail(device.deviceId)
                                            }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Device Icon
                                    Box(
                                        modifier = Modifier.size(42.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val isWatch = device.deviceName.contains("Watch", ignoreCase = true)
                                        val isBuds = device.deviceName.contains("Buds", ignoreCase = true) || device.deviceName.contains("AirPods", ignoreCase = true)

                                        Icon(
                                            when {
                                                isWatch -> Icons.Default.Watch
                                                isBuds -> Icons.Default.Headphones
                                                else -> Icons.Default.Smartphone
                                            },
                                            contentDescription = null,
                                            tint = if (device.isThisDevice) Color(0xFF1976D2) else Color(0xFF49454F),
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(14.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = device.deviceName,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = Color(0xFF1D1B20)
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = if (device.isThisDevice) "This device" else "${device.osVersion} • ${device.lastSeenText}",
                                            color = if (device.isThisDevice) Color(0xFF1976D2) else Color(0xFF49454F),
                                            fontSize = 13.sp,
                                            fontWeight = if (device.isThisDevice) FontWeight.Medium else FontWeight.Normal
                                        )
                                    }

                                    if (!device.isThisDevice) {
                                        Icon(
                                            Icons.Default.ChevronRight,
                                            contentDescription = "Details",
                                            tint = Color(0xFF9E9E9E),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
