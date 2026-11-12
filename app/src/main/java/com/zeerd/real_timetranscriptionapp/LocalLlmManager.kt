package com.zeerd.real_timetranscriptionapp

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class LocalLlmManager(private val context: Context, private val modelDir: File) {
    private val TAG = "LocalLlmManager"

    companion object {
        init {
            try {
                System.loadLibrary("litertlm_jni")
                Log.i("LocalLlmManager", "Successfully loaded liblitertlm_jni.so")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("LocalLlmManager", "Failed to load liblitertlm_jni.so: ${e.message}")
            }
        }
    }

    private var engine: Engine? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    // 使用无界队列：本地 LLM 推理较慢，CONFLATED 会在处理期间覆盖并丢弃后续批次，导致正式稿几乎不更新
    private val processingChannel = Channel<List<RawSpeakerTurn>>(Channel.UNLIMITED)
    // replay=1：即使 UI 端的收集器稍晚挂载，也能拿到最近一次结果，避免“偶尔才显示一次”
    private val _regularizedBlocks = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 16)
    val regularizedBlocks = _regularizedBlocks.asSharedFlow()
    // 保证处理循环只启动一次，避免引擎回退（GPU->CPU）时产生多个消费者竞争同一通道
    private val processQueueStarted = AtomicBoolean(false)

    init {
        scope.launch {
            initializeEngine(attempt = 1)
        }
    }

    private suspend fun initializeEngine(attempt: Int = 1) {
        val useGpu = attempt <= 2 // 前两次尝试 GPU，失败后最后一次尝试 CPU
        val cacheDir = File(context.cacheDir, "litert_cache")
        
        try {
            val modelFile = modelDir.listFiles()?.find { 
                it.name.endsWith(".bin") || it.name.endsWith(".litertlm") || it.name.endsWith(".tflite")
            }
            
            if (modelFile == null) {
                Log.e(TAG, "[V2_LLM] 未在目录中找到 LiteRT LM 模型: ${modelDir.absolutePath}")
                return
            }

            Log.i(TAG, "[V2_LLM] 初始化尝试 #$attempt (Backend: ${if (useGpu) "GPU" else "CPU"})")
            Log.d(TAG, "[V2_LLM] 模型文件路径: ${modelFile.absolutePath}, 缓存目录: ${cacheDir.absolutePath}")
            
            val config = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = if (useGpu) Backend.GPU() else Backend.CPU(),
                cacheDir = cacheDir.absolutePath
            )
            
            val litertEngine = Engine(config)
            litertEngine.initialize()
            engine = litertEngine

            Log.i(TAG, "[V2_LLM] 引擎初始化成功! (Backend: ${if (useGpu) "GPU" else "CPU"})")
            // 处理循环只启动一次，避免回退时产生多个消费者
            if (processQueueStarted.compareAndSet(false, true)) {
                processQueue()
            }
        } catch (e: Exception) {
            Log.e(TAG, "[V2_LLM] 初始化失败 (Attempt #$attempt, GPU=$useGpu)", e)
            
            if (useGpu) {
                if (attempt == 1) {
                    Log.w(TAG, "[V2_LLM] GPU 首次尝试失败，正在清理缓存并重试 GPU...")
                    withContext(Dispatchers.IO) {
                        if (cacheDir.exists()) cacheDir.deleteRecursively()
                        cacheDir.mkdirs()
                    }
                    initializeEngine(attempt = 2)
                } else {
                    Log.w(TAG, "[V2_LLM] GPU 二次尝试依然失败，正在回退到 CPU 模式...")
                    initializeEngine(attempt = 3)
                }
            } else {
                Log.e(TAG, "[V2_LLM] 所有初始化尝试均已失败，LLM 功能将不可用。")
            }
        }
    }

    private suspend fun processQueue() {
        for (turns in processingChannel) {
            // 引擎正在回退/重新初始化时，engine 会短暂为 null。
            // 此时不要丢弃这批数据，而是重新入队，等引擎就绪后再处理，避免正式稿丢失内容。
            if (engine == null) {
                Log.w(TAG, "[V2_LLM] Engine not ready while processing, re-queueing ${turns.size} turns")
                processingChannel.send(turns)
                delay(200)
                continue
            }
            val result = regularizeInternal(turns)
            if (result.isNotBlank()) {
                _regularizedBlocks.emit(result)
            }
        }
    }

    fun enqueueRegularization(turns: List<RawSpeakerTurn>) {
        processingChannel.trySend(turns)
    }

    /** 引擎是否已初始化完成（可用于在推理前判断就绪状态）。 */
    fun isEngineReady(): Boolean = engine != null

    private suspend fun regularizeInternal(turns: List<RawSpeakerTurn>): String = withContext(Dispatchers.Default) {
        val litertEngine = engine ?: run {
            Log.w(TAG, "[V2_LLM] Engine not initialized, skipping regularization")
            return@withContext ""
        }
        
        val systemPrompt = """
你是一位专家级的转录文本编辑员。你的工作是将输入的多段原始语音转录文本，格式化为干净、易读的一段对话式文本。

编辑规则
1： 完整保留每一个说出的单词/字词。切勿进行概括、总结或省略任何事实细节。
2： 将属于同一位发言人（Speaker）的相邻原始文本行进行合并，并加上正确的标点符号，整理成通顺的段落。
3： 每当发言人发生切换，或出现重大的话题转变时，请换行并以 [发言人姓名]: 开头输出新的段落。
4： 根据上下文语境，纠正明显的同音字/语音识别错误/文字遗漏（例如：将 "I'm hear" 纠正为 "I'm here"）。
5： 中文文本请使用简体中文输出。

输入格式
[Speaker X]: 你好
[Speaker X]: 你最近怎么样
输出格式
[Speaker X]: 你好。你最近怎么样？
""".trimIndent()

        val inputPayload = turns.joinToString("\n") { 
            "[${it.speakerId}]: ${it.rawText}" 
        }

        val prompt = "原始文本:\n$inputPayload\n\n请格式化上述文本。/no_think"
        
        Log.i(TAG, "[V2_LLM] Starting inference for ${turns.size} turns. Input size: ${inputPayload.length} chars")
        Log.d(TAG, "[V2_LLM] Prompt preview: \"${prompt.take(100)}\"")
        
        runInference(litertEngine, systemPrompt, prompt)
    }

    /**
     * 对一段完整文本做「总结摘要」。如果文本超出了 LLM 的上下文窗口上限（如 > 3500 字符），
     * 将采用分段提炼 (Map-Reduce) 策略，先对各片段生成子摘要，再对子摘要汇总提炼，
     * 避免触发 LiteRT LM 的 Max Token Limit (4096 tokens) 错误。
     *
     * @param text 待总结的文本（通常是本次采集会话的全部转写内容）
     * @param onProgress 进度回调 (当前步数, 总步数, 阶段描述)
     * @return LLM 生成的摘要；引擎未就绪或失败时返回空字符串
     */
    suspend fun summarize(
        text: String,
        onProgress: ((current: Int, total: Int, description: String) -> Unit)? = null
    ): String = withContext(Dispatchers.Default) {
        val litertEngine = engine ?: run {
            Log.w(TAG, "[V2_LLM] Engine not initialized, skipping summarization")
            return@withContext ""
        }
        if (text.isBlank()) {
            Log.w(TAG, "[V2_LLM] Empty text, skipping summarization")
            return@withContext ""
        }

        // 考虑到 4096 max token limit，以及 Prompt/System Prompt 占用的 token，
        // 单次 Prompt 输入建议控制在 ~3500 字符以内（约 2000~2500 tokens）
        val maxChunkChars = 3500

        if (text.length <= maxChunkChars) {
            onProgress?.invoke(1, 1, "正在生成摘要...")
            return@withContext summarizeSingleChunk(litertEngine, text)
        }

        Log.i(TAG, "[V2_LLM] Text size (${text.length} chars) exceeds chunk threshold ($maxChunkChars), using Map-Reduce summarization")

        // 1. 分切文本（按换行符划分段落）
        val chunks = splitTextIntoChunks(text, maxChunkChars)
        val totalSteps = chunks.size + 1
        Log.i(TAG, "[V2_LLM] Split text into ${chunks.size} chunks for sub-summaries")

        // 2. 对每个 chunk 逐个生成子摘要 (Map)
        val subSummaries = mutableListOf<String>()
        for ((index, chunk) in chunks.withIndex()) {
            val step = index + 1
            Log.i(TAG, "[V2_LLM] Summarizing chunk $step/${chunks.size} (${chunk.length} chars)")
            onProgress?.invoke(step, totalSteps, "正在分析第 $step/${chunks.size} 段文本...")
            val subSummary = summarizeSingleChunk(litertEngine, chunk, isSubSummary = true)
            if (subSummary.isNotBlank()) {
                subSummaries.add("【片段 $step 摘要】:\n$subSummary")
            }
        }

        if (subSummaries.isEmpty()) {
            Log.w(TAG, "[V2_LLM] All chunk summaries failed")
            return@withContext ""
        }

        val combinedSubSummaries = subSummaries.joinToString("\n\n")
        Log.i(TAG, "[V2_LLM] Combined sub-summaries size: ${combinedSubSummaries.length} chars")

        // 3. 对子摘要结果进行最终汇总 (Reduce)
        onProgress?.invoke(totalSteps, totalSteps, "正在整合最终总结...")
        if (combinedSubSummaries.length > maxChunkChars) {
            val truncated = combinedSubSummaries.take(maxChunkChars)
            summarizeSingleChunk(litertEngine, truncated, isReducePass = true)
        } else {
            summarizeSingleChunk(litertEngine, combinedSubSummaries, isReducePass = true)
        }
    }

    private suspend fun summarizeSingleChunk(
        litertEngine: Engine,
        text: String,
        isSubSummary: Boolean = false,
        isReducePass: Boolean = false
    ): String {
        val systemPrompt = when {
            isSubSummary -> """
你是一位专业的会议/对话记录整理助手。你的任务是将长文本中的局部转写内容，提炼为简短的要点概括。

要求：
1： 用简体中文输出。
2： 提炼出本段落中的核心观点、结论或重要发言，使用 - 项目符号。
3： 保持简明扼要（控制在 3~6 条要点内）。
""".trimIndent()
            isReducePass -> """
你是一位专业的会议/对话记录整理助手。以下是根据长篇语音转写分段提炼的各个局部摘要。
你的任务是将这些局部摘要整合为一份最终的完整会议/对话总结。

要求：
1： 用简体中文输出。
2： 先给出一段 2~4 句话的总体概述，概括整场对话/会议的核心主题与结论。
3： 随后汇总所有要点，归类整理为结构清晰的关键信息、决策与待办事项（使用 - 项目符号）。
4： 去除各片段之间的重复信息。
""".trimIndent()
            else -> """
你是一位专业的会议/对话记录整理助手。你的任务是将一段完整的语音转写文本，提炼为简洁、结构清晰的摘要。

要求：
1： 用简体中文输出（除非原文为英文，则保持英文）。
2： 先给出一段 2~4 句话的总体概述，概括本次对话/会议的核心主题与结论。
3： 随后以「要点」形式分条列出关键信息、决策、待办事项或重要结论（使用 - 项目符号）。
4： 若涉及不同发言人，可在要点中标注其观点归属（如 [发言人姓名]）。
5： 不要编造原文中不存在的信息；若内容过于零散无法归纳，可如实说明。
""".trimIndent()
        }

        val prompt = if (isReducePass) {
            "以下是各段落的局部摘要，请整合生成一份最终总结：\n\n$text\n\n请输出最终摘要。/no_think"
        } else {
            "以下是转写内容，请对其进行总结摘要：\n\n$text\n\n请输出摘要。/no_think"
        }

        Log.i(TAG, "[V2_LLM] Starting single-pass summarization. Input size: ${text.length} chars (isSubSummary=$isSubSummary, isReduce=$isReducePass)")
        return runInference(litertEngine, systemPrompt, prompt)
    }

    /**
     * 将长文本按段落 (换行符) 切分为若干不超过 [maxChars] 字符的 chunk。
     */
    private fun splitTextIntoChunks(text: String, maxChars: Int): List<String> {
        val lines = text.lines()
        val chunks = mutableListOf<String>()
        val currentChunk = StringBuilder()

        for (line in lines) {
            if (currentChunk.length + line.length + 1 > maxChars && currentChunk.isNotEmpty()) {
                chunks.add(currentChunk.toString().trim())
                currentChunk.clear()
            }
            if (line.length > maxChars) {
                var i = 0
                while (i < line.length) {
                    val end = (i + maxChars).coerceAtMost(line.length)
                    chunks.add(line.substring(i, end))
                    i = end
                }
            } else {
                currentChunk.append(line).append("\n")
            }
        }
        if (currentChunk.isNotBlank()) {
            chunks.add(currentChunk.toString().trim())
        }
        return chunks
    }

    /**
     * 在给定引擎上执行一次完整的推理（创建会话 → 流式收集 → 去除思考标签 → 返回文本）。
     * 供 [regularizeInternal] 与 [summarize] 复用。
     */
    private suspend fun runInference(
        litertEngine: Engine,
        systemPrompt: String,
        prompt: String
    ): String {
        return try {
            var fullResponse = ""
            val startTime = System.currentTimeMillis()
            
            // 将 systemPrompt 拼接在 prompt 头部，避免 LiteRT LM 内部 Jinja 模板渲染 systemInstruction 时
            // 抛出 "tried to use + operator on unsupported types string and sequence (in template:24)" 错误
            val combinedPrompt = if (systemPrompt.isNotBlank()) {
                "$systemPrompt\n\n$prompt"
            } else {
                prompt
            }

            val conversation = litertEngine.createConversation()
            try {
                val responseBuilder = StringBuilder()
                conversation?.sendMessageAsync(combinedPrompt)?.collect { partialResponse ->
                    val partial = partialResponse.toString()
                    responseBuilder.append(partial)
                }
                val rawResponseParts = responseBuilder.toString().split("</think>")
                val rawResponse = if (rawResponseParts.size > 1) rawResponseParts[1] else rawResponseParts[0]
                fullResponse = rawResponse.trimEnd()
            } finally {
                conversation.close()
            }
            val duration = System.currentTimeMillis() - startTime
            Log.i(TAG, "[V2_LLM] Inference completed in ${duration}ms. Output len=${fullResponse.length}, preview:\"${fullResponse.take(50)}\"")
            fullResponse
        } catch (e: Exception) {
            Log.e(TAG, "[V2_LLM] Inference failed", e)
            if (e.message?.contains("OpenCL") == true || e.message?.contains("GPU") == true) {
                Log.w(TAG, "[V2_LLM] 检测到 GPU 相关错误，正在尝试重启引擎并回退到 CPU...")
                scope.launch {
                    engine = null
                    initializeEngine(attempt = 3) // 直接回退到 CPU
                }
            }
            ""
        }
    }

    fun release() {
        scope.cancel()
    }
}
