package com.example.sentry.ui.permissions

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var cameraAllowed by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    var locationAllowed by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    var notificationAllowed by remember {
        mutableStateOf(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else true
        )
    }
    var micAllowed by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    var notificationReadAllowed by remember {
        mutableStateOf(
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )?.contains(context.packageName) == true
        )
    }

    var filesAllowed by remember { mutableStateOf(true) }

    fun syncCapabilitiesToBackend() {
        coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val client = com.example.sentry.network.SentryApiClient(context)
            client.syncCapabilities()
        }
    }

    val cameraLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        cameraAllowed = isGranted
        syncCapabilitiesToBackend()
    }

    val locationLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        locationAllowed = isGranted
        syncCapabilitiesToBackend()
    }

    val notificationLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        notificationAllowed = isGranted
        syncCapabilitiesToBackend()
    }

    val micLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        micAllowed = isGranted
        syncCapabilitiesToBackend()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Permissions & Capabilities", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .verticalScroll(rememberScrollState())
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Operating System Permissions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Toggle permissions below to open official Android dialogs and grant or revoke access in real time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    PermissionToggleRow(
                        title = "Notification Read Access",
                        description = "Permits reading incoming SMS, WhatsApp, and app alerts in real time",
                        icon = Icons.Default.NotificationsActive,
                        checked = notificationReadAllowed,
                        onCheckedChange = {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    PermissionToggleRow(
                        title = "Camera",
                        description = "Allows controller to request photos with explicit consent",
                        icon = Icons.Default.CameraAlt,
                        checked = cameraAllowed,
                        onCheckedChange = {
                            if (!cameraAllowed) {
                                cameraLauncher.launch(android.Manifest.permission.CAMERA)
                            } else {
                                cameraAllowed = false
                                syncCapabilitiesToBackend()
                            }
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    PermissionToggleRow(
                        title = "Files & Storage",
                        description = "Permits authenticated file transfers",
                        icon = Icons.Default.Folder,
                        checked = filesAllowed,
                        onCheckedChange = {
                            filesAllowed = it
                            syncCapabilitiesToBackend()
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    PermissionToggleRow(
                        title = "Notifications Alert",
                        description = "Permits pairing and connection alerts",
                        icon = Icons.Default.Notifications,
                        checked = notificationAllowed,
                        onCheckedChange = {
                            if (!notificationAllowed && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                notificationAllowed = it
                                syncCapabilitiesToBackend()
                            }
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    PermissionToggleRow(
                        title = "Location",
                        description = "Enables location telemetry when requested",
                        icon = Icons.Default.LocationOn,
                        checked = locationAllowed,
                        onCheckedChange = {
                            if (!locationAllowed) {
                                locationLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                            } else {
                                locationAllowed = false
                                syncCapabilitiesToBackend()
                            }
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    PermissionToggleRow(
                        title = "Microphone",
                        description = "Audio recording capabilities",
                        icon = Icons.Default.Mic,
                        checked = micAllowed,
                        onCheckedChange = {
                            if (!micAllowed) {
                                micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                            } else {
                                micAllowed = false
                                syncCapabilitiesToBackend()
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionToggleRow(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
