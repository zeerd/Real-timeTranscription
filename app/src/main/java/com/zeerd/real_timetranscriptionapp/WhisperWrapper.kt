package com.zeerd.real_timetranscriptionapp

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import java.io.FileOutputStream

class WhisperWrapper(context: Context, modelId: String, modelDir: File) {
    private val recognizer: OfflineRecognizer
    private val TAG = "WhisperWrapper"
    // 调试用：把每段送入识别的音频写成 WAV，方便核对是否漏字
    var dumpDir: File? = null

    // 内容驱动：不靠 modelId 字符串判断类型，而是看目录里实际有哪些文件。
    // - 同时存在 encoder.int8.onnx + decoder.int8.onnx -> Whisper（双 onnx 结构）
    // - 否则只要有一个 .onnx -> 单 onnx 模型（SenseVoice 或任何未来单文件 ASR 模型）
    // 这样将来新增模型类型无需改这里。
    private val isSenseVoice = run {
        val files = modelDir.listFiles()?.map { it.name } ?: emptyList()
        val hasEncoder = files.any { it.contains("encoder") && it.endsWith(".onnx") }
        val hasDecoder = files.any { it.contains("decoder") && it.endsWith(".onnx") }
        // 有 encoder/decoder 对 -> Whisper；否则单 onnx 走 SenseVoice 式单模型配置
        !(hasEncoder && hasDecoder) && files.any { it.endsWith(".onnx", ignoreCase = true) }
    }

    init {
        Log.d(TAG, "Initializing WhisperWrapper with model: $modelId at ${modelDir.absolutePath}")

        val config = if (isSenseVoice) {
            buildSenseVoiceConfig(modelDir)
        } else {
            buildWhisperConfig(modelId, modelDir)
        }

        try {
            recognizer = OfflineRecognizer(null, config)
            Log.i(TAG, "[V2_ASR] OfflineRecognizer initialized successfully for $modelId")
        } catch (e: Exception) {
            Log.e(TAG, "[V2_ASR] [FATAL_ERROR] Failed to initialize: ${e.message}", e)
            throw e
        }
    }

