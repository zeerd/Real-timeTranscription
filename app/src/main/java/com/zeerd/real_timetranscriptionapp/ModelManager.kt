package com.zeerd.real_timetranscriptionapp

import android.app.DownloadManager
import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.Collections
import java.util.Locale

data class ModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val encoderUrl: String,
    val decoderUrl: String,
    val tokensUrl: String,
    val sizeLabel: String
)

sealed class ModelStatus {
    object NOT_DOWNLOADED : ModelStatus()
    data class DOWNLOADING(val progress: Float) : ModelStatus()
    data class EXTRACTING(val progress: Float) : ModelStatus()
    object READY : ModelStatus()
    object ERROR : ModelStatus()
}

class ModelManager(private val context: Context) {
    private val TAG = "ModelManager"
    private val rootDir = File(context.filesDir, "models")
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val activeDownloads = mutableMapOf<String, Long>()
    private val extractingModels = Collections.synchronizedSet(mutableSetOf<String>())

    private val _customModels = MutableStateFlow<List<ModelInfo>>(emptyList())

    private val staticModels = listOf(
        ModelInfo(
            id = "vad-silero",
            name = "Silero VAD",
            description = "Voice Activity Detection model. Required for all transcription.",
            encoderUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            decoderUrl = "",
            tokensUrl = "",
            sizeLabel = "~600KB"
        ),
        ModelInfo(
            id = "whisper-tiny",
            name = "Whisper Tiny (Multi)",
            description = "Fastest, lowest accuracy. Good for high-end devices or testing.",
            encoderUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2",
            decoderUrl = "", // We handle tar.bz2 for these
            tokensUrl = "",
            sizeLabel = "~150MB"
        ),
        ModelInfo(
            id = "whisper-base",
            name = "Whisper Base (Multi)",
            description = "Better accuracy than Tiny. Recommended for most users.",
            encoderUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2",
            decoderUrl = "",
            tokensUrl = "",
            sizeLabel = "~290MB"
        ),
        ModelInfo(
            id = "whisper-small",
            name = "Whisper Small (Multi)",
            description = "High accuracy. Requires more memory and processing power.",
            encoderUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2",
            decoderUrl = "",
            tokensUrl = "",
            sizeLabel = "~960MB"
        ),
        ModelInfo(
            id = "speaker-ecapa",
            name = "ECAPA-TDNN Speaker Embedding",
            description = "Identifies different speakers based on voiceprints.",
            encoderUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx",
            decoderUrl = "",
            tokensUrl = "",
            sizeLabel = "~20MB"
        ),
        ModelInfo(
            id = "llm-gemma-2b",
            name = "Gemma 2B (LiteRT)",
            description = "Google LiteRT LM for semantic paragraphing and formatting.",
            encoderUrl = "https://huggingface.co/google/gemma-2b-it-tflite/resolve/main/gemma-2b-it-cpu-int8.tflite", // Example URL
            decoderUrl = "",
            tokensUrl = "",
            sizeLabel = "~1.4GB"
        )
    )

    val availableModels: List<ModelInfo>
        get() = staticModels + _customModels.value

    // 添加一个 Flow 以便 UI 观察模型列表的变化
    val customModels = _customModels.asStateFlow()

    private val _modelStatuses = MutableStateFlow<Map<String, ModelStatus>>(emptyMap())
    val modelStatuses = _modelStatuses.asStateFlow()

    private val _settingsChanged = MutableStateFlow(0)
    val settingsChanged = _settingsChanged.asStateFlow()

    init {
        if (!rootDir.exists()) rootDir.mkdirs()
        discoverCustomModels()
        // 系统性修复：启动时检查是否有已下载但未解压的 tar.bz2 文件，自动触发解压流程
        checkPendingDownloads()
        refreshStatuses()
    }

