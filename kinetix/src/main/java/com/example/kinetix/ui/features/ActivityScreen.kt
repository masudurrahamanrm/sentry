package com.example.kinetix.ui.features

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kinetix.network.KinetixApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

data class AppUsageStat(
    val name: String,
    val packageName: String,
    val category: String,
    val durationText: String,
    val durationMinutes: Int,
    val percentage: Float,
    val openCount: Int,
    val iconColor: Color,
    val icon: ImageVector,
    val iconBase64: String? = null,
    val iconBitmap: Bitmap? = null
)

data class DayActivityData(
    val dayOfWeek: String,       // "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"
    val dateLabel: String,       // "Fri, 4 Sept"
    val screenTimeText: String,  // "2 hrs, 26 mins"
    val screenTimeMinutes: Int,
    val unlocks: Int,
    val isToday: Boolean,
    val isFuture: Boolean,
    val apps: List<AppUsageStat>
)

enum class MetricType(val label: String) {
    SCREEN_TIME("Screen time"),
    NOTIFICATIONS("Notifications received"),
    TIMES_OPENED("Times opened")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    deviceId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(false) }
    var hasPermissionOnRemote by remember { mutableStateOf(true) }
    var selectedMetric by remember { mutableStateOf(MetricType.SCREEN_TIME) }
    var showMetricDropdown by remember { mutableStateOf(false) }
    var showOptionsMenu by remember { mutableStateOf(false) }
    var showTimerBanner by remember { mutableStateOf(true) }

    // 7-day data
    var weeklyDays by remember { mutableStateOf<List<DayActivityData>>(emptyList()) }
    var selectedDayIndex by remember { mutableIntStateOf(5) } // Defaults to Friday / current day

    fun decodeAppIcon(base64Str: String?): Bitmap? {
        if (base64Str.isNullOrBlank()) return null
        return try {
            val bytes = Base64.decode(base64Str, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }

    fun resolveProperAppName(packageName: String, systemLabel: String?): String {
        if (!systemLabel.isNullOrBlank() && !systemLabel.contains(".") && !systemLabel.equals("android", ignoreCase = true)) {
            return systemLabel
        }
        val lower = packageName.lowercase(Locale.ROOT)
        return when {
            lower == "com.whatsapp" || lower.contains("whatsapp") -> "WhatsApp"
            lower == "com.google.android.youtube" || lower.contains("youtube") -> "YouTube"
            lower == "com.instagram.android" || lower.contains("instagram") -> "Instagram"
            lower == "com.android.chrome" || lower.contains("chrome") -> "Google Chrome"
            lower == "com.facebook.katana" || lower.contains("facebook") -> "Facebook"
            lower == "com.facebook.orca" || lower.contains("messenger") -> "Messenger"
            lower == "org.telegram.messenger" || lower.contains("telegram") -> "Telegram"
            lower == "com.spotify.music" || lower.contains("spotify") -> "Spotify"
            lower == "com.google.android.apps.maps" || lower.contains("maps") -> "Google Maps"
            lower == "com.google.android.gm" || lower.contains("gmail") -> "Gmail"
            lower == "com.snapchat.android" || lower.contains("snapchat") -> "Snapchat"
            lower == "com.zhiliaoapp.musically" || lower.contains("tiktok") -> "TikTok"
            lower == "com.netflix.mediaclient" || lower.contains("netflix") -> "Netflix"
            lower == "com.dts.freefireth" || lower.contains("freefire") -> "Free Fire"
            lower == "com.tencent.ig" || lower.contains("pubg") -> "PUBG Mobile"
            lower == "com.example.kinetix" -> "Kinetix"
            lower.contains("gallery") -> "Photos & Gallery"
            lower.contains("camera") -> "Camera"
            lower.contains("settings") -> "Settings"
            lower.contains("calculator") -> "Calculator"
            lower.contains("contacts") || lower.contains("dialer") -> "Phone & Contacts"
            else -> packageName.substringAfterLast('.').replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }
    }

    fun parseAppsFromJson(appsArr: JSONArray?, totalMins: Int): List<AppUsageStat> {
        if (appsArr == null || appsArr.length() == 0) return emptyList()
        val list = mutableListOf<AppUsageStat>()

        for (i in 0 until appsArr.length()) {
            val obj = appsArr.optJSONObject(i) ?: continue
            val pkg = obj.optString("packageName", "")
            val rawName = obj.optString("name", "")
            val name = resolveProperAppName(pkg, rawName)
            val cat = obj.optString("category", "Utility")
            val durMins = obj.optInt("durationMinutes", 0)

            val hours = durMins / 60
            val remMins = durMins % 60
            val durText = if (hours > 0 && remMins > 0) {
                "$hours hr, $remMins mins"
            } else if (hours > 0) {
                "$hours hr"
            } else {
                "$remMins mins"
            }

            val pct = if (totalMins > 0) {
                (durMins.toFloat() / totalMins).coerceIn(0.01f, 1.0f)
            } else obj.optDouble("percentage", 0.0).toFloat().coerceIn(0.01f, 1.0f)

            val opens = obj.optInt("openCount", 1)
            val iconBase64 = obj.optString("iconBase64").takeIf { !it.isNullOrBlank() && it != "null" }
            val iconBitmap = decodeAppIcon(iconBase64)

            val (color, icon) = when {
                pkg.contains("whatsapp", ignoreCase = true) -> Color(0xFF25D366) to Icons.AutoMirrored.Filled.Chat
                pkg.contains("youtube", ignoreCase = true) -> Color(0xFFFF0000) to Icons.Default.PlayArrow
                pkg.contains("instagram", ignoreCase = true) -> Color(0xFFE1306C) to Icons.Default.CameraAlt
                pkg.contains("facebook", ignoreCase = true) -> Color(0xFF1877F2) to Icons.Default.Public
                pkg.contains("telegram", ignoreCase = true) -> Color(0xFF0088CC) to Icons.AutoMirrored.Filled.Chat
                pkg.contains("chrome", ignoreCase = true) || pkg.contains("browser", ignoreCase = true) -> Color(0xFF1976D2) to Icons.Default.Language
                pkg.contains("game", ignoreCase = true) || pkg.contains("freefire", ignoreCase = true) || pkg.contains("pubg", ignoreCase = true) -> Color(0xFFFF9800) to Icons.Default.SportsEsports
                pkg.contains("maps", ignoreCase = true) -> Color(0xFF4CAF50) to Icons.Default.LocationOn
                pkg.contains("gallery", ignoreCase = true) || pkg.contains("photo", ignoreCase = true) || pkg.contains("camera", ignoreCase = true) -> Color(0xFF9C27B0) to Icons.Default.PhotoLibrary
                pkg.contains("settings", ignoreCase = true) -> Color(0xFF607D8B) to Icons.Default.Settings
                else -> Color(0xFF673AB7) to Icons.Default.Apps
            }

            list.add(
                AppUsageStat(
                    name = name,
                    packageName = pkg,
                    category = cat,
                    durationText = durText,
                    durationMinutes = durMins,
                    percentage = pct,
                    openCount = opens,
                    iconColor = color,
                    icon = icon,
                    iconBase64 = iconBase64,
                    iconBitmap = iconBitmap
                )
            )
        }
        return list
    }

    suspend fun fetchActivityData() {
        withContext(Dispatchers.IO) {
            try {
                val client = KinetixApiClient(context)
                val res = client.getDeviceActivity(deviceId)
                if (res.isSuccess) {
                    val actObj = res.getOrNull()
                    if (actObj != null) {
                        val hasPerm = actObj.optBoolean("hasPermission", true)
                        withContext(Dispatchers.Main) {
                            hasPermissionOnRemote = hasPerm
                        }

                        val dailyArr = actObj.optJSONArray("dailyBreakdown")
                        val dayNames = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
                        val monthNames = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sept", "Oct", "Nov", "Dec")
                        val todayCal = Calendar.getInstance()
                        val currentDayOfWeekIdx = todayCal.get(Calendar.DAY_OF_WEEK) - 1 // 0=Sun .. 6=Sat

                        val parsedDays = mutableListOf<DayActivityData>()

                        if (dailyArr != null && dailyArr.length() == 7) {
                            for (i in 0 until 7) {
                                val dObj = dailyArr.optJSONObject(i) ?: JSONObject()
                                val dayOfWeek = dObj.optString("day", dayNames[i])
                                val dateLabel = dObj.optString("date", "$dayOfWeek, ${todayCal.get(Calendar.DAY_OF_MONTH)} ${monthNames[todayCal.get(Calendar.MONTH)]}")
                                val durMins = dObj.optInt("screenTimeMinutes", 0)
                                val unlocks = dObj.optInt("unlocks", 0)
                                val isToday = dObj.optBoolean("isToday", i == currentDayOfWeekIdx)
                                val isFuture = dObj.optBoolean("isFuture", i > currentDayOfWeekIdx)

                                val hours = durMins / 60
                                val remMins = durMins % 60
                                val durText = if (hours > 0 && remMins > 0) {
                                    "$hours hrs, $remMins mins"
                                } else if (hours > 0) {
                                    "$hours hrs"
                                } else {
                                    "$remMins mins"
                                }

                                val apps = parseAppsFromJson(dObj.optJSONArray("apps"), durMins)

                                parsedDays.add(
                                    DayActivityData(
                                        dayOfWeek = dayOfWeek,
                                        dateLabel = dateLabel,
                                        screenTimeText = durText,
                                        screenTimeMinutes = durMins,
                                        unlocks = unlocks,
                                        isToday = isToday,
                                        isFuture = isFuture,
                                        apps = apps
                                    )
                                )
                            }
                        } else {
                            // Fallback using direct response
                            val todayMins = actObj.optInt("screenTimeMinutes", 0)
                            val todayApps = parseAppsFromJson(actObj.optJSONArray("apps"), todayMins)

                            val hours = todayMins / 60
                            val remMins = todayMins % 60
                            val durText = if (hours > 0 && remMins > 0) {
                                "$hours hrs, $remMins mins"
                            } else if (hours > 0) {
                                "$hours hrs"
                            } else {
                                "$remMins mins"
                            }

                            for (i in 0..6) {
                                val isToday = (i == currentDayOfWeekIdx)
                                val isFuture = (i > currentDayOfWeekIdx)
                                val dayName = dayNames[i]
                                val dateLabel = "$dayName, ${todayCal.get(Calendar.DAY_OF_MONTH)} ${monthNames[todayCal.get(Calendar.MONTH)]}"

                                parsedDays.add(
                                    DayActivityData(
                                        dayOfWeek = dayName,
                                        dateLabel = dateLabel,
                                        screenTimeText = if (isToday) durText else "0 mins",
                                        screenTimeMinutes = if (isToday) todayMins else 0,
                                        unlocks = if (isToday) actObj.optInt("unlocks", 0) else 0,
                                        isToday = isToday,
                                        isFuture = isFuture,
                                        apps = if (isToday) todayApps else emptyList()
                                    )
                                )
                            }
                        }

                        withContext(Dispatchers.Main) {
                            weeklyDays = parsedDays
                            selectedDayIndex = currentDayOfWeekIdx.coerceIn(0, 6)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(deviceId) {
        fetchActivityData()
    }

    val currentDay = weeklyDays.getOrNull(selectedDayIndex)

    Scaffold(
        containerColor = Color.White,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "App activity details",
                        fontWeight = FontWeight.Normal,
                        fontSize = 22.sp,
                        color = Color(0xFF1D1B20)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color(0xFF1D1B20)
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showOptionsMenu = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "More options",
                                tint = Color(0xFF1D1B20)
                            )
                        }
                        DropdownMenu(
                            expanded = showOptionsMenu,
                            onDismissRequest = { showOptionsMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Refresh telemetry") },
                                onClick = {
                                    showOptionsMenu = false
                                    coroutineScope.launch {
                                        isLoading = true
                                        fetchActivityData()
                                        isLoading = false
                                    }
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = {
                coroutineScope.launch {
                    isLoading = true
                    fetchActivityData()
                    isLoading = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp)
            ) {
                // Permission Warning Banner (if disabled on target device)
                if (!hasPermissionOnRemote) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFFFFFBEB),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFFEF3C7)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(20.dp))
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Usage Access Required on Target Phone",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = Color(0xFF92400E)
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Grant 'Usage Access' in SentrY > Permissions on the remote device to receive real-time screen time.",
                                        fontSize = 11.sp,
                                        color = Color(0xFFB45309)
                                    )
                                }
                            }
                        }
                    }
                }

                // 1. Metric Selector Pill Button (Screen time ▾)
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            onClick = { showMetricDropdown = true },
                            shape = RoundedCornerShape(20.dp),
                            color = Color(0xFFE8EEF9),
                            modifier = Modifier.height(38.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = selectedMetric.label,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF041E49)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    tint = Color(0xFF041E49),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = showMetricDropdown,
                            onDismissRequest = { showMetricDropdown = false }
                        ) {
                            MetricType.entries.forEach { metric ->
                                DropdownMenuItem(
                                    text = { Text(metric.label) },
                                    onClick = {
                                        selectedMetric = metric
                                        showMetricDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }

                // 2. Big Centered Hero Metric (e.g. 2 hrs, 26 mins / Today)
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val heroText = when (selectedMetric) {
                            MetricType.SCREEN_TIME -> currentDay?.screenTimeText ?: "0 mins"
                            MetricType.NOTIFICATIONS -> "${currentDay?.unlocks ?: 0} notifications"
                            MetricType.TIMES_OPENED -> "${currentDay?.apps?.sumOf { it.openCount } ?: 0} opens"
                        }

                        val heroSub = if (currentDay?.isToday == true) "Today" else currentDay?.dateLabel ?: "Today"

                        Text(
                            text = heroText,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Normal,
                            color = Color(0xFF1D1B20),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = heroSub,
                            fontSize = 13.sp,
                            color = Color(0xFF44474E),
                            fontWeight = FontWeight.Normal
                        )
                    }
                }

                // 3. 7-Day Histogram (Weekly Bar Chart Sun - Sat)
                item {
                    WeeklyHistogramChart(
                        days = weeklyDays,
                        selectedIndex = selectedDayIndex,
                        onSelectDay = { index ->
                            if (index in weeklyDays.indices && !weeklyDays[index].isFuture) {
                                selectedDayIndex = index
                            }
                        }
                    )
                }

                // 4. Date Stepper Navigation (< Fri, 4 Sept >)
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val canGoLeft = selectedDayIndex > 0
                        val canGoRight = selectedDayIndex < weeklyDays.lastIndex && !weeklyDays[selectedDayIndex + 1].isFuture

                        IconButton(
                            onClick = { if (canGoLeft) selectedDayIndex-- },
                            enabled = canGoLeft,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "Previous day",
                                tint = if (canGoLeft) Color(0xFF44474E) else Color(0xFFC4C7C5)
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Text(
                            text = currentDay?.dateLabel ?: "Today",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF1D1B20)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        IconButton(
                            onClick = { if (canGoRight) selectedDayIndex++ },
                            enabled = canGoRight,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "Next day",
                                tint = if (canGoRight) Color(0xFF44474E) else Color(0xFFC4C7C5)
                            )
                        }
                    }
                }

                // 5. "Set timers for your apps" Info Banner
                if (showTimerBanner) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = Color(0xFFE8DEF8),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(18.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    Icons.Outlined.HourglassEmpty,
                                    contentDescription = null,
                                    tint = Color(0xFF1D1B20),
                                    modifier = Modifier
                                        .size(24.dp)
                                        .padding(top = 2.dp)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Set timers for your apps",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.5.sp,
                                        color = Color(0xFF1D1B20)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "You can set daily timers for most apps. When the app timer ends, the app is paused for the rest of the day.",
                                        fontSize = 12.5.sp,
                                        lineHeight = 17.sp,
                                        color = Color(0xFF49454F)
                                    )
                                }
                                IconButton(
                                    onClick = { showTimerBanner = false },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = "Dismiss",
                                        tint = Color(0xFF49454F),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 6. App Usage Breakdown List
                val dayApps = currentDay?.apps ?: emptyList()
                if (dayApps.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No app activity recorded for this day",
                                fontSize = 13.5.sp,
                                color = Color(0xFF74777F)
                            )
                        }
                    }
                } else {
                    items(dayApps, key = { it.packageName }) { app ->
                        AppDigitalWellbeingRow(app = app, metric = selectedMetric)
                    }
                }
            }
        }
    }
}

