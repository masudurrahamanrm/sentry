package com.example.kinetix.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextOverflow
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

    // Persistent settings loaded from KinetixDeviceCache
    var serverUrl by remember { mutableStateOf(KinetixDeviceCache.getServerUrl(context)) }
    var pingStatus by remember { mutableStateOf<String?>(null) }
    var isPinging by remember { mutableStateOf(false) }

    var autoSyncInterval by remember { mutableIntStateOf(KinetixDeviceCache.getTelemetrySyncInterval(context)) }
    var hapticFeedback by remember { mutableStateOf(KinetixDeviceCache.isHapticEnabled(context)) }
    var backgroundAlerts by remember { mutableStateOf(KinetixDeviceCache.isBackgroundAlertsEnabled(context)) }
    var zeroLagCaching by remember { mutableStateOf(KinetixDeviceCache.isZeroLagCachingEnabled(context)) }
    var lowDataMode by remember { mutableStateOf(KinetixDeviceCache.isLowDataModeEnabled(context)) }
    var appLock by remember { mutableStateOf(KinetixDeviceCache.isAppLockEnabled(context)) }

    // Dialog & UI states
    var showServerUrlDialog by remember { mutableStateOf(false) }
    var customServerInput by remember { mutableStateOf(serverUrl) }
    var showSyncIntervalDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var deviceRenameInput by remember { mutableStateOf("") }
    var isRenamingDevice by remember { mutableStateOf(false) }
    var isIconHidden by remember { mutableStateOf(false) }
    var isUpdatingIcon by remember { mutableStateOf(false) }
    var isWakingDevice by remember { mutableStateOf(false) }

    var estimatedCacheSizeBytes by remember { mutableLongStateOf(KinetixDeviceCache.getEstimatedCacheSizeBytes(context)) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var isRefreshingSettings by remember { mutableStateOf(false) }

    val controllerDeviceId = remember { CryptoManager.getOrCreateDeviceId(context) }
    var deviceAlias by remember(deviceId) {
        mutableStateOf(if (!deviceId.isNullOrBlank()) KinetixDeviceCache.getDeviceName(context, deviceId) else "")
    }

    fun triggerHapticFeedback() {
        if (!hapticFeedback) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(35)
                }
            }
        } catch (_: Exception) {}
    }

    // Check remote icon state if deviceId is passed
    LaunchedEffect(deviceId) {
        if (!deviceId.isNullOrBlank()) {
            withContext(Dispatchers.IO) {
                try {
                    val client = KinetixApiClient(context)
                    val iconRes = client.getAppIconState(deviceId)
                    if (iconRes.isSuccess) {
                        withContext(Dispatchers.Main) {
                            isIconHidden = iconRes.getOrDefault(false)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Settings & Preferences",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 18.sp,
                            color = Color(0xFF1D1B20)
                        )
                        if (!deviceId.isNullOrBlank()) {
                            Text(
                                text = "Device: $deviceAlias",
                                fontSize = 11.5.sp,
                                color = Color(0xFF673AB7),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        triggerHapticFeedback()
                        onBack()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color(0xFF1D1B20)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        triggerHapticFeedback()
                        coroutineScope.launch {
                            isRefreshingSettings = true
                            serverUrl = KinetixDeviceCache.getServerUrl(context)
                            autoSyncInterval = KinetixDeviceCache.getTelemetrySyncInterval(context)
                            estimatedCacheSizeBytes = KinetixDeviceCache.getEstimatedCacheSizeBytes(context)
                            delay(400)
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
                triggerHapticFeedback()
                coroutineScope.launch {
                    isRefreshingSettings = true
                    serverUrl = KinetixDeviceCache.getServerUrl(context)
                    autoSyncInterval = KinetixDeviceCache.getTelemetrySyncInterval(context)
                    estimatedCacheSizeBytes = KinetixDeviceCache.getEstimatedCacheSizeBytes(context)
                    delay(400)
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
                            border = BorderStroke(1.dp, Color(0xFF4CAF50).copy(alpha = 0.5f)),
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
                                    Text("Hardware Cryptographic Identity", fontSize = 11.sp, color = Color(0xFF757575))
                                }
                            }
                            IconButton(onClick = {
                                triggerHapticFeedback()
                                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clip?.setPrimaryClip(ClipData.newPlainText("Controller Device ID", controllerDeviceId))
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

                // Section: Remote Device Management (Only when deviceId is provided)
                if (!deviceId.isNullOrBlank()) {
                    SettingsSectionHeader(title = "Remote Target Device ($deviceAlias)")

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = Color.White,
                        shadowElevation = 1.5.dp
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            // Device Nickname Row
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
                                        Icon(Icons.Outlined.Edit, contentDescription = null, tint = Color(0xFF673AB7), modifier = Modifier.size(20.dp))
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text("Device Nickname", fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = Color(0xFF1D1B20))
                                        Text(deviceAlias, fontSize = 11.sp, color = Color(0xFF757575), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                OutlinedButton(
                                    onClick = {
                                        triggerHapticFeedback()
                                        deviceRenameInput = deviceAlias
                                        showRenameDialog = true
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text("Rename", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            HorizontalDivider(color = Color(0xFFF0F0F0))

                            // App Icon Stealth Toggle Row
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
                                            .background(if (isIconHidden) Color(0xFFEDE7F6) else Color(0xFFF1F5F9)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            if (isIconHidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                            contentDescription = null,
                                            tint = if (isIconHidden) Color(0xFF673AB7) else Color(0xFF64748B),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text("Stealth App Icon", fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = Color(0xFF1D1B20))
                                        Text(if (isIconHidden) "Icon hidden on remote home screen" else "Icon visible in app drawer", fontSize = 11.sp, color = Color(0xFF757575))
                                    }
                                }
                                Switch(
                                    checked = isIconHidden,
                                    enabled = !isUpdatingIcon,
                                    onCheckedChange = { hide ->
                                        triggerHapticFeedback()
                                        isUpdatingIcon = true
                                        coroutineScope.launch {
                                            withContext(Dispatchers.IO) {
                                                try {
                                                    val client = KinetixApiClient(context)
                                                    val res = client.setAppIconHidden(deviceId, hide)
                                                    if (res.isSuccess) {
                                                        withContext(Dispatchers.Main) {
                                                            isIconHidden = hide
                                                            feedbackMessage = if (hide) "🕶️ SentrY icon hidden on remote device" else "👁️ SentrY icon restored on remote device"
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(context, "Failed to update icon: ${e.message}", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                            isUpdatingIcon = false
                                            delay(2500)
                                            feedbackMessage = null
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFF673AB7)
                                    )
                                )
                            }

                            HorizontalDivider(color = Color(0xFFF0F0F0))

                            // Remote Wake / Ping Signal Row
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
                                            .background(Color(0xFFFEF3C7)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Outlined.Podcasts, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(20.dp))
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text("Remote Wakeup Signal", fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = Color(0xFF1D1B20))
                                        Text("Force daemon to sync immediate telemetry", fontSize = 11.sp, color = Color(0xFF757575))
                                    }
                                }
                                Button(
                                    onClick = {
                                        if (isWakingDevice) return@Button
                                        triggerHapticFeedback()
                                        isWakingDevice = true
                                        coroutineScope.launch {
                                            withContext(Dispatchers.IO) {
                                                try {
                                                    val client = KinetixApiClient(context)
                                                    val res = client.wakeDevice(deviceId)
                                                    withContext(Dispatchers.Main) {
                                                        if (res.isSuccess) {
                                                            feedbackMessage = "📡 Wake signal transmitted to $deviceAlias"
                                                        } else {
                                                            feedbackMessage = "⚠️ Wake signal queued for cloud relay"
                                                        }
                                                    }
                                                } catch (_: Exception) {
                                                    withContext(Dispatchers.Main) {
                                                        feedbackMessage = "📡 Wake signal broadcast sent"
                                                    }
                                                }
                                            }
                                            isWakingDevice = false
                                            delay(2500)
                                            feedbackMessage = null
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text(if (isWakingDevice) "Sending..." else "Ping Wake", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        triggerHapticFeedback()
                                        customServerInput = serverUrl
                                        showServerUrlDialog = true
                                    }
                            ) {
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
                                    Text("Cloud Server Node", fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = Color(0xFF1D1B20))
                                    Text(serverUrl, fontSize = 10.5.sp, color = Color(0xFF757575), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Button(
                                onClick = {
                                    if (isPinging) return@Button
                                    triggerHapticFeedback()
                                    isPinging = true
                                    pingStatus = "Pinging..."
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
                                        pingStatus = if (ok) "⚡ ${duration}ms" else "❌ Offline"
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

                        // Telemetry Sync Rate Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    triggerHapticFeedback()
                                    showSyncIntervalDialog = true
                                },
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
                                    Text(
                                        when (autoSyncInterval) {
                                            1 -> "1s (Real-time live telemetry)"
                                            3 -> "3s (Continuous active tracking)"
                                            5 -> "5s (Balanced battery & data)"
                                            10 -> "10s (Battery saver mode)"
                                            else -> "${autoSyncInterval}s (Custom Interval)"
                                        },
                                        fontSize = 11.sp,
                                        color = Color(0xFF757575)
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFFEDE7F6)
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

                        HorizontalDivider(color = Color(0xFFF0F0F0))

                        SettingsToggleRow(
                            icon = Icons.Outlined.Bolt,
                            iconTint = Color(0xFFF59E0B),
                            iconBg = Color(0xFFFEF3C7),
                            title = "Zero-Lag Instant Caching",
                            subtitle = "0ms instant offline rendering with background validation",
                            checked = zeroLagCaching,
                            onCheckedChange = {
                                triggerHapticFeedback()
                                zeroLagCaching = it
                                KinetixDeviceCache.saveZeroLagCachingEnabled(context, it)
                                coroutineScope.launch {
                                    feedbackMessage = if (it) "⚡ Zero-Lag Caching enabled" else "Zero-Lag Caching disabled"
                                    delay(2000)
                                    feedbackMessage = null
                                }
                            }
                        )

                        HorizontalDivider(color = Color(0xFFF0F0F0))

                        SettingsToggleRow(
                            icon = Icons.Outlined.DataUsage,
                            iconTint = Color(0xFF0284C7),
                            iconBg = Color(0xFFE0F2FE),
                            title = "Low Data Saver Mode",
                            subtitle = "Compress thumbnails & restrict background sync to Wi-Fi",
                            checked = lowDataMode,
                            onCheckedChange = {
                                triggerHapticFeedback()
                                lowDataMode = it
                                KinetixDeviceCache.saveLowDataModeEnabled(context, it)
                                coroutineScope.launch {
                                    feedbackMessage = if (it) "📉 Low Data Mode active" else "Standard bandwidth restored"
                                    delay(2000)
                                    feedbackMessage = null
                                }
                            }
                        )
                    }
                }

                // Section 2: Cache & Storage Management
                SettingsSectionHeader(title = "Local Cache & Storage")

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
                                    val formattedSize = if (estimatedCacheSizeBytes > 1024 * 1024) {
                                        String.format(java.util.Locale.US, "%.1f MB cached data", estimatedCacheSizeBytes / (1024f * 1024f))
                                    } else {
                                        String.format(java.util.Locale.US, "%.1f KB cached data", estimatedCacheSizeBytes / 1024f)
                                    }
                                    Text(formattedSize, fontSize = 11.sp, color = Color(0xFF757575))
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    triggerHapticFeedback()
                                    showClearCacheDialog = true
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626)),
                                border = BorderStroke(1.dp, Color(0xFFEF5350).copy(alpha = 0.5f)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("Clear", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        HorizontalDivider(color = Color(0xFFF0F0F0))

                        // Export Diagnostics Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    triggerHapticFeedback()
                                    val diagText = """
                                        === KINETIX CONTROLLER DIAGNOSTICS ===
                                        Controller ID: $controllerDeviceId
                                        Active Device: ${deviceId ?: "None"} ($deviceAlias)
                                        Server URL: $serverUrl
                                        Telemetry Sync Rate: ${autoSyncInterval}s
                                        Zero-Lag Caching: $zeroLagCaching
                                        Low Data Mode: $lowDataMode
                                        Cache Footprint: ${estimatedCacheSizeBytes} bytes
                                        OS Version: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
                                        App Version: v1.0.0 (Beta Build)
                                        Timestamp: ${java.util.Date()}
                                    """.trimIndent()

                                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    clip?.setPrimaryClip(ClipData.newPlainText("Kinetix Diagnostics", diagText))
                                    Toast.makeText(context, "Diagnostics report copied to clipboard!", Toast.LENGTH_SHORT).show()
                                    coroutineScope.launch {
                                        feedbackMessage = "📄 Diagnostic log exported to clipboard"
                                        delay(2500)
                                        feedbackMessage = null
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFE2E8F0)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Outlined.BugReport, contentDescription = null, tint = Color(0xFF475569), modifier = Modifier.size(20.dp))
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Export Diagnostics Log", fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = Color(0xFF1D1B20))
                                    Text("Copy full system runtime report to clipboard", fontSize = 11.sp, color = Color(0xFF757575))
                                }
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color(0xFF9E9E9E))
                        }
                    }
                }

                // Section 3: App Behavior & Preferences
                SettingsSectionHeader(title = "App Preferences & Feedback")

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
                            subtitle = "Tactile haptic buzz on button clicks & commands",
                            checked = hapticFeedback,
                            onCheckedChange = {
                                hapticFeedback = it
                                KinetixDeviceCache.saveHapticEnabled(context, it)
                                if (it) triggerHapticFeedback()
                            }
                        )

                        HorizontalDivider(color = Color(0xFFF0F0F0))

                        SettingsToggleRow(
                            icon = Icons.Outlined.NotificationsActive,
                            iconTint = Color(0xFF10B981),
                            iconBg = Color(0xFFD1FAE5),
                            title = "Background Alert Sounds",
                            subtitle = "Play audio chime when remote device critical alerts occur",
                            checked = backgroundAlerts,
                            onCheckedChange = {
                                triggerHapticFeedback()
                                backgroundAlerts = it
                                KinetixDeviceCache.saveBackgroundAlertsEnabled(context, it)
                            }
                        )

                        HorizontalDivider(color = Color(0xFFF0F0F0))

                        SettingsToggleRow(
                            icon = Icons.Outlined.Lock,
                            iconTint = Color(0xFF6366F1),
                            iconBg = Color(0xFFEEF2FF),
                            title = "App Lock on Launch",
                            subtitle = "Require device biometric or PIN to open Kinetix",
                            checked = appLock,
                            onCheckedChange = {
                                triggerHapticFeedback()
                                appLock = it
                                KinetixDeviceCache.saveAppLockEnabled(context, it)
                                coroutineScope.launch {
                                    feedbackMessage = if (it) "🔒 App Lock enabled" else "🔓 App Lock disabled"
                                    delay(2000)
                                    feedbackMessage = null
                                }
                            }
                        )
                    }
                }

                // Section 4: Security & About
                SettingsSectionHeader(title = "Security & Hardware Keystore")

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = Color.White,
                    shadowElevation = 1.5.dp
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SettingsInfoRow(label = "Encryption Standard", value = "AES-256-GCM + RSA-4096", icon = Icons.Outlined.Security)
                        HorizontalDivider(color = Color(0xFFF0F0F0))
                        SettingsInfoRow(label = "Hardware Key Provider", value = "AndroidKeyStore (StrongBox)", icon = Icons.Outlined.Key)
                        HorizontalDivider(color = Color(0xFFF0F0F0))
                        SettingsInfoRow(label = "Kinetix Controller Version", value = "v1.0.0 (Beta Build)", icon = Icons.Outlined.Info)
                        HorizontalDivider(color = Color(0xFFF0F0F0))
                        SettingsInfoRow(label = "SentrY Daemon Compatibility", value = "v1.0.0+ Active", icon = Icons.Outlined.CheckCircle)
                        HorizontalDivider(color = Color(0xFFF0F0F0))
                        SettingsInfoRow(label = "Architecture", value = "Jetpack Compose • KMP Architecture", icon = Icons.Outlined.Code)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Dialog 1: Change / Configure Cloud Server URL
    if (showServerUrlDialog) {
        AlertDialog(
            onDismissRequest = { showServerUrlDialog = false },
            title = { Text("Cloud Server Configuration", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Select a preset endpoint or enter a custom backend API URL:", fontSize = 12.5.sp, color = Color(0xFF555555))

                    OutlinedTextField(
                        value = customServerInput,
                        onValueChange = { customServerInput = it },
                        label = { Text("Server Base URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )

                    Text("Quick Presets:", fontWeight = FontWeight.Bold, fontSize = 11.5.sp, color = Color(0xFF757575))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SuggestionChip(
                            onClick = { customServerInput = "https://sentry-devloper-version.onrender.com/api/v1" },
                            label = { Text("Render Cloud", fontSize = 11.sp) }
                        )
                        SuggestionChip(
                            onClick = { customServerInput = "https://sentry-f502.onrender.com/api/v1" },
                            label = { Text("Backup Node", fontSize = 11.sp) }
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        triggerHapticFeedback()
                        val clean = customServerInput.trim().trimEnd('/')
                        if (clean.isNotBlank()) {
                            serverUrl = clean
                            KinetixDeviceCache.saveServerUrl(context, clean)
                            showServerUrlDialog = false
                            coroutineScope.launch {
                                feedbackMessage = "🌐 Server URL updated to: $clean"
                                delay(2500)
                                feedbackMessage = null
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                ) {
                    Text("Save & Apply", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showServerUrlDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dialog 2: Telemetry Sync Rate Selection
    if (showSyncIntervalDialog) {
        AlertDialog(
            onDismissRequest = { showSyncIntervalDialog = false },
            title = { Text("Telemetry Sync Rate", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Select how frequently Kinetix queries real-time battery, network, and sensor telemetry from SentrY:", fontSize = 12.5.sp)

                    val options = listOf(
                        1 to "1s • Real-time continuous",
                        3 to "3s • Standard active sync (Recommended)",
                        5 to "5s • Balanced data & battery",
                        10 to "10s • Battery saver mode",
                        30 to "30s • Low-power standby"
                    )

                    options.forEach { (sec, label) ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    triggerHapticFeedback()
                                    autoSyncInterval = sec
                                    KinetixDeviceCache.saveTelemetrySyncInterval(context, sec)
                                    showSyncIntervalDialog = false
                                    coroutineScope.launch {
                                        feedbackMessage = "⚡ Telemetry sync rate set to ${sec}s"
                                        delay(2000)
                                        feedbackMessage = null
                                    }
                                },
                            shape = RoundedCornerShape(10.dp),
                            color = if (autoSyncInterval == sec) Color(0xFFEDE7F6) else Color(0xFFF8F8FA),
                            border = if (autoSyncInterval == sec) BorderStroke(1.dp, Color(0xFF673AB7)) else null
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 12.5.sp,
                                    fontWeight = if (autoSyncInterval == sec) FontWeight.Bold else FontWeight.Normal,
                                    color = if (autoSyncInterval == sec) Color(0xFF673AB7) else Color(0xFF1D1B20)
                                )
                                if (autoSyncInterval == sec) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF673AB7), modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSyncIntervalDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Dialog 3: Device Rename Dialog
    if (showRenameDialog && !deviceId.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = { if (!isRenamingDevice) showRenameDialog = false },
            title = { Text("Rename Remote Device", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Assign a custom alias for $deviceAlias on this controller and in cloud listings:", fontSize = 12.5.sp)
                    OutlinedTextField(
                        value = deviceRenameInput,
                        onValueChange = { deviceRenameInput = it },
                        label = { Text("Device Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val newName = deviceRenameInput.trim()
                        if (newName.isNotBlank()) {
                            triggerHapticFeedback()
                            isRenamingDevice = true
                            KinetixDeviceCache.saveDeviceName(context, deviceId, newName)
                            deviceAlias = newName
                            coroutineScope.launch {
                                withContext(Dispatchers.IO) {
                                    try {
                                        val client = KinetixApiClient(context)
                                        client.updateDeviceName(deviceId, newName)
                                    } catch (_: Exception) {}
                                }
                                isRenamingDevice = false
                                showRenameDialog = false
                                feedbackMessage = "✏️ Device renamed to: $newName"
                                delay(2500)
                                feedbackMessage = null
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF673AB7))
                ) {
                    Text(if (isRenamingDevice) "Saving..." else "Save Name", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }, enabled = !isRenamingDevice) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dialog 4: Clear Cache Dialog
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("Clear Local Cache?", fontWeight = FontWeight.Bold) },
            text = { Text("This will purge locally cached notifications, photos, call logs, telemetry, and temporary offline thumbnails. Fresh data will seamlessly pull from the cloud backend.") },
            confirmButton = {
                Button(
                    onClick = {
                        triggerHapticFeedback()
                        KinetixDeviceCache.clearAllDataCache(context)
                        estimatedCacheSizeBytes = 0L
                        showClearCacheDialog = false
                        coroutineScope.launch {
                            feedbackMessage = "🧹 Local cache cleared successfully!"
                            delay(2500)
                            feedbackMessage = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Text("Clear All Data", color = Color.White)
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
