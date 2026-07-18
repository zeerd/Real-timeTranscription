package com.zeerd.real_timetranscriptionapp

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 前台转写服务。
 *
 * 把原本跑在 MainActivity.lifecycleScope 里的整条管线（AudioRecorder → VAD → Whisper →
 * 说话人分离 → 语义分段 → LLM 润色）搬到这里，用 [startForeground] 提升为前台服务，
 * 这样即使 Activity 被销毁 / 应用切到后台，录音与转写仍持续运行。
 *
 * 与 UI 的通信全部走 [TranscriptionState] 单例（Flow），服务不持有任何 View 引用。
 */
class TranscriptionService : Service() {
    private val TAG = "TranscriptionService"

    private val app by lazy { application as TranscriptionApplication }
    private val modelManager by lazy { app.modelManager }
    private val fileManager by lazy { app.fileManager }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 与 MainActivity 中完全一致的通道与组件
    private val audioChannel = Channel<ByteArray>(100)
    private val transcriptionChannel = Channel<AudioTextPair>(Channel.UNLIMITED)
    private val semanticBuffer = SemanticBuffer()

    private var whisperWrapper: WhisperWrapper? = null
    private var vadWrapper: SileroVadWrapper? = null
    private var diarizationManager: SpeakerDiarizationManager? = null
    private var llmManager: LocalLlmManager? = null

    private var currentModelId: String? = null
    private var currentAudioSource: Int? = null
    private var isPipelineStarting = false

