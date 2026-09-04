package com.example.kinetix.ui.features

import android.content.Context
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
import androidx.compose.material.icons.outlined.Timer
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
    val notifCount: Int,
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
    val totalOpens: Int,
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
    val prefs = remember { context.getSharedPreferences("kinetix_app_timers", Context.MODE_PRIVATE) }

    var isLoading by remember { mutableStateOf(false) }
    var hasPermissionOnRemote by remember { mutableStateOf(true) }
    var selectedMetric by remember { mutableStateOf(MetricType.SCREEN_TIME) }
    var showMetricDropdown by remember { mutableStateOf(false) }
    var showOptionsMenu by remember { mutableStateOf(false) }
    var showTimerBanner by remember { mutableStateOf(true) }

    // 7-day data
    var weeklyDays by remember { mutableStateOf<List<DayActivityData>>(emptyList()) }
    var selectedDayIndex by remember { mutableIntStateOf(5) } // Defaults to Friday / current day

    // Active Timer Dialog State
    var timerDialogApp by remember { mutableStateOf<AppUsageStat?>(null) }
    var activeTimersMap by remember { mutableStateOf(loadActiveTimers(prefs)) }

    // App Detail Sheet State
    var detailSheetApp by remember { mutableStateOf<AppUsageStat?>(null) }

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
            val notifs = (opens * 1.5).toInt().coerceAtLeast(1)
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
                    notifCount = notifs,
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
                                val totalOpens = dObj.optInt("totalOpens", apps.sumOf { it.openCount })

                                parsedDays.add(
                                    DayActivityData(
                                        dayOfWeek = dayOfWeek,
                                        dateLabel = dateLabel,
                                        screenTimeText = durText,
                                        screenTimeMinutes = durMins,
                                        totalOpens = totalOpens,
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
                                        totalOpens = if (isToday) todayApps.sumOf { it.openCount } else 0,
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

    // Sort apps according to selected metric
    val sortedDayApps = remember(currentDay, selectedMetric) {
        val apps = currentDay?.apps ?: emptyList()
        when (selectedMetric) {
            MetricType.SCREEN_TIME -> apps.sortedByDescending { it.durationMinutes }
            MetricType.TIMES_OPENED -> apps.sortedByDescending { it.openCount }
            MetricType.NOTIFICATIONS -> apps.sortedByDescending { it.notifCount }
        }
    }

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
                            MetricType.TIMES_OPENED -> "${currentDay?.totalOpens ?: 0} times opened"
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
                        metric = selectedMetric,
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
                if (sortedDayApps.isEmpty()) {
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
                    items(sortedDayApps, key = { it.packageName }) { app ->
                        val timerMinutes = activeTimersMap[app.packageName]
                        AppDigitalWellbeingRow(
                            app = app,
                            metric = selectedMetric,
                            timerMinutes = timerMinutes,
                            onTimerClick = { timerDialogApp = app },
                            onRowClick = { detailSheetApp = app }
                        )
                    }
                }
            }
        }
    }

    // App Timer Setup Dialog
    if (timerDialogApp != null) {
        val app = timerDialogApp!!
        val currentTimer = activeTimersMap[app.packageName] ?: 0

        AppTimerDialog(
            app = app,
            initialMinutes = currentTimer,
            onDismiss = { timerDialogApp = null },
            onSetTimer = { minutes ->
                if (minutes > 0) {
                    prefs.edit().putInt(app.packageName, minutes).apply()
                } else {
                    prefs.edit().remove(app.packageName).apply()
                }
                activeTimersMap = loadActiveTimers(prefs)
                timerDialogApp = null
            },
            onDeleteTimer = {
                prefs.edit().remove(app.packageName).apply()
                activeTimersMap = loadActiveTimers(prefs)
                timerDialogApp = null
            }
        )
    }

    // App Detail Bottom Sheet
    if (detailSheetApp != null) {
        val app = detailSheetApp!!
        val timerMinutes = activeTimersMap[app.packageName]

        ModalBottomSheet(
            onDismissRequest = { detailSheetApp = null },
            containerColor = Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (app.iconBitmap != null) {
                        Image(
                            bitmap = app.iconBitmap.asImageBitmap(),
                            contentDescription = app.name,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(app.iconColor.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(app.icon, contentDescription = null, tint = app.iconColor, modifier = Modifier.size(26.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(app.name, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF1D1B20))
                        Text(app.packageName, fontSize = 12.sp, color = Color(0xFF74777F))
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFF3F4F9),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Screen Time", fontSize = 11.5.sp, color = Color(0xFF74777F))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(app.durationText, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFF1D1B20))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Times Opened", fontSize = 11.5.sp, color = Color(0xFF74777F))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("${app.openCount} opens", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFF1D1B20))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Category", fontSize = 11.5.sp, color = Color(0xFF74777F))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(app.category, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFF1D1B20))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        detailSheetApp = null
                        timerDialogApp = app
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0B57D0)),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Icon(Icons.Outlined.Timer, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (timerMinutes != null && timerMinutes > 0) "Edit App Timer (${timerMinutes}m)" else "Set App Timer")
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

private fun loadActiveTimers(prefs: android.content.SharedPreferences): Map<String, Int> {
    val map = mutableMapOf<String, Int>()
    for ((k, v) in prefs.all) {
        if (v is Int && v > 0) {
            map[k] = v
        }
    }
    return map
}

@Composable
fun WeeklyHistogramChart(
    days: List<DayActivityData>,
    selectedIndex: Int,
    metric: MetricType,
    onSelectDay: (Int) -> Unit
) {
    val maxValue = when (metric) {
        MetricType.SCREEN_TIME -> days.maxOfOrNull { it.screenTimeMinutes }?.coerceAtLeast(180) ?: 180
        MetricType.TIMES_OPENED -> days.maxOfOrNull { it.totalOpens }?.coerceAtLeast(40) ?: 40
        MetricType.NOTIFICATIONS -> days.maxOfOrNull { it.unlocks }?.coerceAtLeast(30) ?: 30
    }

    val gridLabels = when (metric) {
        MetricType.SCREEN_TIME -> {
            val scale1 = (maxValue * 0.33f / 60).toInt().coerceAtLeast(2)
            val scale2 = (maxValue * 0.66f / 60).toInt().coerceAtLeast(scale1 + 2)
            val scale3 = (maxValue * 1.0f / 60).toInt().coerceAtLeast(scale2 + 2)
            listOf("${scale3}h", "${scale2}h", "${scale1}h", "0h")
        }
        else -> {
            val scale1 = (maxValue * 0.33f).toInt().coerceAtLeast(10)
            val scale2 = (maxValue * 0.66f).toInt().coerceAtLeast(scale1 + 10)
            val scale3 = (maxValue * 1.0f).toInt().coerceAtLeast(scale2 + 10)
            listOf("$scale3", "$scale2", "$scale1", "0")
        }
    }

    val maxScaleNumber = when (metric) {
        MetricType.SCREEN_TIME -> (gridLabels.first().removeSuffix("h").toIntOrNull() ?: 3) * 60
        else -> gridLabels.first().toIntOrNull() ?: 30
    }

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
                    for (i in 0..6) {
                        val dayData = days.getOrNull(i)
                        val isSelected = (i == selectedIndex)
                        val isFuture = dayData?.isFuture == true

                        val metricValue = when (metric) {
                            MetricType.SCREEN_TIME -> dayData?.screenTimeMinutes ?: 0
                            MetricType.TIMES_OPENED -> dayData?.totalOpens ?: 0
                            MetricType.NOTIFICATIONS -> dayData?.unlocks ?: 0
                        }

                        val heightFraction = if (maxScaleNumber > 0 && !isFuture) {
                            (metricValue.toFloat() / maxScaleNumber.toFloat()).coerceIn(0.04f, 1.0f)
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
                    .width(36.dp)
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
    metric: MetricType,
    timerMinutes: Int?,
    onTimerClick: () -> Unit,
    onRowClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRowClick)
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

        // App Name and Timer Status
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                color = Color(0xFF1D1B20),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (timerMinutes != null && timerMinutes > 0) {
                Text(
                    text = "${timerMinutes}m daily timer",
                    fontSize = 11.5.sp,
                    color = Color(0xFF0B57D0),
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Metric Value Text
        val valueText = when (metric) {
            MetricType.SCREEN_TIME -> app.durationText
            MetricType.TIMES_OPENED -> "${app.openCount} opens"
            MetricType.NOTIFICATIONS -> "${app.notifCount} notifs"
        }

        Text(
            text = valueText,
            fontSize = 13.5.sp,
            color = Color(0xFF44474E),
            fontWeight = FontWeight.Normal
        )

        Spacer(modifier = Modifier.width(14.dp))

        // Digital Wellbeing Hourglass Timer Icon
        IconButton(
            onClick = onTimerClick,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Outlined.HourglassEmpty,
                contentDescription = "Set Timer",
                tint = if (timerMinutes != null && timerMinutes > 0) Color(0xFF0B57D0) else Color(0xFF74777F),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun AppTimerDialog(
    app: AppUsageStat,
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onSetTimer: (Int) -> Unit,
    onDeleteTimer: () -> Unit
) {
    var hours by remember { mutableIntStateOf(initialMinutes / 60) }
    var minutes by remember { mutableIntStateOf(initialMinutes % 60) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Set timer for ${app.name}",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "App will pause when daily timer ends.",
                    fontSize = 12.5.sp,
                    color = Color(0xFF74777F)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Hours Picker
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Hours", fontSize = 12.sp, color = Color(0xFF74777F))
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { if (hours > 0) hours-- }, modifier = Modifier.size(32.dp)) {
                                Text("-", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            }
                            Text("$hours hr", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 8.dp))
                            IconButton(onClick = { if (hours < 12) hours++ }, modifier = Modifier.size(32.dp)) {
                                Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(20.dp))

                    // Minutes Picker (5m intervals)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Minutes", fontSize = 12.sp, color = Color(0xFF74777F))
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { if (minutes >= 5) minutes -= 5 }, modifier = Modifier.size(32.dp)) {
                                Text("-", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            }
                            Text("$minutes min", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 8.dp))
                            IconButton(onClick = { if (minutes <= 50) minutes += 5 }, modifier = Modifier.size(32.dp)) {
                                Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSetTimer(hours * 60 + minutes) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0B57D0))
            ) {
                Text("Set timer")
            }
        },
        dismissButton = {
            Row {
                if (initialMinutes > 0) {
                    TextButton(onClick = onDeleteTimer) {
                        Text("Delete timer", color = Color(0xFFBA1A1A))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color(0xFF44474E))
                }
            }
        },
        containerColor = Color.White
    )
}
