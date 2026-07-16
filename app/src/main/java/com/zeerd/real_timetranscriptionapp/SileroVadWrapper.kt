package com.zeerd.real_timetranscriptionapp

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

class SileroVadWrapper(context: Context) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val TAG = "SileroVadWrapper"

    // Silero VAD v4 hidden states: h and c, each [2, 1, 64]
    private var hState: FloatBuffer = FloatBuffer.allocate(2 * 1 * 64)
    private var cState: FloatBuffer = FloatBuffer.allocate(2 * 1 * 64)
    private val stateShape = longArrayOf(2, 1, 64)

    init {
        Log.d(TAG, "Initializing SileroVadWrapper (v4 mode)...")
        try {
            val modelBytes = context.assets.open("silero_vad.onnx").readBytes()
            session = env.createSession(modelBytes)
            Log.d(TAG, "ONNX Session created successfully. Inputs: ${session.inputNames}, Outputs: ${session.outputNames}")
            reset()
        } catch (e: Exception) {
            Log.e(TAG, "[FATAL_ERROR] Failed to initialize Silero VAD: ${e.message}", e)
            throw e
        }
    }

    fun isSpeech(audioFrame: FloatArray): Float {
        return try {
            val inputShape = longArrayOf(1, audioFrame.size.toLong())
            val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(audioFrame), inputShape)
            
            val hTensor = OnnxTensor.createTensor(env, hState, stateShape)
            val cTensor = OnnxTensor.createTensor(env, cState, stateShape)
            
            // Map inputs based on the model's expectation: x, h, c
            // Some versions also need 'sr', but your error message only listed [x, h, c]
            val inputs = mutableMapOf(
                "x" to inputTensor,
                "h" to hTensor,
                "c" to cTensor
            )
            
            if (session.inputNames.contains("sr")) {
                inputs["sr"] = OnnxTensor.createTensor(env, longArrayOf(16000))
            }

            session.run(inputs).use { results ->
                // Results order: output, hn, cn
                val outputTensor = results[0] as OnnxTensor
                val probability = outputTensor.floatBuffer.get(0)
                
                val hnTensor = results[1] as OnnxTensor
                val cnTensor = results[2] as OnnxTensor
                
                hState.clear()
                hState.put(hnTensor.floatBuffer)
                hState.flip()
                
                cState.clear()
                cState.put(cnTensor.floatBuffer)
                cState.flip()
                
                probability
            }
        } catch (e: Exception) {
            Log.e(TAG, "VAD Inference error: ${e.message}")
            0f
        }
    }

    fun reset() {
        Log.d(TAG, "Resetting VAD states (h and c)")
        hState.clear()
        while(hState.hasRemaining()) hState.put(0f)
        hState.flip()
        
        cState.clear()
        while(cState.hasRemaining()) cState.put(0f)
        cState.flip()
    }

    fun close() {
        Log.d(TAG, "Closing VAD session")
        try {
            session.close()
            env.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VAD: ${e.message}")
        }
    }
}
