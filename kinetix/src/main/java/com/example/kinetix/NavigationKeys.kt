package com.example.kinetix

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object Dashboard : NavKey

@Serializable
data object Discovery : NavKey

@Serializable
data class DeviceDetail(val deviceId: String) : NavKey

@Serializable
data class FeatureNotifications(val deviceId: String) : NavKey

@Serializable
data class FeaturePhotos(val deviceId: String) : NavKey

@Serializable
data class FeatureFiles(val deviceId: String) : NavKey

@Serializable
data class FeatureLocation(val deviceId: String) : NavKey

@Serializable
data class FeatureBattery(val deviceId: String) : NavKey

@Serializable
data class FeatureAudio(val deviceId: String) : NavKey

@Serializable
data object Settings : NavKey
