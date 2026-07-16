package com.zeerd.real_timetranscriptionapp

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modelManager: ModelManager,
    onBack: () -> Unit
) {
    val modelStatuses by modelManager.modelStatuses.collectAsState()
    val selectedModelId = remember { mutableStateOf(modelManager.getSelectedModelId()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var showImportDialog by remember { mutableStateOf(false) }
    var targetModelIdForImport by remember { mutableStateOf("") }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null && targetModelIdForImport.isNotEmpty()) {
            scope.launch {
                try {
                    modelManager.importFromUri(targetModelIdForImport, uri)
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
            title = { Text("Select Target Model") },
            text = { Text("Which model are you importing to?") },
            confirmButton = {
                Column {
                    modelManager.availableModels.filter { it.id.startsWith("whisper") }.forEach { model ->
                        TextButton(
                            onClick = {
                                targetModelIdForImport = model.id
                                showImportDialog = false
                                folderPickerLauncher.launch(null)
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
                    onDownload = { modelManager.downloadModel(model) }
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
                    Text("Import Model from Storage")
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
                    }
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
    onDownload: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onSelect,
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
                    RadioButton(selected = isSelected, onClick = onSelect)
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
