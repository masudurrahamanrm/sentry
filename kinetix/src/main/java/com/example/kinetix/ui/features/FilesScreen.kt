package com.example.kinetix.ui.features

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import androidx.compose.material3.pulltorefresh.PullToRefreshBox

data class FileEntry(
    val name: String,
    val path: String,
    val size: String,
    val isFolder: Boolean,
    val modified: String = "Recent"
)

data class StorageStats(
    val total: String = "128 GB",
    val free: String = "48.2 GB",
    val used: String = "79.8 GB",
    val percent: Int = 62
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(deviceId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    fun parseFilesJson(arr: org.json.JSONArray, serverPath: String): List<FileEntry> {
        val items = mutableListOf<FileEntry>()
        for (i in 0 until arr.length()) {
            val f = arr.getJSONObject(i)
            items.add(
                FileEntry(
                    name = f.optString("name", "file"),
                    path = f.optString("path", "$serverPath/file"),
                    size = f.optString("size", "0 B"),
                    isFolder = f.optBoolean("isFolder", false),
                    modified = f.optString("modified", "Recent")
                )
            )
        }
        return items
    }

    var currentPath by remember { mutableStateOf("/sdcard") }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var storageStats by remember { mutableStateOf(StorageStats()) }
    val files = remember(deviceId, currentPath) {
        mutableStateListOf<FileEntry>().apply {
            val cachedArr = com.example.kinetix.cache.KinetixDeviceCache.getCachedFiles(context, deviceId, currentPath)
            if (cachedArr.length() > 0) {
                addAll(parseFilesJson(cachedArr, currentPath))
            }
        }
    }

    // Selected file for preview & download bottom sheet
    var selectedFile by remember { mutableStateOf<FileEntry?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadedImageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    suspend fun fetchFiles() {
        withContext(Dispatchers.IO) {
            val client = com.example.kinetix.network.KinetixApiClient(context)
            val res = client.getFileList(deviceId)
            if (res.isSuccess) {
                val obj = res.getOrNull()
                if (obj != null) {
                    val serverPath = obj.optString("currentPath", "/sdcard")
                    val arr = obj.optJSONArray("files")
                    val statsObj = obj.optJSONObject("storageStats")

                    if (arr != null && arr.length() > 0) {
                        com.example.kinetix.cache.KinetixDeviceCache.saveCachedFiles(context, deviceId, serverPath, arr)
                    }

                    val items = if (arr != null) parseFilesJson(arr, serverPath) else emptyList()

                    val newStats = if (statsObj != null) {
                        StorageStats(
                            total = statsObj.optString("total", "128 GB"),
                            free = statsObj.optString("free", "48.2 GB"),
                            used = statsObj.optString("used", "79.8 GB"),
                            percent = statsObj.optInt("percent", 62)
                        )
                    } else StorageStats()

                    withContext(Dispatchers.Main) {
                        currentPath = serverPath
                        storageStats = newStats
                        if (files.isEmpty()) {
                            files.addAll(items)
                        } else if (items.isNotEmpty()) {
                            files.clear()
                            files.addAll(items)
                        }
                    }
                }
            }
        }
    }

    fun navigateTo(folderPath: String) {
        coroutineScope.launch {
            isLoading = true
            statusMessage = "Opening folder..."
            withContext(Dispatchers.IO) {
                val client = com.example.kinetix.network.KinetixApiClient(context)
                client.exploreFolder(deviceId, folderPath)
            }
            delay(1200)
            fetchFiles()
            isLoading = false
            statusMessage = null
        }
    }

    fun downloadSelectedFile(file: FileEntry) {
        coroutineScope.launch {
            isDownloading = true
            statusMessage = "Requesting ${file.name} from device..."
            withContext(Dispatchers.IO) {
                val client = com.example.kinetix.network.KinetixApiClient(context)
                client.requestFileDownload(deviceId, file.path)
            }

            // Poll for downloaded content
            var fetched = false
            for (attempt in 1..8) {
                delay(1200)
                withContext(Dispatchers.IO) {
                    val client = com.example.kinetix.network.KinetixApiClient(context)
                    val res = client.getDownloadedFile(deviceId, file.path)
                    if (res.isSuccess) {
                        val fObj = res.getOrNull()?.optJSONObject("file")
                        val b64 = fObj?.optString("base64")
                        if (!b64.isNullOrBlank()) {
                            fetched = true
                            try {
                                val bytes = Base64.decode(b64, Base64.DEFAULT)
                                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                withContext(Dispatchers.Main) {
                                    downloadedImageBitmap = bmp
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
                if (fetched) break
            }

            isDownloading = false
            statusMessage = if (fetched) "File downloaded successfully!" else "Download ready!"
            delay(2500)
            statusMessage = null
        }
    }

    LaunchedEffect(deviceId) {
        while (true) {
            fetchFiles()
            delay(3500)
        }
    }

    val filteredFiles = remember(files.toList(), searchQuery) {
        if (searchQuery.isBlank()) {
            files.toList()
        } else {
            files.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
    }

    val folderCount = remember(filteredFiles) { filteredFiles.count { it.isFolder } }
    val fileCount = remember(filteredFiles) { filteredFiles.count { !it.isFolder } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Storage & File Explorer",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 18.sp,
                            color = Color(0xFF1D1B20)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF4CAF50)))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Live Storage Engine Active",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF2E7D32),
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentPath != "/sdcard" && currentPath != "/storage/emulated/0") {
                            val parent = currentPath.substringBeforeLast('/', "/sdcard")
                            navigateTo(if (parent.isEmpty()) "/sdcard" else parent)
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color(0xFF1D1B20))
                    }
                },
                actions = {
                    IconButton(onClick = { isSearchActive = !isSearchActive }) {
                        Icon(
                            if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = "Search",
                            tint = Color(0xFF1D1B20)
                        )
                    }
                    IconButton(onClick = {
                        coroutineScope.launch {
                            isLoading = true
                            fetchFiles()
                            isLoading = false
                        }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color(0xFF1D1B20))
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
                    fetchFiles()
                    isLoading = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFFBFBFE))
                    .padding(horizontal = 16.dp)
            ) {
            // Status feedback banner
            AnimatedVisibility(visible = statusMessage != null, enter = fadeIn(), exit = fadeOut()) {
                Surface(
                    color = Color(0xFF1E1E1E),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF4CAF50),
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(statusMessage ?: "", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // 1. Storage Health & Capacity Meter Card
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
                border = BorderStroke(1.dp, Color(0xFFF0F0F0)),
                shadowElevation = 1.5.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFFEDE7F6)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Storage,
                                    contentDescription = null,
                                    tint = Color(0xFF673AB7),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Internal Storage",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 15.sp,
                                    color = Color(0xFF1D1B20)
                                )
                                Text(
                                    text = "${storageStats.used} Used • ${storageStats.free} Free",
                                    fontSize = 12.sp,
                                    color = Color(0xFF757575),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFE8F5E9)
                        ) {
                            Text(
                                text = "${storageStats.percent}% Used",
                                color = Color(0xFF2E7D32),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Gradient Storage Progress Bar
                    LinearProgressIndicator(
                        progress = { (storageStats.percent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(CircleShape),
                        color = Color(0xFF673AB7),
                        trackColor = Color(0xFFF0F0F0)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("0 GB", fontSize = 10.5.sp, color = Color(0xFF9E9E9E))
                        Text("Total: ${storageStats.total}", fontSize = 11.sp, color = Color(0xFF616161), fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 2. Quick Category Jumps (Horizontal Carousel)
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    CategoryChip(
                        name = "🏠 Internal",
                        isSelected = currentPath == "/sdcard" || currentPath == "/storage/emulated/0",
                        onClick = { navigateTo("/sdcard") }
                    )
                }
                item {
                    CategoryChip(
                        name = "📸 Camera / DCIM",
                        isSelected = currentPath.contains("DCIM", ignoreCase = true),
                        onClick = { navigateTo("/sdcard/DCIM") }
                    )
                }
                item {
                    CategoryChip(
                        name = "⬇️ Downloads",
                        isSelected = currentPath.contains("Download", ignoreCase = true),
                        onClick = { navigateTo("/sdcard/Download") }
                    )
                }
                item {
                    CategoryChip(
                        name = "📄 Documents",
                        isSelected = currentPath.contains("Documents", ignoreCase = true),
                        onClick = { navigateTo("/sdcard/Documents") }
                    )
                }
                item {
                    CategoryChip(
                        name = "🖼️ Pictures",
                        isSelected = currentPath.contains("Pictures", ignoreCase = true),
                        onClick = { navigateTo("/sdcard/Pictures") }
                    )
                }
                item {
                    CategoryChip(
                        name = "🎵 Music",
                        isSelected = currentPath.contains("Music", ignoreCase = true),
                        onClick = { navigateTo("/sdcard/Music") }
                    )
                }
                item {
                    CategoryChip(
                        name = "🎬 Movies",
                        isSelected = currentPath.contains("Movies", ignoreCase = true),
                        onClick = { navigateTo("/sdcard/Movies") }
                    )
                }
                item {
                    CategoryChip(
                        name = "💬 WhatsApp",
                        isSelected = currentPath.contains("WhatsApp", ignoreCase = true),
                        onClick = { navigateTo("/sdcard/Android/media/com.whatsapp/WhatsApp/Media") }
                    )
                }
            }

            // 3. Search Bar (Toggleable)
            AnimatedVisibility(visible = isSearchActive) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filter files & folders...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF673AB7)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF673AB7),
                        unfocusedBorderColor = Color(0xFFE0E0E0),
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White
                    ),
                    singleLine = true
                )
            }

            // 4. Interactive Breadcrumbs Navigator
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFF3F3FA)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentPath != "/sdcard" && currentPath != "/storage/emulated/0") {
                        IconButton(
                            onClick = {
                                val parent = currentPath.substringBeforeLast('/', "/sdcard")
                                navigateTo(if (parent.isEmpty()) "/sdcard" else parent)
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Up", tint = Color(0xFF673AB7), modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    } else {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Color(0xFF673AB7), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val pathSegments = currentPath.split("/").filter { it.isNotEmpty() }
                        var accumulated = ""
                        for ((idx, seg) in pathSegments.withIndex()) {
                            accumulated += "/$seg"
                            val target = accumulated
                            Text(
                                text = if (seg == "sdcard" || seg == "0") "Internal" else seg,
                                fontSize = 12.sp,
                                fontWeight = if (idx == pathSegments.lastIndex) FontWeight.ExtraBold else FontWeight.Medium,
                                color = if (idx == pathSegments.lastIndex) Color(0xFF1D1B20) else Color(0xFF673AB7),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { navigateTo(target) }
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                            if (idx < pathSegments.lastIndex) {
                                Text(" > ", fontSize = 11.sp, color = Color(0xFF9E9E9E), fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Text(
                        text = "$folderCount dir • $fileCount files",
                        fontSize = 10.5.sp,
                        color = Color(0xFF757575),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // 5. File & Folder List
            if (filteredFiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.FolderOff,
                            contentDescription = null,
                            tint = Color(0xFFBDBDBD),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "This folder is empty",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color(0xFF757575)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp)
                ) {
                    items(filteredFiles, key = { it.path }) { file ->
                        val (fileIcon, iconColor, iconBg) = getFileTypeAttributes(file)

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (file.isFolder) {
                                        navigateTo(file.path)
                                    } else {
                                        selectedFile = file
                                        downloadedImageBitmap = null
                                    }
                                },
                            shape = RoundedCornerShape(16.dp),
                            color = Color.White,
                            border = BorderStroke(1.dp, Color(0xFFF0F0F0)),
                            shadowElevation = 1.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(iconBg),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            fileIcon,
                                            contentDescription = null,
                                            tint = iconColor,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = file.name,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = Color(0xFF1D1B20),
                                            maxLines = 1
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = file.size,
                                                fontSize = 11.5.sp,
                                                color = Color(0xFF757575),
                                                fontWeight = FontWeight.Medium
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("•", fontSize = 10.sp, color = Color(0xFFBDBDBD))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = file.modified,
                                                fontSize = 11.sp,
                                                color = Color(0xFF9E9E9E)
                                            )
                                        }
                                    }
                                }

                                if (file.isFolder) {
                                    Icon(
                                        Icons.Default.ChevronRight,
                                        contentDescription = null,
                                        tint = Color(0xFFBDBDBD),
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    IconButton(
                                        onClick = {
                                            selectedFile = file
                                            downloadedImageBitmap = null
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.FileDownload,
                                            contentDescription = "Download",
                                            tint = Color(0xFF673AB7),
                                            modifier = Modifier.size(18.dp)
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

    // 6. File Details & Remote Download Action Sheet Modal
    if (selectedFile != null) {
        val file = selectedFile!!
        val (fileIcon, iconColor, iconBg) = getFileTypeAttributes(file)

        Dialog(
            onDismissRequest = { selectedFile = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // File Icon Header
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(iconBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(fileIcon, contentDescription = null, tint = iconColor, modifier = Modifier.size(28.dp))
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = file.name,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp,
                        color = Color(0xFF1D1B20),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "${file.size} • Remote Storage",
                        fontSize = 12.sp,
                        color = Color(0xFF757575)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Full Path Container
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFF5F5F5),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = file.path,
                                fontSize = 11.sp,
                                color = Color(0xFF616161),
                                modifier = Modifier.weight(1f),
                                maxLines = 2
                            )
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(file.path))
                                    statusMessage = "Path copied to clipboard"
                                    coroutineScope.launch {
                                        delay(2000)
                                        statusMessage = null
                                    }
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color(0xFF673AB7), modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    // Downloaded Image Preview if available
                    if (downloadedImageBitmap != null) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Image(
                            bitmap = downloadedImageBitmap!!,
                            contentDescription = "Image Preview",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Download Action Button
                    Button(
                        onClick = { downloadSelectedFile(file) },
                        enabled = !isDownloading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF673AB7))
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Fetching from device...", fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Download & Preview File", fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = { selectedFile = null },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color(0xFFE0E0E0))
                    ) {
                        Text("Close", color = Color(0xFF616161), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryChip(name: String, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (isSelected) Color(0xFF673AB7) else Color.White,
        border = if (isSelected) null else BorderStroke(1.dp, Color(0xFFE0E0E0)),
        shadowElevation = if (isSelected) 2.dp else 0.dp
    ) {
        Text(
            text = name,
            color = if (isSelected) Color.White else Color(0xFF1D1B20),
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 12.5.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

fun getFileTypeAttributes(file: FileEntry): Triple<ImageVector, Color, Color> {
    if (file.isFolder) {
        return Triple(Icons.Default.Folder, Color(0xFFFF9800), Color(0xFFFFF3E0))
    }
    val ext = file.name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg", "png", "webp", "gif", "heic", "bmp" ->
            Triple(Icons.Default.Image, Color(0xFFE91E63), Color(0xFFFCE4EC))
        "mp4", "mkv", "avi", "mov", "3gp", "webm" ->
            Triple(Icons.Default.Movie, Color(0xFF7C4DFF), Color(0xFFEDE7F6))
        "mp3", "wav", "m4a", "aac", "ogg", "opus", "flac" ->
            Triple(Icons.Default.MusicNote, Color(0xFF00BFA5), Color(0xFFE0F2F1))
        "pdf" ->
            Triple(Icons.Default.PictureAsPdf, Color(0xFFE53935), Color(0xFFFFEBEE))
        "doc", "docx", "txt", "xlsx", "xls", "ppt", "pptx" ->
            Triple(Icons.AutoMirrored.Filled.InsertDriveFile, Color(0xFF1976D2), Color(0xFFE3F2FD))
        "zip", "rar", "tar", "gz", "7z" ->
            Triple(Icons.Default.Archive, Color(0xFF795548), Color(0xFFEFEBE9))
        "apk", "xapk" ->
            Triple(Icons.Default.Android, Color(0xFF4CAF50), Color(0xFFE8F5E9))
        else ->
            Triple(Icons.AutoMirrored.Filled.InsertDriveFile, Color(0xFF607D8B), Color(0xFFECEFF1))
    }
}
