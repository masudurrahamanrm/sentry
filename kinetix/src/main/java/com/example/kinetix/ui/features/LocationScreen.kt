package com.example.kinetix.ui.features

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kinetix.network.KinetixApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import androidx.compose.material3.pulltorefresh.PullToRefreshBox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationScreen(deviceId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var latitude by remember { mutableDoubleStateOf(22.5726) }
    var longitude by remember { mutableDoubleStateOf(88.3639) }
    var accuracy by remember { mutableDoubleStateOf(3.0) }
    var altitude by remember { mutableDoubleStateOf(14.0) }
    var speed by remember { mutableDoubleStateOf(0.0) }
    var address by remember { mutableStateOf("Live GPS Location") }
    var isFetching by remember { mutableStateOf(false) }
    var lastFixTime by remember { mutableStateOf("Just now") }

    suspend fun refreshLocation() {
        withContext(Dispatchers.IO) {
            try {
                val client = KinetixApiClient(context)
                val res = client.getDeviceLocation(deviceId)
                if (res.isSuccess) {
                    val locObj = res.getOrNull()
                    if (locObj != null) {
                        latitude = locObj.optDouble("latitude", 22.5726)
                        longitude = locObj.optDouble("longitude", 88.3639)
                        accuracy = locObj.optDouble("accuracy", 3.0)
                        altitude = locObj.optDouble("altitude", 14.0)
                        speed = locObj.optDouble("speed", 0.0)
                        address = locObj.optString("address", "Live GPS Location")
                        lastFixTime = "Just now (Live streaming)"
                    }
                }
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(deviceId) {
        while (true) {
            refreshLocation()
            delay(3000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Live Location", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        coroutineScope.launch {
                            isFetching = true
                            refreshLocation()
                            delay(400)
                            isFetching = false
                        }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isFetching,
            onRefresh = {
                coroutineScope.launch {
                    isFetching = true
                    refreshLocation()
                    delay(400)
                    isFetching = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                // Live Status Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF1E293B)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(
                            Icons.Default.GpsFixed,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            address,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Accuracy: ±${accuracy.toInt()}m • GPS Live Fix",
                            color = Color(0xFF94A3B8),
                            fontSize = 13.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Coordinates Details Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Telemetry Coordinates", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))

                        DetailRow("Latitude", String.format("%.6f°", latitude))
                        DetailRow("Longitude", String.format("%.6f°", longitude))
                        DetailRow("Accuracy", "±${accuracy.toInt()} meters")
                        DetailRow("Altitude", String.format("%.1f m Above Sea Level", altitude))
                        DetailRow("Speed", String.format("%.1f km/h", speed))
                        DetailRow("Provider", "GPS / FusedLocationProvider")
                        DetailRow("Device ID", deviceId)
                        DetailRow("Status", lastFixTime)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        coroutineScope.launch {
                            isFetching = true
                            refreshLocation()
                            delay(600)
                            isFetching = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isFetching
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isFetching) "Acquiring Fix..." else "Refresh High-Precision GPS")
                }
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Text(value, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}
