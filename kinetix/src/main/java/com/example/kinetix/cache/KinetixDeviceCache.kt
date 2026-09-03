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

    fun saveCachedDeviceList(context: Context, devices: List<JSONObject>) {
        try {
            val arr = org.json.JSONArray()
            for (d in devices) {
                arr.put(d)
            }
            getPrefs(context).edit().putString("cached_device_list_json", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    fun getCachedDeviceList(context: Context): List<JSONObject> {
        val jsonStr = getPrefs(context).getString("cached_device_list_json", null) ?: return emptyList()
        val result = mutableListOf<JSONObject>()
        try {
            val arr = org.json.JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                result.add(arr.getJSONObject(i))
            }
        } catch (_: Exception) {}
        return result
    }

    // Generic JSON array cache helper
    fun saveJsonArray(context: Context, key: String, arr: org.json.JSONArray) {
        try {
            getPrefs(context).edit().putString(key, arr.toString()).apply()
        } catch (_: Exception) {}
    }

    fun getJsonArray(context: Context, key: String): org.json.JSONArray {
        val str = getPrefs(context).getString(key, null) ?: return org.json.JSONArray()
        return try {
            org.json.JSONArray(str)
        } catch (_: Exception) {
            org.json.JSONArray()
        }
    }

    // Feature specific caches
    fun saveCachedNotifications(context: Context, deviceId: String, arr: org.json.JSONArray) {
        saveJsonArray(context, "notifs_$deviceId", arr)
    }

    fun getCachedNotifications(context: Context, deviceId: String): org.json.JSONArray {
        return getJsonArray(context, "notifs_$deviceId")
    }

    fun saveCachedPhotos(context: Context, deviceId: String, arr: org.json.JSONArray) {
        saveJsonArray(context, "photos_$deviceId", arr)
    }

    fun getCachedPhotos(context: Context, deviceId: String): org.json.JSONArray {
        return getJsonArray(context, "photos_$deviceId")
    }

    fun saveCachedGallery(context: Context, deviceId: String, arr: org.json.JSONArray) {
        saveJsonArray(context, "gallery_$deviceId", arr)
    }

    fun getCachedGallery(context: Context, deviceId: String): org.json.JSONArray {
        return getJsonArray(context, "gallery_$deviceId")
    }

    fun saveCachedFiles(context: Context, deviceId: String, path: String, arr: org.json.JSONArray) {
        saveJsonArray(context, "files_${deviceId}_${path.hashCode()}", arr)
    }

    fun getCachedFiles(context: Context, deviceId: String, path: String): org.json.JSONArray {
        return getJsonArray(context, "files_${deviceId}_${path.hashCode()}")
    }

    fun saveCachedCalls(context: Context, deviceId: String, arr: org.json.JSONArray) {
        saveJsonArray(context, "calls_$deviceId", arr)
    }

    fun getCachedCalls(context: Context, deviceId: String): org.json.JSONArray {
        return getJsonArray(context, "calls_$deviceId")
    }

    fun saveCachedAudio(context: Context, deviceId: String, arr: org.json.JSONArray) {
        saveJsonArray(context, "audio_$deviceId", arr)
    }

    fun getCachedAudio(context: Context, deviceId: String): org.json.JSONArray {
        return getJsonArray(context, "audio_$deviceId")
    }

    fun saveCachedActivity(context: Context, deviceId: String, arr: org.json.JSONArray) {
        saveJsonArray(context, "activity_$deviceId", arr)
    }

    fun getCachedActivity(context: Context, deviceId: String): org.json.JSONArray {
        return getJsonArray(context, "activity_$deviceId")
    }

    fun saveCachedLocation(context: Context, deviceId: String, obj: JSONObject) {
        try {
            getPrefs(context).edit().putString("loc_$deviceId", obj.toString()).apply()
        } catch (_: Exception) {}
    }

    fun getCachedLocation(context: Context, deviceId: String): JSONObject? {
        val str = getPrefs(context).getString("loc_$deviceId", null) ?: return null
        return try {
            JSONObject(str)
        } catch (_: Exception) {
            null
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
