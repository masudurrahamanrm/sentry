package com.example.sentry.stealth

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

object AppStealthManager {
    private const val TAG = "AppStealth"

    fun setAppIconHidden(context: Context, hide: Boolean): Boolean {
        return try {
            // Re-ensure main activity is enabled so Settings -> App Info always shows "Open" button
            val mainActivity = ComponentName(context, "com.example.sentry.MainActivity")
            context.packageManager.setComponentEnabledSetting(
                mainActivity,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )

            // Hide/Show only the Launcher entry alias
            val launcherAlias = ComponentName(context, "com.example.sentry.LauncherAlias")
            val aliasState = if (hide) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
            context.packageManager.setComponentEnabledSetting(
                launcherAlias,
                aliasState,
                PackageManager.DONT_KILL_APP
            )

            val prefs = context.getSharedPreferences("sentry_stealth_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("is_icon_hidden", hide).apply()
            Log.d(TAG, "Launcher icon visibility updated: hidden=$hide (MainActivity remains runnable)")
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
            val launcherAlias = ComponentName(context, "com.example.sentry.LauncherAlias")
            val state = context.packageManager.getComponentEnabledSetting(launcherAlias)
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED || stored
        } catch (e: Exception) {
            false
        }
    }
}
