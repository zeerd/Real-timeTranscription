package com.zeerd.real_timetranscriptionapp

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import java.io.FileOutputStream

class WhisperWrapper(context: Context, modelId: String, modelDir: File) {
    private val recognizer: OfflineRecognizer
    private val TAG = "WhisperWrapper"

    init {
        Log.d(TAG, "Initializing WhisperWrapper with model: $modelId at ${modelDir.absolutePath}")
        
        // Handle models that might still be in assets (legacy) or in filesDir
        val filesDir = modelDir.absolutePath
        
        // Determine filenames based on model ID
        val prefix = when {
            modelId.contains("tiny") -> "tiny"
            modelId.contains("base") -> "base"
            modelId.contains("small") -> "small"
            else -> "tiny"
        }

        val encoderPath = File(modelDir, "$prefix-encoder.int8.onnx").absolutePath
        val decoderPath = File(modelDir, "$prefix-decoder.int8.onnx").absolutePath
        val tokensPath = File(modelDir, "$prefix-tokens.txt").absolutePath

        Log.d(TAG, "Checking model files in $filesDir...")
        listOf("$prefix-encoder.int8.onnx", "$prefix-decoder.int8.onnx", "$prefix-tokens.txt").forEach {
            val file = File(modelDir, it)
            if (!file.exists()) {
                Log.e(TAG, "CRITICAL: Model file ${file.absolutePath} is missing!")
            } else {
                Log.d(TAG, "Found: ${file.name} (${file.length()} bytes)")
            }
        }

        val config = OfflineRecognizerConfig(
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
            decodingMethod = "greedy_search"
        )
        
        try {
            recognizer = OfflineRecognizer(null, config)
            Log.i(TAG, "[V2_ASR] OfflineRecognizer initialized successfully for $modelId")
        } catch (e: Exception) {
            Log.e(TAG, "[V2_ASR] [FATAL_ERROR] Failed to initialize: ${e.message}", e)
            throw e
        }
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
            val sample = recognizer.createStream()
            // Force Simplified Chinese using initial prompt
            sample.setOption("prompt", "简体中文")

            sample.acceptWaveform(audioData, 16000)
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

    fun close() {
        Log.d(TAG, "Closing recognizer")
        try {
            recognizer.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing recognizer: ${e.message}")
        }
    }
}
