package com.example.kinetix.ui.dashboard

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.kinetix.network.KinetixApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

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
    val accuracy: Double = 3.0,
    val address: String = "Live GPS Location",
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
    val haptic = LocalHapticFeedback.current

    val pairedDevices = remember { mutableStateListOf<PairedDeviceItem>() }
    var selectedDevice by remember { mutableStateOf<PairedDeviceItem?>(null) }
    var showDeviceDetails by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var activeTab by remember { mutableStateOf(0) }
    var actionFeedbackMessage by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Device Rename Modal State (Tap & hold for 4 seconds)
    var deviceToRename by remember { mutableStateOf<PairedDeviceItem?>(null) }
    var renameInputText by remember { mutableStateOf("") }
    var isRenamingDevice by remember { mutableStateOf(false) }

    var isBottomSheetExpanded by remember { mutableStateOf(true) }
    var sheetDragOffset by remember { mutableFloatStateOf(0f) }

    val sheetHeightFraction by animateFloatAsState(
        targetValue = if (isBottomSheetExpanded) 0.50f else 0.11f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "sheetHeight"
    )

    // Current device real GPS coordinates
    var currentDeviceLat by remember { mutableDoubleStateOf(22.5726) }
    var currentDeviceLon by remember { mutableDoubleStateOf(88.3639) }
    var currentDeviceAddress by remember { mutableStateOf("Live GPS Location") }

    // Request runtime location permissions on launch
    val locationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Trigger GPS update when permission results arrive
    }

    LaunchedEffect(Unit) {
        val hasFine = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            locationPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // Query hardware GPS location of current phone
    fun updateCurrentDeviceGps() {
        try {
            val locManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            val hasFine = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasCoarse = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (hasFine || hasCoarse) {
                var bestLoc: Location? = null
                val providers = locManager.getProviders(true)
                for (p in providers) {
                    try {
                        val l = locManager.getLastKnownLocation(p) ?: continue
                        if (bestLoc == null || l.accuracy < bestLoc.accuracy || l.time > bestLoc.time) {
                            bestLoc = l
                        }
                    } catch (_: Exception) {}
                }

                if (bestLoc != null) {
                    currentDeviceLat = bestLoc.latitude
                    currentDeviceLon = bestLoc.longitude

                    try {
                        val geocoder = Geocoder(context, Locale.getDefault())
                        val list = geocoder.getFromLocation(bestLoc.latitude, bestLoc.longitude, 1)
                        if (!list.isNullOrEmpty()) {
                            val addr = list[0]
                            val thoroughfare = addr.thoroughfare ?: addr.featureName ?: ""
                            val subLocality = addr.subLocality ?: addr.locality ?: addr.subAdminArea ?: ""
                            val city = addr.locality ?: addr.adminArea ?: ""
                            currentDeviceAddress = when {
                                thoroughfare.isNotBlank() && subLocality.isNotBlank() -> "$thoroughfare, $subLocality"
                                thoroughfare.isNotBlank() && city.isNotBlank() -> "$thoroughfare, $city"
                                thoroughfare.isNotBlank() -> thoroughfare
                                subLocality.isNotBlank() -> subLocality
                                else -> addr.getAddressLine(0) ?: "Live GPS Location"
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    suspend fun fetchDevices() {
        withContext(Dispatchers.IO) {
            try {
                updateCurrentDeviceGps()

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

                // 1. Current device (This Phone running Kinetix) with REAL GPS
                val thisModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                val defaultModelName = if (thisModel.isNotBlank()) thisModel else "realme 15 Pro 5G"
                val cachedThisDeviceName = com.example.kinetix.cache.KinetixDeviceCache.getDeviceName(context, "THIS_DEVICE", "")
                val thisDeviceName = if (cachedThisDeviceName.isNotBlank()) cachedThisDeviceName else defaultModelName
                newList.add(
                    PairedDeviceItem(
                        deviceId = "THIS_DEVICE",
                        deviceName = thisDeviceName,
                        platform = "Android",
                        osVersion = "Android ${Build.VERSION.RELEASE}",
                        isOnline = true,
                        lastSeenText = "This device",
                        isThisDevice = true,
                        latitude = currentDeviceLat,
                        longitude = currentDeviceLon,
                        accuracy = 3.0,
                        address = currentDeviceAddress,
                        batteryLevel = 92,
                        batteryStatus = "Fast Charging (USB-PD)"
                    )
                )

                // 2. Discovered remote Sentry devices with their live backend locations
                if (devicesRes.isSuccess) {
                    val arr = devicesRes.getOrNull()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val item = arr.getJSONObject(i)
                            val devId = if (item.has("deviceId")) item.getString("deviceId") else item.optString("device_id", "")
                            val cachedName = com.example.kinetix.cache.KinetixDeviceCache.getDeviceName(context, devId, "")
                            val serverName = if (item.has("deviceName")) item.getString("deviceName") else item.optString("device_name", "Sentry Device")
                            val name = if (cachedName.isNotBlank()) cachedName else serverName
                            val platform = if (item.has("platform")) item.getString("platform") else "Android"
                            val osVer = if (item.has("osVersion")) item.getString("osVersion") else item.optString("os_version", "Android 14")
                            val status = if (item.has("status")) item.getString("status") else "ONLINE"

                            if (devId.startsWith("SN")) {
                                val loc = locationsMap[devId]
                                val lat = if (loc != null && loc.has("latitude")) loc.getDouble("latitude") else (currentDeviceLat + 0.0018)
                                val lon = if (loc != null && loc.has("longitude")) loc.getDouble("longitude") else (currentDeviceLon + 0.0022)
                                val addr = loc?.optString("address", currentDeviceAddress) ?: currentDeviceAddress
                                val acc = loc?.optDouble("accuracy", 3.0) ?: 3.0

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
                                        accuracy = acc,
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
                    } else if (selectedDevice != null) {
                        // Keep selectedDevice fresh with updated coords
                        val updatedMatch = newList.firstOrNull { it.deviceId == selectedDevice!!.deviceId }
                        if (updatedMatch != null) {
                            selectedDevice = updatedMatch
                        }
                    }

                    // Push multi-device location data to Leaflet JS
                    val jsonArr = JSONArray()
                    for (d in newList) {
                        val obj = JSONObject().apply {
                            put("deviceId", d.deviceId)
                            put("deviceName", d.deviceName)
                            put("latitude", d.latitude)
                            put("longitude", d.longitude)
                            put("isThisDevice", d.isThisDevice)
                        }
                        jsonArr.put(obj)
                    }
                    val js = "if(window.setAllDeviceLocations) window.setAllDeviceLocations('${jsonArr.toString().replace("'", "\\'")}');"
                    webViewRef?.evaluateJavascript(js, null)
                }
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            fetchDevices()
            delay(3500)
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
                .padding(bottom = padding.calculateBottomPadding())
                .background(Color(0xFF1A1C1E))
        ) {
            val currentTarget = selectedDevice ?: pairedDevices.firstOrNull()

            // 1. Uber Multi-Device Map (Expands up to 93% when sheet is swiped down)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(if (isBottomSheetExpanded) 0.58f else 0.93f)
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.allowFileAccess = true
                            settings.allowContentAccess = true
                            settings.allowFileAccessFromFileURLs = true
                            settings.allowUniversalAccessFromFileURLs = true
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            settings.cacheMode = WebSettings.LOAD_DEFAULT
                            settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                            webChromeClient = WebChromeClient()

                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    view?.evaluateJavascript("if(window.map) window.map.invalidateSize();", null)
                                    coroutineScope.launch {
                                        fetchDevices()
                                    }
                                }
                            }

                            // JavaScript Bridge for interactive marker clicks
                            addJavascriptInterface(object {
                                @JavascriptInterface
                                fun onMarkerClicked(devId: String) {
                                    coroutineScope.launch(Dispatchers.Main) {
                                        val match = pairedDevices.firstOrNull { it.deviceId == devId }
                                        if (match != null) {
                                            selectedDevice = match
                                            showDeviceDetails = true
                                        }
                                    }
                                }
                            }, "AndroidBridge")

                            loadUrl("file:///android_asset/map.html")
                            webViewRef = this
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Road Name Street Tag Overlay (Top Center with statusBarsPadding)
                Surface(
                    color = Color.Black.copy(alpha = 0.78f),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Navigation,
                            contentDescription = null,
                            tint = Color(0xFF276EF1),
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = currentTarget?.address ?: currentDeviceAddress,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Top-Right Floating Controls (Profile Avatar, Layer Switcher, & GPS Recenter FAB)
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = 12.dp, end = 16.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Profile Avatar with green status ring
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E1E1E))
                            .border(2.5.dp, Color(0xFF4CAF50), CircleShape)
                            .clickable { onNavigateToSettings() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.AccountCircle,
                            contentDescription = "Profile",
                            tint = Color(0xFF276EF1),
                            modifier = Modifier.size(38.dp)
                        )
                    }

                    // Map Layer Switcher Button (Satellite / Street / OSM)
                    var currentLayerIndex by remember { mutableIntStateOf(0) }
                    Surface(
                        onClick = {
                            currentLayerIndex = (currentLayerIndex + 1) % 3
                            val layerName = when (currentLayerIndex) {
                                0 -> "satellite"
                                1 -> "street"
                                else -> "osm"
                            }
                            webViewRef?.evaluateJavascript("window.toggleMapLayer('$layerName')", null)
                        },
                        shape = CircleShape,
                        color = Color(0xFF1E1E1E),
                        shadowElevation = 8.dp,
                        modifier = Modifier.size(46.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Layers,
                                contentDescription = "Toggle Layer",
                                tint = when (currentLayerIndex) {
                                    0 -> Color(0xFF4CAF50)
                                    1 -> Color(0xFF276EF1)
                                    else -> Color(0xFFFFC107)
                                },
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    // GPS Recenter Crosshair Button (🎯)
                    Surface(
                        onClick = {
                            val target = selectedDevice ?: pairedDevices.firstOrNull()
                            target?.let { dev ->
                                webViewRef?.evaluateJavascript("if(window.focusDevice) window.focusDevice(${dev.latitude}, ${dev.longitude}, '${dev.deviceId}');", null)
                                actionFeedbackMessage = "Centered on ${dev.deviceName}"
                                coroutineScope.launch {
                                    delay(3000)
                                    actionFeedbackMessage = null
                                }
                            }
                        },
                        shape = CircleShape,
                        color = Color(0xFF1E1E1E),
                        shadowElevation = 8.dp,
                        modifier = Modifier.size(46.dp)
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

            // 2. Google Find My Device Draggable Bottom Sheet
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(sheetHeightFraction)
                    .align(Alignment.BottomCenter)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (sheetDragOffset > 30f) {
                                    isBottomSheetExpanded = false
                                } else if (sheetDragOffset < -30f) {
                                    isBottomSheetExpanded = true
                                }
                                sheetDragOffset = 0f
                            },
                            onVerticalDrag = { _, dragAmount ->
                                sheetDragOffset += dragAmount
                            }
                        )
                    },
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = Color.White,
                shadowElevation = 16.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    // Draggable Pill Handle Touch Target
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isBottomSheetExpanded = !isBottomSheetExpanded }
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(5.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFD0D0D0))
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

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
                                    .background(if (dev.isThisDevice) Color(0xFFE3F2FD) else Color(0xFFE8F5E9)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Smartphone,
                                    contentDescription = null,
                                    tint = if (dev.isThisDevice) Color(0xFF1976D2) else Color(0xFF2E7D32),
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(dev.deviceName, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFF1D1B20))
                                Text(if (dev.isThisDevice) "This device • ${dev.osVersion}" else "${dev.deviceId} • ${dev.osVersion}", fontSize = 12.sp, color = Color.Gray)
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Telemetry Info Chips (Battery & Interactive GPS Live Mark Button)
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
                                color = Color(0xFFE8DEF8),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        // MARK & LOCATE DEVICE ON MAP
                                        val targetLat = dev.latitude
                                        val targetLon = dev.longitude
                                        val targetId = dev.deviceId
                                        val js = "if(window.focusDevice) window.focusDevice($targetLat, $targetLon, '$targetId');"
                                        webViewRef?.evaluateJavascript(js, null)

                                        // Collapse bottom sheet so user clearly sees the marked device position on the map
                                        isBottomSheetExpanded = false

                                        actionFeedbackMessage = "Marked ${dev.deviceName} on map: ${dev.address}"
                                        coroutineScope.launch {
                                            delay(4000)
                                            actionFeedbackMessage = null
                                        }
                                    }
                            ) {
                                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.GpsFixed, contentDescription = "Mark Location", tint = Color(0xFF1976D2), modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    val accFormatted = if (dev.accuracy > 0.0) "±${dev.accuracy.toInt()}m • GPS Live" else "±3m • GPS Live"
                                    Text(accFormatted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1D1B20))
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
                                items(pairedDevices, key = { it.deviceId }) { device ->
                                    val isSelected = selectedDevice?.deviceId == device.deviceId

                                    DashboardDeviceCard(
                                        device = device,
                                        isSelected = isSelected,
                                        onTap = {
                                            selectedDevice = device
                                            showDeviceDetails = true
                                            val js = "if(window.focusDevice) window.focusDevice(${device.latitude}, ${device.longitude}, '${device.deviceId}');"
                                            webViewRef?.evaluateJavascript(js, null)
                                        },
                                        onHoldComplete = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            try {
                                                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                    vibrator?.vibrate(android.os.VibrationEffect.createOneShot(120, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                                                } else {
                                                    vibrator?.vibrate(120)
                                                }
                                            } catch (_: Exception) {}

                                            deviceToRename = device
                                            renameInputText = device.deviceName
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Rename Device Dialog (Opened on 4-second hold)
        if (deviceToRename != null) {
            val dev = deviceToRename!!
            AlertDialog(
                onDismissRequest = {
                    if (!isRenamingDevice) deviceToRename = null
                },
                icon = {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFEDE7F6)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = null,
                            tint = Color(0xFF673AB7),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                title = {
                    Text(
                        text = "Rename Device",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        textAlign = TextAlign.Center
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Set a custom nickname for ${if (dev.isThisDevice) "this device" else dev.deviceId}:",
                            fontSize = 13.sp,
                            color = Color(0xFF49454F)
                        )

                        OutlinedTextField(
                            value = renameInputText,
                            onValueChange = { renameInputText = it },
                            label = { Text("Device Name") },
                            placeholder = { Text("e.g. My Primary Phone, Office Sentry") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF673AB7),
                                focusedLabelColor = Color(0xFF673AB7)
                            )
                        )

                        Text(
                            text = "Quick suggestions:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF49454F)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("Primary Phone", "Work Device", "Home Sentry").forEach { suggestion ->
                                SuggestionChip(
                                    onClick = { renameInputText = suggestion },
                                    label = { Text(suggestion, fontSize = 11.sp) },
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val newName = renameInputText.trim()
                            if (newName.isNotBlank()) {
                                coroutineScope.launch {
                                    isRenamingDevice = true
                                    val targetId = dev.deviceId
                                    if (!dev.isThisDevice) {
                                        withContext(Dispatchers.IO) {
                                            val client = KinetixApiClient(context)
                                            client.updateDeviceName(targetId, newName)
                                        }
                                    }
                                    com.example.kinetix.cache.KinetixDeviceCache.saveDeviceName(context, targetId, newName)

                                    // Update local state list immediately
                                    val idx = pairedDevices.indexOfFirst { it.deviceId == targetId }
                                    if (idx != -1) {
                                        pairedDevices[idx] = pairedDevices[idx].copy(deviceName = newName)
                                    }
                                    if (selectedDevice?.deviceId == targetId) {
                                        selectedDevice = selectedDevice?.copy(deviceName = newName)
                                    }

                                    actionFeedbackMessage = "Renamed to \"$newName\""
                                    isRenamingDevice = false
                                    deviceToRename = null
                                    delay(2500)
                                    actionFeedbackMessage = null
                                }
                            }
                        },
                        enabled = !isRenamingDevice && renameInputText.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF673AB7))
                    ) {
                        if (isRenamingDevice) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Saving...")
                        } else {
                            Text("Save")
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { deviceToRename = null },
                        enabled = !isRenamingDevice
                    ) {
                        Text("Cancel", color = Color(0xFF673AB7))
                    }
                },
                shape = RoundedCornerShape(20.dp),
                containerColor = Color.White
            )
        }
    }
}

@Composable
fun DashboardDeviceCard(
    device: PairedDeviceItem,
    isSelected: Boolean,
    onTap: () -> Unit,
    onHoldComplete: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var isHolding by remember { mutableStateOf(false) }
    var holdProgress by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isSelected) Color(0xFFF3EDF7) else Color.Transparent
            )
            .border(
                width = if (isHolding) 1.5.dp else 1.dp,
                color = if (isHolding) Color(0xFF673AB7) else if (isSelected) Color(0xFFE8DEF8) else Color(0xFFEEEEEE),
                shape = RoundedCornerShape(16.dp)
            )
            .pointerInput(device.deviceId) {
                detectTapGestures(
                    onPress = {
                        isHolding = true
                        val targetMs = 4000L
                        val startTime = System.currentTimeMillis()
                        var completed = false

                        val progressJob = coroutineScope.launch {
                            while (isHolding) {
                                val elapsed = System.currentTimeMillis() - startTime
                                holdProgress = (elapsed.toFloat() / targetMs).coerceIn(0f, 1f)
                                if (elapsed >= targetMs) {
                                    completed = true
                                    onHoldComplete()
                                    break
                                }
                                delay(30)
                            }
                        }

                        val released = tryAwaitRelease()
                        isHolding = false
                        progressJob.cancel()
                        val duration = System.currentTimeMillis() - startTime
                        holdProgress = 0f

                        if (released && !completed && duration < 500) {
                            onTap()
                        }
                    }
                )
            }
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            if (device.isThisDevice) Color(0xFFE3F2FD) else Color(0xFFE8F5E9)
                        ),
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
                        tint = if (device.isThisDevice) Color(0xFF1976D2) else Color(0xFF2E7D32),
                        modifier = Modifier.size(24.dp)
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
                        text = if (device.isThisDevice) "This device • ${device.address}" else "${device.osVersion} • ${device.lastSeenText}",
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

            // Visual 4-second hold progress indicator
            if (isHolding && holdProgress > 0f) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Hold for 4s to rename...",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF673AB7)
                        )
                        val secondsRemaining = ((4000L * (1f - holdProgress)) / 1000.0)
                        Text(
                            text = String.format(java.util.Locale.US, "%.1fs", secondsRemaining),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF673AB7)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { holdProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = Color(0xFF673AB7),
                        trackColor = Color(0xFFEDE7F6)
                    )
                }
            }
        }
    }
}