@Composable
fun WeeklyHistogramChart(
    days: List<DayActivityData>,
    selectedIndex: Int,
    onSelectDay: (Int) -> Unit
) {
    val maxMinutes = days.maxOfOrNull { it.screenTimeMinutes }?.coerceAtLeast(180) ?: 180
    // Dynamic scale levels: 0h, max/3, 2*max/3, max
    val scaleLevel1 = (maxMinutes * 0.33f / 60).toInt().coerceAtLeast(2)
    val scaleLevel2 = (maxMinutes * 0.66f / 60).toInt().coerceAtLeast(scaleLevel1 + 2)
    val scaleLevel3 = (maxMinutes * 1.0f / 60).toInt().coerceAtLeast(scaleLevel2 + 2)

    val gridLabels = listOf(
        "${scaleLevel3}h",
        "${scaleLevel2}h",
        "${scaleLevel1}h",
        "0h"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
        ) {
            // Left chart area with gridlines and bars
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                // Background Horizontal Gridlines
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val canvasHeight = size.height - 24.dp.toPx()
                    val stepY = canvasHeight / 3f

                    for (i in 0..3) {
                        val y = i * stepY
                        drawLine(
                            color = Color(0xFFE0E2E8),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                }

                // 7 Vertical Bars
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom
                ) {
                    val dayNames = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
                    for (i in 0..6) {
                        val dayData = days.getOrNull(i)
                        val isSelected = (i == selectedIndex)
                        val isFuture = dayData?.isFuture == true
                        val minutes = dayData?.screenTimeMinutes ?: 0
                        val heightFraction = if (maxMinutes > 0 && !isFuture) {
                            (minutes.toFloat() / (scaleLevel3 * 60f)).coerceIn(0.04f, 1.0f)
                        } else 0f

                        val barColor = when {
                            isFuture -> Color.Transparent
                            isSelected -> Color(0xFF0B57D0) // Digital Wellbeing Deep Blue for selected
                            else -> Color(0xFFA8C7FA)       // Light pastel blue for other days
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    enabled = !isFuture
                                ) {
                                    onSelectDay(i)
                                }
                        ) {
                            if (!isFuture && heightFraction > 0f) {
                                Box(
                                    modifier = Modifier
                                        .width(22.dp)
                                        .fillMaxHeight(heightFraction)
                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                        .background(barColor)
                                )
                            } else {
                                Spacer(modifier = Modifier.height(1.dp))
                            }
                        }
                    }
                }

                // Day Labels at bottom (Sun, Mon, Tue, Wed, Thu, Fri, Sat)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val dayNames = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
                    for (i in 0..6) {
                        val isSelected = (i == selectedIndex)
                        Text(
                            text = dayNames[i],
                            fontSize = 11.5.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) Color(0xFF0B57D0) else Color(0xFF74777F),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Right side grid labels (0h, 6h, 12h, 18h)
            Column(
                modifier = Modifier
                    .width(32.dp)
                    .fillMaxHeight()
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                gridLabels.forEach { label ->
                    Text(
                        text = label,
                        fontSize = 10.sp,
                        color = Color(0xFF74777F)
                    )
                }
            }
        }
    }
}

@Composable
fun AppDigitalWellbeingRow(
    app: AppUsageStat,
    metric: MetricType
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // App Icon
        if (app.iconBitmap != null) {
            Image(
                bitmap = app.iconBitmap.asImageBitmap(),
                contentDescription = app.name,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(app.iconColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = app.icon,
                    contentDescription = app.name,
                    tint = app.iconColor,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        // App Name and Category
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                color = Color(0xFF1D1B20),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Metric Value Text
        val valueText = when (metric) {
            MetricType.SCREEN_TIME -> app.durationText
            MetricType.TIMES_OPENED -> "${app.openCount} opens"
            MetricType.NOTIFICATIONS -> "${(app.openCount * 1.5).toInt()} notifs"
        }

        Text(
            text = valueText,
            fontSize = 13.5.sp,
            color = Color(0xFF44474E),
            fontWeight = FontWeight.Normal
        )

        Spacer(modifier = Modifier.width(14.dp))

        // Digital Wellbeing Hourglass Timer Icon
        Icon(
            Icons.Outlined.HourglassEmpty,
            contentDescription = "Set Timer",
            tint = Color(0xFF74777F),
            modifier = Modifier
                .size(20.dp)
                .clickable { /* timer action */ }
        )
    }
}
