package com.example.sentry.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * PermissionManager queries official Android OS permission grants.
 * Never bypasses OS security dialogs or extracts private data.
 */
object PermissionManager {

    fun getDeviceCapabilities(context: Context): JSONObject {
        return JSONObject().apply {
            put("camera", isPermissionGranted(context, Manifest.permission.CAMERA))
            put("location", isPermissionGranted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
                    isPermissionGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION))
            put("microphone", isPermissionGranted(context, Manifest.permission.RECORD_AUDIO))
            put("notifications", if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                isPermissionGranted(context, Manifest.permission.POST_NOTIFICATIONS)
            } else {
                true
            })
            put("files", true) // App-owned files permitted
            put("battery", true) // Non-sensitive system telemetry permitted
        }
    }

    private fun isPermissionGranted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }
}
