package com.zeerd.real_timetranscriptionapp

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.io.File
import kotlin.math.sqrt

data class RawSpeakerTurn(
    val speakerId: String,
    val timestampMs: Long,
    val rawText: String
)

class SpeakerDiarizationManager(
    private val context: Context,
    private val modelPath: String
) {
    private val TAG = "SpeakerDiarization"
    private val extractor: SpeakerEmbeddingExtractor
    private val speakerProfiles = mutableMapOf<String, FloatArray>()
    private val threshold = Constants.DIARIZATION_SIMILARITY_THRESHOLD
    // 画像更新系数：匹配成功后用新 embedding 平滑更新画像，避免画像停留在首句短音频而漂移
    private val profileUpdateRate = 0.25f
    // 记录上一次识别出的说话人，用于极短片段回退
    private var lastSpeakerId: String? = null

    init {
        Log.i(TAG, "[V2_DIARIZATION] Initializing with model: $modelPath")
        val config = SpeakerEmbeddingExtractorConfig(
            model = modelPath,
            numThreads = 4,
            debug = false
        )
        extractor = SpeakerEmbeddingExtractor(null, config)
        Log.i(TAG, "[V2_DIARIZATION] Extractor initialized.")
    }

    fun identifySpeaker(audioSegment: FloatArray): String {
        Log.d(TAG, "[V2_DIARIZATION] Identifying speaker for ${audioSegment.size} samples...")
        val startTime = System.currentTimeMillis()
        val embedding = extractEmbedding(audioSegment)

        // 极短片段 embedding 不可靠，直接沿用上一位说话人，避免每句都误判为新说话人
        if (audioSegment.size < Constants.MIN_DIARIZATION_SAMPLES && lastSpeakerId != null) {
            Log.i(TAG, "[V2_DIARIZATION] Segment too short (${audioSegment.size} < ${Constants.MIN_DIARIZATION_SAMPLES}), reusing last speaker: $lastSpeakerId")
            return lastSpeakerId!!
        }

        var bestSpeakerId = ""
        var maxSimilarity = -1f

        for ((id, profile) in speakerProfiles) {
            val similarity = calculateCosineSimilarity(embedding, profile)
            if (similarity > maxSimilarity) {
                maxSimilarity = similarity
                bestSpeakerId = id
            }
        }

        val duration = System.currentTimeMillis() - startTime
        return if (maxSimilarity >= threshold) {
            Log.i(TAG, "[V2_DIARIZATION] Identified: $bestSpeakerId (sim: ${String.format("%.3f", maxSimilarity)}) in ${duration}ms")
            // 平滑更新画像，使后续同说话人匹配更稳定
            updateProfile(bestSpeakerId, embedding)
            lastSpeakerId = bestSpeakerId
            bestSpeakerId
        } else {
            val newId = "Speaker ${speakerProfiles.size + 1}"
            speakerProfiles[newId] = embedding
            lastSpeakerId = newId
            Log.i(TAG, "[V2_DIARIZATION] New speaker detected: $newId (maxSim: ${String.format("%.3f", maxSimilarity)}) in ${duration}ms")
            newId
        }
    }

    private fun updateProfile(id: String, embedding: FloatArray) {
        val profile = speakerProfiles[id] ?: run {
            speakerProfiles[id] = embedding.copyOf()
            return
        }
        for (i in profile.indices) {
            profile[i] = profile[i] * (1 - profileUpdateRate) + embedding[i] * profileUpdateRate
        }
    }

    private fun extractEmbedding(audioSegment: FloatArray): FloatArray {
        val stream = extractor.createStream()
        stream.acceptWaveform(audioSegment, 16000)
        stream.inputFinished()
        val embedding = extractor.compute(stream)
        stream.release()
        return embedding
    }

    private fun calculateCosineSimilarity(vectorA: FloatArray, vectorB: FloatArray): Float {
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        for (i in vectorA.indices) {
            dotProduct += vectorA[i] * vectorB[i]
            normA += vectorA[i] * vectorA[i]
            normB += vectorB[i] * vectorB[i]
        }
        return dotProduct / (sqrt(normA) * sqrt(normB))
    }
    
    fun release() {
        extractor.release()
    }
}
