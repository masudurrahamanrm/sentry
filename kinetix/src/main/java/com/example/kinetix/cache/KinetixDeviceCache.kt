package com.example.kinetix.cache

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

object KinetixDeviceCache {
    private const val PREFS_NAME = "kinetix_device_cache_prefs"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveDeviceName(context: Context, deviceId: String, name: String) {
        getPrefs(context).edit().putString("name_$deviceId", name).apply()
    }

    fun getDeviceName(context: Context, deviceId: String, defaultName: String = "realme RMX5101 (Sentry)"): String {
        return getPrefs(context).getString("name_$deviceId", null) ?: defaultName
    }

    fun saveWallpaper(context: Context, deviceId: String, wallpaperBase64: String) {
        getPrefs(context).edit().putString("wallpaper_$deviceId", wallpaperBase64).apply()
    }

    fun getWallpaper(context: Context, deviceId: String): String? {
        return getPrefs(context).getString("wallpaper_$deviceId", null)
    }

    fun saveTelemetry(
        context: Context,
        deviceId: String,
        percentage: Int,
        status: String,
        networkType: String,
        uptime: String
    ) {
        getPrefs(context).edit()
            .putInt("battery_pct_$deviceId", percentage)
            .putString("battery_status_$deviceId", status)
            .putString("network_type_$deviceId", networkType)
            .putString("uptime_$deviceId", uptime)
            .putLong("last_sync_$deviceId", System.currentTimeMillis())
            .apply()
    }

    fun getCachedTelemetry(context: Context, deviceId: String): CachedTelemetry {
        val prefs = getPrefs(context)
        return CachedTelemetry(
            percentage = prefs.getInt("battery_pct_$deviceId", 44),
            status = prefs.getString("battery_status_$deviceId", "Good") ?: "Good",
            networkType = prefs.getString("network_type_$deviceId", "5G+") ?: "5G+",
            uptime = prefs.getString("uptime_$deviceId", "2h 14m") ?: "2h 14m",
            lastSync = prefs.getLong("last_sync_$deviceId", 0L)
        )
    }

    fun saveCapabilities(
        context: Context,
        deviceId: String,
        camera: Boolean,
        location: Boolean,
        notifications: Boolean,
        mic: Boolean,
        files: Boolean,
        battery: Boolean
    ) {
        getPrefs(context).edit()
            .putBoolean("cap_camera_$deviceId", camera)
            .putBoolean("cap_location_$deviceId", location)
            .putBoolean("cap_notif_$deviceId", notifications)
            .putBoolean("cap_mic_$deviceId", mic)
            .putBoolean("cap_files_$deviceId", files)
            .putBoolean("cap_battery_$deviceId", battery)
            .apply()
    }

    fun getCachedCapabilities(context: Context, deviceId: String): CachedCapabilities {
        val prefs = getPrefs(context)
        return CachedCapabilities(
            camera = prefs.getBoolean("cap_camera_$deviceId", true),
            location = prefs.getBoolean("cap_location_$deviceId", true),
            notifications = prefs.getBoolean("cap_notif_$deviceId", true),
            mic = prefs.getBoolean("cap_mic_$deviceId", true),
            files = prefs.getBoolean("cap_files_$deviceId", true),
            battery = prefs.getBoolean("cap_battery_$deviceId", true)
        )
    }

    fun saveStorageStats(context: Context, deviceId: String, total: String, free: String, used: String, percent: Int) {
        getPrefs(context).edit()
            .putString("storage_total_$deviceId", total)
            .putString("storage_free_$deviceId", free)
            .putString("storage_used_$deviceId", used)
            .putInt("storage_percent_$deviceId", percent)
            .apply()
    }

    fun getCachedStorageStats(context: Context, deviceId: String): JSONObject {
        val prefs = getPrefs(context)
        return JSONObject().apply {
            put("total", prefs.getString("storage_total_$deviceId", "128 GB") ?: "128 GB")
            put("free", prefs.getString("storage_free_$deviceId", "48.2 GB") ?: "48.2 GB")
            put("used", prefs.getString("storage_used_$deviceId", "79.8 GB") ?: "79.8 GB")
            put("percent", prefs.getInt("storage_percent_$deviceId", 62))
        }
    }
}

data class CachedTelemetry(
    val percentage: Int,
    val status: String,
    val networkType: String,
    val uptime: String,
    val lastSync: Long
)

data class CachedCapabilities(
    val camera: Boolean,
    val location: Boolean,
    val notifications: Boolean,
    val mic: Boolean,
    val files: Boolean,
    val battery: Boolean
)
