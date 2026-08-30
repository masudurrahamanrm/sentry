package com.example.kinetix.ui.features

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

data class LiveNotification(val app: String, val title: String, val body: String, val time: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(deviceId: String, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val notifications = remember { mutableStateListOf<LiveNotification>() }
    var isLoading by remember { mutableStateOf(true) }

    suspend fun fetchLiveNotifications() {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val client = com.example.kinetix.network.KinetixApiClient(context)
            val res = client.getNotifications(deviceId)
            if (res.isSuccess) {
                val arr = res.getOrNull()
                if (arr != null) {
                    val list = mutableListOf<LiveNotification>()
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val pkg = item.optString("packageName", "System")
                        val friendlyApp = when {
                            pkg.contains("whatsapp", ignoreCase = true) -> "WhatsApp"
                            pkg.contains("messaging", ignoreCase = true) || pkg.contains("mms", ignoreCase = true) -> "Messages"
                            pkg.contains("gm", ignoreCase = true) || pkg.contains("mail", ignoreCase = true) -> "Gmail"
                            pkg.contains("telegram", ignoreCase = true) -> "Telegram"
                            pkg.contains("dialer", ignoreCase = true) || pkg.contains("phone", ignoreCase = true) -> "Phone"
                            else -> pkg.substringAfterLast(".").capitalize()
                        }
                        list.add(
                            LiveNotification(
                                app = friendlyApp,
                                title = item.optString("title", "Alert"),
                                body = item.optString("body", ""),
                                time = "Just now"
                            )
                        )
                    }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        notifications.clear()
                        notifications.addAll(list)
                        isLoading = false
                    }
                }
            }
        }
    }

    LaunchedEffect(deviceId) {
        while (true) {
            fetchLiveNotifications()
            kotlinx.coroutines.delay(3000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Device Notifications", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        coroutineScope.launch {
                            fetchLiveNotifications()
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
                .padding(16.dp)
        ) {
            Text(
                text = "Live Stream from $deviceId",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (notifications.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(bottom = 64.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.NotificationsNone, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(modifier = Modifier.height(14.dp))
                        Text("No Live Notifications Yet", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Any incoming SMS, WhatsApp, or App alerts on $deviceId will appear here in real time.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(notifications) { notif ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(notif.app, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    }
                                    Text(notif.time, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(notif.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(notif.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
