package com.example.kinetix.ui.devicedetail

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(
    deviceId: String,
    onBack: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToPhotos: () -> Unit,
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
    var deviceName by remember { mutableStateOf("realme RMX5101 (Sentry)") }
    var osVersion by remember { mutableStateOf("Android 16") }
    var isOnline by remember { mutableStateOf(true) }
    var batteryPercentage by remember { mutableIntStateOf(44) }
    var batteryStatusText by remember { mutableStateOf("Good") }
    var networkType by remember { mutableStateOf("5G+") }
    var uptimeText by remember { mutableStateOf("2h 14m") }
    var wallpaperBase64 by remember { mutableStateOf<String?>(null) }
    var cameraEnabled by remember { mutableStateOf(true) }
    var locationEnabled by remember { mutableStateOf(true) }
    var notificationEnabled by remember { mutableStateOf(true) }
    var micEnabled by remember { mutableStateOf(true) }
    var filesEnabled by remember { mutableStateOf(true) }
    var batteryEnabled by remember { mutableStateOf(true) }
    var showInfoModal by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var activeBottomTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(deviceId) {
        while (true) {
            withContext(Dispatchers.IO) {
                val client = com.example.kinetix.network.KinetixApiClient(context)
                val res = client.listAvailableDevices()
                if (res.isSuccess) {
                    val arr = res.getOrNull()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val item = arr.getJSONObject(i)
                            val id = item.optString("deviceId", item.optString("device_id", ""))
                            if (id == deviceId) {
                                deviceName = item.optString("deviceName", item.optString("device_name", "realme RMX5101 (Sentry)"))
                                osVersion = item.optString("osVersion", item.optString("os_version", "Android 16"))
                                isOnline = item.optString("status", "ONLINE") == "ONLINE"
                                val caps = item.optJSONObject("capabilities")
                                if (caps != null) {
                                    cameraEnabled = caps.optBoolean("camera", true)
                                    locationEnabled = caps.optBoolean("location", true)
                                    notificationEnabled = caps.optBoolean("notifications", true)
                                    micEnabled = caps.optBoolean("microphone", true)
                                    filesEnabled = caps.optBoolean("files", true)
                                    batteryEnabled = caps.optBoolean("battery", true)
                                }
                                break
                            }
                        }
                    }
                }

                // Fetch live battery & hardware telemetry (percentage, network, uptime, wallpaper)
                val battRes = client.getBatteryTelemetry(deviceId)
                if (battRes.isSuccess) {
                    val bObj = battRes.getOrNull()
                    if (bObj != null) {
                        batteryPercentage = bObj.optInt("percentage", bObj.optInt("level", 44))
                        val isCharging = bObj.optBoolean("isCharging", false)
                        val status = bObj.optString("chargingStatus", "")
                        batteryStatusText = if (status.isNotBlank()) status else (if (isCharging) "Charging" else "Good")
                        networkType = bObj.optString("networkType", "5G+")
                        uptimeText = bObj.optString("uptime", "2h 14m")
                        val wall = bObj.optString("wallpaper", "")
                        if (wall.isNotBlank()) {
                            wallpaperBase64 = wall
                        }
                    }
                }
            }
            delay(2500)
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFFBFBFE))
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // 1. Top Bar Header (Circular Menu Button, Device Name, Online Status, Last Seen Pill, Shield Button)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Circular Hamburger / Back Button
                Surface(
                    onClick = onBack,
                    shape = CircleShape,
                    color = Color.White,
                    shadowElevation = 2.dp,
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color(0xFF1D1B20), modifier = Modifier.size(20.dp))
                    }
                }

                // Device Name & Status Column
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                ) {
                    Text(
                        text = deviceName,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 17.sp,
                        color = Color(0xFF1D1B20),
                        maxLines = 1
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isOnline) Color(0xFF4CAF50) else Color(0xFFE53935))
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isOnline) "Connected • Online" else "Offline",
                            fontSize = 11.5.sp,
                            color = if (isOnline) Color(0xFF2E7D32) else Color(0xFFC62828),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Last Seen Pill
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE0E0E0))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.AccessTime, contentDescription = null, tint = Color(0xFF757575), modifier = Modifier.size(11.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Last seen: Just now", fontSize = 10.sp, color = Color(0xFF757575), fontWeight = FontWeight.Medium)
                        }
                    }
                }

                // Circular Green Shield Security / Info Button
                Surface(
                    onClick = { showInfoModal = true },
                    shape = CircleShape,
                    color = Color.White,
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF4CAF50).copy(alpha = 0.5f)),
                    shadowElevation = 2.dp,
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.Shield,
                            contentDescription = "Device Information",
                            tint = Color(0xFF2E7D32),
                            modifier = Modifier.size(22.dp)
                        )
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
                            text = "6 Tools Ready",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2E7D32)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 4. Grid of 6 Modern Feature Access Cards
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

            Spacer(modifier = Modifier.height(10.dp))

            // Row 2: File Explorer & Live Location
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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

            Spacer(modifier = Modifier.height(10.dp))

            // Row 3: Battery Stats & Mic/Audio
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF0F0F0)),
        shadowElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(13.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(badgeBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier.size(20.dp)
                    )
                }

                if (tagText != null) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = tagBg
                    ) {
                        Text(
                            text = tagText,
                            color = tagColor,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                } else {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = Color(0xFFBDBDBD),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 13.5.sp,
                color = Color(0xFF1D1B20),
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = Color(0xFF757575),
                fontSize = 11.sp,
                maxLines = 1
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
