package com.example.kinetix.ui.devicedetail

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    val context = androidx.compose.ui.platform.LocalContext.current
    var deviceName by remember { mutableStateOf("Sentry Device") }
    var osVersion by remember { mutableStateOf("Android 14") }
    var isOnline by remember { mutableStateOf(true) }
    var cameraEnabled by remember { mutableStateOf(false) }
    var locationEnabled by remember { mutableStateOf(false) }
    var notificationEnabled by remember { mutableStateOf(false) }
    var micEnabled by remember { mutableStateOf(false) }
    var filesEnabled by remember { mutableStateOf(true) }
    var batteryEnabled by remember { mutableStateOf(true) }

    LaunchedEffect(deviceId) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
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
                            osVersion = item.optString("osVersion", item.optString("os_version", "Android 14"))
                            isOnline = item.optString("status", "ONLINE") == "ONLINE"
                            val caps = item.optJSONObject("capabilities")
                            if (caps != null) {
                                cameraEnabled = caps.optBoolean("camera", false)
                                locationEnabled = caps.optBoolean("location", false)
                                notificationEnabled = caps.optBoolean("notifications", false)
                                micEnabled = caps.optBoolean("microphone", false)
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
                title = { Text("Device Details", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .verticalScroll(rememberScrollState())
        ) {
            // Device Identity Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = deviceName,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isOnline) Color(0xFF4CAF50).copy(alpha = 0.2f) else Color(0xFFE53935).copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = if (isOnline) "ONLINE" else "OFFLINE",
                                color = if (isOnline) Color(0xFF2E7D32) else Color(0xFFC62828),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = deviceId,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Platform: Android • OS: $osVersion • Sentry: 1.0.0",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Capabilities Checklist
            Text(
                text = "Capabilities & Permissions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    CapabilityRow(name = "Camera & Photos", isEnabled = cameraEnabled, icon = Icons.Default.CameraAlt)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    CapabilityRow(name = "Files & Storage", isEnabled = filesEnabled, icon = Icons.Default.Folder)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    CapabilityRow(name = "Live Notifications", isEnabled = notificationEnabled, icon = Icons.Default.Notifications)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    CapabilityRow(name = "GPS Location", isEnabled = locationEnabled, icon = Icons.Default.LocationOn)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    CapabilityRow(name = "Microphone Audio", isEnabled = micEnabled, icon = Icons.Default.Mic)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    CapabilityRow(name = "Battery & Health", isEnabled = batteryEnabled, icon = Icons.Default.BatteryFull)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Remote Access & Control Features
            Text(
                text = "Live Access & Remote Tools",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Grid of 6 interactive Feature Access Cards opening dedicated pages
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FeatureCard(
                    modifier = Modifier.weight(1f),
                    title = "Notifications",
                    subtitle = "Read SMS & Alerts",
                    icon = Icons.Default.NotificationsActive,
                    badgeColor = Color(0xFF1976D2),
                    onClick = onNavigateToNotifications
                )
                FeatureCard(
                    modifier = Modifier.weight(1f),
                    title = "Photos & Camera",
                    subtitle = "Capture & Gallery",
                    icon = Icons.Default.PhotoCamera,
                    badgeColor = Color(0xFF7B1FA2),
                    onClick = onNavigateToPhotos
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FeatureCard(
                    modifier = Modifier.weight(1f),
                    title = "File Explorer",
                    subtitle = "Browse Storage",
                    icon = Icons.Default.FolderOpen,
                    badgeColor = Color(0xFFF57C00),
                    onClick = onNavigateToFiles
                )
                FeatureCard(
                    modifier = Modifier.weight(1f),
                    title = "Live Location",
                    subtitle = "GPS Coordinates",
                    icon = Icons.Default.MyLocation,
                    badgeColor = Color(0xFF388E3C),
                    onClick = onNavigateToLocation
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FeatureCard(
                    modifier = Modifier.weight(1f),
                    title = "Battery & Stats",
                    subtitle = "Level & Health",
                    icon = Icons.Default.BatteryChargingFull,
                    badgeColor = Color(0xFF00796B),
                    onClick = onNavigateToBattery
                )
                FeatureCard(
                    modifier = Modifier.weight(1f),
                    title = "Mic & Audio",
                    subtitle = "Voice Memo",
                    icon = Icons.Default.MicNone,
                    badgeColor = Color(0xFFC2185B),
                    onClick = onNavigateToAudio
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun FeatureCard(
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
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
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun NotificationItemPreview(appName: String, message: String, time: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = appName, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(text = message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(text = time, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
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
            shape = RoundedCornerShape(6.dp),
            color = if (isEnabled) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
        ) {
            Text(
                text = if (isEnabled) "Enabled" else "Disabled",
                color = if (isEnabled) Color(0xFF2E7D32) else Color(0xFFC62828),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}
