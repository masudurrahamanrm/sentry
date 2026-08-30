package com.example.kinetix.ui.features

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FileEntry(val name: String, val path: String, val size: String, val isFolder: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(deviceId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var currentPath by remember { mutableStateOf("/sdcard") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val files = remember { mutableStateListOf<FileEntry>() }

    suspend fun fetchFiles() {
        withContext(Dispatchers.IO) {
            val client = com.example.kinetix.network.KinetixApiClient(context)
            val res = client.getFileList(deviceId)
            if (res.isSuccess) {
                val obj = res.getOrNull()
                if (obj != null) {
                    val serverPath = obj.optString("currentPath", "/sdcard")
                    val arr = obj.optJSONArray("files")
                    val items = mutableListOf<FileEntry>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val f = arr.getJSONObject(i)
                            items.add(
                                FileEntry(
                                    name = f.optString("name", "file"),
                                    path = f.optString("path", "$serverPath/file"),
                                    size = f.optString("size", "0 B"),
                                    isFolder = f.optBoolean("isFolder", false)
                                )
                            )
                        }
                    }
                    withContext(Dispatchers.Main) {
                        currentPath = serverPath
                        files.clear()
                        files.addAll(items)
                    }
                }
            }
        }
    }

    fun navigateTo(folderPath: String) {
        coroutineScope.launch {
            statusMessage = "Opening $folderPath..."
            withContext(Dispatchers.IO) {
                val client = com.example.kinetix.network.KinetixApiClient(context)
                client.exploreFolder(deviceId, folderPath)
            }
            delay(1500)
            fetchFiles()
            statusMessage = null
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            fetchFiles()
            delay(3500)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("File & Storage Explorer", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentPath != "/sdcard" && currentPath != "/storage/emulated/0") {
                            val parent = currentPath.substringBeforeLast('/', "/sdcard")
                            navigateTo(if (parent.isEmpty()) "/sdcard" else parent)
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { coroutineScope.launch { fetchFiles() } }) {
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
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(currentPath, fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 1)
                    }

                    if (currentPath != "/sdcard" && currentPath != "/storage/emulated/0") {
                        TextButton(onClick = {
                            val parent = currentPath.substringBeforeLast('/', "/sdcard")
                            navigateTo(if (parent.isEmpty()) "/sdcard" else parent)
                        }) {
                            Text("Up ⬆", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (statusMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(statusMessage!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (files.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading device files...", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(files) { item ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (item.isFolder) {
                                        navigateTo(item.path)
                                    }
                                },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(
                                        if (item.isFolder) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                        contentDescription = null,
                                        tint = if (item.isFolder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(26.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(item.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                        Text(item.size, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                                    }
                                }

                                if (item.isFolder) {
                                    Icon(
                                        Icons.Default.ChevronRight,
                                        contentDescription = "Open Folder",
                                        tint = MaterialTheme.colorScheme.outline
                                    )
                                } else {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    ) {
                                        Text(
                                            text = item.name.substringAfterLast('.', "FILE").uppercase(),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
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
}
