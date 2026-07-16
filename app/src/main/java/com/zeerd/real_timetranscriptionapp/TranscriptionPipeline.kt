package com.zeerd.real_timetranscriptionapp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class TranscriptionPipeline(
    private val audioChannel: Channel<ByteArray>,
    private val vadWrapper: SileroVadWrapper,
    private val transcriptionChannel: Channel<FloatArray>
) {
    private val TAG = "TranscriptionPipeline"
    private val _vadState = MutableStateFlow(VadState.IDLE)
    val vadState = _vadState.asStateFlow()

    private var silenceCount = 0
    private val speechBuffer = mutableListOf<Float>()

    suspend fun startProcessing() = withContext(Dispatchers.Default) {
        Log.d(TAG, "TranscriptionPipeline started processing")
        try {
            while (isActive) {
                val byteBuffer = audioChannel.receive()
                val floatFrame = byteBuffer.toFloatArrayNormalized()
                
                val probability = vadWrapper.isSpeech(floatFrame)
                val isSpeech = probability > Constants.SPEECH_PROBABILITY_THRESHOLD

                when (_vadState.value) {
                    VadState.IDLE -> {
                        if (isSpeech) {
                            Log.d(TAG, "Speech detected (prob: $probability), transition IDLE -> RECORDING")
                            _vadState.value = VadState.RECORDING
                            speechBuffer.addAll(floatFrame.toList())
                            silenceCount = 0
                        }
                    }
                    VadState.RECORDING -> {
                        speechBuffer.addAll(floatFrame.toList())
                        if (isSpeech) {
                            silenceCount = 0
                        } else {
                            silenceCount++
                        }

                        val shouldCutNatural = silenceCount >= Constants.SILENCE_THRESHOLD_LIMIT
                        val shouldCutForce = speechBuffer.size >= Constants.MAX_SPEECH_DURATION_SAMPLES

                        if (shouldCutNatural || shouldCutForce) {
                            val reason = if (shouldCutNatural) "natural silence" else "max duration"
                            Log.d(TAG, "Cut triggered by $reason. Samples: ${speechBuffer.size}. Transition RECORDING -> IDLE")
                            
                            transcriptionChannel.send(speechBuffer.toFloatArray())
                            speechBuffer.clear()
                            _vadState.value = VadState.IDLE
                            silenceCount = 0
                            vadWrapper.reset()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Processing loop error: ${e.message}", e)
        } finally {
            Log.d(TAG, "TranscriptionPipeline stopped")
        }
    }
}
