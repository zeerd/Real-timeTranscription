package com.zeerd.real_timetranscriptionapp

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TranscriptionFileManager(private val context: Context) {
    private val TAG = "TranscriptionFileManager"
    private var userSelectedUri: Uri? = null
    private var selectedFileName: String? = null
    private val defaultFile: File = File(context.filesDir, "autosave_transcription.txt")

    suspend fun setUserSelectedUri(uri: Uri?) = withContext(Dispatchers.IO) {
        userSelectedUri = uri
        if (uri != null) {
            selectedFileName = getFileName(uri)
            migrateTempToSelected(uri)
        } else {
            selectedFileName = null
        }
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }

    private fun migrateTempToSelected(uri: Uri) {
        if (!defaultFile.exists()) return
        try {
            context.contentResolver.openOutputStream(uri, "wa")?.use { output ->
                FileInputStream(defaultFile).use { input ->
                    input.copyTo(output)
                }
                // Optional: Clear or keep temp file? User asked to transfer, 
                // so we keep temp as backup but have it in the new file now.
                Log.d(TAG, "Migrated existing transcriptions to $uri")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed: ${e.message}")
        }
    }

    suspend fun saveTranscription(text: String) = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$timestamp] $text\n\n"

        // 1. Always save to default file (internal storage)
        try {
            FileOutputStream(defaultFile, true).use { 
                it.write(entry.toByteArray())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save to default file: ${e.message}")
        }

        // 2. If user selected a file, append to it
        val uri = userSelectedUri
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri, "wa")?.use {
                    it.write(entry.toByteArray())
                }
                Log.d(TAG, "Saved to user selected file: $selectedFileName")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save to user selected file: ${e.message}")
            }
        }
    }
    
    fun getCurrentSaveLocation(): String {
        return selectedFileName ?: "Internal Storage (autosave_transcription.txt)"
    }
    
    suspend fun getHistory(): List<String> = withContext(Dispatchers.IO) {
        if (!defaultFile.exists()) return@withContext emptyList()
        try {
            defaultFile.readLines()
                .filter { it.isNotBlank() }
                .takeLast(50) // Load last 50 entries to avoid overwhelming the UI
                .reversed()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read history: ${e.message}")
            emptyList()
        }
    }

    fun getDefaultFilePath(): String = defaultFile.absolutePath
}
