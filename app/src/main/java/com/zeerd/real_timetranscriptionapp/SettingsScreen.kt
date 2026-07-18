package com.zeerd.real_timetranscriptionapp

import android.media.MediaRecorder
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import android.util.Log

data class AudioSourceOption(val name: String, val value: Int, val description: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modelManager: ModelManager,
    onBack: () -> Unit
) {
    val modelStatuses by modelManager.modelStatuses.collectAsState()
    val customModels by modelManager.customModels.collectAsState()
    
    val allModels = remember(customModels) { modelManager.availableModels }

    val selectedModelId = remember { mutableStateOf(modelManager.getSelectedModelId()) }
    val selectedLlmModelId = remember { mutableStateOf(modelManager.getSelectedLlmModelId()) }
    val selectedSpeakerModelId = remember { mutableStateOf(modelManager.getSelectedSpeakerModelId()) }
    val selectedAudioSource = remember { mutableIntStateOf(modelManager.getAudioSource()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val audioSources = listOf(
        AudioSourceOption("VOICE_RECOGNITION", MediaRecorder.AudioSource.VOICE_RECOGNITION, "System optimized for voice. Usually includes aggressive noise cancellation."),
        AudioSourceOption("CAMCORDER", MediaRecorder.AudioSource.CAMCORDER, "Uses secondary/top mic if available. Better for ambient/meeting room audio."),
        AudioSourceOption("MIC", MediaRecorder.AudioSource.MIC, "Standard primary microphone input."),
        AudioSourceOption("UNPROCESSED", 9 /* MediaRecorder.AudioSource.UNPROCESSED */, "Raw audio with minimal system processing. (API 24+)")
    )

    var showImportDialog by remember { mutableStateOf(false) }
    var targetModelIdForImport by remember { mutableStateOf("") }
    
    // Delete Confirmation Dialog
    var modelToDelete by remember { mutableStateOf<ModelInfo?>(null) }

    if (modelToDelete != null) {
        AlertDialog(
            onDismissRequest = { modelToDelete = null },
            title = { Text("Delete Model?") },
            text = { Text("This will remove the downloaded files for ${modelToDelete?.name}. You will need to download it again to use it.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        modelToDelete?.let {
                            Log.i("SettingsScreen", "[USER_ACTION] Confirmed delete model: ${it.id}")
                            modelManager.deleteModel(it.id)
                        }
                        modelToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = {
                    Log.d("SettingsScreen", "[USER_ACTION] Cancelled delete dialog for: ${modelToDelete?.id}")
                    modelToDelete = null
                }) { Text("Cancel") }
            }
        )
    }

    val llmPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            Log.i("SettingsScreen", "[USER_ACTION] LiteRT model picker returned uri=$uri")
            scope.launch {
                try {
                    val cursor = context.contentResolver.query(uri, null, null, null, null)
                    val fileName = cursor?.use {
                        if (it.moveToFirst()) {
                            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIndex != -1) it.getString(nameIndex) else "imported_model.tflite"
                        } else "imported_model.tflite"
                    } ?: "imported_model.tflite"
                    
                    Toast.makeText(context, "Importing $fileName...", Toast.LENGTH_SHORT).show()
                    modelManager.importLiteRtModel(uri, fileName)
                    Toast.makeText(context, "Import successful!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val whisperArchivePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null && targetModelIdForImport.isNotEmpty()) {
            Log.i("SettingsScreen", "[USER_ACTION] Whisper archive picker returned uri=$uri for model=$targetModelIdForImport")
            scope.launch {
                try {
                    Toast.makeText(context, "Importing archive...", Toast.LENGTH_SHORT).show()
                    modelManager.importFromArchive(targetModelIdForImport, uri)
                    Toast.makeText(context, "Import successful!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val speakerOnnxPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        Log.i("SettingsScreen", "[SPEAKER_IMPORT] picker returned uri=$uri")
        if (uri != null) {
            scope.launch {
                try {
                    val cursor = context.contentResolver.query(uri, null, null, null, null)
                    val fileName = cursor?.use {
                        if (it.moveToFirst()) {
                            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIndex != -1) it.getString(nameIndex) else "speaker_model.onnx"
                        } else "speaker_model.onnx"
                    } ?: "speaker_model.onnx"
                    Log.i("SettingsScreen", "[SPEAKER_IMPORT] fileName=$fileName")

                    Toast.makeText(context, "Importing $fileName...", Toast.LENGTH_SHORT).show()
                    modelManager.importSingleFileModel(uri, fileName)
                    Toast.makeText(context, "Import successful!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e("SettingsScreen", "[SPEAKER_IMPORT] failed", e)
                    Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Select Whisper Specification") },
            text = { Text("Which Whisper model does this archive contain?") },
            confirmButton = {
                Column {
                    allModels.filter { it.id.startsWith("whisper") }.forEach { model ->
                        TextButton(
                            onClick = {
                                Log.i("SettingsScreen", "[USER_ACTION] Import dialog: selected spec '${model.id}' for archive import")
                                targetModelIdForImport = model.id
                                showImportDialog = false
                                whisperArchivePickerLauncher.launch(arrayOf("application/x-bzip2", "application/octet-stream", "*/*"))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(model.name)
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    Log.d("SettingsScreen", "[USER_ACTION] Cancelled import dialog")
                    showImportDialog = false
                }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model Settings") },
                navigationIcon = {
                    IconButton(onClick = {
                        Log.d("SettingsScreen", "[USER_ACTION] Back button pressed")
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "Audio Settings",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "Choose the recording source based on your environment.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
            }

            items(audioSources) { option ->
                val isSelected = selectedAudioSource.intValue == option.value
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        Log.i("SettingsScreen", "[USER_ACTION] Audio source selected: ${option.name} (value=${option.value})")
                        modelManager.setAudioSource(option.value)
                        selectedAudioSource.intValue = option.value
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer 
                                         else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = option.name, style = MaterialTheme.typography.titleMedium)
                            Text(text = option.description, style = MaterialTheme.typography.bodySmall)
                        }
                        RadioButton(selected = isSelected, onClick = {
                            Log.i("SettingsScreen", "[USER_ACTION] Audio source radio selected: ${option.name} (value=${option.value})")
                            modelManager.setAudioSource(option.value)
                            selectedAudioSource.intValue = option.value
                        })
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "VAD Model",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "Voice Activity Detection is required to segment audio.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
            }

            items(allModels.filter { it.id == "vad-silero" }) { model ->
                val status = modelStatuses[model.id] ?: ModelStatus.NOT_DOWNLOADED
                ModelItem(
                    model = model,
                    status = status,
                    isSelected = false,
                    onSelect = {},
                    onDownload = {
                        Log.i("SettingsScreen", "[USER_ACTION] Download requested for VAD model: ${model.id}")
                        modelManager.downloadModel(model)
                    },
                    onDelete = { modelToDelete = it },
                    isVad = true
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Whisper ASR Models",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "Larger models are more accurate but slower and use more memory.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = {
                        Log.d("SettingsScreen", "[USER_ACTION] Opening Whisper archive import dialog")
                        showImportDialog = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Import Whisper Archive (.tar.bz2)")
                }
            }

            items(allModels.filter { it.id.startsWith("whisper") }) { model ->
                val status = modelStatuses[model.id] ?: ModelStatus.NOT_DOWNLOADED
                val isSelected = selectedModelId.value == model.id

                ModelItem(
                    model = model,
                    status = status,
                    isSelected = isSelected,
                    onSelect = {
                        if (status == ModelStatus.READY) {
                            Log.i("SettingsScreen", "[USER_ACTION] Whisper model selected: ${model.id}")
                            modelManager.setSelectedModelId(model.id)
                            selectedModelId.value = model.id
                        } else {
                            Log.w("SettingsScreen", "[USER_ACTION] Whisper model '${model.id}' not READY, selection ignored")
                        }
                    },
                    onDownload = {
                        Log.i("SettingsScreen", "[USER_ACTION] Download requested for Whisper model: ${model.id}")
                        modelManager.downloadModel(model)
                    },
                    onDelete = { modelToDelete = it }
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Speaker Diarization",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "Identifies different speakers by voiceprint. Enables \"Speaker 1\", \"Speaker 2\" labels in transcripts.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        Log.d("SettingsScreen", "[USER_ACTION] Opening Speaker model (.onnx) import picker")
                        speakerOnnxPickerLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Import Speaker Model (.onnx)")
                }
            }

            items(allModels.filter { it.id == "speaker-ecapa" || it.id.startsWith("custom-speaker-") }) { model ->
                val status = modelStatuses[model.id] ?: ModelStatus.NOT_DOWNLOADED
                val isSelected = selectedSpeakerModelId.value == model.id

                ModelItem(
                    model = model,
                    status = status,
                    isSelected = isSelected,
                    onSelect = {
                        Log.i("SettingsScreen", "[SPEAKER_SELECT] clicked model=${model.id} status=$status")
                        if (status == ModelStatus.READY) {
                            modelManager.setSelectedSpeakerModelId(model.id)
                            selectedSpeakerModelId.value = model.id
                            Log.i("SettingsScreen", "[SPEAKER_SELECT] selected -> ${model.id}")
                        } else {
                            Log.w("SettingsScreen", "[SPEAKER_SELECT] ignored, model not READY")
                        }
                    },
                    onDownload = { modelManager.downloadModel(model) },
                    onDelete = { modelToDelete = it }
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Local LLM (LiteRT)",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "Used for semantic paragraphing and formatting. You can download Gemma 2B or import your own .tflite model.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = {
                        Log.d("SettingsScreen", "[USER_ACTION] Opening LiteRT (.tflite) import picker")
                        llmPickerLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Import LiteRT Model (.tflite)")
                }
            }

            items(allModels.filter { it.id.startsWith("llm") || it.id.startsWith("custom-llm") }) { model ->
                val status = modelStatuses[model.id] ?: ModelStatus.NOT_DOWNLOADED
                val isSelected = selectedLlmModelId.value == model.id

                ModelItem(
                    model = model,
                    status = status,
                    isSelected = isSelected,
                    onSelect = {
                        if (status == ModelStatus.READY) {
                            Log.i("SettingsScreen", "[USER_ACTION] LLM model selected: ${model.id}")
                            modelManager.setSelectedLlmModelId(model.id)
                            selectedLlmModelId.value = model.id
                        } else {
                            Log.w("SettingsScreen", "[USER_ACTION] LLM model '${model.id}' not READY, selection ignored")
                        }
                    },
                    onDownload = {
                        Log.i("SettingsScreen", "[USER_ACTION] Download requested for LLM model: ${model.id}")
                        modelManager.downloadModel(model)
                    },
                    onDelete = { modelToDelete = it }
                )
            }
        }
    }
}

@Composable
fun ModelItem(
    model: ModelInfo,
    status: ModelStatus,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: (ModelInfo) -> Unit = {},
    isVad: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = if (!isVad) onSelect else ({}),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer 
                             else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = model.name, style = MaterialTheme.typography.titleMedium)
                Text(text = model.description, style = MaterialTheme.typography.bodySmall)
                Text(
                    text = "Size: ${model.sizeLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }

            when (status) {
                is ModelStatus.READY -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!isVad) {
                            IconButton(onClick = {
                                Log.i("SettingsScreen", "[USER_ACTION] Delete icon clicked for model: ${model.id}")
                                onDelete(model)
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Gray)
                            }
                            RadioButton(selected = isSelected, onClick = onSelect)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Ready",
                                tint = Color(0xFF4CAF50)
                            )
                        }
                    }
                }
                is ModelStatus.DOWNLOADING -> {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { status.progress },
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp
                        )
                        Text(
                            text = "${(status.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 8.sp
                        )
                    }
                }
                is ModelStatus.EXTRACTING -> {
                    Box(contentAlignment = Alignment.Center) {
                        if (status.progress < 0f) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                strokeWidth = 3.dp,
                                color = Color.Magenta
                            )
                        } else {
                            CircularProgressIndicator(
                                progress = { status.progress },
                                modifier = Modifier.size(32.dp),
                                strokeWidth = 3.dp,
                                color = Color.Magenta
                            )
                            Text(
                                text = "${(status.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 8.sp
                            )
                        }
                    }
                }
                is ModelStatus.NOT_DOWNLOADED -> {
                    IconButton(onClick = {
                        Log.i("SettingsScreen", "[USER_ACTION] Download icon clicked for model: ${model.id}")
                        onDownload()
                    }) {
                        Icon(Icons.Default.Download, contentDescription = "Download")
                    }
                }
                is ModelStatus.ERROR -> {
                    Text("Error", color = Color.Red)
                }
            }
        }
    }
}
