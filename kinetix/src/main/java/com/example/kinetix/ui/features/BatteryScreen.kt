package com.example.kinetix.ui.features

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import kotlinx.coroutines.launch

data class BatteryData(
    val level: Int = 100,
    val isCharging: Boolean = false,
    val chargingStatus: String = "Discharging on Battery",
    val temperature: String = "32.0 °C",
    val voltage: String = "4,150 mV",
    val health: String = "Good (Optimal)",
    val technology: String = "Li-ion",
    val powerSave: String = "Disabled"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryScreen(deviceId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var batteryData by remember { mutableStateOf(BatteryData()) }
    var isRefreshing by remember { mutableStateOf(false) }

    suspend fun fetchBatteryData() {
        withContext(Dispatchers.IO) {
            val client = com.example.kinetix.network.KinetixApiClient(context)
            val res = client.getBatteryTelemetry(deviceId)
            if (res.isSuccess) {
                val obj = res.getOrNull()
                if (obj != null) {
                    withContext(Dispatchers.Main) {
                        batteryData = BatteryData(
                            level = obj.optInt("level", 100),
                            isCharging = obj.optBoolean("isCharging", false),
                            chargingStatus = obj.optString("chargingStatus", "Discharging on Battery"),
                            temperature = obj.optString("temperature", "33.5 °C"),
                            voltage = obj.optString("voltage", "4,180 mV"),
                            health = obj.optString("health", "Good (Optimal)"),
                            technology = obj.optString("technology", "Li-ion"),
                            powerSave = obj.optString("powerSave", "Disabled")
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            fetchBatteryData()
            delay(3000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Battery & Hardware Health", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        coroutineScope.launch {
                            isRefreshing = true
                            fetchBatteryData()
                            isRefreshing = false
                        }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                coroutineScope.launch {
                    isRefreshing = true
                    fetchBatteryData()
                    isRefreshing = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                val isCharging = batteryData.isCharging
                val level = batteryData.level

                val tintColor = when {
                    isCharging -> Color(0xFF2E7D32)
                    level <= 20 -> Color(0xFFD32F2F)
                    level <= 40 -> Color(0xFFF57C00)
                    else -> MaterialTheme.colorScheme.primary
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isCharging) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            if (isCharging) Icons.Default.BatteryChargingFull else if (level <= 20) Icons.Default.BatteryAlert else Icons.Default.BatteryFull,
                            contentDescription = null,
                            tint = tintColor,
                            modifier = Modifier.size(68.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("$level%", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = tintColor)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(batteryData.chargingStatus, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = tintColor)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Hardware Telemetry", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))

                        DetailRow("Battery Health", batteryData.health)
                        DetailRow("Temperature", batteryData.temperature)
                        DetailRow("Voltage", batteryData.voltage)
                        DetailRow("Battery Technology", batteryData.technology)
                        DetailRow("Power Saving Mode", batteryData.powerSave)
                        DetailRow("Live Telemetry Status", "Streaming over Cloud")
                    }
                }
            }
        }
    }
}
