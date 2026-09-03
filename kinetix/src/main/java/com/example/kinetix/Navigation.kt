package com.example.kinetix

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.kinetix.ui.dashboard.DashboardScreen
import com.example.kinetix.ui.devicedetail.DeviceDetailScreen
import com.example.kinetix.ui.discovery.DiscoveryScreen
import com.example.kinetix.ui.settings.SettingsScreen

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing

@Composable
fun MainNavigation() {
    val backStack = rememberNavBackStack(Dashboard)

    NavDisplay(
        modifier = Modifier.fillMaxSize(),
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        transitionSpec = {
            val isBack = targetState.key is Dashboard || (initialState.key !is Dashboard && targetState.key is DeviceDetail)
            if (isBack) {
                (slideInHorizontally(animationSpec = tween(280, easing = FastOutSlowInEasing), initialOffsetX = { -it / 4 }) +
                        fadeIn(animationSpec = tween(250, easing = LinearOutSlowInEasing)))
                    .togetherWith(
                        slideOutHorizontally(animationSpec = tween(280, easing = FastOutSlowInEasing), targetOffsetX = { it }) +
                                fadeOut(animationSpec = tween(200, easing = FastOutLinearInEasing))
                    )
            } else {
                (slideInHorizontally(animationSpec = tween(300, easing = FastOutSlowInEasing), initialOffsetX = { it }) +
                        fadeIn(animationSpec = tween(250, easing = LinearOutSlowInEasing)))
                    .togetherWith(
                        slideOutHorizontally(animationSpec = tween(300, easing = FastOutSlowInEasing), targetOffsetX = { -it / 4 }) +
                                fadeOut(animationSpec = tween(200, easing = FastOutLinearInEasing))
                    )
            }
        },
        entryProvider = entryProvider {
            entry<Dashboard> {
                DashboardScreen(
                    onNavigateToDiscovery = { backStack.add(Discovery) },
                    onNavigateToDeviceDetail = { deviceId -> backStack.add(DeviceDetail(deviceId)) },
                    onNavigateToSettings = { backStack.add(Settings) }
                )
            }
            entry<Discovery> {
                DiscoveryScreen(
                    onBack = { backStack.removeLastOrNull() },
                    onPairingSuccess = { backStack.removeLastOrNull() }
                )
            }
            entry<DeviceDetail> { key ->
                DeviceDetailScreen(
                    deviceId = key.deviceId,
                    onBack = { backStack.removeLastOrNull() },
                    onNavigateToNotifications = { backStack.add(FeatureNotifications(key.deviceId)) },
                    onNavigateToCalls = { backStack.add(FeatureCalls(key.deviceId)) },
                    onNavigateToPhotos = { backStack.add(FeaturePhotos(key.deviceId)) },
                    onNavigateToGallery = { backStack.add(FeatureGallery(key.deviceId)) },
                    onNavigateToFiles = { backStack.add(FeatureFiles(key.deviceId)) },
                    onNavigateToLocation = { backStack.add(FeatureLocation(key.deviceId)) },
                    onNavigateToBattery = { backStack.add(FeatureBattery(key.deviceId)) },
                    onNavigateToAudio = { backStack.add(FeatureAudio(key.deviceId)) },
                    onNavigateToActivity = { backStack.add(FeatureActivity(key.deviceId)) },
                    onUnpaired = { backStack.removeLastOrNull() }
                )
            }
            entry<FeatureActivity> { key ->
                com.example.kinetix.ui.features.ActivityScreen(
                    deviceId = key.deviceId,
                    onBack = { backStack.removeLastOrNull() }
                )
            }
            entry<FeatureNotifications> { key ->
                com.example.kinetix.ui.features.NotificationsScreen(
                    deviceId = key.deviceId,
                    onBack = { backStack.removeLastOrNull() }
                )
            }
            entry<FeatureCalls> { key ->
                com.example.kinetix.ui.features.CallHistoryScreen(
                    deviceId = key.deviceId,
                    onBack = { backStack.removeLastOrNull() }
                )
            }
            entry<FeatureGallery> { key ->
                com.example.kinetix.ui.features.GalleryScreen(
                    deviceId = key.deviceId,
                    onBack = { backStack.removeLastOrNull() }
                )
            }
            entry<FeaturePhotos> { key ->
                com.example.kinetix.ui.features.GalleryScreen(
                    deviceId = key.deviceId,
                    onBack = { backStack.removeLastOrNull() }
                )
            }
            entry<FeatureFiles> { key ->
                com.example.kinetix.ui.features.FilesScreen(
                    deviceId = key.deviceId,
                    onBack = { backStack.removeLastOrNull() }
                )
            }
            entry<FeatureLocation> { key ->
                com.example.kinetix.ui.features.LocationScreen(
                    deviceId = key.deviceId,
                    onBack = { backStack.removeLastOrNull() }
                )
            }
            entry<FeatureBattery> { key ->
                com.example.kinetix.ui.features.BatteryScreen(
                    deviceId = key.deviceId,
                    onBack = { backStack.removeLastOrNull() }
                )
            }
            entry<FeatureAudio> { key ->
                com.example.kinetix.ui.features.AudioScreen(
                    deviceId = key.deviceId,
                    onBack = { backStack.removeLastOrNull() }
                )
            }
            entry<Settings> {
                SettingsScreen(
                    onBack = { backStack.removeLastOrNull() }
                )
            }
        }
    )
}
