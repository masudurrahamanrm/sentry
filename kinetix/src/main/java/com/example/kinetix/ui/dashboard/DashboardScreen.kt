package com.example.kinetix.ui.dashboard

import android.annotation.SuppressLint
import android.os.Build
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val address: String = "Kadampukur - Jhalgachi Rd",
    val batteryLevel: Int = 87,
    val batteryStatus: String = "Fast Charging (USB-PD 33W)"
)

@SuppressLint("SetJavaScriptEnabled")
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
    var showDeviceDetails by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var isSatelliteMode by remember { mutableStateOf(true) }
    var activeTab by remember { mutableStateOf(0) } // 0 = Devices, 1 = People
    var actionFeedbackMessage by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

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
                        address = "Kadampukur - Jhalgachi Rd",
                        batteryLevel = 92,
                        batteryStatus = "Fast Charging (USB-PD)"
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
                                        address = addr,
                                        batteryLevel = 87,
                                        batteryStatus = "Optimal Health (Good)"
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

    // Function to update map center and marker via JS
    fun updateMapTarget(lat: Double, lon: Double, deviceName: String) {
        val js = """
            if (window.updateDeviceLocation) {
                window.updateDeviceLocation($lat, $lon, '$deviceName');
            }
        """.trimIndent()
        webViewRef?.evaluateJavascript(js, null)
    }

    LaunchedEffect(selectedDevice) {
        selectedDevice?.let { dev ->
            updateMapTarget(dev.latitude, dev.longitude, dev.deviceName)
        }
    }

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
            val targetLat = currentTarget?.latitude ?: 22.5726
            val targetLon = currentTarget?.longitude ?: 88.3639
            val targetName = currentTarget?.deviceName ?: "realme 15 Pro 5G"

            // 1. Live Real Satellite Map (Top 54% of Screen)
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
                            settings.databaseEnabled = true
                            settings.allowFileAccess = true
                            settings.allowContentAccess = true
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            settings.cacheMode = WebSettings.LOAD_DEFAULT
                            settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                            webChromeClient = WebChromeClient()

                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    selectedDevice?.let { dev ->
                                        updateMapTarget(dev.latitude, dev.longitude, dev.deviceName)
                                    }
                                }
                            }

                            // Inject JavaScript Bridge to handle clicks on the map pin
                            addJavascriptInterface(object {
                                @JavascriptInterface
                                fun onMarkerClicked() {
                                    coroutineScope.launch(Dispatchers.Main) {
                                        showDeviceDetails = true
                                    }
                                }
                            }, "AndroidBridge")

                            loadUrl("file:///android_asset/map.html")
                            webViewRef = this
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

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

                    // Map Layer Switcher Button (Uber Dark / Uber Day / Satellite)
                    var currentLayerIndex by remember { mutableIntStateOf(0) } // 0 = dark, 1 = day, 2 = sat
                    Surface(
                        onClick = {
                            currentLayerIndex = (currentLayerIndex + 1) % 3
                            val layerName = when (currentLayerIndex) {
                                0 -> "dark"
                                1 -> "day"
                                else -> "satellite"
                            }
                            webViewRef?.evaluateJavascript("window.toggleMapLayer('$layerName')", null)
                        },
                        shape = CircleShape,
                        color = Color(0xFF1E1E1E),
                        shadowElevation = 8.dp,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Layers,
                                contentDescription = "Toggle Layer",
                                tint = when (currentLayerIndex) {
                                    0 -> Color(0xFF276EF1)
                                    1 -> Color(0xFFFFC107)
                                    else -> Color(0xFF4CAF50)
                                },
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                // Bottom-Right Map Controls: Zoom In (+), Zoom Out (-), Recenter (🎯)
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Zoom In Button (+)
                    Surface(
                        onClick = {
                            webViewRef?.evaluateJavascript("window.mapZoomIn()", null)
                        },
                        shape = CircleShape,
                        color = Color(0xFF1E1E1E),
                        shadowElevation = 6.dp,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Zoom In",
                                tint = Color(0xFF276EF1),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    // Zoom Out Button (-)
                    Surface(
                        onClick = {
                            webViewRef?.evaluateJavascript("window.mapZoomOut()", null)
                        },
                        shape = CircleShape,
                        color = Color(0xFF1E1E1E),
                        shadowElevation = 6.dp,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Remove,
                                contentDescription = "Zoom Out",
                                tint = Color(0xFF276EF1),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    // Recenter Crosshair Button
                    Surface(
                        onClick = {
                            selectedDevice?.let { dev ->
                                webViewRef?.evaluateJavascript("window.recenterMap(${dev.latitude}, ${dev.longitude})", null)
                            }
                        },
                        shape = CircleShape,
                        color = Color(0xFF1E1E1E),
                        shadowElevation = 8.dp,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.MyLocation,
                                contentDescription = "Recenter",
                                tint = Color(0xFF276EF1),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                // Bottom-Left Uber Watermark
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, bottom = 32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Uber",
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp,
                        letterSpacing = (-0.5).sp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Maps",
                        color = Color(0xFF276EF1),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
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

                    if (showDeviceDetails && selectedDevice != null) {
                        // --- DETAILS VIEW ---
                        val dev = selectedDevice!!
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { showDeviceDetails = false },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("All devices", fontWeight = FontWeight.Bold)
                            }

                            Surface(
                                color = if (dev.isOnline) Color(0xFFE8F5E9) else Color(0xFFECEFF1),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = if (dev.isOnline) "• Online now" else "• Offline",
                                    color = if (dev.isOnline) Color(0xFF2E7D32) else Color(0xFF546E7A),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Device Header Card
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFFECE6F8)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Smartphone, contentDescription = null, tint = Color(0xFF6750A4), modifier = Modifier.size(28.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(dev.deviceName, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFF1D1B20))
                                Text(if (dev.isThisDevice) "This device • ${dev.osVersion}" else "${dev.deviceId} • ${dev.osVersion}", fontSize = 12.sp, color = Color.Gray)
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Telemetry Info Chips (Battery & GPS)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                color = Color(0xFFF3EDF7),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("${dev.batteryLevel}% • Charging", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            Surface(
                                color = Color(0xFFF3EDF7),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.GpsFixed, contentDescription = null, tint = Color(0xFF1976D2), modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("±3m • GPS Live", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        if (actionFeedbackMessage != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(actionFeedbackMessage!!, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Quick Action Buttons Grid
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    actionFeedbackMessage = "Playing loud alarm on ${dev.deviceName}..."
                                    coroutineScope.launch {
                                        delay(3000)
                                        actionFeedbackMessage = null
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                            ) {
                                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Play Sound", fontSize = 12.sp)
                            }

                            if (!dev.isThisDevice) {
                                Button(
                                    onClick = { onNavigateToDeviceDetail(dev.deviceId) },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4))
                                ) {
                                    Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Control Hub", fontSize = 12.sp)
                                }
                            }
                        }
                    } else {
                        // --- DEVICE LIST VIEW ---
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
                                                showDeviceDetails = true
                                                updateMapTarget(device.latitude, device.longitude, device.deviceName)
                                            }
                                            .padding(horizontal = 8.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
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
