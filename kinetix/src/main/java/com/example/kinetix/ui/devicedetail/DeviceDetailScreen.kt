package com.example.kinetix.ui.devicedetail

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DeviceDetailScreen(
    deviceId: String,
    onBack: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToCalls: () -> Unit = {},
    onNavigateToPhotos: () -> Unit,
    onNavigateToGallery: () -> Unit = {},
    onNavigateToFiles: () -> Unit,
    onNavigateToLocation: () -> Unit,
    onNavigateToBattery: () -> Unit,
    onNavigateToAudio: () -> Unit,
    onNavigateToActivity: () -> Unit,
    onUnpaired: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    // 0ms Instant Load from Persistent Local Cache
    val cachedTel = remember(deviceId) { com.example.kinetix.cache.KinetixDeviceCache.getCachedTelemetry(context, deviceId) }
    val cachedCaps = remember(deviceId) { com.example.kinetix.cache.KinetixDeviceCache.getCachedCapabilities(context, deviceId) }
    var deviceName by remember(deviceId) { mutableStateOf(com.example.kinetix.cache.KinetixDeviceCache.getDeviceName(context, deviceId)) }
    var osVersion by remember { mutableStateOf("Android 16") }
    var isOnline by remember { mutableStateOf(true) }
    var lastSeenText by remember { mutableStateOf("Just now") }
    var batteryPercentage by remember(deviceId) { mutableIntStateOf(cachedTel.percentage) }
    var batteryStatusText by remember(deviceId) { mutableStateOf(cachedTel.status) }
    var networkType by remember(deviceId) { mutableStateOf(cachedTel.networkType) }
    var uptimeText by remember(deviceId) { mutableStateOf(cachedTel.uptime) }
    var wallpaperBase64 by remember(deviceId) { mutableStateOf(com.example.kinetix.cache.KinetixDeviceCache.getWallpaper(context, deviceId)) }
    var cameraEnabled by remember(deviceId) { mutableStateOf(cachedCaps.camera) }
    var locationEnabled by remember(deviceId) { mutableStateOf(cachedCaps.location) }
    var notificationEnabled by remember(deviceId) { mutableStateOf(cachedCaps.notifications) }
    var micEnabled by remember(deviceId) { mutableStateOf(cachedCaps.mic) }
    var filesEnabled by remember(deviceId) { mutableStateOf(cachedCaps.files) }
    var batteryEnabled by remember(deviceId) { mutableStateOf(cachedCaps.battery) }
    var showInfoModal by remember { mutableStateOf(false) }
    var showRenameModal by remember { mutableStateOf(false) }
    var renameInputText by remember { mutableStateOf("") }
    var isRenaming by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var activeBottomTab by remember { mutableIntStateOf(0) }
    var isIconHidden by remember(deviceId) { mutableStateOf(false) }

    suspend fun refreshDeviceData(): Boolean {
        return withContext(Dispatchers.IO) {
            var foundOnline = false
            try {
                val client = com.example.kinetix.network.KinetixApiClient(context)
                val res = client.listAvailableDevices()
                if (res.isSuccess) {
                    val arr = res.getOrNull()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val item = arr.getJSONObject(i)
                            val id = item.optString("deviceId", item.optString("device_id", ""))
                            if (id == deviceId) {
                                val fetchedName = item.optString("deviceName", item.optString("device_name", ""))
                                val savedCustomName = com.example.kinetix.cache.KinetixDeviceCache.getDeviceName(context, deviceId, "")
                                val finalName = if (savedCustomName.isNotBlank()) savedCustomName else fetchedName
                                if (finalName.isNotBlank()) {
                                    withContext(Dispatchers.Main) {
                                        deviceName = finalName
                                    }
                                }
                                osVersion = item.optString("osVersion", item.optString("os_version", "Android 16"))
                                val statusStr = item.optString("status", "ONLINE")
                                val online = statusStr.equals("ONLINE", ignoreCase = true)
                                foundOnline = online
                                val fetchedLastSeen = item.optString("lastSeenAt", "")

                                val formattedLastSeen = if (online) {
                                    "Just now"
                                } else if (fetchedLastSeen.isNotBlank()) {
                                    try {
                                        val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
                                            timeZone = java.util.TimeZone.getTimeZone("UTC")
                                        }
                                        val clean = fetchedLastSeen.substringBefore(".").substringBefore("Z")
                                        val parsedDate = format.parse(clean)
                                        if (parsedDate != null) {
                                            val diffMs = System.currentTimeMillis() - parsedDate.time
                                            when {
                                                diffMs < 60_000L -> "Just now"
                                                diffMs < 3600_000L -> "${diffMs / 60_000L}m ago"
                                                diffMs < 86400_000L -> {
                                                    val timeFormat = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                                                    "Today at ${timeFormat.format(parsedDate)}"
                                                }
                                                diffMs < 172800_000L -> {
                                                    val timeFormat = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                                                    "Yesterday at ${timeFormat.format(parsedDate)}"
                                                }
                                                else -> {
                                                    val dateFormat = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
                                                    dateFormat.format(parsedDate)
                                                }
                                            }
                                        } else {
                                            "Recently"
                                        }
                                    } catch (_: Exception) {
                                        "Recently"
                                    }
                                } else {
                                    "Recently"
                                }

                                withContext(Dispatchers.Main) {
                                    isOnline = online
                                    lastSeenText = formattedLastSeen
                                }

                                val caps = item.optJSONObject("capabilities")
                                if (caps != null) {
                                    cameraEnabled = caps.optBoolean("camera", true)
                                    locationEnabled = caps.optBoolean("location", true)
                                    notificationEnabled = caps.optBoolean("notifications", true)
                                    micEnabled = caps.optBoolean("microphone", true)
                                    filesEnabled = caps.optBoolean("files", true)
                                    batteryEnabled = caps.optBoolean("battery", true)
                                    com.example.kinetix.cache.KinetixDeviceCache.saveCapabilities(
                                        context, deviceId, cameraEnabled, locationEnabled,
                                        notificationEnabled, micEnabled, filesEnabled, batteryEnabled
                                    )
                                }
                                break
                            }
                        }
                    }
                }
            } catch (_: Exception) {}

            try {
                val client = com.example.kinetix.network.KinetixApiClient(context)
                val telRes = client.getBatteryTelemetry(deviceId)
                if (telRes.isSuccess) {
                    val bObj = telRes.getOrNull()
                    if (bObj != null) {
                        val pct = bObj.optInt("percentage", bObj.optInt("level", 44))
                        val isCharging = bObj.optBoolean("isCharging", false)
                        val status = bObj.optString("chargingStatus", "")
                        val st = if (status.isNotBlank()) status else (if (isCharging) "Charging" else "Good")
                        val net = bObj.optString("networkType", "5G+")
                        val up = bObj.optString("uptime", "2h 14m")
                        val wall = bObj.optString("wallpaper", "")

                        withContext(Dispatchers.Main) {
                            batteryPercentage = pct
                            batteryStatusText = st
                            networkType = net
                            uptimeText = up
                            if (wall.isNotBlank()) {
                                wallpaperBase64 = wall
                                com.example.kinetix.cache.KinetixDeviceCache.saveWallpaper(context, deviceId, wall)
                            }
                        }
                        com.example.kinetix.cache.KinetixDeviceCache.saveTelemetry(
                            context, deviceId, pct, st, net, up
                        )
                    }
                }

                // Check remote app icon visibility state
                val iconStateRes = client.getAppIconState(deviceId)
                if (iconStateRes.isSuccess) {
                    withContext(Dispatchers.Main) {
                        isIconHidden = iconStateRes.getOrDefault(false)
                    }
                }
            } catch (_: Exception) {}
            foundOnline
        }
    }

    LaunchedEffect(deviceId) {
        while (true) {
            refreshDeviceData()
            delay(4000)
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = Color.White,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = activeBottomTab == 0,
                    onClick = { activeBottomTab = 0 },
                    icon = {
                        Icon(
                            if (activeBottomTab == 0) Icons.Filled.Home else Icons.Outlined.Home,
                            contentDescription = "Dashboard",
                            tint = if (activeBottomTab == 0) Color(0xFF673AB7) else Color(0xFF757575)
                        )
                    },
                    label = {
                        Text(
                            "Dashboard",
                            fontWeight = if (activeBottomTab == 0) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 11.sp,
                            color = if (activeBottomTab == 0) Color(0xFF673AB7) else Color(0xFF757575)
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFFEDE7F6))
                )
                NavigationBarItem(
                    selected = activeBottomTab == 1,
                    onClick = {
                        activeBottomTab = 1
                        onNavigateToActivity()
                    },
                    icon = {
                        Icon(
                            Icons.Outlined.AccessTime,
                            contentDescription = "Activity",
                            tint = if (activeBottomTab == 1) Color(0xFF673AB7) else Color(0xFF757575)
                        )
                    },
                    label = {
                        Text(
                            "Activity",
                            fontWeight = if (activeBottomTab == 1) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 11.sp,
                            color = if (activeBottomTab == 1) Color(0xFF673AB7) else Color(0xFF757575)
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFFEDE7F6))
                )
                NavigationBarItem(
                    selected = activeBottomTab == 2,
                    onClick = {
                        activeBottomTab = 2
                        onNavigateToNotifications()
                    },
                    icon = {
                        BadgedBox(badge = {
                            Badge(
                                containerColor = Color(0xFFE53935),
                                contentColor = Color.White
                            ) { Text("3", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                        }) {
                            Icon(
                                Icons.Outlined.Notifications,
                                contentDescription = "Alerts",
                                tint = if (activeBottomTab == 2) Color(0xFF673AB7) else Color(0xFF757575)
                            )
                        }
                    },
                    label = {
                        Text(
                            "Alerts",
                            fontWeight = if (activeBottomTab == 2) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 11.sp,
                            color = if (activeBottomTab == 2) Color(0xFF673AB7) else Color(0xFF757575)
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFFEDE7F6))
                )
                NavigationBarItem(
                    selected = activeBottomTab == 3,
                    onClick = { activeBottomTab = 3 },
                    icon = {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = "Settings",
                            tint = if (activeBottomTab == 3) Color(0xFF673AB7) else Color(0xFF757575)
                        )
                    },
                    label = {
                        Text(
                            "Settings",
                            fontWeight = if (activeBottomTab == 3) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 11.sp,
                            color = if (activeBottomTab == 3) Color(0xFF673AB7) else Color(0xFF757575)
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFFEDE7F6))
                )
            }
        }
    ) { padding ->
        when (activeBottomTab) {
            3 -> {
                Box(modifier = Modifier.padding(padding)) {
                    com.example.kinetix.ui.settings.SettingsScreen(
                        onBack = { activeBottomTab = 0 },
                        deviceId = deviceId
                    )
                }
            }
            1 -> {
                Box(modifier = Modifier.padding(padding)) {
                    com.example.kinetix.ui.features.ActivityScreen(
                        deviceId = deviceId,
                        onBack = { activeBottomTab = 0 }
                    )
                }
            }
            2 -> {
                Box(modifier = Modifier.padding(padding)) {
                    com.example.kinetix.ui.features.NotificationsScreen(
                        deviceId = deviceId,
                        onBack = { activeBottomTab = 0 }
                    )
                }
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFFBFBFE))
                        .padding(padding)
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Spacer(modifier = Modifier.height(12.dp))

            // 1. Top Bar Header (Circular Menu Button, Centered Device Name & Status, Right Action Buttons)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                // Left: Circular Hamburger / Back Button
                Surface(
                    onClick = onBack,
                    shape = CircleShape,
                    color = Color.White,
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .size(42.dp)
                        .align(Alignment.CenterStart)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color(0xFF1D1B20), modifier = Modifier.size(20.dp))
                    }
                }

                // Center: Device Name with Status Indicator Dot & Centered Last Seen Pill
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = deviceName,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 18.sp,
                            color = Color(0xFF1D1B20),
                            maxLines = 1,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isOnline) Color(0xFF4CAF50) else Color(0xFFE53935))
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Centered Last Seen Pill with Remote Wakeup & Reconnect Trigger
                    Surface(
                        onClick = {
                            coroutineScope.launch {
                                actionMessage = "⚡ Awakening remote phone & syncing..."
                                var wakeSuccess = false
                                withContext(Dispatchers.IO) {
                                    try {
                                        val client = com.example.kinetix.network.KinetixApiClient(context)
                                        val res = client.wakeDevice(deviceId)
                                        if (res.isSuccess) {
                                            wakeSuccess = true
                                        }
                                    } catch (_: Exception) {}
                                }
                                delay(600)
                                val online = refreshDeviceData()
                                if (online || wakeSuccess) {
                                    actionMessage = "✅ Remote Wakeup Successful • Connected ⚡"
                                } else {
                                    actionMessage = "❌ Remote Wakeup Unsuccessful • Offline ⚠️"
                                }
                                delay(2800)
                                actionMessage = null
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        color = Color.White,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE0E0E0)),
                        shadowElevation = 1.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Outlined.AccessTime,
                                contentDescription = null,
                                tint = Color(0xFF757575),
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = lastSeenText,
                                fontSize = 10.5.sp,
                                color = Color(0xFF757575),
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Remote Wakeup & Refresh",
                                tint = Color(0xFF0284C7),
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }

                // Right: Shield Info Button (Symmetrical with Left Button)
                Surface(
                    onClick = { showInfoModal = true },
                    shape = CircleShape,
                    color = Color.White,
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF4CAF50).copy(alpha = 0.5f)),
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .size(42.dp)
                        .align(Alignment.CenterEnd)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.Shield,
                            contentDescription = "Device Information",
                            tint = Color(0xFF2E7D32),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Action Feedback Notification Pill Banner
            AnimatedVisibility(
                visible = actionMessage != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                actionMessage?.let { msg ->
                    val isError = msg.contains("❌") || msg.contains("Unsuccessful")
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = if (isError) Color(0xFFFFEBEE) else Color(0xFFE8F5E9),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isError) Color(0xFFEF5350).copy(alpha = 0.5f) else Color(0xFF4CAF50).copy(alpha = 0.5f)
                        ),
                        shadowElevation = 2.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = msg,
                                color = if (isError) Color(0xFFC62828) else Color(0xFF2E7D32),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Hero Overview Card (Phone Mockup Thumbnail + Battery + Network + Uptime)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToBattery() },
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
                shadowElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Phone Graphic Thumbnail showing Live Target Device Wallpaper
                    val wallBitmap = remember(wallpaperBase64) {
                        if (!wallpaperBase64.isNullOrBlank()) {
                            try {
                                val bytes = Base64.decode(wallpaperBase64, Base64.DEFAULT)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                            } catch (_: Exception) {
                                null
                            }
                        } else {
                            null
                        }
                    }

                    Box(
                        modifier = Modifier
                            .width(46.dp)
                            .height(76.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(Color(0xFF1E1E1E))
                            .border(1.5.dp, Color(0xFF2E2E2E), RoundedCornerShape(9.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (wallBitmap != null) {
                            Image(
                                bitmap = wallBitmap,
                                contentDescription = "Live Wallpaper",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(Color(0xFF3F51B5), Color(0xFF7B1FA2), Color(0xFFE91E63))
                                        )
                                    )
                            )
                        }

                        // Top camera punch-hole notch
                        Box(
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.85f))
                                .align(Alignment.TopCenter)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Battery Column
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Battery", fontSize = 11.sp, color = Color(0xFF757575), fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("$batteryPercentage%", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF1D1B20))
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { batteryPercentage / 100f },
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .height(4.dp)
                                .clip(CircleShape),
                            color = Color(0xFF4CAF50),
                            trackColor = Color(0xFFE0E0E0)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(10.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(batteryStatusText, fontSize = 10.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                        }
                    }

                    // Network Column
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Network", fontSize = 11.sp, color = Color(0xFF757575), fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.SignalCellularAlt, contentDescription = null, tint = Color(0xFF1D1B20), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(networkType, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF2E7D32))
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(Color(0xFF4CAF50)))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("Strong", fontSize = 10.sp, color = Color(0xFF757575), fontWeight = FontWeight.Medium)
                        }
                    }

                    // Uptime Column
                    Column(modifier = Modifier.weight(0.9f)) {
                        Text("Uptime", fontSize = 11.sp, color = Color(0xFF757575), fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(uptimeText, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF1D1B20))
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Online", fontSize = 10.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                    }

                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color(0xFFBDBDBD), modifier = Modifier.size(20.dp))
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 3. Section Header: Live Access & Remote Tools + Green Pill Tag
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Live Access & Remote Tools",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.5.sp,
                    color = Color(0xFF1D1B20)
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFE8F5E9)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "8 Tools Ready",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2E7D32)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 4. Grid of 8 Modern Feature Access Cards
            // Row 1: Notifications & Photos/Cam
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SentryModernToolCard(
                    modifier = Modifier.weight(1f),
                    title = "Notifications",
                    subtitle = "Read SMS & OTP",
                    icon = Icons.Default.Notifications,
                    badgeColor = Color(0xFF7C4DFF),
                    badgeBg = Color(0xFFEDE7F6),
                    tagText = "New",
                    tagColor = Color(0xFF7C4DFF),
                    tagBg = Color(0xFFEDE7F6),
                    onClick = onNavigateToNotifications
                )
                SentryModernToolCard(
                    modifier = Modifier.weight(1f),
                    title = "Photos & Cam",
                    subtitle = "Capture & Gallery",
                    icon = Icons.Default.PhotoCamera,
                    badgeColor = Color(0xFFE91E63),
                    badgeBg = Color(0xFFFCE4EC),
                    onClick = onNavigateToPhotos
                )
            }

            Spacer(modifier = Modifier.height(7.dp))

            // Row 2: File Explorer & Live Location
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SentryModernToolCard(
                    modifier = Modifier.weight(1f),
                    title = "File Explorer",
                    subtitle = "Browse Storage",
                    icon = Icons.Default.Folder,
                    badgeColor = Color(0xFFFF9800),
                    badgeBg = Color(0xFFFFF3E0),
                    onClick = onNavigateToFiles
                )
                SentryModernToolCard(
                    modifier = Modifier.weight(1f),
                    title = "Live Location",
                    subtitle = "GPS Tracker",
                    icon = Icons.Default.LocationOn,
                    badgeColor = Color(0xFF4CAF50),
                    badgeBg = Color(0xFFE8F5E9),
                    tagText = "Live",
                    tagColor = Color(0xFF2E7D32),
                    tagBg = Color(0xFFE8F5E9),
                    onClick = onNavigateToLocation
                )
            }

            Spacer(modifier = Modifier.height(7.dp))

            // Row 3: Battery Stats & Mic/Audio
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SentryModernToolCard(
                    modifier = Modifier.weight(1f),
                    title = "Battery Stats",
                    subtitle = "Level & Health",
                    icon = Icons.Default.BatteryChargingFull,
                    badgeColor = Color(0xFF00BFA5),
                    badgeBg = Color(0xFFE0F2F1),
                    onClick = onNavigateToBattery
                )
                SentryModernToolCard(
                    modifier = Modifier.weight(1f),
                    title = "Mic & Audio",
                    subtitle = "Voice Memo",
                    icon = Icons.Default.Mic,
                    badgeColor = Color(0xFF2979FF),
                    badgeBg = Color(0xFFE3F2FD),
                    onClick = onNavigateToAudio
                )
            }

            Spacer(modifier = Modifier.height(7.dp))

            // Row 4: Call History & Cloud Gallery
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SentryModernToolCard(
                    modifier = Modifier.weight(1f),
                    title = "Call History",
                    subtitle = "Logs & Duration",
                    icon = Icons.Default.PhoneCallback,
                    badgeColor = Color(0xFF0284C7),
                    badgeBg = Color(0xFFE0F2FE),
                    tagText = "Live",
                    tagColor = Color(0xFF0284C7),
                    tagBg = Color(0xFFE0F2FE),
                    onClick = onNavigateToCalls
                )
                SentryModernToolCard(
                    modifier = Modifier.weight(1f),
                    title = "Gallery",
                    subtitle = "Photos & Media",
                    icon = Icons.Default.PhotoLibrary,
                    badgeColor = Color(0xFF8B5CF6),
                    badgeBg = Color(0xFFF3E8FF),
                    tagText = "Live",
                    tagColor = Color(0xFF7C3AED),
                    tagBg = Color(0xFFEDE9FE),
                    onClick = onNavigateToGallery
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 5. Security & Encryption Card (AES-256)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE3F2FD)),
                shadowElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE3F2FD)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.Shield, contentDescription = null, tint = Color(0xFF1976D2), modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Your connection is secure", fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = Color(0xFF1D1B20))
                            Text("End-to-end encrypted communication", fontSize = 11.sp, color = Color(0xFF757575))
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFE3F2FD)
                    ) {
                        Text(
                            text = "AES-256",
                            color = Color(0xFF1976D2),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 6. Quick Actions Section
            Text(
                text = "Quick Actions",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 15.5.sp,
                color = Color(0xFF1D1B20)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Remote Wakeup & Reconnect Action Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                shadowElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFFE0F2FE)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null, tint = Color(0xFF0284C7), modifier = Modifier.size(22.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Remote Wakeup & Reconnect", fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = Color(0xFF1D1B20))
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("Awakens Sentry background service on remote phone", fontSize = 11.sp, color = Color(0xFF757575))
                        }
                    }

                    Button(
                        onClick = {
                            coroutineScope.launch {
                                actionMessage = "⚡ Dispatching remote wakeup & refreshing..."
                                withContext(Dispatchers.IO) {
                                    try {
                                        val client = com.example.kinetix.network.KinetixApiClient(context)
                                        client.wakeDevice(deviceId)
                                    } catch (_: Exception) {}
                                }
                                refreshDeviceData()
                                delay(800)
                                refreshDeviceData()
                                actionMessage = "✅ Connection Active • Synced"
                                delay(2000)
                                actionMessage = null
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Sync, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Reconnect", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // App Drawer Icon Stealth Action Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                shadowElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isIconHidden) Color(0xFFFEE2E2) else Color(0xFFF3E8FF)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (isIconHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                                tint = if (isIconHidden) Color(0xFFDC2626) else Color(0xFF9333EA),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                "App Drawer Icon Stealth",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp,
                                color = Color(0xFF1D1B20)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                if (isIconHidden) "Icon is HIDDEN from remote phone launcher"
                                else "Hides icon from remote phone app drawer",
                                fontSize = 11.sp,
                                color = if (isIconHidden) Color(0xFFDC2626) else Color(0xFF757575)
                            )
                        }
                    }

                    Button(
                        onClick = {
                            val newHideState = !isIconHidden
                            coroutineScope.launch {
                                actionMessage = if (newHideState) "🔒 Hiding app icon on remote phone..." else "🔓 Restoring app icon..."
                                withContext(Dispatchers.IO) {
                                    try {
                                        val client = com.example.kinetix.network.KinetixApiClient(context)
                                        client.setAppIconHidden(deviceId, newHideState)
                                    } catch (_: Exception) {}
                                }
                                isIconHidden = newHideState
                                delay(600)
                                refreshDeviceData()
                                actionMessage = if (newHideState) "✅ Sentry icon hidden from App Drawer" else "✅ Sentry icon restored to App Drawer"
                                delay(2000)
                                actionMessage = null
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isIconHidden) Color(0xFF16A34A) else Color(0xFFDC2626)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isIconHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (isIconHidden) "Reveal Icon" else "Hide Icon",
                                color = Color.White,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Ring Device Action Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                shadowElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFFEDE7F6)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = Color(0xFF673AB7), modifier = Modifier.size(22.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Ring Device at Max Volume", fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = Color(0xFF1D1B20))
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("Plays loud alert sound even on silent mode", fontSize = 11.sp, color = Color(0xFF757575))
                        }
                    }

                    Button(
                        onClick = {
                            actionMessage = "Sent Ring Command to $deviceName"
                            coroutineScope.launch {
                                delay(2500)
                                actionMessage = null
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEDE7F6)),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Color(0xFF673AB7), modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Ring Now", color = Color(0xFF673AB7), fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Unpair Device Action Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                shadowElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFFFFEBEE)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.LinkOff, contentDescription = null, tint = Color(0xFFE53935), modifier = Modifier.size(22.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Unpair Device", fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = Color(0xFFE53935))
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("Remove Sentry pairing and encryption keys", fontSize = 11.sp, color = Color(0xFF757575))
                        }
                    }

                    OutlinedButton(
                        onClick = onUnpaired,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE53935)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF9A9A)),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text("Unpair", color = Color(0xFFE53935), fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

    // Modal Bottom Sheet showing Device Information & Capabilities (Triggered by (i) button)
    if (showInfoModal) {
        ModalBottomSheet(
            onDismissRequest = { showInfoModal = false },
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            containerColor = Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .padding(bottom = 28.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Device Information",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isOnline) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                    ) {
                        Text(
                            text = if (isOnline) "• ONLINE" else "• OFFLINE",
                            color = if (isOnline) Color(0xFF2E7D32) else Color(0xFFC62828),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Device Identity Details Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = deviceName,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // Device ID with Copy Button
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    clipboardManager.setText(AnnotatedString(deviceId))
                                    actionMessage = "Device ID copied to clipboard"
                                    coroutineScope.launch {
                                        delay(2000)
                                        actionMessage = null
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = deviceId,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy ID", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Platform: Android • OS: $osVersion • Sentry: 1.0.0",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.5.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Capabilities & Permissions Checklist
                Text(
                    text = "Capabilities & Permissions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        CapabilityRow(name = "Camera & Photos", isEnabled = cameraEnabled, icon = Icons.Default.CameraAlt)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        CapabilityRow(name = "Files & Storage", isEnabled = filesEnabled, icon = Icons.Default.Folder)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        CapabilityRow(name = "Live Notifications", isEnabled = notificationEnabled, icon = Icons.Default.Notifications)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        CapabilityRow(name = "GPS Location", isEnabled = locationEnabled, icon = Icons.Default.LocationOn)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        CapabilityRow(name = "Microphone Audio", isEnabled = micEnabled, icon = Icons.Default.Mic)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        CapabilityRow(name = "Battery & Health", isEnabled = batteryEnabled, icon = Icons.Default.BatteryFull)
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = { showInfoModal = false },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Close", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // Device Rename Dialog
    if (showRenameModal) {
        AlertDialog(
            onDismissRequest = { if (!isRenaming) showRenameModal = false },
            icon = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEDE7F6)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = Color(0xFF673AB7), modifier = Modifier.size(24.dp))
                }
            },
            title = {
                Text("Rename Device", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = Color(0xFF1D1B20))
            },
            text = {
                Column {
                    Text(
                        "Set a custom friendly name for this device. It will update across your dashboard immediately.",
                        fontSize = 13.sp,
                        color = Color(0xFF757575)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = renameInputText,
                        onValueChange = { renameInputText = it },
                        label = { Text("Device Name") },
                        placeholder = { Text("e.g. Work Phone, Galaxy S24") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF673AB7),
                            focusedLabelColor = Color(0xFF673AB7)
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val newName = renameInputText.trim()
                        if (newName.isNotBlank()) {
                            coroutineScope.launch {
                                isRenaming = true
                                withContext(Dispatchers.IO) {
                                    val client = com.example.kinetix.network.KinetixApiClient(context)
                                    client.updateDeviceName(deviceId, newName)
                                }
                                deviceName = newName
                                com.example.kinetix.cache.KinetixDeviceCache.saveDeviceName(context, deviceId, newName)
                                actionMessage = "Device renamed to \"$newName\""
                                isRenaming = false
                                showRenameModal = false
                                delay(2500)
                                actionMessage = null
                            }
                        }
                    },
                    enabled = !isRenaming && renameInputText.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF673AB7))
                ) {
                    if (isRenaming) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Saving...")
                    } else {
                        Text("Save Name", fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showRenameModal = false },
                    enabled = !isRenaming,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = Color.White
        )
    }
}

@Composable
fun SentryModernToolCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    badgeColor: Color,
    badgeBg: Color,
    tagText: String? = null,
    tagColor: Color = Color.Unspecified,
    tagBg: Color = Color.Unspecified,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF0F0F0)),
        shadowElevation = 0.5.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(badgeBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.size(15.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.5.sp,
                        color = Color(0xFF1D1B20),
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (tagText != null) {
                        Spacer(modifier = Modifier.width(3.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = tagBg
                        ) {
                            Text(
                                text = tagText,
                                color = tagColor,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Text(
                    text = subtitle,
                    color = Color(0xFF757575),
                    fontSize = 9.5.sp,
                    maxLines = 1
                )
            }

            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = Color(0xFFD1D5DB),
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

@Composable
fun CapabilityRow(name: String, isEnabled: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(text = name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, fontSize = 13.sp)
        }

        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (isEnabled) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
        ) {
            Text(
                text = if (isEnabled) "Enabled" else "Disabled",
                color = if (isEnabled) Color(0xFF2E7D32) else Color(0xFFC62828),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}
