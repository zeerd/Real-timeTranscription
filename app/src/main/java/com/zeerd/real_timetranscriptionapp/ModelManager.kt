package com.zeerd.real_timetranscriptionapp

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
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

enum class ModelCategory { ASR, SPEAKER, LLM, VAD }

data class ModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val encoderUrl: String,
    val decoderUrl: String,
    val tokensUrl: String,
    val sizeLabel: String,
    val category: ModelCategory = ModelCategory.ASR
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
    private val extractingModels = Collections.synchronizedSet(mutableSetOf<String>())

    private val _customModels = MutableStateFlow<List<ModelInfo>>(emptyList())

    private val staticModels = listOf(
        ModelInfo(
            id = "whisper-tiny",
            name = "Whisper Tiny (Multi)",
            description = "Fastest, lowest accuracy. Good for high-end devices or testing.",
            encoderUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2",
            decoderUrl = "", // We handle tar.bz2 for these
            tokensUrl = "",
            sizeLabel = "~150MB",
            category = ModelCategory.ASR
        ),
        ModelInfo(
            id = "whisper-base",
            name = "Whisper Base (Multi)",
            description = "Better accuracy than Tiny. Recommended for most users.",
            encoderUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2",
            decoderUrl = "",
            tokensUrl = "",
            sizeLabel = "~290MB",
            category = ModelCategory.ASR
        ),
        ModelInfo(
            id = "whisper-small",
            name = "Whisper Small (Multi)",
            description = "High accuracy. Requires more memory and processing power.",
            encoderUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2",
            decoderUrl = "",
            tokensUrl = "",
            sizeLabel = "~960MB",
            category = ModelCategory.ASR
        ),
        ModelInfo(
            id = "SenseVoiceSmall",
            name = "SenseVoice Small (Multi)",
            description = "High accuracy. Requires more memory and processing power.",
            encoderUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
            decoderUrl = "",
            tokensUrl = "",
            sizeLabel = "~960MB",
            category = ModelCategory.ASR
        ),
        ModelInfo(
            id = "speaker-ecapa",
            name = "ECAPA-TDNN Speaker Embedding",
            description = "Identifies different speakers based on voiceprints.",
            encoderUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx",
            decoderUrl = "",
            tokensUrl = "",
            sizeLabel = "~20MB",
            category = ModelCategory.SPEAKER
        ),
        ModelInfo(
            id = "llm-gemma-2b",
            name = "Gemma 2B (LiteRT)",
            description = "Google LiteRT LM for semantic paragraphing and formatting.",
            encoderUrl = "https://huggingface.co/google/gemma-2b-it-tflite/resolve/main/gemma-2b-it-cpu-int8.tflite", // Example URL
            decoderUrl = "",
            tokensUrl = "",
            sizeLabel = "~1.4GB",
            category = ModelCategory.LLM
        )
    )

    val availableModels: List<ModelInfo>
        get() = staticModels + _customModels.value

    // 默认可选模型的下载链接列表（供设置页以“可点击链接”形式展示，不再提供下载按钮）。
    // 用户可手动下载后通过导入安装，也可下载其他 sherpa-onnx 支持的模型。
    val defaultDownloadLinks: List<ModelInfo>
        get() = staticModels

    // 添加一个 Flow 以便 UI 观察模型列表的变化
    val customModels = _customModels.asStateFlow()

    private val _modelStatuses = MutableStateFlow<Map<String, ModelStatus>>(emptyMap())
    val modelStatuses = _modelStatuses.asStateFlow()

    private val _settingsChanged = MutableStateFlow(0)
    val settingsChanged = _settingsChanged.asStateFlow()

    init {
        if (!rootDir.exists()) rootDir.mkdirs()
        discoverCustomModels()
        refreshStatuses()
    }

    private fun discoverCustomModels() {
        // 保留已登记（导入时立即创建）的自定义模型名称/描述，仅刷新体积标签
        val existing = _customModels.value.associateBy { it.id }
        // 扫描所有模型目录（包括历史遗留的 whisper-small 等），按内容归类，避免“死占位行”
        // 也避免已安装模型因 id 前缀不匹配而从列表消失。
        val custom = rootDir.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            val files = dir.listFiles() ?: return@mapNotNull null
            val hasLlm = files.any { it.name.endsWith(".tflite") || it.name.endsWith(".bin") || it.name.endsWith(".litertlm") }
            val hasOnnx = files.any { it.name.endsWith(".onnx") }
            val hasTokens = files.any { it.name == "tokens.txt" }
            val category = when {
                hasLlm -> ModelCategory.LLM
                hasOnnx && !hasTokens -> ModelCategory.SPEAKER
                hasOnnx -> ModelCategory.ASR
                else -> return@mapNotNull null
            }
            val prev = existing[dir.name]
            ModelInfo(
                id = dir.name,
                name = prev?.name ?: dir.name.removePrefix("custom-").replace("-", " ").replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() },
                description = prev?.description ?: when (category) {
                    ModelCategory.LLM -> "Custom LLM model imported by user."
                    ModelCategory.SPEAKER -> "Custom speaker diarization model imported by user."
                    ModelCategory.ASR -> "Custom ASR model imported by user."
                    ModelCategory.VAD -> "Custom VAD model imported by user."
                },
                encoderUrl = "",
                decoderUrl = "",
                tokensUrl = "",
                sizeLabel = formatSize(computeDirectorySize(dir)),
                category = category
            )
        } ?: emptyList()
        _customModels.value = custom
        Log.i(TAG, "[DISCOVER] custom models found: ${custom.map { it.id }}")
    }

    // 导入开始时立即登记一个自定义模型信息块，使 UI 能即时显示进度（EXTRACTING），
    // 避免“进度信息块缺失”的问题。
    private fun registerCustomModel(info: ModelInfo) {
        val current = _customModels.value.toMutableList()
        if (current.none { it.id == info.id }) {
            current.add(info)
            _customModels.value = current
            Log.i(TAG, "[REGISTER] custom model block created: ${info.id}")
        }
    }

    // 从导入的 uri 读取原始文件名，用于给自定义模型起更友好的名字
    private fun getDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx != -1) cursor.getString(idx) else null
                } else null
            }
        } catch (e: Exception) { null }
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

    fun refreshStatuses() {
        val currentStatuses = _modelStatuses.value
        // 只检查磁盘上真实存在的模型（_customModels）。staticModels 里的 whisper-tiny /
        // SenseVoiceSmall 等只是下载链接占位，永远不会被应用内安装，检查它们只会产生
        // 无意义的 "not ready" 日志，故跳过。
        val statuses = _customModels.value.associate { model ->
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

        // 内容驱动：完全按目录里的文件判定，不依赖 modelId 里的 tiny/base/small/sense 等字样。
        // sherpa-onnx 的 Whisper/SenseVoice 配置只接收 onnx 文件路径，模型尺寸（tiny/base/small）
        // 已固化在 onnx 图里，文件名前缀对推理没有任何意义，因此这里不做任何按尺寸的特殊处理。
        val files = modelDir.listFiles() ?: emptyArray()
        val onnxFiles = files.filter { it.name.endsWith(".onnx", ignoreCase = true) }
        val tokensFile = File(modelDir, "tokens.txt")

        // 1) ASR 模型（Whisper / SenseVoice / 任意新类型）：至少一个 onnx(>1MB) + tokens.txt(>10B)
        if (tokensFile.exists() && tokensFile.length() > 10 && onnxFiles.any { it.length() > 1024 * 1024 }) {
            val ready = true
            Log.d(TAG, "[isModelReady] ASR model '$modelId': onnx=${onnxFiles.firstOrNull()?.absolutePath}, tokens=${tokensFile.exists()}, ready=$ready")
            return ready
        }

        // 2) 单文件模型（如说话人 ECAPA .onnx）：目录内存在 onnx(>1MB)，无需 tokens.txt
        val singleOnnx = onnxFiles.firstOrNull { it.length() > 1024 * 1024 }
        if (singleOnnx != null) {
            val ready = true
            Log.d(TAG, "[isModelReady] Single-file model '$modelId': onnxFile=${singleOnnx.absolutePath}, size=${singleOnnx.length()}, ready=$ready")
            return ready
        }

        Log.d(TAG, "[isModelReady] Model '$modelId' not ready (no valid onnx/tokens combination)")
        return false
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

    // 内容驱动导入：不要求用户选择模型类型。压缩包里的 .onnx + tokens.txt 原样解压，
    // 模型类型由 WhisperWrapper 按文件内容自动识别。自动分配 custom-asr-<时间戳> 目录，
    // 将来新增任何 ASR 模型类型都无需改这里。导入开始即创建信息块并描画进度。
    suspend fun importAsrArchive(uri: Uri) = withContext(Dispatchers.IO) {
        val modelId = "custom-asr-${System.currentTimeMillis()}"
        val displayName = getDisplayName(uri)
        val name = displayName?.substringBeforeLast(".")?.takeIf { it.isNotBlank() } ?: "Imported ASR Model"
        val info = ModelInfo(
            id = modelId,
            name = name,
            description = "Custom ASR model imported by user.",
            encoderUrl = "", decoderUrl = "", tokensUrl = "", sizeLabel = "Importing...",
            category = ModelCategory.ASR
        )
        registerCustomModel(info)
        _modelStatuses.update { it + (modelId to ModelStatus.EXTRACTING(-1f)) }
        Log.i(TAG, "[IMPORT_ASR] auto-assigned modelId=$modelId name=$name")
        importFromArchive(modelId, uri)
        discoverCustomModels()
    }

    // 导入单文件模型（如说话人 ECAPA .onnx）：生成独立的 custom-speaker-<name> 目录，
    // 使其作为独立信息块出现在设置页的 Speaker Diarization 分区。导入开始即创建信息块并描画进度。
    suspend fun importSingleFileModel(uri: Uri, originalFileName: String) = withContext(Dispatchers.IO) {
        val baseName = originalFileName.substringBeforeLast(".")
        val modelId = "custom-speaker-${baseName.lowercase().replace(" ", "-")}"
        val info = ModelInfo(
            id = modelId,
            name = baseName,
            description = "Custom speaker diarization model imported by user.",
            encoderUrl = "", decoderUrl = "", tokensUrl = "", sizeLabel = "Importing...",
            category = ModelCategory.SPEAKER
        )
        registerCustomModel(info)
        _modelStatuses.update { it + (modelId to ModelStatus.EXTRACTING(-1f)) }

        val modelDir = getModelDir(modelId)
        if (!modelDir.exists()) modelDir.mkdirs()
        val destFile = File(modelDir, originalFileName)
        Log.i(TAG, "[IMPORT_SPEAKER] uri=$uri fileName=$originalFileName -> modelId=$modelId dest=${destFile.absolutePath}")

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "[IMPORT_SPEAKER] Success: ${destFile.absolutePath} (${destFile.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "[IMPORT_SPEAKER] Failed to import speaker model", e)
            _modelStatuses.update { it + (modelId to ModelStatus.ERROR) }
        } finally {
            discoverCustomModels()
        }
    }

    suspend fun importLiteRtModel(uri: Uri, originalFileName: String) = withContext(Dispatchers.IO) {
        val baseName = originalFileName.substringBeforeLast(".")
        val modelId = "custom-llm-${baseName.lowercase().replace(" ", "-")}"
        val info = ModelInfo(
            id = modelId,
            name = baseName,
            description = "Custom LLM model imported by user.",
            encoderUrl = "", decoderUrl = "", tokensUrl = "", sizeLabel = "Importing...",
            category = ModelCategory.LLM
        )
        registerCustomModel(info)
        _modelStatuses.update { it + (modelId to ModelStatus.EXTRACTING(-1f)) }

        val modelDir = File(rootDir, modelId)
        if (!modelDir.exists()) modelDir.mkdirs()

        val destFile = File(modelDir, originalFileName)

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
            Log.d(TAG, "LiteRT model imported successfully to ${destFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "LiteRT model Import failed", e)
            _modelStatuses.update { it + (modelId to ModelStatus.ERROR) }
        } finally {
            discoverCustomModels()
        }
    }

    private suspend fun extractTarBz2(modelId: String, archive: File) {
        Log.d(TAG, "Extracting archive ${archive.name} (content-driven pass)...")
        extractingModels.add(modelId)
        _modelStatuses.update { it + (modelId to ModelStatus.EXTRACTING(-1f)) }

        val modelDir = File(rootDir, modelId)
        val tempDir = File(rootDir, "${modelId}_tmp")
        if (tempDir.exists()) tempDir.deleteRecursively()
        tempDir.mkdirs()
        val totalCompressedSize = archive.length()

        try {
            withContext(Dispatchers.IO) {
                var hasOnnx = false
                var hasTokens = false
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
                                    // 内容驱动：保留压缩包内的原始文件名，不做按类型重命名。
                                    // 这样 Whisper 的 encoder/decoder、SenseVoice 的 model.onnx、
                                    // 以及将来任何新模型都能原样落盘，由 WhisperWrapper 按文件自动识别类型。
                                    val isOnnx = name.endsWith(".onnx", ignoreCase = true)
                                    val isTokens = name.endsWith("tokens.txt", ignoreCase = true)

                                    val destName: String? = when {
                                        isOnnx -> name.substringAfterLast("/")
                                        isTokens -> "tokens.txt"
                                        else -> null
                                    }

                                    if (destName != null) {
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
                                        if (isOnnx) hasOnnx = true
                                        if (isTokens) hasTokens = true
                                        Log.d(TAG, "[SUCCESS] Extracted $destName (${destFile.length()} bytes)")
                                    }
                                }
                                entry = tarIn.nextEntry
                            }
                        }
                    }
                }

                // 只要拿到至少一个 onnx + tokens.txt 就认为解压成功（模型类型由文件内容决定）
                if (hasOnnx && hasTokens) {
                    if (modelDir.exists()) modelDir.deleteRecursively()
                    if (tempDir.renameTo(modelDir)) {
                        Log.i(TAG, "Successfully moved $tempDir to $modelDir")
                    } else {
                        Log.e(TAG, "Failed to rename $tempDir to $modelDir, trying copy.")
                        tempDir.copyRecursively(modelDir, overwrite = true)
                    }
                } else {
                    Log.w(TAG, "Extraction incomplete: hasOnnx=$hasOnnx, hasTokens=$hasTokens")
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
    // 返回当前选中的 ASR 模型；若保存的 id 不存在或不可用，则回退到第一个就绪的 ASR 模型
    // （按内容归类，含历史遗留的 whisper-small 等）。静态占位 whisper-tiny 等永远不会被安装，故不默认。
    fun getSelectedModelId(): String {
        val saved = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("selected_model", "") ?: ""
        if (saved.isNotEmpty() && isModelReady(saved)) return saved
        // 只从真实存在的模型（_customModels）中回退选择，静态占位永远不就绪，跳过。
        return _customModels.value.firstOrNull { it.category == ModelCategory.ASR && isModelReady(it.id) }?.id ?: ""
    }
    fun setSelectedModelId(modelId: String) {
        Log.i(TAG, "Setting selected model to: $modelId")
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("selected_model", modelId).apply()
        _settingsChanged.update { it + 1 }
    }

    // 返回当前选中的 LLM 模型；若保存的 id 不存在或不可用，则回退到第一个就绪的 LLM 模型
    // （按内容归类）。静态占位 llm-gemma-2b 不会被安装，故不默认。
    fun getSelectedLlmModelId(): String {
        val saved = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("selected_llm_model", "") ?: ""
        if (saved.isNotEmpty() && isModelReady(saved)) return saved
        // 只从真实存在的模型（_customModels）中回退选择，静态占位永远不就绪，跳过。
        return _customModels.value.firstOrNull { it.category == ModelCategory.LLM && isModelReady(it.id) }?.id ?: ""
    }
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

    // LLM 润色开关：默认开启。关闭后管线不再初始化 LLM，也不对原始稿做语义分段润色。
    fun isLlmPolishingEnabled(): Boolean =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("llm_polishing_enabled", true)
    fun setLlmPolishingEnabled(enabled: Boolean) {
        Log.i(TAG, "Setting LLM polishing enabled to: $enabled")
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("llm_polishing_enabled", enabled).apply()
        _settingsChanged.update { it + 1 }
    }
}
