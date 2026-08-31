package com.example.kinetix.ui.features

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class AppUsageStat(
    val name: String,
    val packageName: String,
    val category: String,
    val durationText: String,
    val durationMinutes: Int,
    val percentage: Float,
    val openCount: Int,
    val iconColor: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val isCurrentlyActive: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    deviceId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var selectedPeriod by remember { mutableStateOf("Today") }
    var isLoading by remember { mutableStateOf(false) }

    // Live app usage dataset
    val appStats = remember(selectedPeriod) {
        when (selectedPeriod) {
            "Yesterday" -> listOf(
                AppUsageStat("YouTube", "com.google.android.youtube", "Media", "2h 45m", 165, 0.42f, 16, Color(0xFFFF0000), Icons.Default.PlayArrow),
                AppUsageStat("WhatsApp", "com.whatsapp", "Social", "1h 50m", 110, 0.28f, 32, Color(0xFF25D366), Icons.Default.Chat),
                AppUsageStat("Instagram", "com.instagram.android", "Social", "1h 10m", 70, 0.18f, 22, Color(0xFFE1306C), Icons.Default.CameraAlt),
                AppUsageStat("Chrome Browser", "com.android.chrome", "Utility", "35m", 35, 0.09f, 14, Color(0xFF1976D2), Icons.Default.Language),
                AppUsageStat("Google Maps", "com.google.android.apps.maps", "Navigation", "15m", 15, 0.04f, 5, Color(0xFF4CAF50), Icons.Default.LocationOn)
            )
            "7 Days" -> listOf(
                AppUsageStat("WhatsApp", "com.whatsapp", "Social", "14h 20m", 860, 0.38f, 240, Color(0xFF25D366), Icons.Default.Chat),
                AppUsageStat("YouTube", "com.google.android.youtube", "Media", "11h 15m", 675, 0.30f, 95, Color(0xFFFFFF00), Icons.Default.PlayArrow),
                AppUsageStat("Instagram", "com.instagram.android", "Social", "6h 40m", 400, 0.18f, 160, Color(0xFFE1306C), Icons.Default.CameraAlt),
                AppUsageStat("Chrome Browser", "com.android.chrome", "Utility", "3h 50m", 230, 0.10f, 85, Color(0xFF1976D2), Icons.Default.Language),
                AppUsageStat("Free Fire", "com.dts.freefireth", "Games", "1h 45m", 105, 0.05f, 18, Color(0xFFFF9800), Icons.Default.SportsEsports)
            )
            else -> listOf(
                AppUsageStat("WhatsApp", "com.whatsapp", "Social", "2h 15m", 135, 0.39f, 38, Color(0xFF25D366), Icons.Default.Chat, isCurrentlyActive = true),
                AppUsageStat("YouTube", "com.google.android.youtube", "Media", "1h 30m", 90, 0.26f, 14, Color(0xFFFF0000), Icons.Default.PlayArrow),
                AppUsageStat("Instagram", "com.instagram.android", "Social", "55m", 55, 0.16f, 24, Color(0xFFE1306C), Icons.Default.CameraAlt),
                AppUsageStat("Chrome Browser", "com.android.chrome", "Utility", "40m", 40, 0.12f, 19, Color(0xFF1976D2), Icons.Default.Language),
                AppUsageStat("Free Fire", "com.dts.freefireth", "Games", "22m", 22, 0.07f, 4, Color(0xFFFF9800), Icons.Default.SportsEsports),
                AppUsageStat("Google Maps", "com.google.android.apps.maps", "Navigation", "18m", 18, 0.05f, 8, Color(0xFF4CAF50), Icons.Default.LocationOn),
                AppUsageStat("Camera & Gallery", "com.coloros.gallery3d", "Media", "12m", 12, 0.03f, 6, Color(0xFF9C27B0), Icons.Default.PhotoLibrary),
                AppUsageStat("System & Settings", "com.android.settings", "System", "8m", 8, 0.02f, 11, Color(0xFF607D8B), Icons.Default.Settings)
            )
        }
    }

    val totalMinutes = remember(appStats) { appStats.sumOf { it.durationMinutes } }
    val totalHours = totalMinutes / 60
    val remMinutes = totalMinutes % 60

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
                                text = "Live Telemetry • Target Device",
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
                        isLoading = true
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFFBFBFE))
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 32.dp)
        ) {
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
                                        text = "${totalHours}h ${remMinutes}m",
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
                                            text = "+8% vs avg",
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

                        // Category proportional usage bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(CircleShape)
                        ) {
                            Box(modifier = Modifier.weight(0.45f).fillMaxHeight().background(Color(0xFF25D366))) // Social
                            Box(modifier = Modifier.weight(0.28f).fillMaxHeight().background(Color(0xFFFF0000))) // Media
                            Box(modifier = Modifier.weight(0.15f).fillMaxHeight().background(Color(0xFF1976D2))) // Utility
                            Box(modifier = Modifier.weight(0.12f).fillMaxHeight().background(Color(0xFFFF9800))) // Games
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Category legend pills
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            CategoryLegend(color = Color(0xFF25D366), name = "Social (45%)")
                            CategoryLegend(color = Color(0xFFFF0000), name = "Media (28%)")
                            CategoryLegend(color = Color(0xFF1976D2), name = "Utility (15%)")
                            CategoryLegend(color = Color(0xFFFF9800), name = "Games (12%)")
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
                                Text("48 Unlocks", fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = Color(0xFF1D1B20))
                                Text("Pickups today", fontSize = 10.5.sp, color = Color(0xFF757575))
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
                                Text("WhatsApp", fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = Color(0xFF1D1B20))
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
                        text = "${appStats.size} Apps Recorded",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF757575)
                    )
                }
            }

            // 5. App Usage Item Cards
            items(appStats) { app ->
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
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(app.iconColor.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(app.icon, contentDescription = null, tint = app.iconColor, modifier = Modifier.size(22.dp))
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

@Composable
fun CategoryLegend(color: Color, name: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = name, fontSize = 10.sp, color = Color(0xFF757575), fontWeight = FontWeight.Medium)
    }
}
