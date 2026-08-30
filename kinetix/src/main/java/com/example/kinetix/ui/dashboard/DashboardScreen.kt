package com.example.kinetix.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

data class PairedDeviceItem(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val osVersion: String,
    val isOnline: Boolean,
    val lastSeenText: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToDiscovery: () -> Unit,
    onNavigateToDeviceDetail: (String) -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pairedDevices = remember { mutableStateListOf<PairedDeviceItem>() }
    var isRefreshing by remember { mutableStateOf(false) }
    var isConnected by remember { mutableStateOf(false) }

    suspend fun fetchDevices() {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val client = com.example.kinetix.network.KinetixApiClient(context)
            val regRes = client.registerDevice()
            if (regRes.isSuccess) {
                isConnected = true
            }
            val devicesRes = client.listAvailableDevices()
            if (devicesRes.isSuccess) {
                val arr = devicesRes.getOrNull()
                if (arr != null) {
                    val newList = mutableListOf<PairedDeviceItem>()
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val devId = if (item.has("deviceId")) item.getString("deviceId") else item.optString("device_id", "")
                        val name = if (item.has("deviceName")) item.getString("deviceName") else item.optString("device_name", "Sentry Device")
                        val platform = if (item.has("platform")) item.getString("platform") else "Android"
                        val osVer = if (item.has("osVersion")) item.getString("osVersion") else item.optString("os_version", "Android 14")
                        val status = if (item.has("status")) item.getString("status") else "ONLINE"

                        // Only display Sentry agents (prefix SN-)
                        if (devId.startsWith("SN")) {
                            newList.add(
                                PairedDeviceItem(
                                    deviceId = devId,
                                    deviceName = name,
                                    platform = platform,
                                    osVersion = osVer,
                                    isOnline = status.equals("ONLINE", ignoreCase = true),
                                    lastSeenText = "Online now"
                                )
                            )
                        }
                    }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        pairedDevices.clear()
                        pairedDevices.addAll(newList)
                    }
                }
            }
        }
    }

    val coroutineScope = rememberCoroutineScope()

    // Auto-refresh every 3 seconds so new phones appear instantly without pressing anything
    LaunchedEffect(Unit) {
        while (true) {
            fetchDevices()
            kotlinx.coroutines.delay(3000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Kinetix Control",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Text(
                            text = "Personal Device Gateway",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                isRefreshing = true
                                fetchDevices()
                                isRefreshing = false
                            }
                        }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Devices")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    coroutineScope.launch {
                        fetchDevices()
                    }
                },
                icon = { Icon(Icons.Default.Refresh, contentDescription = "Scan Devices") },
                text = { Text("Scan Devices") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // Header Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Discovered Devices (${pairedDevices.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (pairedDevices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No Sentry Devices Found",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Install and open Sentry on your second phone. It will automatically appear here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 32.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(pairedDevices) { device ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigateToDeviceDetail(device.deviceId) },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.PhoneAndroid,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }

                                Spacer(modifier = Modifier.width(16.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = device.deviceName,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        // Status dot
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(if (device.isOnline) Color(0xFF4CAF50) else Color(0xFF9E9E9E))
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Text(
                                        text = device.deviceId,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium
                                    )

                                    Spacer(modifier = Modifier.height(2.dp))

                                    Text(
                                        text = "${device.osVersion} • ${device.lastSeenText}",
                                        style = MaterialTheme.typography.bodySmall,
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
