package com.example.kinetix.ui.features

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import java.text.SimpleDateFormat
import java.util.*

data class NotificationAppStyle(
    val friendlyApp: String,
    val color: Color,
    val bg: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

data class ModernNotification(
    val id: String,
    val app: String,
    val packageName: String,
    val title: String,
    val body: String,
    val imageBase64: String? = null,
    val timeFormatted: String,
    val exactTimeFormatted: String,
    val timestamp: Long,
    val iconColor: Color,
    val iconBg: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@Composable
fun NotificationCardItem(
    notif: ModernNotification,
    onCopy: () -> Unit,
    onImageClick: (ImageBitmap) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCopy),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF0F0F0)),
        shadowElevation = 1.5.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(15.dp)
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
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(notif.iconBg),
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
                            color = Color(0xFF757575),
                            maxLines = 1
                        )
                    }
                }

                // Time Stamp Pill
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFF0F7FF),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE1EBF5))
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.AccessTime,
                                contentDescription = null,
                                modifier = Modifier.size(11.dp),
                                tint = Color(0xFF1976D2)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = notif.timeFormatted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1976D2)
                            )
                        }
                        Text(
                            text = notif.exactTimeFormatted,
                            fontSize = 10.sp,
                            color = Color(0xFF757575),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Notification Sender / Title
            Text(
                text = notif.title,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = Color(0xFF1D1B20)
            )

            // Notification Body / GPS status footer
            if (notif.body.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                if (notif.body.contains("GPS", ignoreCase = true) || notif.body.contains("°")) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Outlined.LocationOn,
                            contentDescription = null,
                            tint = Color(0xFF7C4DFF),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = notif.body.substringBefore("•").trim(),
                            fontSize = 11.5.sp,
                            color = Color(0xFF616161),
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("|", fontSize = 11.sp, color = Color(0xFFBDBDBD))
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(Color(0xFF4CAF50)))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Active",
                            fontSize = 11.sp,
                            color = Color(0xFF616161),
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    Text(
                        text = notif.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF616161),
                        lineHeight = 19.sp,
                        fontSize = 12.5.sp
                    )
                }
            }

            // Attached Photo / Media Preview
            if (!notif.imageBase64.isNullOrBlank()) {
                val bitmap = remember(notif.imageBase64) {
                    try {
                        val bytes = Base64.decode(notif.imageBase64, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    } catch (_: Exception) {
                        null
                    }
                }

                if (bitmap != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(190.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFFF5F5F5))
                            .clickable { onImageClick(bitmap) }
                    ) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = "Notification Attached Image",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Black.copy(alpha = 0.65f),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.ZoomIn, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Attached Photo", color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(deviceId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

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
        val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
        if (timestamp <= 0) return sdf.format(Date()).lowercase(Locale.getDefault())
        return sdf.format(Date(timestamp)).lowercase(Locale.getDefault())
    }

    fun getDateGroupHeader(timestamp: Long): String {
        if (timestamp <= 0) return "Today"
        val notifCal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val todayCal = Calendar.getInstance()
        val yesterdayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

        val isToday = notifCal.get(Calendar.YEAR) == todayCal.get(Calendar.YEAR) &&
                notifCal.get(Calendar.DAY_OF_YEAR) == todayCal.get(Calendar.DAY_OF_YEAR)
        if (isToday) return "Today"

        val isYesterday = notifCal.get(Calendar.YEAR) == yesterdayCal.get(Calendar.YEAR) &&
                notifCal.get(Calendar.DAY_OF_YEAR) == yesterdayCal.get(Calendar.DAY_OF_YEAR)
        if (isYesterday) return "Yesterday"

        val sdf = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun parseNotificationsJson(arr: org.json.JSONArray): List<ModernNotification> {
        val list = mutableListOf<ModernNotification>()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val pkg = item.optString("packageName", "com.example.sentry")
            val rawAppName = item.optString("appName", "")
            val ts = item.optLong("timestamp", System.currentTimeMillis())
            val img = item.optString("image", "").ifBlank { null }

            val style = when {
                rawAppName.isNotBlank() && !rawAppName.contains(".") -> {
                    val color = when {
                        pkg.contains("whatsapp", ignoreCase = true) -> Color(0xFF25D366)
                        pkg.contains("telegram", ignoreCase = true) -> Color(0xFF0088CC)
                        pkg.contains("instagram", ignoreCase = true) -> Color(0xFFE1306C)
                        pkg.contains("facebook", ignoreCase = true) -> Color(0xFF1877F2)
                        pkg.contains("youtube", ignoreCase = true) -> Color(0xFFFF0000)
                        pkg.contains("mail", ignoreCase = true) || pkg.contains("gm", ignoreCase = true) -> Color(0xFFEA4335)
                        pkg.contains("android", ignoreCase = true) || pkg.contains("system", ignoreCase = true) -> Color(0xFF607D8B)
                        else -> Color(0xFF1976D2)
                    }
                    NotificationAppStyle(rawAppName, color, color.copy(alpha = 0.12f), Icons.Default.Notifications)
                }
                pkg.contains("whatsapp", ignoreCase = true) -> NotificationAppStyle("WhatsApp", Color(0xFF25D366), Color(0xFFE8F5E9), Icons.AutoMirrored.Filled.Chat)
                pkg.contains("telegram", ignoreCase = true) -> NotificationAppStyle("Telegram", Color(0xFF0088CC), Color(0xFFE1F5FE), Icons.Default.Send)
                pkg.contains("instagram", ignoreCase = true) -> NotificationAppStyle("Instagram", Color(0xFFE1306C), Color(0xFFFCE4EC), Icons.Default.CameraAlt)
                pkg.contains("facebook", ignoreCase = true) || pkg.contains("katana", ignoreCase = true) -> NotificationAppStyle("Facebook", Color(0xFF1877F2), Color(0xFFE3F2FD), Icons.Default.ThumbUp)
                pkg.contains("twitter", ignoreCase = true) || pkg.contains("x.android", ignoreCase = true) -> NotificationAppStyle("X / Twitter", Color(0xFF1DA1F2), Color(0xFFE1F5FE), Icons.Default.Tag)
                pkg.contains("messaging", ignoreCase = true) || pkg.contains("mms", ignoreCase = true) -> NotificationAppStyle("Messages (SMS)", Color(0xFF1976D2), Color(0xFFE3F2FD), Icons.Default.Message)
                pkg.contains("gm", ignoreCase = true) || pkg.contains("mail", ignoreCase = true) -> NotificationAppStyle("Gmail", Color(0xFFEA4335), Color(0xFFFFEBEE), Icons.Default.Mail)
                pkg.contains("dialer", ignoreCase = true) || pkg.contains("phone", ignoreCase = true) -> NotificationAppStyle("Phone Call", Color(0xFF34A853), Color(0xFFE8F5E9), Icons.Default.Call)
                pkg.contains("youtube", ignoreCase = true) -> NotificationAppStyle("YouTube", Color(0xFFFF0000), Color(0xFFFFEBEE), Icons.Default.PlayArrow)
                pkg.contains("zomato", ignoreCase = true) -> NotificationAppStyle("Zomato", Color(0xFFE23744), Color(0xFFFFEBEE), Icons.Default.Fastfood)
                pkg.contains("sentry", ignoreCase = true) -> NotificationAppStyle("Sentry Agent", Color(0xFF673AB7), Color(0xFFEDE7F6), Icons.Default.Security)
                pkg.contains("odad", ignoreCase = true) -> NotificationAppStyle("Google Play Protect", Color(0xFF34A853), Color(0xFFE8F5E9), Icons.Default.Security)
                pkg.contains("android", ignoreCase = true) || pkg.contains("system", ignoreCase = true) -> NotificationAppStyle("Android System", Color(0xFF607D8B), Color(0xFFECEFF1), Icons.Default.PhoneAndroid)
                else -> NotificationAppStyle(
                    pkg.substringAfterLast('.').replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },
                    Color(0xFF3F51B5),
                    Color(0xFFEDE7F6),
                    Icons.Default.Notifications
                )
            }

            list.add(
                ModernNotification(
                    id = "${item.optString("id", "notif_$i")}_$i",
                    app = style.friendlyApp,
                    packageName = pkg,
                    title = item.optString("title", "Sentry Alert"),
                    body = item.optString("body", ""),
                    imageBase64 = img,
                    timeFormatted = formatNotificationTime(ts),
                    exactTimeFormatted = formatExactTime(ts),
                    timestamp = ts,
                    iconColor = style.color,
                    iconBg = style.bg,
                    icon = style.icon
                )
            )
        }
        return list.distinctBy { "${it.packageName}|${it.title}|${it.body}|${it.imageBase64?.take(20) ?: ""}" }
    }

    val notifications = remember(deviceId) {
        mutableStateListOf<ModernNotification>().apply {
            val cachedArr = com.example.kinetix.cache.KinetixDeviceCache.getCachedNotifications(context, deviceId)
            if (cachedArr.length() > 0) {
                addAll(parseNotificationsJson(cachedArr))
            }
        }
    }
    var selectedFilter by remember { mutableStateOf("All") }
    var isLoading by remember { mutableStateOf(false) }
    var copiedMessage by remember { mutableStateOf<String?>(null) }
    var previewImageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    suspend fun fetchLiveNotifications() {
        withContext(Dispatchers.IO) {
            val client = com.example.kinetix.network.KinetixApiClient(context)
            val res = client.getNotifications(deviceId)
            if (res.isSuccess) {
                val arr = res.getOrNull()
                if (arr != null) {
                    com.example.kinetix.cache.KinetixDeviceCache.saveCachedNotifications(context, deviceId, arr)
                    val distinctList = parseNotificationsJson(arr)
                    withContext(Dispatchers.Main) {
                        if (notifications.isEmpty()) {
                            notifications.addAll(distinctList)
                        } else {
                            notifications.clear()
                            notifications.addAll(distinctList)
                        }
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

    val groupedByDate = remember(filteredList) {
        filteredList.groupBy { getDateGroupHeader(it.timestamp) }
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
                                fontSize = 18.sp,
                                color = Color(0xFF1D1B20)
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4CAF50).copy(alpha = pulseAlpha))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${notifications.size} Total • Live Stream Active",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF2E7D32),
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color(0xFF1D1B20))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        coroutineScope.launch {
                            isLoading = true
                            fetchLiveNotifications()
                            isLoading = false
                        }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color(0xFF1D1B20))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.White
                )
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = {
                coroutineScope.launch {
                    isLoading = true
                    fetchLiveNotifications()
                    isLoading = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFFBFBFE))
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

            // Modern App Filter Chips
            if (availableApps.size > 1) {
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
                            color = if (isSelected) Color(0xFF1976D2) else Color.White,
                            border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE0E0E0)),
                            shadowElevation = if (isSelected) 2.dp else 0.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = appName,
                                    color = if (isSelected) Color.White else Color(0xFF1D1B20),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 13.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = CircleShape,
                                    color = if (isSelected) Color.White.copy(alpha = 0.25f) else Color(0xFFF0F0F0)
                                ) {
                                    Text(
                                        text = "$count",
                                        color = if (isSelected) Color.White else Color(0xFF616161),
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
                                .background(Color(0xFFEDE7F6)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.NotificationsNone,
                                contentDescription = null,
                                modifier = Modifier.size(44.dp),
                                tint = Color(0xFF673AB7)
                            )
                        }
                        Spacer(modifier = Modifier.height(18.dp))
                        Text(
                            "No Notifications Yet",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 18.sp,
                            color = Color(0xFF1D1B20)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Incoming notifications and attached photos from WhatsApp, SMS, and other apps will stream here live.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF757575),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 28.dp)
                ) {
                    groupedByDate.forEach { (dateHeader, notifsForDate) ->
                        item(key = "header_$dateHeader") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = when (dateHeader) {
                                        "Today" -> Color(0xFFE8F0FE)
                                        "Yesterday" -> Color(0xFFF3EDF7)
                                        else -> Color(0xFFF1F3F4)
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.CalendarToday,
                                            contentDescription = null,
                                            tint = when (dateHeader) {
                                                "Today" -> Color(0xFF1976D2)
                                                "Yesterday" -> Color(0xFF673AB7)
                                                else -> Color(0xFF44474E)
                                            },
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = dateHeader,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = when (dateHeader) {
                                                "Today" -> Color(0xFF1976D2)
                                                "Yesterday" -> Color(0xFF673AB7)
                                                else -> Color(0xFF1D1B20)
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            shape = CircleShape,
                                            color = Color.White.copy(alpha = 0.9f)
                                        ) {
                                            Text(
                                                text = "${notifsForDate.size}",
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF44474E),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFFE8E8E8))
                            }
                        }

                        items(notifsForDate, key = { it.id }) { notif ->
                            NotificationCardItem(
                                notif = notif,
                                onCopy = {
                                    val fullContent = "${notif.app} - ${notif.title}\n${notif.body}\nTime: ${notif.exactTimeFormatted}"
                                    clipboardManager.setText(AnnotatedString(fullContent))
                                    copiedMessage = "Copied to clipboard"
                                    coroutineScope.launch {
                                        delay(2000)
                                        copiedMessage = null
                                    }
                                },
                                onImageClick = { bmp: ImageBitmap -> previewImageBitmap = bmp }
                            )
                        }
                    }
                }
            }
        }
        }

        // Fullscreen Attached Photo Preview Dialog
        if (previewImageBitmap != null) {
            Dialog(
                onDismissRequest = { previewImageBitmap = null },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.95f))
                        .clickable { previewImageBitmap = null },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = previewImageBitmap!!,
                        contentDescription = "Fullscreen Attached Photo",
                        modifier = Modifier
                            .fillMaxWidth(0.95f)
                            .fillMaxHeight(0.80f)
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Fit
                    )

                    // Close Button Top-Right
                    IconButton(
                        onClick = { previewImageBitmap = null },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(24.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.2f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}




