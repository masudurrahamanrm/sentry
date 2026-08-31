package com.example.kinetix.ui.dashboard

import android.annotation.SuppressLint
import android.os.Build
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

    suspend fun fetchDevices() {
        withContext(Dispatchers.IO) {
            val client = KinetixApiClient(context)
            client.registerDevice()

            // 1. Fetch available remote Sentry agents
            val devicesRes = client.listAvailableDevices()
            val locationsRes = client.getAllLocations()
            val locationsMap = mutableMapOf<String, JSONObject>()

            if (locationsRes.isSuccess) {
                val locObj = locationsRes.getOrNull()
                if (locObj != null) {
                    val keys = locObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        locationsMap[key] = locObj.getJSONObject(key)
                    }
                }
            }

            val newList = mutableListOf<PairedDeviceItem>()

            // Add This Device as primary Find My item
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
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            fetchDevices()
            delay(4000)
        }
    }

    // Function to update map center via JS
    fun updateMapLocation(lat: Double, lon: Double, deviceName: String, isSat: Boolean) {
        val tileUrl = if (isSat) {
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
        } else {
            "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        }
        val attribution = if (isSat) "© Esri World Imagery" else "© OpenStreetMap"

        val js = """
            if (window.map) {
                window.map.setView([$lat, $lon], 17);
                if (window.deviceMarker) {
                    window.deviceMarker.setLatLng([$lat, $lon]);
                }
                if (window.tileLayer) {
                    window.tileLayer.setUrl('$tileUrl');
                }
            }
        """.trimIndent()
        webViewRef?.evaluateJavascript(js, null)
    }

    LaunchedEffect(selectedDevice, isSatelliteMode) {
        selectedDevice?.let { dev ->
            updateMapLocation(dev.latitude, dev.longitude, dev.deviceName, isSatelliteMode)
        }
    }

    // Animation for pulse marker
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = Color.White,
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
                    label = { Text("Devices", fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF1E192B),
                        selectedTextColor = Color(0xFF1E192B),
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
                    label = { Text("People", fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF1E192B),
                        selectedTextColor = Color(0xFF1E192B),
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
            val targetLat = currentTarget?.latitude ?: 22.5726
            val targetLon = currentTarget?.longitude ?: 88.3639

            // 1. Full Screen / Top Half Live Interactive Map View
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.56f)
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.cacheMode = WebSettings.LOAD_DEFAULT
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    selectedDevice?.let { dev ->
                                        updateMapLocation(dev.latitude, dev.longitude, dev.deviceName, isSatelliteMode)
                                    }
                                }
                            }

                            val initialTile = if (isSatelliteMode) {
                                "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
                            } else {
                                "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
                            }

                            val html = """
                                <!DOCTYPE html>
                                <html>
                                <head>
                                    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
                                    <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
                                    <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
                                    <style>
                                        body, html, #map { margin: 0; padding: 0; width: 100%; height: 100%; background: #263238; }
                                        .leaflet-control-attribution { display: none; }
                                        .leaflet-control-zoom { display: none; }
                                        .custom-pin {
                                            display: flex;
                                            flex-direction: column;
                                            align-items: center;
                                            justify-content: center;
                                        }
                                        .pin-card {
                                            width: 48px;
                                            height: 48px;
                                            background: white;
                                            border-radius: 50%;
                                            box-shadow: 0 4px 14px rgba(0,0,0,0.35);
                                            display: flex;
                                            align-items: center;
                                            justify-content: center;
                                            border: 2.5px solid #2196F3;
                                        }
                                        .pin-dot {
                                            width: 10px;
                                            height: 10px;
                                            background: #1976D2;
                                            border-radius: 50%;
                                            border: 2px solid white;
                                            margin-top: 2px;
                                        }
                                        .pulse-ring {
                                            position: absolute;
                                            width: 90px;
                                            height: 90px;
                                            border-radius: 50%;
                                            background: rgba(33, 150, 243, 0.28);
                                            animation: pulsate 2s infinite ease-in-out;
                                            pointer-events: none;
                                        }
                                        @keyframes pulsate {
                                            0% { transform: scale(0.7); opacity: 0.7; }
                                            50% { transform: scale(1.3); opacity: 0.2; }
                                            100% { transform: scale(0.7); opacity: 0.7; }
                                        }
                                    </style>
                                </head>
                                <body>
                                    <div id="map"></div>
                                    <script>
                                        var map = L.map('map', { zoomControl: false, attributionControl: false }).setView([$targetLat, $targetLon], 17);
                                        var tileLayer = L.tileLayer('$initialTile', { maxZoom: 19 }).addTo(map);
                                        
                                        var iconHtml = '<div class="custom-pin">' +
                                                       '  <div class="pulse-ring"></div>' +
                                                       '  <div class="pin-card">' +
                                                       '    <svg width="24" height="24" viewBox="0 0 24 24" fill="#1976D2">' +
                                                       '      <path d="M17 1.01L7 1c-1.1 0-2 .9-2 2v18c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V3c0-1.1-.9-1.99-2-1.99zM17 19H7V5h10v14z"/>' +
                                                       '    </svg>' +
                                                       '  </div>' +
                                                       '  <div class="pin-dot"></div>' +
                                                       '</div>';
                                        
                                        var customIcon = L.divIcon({
                                            html: iconHtml,
                                            className: '',
                                            iconSize: [48, 58],
                                            iconAnchor: [24, 56]
                                        });
                                        
                                        var deviceMarker = L.marker([$targetLat, $targetLon], { icon: customIcon }).addTo(map);
                                        window.map = map;
                                        window.deviceMarker = deviceMarker;
                                        window.tileLayer = tileLayer;
                                    </script>
                                </body>
                                </html>
                            """.trimIndent()

                            loadDataWithBaseURL("https://leafletjs.com", html, "text/html", "UTF-8", null)
                            webViewRef = this
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Live Street / Road Banner on Map
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 40.dp)
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Navigation,
                                contentDescription = null,
                                tint = Color(0xFF64B5F6),
                                modifier = Modifier.size(16.dp)
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
                }

                // Top-Right Floating Controls (Avatar & Layers)
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 16.dp, end = 16.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Profile Avatar with active ring
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

                    // Map Layer Switcher Button (Satellite / Normal)
                    Surface(
                        onClick = {
                            isSatelliteMode = !isSatelliteMode
                            selectedDevice?.let { dev ->
                                updateMapLocation(dev.latitude, dev.longitude, dev.deviceName, isSatelliteMode)
                            }
                        },
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

                // Bottom-Right Recenter Button
                Surface(
                    onClick = {
                        selectedDevice?.let { dev ->
                            updateMapLocation(dev.latitude, dev.longitude, dev.deviceName, isSatelliteMode)
                        }
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

                // Bottom Left Watermark
                Text(
                    text = "Google",
                    color = Color.White.copy(alpha = 0.85f),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, bottom = 32.dp)
                )
            }

            // 2. Bottom Sheet: Device List & Controls
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
                                text = "Searching for Sentry devices on network...",
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
                                            updateMapLocation(device.latitude, device.longitude, device.deviceName, isSatelliteMode)
                                            if (!device.isThisDevice) {
                                                onNavigateToDeviceDetail(device.deviceId)
                                            }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Device Icon
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp),
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
