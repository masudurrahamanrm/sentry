package com.example.sentry.command

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import org.json.JSONObject

/**
 * CommandExecutor executes authorized system commands strictly conforming
 * to operating system sandbox restrictions and user permissions.
 */
object CommandExecutor {

    private val seenNonces = mutableSetOf<String>()

    fun execute(
        context: Context,
        commandId: String,
        type: String,
        timestamp: Long,
        nonce: String,
        payload: JSONObject
    ): CommandResult {
        val now = System.currentTimeMillis()

        // 1. Freshness Check (60 seconds)
        if (Math.abs(now - timestamp) > 60_000) {
            return CommandResult(
                commandId = commandId,
                status = "DENIED",
                reason = "COMMAND_TIMESTAMP_EXPIRED"
            )
        }

        // 2. Nonce Replay Check
        if (seenNonces.contains(nonce)) {
            return CommandResult(
                commandId = commandId,
                status = "DENIED",
                reason = "REPLAY_ATTACK_DETECTED"
            )
        }
        seenNonces.add(nonce)

        // 3. Command Execution
        return when (type) {
            "PING" -> {
                CommandResult(
                    commandId = commandId,
                    status = "SUCCESS",
                    result = JSONObject().apply { put("pong", true); put("timestamp", now) }
                )
            }
            "DEVICE_INFO" -> {
                val info = JSONObject().apply {
                    put("model", Build.MODEL)
                    put("manufacturer", Build.MANUFACTURER)
                    put("androidVersion", Build.VERSION.RELEASE)
                    put("sdkInt", Build.VERSION.SDK_INT)
                }
                CommandResult(commandId = commandId, status = "SUCCESS", result = info)
            }
            "GET_BATTERY" -> {
                val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
                    context.registerReceiver(null, ifilter)
                }
                val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val batteryPct: Float = if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()) else -1f
                val isCharging: Boolean = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0

                val batteryJson = JSONObject().apply {
                    put("levelPercent", batteryPct)
                    put("isCharging", isCharging)
                }
                CommandResult(commandId = commandId, status = "SUCCESS", result = batteryJson)
            }
            else -> {
                CommandResult(
                    commandId = commandId,
                    status = "DENIED",
                    reason = "UNKNOWN_OR_UNSUPPORTED_COMMAND"
                )
            }
        }
    }
}

data class CommandResult(
    val commandId: String,
    val status: String,
    val result: JSONObject? = null,
    val reason: String? = null
)
