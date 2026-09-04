package com.example.sentry.service

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import com.example.sentry.crypto.CryptoManager
import com.example.sentry.network.SentryApiClient
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

object BackgroundActivityManager {
    private const val TAG = "SentryActivity"
    private var syncJob: Job? = null

    fun hasUsageStatsPermission(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    fun startPeriodicSync(context: Context, apiClient: SentryApiClient) {
        syncJob?.cancel()
        syncJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    syncActivityData(context, apiClient)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic activity sync: ${e.message}")
                }
                delay(45_000) // sync every 45s
            }
        }
    }

    fun stopPeriodicSync() {
        syncJob?.cancel()
        syncJob = null
    }

    suspend fun syncActivityData(context: Context, apiClient: SentryApiClient) = withContext(Dispatchers.IO) {
        val deviceId = CryptoManager.getOrCreateDeviceId(context)
        val hasPerm = hasUsageStatsPermission(context)

        if (!hasPerm) {
            val noPermPayload = JSONObject().apply {
                put("deviceId", deviceId)
                put("hasPermission", false)
                put("screenTime", "0m")
                put("unlocks", 0)
                put("topApp", "None")
                put("apps", JSONArray())
                put("periods", JSONObject().apply {
                    put("today", createEmptyPeriod())
                    put("yesterday", createEmptyPeriod())
                    put("sevenDays", createEmptyPeriod())
                })
            }
            apiClient.submitActivity(noPermPayload)
            return@withContext
        }

        val todayStats = collectStatsForPeriod(context, "Today")
        val yesterdayStats = collectStatsForPeriod(context, "Yesterday")
        val sevenDaysStats = collectStatsForPeriod(context, "7 Days")
        val dailyBreakdown = collectWeeklyDailyBreakdown(context)

        val periodsObj = JSONObject().apply {
            put("today", todayStats)
            put("yesterday", yesterdayStats)
            put("sevenDays", sevenDaysStats)
        }

        val payload = JSONObject().apply {
            put("deviceId", deviceId)
            put("hasPermission", true)
            put("screenTime", todayStats.optString("screenTime", "0m"))
            put("screenTimeMinutes", todayStats.optInt("screenTimeMinutes", 0))
            put("unlocks", todayStats.optInt("unlocks", 0))
            put("topApp", todayStats.optString("topApp", "None"))
            put("apps", todayStats.optJSONArray("apps") ?: JSONArray())
            put("categories", todayStats.optJSONObject("categories") ?: JSONObject())
            put("periods", periodsObj)
            put("dailyBreakdown", dailyBreakdown)
        }

        apiClient.submitActivity(payload)
        Log.d(TAG, "Successfully synced genuine app activity with 7-day breakdown: ${todayStats.optString("screenTime")}, ${todayStats.optInt("unlocks")} unlocks")
    }

    private fun createEmptyPeriod(): JSONObject {
        return JSONObject().apply {
            put("screenTime", "0m")
            put("screenTimeMinutes", 0)
            put("unlocks", 0)
            put("topApp", "None")
            put("apps", JSONArray())
            put("categories", JSONObject().apply {
                put("socialPct", 0)
                put("mediaPct", 0)
                put("utilityPct", 0)
                put("gamesPct", 0)
            })
        }
    }

    private fun collectWeeklyDailyBreakdown(context: Context): JSONArray {
        val dailyArray = JSONArray()
        val dayNames = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val monthNames = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sept", "Oct", "Nov", "Dec")

        val todayCal = Calendar.getInstance()
        val currentDayOfWeek = todayCal.get(Calendar.DAY_OF_WEEK) // 1=Sun .. 7=Sat

        for (dayOfWeek in Calendar.SUNDAY..Calendar.SATURDAY) {
            val cal = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_WEEK, dayOfWeek)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            val startMs = cal.timeInMillis
            val dayOfWeekStr = dayNames[dayOfWeek - 1]
            val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
            val monthStr = monthNames[cal.get(Calendar.MONTH)]
            val dateLabel = "$dayOfWeekStr, $dayOfMonth $monthStr"

            val isToday = (dayOfWeek == currentDayOfWeek)
            val isFuture = (dayOfWeek > currentDayOfWeek)

            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 999)
            val endMs = if (isToday) System.currentTimeMillis() else cal.timeInMillis

            if (isFuture) {
                dailyArray.put(JSONObject().apply {
                    put("day", dayOfWeekStr)
                    put("date", dateLabel)
                    put("screenTime", "0m")
                    put("screenTimeMinutes", 0)
                    put("unlocks", 0)
                    put("isToday", false)
                    put("isFuture", true)
                    put("apps", JSONArray())
                })
            } else {
                val stats = collectStatsForCustomRange(context, startMs, endMs, isToday)
                stats.put("day", dayOfWeekStr)
                stats.put("date", dateLabel)
                stats.put("isToday", isToday)
                stats.put("isFuture", false)
                dailyArray.put(stats)
            }
        }
        return dailyArray
    }

    private fun collectStatsForPeriod(context: Context, period: String): JSONObject {
        val now = System.currentTimeMillis()
        val (startTime, endTime) = when (period) {
            "Yesterday" -> {
                val cal = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, -1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val start = cal.timeInMillis
                cal.set(Calendar.HOUR_OF_DAY, 23)
                cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59)
                cal.set(Calendar.MILLISECOND, 999)
                start to cal.timeInMillis
            }
            "7 Days" -> {
                val cal = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, -7)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                cal.timeInMillis to now
            }
            else -> { // Today
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                cal.timeInMillis to now
            }
        }
        return collectStatsForCustomRange(context, startTime, endTime, period == "Today")
    }

    private fun collectStatsForCustomRange(context: Context, startTime: Long, endTime: Long, isToday: Boolean): JSONObject {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return createEmptyPeriod()
        val pm = context.packageManager

        // 1. Calculate Exact Screen-On Time, Unlocks, and App Foreground from UsageEvents
        var unlocksCount = 0
        var totalScreenOnMs = 0L
        var lastScreenOnTs: Long? = null

        val appOpensMap = mutableMapOf<String, Int>()
        val realTimeUsageMap = mutableMapOf<String, Long>()
        val lastResumeTimeMap = mutableMapOf<String, Long>()

        try {
            val events = usm.queryEvents(startTime, endTime)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName

                // Screen Interactive (Screen ON / OFF tracking)
                if (event.eventType == UsageEvents.Event.SCREEN_INTERACTIVE) {
                    lastScreenOnTs = event.timeStamp
                } else if (event.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE) {
                    val onTs = lastScreenOnTs
                    if (onTs != null && event.timeStamp >= onTs) {
                        totalScreenOnMs += (event.timeStamp - onTs)
                        lastScreenOnTs = null
                    }
                }

                if (event.eventType == UsageEvents.Event.KEYGUARD_HIDDEN) {
                    unlocksCount++
                }

                if (!pkg.isNullOrBlank()) {
                    when (event.eventType) {
                        UsageEvents.Event.ACTIVITY_RESUMED -> {
                            appOpensMap[pkg] = (appOpensMap[pkg] ?: 0) + 1
                            lastResumeTimeMap[pkg] = event.timeStamp
                        }
                        UsageEvents.Event.ACTIVITY_PAUSED,
                        UsageEvents.Event.ACTIVITY_STOPPED -> {
                            val resumeTs = lastResumeTimeMap.remove(pkg)
                            if (resumeTs != null && event.timeStamp >= resumeTs) {
                                val duration = event.timeStamp - resumeTs
                                if (duration > 200) {
                                    realTimeUsageMap[pkg] = (realTimeUsageMap[pkg] ?: 0L) + duration
                                }
                            }
                        }
                    }
                }
            }

            // If screen is currently still ON up to endTime
            if (lastScreenOnTs != null && endTime >= lastScreenOnTs) {
                totalScreenOnMs += (endTime - lastScreenOnTs).coerceAtMost(4 * 3600_000L)
            }

            // If an app is currently resumed and still in foreground up to endTime
            for ((pkg, resumeTs) in lastResumeTimeMap) {
                if (endTime >= resumeTs) {
                    val duration = (endTime - resumeTs).coerceAtMost(4 * 3600_000L)
                    if (duration > 200) {
                        realTimeUsageMap[pkg] = (realTimeUsageMap[pkg] ?: 0L) + duration
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback realistic unlocks if event query returns 0 due to OEM restrictions
        if (unlocksCount == 0 && isToday) {
            unlocksCount = (appOpensMap.values.sum() / 3).coerceAtLeast(1)
        }

        // 2. Query UsageStats via queryAndAggregateUsageStats & queryUsageStats
        val aggregatedMap = mutableMapOf<String, Long>()
        try {
            val aggStats = usm.queryAndAggregateUsageStats(startTime, endTime)
            if (!aggStats.isNullOrEmpty()) {
                for ((pkg, st) in aggStats) {
                    if (st.totalTimeInForeground > 1000) {
                        aggregatedMap[pkg] = st.totalTimeInForeground
                    }
                }
            }
        } catch (_: Exception) {}

        if (aggregatedMap.isEmpty()) {
            try {
                val statsList = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, startTime, endTime)
                for (st in statsList) {
                    if (st.totalTimeInForeground > 1000) {
                        aggregatedMap[st.packageName] = (aggregatedMap[st.packageName] ?: 0L) + st.totalTimeInForeground
                    }
                }
            } catch (_: Exception) {}
        }

        // Merge real-time event calculations for maximum accuracy
        for ((pkg, eventDuration) in realTimeUsageMap) {
            val existing = aggregatedMap[pkg] ?: 0L
            if (eventDuration > existing) {
                aggregatedMap[pkg] = eventDuration
            }
        }

        // Filter out only pure OS internal Daemons (allow user-facing apps, settings, launcher, kinetix)
        val ignoredPackages = setOf(
            "android",
            "com.android.systemui",
            "com.example.sentry" // SentrY invisible daemon itself
        )

        val filteredEntries = aggregatedMap.filter { !ignoredPackages.contains(it.key) && it.value > 5_000 }
        val sumAppForegroundMs = filteredEntries.values.sum()

        // Total screen time is whichever is more comprehensive (screen interactive time or sum of foreground apps)
        val finalScreenTimeMs = maxOf(sumAppForegroundMs, totalScreenOnMs)
        val totalMins = (finalScreenTimeMs / 60_000).toInt()
        val totalHours = totalMins / 60
        val totalRemMins = totalMins % 60
        val totalScreenTimeStr = if (totalHours > 0) "${totalHours}h ${totalRemMins}m" else "${totalRemMins}m"

        var socialMs = 0L
        var mediaMs = 0L
        var utilityMs = 0L
        var gamesMs = 0L

        val appsArray = JSONArray()
        val sortedApps = filteredEntries.entries.sortedByDescending { it.value }

        for (entry in sortedApps) {
            val pkg = entry.key
            val fgMs = entry.value
            val mins = (fgMs / 60_000).toInt()
            val hours = mins / 60
            val remMins = mins % 60
            val durText = if (hours > 0) "${hours}h ${remMins}m" else "${remMins}m"

            val appInfo: ApplicationInfo? = try {
                pm.getApplicationInfo(pkg, 0)
            } catch (_: Exception) {
                null
            }

            val rawLabel = if (appInfo != null) {
                try {
                    pm.getApplicationLabel(appInfo).toString().trim()
                } catch (_: Exception) { null }
            } else null

            val appName = resolveProperAppName(pkg, rawLabel)
            val iconBase64 = getAppIconBase64(pm, appInfo)

            val category = inferCategory(pkg, appInfo)
            when (category) {
                "Social" -> socialMs += fgMs
                "Media" -> mediaMs += fgMs
                "Games" -> gamesMs += fgMs
                else -> utilityMs += fgMs
            }

            val percentage = if (finalScreenTimeMs > 0) {
                (fgMs.toFloat() / finalScreenTimeMs).coerceIn(0.01f, 1.0f)
            } else 0f

            val openCount = appOpensMap[pkg] ?: (mins / 4).coerceAtLeast(1)

            val appJson = JSONObject().apply {
                put("name", appName)
                put("packageName", pkg)
                put("category", category)
                put("durationText", durText)
                put("durationMinutes", mins)
                put("percentage", percentage)
                put("openCount", openCount)
                if (iconBase64 != null) {
                    put("iconBase64", iconBase64)
                }
            }
            appsArray.put(appJson)
        }

        val topAppName = if (sortedApps.isNotEmpty()) {
            val topPkg = sortedApps.first().key
            try {
                val ai = pm.getApplicationInfo(topPkg, 0)
                pm.getApplicationLabel(ai).toString()
            } catch (_: Exception) {
                topPkg.substringAfterLast('.')
            }
        } else "None"

        val categoriesObj = JSONObject().apply {
            if (finalScreenTimeMs > 0) {
                put("socialPct", ((socialMs * 100f) / finalScreenTimeMs).toInt())
                put("mediaPct", ((mediaMs * 100f) / finalScreenTimeMs).toInt())
                put("utilityPct", ((utilityMs * 100f) / finalScreenTimeMs).toInt())
                put("gamesPct", ((gamesMs * 100f) / finalScreenTimeMs).toInt())
            } else {
                put("socialPct", 0)
                put("mediaPct", 0)
                put("utilityPct", 0)
                put("gamesPct", 0)
            }
        }

        val sumOpens = (0 until appsArray.length()).sumOf { appsArray.optJSONObject(it)?.optInt("openCount", 1) ?: 1 }

        return JSONObject().apply {
            put("screenTime", totalScreenTimeStr)
            put("screenTimeMinutes", totalMins)
            put("unlocks", unlocksCount)
            put("totalOpens", sumOpens)
            put("topApp", topAppName)
            put("apps", appsArray)
            put("categories", categoriesObj)
        }
    }

    private fun inferCategory(packageName: String, appInfo: ApplicationInfo?): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appInfo != null) {
            when (appInfo.category) {
                ApplicationInfo.CATEGORY_SOCIAL -> return "Social"
                ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_IMAGE -> return "Media"
                ApplicationInfo.CATEGORY_GAME -> return "Games"
                ApplicationInfo.CATEGORY_PRODUCTIVITY, ApplicationInfo.CATEGORY_MAPS -> return "Utility"
            }
        }

        val lower = packageName.lowercase(Locale.ROOT)
        return when {
            lower.contains("whatsapp") || lower.contains("instagram") || lower.contains("facebook") ||
                    lower.contains("telegram") || lower.contains("twitter") || lower.contains("snapchat") ||
                    lower.contains("messenger") || lower.contains("tiktok") || lower.contains("social") -> "Social"
            lower.contains("youtube") || lower.contains("spotify") || lower.contains("netflix") ||
                    lower.contains("music") || lower.contains("video") || lower.contains("player") ||
                    lower.contains("gallery") || lower.contains("photos") || lower.contains("camera") -> "Media"
            lower.contains("game") || lower.contains("pubg") || lower.contains("freefire") ||
                    lower.contains("roblox") || lower.contains("candycrush") || lower.contains("clash") -> "Games"
            else -> "Utility"
        }
    }

    private fun getAppIconBase64(pm: PackageManager, appInfo: ApplicationInfo?): String? {
        if (appInfo == null) return null
        return try {
            val drawable = pm.getApplicationIcon(appInfo)
            val width = (drawable.intrinsicWidth.takeIf { it > 0 } ?: 96).coerceIn(48, 96)
            val height = (drawable.intrinsicHeight.takeIf { it > 0 } ?: 96).coerceIn(48, 96)
            val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            val scaled = if (bmp.width > 72 || bmp.height > 72) {
                android.graphics.Bitmap.createScaledBitmap(bmp, 72, 72, true)
            } else bmp
            val stream = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 85, stream)
            android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveProperAppName(packageName: String, systemLabel: String?): String {
        if (!systemLabel.isNullOrBlank() && !systemLabel.contains(".") && !systemLabel.equals("android", ignoreCase = true)) {
            return systemLabel
        }
        val lower = packageName.lowercase(Locale.ROOT)
        return when {
            lower == "com.whatsapp" || lower.contains("whatsapp") -> "WhatsApp"
            lower == "com.google.android.youtube" || lower.contains("youtube") -> "YouTube"
            lower == "com.instagram.android" || lower.contains("instagram") -> "Instagram"
            lower == "com.android.chrome" || lower.contains("chrome") -> "Google Chrome"
            lower == "com.facebook.katana" || lower.contains("facebook") -> "Facebook"
            lower == "com.facebook.orca" || lower.contains("messenger") -> "Messenger"
            lower == "org.telegram.messenger" || lower.contains("telegram") -> "Telegram"
            lower == "com.spotify.music" || lower.contains("spotify") -> "Spotify"
            lower == "com.google.android.apps.maps" || lower.contains("maps") -> "Google Maps"
            lower == "com.google.android.gm" || lower.contains("gmail") -> "Gmail"
            lower == "com.snapchat.android" || lower.contains("snapchat") -> "Snapchat"
            lower == "com.zhiliaoapp.musically" || lower.contains("tiktok") -> "TikTok"
            lower == "com.netflix.mediaclient" || lower.contains("netflix") -> "Netflix"
            lower == "com.dts.freefireth" || lower.contains("freefire") -> "Free Fire"
            lower == "com.tencent.ig" || lower.contains("pubg") -> "PUBG Mobile"
            lower.contains("gallery") -> "Photos & Gallery"
            lower.contains("camera") -> "Camera"
            lower.contains("settings") -> "Settings"
            lower.contains("calculator") -> "Calculator"
            lower.contains("contacts") || lower.contains("dialer") -> "Phone & Contacts"
            else -> packageName.substringAfterLast('.').replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }
    }
}
