package com.zeerd.real_timetranscriptionapp

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.zeerd.real_timetranscriptionapp.ui.theme.RealtimeTranscriptionAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"
    private val transcriptions = mutableStateListOf<String>()
    private val regularizedTranscriptions = mutableStateListOf<String>()
    private val isRecording = mutableStateOf(false)
    private val volumeLevel = mutableStateOf(0f)
    private val saveLocationDescription = mutableStateOf("")
    
    private val audioChannel = Channel<ByteArray>(100)
    private val transcriptionChannel = Channel<AudioTextPair>(Channel.CONFLATED)
    
    private var whisperWrapper: WhisperWrapper? = null
    private var vadWrapper: SileroVadWrapper? = null
    private var diarizationManager: SpeakerDiarizationManager? = null
    private var llmManager: LocalLlmManager? = null
    private val semanticBuffer = SemanticBuffer()
    private var currentModelId: String? = null
    private var pipelineJob: Job? = null
    private lateinit var fileManager: TranscriptionFileManager
    private lateinit var modelManager: ModelManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        Log.d(TAG, "Permission RECORD_AUDIO granted: $isGranted")
        if (isGranted) {
            startAudioPipeline()
        } else {
            val msg = "Permission denied. App cannot record audio."
            Log.e(TAG, "[UI_MSG] $msg")
            transcriptions.add(0, msg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        
        fileManager = TranscriptionFileManager(this)
        modelManager = ModelManager(this)
        saveLocationDescription.value = fileManager.getCurrentSaveLocation()
        
        enableEdgeToEdge()
        setContent {
            RealtimeTranscriptionAppTheme {
                val navController = rememberNavController()
                
                NavHost(navController = navController, startDestination = "transcription") {
                    composable("transcription") {
                        val createDocumentLauncher = rememberLauncherForActivityResult(
                            contract = ActivityResultContracts.CreateDocument("text/plain")
                        ) { uri ->
                            if (uri != null) {
                                Log.d(TAG, "[USER_ACTION] User selected a save file via picker: $uri")
                                lifecycleScope.launch {
                                    fileManager.setUserSelectedUri(uri)
                                    saveLocationDescription.value = fileManager.getCurrentSaveLocation()
                                    Log.d(TAG, "User selected save file: $uri")
                                }
                            } else {
                                Log.d(TAG, "[USER_ACTION] User cancelled save-file picker (no URI returned)")

                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            floatingActionButton = {
                                Column(horizontalAlignment = Alignment.End) {
                                    FloatingActionButton(
                                        onClick = {
                                            Log.d(TAG, "[USER_ACTION] Navigating to Settings screen")
                                            navController.navigate("settings")
                                        },
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                                    ) {
                                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                    FloatingActionButton(onClick = {
                                        Log.d(TAG, "[USER_ACTION] Opening save-file picker")
                                        createDocumentLauncher.launch("transcription_${System.currentTimeMillis()}.txt")
                                    }) {
                                        Icon(Icons.Default.Save, contentDescription = "Set Save File")
                                    }
                                }
                            }
                        ) { innerPadding ->
                            TranscriptionScreen(
                                transcriptions = transcriptions,
                                regularizedTranscriptions = regularizedTranscriptions,
                                isRecording = isRecording.value,
                                volumeLevel = volumeLevel.value,
                                saveLocation = saveLocationDescription.value,
                                modifier = Modifier.padding(innerPadding)
                            )
                        }
                    }
                    composable("settings") {
                        SettingsScreen(
                            modelManager = modelManager,
                            onBack = { 
                                Log.d(TAG, "[USER_ACTION] Returning from Settings screen")
                                navController.popBackStack()
                                // 移除这里的 startAudioPipeline()，由下方的 collect 统一处理
                            }
                        )
                    }
                }
            }
        }

        loadHistory()

        // 统一的状态观察器：这是启动/重启录音管道的唯一入口
        lifecycleScope.launch {
            launch {
                modelManager.settingsChanged.collect {
                    Log.i(TAG, "Settings change signal received (#$it)")
                    checkAndStartPipeline()
                }
            }
            launch {
                modelManager.modelStatuses.collect {
                    checkAndStartPipeline()
                }
            }
            launch {
                semanticBuffer.bufferFullFlow.collect { turns ->
                    Log.i(TAG, "[V2_PIPELINE] Buffer full (${turns.size} turns), sending to LLM")
                    llmManager?.enqueueRegularization(turns)
                }
            }
        }
    }

    private var currentAudioSource: Int? = null
    private var isPipelineStarting = false

    private fun getAudioSourceName(source: Int?): String {
        return when (source) {
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            null -> "NONE"
            else -> "UNKNOWN($source)"
        }
    }

    private fun checkAndStartPipeline() {
        val selectedId = modelManager.getSelectedModelId()
        val selectedLlmId = modelManager.getSelectedLlmModelId()
        val selectedAudioSource = modelManager.getAudioSource()
        val isReady = modelManager.isModelReady(selectedId)
        
        // 使用 Info 级别日志，并显示易读的字符串名称
        Log.i(TAG, "Pipeline Sync Check: whisper=$selectedId, llm=$selectedLlmId, audioSource=${getAudioSourceName(selectedAudioSource)}, runningModel=$currentModelId, starting=$isPipelineStarting")

        if (isReady) {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                // 如果发现音源或者模型变了，就重启 (这里简单比较 whisper 模型，LLM 可以在运行中切换或重启)
                if (currentModelId != selectedId || currentAudioSource != selectedAudioSource) {
                    if (isPipelineStarting) {
                        Log.w(TAG, "Pipeline is already starting, skipping duplicate call")
                        return
                    }
                    Log.i(TAG, "Change detected. Restarting pipeline...")
                    startAudioPipeline()
                }
            }
        }
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            val history = fileManager.getHistory()
            if (history.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    transcriptions.clear()
                    transcriptions.addAll(history)
                }
            }
        }
    }

    private fun startAudioPipeline() {
        val modelId = modelManager.getSelectedModelId()
        val modelDir = modelManager.getModelDir(modelId)
        
        Log.i(TAG, ">>> RESTARTING AUDIO PIPELINE with model: $modelId")
        
        // 立即标记正在启动，防止 checkAndStartPipeline 重复调用
        isPipelineStarting = true
        
        pipelineJob?.cancel()
        pipelineJob = lifecycleScope.launch {
            // 系统性修复：严格管理 Native 资源释放
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
                    val vad = SileroVadWrapper(this@MainActivity)
                    val whisper = WhisperWrapper(this@MainActivity, modelId, modelDir)
                    
                    // Initialize Diarization and LLM if their models are ready
                    // 使用用户在设置中选中的说话人模型（未选中则回退到任意就绪的说话人模型）
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
                        diarizationManager = SpeakerDiarizationManager(this@MainActivity, speakerModel.absolutePath)
                    } else {
                        Log.w(TAG, "[V2_PIPELINE] Speaker model NOT found. Diarization disabled.")
                    }
                    
                    val selectedLlmId = modelManager.getSelectedLlmModelId()
                    val llmModelDir = modelManager.getModelDir(selectedLlmId)
                    if (modelManager.isModelReady(selectedLlmId)) {
                        Log.i(TAG, "[V2_PIPELINE] LLM model $selectedLlmId is ready, enabling Regularization")
                        val manager = LocalLlmManager(this@MainActivity, llmModelDir)
                        llmManager = manager
                    } else {
                        Log.w(TAG, "[V2_PIPELINE] LLM model $selectedLlmId is NOT ready. Regularization disabled.")
                    }

                    // 赋值给成员变量以便管理
                    vadWrapper = vad
                    whisperWrapper = whisper
                    currentModelId = modelId
                    currentAudioSource = modelManager.getAudioSource()
                    
                    vad to whisper
                } catch (t: Throwable) {
                    val msg = "Initialization failed: ${t.message}"
                    Log.e(TAG, "[FATAL_ERROR] $msg", t)
                    withContext(Dispatchers.Main) { transcriptions.add(0, msg) }
                    null
                }
            }

            // 在 withContext 外部启动 LLM regularizedBlocks 的收集，
            // 避免 withContext 被永不结束的 collect 阻塞
            llmManager?.let { mgr ->
                launch(Dispatchers.Main) {
                    mgr.regularizedBlocks.collect { text ->
                        if (text.isNotBlank()) {
                            regularizedTranscriptions.add(0, text)
                            Log.i(TAG, "[V2_UI] New regularized block added to UI")
                        }
                    }
                }
            }

            if (initialized != null) {
                val (vad, whisper) = initialized
                Log.i(TAG, "Pipeline started successfully with $modelId")
                // 初始化成功，重置启动标记
                isPipelineStarting = false

                val audioSource = modelManager.getAudioSource()
                val recorder = AudioRecorder(audioChannel, audioSource)
                val pipeline = TranscriptionPipeline(audioChannel, vad, whisper, transcriptionChannel)

                launch {
                    pipeline.vadState.collect { state ->
                        Log.d(TAG, "VAD State update: $state")
                        isRecording.value = (state == VadState.RECORDING)
                    }
                }

                launch {
                    Log.d(TAG, "Launching AudioRecorder coroutine")
                    recorder.startRecording()
                }

                launch {
                    Log.d(TAG, "Launching TranscriptionPipeline coroutine")
                    pipeline.startProcessing()
                }

                launch {
                    Log.d(TAG, "Launching Transcription results loop")
                    for (pair in transcriptionChannel) {
                        Log.d(TAG, "Received AudioTextPair (${pair.rawAudio.size} samples)")
                        
                        val result = pair.transcribedText
                        
                        withContext(Dispatchers.Main) {
                            if (result.isNotBlank()) {
                                val speakerId = withContext(Dispatchers.Default) {
                                    val id = diarizationManager?.identifySpeaker(pair.rawAudio) ?: "Speaker"
                                    Log.i(TAG, "[V2_PIPELINE] Segment diarization completed. Result: $id")
                                    id
                                }
                                
                                val displayResult = "[$speakerId]: $result"
                                transcriptions.add(0, displayResult)
                                Log.d(TAG, "[UI_MSG] Transcription added: $displayResult")
                                
                                // Add to semantic buffer for LLM regularization
                                semanticBuffer.addTurn(RawSpeakerTurn(speakerId, System.currentTimeMillis(), result))

                                // Auto-save to file (IO bound)
                                launch(Dispatchers.IO) {
                                    fileManager.saveTranscription(displayResult)
                                }
                            }
                        }
                    }
                }
            } else {
                // 初始化失败，重置启动标记以便后续重试
                Log.e(TAG, "Pipeline initialization returned null, resetting start flag")
                isPipelineStarting = false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy, closing resources")
        whisperWrapper?.close()
        diarizationManager?.release()
        llmManager?.release()
    }
}

