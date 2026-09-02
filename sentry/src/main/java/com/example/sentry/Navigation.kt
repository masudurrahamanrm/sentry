package com.example.sentry

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.sentry.ui.about.AboutScreen
import com.example.sentry.ui.home.HomeScreen
import com.example.sentry.ui.pairing.PairingScreen
import com.example.sentry.ui.permissions.PermissionsScreen

@Composable
fun SentryNavigation() {
    val backStack = rememberNavBackStack(Home)

    NavDisplay(
        modifier = Modifier.fillMaxSize(),
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<Home> {
                HomeScreen(
                    onNavigateToCompanionInfo = { backStack.add(CompanionInfo) },
                    onNavigateToPairing = { backStack.add(Pairing) },
                    onNavigateToPermissions = { backStack.add(Permissions) },
                    onNavigateToAbout = { backStack.add(About) }
                )
            }
            entry<Pairing> {
                PairingScreen(
                    onBack = { backStack.removeLastOrNull() },
                    onPairingSuccess = { backStack.removeLastOrNull() }
                )
            }
            entry<Permissions> {
                PermissionsScreen(
                    onBack = { backStack.removeLastOrNull() }
                )
            }
            entry<About> {
                AboutScreen(
                    onBack = { backStack.removeLastOrNull() }
                )
            }
            entry<CompanionInfo> {
                com.example.sentry.ui.companion.CompanionInfoScreen(
                    onBack = { backStack.removeLastOrNull() },
                    onNavigateToPairing = { backStack.add(Pairing) },
                    onNavigateToPermissions = { backStack.add(Permissions) },
                    onNavigateToAbout = { backStack.add(About) }
                )
            }
        }
    )
}
