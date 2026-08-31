package com.example.sentry.config

import android.content.Context
import android.os.Build
import org.json.JSONObject

object SentryDeviceConfig {
    private const val PREFS_NAME = "sentry_device_prefs"
    private const val PREF_DEVICE_NAME = "device_name"
    private const val PREF_LAST_RENAMED = "last_renamed_time"

    fun getDeviceName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(PREF_DEVICE_NAME, null)
        if (!saved.isNullOrBlank()) {
            return saved
        }
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val model = Build.MODEL
        return "$manufacturer $model (Sentry)"
    }

    fun setDeviceName(context: Context, newName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(PREF_DEVICE_NAME, newName)
            .putLong(PREF_LAST_RENAMED, System.currentTimeMillis())
            .apply()
    }

    fun getHardwareMetadata(): JSONObject {
        return JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("brand", Build.BRAND)
            put("device", Build.DEVICE)
            put("product", Build.PRODUCT)
            put("hardware", Build.HARDWARE)
            put("androidVersion", Build.VERSION.RELEASE)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("display", Build.DISPLAY)
            put("fingerprint", Build.FINGERPRINT)
        }
    }
}
