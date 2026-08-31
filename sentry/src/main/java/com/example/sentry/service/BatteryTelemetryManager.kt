package com.example.sentry.service

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import com.example.sentry.crypto.CryptoManager
import com.example.sentry.network.SentryApiClient
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream

object BatteryTelemetryManager {
    private var syncJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var cachedWallpaperBase64: String? = null
    private var lastWallpaperCheckTime = 0L

    fun startSync(context: Context) {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            val client = SentryApiClient(context)
            while (isActive) {
                try {
                    val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
                        context.registerReceiver(null, ifilter)
                    }

                    val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                    val batteryPct: Int = if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()).toInt() else 100

                    val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                    val isCharging: Boolean = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL

                    val chargePlug: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
                    val isUsb = chargePlug == BatteryManager.BATTERY_PLUGGED_USB
                    val isAc = chargePlug == BatteryManager.BATTERY_PLUGGED_AC
                    val isWireless = chargePlug == BatteryManager.BATTERY_PLUGGED_WIRELESS

                    val chargingStatusStr = when {
                        isCharging && (isAc || isUsb) -> "Charging"
                        isCharging && isWireless -> "Wireless Charging"
                        isCharging -> "Charging"
                        else -> "Good"
                    }

                    val tempRaw = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
                    val tempC = if (tempRaw > 0) String.format("%.1f °C", tempRaw / 10.0) else "33.5 °C"

                    val voltRaw = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
                    val voltStr = if (voltRaw > 0) String.format("%,d mV", voltRaw) else "4,180 mV"

                    val healthRaw = batteryStatus?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_GOOD) ?: BatteryManager.BATTERY_HEALTH_GOOD
                    val healthStr = when (healthRaw) {
                        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
                        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
                        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
                        else -> "Normal"
                    }

                    val technology = batteryStatus?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Li-ion"

                    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    val isPowerSave = powerManager?.isPowerSaveMode == true

                    // Network detection
                    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    val netCap = cm?.getNetworkCapabilities(cm.activeNetwork)
                    val (netType, netStatus) = when {
                        netCap?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> Pair("Wi-Fi", "Strong")
                        netCap?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> Pair("5G+", "Strong")
                        else -> Pair("5G+", "Online")
                    }

                    // Uptime calculation
                    val uptimeMs = SystemClock.elapsedRealtime()
                    val upHours = (uptimeMs / (1000 * 60 * 60)).toInt()
                    val upMins = ((uptimeMs / (1000 * 60)) % 60).toInt()
                    val uptimeStr = if (upHours > 0) "${upHours}h ${upMins}m" else "${upMins}m"

                    // Wallpaper extraction (cached for 60s to avoid high CPU)
                    val now = System.currentTimeMillis()
                    if (cachedWallpaperBase64 == null || now - lastWallpaperCheckTime > 60_000) {
                        cachedWallpaperBase64 = extractWallpaperBase64(context)
                        lastWallpaperCheckTime = now
                    }

                    val deviceId = CryptoManager.getOrCreateDeviceId(context)

                    val body = JSONObject().apply {
                        put("deviceId", deviceId)
                        put("level", batteryPct)
                        put("percentage", batteryPct)
                        put("isCharging", isCharging)
                        put("chargingStatus", chargingStatusStr)
                        put("temperature", tempC)
                        put("voltage", voltStr)
                        put("health", healthStr)
                        put("technology", technology)
                        put("powerSave", isPowerSave)
                        put("networkType", netType)
                        put("networkStatus", netStatus)
                        put("uptime", uptimeStr)
                        if (!cachedWallpaperBase64.isNullOrBlank()) {
                            put("wallpaper", cachedWallpaperBase64)
                        }
                    }

                    client.syncBatteryTelemetry(body)
                } catch (_: Exception) {
                }
                delay(3000) // Live telemetry sync every 3 seconds
            }
        }
    }

    private fun extractWallpaperBase64(context: Context): String? {
        return try {
            val wm = WallpaperManager.getInstance(context)
            val drawable = wm.drawable ?: wm.fastDrawable
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                val orig = drawable.bitmap
                val targetW = 120
                val targetH = 200
                val scaled = Bitmap.createScaledBitmap(orig, targetW, targetH, true)
                val stream = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
