package com.zeerd.real_timetranscriptionapp

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.*
import com.google.ai.edge.litertlm.ConversationConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

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
    private val processingChannel = Channel<List<RawSpeakerTurn>>(Channel.CONFLATED)
    
    private val _regularizedBlocks = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val regularizedBlocks = _regularizedBlocks.asSharedFlow()

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
            
            val config = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = if (useGpu) Backend.GPU() else Backend.CPU(),
                cacheDir = cacheDir.absolutePath
            )
            
            val litertEngine = Engine(config)
            litertEngine.initialize()
            engine = litertEngine
            
            Log.i(TAG, "[V2_LLM] 引擎初始化成功! (Backend: ${if (useGpu) "GPU" else "CPU"})")
            processQueue()
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
            val result = regularizeInternal(turns)
            if (result.isNotBlank()) {
                _regularizedBlocks.emit(result)
            }
        }
    }

    fun enqueueRegularization(turns: List<RawSpeakerTurn>) {
        processingChannel.trySend(turns)
    }

    private suspend fun regularizeInternal(turns: List<RawSpeakerTurn>): String = withContext(Dispatchers.Default) {
        val litertEngine = engine ?: run {
            Log.w(TAG, "[V2_LLM] Engine not initialized, skipping regularization")
            return@withContext ""
        }
        
        val systemPrompt = """
你是一位专家级的转录文本编辑员。你的工作是将未经标点符号断句的原始语音转录文本，格式化为干净、易读的对话式文本。

编辑规则
1： 完整保留每一个说出的单词/字词。切勿进行概括、总结或省略任何事实细节。
2： 将属于同一位发言人（Speaker）的相邻原始文本行进行合并，并加上正确的标点符号，整理成通顺的段落。
3： 每当发言人发生切换，或出现重大的话题转变时，请换行并以 [发言人姓名]: 开头输出新的段落。
4： 根据上下文语境，纠正明显的同音字/语音识别错误（例如：将 "I'm hear" 纠正为 "I'm here"）。
5： 中文文本请使用简体中文输出。

输入格式
[发言人 X (时间)]: 原始文本
""".trimIndent()

        val inputPayload = turns.joinToString("\n") { 
            "[${it.speakerId}]: ${it.rawText}" 
        }

        val prompt = "原始文本:\n$inputPayload\n\n请格式化上述文本:"
        
        Log.i(TAG, "[V2_LLM] Starting inference for ${turns.size} turns. Input size: ${inputPayload.length} chars")
        
        try {
            var fullResponse = ""
            val startTime = System.currentTimeMillis()
            val config = ConversationConfig(
                systemInstruction = Contents.of(systemPrompt)
            )
            litertEngine.createConversation(config).use { conversation ->
                conversation.sendMessageAsync(prompt).collect { token ->
                    fullResponse += token
                }
            }
            val duration = System.currentTimeMillis() - startTime
            Log.i(TAG, "[V2_LLM] Inference completed in ${duration}ms. Output: \"${fullResponse.take(50)}...\"")
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
