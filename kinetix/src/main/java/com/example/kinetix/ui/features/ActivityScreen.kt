package com.example.kinetix.ui.features

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kinetix.network.KinetixApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
    val iconBitmap: Bitmap? = null,
    val isCurrentlyActive: Boolean = false
)

data class CategoryBreakdown(
    val socialPct: Int = 0,
    val mediaPct: Int = 0,
    val utilityPct: Int = 0,
    val gamesPct: Int = 0
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    deviceId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedPeriod by remember { mutableStateOf("Today") }
    var isLoading by remember { mutableStateOf(false) }

    var hasPermissionOnRemote by remember { mutableStateOf(true) }
    var liveApps by remember { mutableStateOf<List<AppUsageStat>>(emptyList()) }
    var totalScreenTimeDisplay by remember { mutableStateOf("0m") }
    var totalUnlocksCount by remember { mutableIntStateOf(0) }
    var topAppName by remember { mutableStateOf("None") }
    var categoryBreakdown by remember { mutableStateOf(CategoryBreakdown()) }

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
            lower.contains("gallery") -> "Photos & Gallery"
            lower.contains("camera") -> "Camera"
            lower.contains("settings") -> "Settings"
            lower.contains("calculator") -> "Calculator"
            lower.contains("contacts") || lower.contains("dialer") -> "Phone & Contacts"
            else -> packageName.substringAfterLast('.').replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }
    }

    fun parseAppsFromJson(appsArr: JSONArray?): List<AppUsageStat> {
        if (appsArr == null || appsArr.length() == 0) return emptyList()
        val list = mutableListOf<AppUsageStat>()

        for (i in 0 until appsArr.length()) {
            val obj = appsArr.optJSONObject(i) ?: continue
            val pkg = obj.optString("packageName", "")
            val rawName = obj.optString("name", "")
            val name = resolveProperAppName(pkg, rawName)
            val cat = obj.optString("category", "Utility")
            val durText = obj.optString("durationText", "0m")
            val durMins = obj.optInt("durationMinutes", 0)
            val pct = obj.optDouble("percentage", 0.0).toFloat().coerceIn(0.01f, 1.0f)
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
                    iconBitmap = iconBitmap,
                    isCurrentlyActive = (i == 0 && durMins > 0 && selectedPeriod == "Today")
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

                        val periodsObj = actObj.optJSONObject("periods")
                        val periodKey = when (selectedPeriod) {
                            "Yesterday" -> "yesterday"
                            "7 Days" -> "sevenDays"
                            else -> "today"
                        }

                        val currentPeriodObj = periodsObj?.optJSONObject(periodKey) ?: actObj
                        val appsArr = currentPeriodObj.optJSONArray("apps")
                        val screenTime = currentPeriodObj.optString("screenTime", "0m")
                        val unlocks = currentPeriodObj.optInt("unlocks", 0)
                        val top = resolveProperAppName("", currentPeriodObj.optString("topApp", "None"))

                        val catObj = currentPeriodObj.optJSONObject("categories")
                        val catBreakdown = if (catObj != null) {
                            CategoryBreakdown(
                                socialPct = catObj.optInt("socialPct", 0),
                                mediaPct = catObj.optInt("mediaPct", 0),
                                utilityPct = catObj.optInt("utilityPct", 0),
                                gamesPct = catObj.optInt("gamesPct", 0)
                            )
                        } else CategoryBreakdown()

                        val parsedApps = parseAppsFromJson(appsArr)

                        withContext(Dispatchers.Main) {
                            totalScreenTimeDisplay = screenTime
                            totalUnlocksCount = unlocks
                            topAppName = top
                            categoryBreakdown = catBreakdown
                            liveApps = parsedApps
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(deviceId, selectedPeriod) {
        fetchActivityData()
    }

    // Pulsing live indicator
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
                        Text("App Activity & Usage", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4CAF50).copy(alpha = pulseAlpha))
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Live Android Telemetry • Target Device",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.5.sp
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
                            isLoading = true
                            fetchActivityData()
                            isLoading = false
                        }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
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
                    .background(Color(0xFFFBFBFE))
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(top = 10.dp, bottom = 32.dp)
            ) {
                // Warning Banner if Remote Target Device needs Usage Access Permission
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
                                        text = "Grant 'Usage Access' in SentrY > Permissions or Settings > Special App Access > Usage Access on the remote device to receive real-time screen time.",
                                        fontSize = 11.sp,
                                        color = Color(0xFFB45309)
                                    )
                                }
                            }
                        }
                    }
                }

                // 1. Period Selector Chips (Today, Yesterday, 7 Days)
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFFEDE7F6))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        listOf("Today", "Yesterday", "7 Days").forEach { period ->
                            val isSelected = selectedPeriod == period
                            Surface(
                                onClick = { selectedPeriod = period },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) Color.White else Color.Transparent,
                                shadowElevation = if (isSelected) 2.dp else 0.dp,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = period,
                                        fontSize = 12.5.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) Color(0xFF673AB7) else Color(0xFF757575)
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. Screen Time Hero Card
                item {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color.White,
                        shadowElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Total Screen Time", fontSize = 12.sp, color = Color(0xFF757575), fontWeight = FontWeight.Medium)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        Text(
                                            text = totalScreenTimeDisplay,
                                            fontSize = 28.sp,
                                            fontWeight = FontWeight.Black,
                                            color = Color(0xFF1D1B20)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color(0xFFE8F5E9),
                                            modifier = Modifier.padding(bottom = 4.dp)
                                        ) {
                                            Text(
                                                text = if (totalScreenTimeDisplay != "0m") "Real Telemetry" else "Waiting sync",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF2E7D32),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFEDE7F6)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.HourglassBottom, contentDescription = null, tint = Color(0xFF673AB7), modifier = Modifier.size(24.dp))
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Dynamic category proportional usage bar
                            val sWeight = (categoryBreakdown.socialPct / 100f).coerceAtLeast(0.01f)
                            val mWeight = (categoryBreakdown.mediaPct / 100f).coerceAtLeast(0.01f)
                            val uWeight = (categoryBreakdown.utilityPct / 100f).coerceAtLeast(0.01f)
                            val gWeight = (categoryBreakdown.gamesPct / 100f).coerceAtLeast(0.01f)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFF0F0F0))
                            ) {
                                if (categoryBreakdown.socialPct > 0) {
                                    Box(modifier = Modifier.weight(sWeight).fillMaxHeight().background(Color(0xFF25D366)))
                                }
                                if (categoryBreakdown.mediaPct > 0) {
                                    Box(modifier = Modifier.weight(mWeight).fillMaxHeight().background(Color(0xFFFF0000)))
                                }
                                if (categoryBreakdown.utilityPct > 0) {
                                    Box(modifier = Modifier.weight(uWeight).fillMaxHeight().background(Color(0xFF1976D2)))
                                }
                                if (categoryBreakdown.gamesPct > 0) {
                                    Box(modifier = Modifier.weight(gWeight).fillMaxHeight().background(Color(0xFFFF9800)))
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Category legend pills with REAL percentages
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                CategoryLegend(color = Color(0xFF25D366), name = "Social (${categoryBreakdown.socialPct}%)")
                                CategoryLegend(color = Color(0xFFFF0000), name = "Media (${categoryBreakdown.mediaPct}%)")
                                CategoryLegend(color = Color(0xFF1976D2), name = "Utility (${categoryBreakdown.utilityPct}%)")
                                CategoryLegend(color = Color(0xFFFF9800), name = "Games (${categoryBreakdown.gamesPct}%)")
                            }
                        }
                    }
                }

                // 3. Quick Metrics Row (Device Unlocks & Most Used App)
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color.White,
                            shadowElevation = 1.dp,
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFE3F2FD)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.LockOpen, contentDescription = null, tint = Color(0xFF1976D2), modifier = Modifier.size(18.dp))
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text("$totalUnlocksCount Unlocks", fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = Color(0xFF1D1B20))
                                    Text("Pickups $selectedPeriod", fontSize = 10.5.sp, color = Color(0xFF757575))
                                }
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color.White,
                            shadowElevation = 1.dp,
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFE8F5E9)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(18.dp))
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(topAppName, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = Color(0xFF1D1B20), maxLines = 1)
                                    Text("Top used app", fontSize = 10.5.sp, color = Color(0xFF757575))
                                }
                            }
                        }
                    }
                }

                // 4. App Usage Breakdown Header
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "App Usage Breakdown",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.5.sp,
                            color = Color(0xFF1D1B20)
                        )
                        Text(
                            text = "${liveApps.size} Apps Recorded",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF757575)
                        )
                    }
                }

                if (liveApps.isEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color.White,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(32.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Outlined.QueryStats,
                                    contentDescription = null,
                                    tint = Color(0xFF9E9E9E),
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "No app usage recorded for $selectedPeriod",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF757575)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Pull down or refresh to sync latest telemetry from SentrY",
                                    fontSize = 11.sp,
                                    color = Color(0xFFBDBDBD),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                } else {
                    // 5. App Usage Item Cards (with Real App Icons)
                    items(liveApps, key = { "${it.packageName}_${it.durationMinutes}" }) { app ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color.White,
                            shadowElevation = 1.5.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Box(
                                            modifier = Modifier
                                                .size(42.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(app.iconColor.copy(alpha = 0.12f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (app.iconBitmap != null) {
                                                Image(
                                                    bitmap = app.iconBitmap.asImageBitmap(),
                                                    contentDescription = app.name,
                                                    modifier = Modifier
                                                        .size(32.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                )
                                            } else {
                                                Icon(app.icon, contentDescription = null, tint = app.iconColor, modifier = Modifier.size(24.dp))
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = app.name,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 14.sp,
                                                    color = Color(0xFF1D1B20)
                                                )
                                                if (app.isCurrentlyActive) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Surface(
                                                        shape = RoundedCornerShape(6.dp),
                                                        color = Color(0xFFE8F5E9)
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(Color(0xFF2E7D32)))
                                                            Spacer(modifier = Modifier.width(3.dp))
                                                            Text("In Use", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                                                        }
                                                    }
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "${app.category} • ${app.openCount} opens",
                                                fontSize = 11.sp,
                                                color = Color(0xFF757575)
                                            )
                                        }
                                    }

                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = app.durationText,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 14.sp,
                                            color = Color(0xFF1D1B20)
                                        )
                                        Text(
                                            text = "${(app.percentage * 100).toInt()}% of time",
                                            fontSize = 10.5.sp,
                                            color = Color(0xFF757575)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Usage Progress bar
                                LinearProgressIndicator(
                                    progress = { app.percentage },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(5.dp)
                                        .clip(CircleShape),
                                    color = app.iconColor,
                                    trackColor = Color(0xFFF0F0F0)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryLegend(color: Color, name: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = name, fontSize = 10.sp, color = Color(0xFF757575), fontWeight = FontWeight.Medium)
    }
}
