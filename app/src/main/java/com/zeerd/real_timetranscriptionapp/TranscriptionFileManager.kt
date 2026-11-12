package com.zeerd.real_timetranscriptionapp

import android.content.Context
import android.net.Uri
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.Locale

class TranscriptionFileManager(private val context: Context) {
    private val TAG = "TranscriptionFileManager"
    // 用户通过 SAF 直接选择的保存文件（文档 URI），转写与摘要都写入此文件
    private var userSelectedFileUri: Uri? = null
    private var selectedFileName: String? = null
    // 内部自动保存（未选择文件时回退）
    private val defaultRawFile: File = File(context.filesDir, "autosave_transcription_raw.txt")

    suspend fun setUserSelectedFile(uri: Uri?) = withContext(Dispatchers.IO) {
        Log.d(TAG, "setUserSelectedFile called: ${if (uri != null) uri.toString() else "null (revert to internal storage)"}")
        userSelectedFileUri = uri
        if (uri != null) {
            selectedFileName = getDisplayName(uri)
            migrateTempToSelected()
        } else {
            selectedFileName = null
            Log.i(TAG, "User cleared selected save file, reverting to internal autosave")
        }
    }

    private fun getDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx != -1) cursor.getString(idx) else null
                } else null
            }
        } catch (e: Exception) {
            uri.path?.substringAfterLast('/')
        }
    }

    private fun migrateTempToSelected() {
        migrateSingle(defaultRawFile, userSelectedFileUri, "raw")
    }

    private fun migrateSingle(tempFile: File, targetUri: Uri?, kind: String) {
        if (targetUri == null || !tempFile.exists()) return
        try {
            context.contentResolver.openOutputStream(targetUri, "wa")?.use { output ->
                FileInputStream(tempFile).use { input ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Migrated existing $kind transcriptions to selected file")
        } catch (e: Exception) {
            Log.e(TAG, "Migration of $kind failed: ${e.message}")
        }
    }

    private suspend fun appendEntry(targetFile: File?, targetUri: Uri?, entry: String) {
        // 优先写入用户选择的文件
        if (targetUri != null) {
            try {
                context.contentResolver.openOutputStream(targetUri, "wa")?.use {
                    it.write(entry.toByteArray())
                }
                return
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save to user file: ${e.message}")
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

    // 保存转写结果（已按同说话人合并）
    suspend fun saveTranscription(text: String) = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$timestamp] $text\n\n"
        Log.d(TAG, "saveTranscription called (${text.length} chars)")
        appendEntry(defaultRawFile, userSelectedFileUri, entry)
    }

    /**
     * 从指定 URI、File 或当前记录文件读取文本，并过滤掉已有摘要块，仅保留原始转写内容。
     *
     * @param uri SAF 文档 URI（若指定则优先从 URI 读取）
     * @param file File 实例（若指定且 uri 为 null，则从 File 读取）
     * @return 过滤后的纯转写文本
     */
    suspend fun readRecordContent(uri: Uri? = null, file: File? = null): String = withContext(Dispatchers.IO) {
        val targetUri = uri ?: userSelectedFileUri
        val targetFile = file ?: defaultRawFile

        val rawText = try {
            if (targetUri != null) {
                context.contentResolver.openInputStream(targetUri)?.use { input ->
                    input.bufferedReader().use { it.readText() }
                } ?: ""
            } else if (targetFile.exists()) {
                targetFile.readText()
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read record file content: ${e.message}", e)
            ""
        }

        extractTranscriptionOnly(rawText)
    }

    /**
     * 过滤掉转写文本中的摘要块（以 `=====` 开头的块），避免重新总结时重复包含旧摘要。
     *
     * 解析规则：以「整行以 `=====` 开头」作为块分隔标记，而不是「进入/退出」状态机。
     * 这样即使摘要正文自身包含 `=====` 分隔线，也只会被当作块边界，
     * 不会把其后的原始转写内容一并吞掉。
     */
    fun extractTranscriptionOnly(fullText: String): String {
        if (fullText.isBlank()) return ""
        val resultLines = mutableListOf<String>()
        var inSummaryBlock = false

        for (line in fullText.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("=====")) {
                // 分隔标记本身不入正文；翻转当前所在块
                inSummaryBlock = !inSummaryBlock
                continue
            }
            if (!inSummaryBlock) {
                resultLines.add(line)
            }
        }
        return resultLines.joinToString("\n").trim()
    }

    /**
     * 保存一次采集会话的 LLM 摘要，追加到同一个转写文件末尾（与原始稿合并，不再单开文件）。
     *
     * 注意：targetUri / targetFile 必须显式传入，不要依赖默认值。
     * 默认参数在调用点求值，若默认取可变的 [userSelectedFileUri]，会因调用时机不同
     * 而把摘要写到错误的文件（例如「重新总结」选了外部文件后，再对当前会话总结）。
     *
     * @param targetUri 目标 SAF 文档 URI；为 null 时回退到 [defaultRawFile]
     * @param targetFile 目标内部文件；为 null 时回退到 [defaultRawFile]
     * @return 实际写入的文件路径（内部回退）或 URI 描述，便于 UI 提示
     */
    suspend fun saveSummary(
        text: String,
        targetUri: Uri?,
        targetFile: File?
    ): String = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "\n===== 摘要 ($timestamp) =====\n\n$text\n\n"
        Log.d(TAG, "saveSummary called (${text.length} chars), uri=$targetUri, file=${targetFile?.absolutePath}")
        val uriToSave = targetUri ?: userSelectedFileUri
        val fileToSave = targetFile ?: defaultRawFile
        appendEntry(fileToSave, uriToSave, entry)
        uriToSave?.toString() ?: fileToSave.absolutePath
    }

    /**
     * 重新对记录文件生成摘要。
     * 读取指定或默认记录文件中的转写内容，调用 [LocalLlmManager.summarize]，
     * 并将生成的新摘要追加保存到文件中。
     *
     * @param llmManager 本地 LLM 管理器
     * @param uri 可选的记录文件 URI
     * @param file 可选的记录文件 File
     * @param onProgress 可选的进度回调
     * @return 生成的摘要文本；失败或无内容时返回空字符串
     */
    suspend fun reSummarizeRecordFile(
        llmManager: LocalLlmManager,
        uri: Uri? = null,
        file: File? = null,
        onProgress: ((current: Int, total: Int, description: String) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        val transcriptText = readRecordContent(uri = uri, file = file)
        if (transcriptText.isBlank()) {
            Log.w(TAG, "reSummarizeRecordFile: No transcription content found in record file")
            return@withContext ""
        }

        Log.i(TAG, "reSummarizeRecordFile: Starting re-summarization of ${transcriptText.length} chars")
        val newSummary = llmManager.summarize(transcriptText, onProgress = onProgress)
        if (newSummary.isNotBlank()) {
            val targetUri = uri ?: userSelectedFileUri
            val targetFile = file ?: defaultRawFile
            saveSummary(newSummary, targetUri = targetUri, targetFile = targetFile)
        }
        newSummary
    }
    
    fun getCurrentSaveLocation(): String {
        return selectedFileName ?: "Internal Storage (autosave_transcription_raw.txt)"
    }

    /**
     * 清空内部 autosave 历史文件（autosave_transcription.txt）。
     * 注意：仅清理内部自动保存记录，不会删除用户通过选择器指定的外部保存文件。
     */
    suspend fun clearAutosaveHistory() = withContext(Dispatchers.IO) {
        try {
            defaultRawFile.writeText("")
            Log.i(TAG, "Cleared autosave history: ${defaultRawFile.absolutePath}")
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

    fun getDefaultFilePath(): String = defaultRawFile.absolutePath

    // ===== 调试用 WAV 落盘管理 =====
    // 与 TranscriptionService 中 whisper.dumpDir = File(filesDir, "wav_dump") 保持一致。
    private val wavDumpDir: File = File(context.filesDir, "wav_dump")

    /** 返回 WAV 落盘目录（可能不存在）。 */
    fun getWavDumpDir(): File = wavDumpDir

    /** 当前已保存的 WAV 段数。 */
    suspend fun getWavDumpCount(): Int = withContext(Dispatchers.IO) {
        if (!wavDumpDir.exists()) return@withContext 0
        wavDumpDir.listFiles()?.count { it.isFile && it.name.endsWith(".wav", ignoreCase = true) } ?: 0
    }

    /**
     * 把当前所有 WAV 段打包成一个 zip 文件，便于一键导出试听。
     * @return 生成的 zip 文件（位于缓存目录），无 WAV 时返回 null。
     */
    suspend fun zipWavDumps(): File? = withContext(Dispatchers.IO) {
        if (!wavDumpDir.exists()) return@withContext null
        val wavs = wavDumpDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".wav", ignoreCase = true) }
            ?: emptyList()
        if (wavs.isEmpty()) return@withContext null

        val zipFile = File(context.cacheDir, "wav_dump_${System.currentTimeMillis()}.zip")
        try {
            java.util.zip.ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                val buf = ByteArray(8192)
                wavs.forEach { wav ->
                    java.util.zip.ZipEntry(wav.name).also { zos.putNextEntry(it) }
                    FileInputStream(wav).use { fis ->
                        var len: Int
                        while (fis.read(buf).also { len = it } > 0) {
                            zos.write(buf, 0, len)
                        }
                    }
                    zos.closeEntry()
                }
            }
            Log.i(TAG, "Zipped ${wavs.size} WAV files into ${zipFile.absolutePath}")
            zipFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to zip WAV dumps: ${e.message}")
            null
        }
    }

    /**
     * 清空所有已保存的 WAV 段文件。
     * @return 被删除的文件数。
     */
    suspend fun clearWavDumps(): Int = withContext(Dispatchers.IO) {
        if (!wavDumpDir.exists()) return@withContext 0
        val wavs = wavDumpDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".wav", ignoreCase = true) }
            ?: emptyList()
        var deleted = 0
        wavs.forEach { if (it.delete()) deleted++ }
        Log.i(TAG, "Cleared $deleted WAV dump file(s)")
        deleted
    }
}
