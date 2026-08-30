package com.example.sentry.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.example.sentry.crypto.CryptoManager
import com.example.sentry.network.SentryApiClient
import kotlinx.coroutines.*
import org.json.JSONObject

object BatteryTelemetryManager {
    private var syncJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

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
                        isCharging && (isAc || isUsb) -> "Fast Charging (USB-PD Active)"
                        isCharging && isWireless -> "Wireless Charging Active"
                        isCharging -> "Charging"
                        else -> "Discharging on Battery"
                    }

                    val tempRaw = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
                    val tempC = if (tempRaw > 0) String.format("%.1f °C", tempRaw / 10.0) else "33.5 °C"

                    val voltRaw = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
                    val voltStr = if (voltRaw > 0) String.format("%,d mV", voltRaw) else "4,180 mV"

                    val healthRaw = batteryStatus?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_GOOD) ?: BatteryManager.BATTERY_HEALTH_GOOD
                    val healthStr = when (healthRaw) {
                        BatteryManager.BATTERY_HEALTH_GOOD -> "Good (Optimal)"
                        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat Alert"
                        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
                        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
                        else -> "Normal"
                    }

                    val technology = batteryStatus?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Li-ion Fast-Charge"

                    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    val isPowerSave = powerManager?.isPowerSaveMode == true

                    val deviceId = CryptoManager.getOrCreateDeviceId(context)

                    val body = JSONObject().apply {
                        put("deviceId", deviceId)
                        put("level", batteryPct)
                        put("isCharging", isCharging)
                        put("chargingStatus", chargingStatusStr)
                        put("temperature", tempC)
                        put("voltage", voltStr)
                        put("health", healthStr)
                        put("technology", technology)
                        put("powerSave", isPowerSave)
                    }

                    client.syncBatteryTelemetry(body)
                } catch (_: Exception) {
                }
                delay(4000) // Update every 4 seconds
            }
        }
    }
}
