package com.example.sentry.stealth

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

object AppStealthManager {
    private const val TAG = "AppStealth"

    fun setAppIconHidden(context: Context, hide: Boolean): Boolean {
        return try {
            val componentName = ComponentName(context, "com.example.sentry.MainActivity")
            val newState = if (hide) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
            context.packageManager.setComponentEnabledSetting(
                componentName,
                newState,
                PackageManager.DONT_KILL_APP
            )
            val prefs = context.getSharedPreferences("sentry_stealth_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("is_icon_hidden", hide).apply()
            Log.d(TAG, "App icon visibility updated: hidden=$hide")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to change app icon visibility", e)
            false
        }
    }

    fun isAppIconHidden(context: Context): Boolean {
        return try {
            val prefs = context.getSharedPreferences("sentry_stealth_prefs", Context.MODE_PRIVATE)
            val stored = prefs.getBoolean("is_icon_hidden", false)
            val componentName = ComponentName(context, "com.example.sentry.MainActivity")
            val state = context.packageManager.getComponentEnabledSetting(componentName)
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED || stored
        } catch (e: Exception) {
            false
        }
    }
}
