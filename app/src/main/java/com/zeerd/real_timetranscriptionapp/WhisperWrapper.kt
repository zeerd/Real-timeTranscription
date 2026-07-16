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

class WhisperWrapper(context: Context) {
    private val recognizer: OfflineRecognizer
    private val TAG = "WhisperWrapper"

    init {
        Log.d(TAG, "Initializing WhisperWrapper...")
        // Copy models from assets to files directory if they don't exist
        val assetsDir = "whisper-tiny"
        val filesDir = context.filesDir.absolutePath + "/whisper-tiny"
        val dir = File(filesDir)
        if (!dir.exists()) {
            val created = dir.mkdirs()
            Log.d(TAG, "Created directory $filesDir: $created")
        }

        try {
            copyAsset(context, "$assetsDir/tiny-encoder.int8.onnx", "$filesDir/tiny-encoder.int8.onnx")
            copyAsset(context, "$assetsDir/tiny-decoder.int8.onnx", "$filesDir/tiny-decoder.int8.onnx")
            copyAsset(context, "$assetsDir/tiny-tokens.txt", "$filesDir/tiny-tokens.txt")
        } catch (e: Exception) {
            Log.e(TAG, "[FATAL_ERROR] Failed to copy assets: ${e.message}", e)
            throw e
        }

        Log.d(TAG, "Checking model files in $filesDir...")
        listOf("tiny-encoder.int8.onnx", "tiny-decoder.int8.onnx", "tiny-tokens.txt").forEach {
            val file = File(filesDir, it)
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
                tokens = "$filesDir/tiny-tokens.txt",
                modelType = "whisper",
                numThreads = 4,
                debug = false
            ).apply {
                whisper = OfflineWhisperModelConfig(
                    encoder = "$filesDir/tiny-encoder.int8.onnx",
                    decoder = "$filesDir/tiny-decoder.int8.onnx",
                    language = "zh",
                    task = "transcribe"
                )
            },
            decodingMethod = "greedy_search"
        )
        
        try {
            recognizer = OfflineRecognizer(null, config)
            Log.d(TAG, "OfflineRecognizer initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "[FATAL_ERROR] Failed to initialize OfflineRecognizer: ${e.message}", e)
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
        Log.d(TAG, "Transcribing ${audioData.size} samples...")
        return try {
            val sample = recognizer.createStream()
            // Force Simplified Chinese using initial prompt
            sample.setOption("prompt", "简体中文")

            sample.acceptWaveform(audioData, 16000)
            recognizer.decode(sample)
            val result = recognizer.getResult(sample)
            Log.d(TAG, "Transcription result: ${result.text}")
            result.text.trim()
        } catch (e: Exception) {
            Log.e(TAG, "Transcription error: ${e.message}", e)
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
