package com.zeerd.real_timetranscriptionapp

import android.util.Log
import kotlin.math.sqrt

/**
 * 声纹滑窗换人检测（Speaker-Change-Driven Segmentation）。
 *
 * 在 VAD 判定为连续语音（无明显静音）时，仍按固定子窗口滚动提取声纹向量（Embedding），
 * 并比较相邻窗口的余弦相似度。当相似度跌破阈值且连续多次确认时，判定说话人已切换，
 * 即便中间没有停顿也强制切断当前音频段，避免两人无缝接话/打断被并成一段。
 *
 * 设计要点：
 * - 复用 [SpeakerDiarizationManager] 的同一 extractor（通过 [extractEmbedding] 回调），避免重复加载模型。
 * - 仅做"相对换人"判断（相邻窗口差值），不依赖已注册的说话人画像，因此对未知说话人也有效。
 * - 通过 [confirmStreak] 防抖，过滤噪声导致的偶发相似度暴跌。
 */
class SpeakerChangeDetector(
    private val extractEmbedding: suspend (FloatArray) -> FloatArray,
    private val subWindowSamples: Int = Constants.SCD_SUB_WINDOW_SAMPLES,
    private val slideStepSamples: Int = Constants.SCD_SLIDE_STEP_SAMPLES,
    private val changeThreshold: Float = Constants.SCD_CHANGE_THRESHOLD,
    private val confirmStreak: Int = Constants.SCD_CONFIRM_STREAK,
    private val minCutSamples: Int = Constants.SCD_MIN_CUT_SAMPLES
) {
    private val TAG = "SpeakerChangeDetector"

    // 上一次滑窗的声纹向量，用于与当前窗口比较
    private var lastEmbedding: FloatArray? = null
    // 连续跌破阈值的次数，用于防抖
    private var belowStreak = 0
    // 自上次切段以来累积的样本数，用于保证单次切出的最小有效时长
    private var sinceLastCutSamples = 0

    /**
     * 在 RECORDING 状态下，每收到一帧音频后调用，传入当前 speechBuffer 的只读视图。
     *
     * @param speechBuffer 当前累积的语音样本（Float，16kHz，[-1,1]）
     * @return 若检测到换人且可安全切段，返回应保留在新 buffer 中的样本（即换人点之后的音频）；
     *         否则返回 null，表示继续累积。
     */
    suspend fun process(speechBuffer: List<Float>): FloatArray? {
        // 需要至少能容纳一个子窗口 + 一个滑动步长，才能构造两个重叠窗口
        val required = subWindowSamples + slideStepSamples
        if (speechBuffer.size < required) {
            return null
        }

        // 取最近的两个重叠子窗口：
        // windowA = 最近 subWindowSamples 个样本
        // windowB = 比 windowA 早 slideStepSamples 开始、同样长度的窗口（与 A 重叠）
        val windowA = speechBuffer.takeLast(subWindowSamples).toFloatArray()
        val windowB = speechBuffer.takeLast(required).take(subWindowSamples).toFloatArray()

        val embeddingA = extractEmbedding(windowA)
        // windowB 与上一帧的 windowA 完全重叠（滑动步长=200ms），直接复用上一帧 embedding，
        // 省去一次声纹提取（约减半 SCD 开销），避免拖慢实时管线。
        val embeddingB = lastEmbedding ?: extractEmbedding(windowB)

        val similarity = cosineSimilarity(embeddingA, embeddingB)
        sinceLastCutSamples += slideStepSamples

        // 首帧无历史向量，仅记录基线
        val prev = lastEmbedding
        lastEmbedding = embeddingA

        if (prev == null) {
            belowStreak = 0
            return null
        }

        if (similarity < changeThreshold) {
            belowStreak++
            Log.d(TAG, "SCD below threshold: sim=${String.format("%.3f", similarity)} streak=$belowStreak/$confirmStreak")
        } else {
            belowStreak = 0
        }

        // 连续多次跌破阈值，且自上次切段以来已累积足够时长，才真正切段
        if (belowStreak >= confirmStreak && sinceLastCutSamples >= minCutSamples) {
            Log.i(TAG, "[SCD_CUT] Speaker change confirmed (sim=${String.format("%.3f", similarity)}), forcing segment cut.")
            belowStreak = 0
            sinceLastCutSamples = 0
            // 保留 windowA（换人点之后的音频）作为新说话人的起始 buffer
            return windowA
        }

        return null
    }

    /**
     * 在每次 VAD 自然切段或强制切段后调用，重置滑窗状态，避免跨段误判。
     */
    fun reset() {
        lastEmbedding = null
        belowStreak = 0
        sinceLastCutSamples = 0
    }

    private fun cosineSimilarity(vectorA: FloatArray, vectorB: FloatArray): Float {
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        for (i in vectorA.indices) {
            dotProduct += vectorA[i] * vectorB[i]
            normA += vectorA[i] * vectorA[i]
            normB += vectorB[i] * vectorB[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0f) dotProduct / denom else 0f
    }
}
