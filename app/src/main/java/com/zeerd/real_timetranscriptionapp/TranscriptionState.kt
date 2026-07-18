package com.zeerd.real_timetranscriptionapp

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 跨 Activity / Service 共享的转写状态。
 *
 * 之前管线跑在 [MainActivity.lifecycleScope] 里，切后台时 Activity 被销毁、
 * 协程被取消，录音随之停止。现在管线迁移到前台 [TranscriptionService]，
 * 本单例作为 UI（Activity）与后台服务之间唯一的状态桥梁：
 * - 服务把实时转写结果、正式稿、录音状态、音量推送到这里；
 * - Activity 只负责订阅这些 Flow 并渲染，不再持有任何 Native 资源或协程管线。
 *
 * 使用单例而非依赖注入，是因为 Service 与 Activity 生命周期独立，
 * 且本应用只有一个转写会话，单例足够且最简单。
 */
object TranscriptionState {
    // 实时原始转写（ASR 结果，含说话人标签）。新结果插到队首，UI 倒序展示。
    private val _transcriptions = MutableStateFlow<List<String>>(emptyList())
    val transcriptions = _transcriptions.asStateFlow()

    // LLM 润色后的正式稿。
    private val _regularizedTranscriptions = MutableStateFlow<List<String>>(emptyList())
    val regularizedTranscriptions = _regularizedTranscriptions.asStateFlow()

    // 是否正在录音（VAD 处于 RECORDING）。
    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    // 当前音量电平（0~1），用于 UI 指示。
    private val _volumeLevel = MutableStateFlow(0f)
    val volumeLevel = _volumeLevel.asStateFlow()

    // 服务是否正在运行（前台服务存活）。
    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning = _serviceRunning.asStateFlow()

    // 服务内部错误 / 提示信息（如初始化失败），UI 可选择性展示。
    private val _messages = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 32)
    val messages = _messages.asSharedFlow()

    fun addTranscription(text: String) {
        // 新结果插到队首
        _transcriptions.value = listOf(text) + _transcriptions.value
    }

    fun addRegularized(text: String) {
        _regularizedTranscriptions.value = listOf(text) + _regularizedTranscriptions.value
    }

    fun setRecording(active: Boolean) {
        _isRecording.value = active
    }

    fun setVolume(level: Float) {
        _volumeLevel.value = level
    }

    fun setServiceRunning(running: Boolean) {
        _serviceRunning.value = running
    }

    fun postMessage(msg: String) {
        _messages.tryEmit(msg)
    }

    // 清空历史（重置按钮调用，同时服务也会清空文件）。
    fun clearUiHistory() {
        _transcriptions.value = emptyList()
        _regularizedTranscriptions.value = emptyList()
    }

    // 服务启动时恢复历史，避免 UI 空白。
    fun restoreHistory(raw: List<String>, formal: List<String>) {
        _transcriptions.value = raw
        _regularizedTranscriptions.value = formal
    }
}
