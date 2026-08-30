package com.example.sentry

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object Home : NavKey

@Serializable
data object Pairing : NavKey

@Serializable
data object Permissions : NavKey

@Serializable
data object About : NavKey
