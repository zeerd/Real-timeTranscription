package com.zeerd.real_timetranscriptionapp

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.io.FileOutputStream

class SileroVadWrapper(context: Context) {
    private val TAG = "SileroVadWrapper"
    private val vad: Vad

    init {
        Log.i(TAG, "[V2_VAD] Initializing SileroVadWrapper using sherpa-onnx Vad...")
        try {
            val vadFile = File(context.filesDir, "silero_vad.onnx")
            if (!vadFile.exists()) {
                context.assets.open("silero_vad.onnx").use { input ->
                    FileOutputStream(vadFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            val sileroVadConfig = SileroVadModelConfig(
                model = vadFile.absolutePath,
                threshold = 0.5f,
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 30.0f
            )
            val vadConfig = VadModelConfig(
                sileroVadModelConfig = sileroVadConfig,
                sampleRate = 16000,
                numThreads = 1,
                provider = "cpu",
                debug = false
            )
            vad = Vad(null, vadConfig)
            Log.i(TAG, "[V2_VAD] sherpa-onnx Vad initialized successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "[V2_VAD] [FATAL_ERROR] Failed to initialize: ${e.message}", e)
            throw e
        }
    }

    fun isSpeech(audioFrame: FloatArray): Float {
        return try {
            vad.compute(audioFrame)
        } catch (e: Exception) {
            Log.e(TAG, "[V2_VAD] Inference error: ${e.message}")
            0f
        }
    }

    fun reset() {
        Log.d(TAG, "Resetting VAD states")
        try {
            vad.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing VAD: ${e.message}")
        }
    }

    fun close() {
        Log.d(TAG, "Closing VAD session")
        try {
            vad.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VAD: ${e.message}")
        }
    }
}
