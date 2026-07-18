package com.zeerd.real_timetranscriptionapp

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.sqrt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RawSpeakerTurn(
    val speakerId: String,
    // 语音段结束时间（解析完成时记录），用于 MAX_CHARS 兜底与日志
    val timestampMs: Long,
    // 语音段开始时间（VAD 进入 RECORDING 时记录），用于计算段间真实停顿
    val startTimestampMs: Long,
    val rawText: String
)

class SpeakerDiarizationManager(
    private val context: Context,
    private val modelPath: String
) {
    private val TAG = "SpeakerDiarization"
    private val extractor: SpeakerEmbeddingExtractor
    private val speakerProfiles = mutableMapOf<String, FloatArray>()
    // 声纹画像持久化文件：以声纹模型文件名（无后缀）为前缀，按模型隔离，
    // 切换模型时互不混淆，且切换模型不会丢失历史画像（旧模型文件仍保留，切回即恢复）。
    private val modelKey = File(modelPath).nameWithoutExtension
    private val profilesFile = File(context.filesDir, "speaker_profiles_$modelKey.bin")
    private val threshold = Constants.DIARIZATION_SIMILARITY_THRESHOLD
    // 画像更新系数：匹配成功后用新 embedding 平滑更新画像，避免画像停留在首句短音频而漂移
    private val profileUpdateRate = 0.25f
    // 记录上一次识别出的说话人，用于极短片段回退
    private var lastSpeakerId: String? = null
    // extractor 非线程安全：pipeline 的实时滑窗检测与 MainActivity 的事后标注会并发访问，统一加锁
    private val extractorMutex = Mutex()

    init {
        Log.i(TAG, "[V2_DIARIZATION] Initializing with model: $modelPath")
        val config = SpeakerEmbeddingExtractorConfig(
            model = modelPath,
            numThreads = 4,
            debug = false
        )
        extractor = SpeakerEmbeddingExtractor(null, config)
        Log.i(TAG, "[V2_DIARIZATION] Extractor initialized.")
        // 恢复历史声纹画像，使重启后说话人序号与画像延续
        loadProfiles()
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
            saveProfiles()
            bestSpeakerId
        } else {
            val newId = "Speaker ${speakerProfiles.size + 1}"
            speakerProfiles[newId] = embedding
            lastSpeakerId = newId
            Log.i(TAG, "[V2_DIARIZATION] New speaker detected: $newId (maxSim: ${String.format("%.3f", maxSimilarity)}) in ${duration}ms")
            saveProfiles()
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

    /**
     * 线程安全的声纹向量提取，供实时滑窗换人检测（SpeakerChangeDetector）复用同一 extractor。
     * 加锁避免与 [identifySpeaker] 并发访问底层 C++ extractor 导致状态错乱。
     */
    suspend fun extractEmbeddingSafe(audioSegment: FloatArray): FloatArray = extractorMutex.withLock {
        extractEmbedding(audioSegment)
    }

    /**
     * 从内部存储恢复声纹画像。文件格式：
     * [int 说话人数][(UTF id, int 维度, float[] 向量) * 说话人数]
     * 加载失败（文件损坏/不存在）时静默忽略，从头开始聚类。
     */
    private fun loadProfiles() {
        if (!profilesFile.exists()) {
            Log.i(TAG, "[V2_DIARIZATION] No persisted speaker profiles found, starting fresh.")
            return
        }
        try {
            DataInputStream(FileInputStream(profilesFile)).use { dis ->
                val count = dis.readInt()
                repeat(count) {
                    val id = dis.readUTF()
                    val dim = dis.readInt()
                    val arr = FloatArray(dim) { dis.readFloat() }
                    speakerProfiles[id] = arr
                }
            }
            Log.i(TAG, "[V2_DIARIZATION] Loaded ${speakerProfiles.size} speaker profiles from disk: ${speakerProfiles.keys}")
        } catch (e: Exception) {
            Log.e(TAG, "[V2_DIARIZATION] Failed to load speaker profiles (corrupted?), ignoring: ${e.message}", e)
            speakerProfiles.clear()
        }
    }

    /**
     * 将当前声纹画像持久化到内部存储。每次新增或更新说话人时调用；
     * 文件很小（几 KB），且说话人切换频率低（每段语音一次），同步写入不会拖慢实时管线。
     */
    private fun saveProfiles() {
        try {
            DataOutputStream(FileOutputStream(profilesFile)).use { dos ->
                dos.writeInt(speakerProfiles.size)
                for ((id, arr) in speakerProfiles) {
                    dos.writeUTF(id)
                    dos.writeInt(arr.size)
                    for (v in arr) dos.writeFloat(v)
                }
            }
            Log.d(TAG, "[V2_DIARIZATION] Saved ${speakerProfiles.size} speaker profiles to disk")
        } catch (e: Exception) {
            Log.e(TAG, "[V2_DIARIZATION] Failed to save speaker profiles: ${e.message}", e)
        }
    }

    /**
     * 清空持久化与内存中的声纹画像（例如用户主动「重置说话人」时调用）。
     * 同时清空当前声纹模型对应的命名映射，避免名字与画像错位。
     */
    fun clearProfiles() {
        speakerProfiles.clear()
        lastSpeakerId = null
        SpeakerNameStore.clear()
        try {
            if (profilesFile.exists()) profilesFile.delete()
            Log.i(TAG, "[V2_DIARIZATION] Speaker profiles cleared.")
        } catch (e: Exception) {
            Log.e(TAG, "[V2_DIARIZATION] Failed to delete profiles file: ${e.message}", e)
        }
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
