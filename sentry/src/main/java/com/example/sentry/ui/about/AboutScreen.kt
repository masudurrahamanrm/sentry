package com.example.sentry.ui.about

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sentry.stealth.AppStealthManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isHidden by remember {
        mutableStateOf(AppStealthManager.isAppIconHidden(context))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "About Sentry", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Application Metadata",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            ListItem(
                headlineContent = { Text("Application Name") },
                supportingContent = { Text("Sentry Companion Agent") }
            )

            HorizontalDivider()

            ListItem(
                headlineContent = { Text("Package ID") },
                supportingContent = { Text("com.example.sentry") }
            )

            HorizontalDivider()

            ListItem(
                headlineContent = { Text("Target SDK") },
                supportingContent = { Text("API 36 (Android 15)") }
            )

            HorizontalDivider()

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isHidden) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                    else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "App Drawer Cleanliness / Stealth",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isHidden)
                            "App icon is currently HIDDEN from launcher/app drawer. All background services continue running."
                        else
                            "Hide app icon from phone's app drawer for cleanliness. Background features remain active.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val newHidden = !isHidden
                            AppStealthManager.setAppIconHidden(context, newHidden)
                            isHidden = newHidden
                            Toast.makeText(
                                context,
                                if (newHidden) "App icon hidden from launcher" else "App icon restored in launcher",
                                Toast.LENGTH_LONG
                            ).show()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isHidden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(if (isHidden) "Reveal in App Drawer" else "Hide from App Drawer")
                    }
                }
            }
        }
    }
}
