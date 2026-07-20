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
    // 实时转写（ASR 结果，已按同说话人临近内容合并，含说话人标签）。新结果插到队首，UI 倒序展示。
    private val _transcriptions = MutableStateFlow<List<String>>(emptyList())
    val transcriptions = _transcriptions.asStateFlow()

    // 是否正在录音（VAD 处于 RECORDING）。
    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    // 用户是否处于「采集会话」中（点击开始 → 点击停止之间）。
    // 与 isRecording 的区别：isRecording 表示 VAD 检测到语音的瞬时状态，
    // isCapturing 表示整段采集会话的开关，用于驱动开始/停止按钮的 UI。
    private val _isCapturing = MutableStateFlow(false)
    val isCapturing = _isCapturing.asStateFlow()

    // 当前音量电平（0~1），用于 UI 指示。
    private val _volumeLevel = MutableStateFlow(0f)
    val volumeLevel = _volumeLevel.asStateFlow()

    // 服务是否正在运行（前台服务存活）。
    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning = _serviceRunning.asStateFlow()

    // 服务内部错误 / 提示信息（如初始化失败），UI 可选择性展示。
    private val _messages = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 32)
    val messages = _messages.asSharedFlow()

    // 停止采集后，服务把本次会话的完整转写文本推到这里，UI 据此弹出「是否总结」对话框。
    private val _pendingSummary = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val pendingSummary = _pendingSummary.asSharedFlow()

    // 是否正在执行 LLM 总结（用于 UI 显示进度）。
    private val _summarizing = MutableStateFlow(false)
    val summarizing = _summarizing.asStateFlow()

    // 最近一次生成的 LLM 总结文本（用于 UI 即时展示）。
    private val _summaryResult = MutableStateFlow<String?>(null)
    val summaryResult = _summaryResult.asStateFlow()

    fun addTranscription(text: String) {
        // 新结果插到队首
        _transcriptions.value = listOf(text) + _transcriptions.value
    }

    fun setRecording(active: Boolean) {
        _isRecording.value = active
    }

    fun setCapturing(active: Boolean) {
        _isCapturing.value = active
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

    fun setPendingSummary(text: String) {
        _pendingSummary.tryEmit(text)
    }

    fun setSummarizing(active: Boolean) {
        _summarizing.value = active
    }

    fun setSummaryResult(text: String?) {
        _summaryResult.value = text
    }

    // 清空历史（重置按钮调用，同时服务也会清空文件）。
    fun clearUiHistory() {
        _transcriptions.value = emptyList()
    }

    // 服务启动时恢复历史，避免 UI 空白。
    fun restoreHistory(raw: List<String>) {
        _transcriptions.value = raw
    }
}
