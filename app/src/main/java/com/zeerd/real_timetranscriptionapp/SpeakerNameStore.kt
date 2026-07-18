package com.zeerd.real_timetranscriptionapp

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 说话人命名映射（speakerId → 显示名）的持久化存储，按声纹模型隔离。
 *
 * 不同声纹模型的声纹空间不同，同一个 "Speaker 1" 在不同模型下指向不同的人。
 * 因此命名与画像都以「声纹模型文件名（无后缀）」为前缀分别存储，切换模型时互不混淆，
 * 且切换模型不会丢失历史数据（旧模型的命名/画像仍保留在各自文件里，切回即恢复）。
 *
 * 设计要点：
 * - 纯文本格式（每行 `id\tname`），不引入 JSON 依赖，保持最小改动。
 * - 作为单例对象，Service 与 UI 共享同一份映射；[init] 在 Application.onCreate 调用一次。
 * - [setActiveModel] 在管线启动、选定声纹模型后调用，切换当前生效的模型命名空间。
 */
object SpeakerNameStore {
    private const val FILE_PREFIX = "speaker_names_"
    private const val FILE_SUFFIX = ".txt"
    private const val TAG = "SpeakerNameStore"

    private lateinit var filesDir: File
    // 每个声纹模型一套命名（key = 模型文件名无后缀）
    private val models = mutableMapOf<String, ModelNames>()
    private var activeKey: String = ""

    private data class ModelNames(
        val file: File,
        val map: MutableMap<String, String> = mutableMapOf(),
        val knownIds: MutableSet<String> = mutableSetOf()
    ) {
        fun load() {
            if (!file.exists()) return
            try {
                file.readLines().forEach { line ->
                    val tab = line.indexOf('\t')
                    if (tab > 0) {
                        val id = line.substring(0, tab)
                        val name = line.substring(tab + 1)
                        if (id.isNotBlank()) map[id] = name
                    }
                }
                Log.i(TAG, "Loaded ${map.size} speaker name mappings for model: $activeKey")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load speaker names for $activeKey: ${e.message}", e)
                map.clear()
            }
        }

        fun save() {
            try {
                file.writeText(map.entries.joinToString("\n") { "${it.key}\t${it.value}" })
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save speaker names for $activeKey: ${e.message}", e)
            }
        }
    }

    fun init(context: Context) {
        filesDir = context.filesDir
    }

    /** 切换当前生效的声纹模型命名空间（在管线选定 speaker 模型后调用）。 */
    fun setActiveModel(modelKey: String) {
        activeKey = modelKey
        val model = models.getOrPut(modelKey) {
            ModelNames(File(filesDir, "$FILE_PREFIX$modelKey$FILE_SUFFIX"))
        }
        // 首次接触该模型时从磁盘加载；已加载过则复用内存态（保留未落盘的编辑）
        if (model.map.isEmpty() && model.knownIds.isEmpty()) model.load()
        Log.i(TAG, "Active speaker model set to: $modelKey (knownIds=${model.knownIds})")
    }

    fun register(speakerId: String) {
        models[activeKey]?.knownIds?.add(speakerId)
    }

    /** 返回显示名；未命名时原样返回 speakerId（如 "Speaker 1"）。 */
    fun getDisplayName(speakerId: String): String =
        models[activeKey]?.map?.get(speakerId) ?: speakerId

    fun setName(speakerId: String, name: String) {
        val model = models[activeKey] ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) model.map.remove(speakerId) else model.map[speakerId] = trimmed
        model.knownIds.add(speakerId)
        model.save()
        Log.i(TAG, "Set name [$activeKey]: $speakerId -> ${model.map[speakerId] ?: "<removed>"}")
    }

    fun getAll(): Map<String, String> = models[activeKey]?.map?.toMap() ?: emptyMap()

    /** 返回当前模型所有出现过的说话人 ID（含未命名的），供 UI 列出。 */
    fun getAllKnownIds(): List<String> = models[activeKey]?.knownIds?.sortedBy { it } ?: emptyList()

    /**
     * 把文本中所有 `[Speaker N]` 标签替换为用户命名（未命名则保持原样）。
     * 用于 LLM 正式稿：模型输出里仍是自动序号，需在此统一替换成显示名。
     */
    fun substituteLabels(text: String): String {
        val map = models[activeKey]?.map ?: return text
        if (map.isEmpty()) return text
        var result = text
        for ((id, name) in map) result = result.replace("[$id]", "[$name]")
        return result
    }

    /** 清空当前生效模型的命名（换模型 / 重置说话人时调用，与画像一同清除）。 */
    fun clear() {
        val model = models[activeKey] ?: return
        model.map.clear()
        model.knownIds.clear()
        model.save()
        Log.i(TAG, "Speaker names cleared for model: $activeKey")
    }
}
