package com.example.kinetix.ui.features

import android.media.MediaPlayer
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class AudioItem(
    val id: String,
    val name: String,
    val duration: String,
    val size: String,
    val date: String,
    val base64: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioScreen(deviceId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isRecording by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(10) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val audioList = remember { mutableStateListOf<AudioItem>() }
    var currentlyPlayingId by remember { mutableStateOf<String?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    suspend fun fetchAudioList() {
        withContext(Dispatchers.IO) {
            val client = com.example.kinetix.network.KinetixApiClient(context)
            val res = client.getAudioList(deviceId)
            if (res.isSuccess) {
                val arr = res.getOrNull()
                if (arr != null) {
                    val items = mutableListOf<AudioItem>()
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        items.add(
                            AudioItem(
                                id = item.optString("id", "rec_$i"),
                                name = item.optString("name", "audio_clip.m4a"),
                                duration = item.optString("duration", "0:10"),
                                size = item.optString("size", "160 KB"),
                                date = item.optString("date", "Just now"),
                                base64 = item.optString("base64").takeIf { b -> b.isNotBlank() && b != "null" }
                            )
                        )
                    }
                    withContext(Dispatchers.Main) {
                        audioList.clear()
                        audioList.addAll(items)
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            fetchAudioList()
            delay(3000)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                mediaPlayer?.stop()
                mediaPlayer?.release()
            } catch (_: Exception) {}
        }
    }

    fun playAudio(item: AudioItem) {
        try {
            if (currentlyPlayingId == item.id) {
                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null
                currentlyPlayingId = null
                return
            }

            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null

            val b64 = item.base64
            if (!b64.isNullOrBlank()) {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val tempFile = File(context.cacheDir, "play_${item.id}.m4a")
                FileOutputStream(tempFile).use { it.write(bytes) }

                val player = MediaPlayer().apply {
                    setDataSource(tempFile.absolutePath)
                    prepare()
                    setOnCompletionListener {
                        currentlyPlayingId = null
                    }
                    start()
                }
                mediaPlayer = player
                currentlyPlayingId = item.id
            } else {
                statusMessage = "Audio file is processing..."
            }
        } catch (_: Exception) {
            statusMessage = "Unable to play audio."
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Microphone & Audio", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { coroutineScope.launch { fetchAudioList() } }) {
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
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isRecording) Color(0xFFFFEBEE) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(if (isRecording) Color(0xFFFFCDD2) else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isRecording) Icons.Default.Mic else Icons.Default.MicNone,
                            contentDescription = null,
                            tint = if (isRecording) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(38.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        if (isRecording) "Recording Remote Audio (${countdown}s)..." else "Remote Audio Standby",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isRecording) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Sample Rate: 44,100 Hz • Format: AAC High-Quality",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = {
                            if (!isRecording) {
                                isRecording = true
                                countdown = 10
                                statusMessage = "Command sent! Phone recording in background..."
                                coroutineScope.launch {
                                    val client = com.example.kinetix.network.KinetixApiClient(context)
                                    client.triggerAudioRecord(deviceId, 10)
                                    for (sec in 10 downTo 1) {
                                        countdown = sec
                                        delay(1000)
                                    }
                                    isRecording = false
                                    statusMessage = "Uploading audio clip from phone..."
                                    delay(2000)
                                    fetchAudioList()
                                    statusMessage = "Audio recording received!"
                                }
                            }
                        },
                        enabled = !isRecording,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(if (isRecording) Icons.Default.GraphicEq else Icons.Default.FiberManualRecord, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isRecording) "Recording ($countdown s)..." else "Start 10s Remote Record")
                    }

                    if (statusMessage != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = statusMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Card(
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Text(
                        "Audio Recordings Archive (${audioList.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (audioList.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No recordings captured yet.\nTap 'Start 10s Remote Record' above.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(audioList) { audio ->
                                val isPlaying = currentlyPlayingId == audio.id
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { playAudio(audio) },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isPlaying) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                                contentDescription = null,
                                                tint = if (isPlaying) Color.White else MaterialTheme.colorScheme.primary
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(12.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(audio.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                            Text("${audio.date} • ${audio.duration}", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                                        }

                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        ) {
                                            Text(
                                                text = audio.size,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
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
}
