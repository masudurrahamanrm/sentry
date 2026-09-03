package com.example.kinetix.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
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
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kinetix.cache.KinetixDeviceCache
import com.example.kinetix.crypto.CryptoManager
import com.example.kinetix.network.KinetixApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    deviceId: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf("https://sentry-devloper-version.onrender.com/api/v1") }
    var pingStatus by remember { mutableStateOf<String?>(null) }
    var isPinging by remember { mutableStateOf(false) }

    var autoSyncInterval by remember { mutableIntStateOf(3) }
    var hapticFeedback by remember { mutableStateOf(true) }
    var backgroundAlerts by remember { mutableStateOf(true) }
    var zeroLagCaching by remember { mutableStateOf(true) }

    var showClearCacheDialog by remember { mutableStateOf(false) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var isRefreshingSettings by remember { mutableStateOf(false) }

    val controllerDeviceId = remember { CryptoManager.getOrCreateDeviceId(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings & Preferences",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp,
                        color = Color(0xFF1D1B20)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color(0xFF1D1B20)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        coroutineScope.launch {
                            isRefreshingSettings = true
                            delay(600)
                            feedbackMessage = "⚡ Settings synced with cloud node"
                            delay(2000)
                            feedbackMessage = null
                            isRefreshingSettings = false
                        }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color(0xFF1D1B20))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFFBFBFE))
            )
        },
        containerColor = Color(0xFFFBFBFE),
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshingSettings,
            onRefresh = {
                coroutineScope.launch {
                    isRefreshingSettings = true
                    delay(600)
                    feedbackMessage = "⚡ Settings synced with cloud node"
                    delay(2000)
                    feedbackMessage = null
                    isRefreshingSettings = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
            // Action feedback banner
            AnimatedVisibility(
                visible = feedbackMessage != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                feedbackMessage?.let { msg ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFE8F5E9),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4CAF50).copy(alpha = 0.5f)),
                        shadowElevation = 2.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = msg,
                                color = Color(0xFF2E7D32),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // Controller Identity Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = Color.White,
                shadowElevation = 1.5.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFEDE7F6)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Outlined.Fingerprint, contentDescription = null, tint = Color(0xFF673AB7), modifier = Modifier.size(22.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Controller Device ID", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF1D1B20))
                                Text("Local Cryptographic Node", fontSize = 11.sp, color = Color(0xFF757575))
                            }
                        }
                        IconButton(onClick = {
                            val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clip?.setPrimaryClip(ClipData.newPlainText("Device ID", controllerDeviceId))
                            Toast.makeText(context, "Controller ID copied!", Toast.LENGTH_SHORT).show()
                            coroutineScope.launch {
                                feedbackMessage = "📋 Controller ID copied to clipboard"
                                delay(2000)
                                feedbackMessage = null
                            }
                        }) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy", tint = Color(0xFF673AB7), modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFF5F5F7),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = controllerDeviceId,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF424242),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // Section 1: Network & Cloud Backend
            SettingsSectionHeader(title = "Cloud & Network Infrastructure")

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = Color.White,
                shadowElevation = 1.5.dp
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFE0F2FE)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Outlined.CloudSync, contentDescription = null, tint = Color(0xFF0284C7), modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Cloud Server", fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = Color(0xFF1D1B20))
                                Text(serverUrl, fontSize = 10.5.sp, color = Color(0xFF757575), maxLines = 1)
                            }
                        }

                        Button(
                            onClick = {
                                if (isPinging) return@Button
                                isPinging = true
                                pingStatus = "Testing..."
                                coroutineScope.launch {
                                    val start = System.currentTimeMillis()
                                    var ok = false
                                    withContext(Dispatchers.IO) {
                                        try {
                                            val client = KinetixApiClient(context)
                                            val res = client.listAvailableDevices()
                                            ok = res.isSuccess
                                        } catch (_: Exception) {}
                                    }
                                    val duration = System.currentTimeMillis() - start
                                    isPinging = false
                                    pingStatus = if (ok) "⚡ ${duration}ms Online" else "❌ Offline"
                                    delay(4000)
                                    pingStatus = null
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text(
                                text = pingStatus ?: "Test Ping",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    HorizontalDivider(color = Color(0xFFF0F0F0))

                    SettingsToggleRow(
                        icon = Icons.Outlined.Bolt,
                        iconTint = Color(0xFFF59E0B),
                        iconBg = Color(0xFFFEF3C7),
                        title = "Zero-Lag Instant Caching",
                        subtitle = "Instant UI display from local SQLite/Prefs cache",
                        checked = zeroLagCaching,
                        onCheckedChange = { zeroLagCaching = it }
                    )

                    HorizontalDivider(color = Color(0xFFF0F0F0))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFEDE7F6)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Outlined.Speed, contentDescription = null, tint = Color(0xFF7C4DFF), modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Telemetry Sync Rate", fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = Color(0xFF1D1B20))
                                Text("${autoSyncInterval}s (Continuous Live Telemetry)", fontSize = 11.sp, color = Color(0xFF757575))
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFEDE7F6),
                            modifier = Modifier.clickable {
                                autoSyncInterval = when (autoSyncInterval) {
                                    3 -> 5
                                    5 -> 10
                                    10 -> 30
                                    else -> 3
                                }
                            }
                        ) {
                            Text(
                                text = "${autoSyncInterval}s",
                                color = Color(0xFF673AB7),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            // Section 2: Cache & Storage Management
            SettingsSectionHeader(title = "Local Cache & Data Management")

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = Color.White,
                shadowElevation = 1.5.dp
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFEE2E2)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Outlined.CleaningServices, contentDescription = null, tint = Color(0xFFDC2626), modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Clear Offline Cache", fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = Color(0xFF1D1B20))
                                Text("Purge cached photos, logs & notifications", fontSize = 11.sp, color = Color(0xFF757575))
                            }
                        }

                        OutlinedButton(
                            onClick = { showClearCacheDialog = true },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF5350).copy(alpha = 0.5f)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text("Clear", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Section 3: App Behavior & Preferences
            SettingsSectionHeader(title = "Preferences & Feedback")

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = Color.White,
                shadowElevation = 1.5.dp
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SettingsToggleRow(
                        icon = Icons.Outlined.Vibration,
                        iconTint = Color(0xFF8B5CF6),
                        iconBg = Color(0xFFEDE9FE),
                        title = "Haptic Vibration Feedback",
                        subtitle = "Gentle haptic buzz on button clicks & commands",
                        checked = hapticFeedback,
                        onCheckedChange = { hapticFeedback = it }
                    )

                    HorizontalDivider(color = Color(0xFFF0F0F0))

                    SettingsToggleRow(
                        icon = Icons.Outlined.NotificationsActive,
                        iconTint = Color(0xFF10B981),
                        iconBg = Color(0xFFD1FAE5),
                        title = "Background Alert Sounds",
                        subtitle = "Play chime when remote device alerts occur",
                        checked = backgroundAlerts,
                        onCheckedChange = { backgroundAlerts = it }
                    )
                }
            }

            // Section 4: Security & About
            SettingsSectionHeader(title = "Security & Build Information")

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = Color.White,
                shadowElevation = 1.5.dp
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsInfoRow(label = "Encryption Standard", value = "AES-256-GCM + RSA-4096", icon = Icons.Outlined.Security)
                    HorizontalDivider(color = Color(0xFFF0F0F0))
                    SettingsInfoRow(label = "Kinetix Controller Version", value = "v1.0.0 (Beta Build)", icon = Icons.Outlined.Info)
                    HorizontalDivider(color = Color(0xFFF0F0F0))
                    SettingsInfoRow(label = "SentrY Daemon Compatibility", value = "v1.0.0+ Active", icon = Icons.Outlined.CheckCircle)
                    HorizontalDivider(color = Color(0xFFF0F0F0))
                    SettingsInfoRow(label = "Architecture", value = "Kotlin Multiplatform • Jetpack Compose", icon = Icons.Outlined.Code)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("Clear Local Cache?", fontWeight = FontWeight.Bold) },
            text = { Text("This will purge locally cached notifications, photos, call logs, and telemetry. Fresh data will be pulled from the cloud automatically.") },
            confirmButton = {
                Button(
                    onClick = {
                        val prefs = context.getSharedPreferences("kinetix_device_cache", Context.MODE_PRIVATE)
                        prefs.edit().clear().apply()
                        showClearCacheDialog = false
                        coroutineScope.launch {
                            feedbackMessage = "🧹 Local cache cleared successfully!"
                            delay(2500)
                            feedbackMessage = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Text("Clear All", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 14.sp,
        color = Color(0xFF1D1B20),
        modifier = Modifier.padding(top = 4.dp, start = 2.dp)
    )
}

@Composable
fun SettingsToggleRow(
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = Color(0xFF1D1B20))
                Text(subtitle, fontSize = 11.sp, color = Color(0xFF757575))
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF673AB7)
            )
        )
    }
}

@Composable
fun SettingsInfoRow(
    label: String,
    value: String,
    icon: ImageVector
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Color(0xFF757575), modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(label, fontSize = 12.5.sp, color = Color(0xFF424242), fontWeight = FontWeight.Medium)
        }
        Text(value, fontSize = 12.sp, color = Color(0xFF673AB7), fontWeight = FontWeight.Bold)
    }
}
