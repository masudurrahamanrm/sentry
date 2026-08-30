package com.example.kinetix.ui.features

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class FileEntry(val name: String, val path: String, val size: String, val isFolder: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(deviceId: String, onBack: () -> Unit) {
    val files = remember {
        mutableStateListOf(
            FileEntry("Download", "/sdcard/Download", "Folder", true),
            FileEntry("DCIM", "/sdcard/DCIM", "Folder", true),
            FileEntry("Documents", "/sdcard/Documents", "Folder", true),
            FileEntry("sentry_report_2026.pdf", "/sdcard/Download/sentry_report_2026.pdf", "3.4 MB", false),
            FileEntry("audio_recording_memo.m4a", "/sdcard/Download/audio_recording_memo.m4a", "1.2 MB", false),
            FileEntry("backup_archive.zip", "/sdcard/Download/backup_archive.zip", "24.8 MB", false),
            FileEntry("notes_project.txt", "/sdcard/Documents/notes_project.txt", "12 KB", false)
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("File & Storage Explorer", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { /* Upload/Sync */ }) {
                        Icon(Icons.Default.CloudDownload, contentDescription = "Sync")
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
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("/sdcard/ (Internal Storage)", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(files) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
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
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(item.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text(item.size, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                                }
                            }

                            if (!item.isFolder) {
                                IconButton(onClick = { /* Download file */ }) {
                                    Icon(Icons.Default.Download, contentDescription = "Download File", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
