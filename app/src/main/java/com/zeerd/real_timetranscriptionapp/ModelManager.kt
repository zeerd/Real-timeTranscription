package com.zeerd.real_timetranscriptionapp

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream

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
    object READY : ModelStatus()
    object ERROR : ModelStatus()
}

class ModelManager(private val context: Context) {
    private val TAG = "ModelManager"
    private val rootDir = File(context.filesDir, "models")
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val activeDownloads = mutableMapOf<String, Long>()

    val availableModels = listOf(
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
        )
    )

    private val _modelStatuses = MutableStateFlow<Map<String, ModelStatus>>(emptyMap())
    val modelStatuses = _modelStatuses.asStateFlow()

    init {
        if (!rootDir.exists()) rootDir.mkdirs()
        refreshStatuses()
    }

    fun refreshStatuses() {
        val statuses = availableModels.associate { model ->
            val status = if (isModelReady(model.id)) {
                ModelStatus.READY
            } else {
                ModelStatus.NOT_DOWNLOADED
            }
            model.id to status
        }
        _modelStatuses.value = statuses
    }

    private fun isModelReady(modelId: String): Boolean {
        if (modelId == "vad-silero") {
            // Check in assets (legacy) or in files
            val fileInFiles = File(context.filesDir, "silero_vad.onnx")
            if (fileInFiles.exists()) return true
            
            return try {
                context.assets.open("silero_vad.onnx").close()
                true
            } catch (e: Exception) {
                false
            }
        }
        val modelDir = File(rootDir, modelId)
        val prefix = when {
            modelId.contains("tiny") -> "tiny"
            modelId.contains("base") -> "base"
            modelId.contains("small") -> "small"
            else -> "tiny"
        }
        return File(modelDir, "$prefix-encoder.int8.onnx").exists() && 
               File(modelDir, "$prefix-decoder.int8.onnx").exists() &&
               File(modelDir, "$prefix-tokens.txt").exists()
    }
    
    fun downloadModel(model: ModelInfo) {
        Log.d(TAG, "Starting download for ${model.name}...")
        
        val fileName = if (model.id == "vad-silero") "silero_vad.onnx" else "${model.id}.tar.bz2"

        val request = DownloadManager.Request(Uri.parse(model.encoderUrl))
            .setTitle("Downloading ${model.name}")
            .setDescription("Downloading model files")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

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
                val cursor: Cursor = downloadManager.query(query)
                
                if (cursor.moveToFirst()) {
                    val bytesDownloaded = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val bytesTotal = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))

                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            _modelStatuses.value = _modelStatuses.value + (modelId to ModelStatus.READY)
                            isDownloading = false
                            activeDownloads.remove(modelId)
                        }
                        DownloadManager.STATUS_FAILED -> {
                            _modelStatuses.value = _modelStatuses.value + (modelId to ModelStatus.ERROR)
                            isDownloading = false
                            activeDownloads.remove(modelId)
                        }
                        else -> {
                            val progress = if (bytesTotal > 0) bytesDownloaded.toFloat() / bytesTotal else 0f
                            _modelStatuses.value = _modelStatuses.value + (modelId to ModelStatus.DOWNLOADING(progress))
                        }
                    }
                }
                cursor.close()
                delay(1000)
            }
        }
    }

    fun getModelDir(modelId: String): File {
        return File(rootDir, modelId)
    }
    
    fun getSelectedModelId(): String {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("selected_model", "whisper-tiny") ?: "whisper-tiny"
    }
    
    fun setSelectedModelId(modelId: String) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putString("selected_model", modelId)
            .apply()
    }

    suspend fun importFromUri(modelId: String, uri: Uri) = withContext(Dispatchers.IO) {
        val documentFile = DocumentFile.fromTreeUri(context, uri) ?: return@withContext
        val modelDir = File(rootDir, modelId)
        if (!modelDir.exists()) modelDir.mkdirs()

        val prefix = when {
            modelId.contains("tiny") -> "tiny"
            modelId.contains("base") -> "base"
            modelId.contains("small") -> "small"
            else -> "tiny"
        }

        val files = documentFile.listFiles()
        val encoder = files.find { it.name?.contains("encoder") == true && it.name?.contains("onnx") == true }
        val decoder = files.find { it.name?.contains("decoder") == true && it.name?.contains("onnx") == true }
        val tokens = files.find { it.name?.contains("tokens") == true || it.name?.contains("vocab") == true }

        if (encoder != null && decoder != null && tokens != null) {
            copyDocumentToFile(encoder, File(modelDir, "$prefix-encoder.int8.onnx"))
            copyDocumentToFile(decoder, File(modelDir, "$prefix-decoder.int8.onnx"))
            copyDocumentToFile(tokens, File(modelDir, "$prefix-tokens.txt"))
            
            Log.d(TAG, "Imported $modelId successfully from $uri")
            withContext(Dispatchers.Main) { refreshStatuses() }
        } else {
            throw Exception("Required files missing in folder (encoder, decoder, tokens)")
        }
    }

    private fun copyDocumentToFile(docFile: DocumentFile, destFile: File) {
        context.contentResolver.openInputStream(docFile.uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
    }
}
