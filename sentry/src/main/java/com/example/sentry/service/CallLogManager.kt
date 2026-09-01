package com.example.sentry.service

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.sentry.crypto.CryptoManager
import com.example.sentry.network.SentryApiClient
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

object CallLogManager {
    private const val TAG = "CallLogManager"
    private var syncJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var observerRegistered = false

    fun startSync(context: Context) {
        // Register ContentObserver to trigger immediate sync on incoming/outgoing/missed call events
        if (!observerRegistered) {
            try {
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        super.onChange(selfChange, uri)
                        scope.launch {
                            val client = SentryApiClient(context)
                            syncCallLogs(context, client)
                        }
                    }
                }
                context.contentResolver.registerContentObserver(
                    CallLog.Calls.CONTENT_URI,
                    true,
                    observer
                )
                observerRegistered = true
                Log.d(TAG, "CallLog ContentObserver registered for live real-time detection")
            } catch (e: Exception) {
                Log.w(TAG, "Could not register CallLog observer: ${e.message}")
            }
        }

        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            val client = SentryApiClient(context)
            while (isActive) {
                try {
                    syncCallLogs(context, client)
                } catch (e: Exception) {
                    Log.w(TAG, "Error in CallLog sync loop: ${e.message}")
                }
                delay(15_000) // Poll every 15 seconds
            }
        }
    }

    suspend fun syncCallLogs(context: Context, client: SentryApiClient) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED

        val deviceId = CryptoManager.getOrCreateDeviceId(context)
        val jsonArray = JSONArray()

        if (hasPermission) {
            val projection = arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            )

            val cursor: Cursor? = try {
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${CallLog.Calls.DATE} DESC"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to query CallLog provider: ${e.message}")
                null
            }

            cursor?.use { c ->
                val idIdx = c.getColumnIndex(CallLog.Calls._ID)
                val numberIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIdx = c.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)
                val durationIdx = c.getColumnIndex(CallLog.Calls.DURATION)

                val sdfTime = SimpleDateFormat("hh:mm a", Locale.getDefault())
                val sdfDate = SimpleDateFormat("MMM dd", Locale.getDefault())

                var count = 0
                while (c.moveToNext() && count < 80) {
                    val id = if (idIdx >= 0) c.getString(idIdx) else "call_$count"
                    val rawNumber = if (numberIdx >= 0) c.getString(numberIdx) else null
                    val number = if (!rawNumber.isNullOrBlank() && rawNumber != "-1" && rawNumber != "-2") rawNumber else "Private Number"
                    var name = if (nameIdx >= 0) c.getString(nameIdx) else null
                    val rawType = if (typeIdx >= 0) c.getInt(typeIdx) else 1
                    val dateEpoch = if (dateIdx >= 0) c.getLong(dateIdx) else System.currentTimeMillis()
                    val durationSec = if (durationIdx >= 0) c.getLong(durationIdx) else 0L

                    // Clean name if empty or literal "NULL"
                    if (name.isNullOrBlank() || name.equals("NULL", ignoreCase = true) || name.equals("null", ignoreCase = true)) {
                        name = resolveContactName(context, number)
                    }

                    // Map all vendor types (Standard Android + Realme/Oppo/Xiaomi HD Calling 100/101)
                    val typeStr = when (rawType) {
                        1, 100 -> "INCOMING"
                        2, 101 -> "OUTGOING"
                        3 -> "MISSED"
                        5, 6 -> "REJECTED"
                        4 -> "MISSED" // Voicemail
                        else -> {
                            if (rawType in listOf(50, 51, 27, -5)) {
                                if (durationSec > 0) "INCOMING" else "MISSED"
                            } else {
                                if (durationSec > 0) "INCOMING" else "MISSED"
                            }
                        }
                    }

                    // Format human readable date
                    val nowCal = Calendar.getInstance()
                    val callCal = Calendar.getInstance().apply { timeInMillis = dateEpoch }
                    val dateFormatted = when {
                        nowCal.get(Calendar.YEAR) == callCal.get(Calendar.YEAR) &&
                                nowCal.get(Calendar.DAY_OF_YEAR) == callCal.get(Calendar.DAY_OF_YEAR) ->
                            "Today, ${sdfTime.format(Date(dateEpoch))}"

                        nowCal.get(Calendar.YEAR) == callCal.get(Calendar.YEAR) &&
                                nowCal.get(Calendar.DAY_OF_YEAR) - callCal.get(Calendar.DAY_OF_YEAR) == 1 ->
                            "Yesterday, ${sdfTime.format(Date(dateEpoch))}"

                        else ->
                            "${sdfDate.format(Date(dateEpoch))}, ${sdfTime.format(Date(dateEpoch))}"
                    }

                    // Format duration
                    val durationStr = when {
                        typeStr == "MISSED" || typeStr == "REJECTED" || durationSec <= 0 ->
                            if (typeStr == "REJECTED") "Declined" else "Missed"
                        durationSec < 60 -> "${durationSec}s"
                        else -> "${durationSec / 60}m ${durationSec % 60}s"
                    }

                    val obj = JSONObject().apply {
                        put("id", id)
                        put("number", number)
                        put("name", if (!name.isNullOrBlank()) name else JSONObject.NULL)
                        put("type", typeStr)
                        put("date", dateFormatted)
                        put("duration", durationStr)
                        put("timestamp", dateEpoch)
                    }
                    jsonArray.put(obj)
                    count++
                }
            }
        }

        if (jsonArray.length() > 0) {
            client.syncCallLogs(jsonArray)
            Log.d(TAG, "Synced ${jsonArray.length()} real hardware call logs to cloud backend for $deviceId")
        }
    }

    private fun resolveContactName(context: Context, phoneNumber: String): String? {
        if (phoneNumber.isBlank() || phoneNumber == "Private Number") return null
        val hasContactsPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasContactsPermission) return null

        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIdx >= 0) cursor.getString(nameIdx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
