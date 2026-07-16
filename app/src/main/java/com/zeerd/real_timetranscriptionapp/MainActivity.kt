package com.zeerd.real_timetranscriptionapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.zeerd.real_timetranscriptionapp.ui.theme.RealtimeTranscriptionAppTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"
    private val transcriptions = mutableStateListOf<String>()
    private val isRecording = mutableStateOf(false)
    private val volumeLevel = mutableStateOf(0f)
    
    private val audioChannel = Channel<ByteArray>(100)
    private val transcriptionChannel = Channel<FloatArray>(Channel.CONFLATED)
    
    private var whisperWrapper: WhisperWrapper? = null

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
        enableEdgeToEdge()
        setContent {
            RealtimeTranscriptionAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TranscriptionScreen(
                        transcriptions = transcriptions,
                        isRecording = isRecording.value,
                        volumeLevel = volumeLevel.value,
                        modifier = Modifier.padding(innerPadding)
                    )
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
        Log.d(TAG, "Starting audio pipeline...")
        val vadWrapper = try {
            SileroVadWrapper(this)
        } catch (e: Exception) {
            val msg = "Error loading VAD model: ${e.message}"
            Log.e(TAG, "[UI_MSG] $msg", e)
            transcriptions.add(0, msg)
            return
        }

        whisperWrapper = try {
            WhisperWrapper(this)
        } catch (e: Exception) {
            val msg = "Whisper model error: ${e.message}. Please follow README instructions."
            Log.e(TAG, "[UI_MSG] $msg", e)
            transcriptions.add(0, msg)
            null
        }

        val recorder = AudioRecorder(audioChannel)
        val pipeline = TranscriptionPipeline(audioChannel, vadWrapper, transcriptionChannel)

        lifecycleScope.launch {
            pipeline.vadState.collect { state ->
                Log.d(TAG, "VAD State update: $state")
                isRecording.value = (state == VadState.RECORDING)
            }
        }

        lifecycleScope.launch {
            Log.d(TAG, "Launching AudioRecorder coroutine")
            recorder.startRecording()
        }

        lifecycleScope.launch {
            Log.d(TAG, "Launching TranscriptionPipeline coroutine")
            pipeline.startProcessing()
        }

        lifecycleScope.launch {
            Log.d(TAG, "Launching Transcription results loop")
            for (speech in transcriptionChannel) {
                val wrapper = whisperWrapper
                if (wrapper != null) {
                    Log.d(TAG, "Received speech segment (${speech.size} samples), calling Whisper...")
                    transcriptions.add(0, "Transcribing...")
                    val result = wrapper.transcribe(speech)
                    transcriptions.removeAt(0)
                    transcriptions.add(0, result)
                    Log.d(TAG, "[UI_MSG] Transcription added: $result")
                } else {
                    val msg = "Captured segment: ${"%.1f".format(speech.size / 16000f)}s (Model missing)"
                    Log.w(TAG, "[UI_MSG] $msg")
                    transcriptions.add(0, msg)
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
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Real-time Transcription (Local)",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
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
