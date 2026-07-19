package com.zeerd.real_timetranscriptionapp

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
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
    // 用户通过 SAF 选择的目录（树 URI），用于同时保存「原始稿」与「正式稿」
    private var userSelectedTreeUri: Uri? = null
    private var selectedDirName: String? = null
    // 目录内两个文件的 URI（分别保存原始 ASR 结果与 LLM 润色结果）
    private var rawFileUri: Uri? = null
    private var formalFileUri: Uri? = null
    // 内部自动保存（未选择目录时回退），同样区分原始稿与正式稿
    private val defaultRawFile: File = File(context.filesDir, "autosave_transcription_raw.txt")
    private val defaultFormalFile: File = File(context.filesDir, "autosave_transcription_formal.txt")

    suspend fun setUserSelectedDir(uri: Uri?) = withContext(Dispatchers.IO) {
        Log.d(TAG, "setUserSelectedDir called: ${if (uri != null) uri.toString() else "null (revert to internal storage)"}")
        userSelectedTreeUri = uri
        if (uri != null) {
            selectedDirName = getTreeDisplayName(uri)
            rawFileUri = createFileInDir(uri, "transcription_raw.txt")
            formalFileUri = createFileInDir(uri, "transcription_formal.txt")
            migrateTempToSelected()
        } else {
            selectedDirName = null
            rawFileUri = null
            formalFileUri = null
            Log.i(TAG, "User cleared selected save dir, reverting to internal autosave")
        }
    }

    private fun getTreeDisplayName(uri: Uri): String? {
        return try {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(
                uri, DocumentsContract.getTreeDocumentId(uri)
            )
            context.contentResolver.query(docUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx != -1) cursor.getString(idx) else null
                } else null
            }
        } catch (e: Exception) {
            uri.path?.substringAfterLast('/')
        }
    }

    private fun createFileInDir(treeUri: Uri, fileName: String): Uri? {
        return try {
            val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return null
            val existing = tree.findFile(fileName)
            val doc = existing ?: tree.createFile("text/plain", fileName)
            doc?.uri
        } catch (e: Exception) {
            Log.e(TAG, "createFileInDir($fileName) failed: ${e.message}")
            null
        }
    }

    private fun migrateTempToSelected() {
        migrateSingle(defaultRawFile, rawFileUri, "raw")
        migrateSingle(defaultFormalFile, formalFileUri, "formal")
        migrateSummaries()
    }

    private fun migrateSingle(tempFile: File, targetUri: Uri?, kind: String) {
        if (targetUri == null || !tempFile.exists()) return
        try {
            context.contentResolver.openOutputStream(targetUri, "wa")?.use { output ->
                FileInputStream(tempFile).use { input ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Migrated existing $kind transcriptions to selected dir")
        } catch (e: Exception) {
            Log.e(TAG, "Migration of $kind failed: ${e.message}")
        }
    }

    /**
     * 把内部自动保存的摘要文件（summary_*.txt）一并迁移到用户选择的目录。
     * 摘要文件名带时间戳，需逐个复制到目标树中（保留原文件名，避免互相覆盖）。
     */
    private fun migrateSummaries() {
        if (userSelectedTreeUri == null) return
        try {
            val summaries = context.filesDir.listFiles()
                ?.filter { it.name.startsWith("summary_") && it.name.endsWith(".txt") }
                ?: emptyList()
            if (summaries.isEmpty()) return
            summaries.forEach { file ->
                val targetUri = createFileInDir(userSelectedTreeUri!!, file.name)
                if (targetUri != null) {
                    context.contentResolver.openOutputStream(targetUri)?.use { output ->
                        FileInputStream(file).use { input -> input.copyTo(output) }
                    }
                    Log.d(TAG, "Migrated summary ${file.name} to selected dir")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Migration of summaries failed: ${e.message}")
        }
    }

    private suspend fun appendEntry(targetFile: File?, targetUri: Uri?, entry: String) {
        // 优先写入用户选择的目录文件
        if (targetUri != null) {
            try {
                context.contentResolver.openOutputStream(targetUri, "wa")?.use {
                    it.write(entry.toByteArray())
                }
                return
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save to user dir file: ${e.message}")
            }
        }
        // 回退到内部自动保存文件
        if (targetFile != null) {
            try {
                FileOutputStream(targetFile, true).use { it.write(entry.toByteArray()) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save to default file: ${e.message}")
            }
        }
    }

    // 保存原始 ASR 结果（LLM 润色之前）
    suspend fun saveTranscription(text: String) = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$timestamp] $text\n\n"
        Log.d(TAG, "saveTranscription called (${text.length} chars)")
        appendEntry(defaultRawFile, rawFileUri, entry)
    }

    // 保存 LLM 润色后的正式稿（与原始 ASR 结果分开保存）
    suspend fun saveRegularized(text: String) = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$timestamp] $text\n\n"
        Log.d(TAG, "saveRegularized called (${text.length} chars)")
        appendEntry(defaultFormalFile, formalFileUri, entry)
    }

    /**
     * 保存一次采集会话的 LLM 摘要到独立文件（summary.txt / summary_<时间戳>.txt）。
     * 与原始稿、正式稿分开，便于用户单独查看每次会话的总结。
     *
     * @return 实际写入的文件路径（内部回退）或 URI 描述，便于 UI 提示
     */
    suspend fun saveSummary(text: String): String = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val header = "===== 摘要 (${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}) =====\n\n"
        val content = header + text + "\n\n"

        // 优先写入用户选择的目录（带时间戳的独立文件名，避免覆盖）
        if (userSelectedTreeUri != null) {
            try {
                val tree = DocumentFile.fromTreeUri(context, userSelectedTreeUri!!)
                val doc = tree?.createFile("text/plain", "summary_$timestamp")
                doc?.uri?.let { uri ->
                    context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                    Log.i(TAG, "saveSummary saved to user dir: $uri")
                    return@withContext uri.toString()
                }
            } catch (e: Exception) {
                Log.e(TAG, "saveSummary to user dir failed: ${e.message}")
            }
        }

        // 回退到内部存储（带时间戳，避免多次摘要互相覆盖）
        val file = File(context.filesDir, "summary_$timestamp.txt")
        try {
            file.writeText(content)
            Log.i(TAG, "saveSummary saved to internal: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "saveSummary to internal failed: ${e.message}")
        }
        file.absolutePath
    }
    
    fun getCurrentSaveLocation(): String {
        return selectedDirName ?: "Internal Storage (autosave_transcription_*.txt)"
    }

    /**
     * 清空内部 autosave 历史文件（autosave_transcription.txt）。
     * 注意：仅清理内部自动保存记录，不会删除用户通过选择器指定的外部保存文件。
     */
    suspend fun clearAutosaveHistory() = withContext(Dispatchers.IO) {
        try {
            defaultRawFile.writeText("")
            defaultFormalFile.writeText("")
            Log.i(TAG, "Cleared autosave history: ${defaultRawFile.absolutePath}, ${defaultFormalFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear autosave history: ${e.message}")
        }
    }
    
    suspend fun getHistory(): List<String> = withContext(Dispatchers.IO) {
        if (!defaultRawFile.exists()) return@withContext emptyList()
        try {
            defaultRawFile.readLines()
                .filter { it.isNotBlank() }
                .takeLast(50) // Load last 50 entries to avoid overwhelming the UI
                .reversed()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read history: ${e.message}")
            emptyList()
        }
    }

    // 读取内部自动保存的正式稿历史（用于重启后恢复「正式稿」标签页）
    suspend fun getFormalHistory(): List<String> = withContext(Dispatchers.IO) {
        if (!defaultFormalFile.exists()) return@withContext emptyList()
        try {
            defaultFormalFile.readLines()
                .filter { it.isNotBlank() }
                .takeLast(50)
                .reversed()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read formal history: ${e.message}")
            emptyList()
        }
    }

    fun getDefaultFilePath(): String = defaultRawFile.absolutePath
}
