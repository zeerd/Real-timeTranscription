package com.zeerd.real_timetranscriptionapp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.LinkedList

data class AudioTextPair(
    val rawAudio: FloatArray,
    val transcribedText: String
)

class TranscriptionPipeline(
    private val audioChannel: Channel<ByteArray>,
    private val vadWrapper: SileroVadWrapper,
    private val whisperWrapper: WhisperWrapper,
    private val resultChannel: Channel<AudioTextPair>
) {
    private val TAG = "TranscriptionPipeline"
    private val _vadState = MutableStateFlow(VadState.IDLE)
    val vadState = _vadState.asStateFlow()

    private var silenceCount = 0
    private val speechBuffer = mutableListOf<Float>()
    
    // Pre-roll buffer to avoid losing the first few characters
    private val preRollBuffer = LinkedList<FloatArray>()

    suspend fun startProcessing() = withContext(Dispatchers.Default) {
        Log.d(TAG, "TranscriptionPipeline started processing with pre-roll support")
        try {
            while (isActive) {
                val byteBuffer = audioChannel.receive()
                val floatFrame = byteBuffer.toFloatArrayNormalized()
                
                val probability = vadWrapper.isSpeech(floatFrame)
                val isSpeech = probability > Constants.SPEECH_PROBABILITY_THRESHOLD

                when (_vadState.value) {
                    VadState.IDLE -> {
                        // Maintain pre-roll buffer
                        preRollBuffer.addLast(floatFrame)
                        if (preRollBuffer.size > Constants.PRE_SPEECH_BUFFER_FRAMES) {
                            preRollBuffer.removeFirst()
                        }

                        if (isSpeech) {
                            Log.i(TAG, "[V2_PIPELINE] Speech detection trigger (prob: ${String.format("%.3f", probability)}), transition IDLE -> RECORDING")
                            _vadState.value = VadState.RECORDING
                            
                            // 1. Prepend pre-roll buffer
                            speechBuffer.clear()
                            preRollBuffer.forEach { frame ->
                                speechBuffer.addAll(frame.toList())
                            }
                            // Note: floatFrame is already in preRollBuffer and added above
                            
                            silenceCount = 0
                            preRollBuffer.clear()
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
                            
                            if (speechBuffer.size >= Constants.MIN_SPEECH_DURATION_SAMPLES) {
                                Log.d(TAG, "Cut triggered by $reason. Samples: ${speechBuffer.size}. Transcribing and sending.")
                                val audioData = speechBuffer.toFloatArray()
                                val text = whisperWrapper.transcribe(audioData)
                                resultChannel.send(AudioTextPair(audioData, text))
                            } else {
                                Log.d(TAG, "Cut triggered by $reason, but segment too short (${speechBuffer.size} samples). Discarding.")
                            }
                            
                            speechBuffer.clear()
                            _vadState.value = VadState.IDLE
                            silenceCount = 0
                            vadWrapper.reset()
                            preRollBuffer.clear()
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
