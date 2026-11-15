package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object MediaUtils {
    fun openFile(context: Context, path: String?, mimeType: String? = null) {
        if (path.isNullOrBlank()) return
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(context, "File does not exist", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType ?: FileStorageManager.mimeTypeFor(file.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Open file"))
        } catch (e: Exception) {
            Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
        }
    }

    fun copyUriToCache(context: Context, uri: Uri, prefix: String = "attachment"): File? {
        return try {
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: prefix
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx) ?: name
            }
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
            val file = File(context.cacheDir, "${System.currentTimeMillis()}-$safe")
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            file
        } catch (_: Exception) {
            null
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.1f GB", mb / 1024.0)
    }
}