    private fun discoverCustomModels() {
        val custom = rootDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("custom-") }?.map { dir ->
            val isLlm = dir.listFiles()?.any { it.name.endsWith(".tflite") || it.name.endsWith(".bin") || it.name.endsWith(".litertlm") } ?: false
            val isSpeaker = dir.name.startsWith("custom-speaker-")
            ModelInfo(
                id = dir.name,
                name = dir.name.removePrefix("custom-").replace("-", " ").replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() },
                description = when {
                    isLlm -> "Custom LLM model imported by user."
                    isSpeaker -> "Custom speaker diarization model imported by user."
                    else -> "Custom ASR model imported by user."
                },
                encoderUrl = "",
                decoderUrl = "",
                tokensUrl = "",
                sizeLabel = formatSize(computeDirectorySize(dir))
            )
        } ?: emptyList()
        _customModels.value = custom
        Log.i(TAG, "[DISCOVER] custom models found: ${custom.map { it.id }}")
    }

    // 递归计算目录（含子目录）中所有文件的总字节数
    private fun computeDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { computeDirectorySize(it) } ?: 0L
    }

    // 将字节数格式化为人类可读的字符串（如 1.4GB / 290MB / 600KB）
    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "Local"
        val kb = 1024L
        val mb = kb * 1024L
        val gb = mb * 1024L
        return when {
            bytes >= gb -> String.format(Locale.ROOT, "%.1fGB", bytes.toDouble() / gb)
            bytes >= mb -> String.format(Locale.ROOT, "%.0fMB", bytes.toDouble() / mb)
            bytes >= kb -> String.format(Locale.ROOT, "%.0fKB", bytes.toDouble() / kb)
            else -> "${bytes}B"
        }
    }

    private fun checkPendingDownloads() {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        availableModels.forEach { model ->
            if (model.id == "vad-silero") return@forEach
            if (model.id.startsWith("llm")) {
                val modelFile = File(downloadDir, "${model.id}.tflite")
                if (modelFile.exists() && !isModelReady(model.id)) {
                    Log.i(TAG, "Found pending LLM model for ${model.id}, triggering move...")
                    scope.launch { handleDownloadComplete(model.id) }
                }
                return@forEach
            }
            if (isSingleFileModel(model)) {
                val fileName = model.encoderUrl.substringAfterLast("/")
                val file = File(downloadDir, fileName)
                if (file.exists() && !isModelReady(model.id)) {
                    Log.i(TAG, "Found pending single-file model for ${model.id}, triggering copy...")
                    scope.launch { handleDownloadComplete(model.id) }
                }
                return@forEach
            }
            val archive = File(downloadDir, "${model.id}.tar.bz2")
            if (archive.exists() && !isModelReady(model.id)) {
                Log.i(TAG, "Found pending archive for ${model.id}, triggering extraction...")
                scope.launch { extractTarBz2(model.id, archive) }
            }
        }
    }

    fun refreshStatuses() {
        val currentStatuses = _modelStatuses.value
        val statuses = availableModels.associate { model ->
            val ready = isModelReady(model.id)
            Log.d(TAG, "Checking status for ${model.id}: ready=$ready")
            val status = if (ready) {
                ModelStatus.READY
            } else {
                // 关键修复：如果当前正在下载或解压，保留该状态，不要跳回 NOT_DOWNLOADED
                val current = currentStatuses[model.id]
                if (current is ModelStatus.DOWNLOADING || current is ModelStatus.EXTRACTING) {
                    current
                } else {
                    ModelStatus.NOT_DOWNLOADED
                }
            }
            model.id to status
        }
        _modelStatuses.value = statuses
    }

    fun isModelReady(modelId: String): Boolean {
        if (extractingModels.contains(modelId)) return false
        if (modelId == "vad-silero") {
            val fileInFiles = File(context.filesDir, "silero_vad.onnx")
            if (fileInFiles.exists()) return true
            return try { context.assets.open("silero_vad.onnx").close(); true } catch (e: Exception) { false }
        }
        
        val modelDir = File(rootDir, modelId)
        
        // 特殊处理 LLM 模型：支持 .bin, .litertlm 或 .tflite
        if (modelId.contains("llm")) {
            val modelFile = modelDir.listFiles()?.find { 
                it.name.endsWith(".bin") || it.name.endsWith(".litertlm") || it.name.endsWith(".tflite")
            }
            return modelFile != null && modelFile.length() > 10 * 1024 * 1024 // 至少 10MB，自定义模型可能较小
        }

        // 特殊处理单文件模型（如说话人 ECAPA .onnx）：检查目录内是否存在 .onnx 文件
        // 注意排除 whisper 模型（其目录也含 .onnx，但需走下面的 encoder/decoder/tokens 校验）
        val isWhisper = setOf("tiny", "base", "small").any { modelId.contains(it) }
        if (!isWhisper && (modelId == "speaker-ecapa" || modelDir.listFiles()?.any { it.name.endsWith(".onnx") } == true)) {
            val onnxFile = modelDir.listFiles()?.find { it.name.endsWith(".onnx") }
            val ready = onnxFile != null && onnxFile.length() > 1024 * 1024 // 至少 1MB
            Log.d(TAG, "[isModelReady] Speaker/onnx model '$modelId': onnxFile=${onnxFile?.absolutePath}, size=${onnxFile?.length()}, ready=$ready")
            return ready
        }

        val prefix = if (modelId.contains("tiny")) "tiny" else if (modelId.contains("base")) "base" else "small"
        
        val encoder = File(modelDir, "$prefix-encoder.int8.onnx")
        val decoder = File(modelDir, "$prefix-decoder.int8.onnx")
        val tokens = File(modelDir, "$prefix-tokens.txt")

        // 不仅要存在，还要有内容（避免解压失败留下的 0 字节文件）
        return encoder.exists() && encoder.length() > 1024 * 1024 &&
               decoder.exists() && decoder.length() > 1024 * 1024 &&
               tokens.exists() && tokens.length() > 10
    }
    
    // 判断模型是否为单文件下载（如说话人模型是单个 .onnx，而非 tar.bz2 压缩包）
    private fun isSingleFileModel(model: ModelInfo): Boolean {
        return model.encoderUrl.endsWith(".onnx", ignoreCase = true)
    }

    // 根据模型信息计算下载目录中的文件名
    private fun getDownloadFileName(model: ModelInfo): String {
        return when {
            model.id == "vad-silero" -> "silero_vad.onnx"
            model.id.startsWith("llm") -> "${model.id}.tflite"
            isSingleFileModel(model) -> model.encoderUrl.substringAfterLast("/")
            else -> "${model.id}.tar.bz2"
        }
    }

    fun downloadModel(model: ModelInfo) {
        Log.d(TAG, "Starting download for ${model.name}...")
        val fileName = getDownloadFileName(model)
        val request = DownloadManager.Request(Uri.parse(model.encoderUrl))
            .setTitle("Downloading ${model.name}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)
        activeDownloads[model.id] = downloadId
        startProgressTracking(model.id, downloadId)
    }

    private fun startProgressTracking(modelId: String, downloadId: Long) {
        scope.launch {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            var isDownloading = true
            while (isDownloading) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    val bytesDownloaded = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val bytesTotal = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            isDownloading = false
                            activeDownloads.remove(modelId)
                            handleDownloadComplete(modelId)
                        }
                        DownloadManager.STATUS_FAILED -> {
                            Log.e(TAG, "Download failed for $modelId")
                            _modelStatuses.update { it + (modelId to ModelStatus.ERROR) }
                            isDownloading = false
                        }
                        else -> {
                            val progress = if (bytesTotal > 0) bytesDownloaded.toFloat() / bytesTotal else 0f
                            _modelStatuses.update { it + (modelId to ModelStatus.DOWNLOADING(progress)) }
                        }
                    }
                    cursor.close()
                }
                delay(1000)
            }
        }
    }

    private suspend fun handleDownloadComplete(modelId: String) {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (modelId == "vad-silero") {
            val src = File(downloadDir, "silero_vad.onnx")
            val dest = File(context.filesDir, "silero_vad.onnx")
            withContext(Dispatchers.IO) {
                if (src.exists()) {
                    src.copyTo(dest, overwrite = true)
                    src.delete()
                    Log.d(TAG, "VAD model copied and source deleted.")
                }
            }
            refreshStatuses()
        } else if (isSingleFileModel(availableModels.firstOrNull { it.id == modelId } ?: return)) {
            // 单文件模型（如说话人 .onnx）：直接拷贝到模型目录，保留原始文件名
            val fileName = getDownloadFileName(availableModels.first { it.id == modelId })
            val src = File(downloadDir, fileName)
            val modelDir = getModelDir(modelId)
            if (!modelDir.exists()) modelDir.mkdirs()
            val dest = File(modelDir, fileName)
            withContext(Dispatchers.IO) {
                if (src.exists()) {
                    src.copyTo(dest, overwrite = true)
                    src.delete()
                    Log.d(TAG, "Single-file model copied to ${dest.absolutePath}")
                }
            }
            refreshStatuses()
        } else if (modelId.startsWith("llm")) {
            val src = File(downloadDir, "$modelId.tflite")
            val modelDir = getModelDir(modelId)
            if (!modelDir.exists()) modelDir.mkdirs()
            val dest = File(modelDir, "$modelId.tflite")
            withContext(Dispatchers.IO) {
                if (src.exists()) {
                    src.copyTo(dest, overwrite = true)
                    src.delete()
                    Log.d(TAG, "LLM model moved to ${dest.absolutePath}")
                }
            }
            refreshStatuses()
        } else {
            val archive = File(downloadDir, "$modelId.tar.bz2")
            if (archive.exists()) {
                extractTarBz2(modelId, archive)
                // Cleanup archive after extraction if successful
                if (isModelReady(modelId)) {
                    withContext(Dispatchers.IO) { archive.delete() }
                    Log.d(TAG, "Archive deleted: ${archive.name}")
                }
            } else {
                Log.e(TAG, "Downloaded archive not found at ${archive.absolutePath}")
                _modelStatuses.update { it + (modelId to ModelStatus.ERROR) }
            }
        }
    }

    suspend fun importFromArchive(modelId: String, uri: Uri) = withContext(Dispatchers.IO) {
        val tempFile = File(context.cacheDir, "import_temp_${System.currentTimeMillis()}.tar.bz2")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            }
            extractTarBz2(modelId, tempFile)
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            withContext(Dispatchers.Main) { _modelStatuses.update { it + (modelId to ModelStatus.ERROR) } }
        } finally {
            tempFile.delete()
        }
    }

    // 导入单文件模型（如说话人 ECAPA .onnx）：生成独立的 custom-speaker-<name> 目录，
    // 使其作为独立信息块出现在设置页的 Speaker Diarization 分区
    suspend fun importSingleFileModel(uri: Uri, originalFileName: String) = withContext(Dispatchers.IO) {
        val baseName = originalFileName.substringBeforeLast(".")
        val modelId = "custom-speaker-${baseName.lowercase().replace(" ", "-")}"
        val modelDir = getModelDir(modelId)
        if (!modelDir.exists()) modelDir.mkdirs()
        val destFile = File(modelDir, originalFileName)
        Log.i(TAG, "[IMPORT_SPEAKER] uri=$uri fileName=$originalFileName -> modelId=$modelId dest=${destFile.absolutePath}")

        _modelStatuses.update { it + (modelId to ModelStatus.EXTRACTING(-1f)) }

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "[IMPORT_SPEAKER] Success: ${destFile.absolutePath} (${destFile.length()} bytes)")
            discoverCustomModels()
        } catch (e: Exception) {
            Log.e(TAG, "[IMPORT_SPEAKER] Failed to import speaker model", e)
            _modelStatuses.update { it + (modelId to ModelStatus.ERROR) }
        } finally {
            refreshStatuses()
        }
    }

    suspend fun importLiteRtModel(uri: Uri, originalFileName: String) = withContext(Dispatchers.IO) {
        val baseName = originalFileName.substringBeforeLast(".")
        val modelId = "custom-llm-${baseName.lowercase().replace(" ", "-")}"
        val modelDir = File(rootDir, modelId)
        if (!modelDir.exists()) modelDir.mkdirs()
        
        val destFile = File(modelDir, originalFileName)
        
        _modelStatuses.update { it + (modelId to ModelStatus.EXTRACTING(-1f)) }
        
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
            Log.d(TAG, "LiteRT model imported successfully to ${destFile.absolutePath}")
            discoverCustomModels()
        } catch (e: Exception) {
            Log.e(TAG, "LiteRT model Import failed", e)
            _modelStatuses.update { it + (modelId to ModelStatus.ERROR) }
        } finally {
            refreshStatuses()
        }
    }

    private suspend fun extractTarBz2(modelId: String, archive: File) {
        Log.d(TAG, "Extracting archive ${archive.name} (optimized pass)...")
        extractingModels.add(modelId)
        _modelStatuses.update { it + (modelId to ModelStatus.EXTRACTING(-1f)) }
        
        val modelDir = File(rootDir, modelId)
        val tempDir = File(rootDir, "${modelId}_tmp")
        if (tempDir.exists()) tempDir.deleteRecursively()
        tempDir.mkdirs()
        val prefix = if (modelId.contains("tiny")) "tiny" else if (modelId.contains("base")) "base" else "small"
        val totalCompressedSize = archive.length()

        try {
            withContext(Dispatchers.IO) {
                val extractedCategories = mutableSetOf<String>()
                var extractedCount = 0
                var lastUpdatePercent = -1

                FileInputStream(archive).use { fis ->
                    var bytesReadTotal = 0L
                    val countingIn = object : FilterInputStream(fis) {
                        private fun updateProgress(n: Int) {
                            if (n <= 0) return
                            bytesReadTotal += n
                            if (totalCompressedSize > 0) {
                                val currentPercent = (bytesReadTotal * 100 / totalCompressedSize).toInt()
                                if (currentPercent > lastUpdatePercent) {
                                    lastUpdatePercent = currentPercent
                                    Log.d(TAG, "Extraction progress: $currentPercent% ($bytesReadTotal / $totalCompressedSize bytes)")
                                    _modelStatuses.update { it + (modelId to ModelStatus.EXTRACTING(currentPercent / 100f)) }
                                }
                            }
                        }

                        override fun read(): Int {
                            val result = super.read()
                            if (result != -1) updateProgress(1)
                            return result
                        }

                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            val result = super.read(b, off, len)
                            if (result != -1) updateProgress(result)
                            return result
                        }
                    }

                    BZip2CompressorInputStream(countingIn).use { bzIn ->
                        TarArchiveInputStream(bzIn).use { tarIn ->
                            var entry = tarIn.nextEntry
                            while (entry != null) {
                                if (!entry.isDirectory) {
                                    val name = entry.name
                                    val isEncoder = name.contains("encoder") && name.contains("int8") && name.endsWith(".onnx")
                                    val isDecoder = name.contains("decoder") && name.contains("int8") && name.endsWith(".onnx")
                                    val isTokens = name.contains("tokens.txt")

                                    val destName: String? = when {
                                        isEncoder && "encoder" !in extractedCategories -> "$prefix-encoder.int8.onnx"
                                        isDecoder && "decoder" !in extractedCategories -> "$prefix-decoder.int8.onnx"
                                        isTokens && "tokens" !in extractedCategories -> "$prefix-tokens.txt"
                                        else -> null
                                    }

                                    if (destName != null) {
                                        val category = when {
                                            isEncoder -> "encoder"
                                            isDecoder -> "decoder"
                                            else -> "tokens"
                                        }
                                        val destFile = File(tempDir, destName)
                                        val tarSize = entry.size
                                        var copiedBytes = 0L
                                        Log.d(TAG, ">>> Extracting: ${entry.name} -> $destName (${tarSize} bytes)")
                                        
                                        FileOutputStream(destFile).use { out ->
                                            val buf = ByteArray(64 * 1024)
                                            var l: Int
                                            var lastPct = -1
                                            while (tarIn.read(buf).also { l = it } != -1) {
                                                out.write(buf, 0, l)
                                                copiedBytes += l
                                                val pct = if (tarSize > 0) (copiedBytes * 100 / tarSize).toInt() else 100
                                                if (pct != lastPct) {
                                                    Log.d(TAG, "Extracting $destName: $pct% complete")
                                                    lastPct = pct
                                                }
                                            }
                                        }
                                        extractedCategories.add(category)
                                        extractedCount++
                                        Log.d(TAG, "[SUCCESS] Extracted $destName (${destFile.length()} bytes)")
                                    }
                                }
                                
                                // 关键优化：如果 3 个文件都拿到了，直接收工，不再扫描后面几百MB的数据
                                if (extractedCategories.size == 3) {
                                    Log.i(TAG, "Found all required files. Stopping extraction early.")
                                    break 
                                }
                                entry = tarIn.nextEntry
                            }
                        }
                    }
                }

                if (extractedCategories.size == 3) {
                    if (modelDir.exists()) modelDir.deleteRecursively()
                    if (tempDir.renameTo(modelDir)) {
                        Log.i(TAG, "Successfully moved $tempDir to $modelDir")
                    } else {
                        Log.e(TAG, "Failed to rename $tempDir to $modelDir, trying copy.")
                        tempDir.copyRecursively(modelDir, overwrite = true)
                    }
                } else {
                    Log.w(TAG, "Only extracted ${extractedCategories.size}/3 files. Missing: ${setOf("encoder", "decoder", "tokens") - extractedCategories}")
                }
                Log.i(TAG, "[SUCCESS] Installation of $modelId finished.")
            }

        } catch (e: Exception) {
            Log.e(TAG, "[FATAL_ERROR] Extraction failed for $modelId", e)
            _modelStatuses.update { it + (modelId to ModelStatus.ERROR) }
        } finally {
            if (tempDir.exists()) tempDir.deleteRecursively()
            // 1. 先从集合中移除，这样接下来的 isModelReady 就能返回 true
            extractingModels.remove(modelId)
            
            // 2. 在 IO 线程直接计算最终状态并更新 Flow，确保 UI 第一时间收到
            val isReady = isModelReady(modelId)
            val finalStatus = if (isReady) ModelStatus.READY else ModelStatus.NOT_DOWNLOADED
            
            _modelStatuses.update { it + (modelId to finalStatus) }
            
            Log.d(TAG, "Extraction cleanup done for $modelId. isReady=$isReady")
            
            // 3. 切换回主线程做一次全局刷新，作为最终保障
            withContext(Dispatchers.Main) {
                refreshStatuses()
            }
        }
    }

    fun getModelDir(modelId: String): File = File(rootDir, modelId)
    
    fun deleteModel(modelId: String) {
        val modelDir = File(rootDir, modelId)
        if (modelDir.exists()) {
            modelDir.deleteRecursively()
            Log.i(TAG, "Model $modelId deleted.")
            if (modelId.startsWith("custom-")) {
                discoverCustomModels()
            }
            // 若删除的是当前选中的说话人模型，清除偏好
            if (getSelectedSpeakerModelId() == modelId) {
                setSelectedSpeakerModelId("")
            }
            refreshStatuses()
        }
    }
    fun getSelectedModelId(): String = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("selected_model", "whisper-tiny") ?: "whisper-tiny"
    fun setSelectedModelId(modelId: String) {
        Log.i(TAG, "Setting selected model to: $modelId")
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("selected_model", modelId).apply()
        _settingsChanged.update { it + 1 }
    }

    fun getSelectedLlmModelId(): String = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("selected_llm_model", "llm-gemma-2b") ?: "llm-gemma-2b"
    fun setSelectedLlmModelId(modelId: String) {
        Log.i(TAG, "Setting selected LLM model to: $modelId")
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("selected_llm_model", modelId).apply()
        _settingsChanged.update { it + 1 }
    }

    fun getSelectedSpeakerModelId(): String {
        val id = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("selected_speaker_model", "") ?: ""
        Log.d(TAG, "[SPEAKER_SELECT] getSelectedSpeakerModelId=$id")
        return id
    }
    fun setSelectedSpeakerModelId(modelId: String) {
        Log.i(TAG, "[SPEAKER_SELECT] Setting selected speaker model to: $modelId")
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("selected_speaker_model", modelId).apply()
        _settingsChanged.update { it + 1 }
    }

    fun getAudioSource(): Int = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("audio_source", MediaRecorder.AudioSource.VOICE_RECOGNITION)
    fun setAudioSource(source: Int) {
        val sourceName = when (source) {
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            else -> "UNKNOWN($source)"
        }
        Log.i(TAG, "Setting audio source to: $sourceName")
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putInt("audio_source", source).apply()
        _settingsChanged.update { it + 1 }
    }
}
