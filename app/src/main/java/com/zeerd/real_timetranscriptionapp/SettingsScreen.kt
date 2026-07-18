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

data class AudioSourceOption(val name: String, val value: Int, val description: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modelManager: ModelManager,
    onBack: () -> Unit
) {
    val modelStatuses by modelManager.modelStatuses.collectAsState()
    val selectedModelId = remember { mutableStateOf(modelManager.getSelectedModelId()) }
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
                        modelToDelete?.let { modelManager.deleteModel(it.id) }
                        modelToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { modelToDelete = null }) { Text("Cancel") }
            }
        )
    }

    val archivePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null && targetModelIdForImport.isNotEmpty()) {
            scope.launch {
                try {
                    Toast.makeText(context, "Importing... Check progress icons below.", Toast.LENGTH_SHORT).show()
                    modelManager.importFromArchive(targetModelIdForImport, uri)
                    Toast.makeText(context, "Import successful!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Select Target Specification") },
            text = { Text("Which model specification does this archive match?") },
            confirmButton = {
                Column {
                    modelManager.availableModels.filter { it.id.startsWith("whisper") }.forEach { model ->
                        TextButton(
                            onClick = {
                                targetModelIdForImport = model.id
                                showImportDialog = false
                                archivePickerLauncher.launch(arrayOf("application/x-bzip2", "application/octet-stream", "*/*"))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(model.name)
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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

            items(modelManager.availableModels.filter { it.id == "vad-silero" }) { model ->
                val status = modelStatuses[model.id] ?: ModelStatus.NOT_DOWNLOADED
                ModelItem(
                    model = model,
                    status = status,
                    isSelected = false,
                    onSelect = {},
                    onDownload = { modelManager.downloadModel(model) },
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
                    onClick = { showImportDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Import Model from Storage (.tar.bz2)")
                }
            }

            items(modelManager.availableModels.filter { it.id.startsWith("whisper") }) { model ->
                val status = modelStatuses[model.id] ?: ModelStatus.NOT_DOWNLOADED
                val isSelected = selectedModelId.value == model.id

                ModelItem(
                    model = model,
                    status = status,
                    isSelected = isSelected,
                    onSelect = {
                        if (status == ModelStatus.READY) {
                            modelManager.setSelectedModelId(model.id)
                            selectedModelId.value = model.id
                        }
                    },
                    onDownload = {
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
                            IconButton(onClick = { onDelete(model) }) {
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
                    IconButton(onClick = onDownload) {
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