@Composable
fun TranscriptionScreen(
    transcriptions: List<String>,
    regularizedTranscriptions: List<String>,
    isRecording: Boolean,
    volumeLevel: Float,
    saveLocation: String,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(1) }
    val tabs = listOf("实时流 (Raw)", "正式稿 (Formal)")

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Real-time Transcription",
                style = MaterialTheme.typography.headlineSmall
            )
        }
        
        SecondaryTabRow(selectedTabIndex = selectedTab, modifier = Modifier.padding(vertical = 8.dp)) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = {
                        Log.d(TAG, "[USER_ACTION] Switched tab to '$title' (index=$index)")
                        selectedTab = index
                    },
                    text = { Text(title) }
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Saving to:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = saveLocation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        
        StatusCard(isRecording, volumeLevel)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        val currentList = if (selectedTab == 0) transcriptions else regularizedTranscriptions
        
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(currentList) { text ->
                SelectionContainer {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedTab == 1) MaterialTheme.colorScheme.primaryContainer 
                                             else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(text = text, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
        
        if (currentList.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = if (selectedTab == 0) "Speak to start capturing..." else "Waiting for LLM refinement...",
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
fun StatusCard(isRecording: Boolean, volumeLevel: Float) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(if (isRecording) Color.Red else Color.Gray, RoundedCornerShape(50))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = if (isRecording) "Recording Speech..." else "Listening for Speech...")
        }
    }
}
