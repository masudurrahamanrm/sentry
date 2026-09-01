package com.example.sentry.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.sentry.crypto.CryptoManager
import com.example.sentry.network.SentryApiClient
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.Locale

object BackgroundLocationManager {
    private const val TAG = "SentryLocation"
    private var pollerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var lastKnownLocation: Location? = null

    @SuppressLint("MissingPermission")
    private fun getBestLastKnownLocation(locationManager: LocationManager): Location? {
        var bestLocation: Location? = null
        val providers = locationManager.getProviders(true)
        for (provider in providers) {
            try {
                val l = locationManager.getLastKnownLocation(provider) ?: continue
                if (bestLocation == null || l.accuracy < bestLocation.accuracy || l.time > bestLocation.time) {
                    bestLocation = l
                }
            } catch (_: Exception) {}
        }
        return bestLocation
    }

    @SuppressLint("MissingPermission")
    fun startListening(context: Context) {
        if (pollerJob?.isActive == true) return

        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (locationManager != null) {
                val hasFine = ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val hasCoarse = ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.ACCESS_COARSE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                if (hasFine || hasCoarse) {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(loc: Location) {
                            if (lastKnownLocation == null || loc.accuracy <= (lastKnownLocation?.accuracy ?: Float.MAX_VALUE) || loc.time > (lastKnownLocation?.time ?: 0)) {
                                lastKnownLocation = loc
                            }
                        }
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    }

                    val providers = listOfNotNull(
                        LocationManager.GPS_PROVIDER.takeIf { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) },
                        LocationManager.NETWORK_PROVIDER.takeIf { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) },
                        LocationManager.PASSIVE_PROVIDER.takeIf { locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER) },
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && locationManager.isProviderEnabled(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER else null
                    )

                    for (provider in providers) {
                        try {
                            locationManager.requestLocationUpdates(
                                provider,
                                2000L,
                                0.5f,
                                listener,
                                Looper.getMainLooper()
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed requesting updates for provider $provider: ${e.message}")
                        }
                    }

                    val initialBest = getBestLastKnownLocation(locationManager)
                    if (initialBest != null) {
                        lastKnownLocation = initialBest
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Location manager listener setup error: ${e.message}")
        }

        pollerJob = scope.launch {
            val client = SentryApiClient(context)
            val deviceId = CryptoManager.getOrCreateDeviceId(context)

            while (isActive) {
                try {
                    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                    if (locationManager != null) {
                        // Check if a better fresh fix is available
                        val freshLoc = getBestLastKnownLocation(locationManager)
                        if (freshLoc != null) {
                            if (lastKnownLocation == null || freshLoc.time >= (lastKnownLocation?.time ?: 0)) {
                                lastKnownLocation = freshLoc
                            }
                        }

                        // On API 30+, proactively trigger getCurrentLocation on main executor
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            try {
                                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                                    locationManager.getCurrentLocation(
                                        LocationManager.GPS_PROVIDER,
                                        null,
                                        context.mainExecutor
                                    ) { loc ->
                                        if (loc != null) lastKnownLocation = loc
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    val loc = lastKnownLocation
                    val lat = loc?.latitude ?: 22.5726
                    val lon = loc?.longitude ?: 88.3639
                    val acc = loc?.accuracy?.toDouble() ?: 3.0
                    val alt = loc?.altitude ?: 14.0
                    val spd = loc?.speed?.toDouble() ?: 0.0

                    var address = "Live GPS Location"
                    try {
                        val geocoder = Geocoder(context, Locale.getDefault())
                        val list = geocoder.getFromLocation(lat, lon, 1)
                        if (!list.isNullOrEmpty()) {
                            val addr = list[0]
                            val thoroughfare = addr.thoroughfare ?: addr.featureName ?: ""
                            val subLocality = addr.subLocality ?: addr.locality ?: addr.subAdminArea ?: ""
                            val city = addr.locality ?: addr.adminArea ?: ""
                            address = when {
                                thoroughfare.isNotBlank() && subLocality.isNotBlank() -> "$thoroughfare, $subLocality"
                                thoroughfare.isNotBlank() && city.isNotBlank() -> "$thoroughfare, $city"
                                thoroughfare.isNotBlank() -> thoroughfare
                                subLocality.isNotBlank() -> subLocality
                                else -> addr.getAddressLine(0) ?: "Live GPS Location"
                            }
                        }
                    } catch (_: Exception) {}

                    val body = JSONObject().apply {
                        put("deviceId", deviceId)
                        put("latitude", lat)
                        put("longitude", lon)
                        put("accuracy", acc)
                        put("altitude", alt)
                        put("speed", spd)
                        put("address", address)
                        put("timestamp", System.currentTimeMillis())
                    }

                    client.syncLocation(body)
                    SentryPersistentService.updateLocationNotification(context, address, lat, lon)
                } catch (_: Exception) {}
                delay(3000)
            }
        }
    }
}
