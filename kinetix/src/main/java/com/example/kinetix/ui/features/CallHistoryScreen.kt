package com.example.kinetix.ui.features

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kinetix.network.KinetixApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class CallLogItem(
    val id: String,
    val name: String?,
    val number: String,
    val type: CallType,
    val date: String,
    val duration: String,
    val timestamp: Long = System.currentTimeMillis()
)

enum class CallType(val label: String, val color: Color) {
    INCOMING("Incoming", Color(0xFF10B981)),
    OUTGOING("Outgoing", Color(0xFF2563EB)),
    MISSED("Missed", Color(0xFFEF4444)),
    REJECTED("Rejected", Color(0xFFF97316))
}

enum class CallFilter { ALL, MISSED, INCOMING, OUTGOING }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallHistoryScreen(deviceId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val callLogs = remember { mutableStateListOf<CallLogItem>() }
    var activeFilter by remember { mutableStateOf(CallFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var isRefreshing by remember { mutableStateOf(false) }

    suspend fun fetchCalls() {
        withContext(Dispatchers.IO) {
            try {
                val client = KinetixApiClient(context)
                val res = client.getCalls(deviceId)
                if (res.isSuccess) {
                    val arr = res.getOrNull()
                    if (arr != null && arr.length() > 0) {
                        val list = mutableListOf<CallLogItem>()
                        for (i in 0 until arr.length()) {
                            val item = arr.getJSONObject(i)
                            val rawType = item.optString("type", "INCOMING").uppercase()
                            val cType = when {
                                rawType.contains("MISSED") -> CallType.MISSED
                                rawType.contains("OUT") -> CallType.OUTGOING
                                rawType.contains("REJECT") -> CallType.REJECTED
                                else -> CallType.INCOMING
                            }
                            list.add(
                                CallLogItem(
                                    id = item.optString("id", "call_$i"),
                                    name = item.optString("name").takeIf { n -> n.isNotBlank() && n != "null" && n != "Unknown" },
                                    number = item.optString("number", "+1 (555) 019-2834"),
                                    type = cType,
                                    date = item.optString("date", "Today, 10:45 AM"),
                                    duration = item.optString("duration", if (cType == CallType.MISSED) "Missed" else "2m 14s"),
                                    timestamp = item.optLong("timestamp", System.currentTimeMillis() - i * 3600000)
                                )
                            )
                        }
                        withContext(Dispatchers.Main) {
                            callLogs.clear()
                            callLogs.addAll(list)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // Auto-refresh periodic loop
    LaunchedEffect(deviceId) {
        while (true) {
            fetchCalls()
            delay(4000)
        }
    }

    val filteredCalls = remember(callLogs.size, activeFilter, searchQuery) {
        callLogs.filter { call ->
            val matchesFilter = when (activeFilter) {
                CallFilter.ALL -> true
                CallFilter.MISSED -> call.type == CallType.MISSED || call.type == CallType.REJECTED
                CallFilter.INCOMING -> call.type == CallType.INCOMING
                CallFilter.OUTGOING -> call.type == CallType.OUTGOING
            }
            val matchesSearch = searchQuery.isBlank() ||
                    (call.name?.contains(searchQuery, ignoreCase = true) == true) ||
                    call.number.contains(searchQuery, ignoreCase = true)
            matchesFilter && matchesSearch
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Call History",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                isRefreshing = true
                                fetchCalls()
                                delay(600)
                                isRefreshing = false
                            }
                        }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Sync Call Logs")
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
            // SEARCH BAR
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search by name or number...", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.outline) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            )

            Spacer(modifier = Modifier.height(6.dp))

            // FILTER TABS
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CallFilter.entries.forEach { filter ->
                    val isSelected = activeFilter == filter
                    val count = when (filter) {
                        CallFilter.ALL -> callLogs.size
                        CallFilter.MISSED -> callLogs.count { it.type == CallType.MISSED || it.type == CallType.REJECTED }
                        CallFilter.INCOMING -> callLogs.count { it.type == CallType.INCOMING }
                        CallFilter.OUTGOING -> callLogs.count { it.type == CallType.OUTGOING }
                    }
                    FilterChip(
                        selected = isSelected,
                        onClick = { activeFilter = filter },
                        label = {
                            Text(
                                "${filter.name.lowercase().replaceFirstChar { it.uppercase() }} ($count)",
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // SUMMARY METRICS BAR
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CallStatMiniPill(
                    modifier = Modifier.weight(1f),
                    label = "Total Logs",
                    value = "${callLogs.size}",
                    color = MaterialTheme.colorScheme.primary
                )
                CallStatMiniPill(
                    modifier = Modifier.weight(1f),
                    label = "Incoming",
                    value = "${callLogs.count { it.type == CallType.INCOMING }}",
                    color = Color(0xFF10B981)
                )
                CallStatMiniPill(
                    modifier = Modifier.weight(1f),
                    label = "Missed",
                    value = "${callLogs.count { it.type == CallType.MISSED }}",
                    color = Color(0xFFEF4444)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // CALL LOG LIST WITH PULL TO REFRESH
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    coroutineScope.launch {
                        isRefreshing = true
                        fetchCalls()
                        delay(600)
                        isRefreshing = false
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (filteredCalls.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Outlined.PhoneMissed,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                "No call logs found",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Syncing telemetry from remote device...",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredCalls, key = { it.id }) { item ->
                            CallLogCard(item = item, context = context)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CallStatMiniPill(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    color: Color
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.1f),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = color)
            Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun CallLogCard(item: CallLogItem, context: Context) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                // Call Type Icon Pill
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(item.type.color.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        when (item.type) {
                            CallType.INCOMING -> Icons.Default.CallReceived
                            CallType.OUTGOING -> Icons.Default.CallMade
                            CallType.MISSED -> Icons.Default.CallMissed
                            CallType.REJECTED -> Icons.Default.PhoneDisabled
                        },
                        contentDescription = item.type.label,
                        tint = item.type.color,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = item.name ?: item.number,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.name != null) {
                        Text(
                            text = item.number,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${item.date} • ",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = item.duration,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = item.type.color
                        )
                    }
                }
            }

            // Quick Actions: Copy Number & Direct Dial
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Phone Number", item.number)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Copied ${item.number} to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = "Copy Number",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(17.dp)
                    )
                }

                IconButton(
                    onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${item.number}"))
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(context, "Unable to launch dialer", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                ) {
                    Icon(
                        Icons.Default.Phone,
                        contentDescription = "Dial Number",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }
    }
}