    private var pipelineJob: Job? = null
    private val observeJobs = mutableListOf<Job>()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        TranscriptionState.setServiceRunning(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action}")

        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Received STOP action, shutting down")
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // 首次启动：先恢复历史到 UI，再启动前台通知与管线
                startForegroundSafely()
                restoreHistoryToUi()
                checkAndStartPipeline()
            }
        }
        // 若被系统杀死，不要自动重启（避免无界面时静默录音）
        return START_NOT_STICKY
    }

    private fun startForegroundSafely() {
        try {
            val notification = buildNotification(isRecording = false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    TranscriptionApplication.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(TranscriptionApplication.NOTIFICATION_ID, notification)
            }
            Log.i(TAG, "startForeground done")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}", e)
        }
    }

    private fun buildNotification(isRecording: Boolean): Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, TranscriptionService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, TranscriptionApplication.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("实时转写进行中")
            .setContentText(if (isRecording) "正在录音并转写…" else "正在聆听…")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(isRecording: Boolean) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(TranscriptionApplication.NOTIFICATION_ID, buildNotification(isRecording))
        } catch (e: Exception) {
            Log.w(TAG, "updateNotification failed: ${e.message}")
        }
    }

    private fun restoreHistoryToUi() {
        serviceScope.launch {
            val raw = fileManager.getHistory()
            val formal = fileManager.getFormalHistory()
            TranscriptionState.restoreHistory(raw, formal)
        }
    }

    // ===== 以下逻辑与 MainActivity 中保持一致，但运行在服务作用域 =====

    private fun checkAndStartPipeline() {
        val selectedId = modelManager.getSelectedModelId()
        val selectedLlmId = modelManager.getSelectedLlmModelId()
        val selectedAudioSource = modelManager.getAudioSource()
        val isReady = modelManager.isModelReady(selectedId)

        Log.i(TAG, "Pipeline Sync Check: whisper=$selectedId, llm=$selectedLlmId, audioSource=$selectedAudioSource, runningModel=$currentModelId, starting=$isPipelineStarting, ready=$isReady")

        if (!isReady) {
            Log.w(TAG, "Pipeline not ready (no ASR model selected/ready), skipping start")
            return
        }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // 后台无法弹权限框：记录提示并停止，等用户回到前台授权后再启动
            Log.w(TAG, "RECORD_AUDIO permission not granted, cannot start in background")
            TranscriptionState.postMessage("缺少麦克风权限，请回到应用授权后重试")
            return
        }

        if (currentModelId != selectedId || currentAudioSource != selectedAudioSource) {
            if (isPipelineStarting) {
                Log.w(TAG, "Pipeline is already starting, skipping duplicate call")
                return
            }
            Log.i(TAG, "Change detected. Restarting pipeline...")
            startAudioPipeline()
        }
    }

    private fun startAudioPipeline() {
        val modelId = modelManager.getSelectedModelId()
        if (modelId.isEmpty() || !modelManager.isModelReady(modelId)) {
            Log.w(TAG, ">>> No ready ASR model selected (modelId='$modelId'), skipping pipeline start")
            isPipelineStarting = false
            return
        }
        val modelDir = modelManager.getModelDir(modelId)

        Log.i(TAG, ">>> RESTARTING AUDIO PIPELINE with model: $modelId")
        isPipelineStarting = true

        pipelineJob?.cancel()
        observeJobs.forEach { it.cancel() }
        observeJobs.clear()

        pipelineJob = serviceScope.launch {
            Log.d(TAG, "Closing old wrappers...")
            whisperWrapper?.close()
            whisperWrapper = null
            vadWrapper?.close()
            vadWrapper = null
            diarizationManager?.release()
            diarizationManager = null
            llmManager?.release()
            llmManager = null
            currentModelId = null
            currentAudioSource = null

            val initialized = withContext(Dispatchers.IO) {
                try {
                    if (!modelManager.isModelReady(modelId)) {
                        Log.w(TAG, "Model $modelId not ready, skipping pipeline start")
                        return@withContext null
                    }

                    Log.d(TAG, "Initializing Native components...")
                    val vad = SileroVadWrapper(this@TranscriptionService)
                    val whisper = WhisperWrapper(this@TranscriptionService, modelId, modelDir)
                    whisper.dumpDir = File(filesDir, "wav_dump")

                    val selectedSpeakerId = modelManager.getSelectedSpeakerModelId()
                    Log.i(TAG, "[V2_PIPELINE] selectedSpeakerId='$selectedSpeakerId'")
                    val speakerModelsRoot = modelManager.getModelDir("")
                    val candidateDirs = if (selectedSpeakerId.isNotEmpty()) {
                        listOf(modelManager.getModelDir(selectedSpeakerId))
                    } else {
                        speakerModelsRoot.listFiles()
                            ?.filter { it.isDirectory && (it.name == "speaker-ecapa" || it.name.startsWith("custom-speaker-")) }
                            ?: emptyList()
                    }
                    Log.i(TAG, "[V2_PIPELINE] speaker candidate dirs: ${candidateDirs.map { it.absolutePath }}")
                    val speakerModel = candidateDirs
                        .mapNotNull { dir -> dir.listFiles()?.find { f -> f.name.endsWith(".onnx") } }
                        .firstOrNull { it.exists() }
                    if (speakerModel != null) {
                        Log.i(TAG, "[V2_PIPELINE] Speaker model found: ${speakerModel.absolutePath}, enabling Diarization")
                        diarizationManager = SpeakerDiarizationManager(this@TranscriptionService, speakerModel.absolutePath)
                    } else {
                        Log.w(TAG, "[V2_PIPELINE] Speaker model NOT found. Diarization disabled.")
                    }

                    val selectedLlmId = modelManager.getSelectedLlmModelId()
                    val llmModelDir = modelManager.getModelDir(selectedLlmId)
                    if (modelManager.isLlmPolishingEnabled() && modelManager.isModelReady(selectedLlmId)) {
                        Log.i(TAG, "[V2_PIPELINE] LLM model $selectedLlmId is ready, enabling Regularization")
                        llmManager = LocalLlmManager(this@TranscriptionService, llmModelDir)
                    } else {
                        Log.w(TAG, "[V2_PIPELINE] LLM Regularization disabled (enabled=${modelManager.isLlmPolishingEnabled()}, modelReady=${modelManager.isModelReady(selectedLlmId)}).")
                    }

                    vadWrapper = vad
                    whisperWrapper = whisper
                    currentModelId = modelId
                    currentAudioSource = modelManager.getAudioSource()

                    vad to whisper
                } catch (t: Throwable) {
                    val msg = "Initialization failed: ${t.message}"
                    Log.e(TAG, "[FATAL_ERROR] $msg", t)
                    TranscriptionState.postMessage(msg)
                    null
                }
            }

            // LLM 正式稿收集（replay=1，UI 重连也能拿到最近结果）
            llmManager?.let { mgr ->
                observeJobs += mgr.regularizedBlocks.onEach { text ->
                    if (text.isNotBlank()) {
                        TranscriptionState.addRegularized(text)
                        Log.i(TAG, "[V2_UI] New regularized block added")
                        launch(Dispatchers.IO) { fileManager.saveRegularized(text) }
                    }
                }.launchIn(serviceScope)
            }

            if (initialized != null) {
                val (vad, whisper) = initialized
                Log.i(TAG, "Pipeline started successfully with $modelId")
                isPipelineStarting = false

                val audioSource = modelManager.getAudioSource()
                val recorder = AudioRecorder(audioChannel, audioSource)
                val speakerChangeDetector = diarizationManager?.let { dm ->
                    SpeakerChangeDetector(extractEmbedding = { audio -> dm.extractEmbeddingSafe(audio) })
                }
                val pipeline = TranscriptionPipeline(audioChannel, vad, whisper, transcriptionChannel, speakerChangeDetector)

                observeJobs += pipeline.vadState.onEach { state ->
                    val recording = state == VadState.RECORDING
                    TranscriptionState.setRecording(recording)
                    updateNotification(recording)
                }.launchIn(serviceScope)

                observeJobs += pipeline.volume.onEach { level ->
                    TranscriptionState.setVolume(level)
                }.launchIn(serviceScope)

                launch { recorder.startRecording() }
                launch { pipeline.startProcessing() }

                launch {
                    Log.d(TAG, "Launching Transcription results loop")
                    for (pair in transcriptionChannel) {
                        Log.d(TAG, "Received AudioTextPair (${pair.rawAudio.size} samples)")
                        val result = pair.transcribedText
                        if (result.isNotBlank()) {
                            val speakerId = withContext(Dispatchers.Default) {
                                val id = diarizationManager?.identifySpeaker(pair.rawAudio) ?: "Speaker"
                                Log.i(TAG, "[V2_PIPELINE] Segment diarization completed. Result: $id")
                                id
                            }
                            val displayResult = "[$speakerId]: $result"
                            TranscriptionState.addTranscription(displayResult)
                            Log.d(TAG, "[UI_MSG] Transcription added: $displayResult")

                            semanticBuffer.addTurn(RawSpeakerTurn(speakerId, System.currentTimeMillis(), pair.segmentStartTimestampMs, result))

                            launch(Dispatchers.IO) { fileManager.saveTranscription(displayResult) }
                        }
                    }
                }
            } else {
                Log.e(TAG, "Pipeline initialization returned null, resetting start flag")
                isPipelineStarting = false
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy, closing resources")
        pipelineJob?.cancel()
        observeJobs.forEach { it.cancel() }
        observeJobs.clear()
        serviceScope.cancel()
        semanticBuffer.release()
        whisperWrapper?.close()
        vadWrapper?.close()
        diarizationManager?.release()
        llmManager?.release()
        TranscriptionState.setServiceRunning(false)
        TranscriptionState.setRecording(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.zeerd.real_timetranscriptionapp.action.STOP"

        /** 启动前台转写服务（幂等：若已在运行则仅触发一次管线同步检查）。 */
        fun start(context: Context) {
            val intent = Intent(context, TranscriptionService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TranscriptionService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