    // Whisper：encoder.int8.onnx + decoder.int8.onnx + tokens.txt
    private fun buildWhisperConfig(modelId: String, modelDir: File): OfflineRecognizerConfig {
        // 内容驱动：前缀（tiny/base/small）只来自文件名，对 sherpa 推理无意义，
        // 纯粹是压缩包里的命名约定。直接从 *-encoder.int8.onnx 推断，不依赖 modelId。
        val prefix = modelDir.listFiles()
            ?.firstOrNull { it.name.endsWith("-encoder.int8.onnx") }
            ?.name
            ?.substringBefore("-encoder.int8.onnx")
            ?: "tiny"

        val encoderPath = File(modelDir, "$prefix-encoder.int8.onnx").absolutePath
        val decoderPath = File(modelDir, "$prefix-decoder.int8.onnx").absolutePath
        val tokensPath = File(modelDir, "$prefix-tokens.txt").absolutePath

        Log.d(TAG, "Checking model files in ${modelDir.absolutePath}...")
        listOf("$prefix-encoder.int8.onnx", "$prefix-decoder.int8.onnx", "$prefix-tokens.txt").forEach {
            val file = File(modelDir, it)
            if (!file.exists()) {
                Log.e(TAG, "CRITICAL: Model file ${file.absolutePath} is missing!")
            } else {
                Log.d(TAG, "Found: ${file.name} (${file.length()} bytes)")
            }
        }

        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = 16000,
                featureDim = 80
            ),
            modelConfig = OfflineModelConfig(
                tokens = tokensPath,
                modelType = "whisper",
                numThreads = 4,
                debug = false
            ).apply {
                whisper = OfflineWhisperModelConfig(
                    encoder = encoderPath,
                    decoder = decoderPath,
                    language = "zh",
                    task = "transcribe"
                )
            },
            // 注意：sherpa-onnx 的 Whisper 实现只支持 greedy_search，
            // modified_beam_search 会直接报错崩溃（offline-recognizer-whisper-impl.h）。
            decodingMethod = "greedy_search"
        )
    }

    // 单 onnx 模型（SenseVoice 或任何未来单文件 ASR 模型）：model.int8.onnx / model.onnx + tokens.txt。
    // 若压缩包里 onnx 不叫 model.int8.onnx，则回退到目录里任意一个 .onnx，做到内容驱动。
    private fun buildSenseVoiceConfig(modelDir: File): OfflineRecognizerConfig {
        val modelFile = listOf("model.int8.onnx", "model.onnx")
            .map { File(modelDir, it) }
            .firstOrNull { it.exists() }
            ?: modelDir.listFiles()?.firstOrNull { it.name.endsWith(".onnx", ignoreCase = true) }
        val tokensFile = File(modelDir, "tokens.txt")

        Log.d(TAG, "[SenseVoice] Checking model files in ${modelDir.absolutePath}...")
        if (modelFile == null) {
            Log.e(TAG, "CRITICAL: SenseVoice model.int8.onnx / model.onnx is missing in ${modelDir.absolutePath}!")
        } else {
            Log.d(TAG, "[SenseVoice] Found: ${modelFile.name} (${modelFile.length()} bytes)")
        }
        if (!tokensFile.exists()) {
            Log.e(TAG, "CRITICAL: SenseVoice tokens.txt is missing in ${modelDir.absolutePath}!")
        } else {
            Log.d(TAG, "[SenseVoice] Found: tokens.txt (${tokensFile.length()} bytes)")
        }

        val senseVoiceConfig = OfflineSenseVoiceModelConfig(
            // 注意：SenseVoice 只有一个 onnx，直接传绝对路径即可
            model = modelFile?.absolutePath ?: File(modelDir, "model.int8.onnx").absolutePath,
            language = "zh",
            useInverseTextNormalization = true
        )

        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = 16000,
                featureDim = 80
            ),
            modelConfig = OfflineModelConfig(
                tokens = tokensFile.absolutePath,
                numThreads = 4,
                debug = false
            ).apply {
                senseVoice = senseVoiceConfig
            },
            // SenseVoice 推荐使用 greedy_search
            decodingMethod = "greedy_search"
        )
    }

    private fun copyAsset(context: Context, assetName: String, destinationPath: String) {
        val destFile = File(destinationPath)
        if (destFile.exists()) {
            Log.d(TAG, "Asset $assetName already exists at $destinationPath")
            return
        }

        Log.d(TAG, "Copying asset $assetName to $destinationPath")
        try {
            context.assets.open(assetName).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Successfully copied $assetName")
        } catch (e: Exception) {
            Log.e(TAG, "Error copying $assetName: ${e.message}")
            throw e
        }
    }

    fun transcribe(audioData: FloatArray): String {
        Log.d(TAG, "[V2_ASR] Transcribing ${audioData.size} samples (${audioData.size / 16000f}s)...")
        val startTime = System.currentTimeMillis()
        return try {
            // 在音频末尾补一段静音，给 Whisper 收尾上下文，避免末尾 token 被截断
            // （表现为"漏掉最后一个字"）。离线识别的 AcceptWaveform 内部已自动调用
            // InputFinished()，无需（也没有）inputFinished() 方法。
            val padded = if (audioData.size >= Constants.MIN_SPEECH_DURATION_SAMPLES) {
                audioData + FloatArray(Constants.TRAILING_SILENCE_SAMPLES)
            } else {
                audioData
            }
            val sample = recognizer.createStream()
            // 注意：语言已由 config 的 language="zh" 指定，不要再设 prompt="简体中文"，
            // 否则初始 prompt 会与真实语音拼接，导致末尾 token 偏移/被吞（漏字）。

            sample.acceptWaveform(padded, 16000)
            recognizer.decode(sample)
            val result = recognizer.getResult(sample)
            val duration = System.currentTimeMillis() - startTime
            Log.i(TAG, "[V2_ASR] Result (${duration}ms): \"${result.text.trim()}\"")
            result.text.trim()
        } catch (e: Exception) {
            Log.e(TAG, "[V2_ASR] Transcription error: ${e.message}", e)
            "Error: ${e.message}"
        }
    }

    // 把每段音频落盘为 WAV，便于人工试听核对
    fun dumpWave(audioData: FloatArray, tag: String) {
        val dir = dumpDir ?: return
        try {
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "seg_${System.currentTimeMillis()}_$tag.wav")
            writeWavFile(audioData, file)
            Log.d(TAG, "[WAV_DUMP] Saved ${audioData.size} samples to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "[WAV_DUMP] Failed to save wav: ${e.message}")
        }
    }

    fun close() {
        Log.d(TAG, "Closing recognizer")
        try {
            recognizer.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing recognizer: ${e.message}")
        }
    }
}
