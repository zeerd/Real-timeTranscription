package com.zeerd.real_timetranscriptionapp

import android.Manifest
import android.content.pm.PackageManager
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"
    private val transcriptions = mutableStateListOf<String>()
    private val isRecording = mutableStateOf(false)
    private val volumeLevel = mutableStateOf(0f)
    private val saveLocationDescription = mutableStateOf("")
    
    private val audioChannel = Channel<ByteArray>(100)
    private val transcriptionChannel = Channel<FloatArray>(Channel.CONFLATED)
    
    private var whisperWrapper: WhisperWrapper? = null
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
                                lifecycleScope.launch {
                                    fileManager.setUserSelectedUri(uri)
                                    saveLocationDescription.value = fileManager.getCurrentSaveLocation()
                                    Log.d(TAG, "User selected save file: $uri")
                                }
                            }
                        }

                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            floatingActionButton = {
                                Column(horizontalAlignment = Alignment.End) {
                                    FloatingActionButton(
                                        onClick = { navController.navigate("settings") },
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                                    ) {
                                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                    FloatingActionButton(onClick = {
                                        createDocumentLauncher.launch("transcription_${System.currentTimeMillis()}.txt")
                                    }) {
                                        Icon(Icons.Default.Save, contentDescription = "Set Save File")
                                    }
                                }
                            }
                        ) { innerPadding ->
                            TranscriptionScreen(
                                transcriptions = transcriptions,
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
                                navController.popBackStack()
                                // Restart pipeline if model changed?
                                // For now, we require a manual restart or handle it in startAudioPipeline
                                startAudioPipeline()
                            }
                        )
                    }
                }
            }
        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "RECORD_AUDIO already granted")
            startAudioPipeline()
        } else {
            Log.d(TAG, "Requesting RECORD_AUDIO permission")
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startAudioPipeline() {
        val modelId = modelManager.getSelectedModelId()
        val modelDir = modelManager.getModelDir(modelId)
        
        Log.d(TAG, "Starting audio pipeline with model: $modelId")
        
        lifecycleScope.launch {
            // Close previous wrapper if any
            whisperWrapper?.close()
            whisperWrapper = null
            
            // Move heavy initialization to IO dispatcher to avoid freezing the Main Thread
            val initialized = withContext(Dispatchers.IO) {
                try {
                    Log.d(TAG, "Initializing VAD on background thread...")
                    val vad = SileroVadWrapper(this@MainActivity)
                    
                    Log.d(TAG, "Initializing Whisper on background thread ($modelId)...")
                    
                    // Check if model exists in assets first (tiny) or in files
                    val finalModelDir = if (!modelDir.exists()) {
                        if (modelId == "whisper-tiny") {
                            // Fallback to legacy assets/whisper-tiny check (only for first run with tiny)
                            val legacyDir = File(filesDir, "whisper-tiny")
                            if (legacyDir.exists() && File(legacyDir, "tiny-encoder.int8.onnx").exists()) {
                                legacyDir
                            } else {
                                throw Exception("Model not downloaded. Go to Settings to download $modelId.")
                            }
                        } else {
                            throw Exception("Model not downloaded. Go to Settings to download $modelId.")
                        }
                    } else {
                        modelDir
                    }
                    
                    val whisper = WhisperWrapper(this@MainActivity, modelId, finalModelDir)
                    
                    whisperWrapper = whisper
                    vad to whisper
                } catch (t: Throwable) {
                    val msg = "Initialization failed: ${t.message}"
                    Log.e(TAG, "[FATAL_ERROR] $msg", t)
                    withContext(Dispatchers.Main) {
                        transcriptions.add(0, msg)
                    }
                    null
                }
            }

            if (initialized != null) {
                val (vadWrapper, whisper) = initialized
                Log.d(TAG, "Components initialized, starting processing...")

                val recorder = AudioRecorder(audioChannel)
                val pipeline = TranscriptionPipeline(audioChannel, vadWrapper, transcriptionChannel)

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
                    for (speech in transcriptionChannel) {
                        Log.d(TAG, "Received speech segment (${speech.size} samples), calling Whisper...")
                        
                        // Use a local copy to avoid UI updates inside withContext if possible
                        // But we want to show "Transcribing..." status
                        withContext(Dispatchers.Main) {
                            transcriptions.add(0, "Transcribing...")
                        }

                        val result = withContext(Dispatchers.Default) {
                            whisper.transcribe(speech)
                        }
                        
                        withContext(Dispatchers.Main) {
                            if (transcriptions.isNotEmpty() && transcriptions[0] == "Transcribing...") {
                                transcriptions.removeAt(0)
                            }
                            if (result.isNotBlank()) {
                                transcriptions.add(0, result)
                                Log.d(TAG, "[UI_MSG] Transcription added: $result")
                                
                                // Auto-save to file (IO bound)
                                launch(Dispatchers.IO) {
                                    fileManager.saveTranscription(result)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy, closing resources")
        whisperWrapper?.close()
    }
}

@Composable
fun TranscriptionScreen(
    transcriptions: List<String>,
    isRecording: Boolean,
    volumeLevel: Float,
    saveLocation: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Real-time Transcription (Local)",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
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
        
        Text(text = "Transcripts:", style = MaterialTheme.typography.titleMedium)
        
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(transcriptions) { text ->
                SelectionContainer {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Text(text = text, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
        
        if (transcriptions.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(text = "Speak to start capturing...", color = Color.Gray)
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
