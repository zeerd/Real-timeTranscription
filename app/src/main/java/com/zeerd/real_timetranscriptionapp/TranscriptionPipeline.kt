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
    val transcribedText: String,
    // 语音段开始时间（VAD 检测到语音、进入 RECORDING 的时刻），用于计算段间真实停顿
    val segmentStartTimestampMs: Long = 0L
)

class TranscriptionPipeline(
    private val audioChannel: Channel<ByteArray>,
    private val vadWrapper: SileroVadWrapper,
    private val whisperWrapper: WhisperWrapper,
    private val resultChannel: Channel<AudioTextPair>,
    private val speakerChangeDetector: SpeakerChangeDetector? = null
) {
    private val TAG = "TranscriptionPipeline"
    private val _vadState = MutableStateFlow(VadState.IDLE)
    val vadState = _vadState.asStateFlow()

    // 实时音量电平（RMS，0~1 左右），供 UI / 通知展示麦克风活跃度。
    // 放在管线里统一计算，避免再开一个消费者抢占 audioChannel。
    private val _volume = MutableStateFlow(0f)
    val volume = _volume.asStateFlow()

    private var silenceCount = 0
    private val speechBuffer = mutableListOf<Float>()

    // 当前语音段的开始时间戳（进入 RECORDING 时记录）
    private var segmentStartTimestampMs: Long = 0L

    // Pre-roll buffer to avoid losing the first few characters
    private val preRollBuffer = LinkedList<FloatArray>()

    // SCD 滑窗检测节流计数器：声纹 embedding 提取开销大（每次数百 ms），
    // 若每帧（32ms）都跑会把管线拖慢到实时性的数倍，导致说完话后十几秒才出结果。
    // 按滑动步长（200ms）节奏触发，既保持检测灵敏度又避免拖慢实时性。
    private var scdAccumulatedSamples = 0

    suspend fun startProcessing() = withContext(Dispatchers.Default) {
        Log.d(TAG, "TranscriptionPipeline started processing with pre-roll support")
        try {
            while (isActive) {
                val byteBuffer = audioChannel.receive()
                val floatFrame = byteBuffer.toFloatArrayNormalized()

                // 计算本帧 RMS 音量（用于 UI 指示），与 VAD 共用同一帧，零额外开销
                var sumSq = 0.0f
                for (s in floatFrame) sumSq += s * s
                val rms = kotlin.math.sqrt(sumSq / floatFrame.size)
                _volume.value = rms.coerceIn(0f, 1f)

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

                            // 记录语音段开始时间（用于语义切批的真实停顿计算）
                            segmentStartTimestampMs = System.currentTimeMillis()
                            scdAccumulatedSamples = 0

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

                        // 声纹滑窗换人检测：即便 VAD 判定为连续语音（无静音），
                        // 若相邻窗口声纹相似度暴跌，也强制切段，避免两人无缝接话被并成一段。
                        // 注意：声纹 embedding 提取开销大（每次数百 ms），必须按滑动步长（200ms）
                        // 节奏节流触发，否则每帧（32ms）都跑会把管线拖慢到实时性的数倍，
                        // 导致说完话后十几秒才出识别结果。
                        var shouldCutSpeakerChange = false
                        if (speakerChangeDetector != null && speechBuffer.size >= Constants.SCD_SUB_WINDOW_SAMPLES + Constants.SCD_SLIDE_STEP_SAMPLES) {
                            scdAccumulatedSamples += floatFrame.size
                            if (scdAccumulatedSamples >= Constants.SCD_SLIDE_STEP_SAMPLES) {
                                scdAccumulatedSamples = 0
                                val retained = speakerChangeDetector.process(speechBuffer)
                                if (retained != null) {
                                shouldCutSpeakerChange = true
                                // 切出当前段（换人点之前的所有音频），保留新说话人音频到 buffer
                                if (speechBuffer.size >= Constants.MIN_SPEECH_DURATION_SAMPLES) {
                                    val audioData = speechBuffer.toFloatArray()
                                    // whisperWrapper.dumpWave(audioData, "scd")
                                    val text = whisperWrapper.transcribe(audioData)
                                    resultChannel.send(AudioTextPair(audioData, text, segmentStartTimestampMs))
                                } else {
                                    Log.d(TAG, "SCD cut but segment too short (${speechBuffer.size} samples). Discarding.")
                                }
                                speechBuffer.clear()
                                speechBuffer.addAll(retained.toList())
                                // 新说话人段重新开始计时
                                segmentStartTimestampMs = System.currentTimeMillis()
                                silenceCount = 0
                            }
                        }
                        }

                        val shouldCutNatural = silenceCount >= Constants.SILENCE_THRESHOLD_LIMIT
                        val shouldCutForce = speechBuffer.size >= Constants.MAX_SPEECH_DURATION_SAMPLES

                        if (shouldCutNatural || shouldCutForce) {
                            val reason = if (shouldCutNatural) "natural silence" else "max duration"
                            
                            if (speechBuffer.size >= Constants.MIN_SPEECH_DURATION_SAMPLES) {
                                Log.d(TAG, "Cut triggered by $reason. Samples: ${speechBuffer.size}. Transcribing and sending.")
                                val audioData = speechBuffer.toFloatArray()
                                // whisperWrapper.dumpWave(audioData, reason.replace(" ", "_"))
                                val text = whisperWrapper.transcribe(audioData)
                                resultChannel.send(AudioTextPair(audioData, text, segmentStartTimestampMs))
                            } else {
                                Log.d(TAG, "Cut triggered by $reason, but segment too short (${speechBuffer.size} samples). Discarding.")
                            }
                            
                            speechBuffer.clear()
                            _vadState.value = VadState.IDLE
                            silenceCount = 0
                            vadWrapper.reset()
                            preRollBuffer.clear()
                            scdAccumulatedSamples = 0
                            speakerChangeDetector?.reset()
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
