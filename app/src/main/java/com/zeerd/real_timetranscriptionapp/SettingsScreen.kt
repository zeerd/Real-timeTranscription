package com.zeerd.real_timetranscriptionapp

import android.content.Intent
import android.media.MediaRecorder
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
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

    val selectedModelId = remember { mutableStateOf(modelManager.getSelectedModelId()) }
    val selectedLlmModelId = remember { mutableStateOf(modelManager.getSelectedLlmModelId()) }
    val selectedSpeakerModelId = remember { mutableStateOf(modelManager.getSelectedSpeakerModelId()) }
    val selectedAudioSource = remember { mutableIntStateOf(modelManager.getAudioSource()) }
    val llmPolishingEnabled = remember { mutableStateOf(modelManager.isLlmPolishingEnabled()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 语言切换：当前选择 + 是否展开下拉
    val selectedLanguage = remember { mutableStateOf(LocaleHelper.getLanguage(context)) }
    var languageExpanded by remember { mutableStateOf(false) }
    val languageOptions = listOf(
        LocaleHelper.LANGUAGE_SYSTEM to stringResource(R.string.language_system),
        LocaleHelper.LANGUAGE_EN to stringResource(R.string.language_english),
        LocaleHelper.LANGUAGE_ZH to stringResource(R.string.language_chinese)
    )

    val audioSources = listOf(
        AudioSourceOption("VOICE_RECOGNITION", MediaRecorder.AudioSource.VOICE_RECOGNITION, stringResource(R.string.audio_voice_recognition)),
        AudioSourceOption("CAMCORDER", MediaRecorder.AudioSource.CAMCORDER, stringResource(R.string.audio_camcorder)),
        AudioSourceOption("MIC", MediaRecorder.AudioSource.MIC, stringResource(R.string.audio_mic)),
        AudioSourceOption("UNPROCESSED", 9 /* MediaRecorder.AudioSource.UNPROCESSED */, stringResource(R.string.audio_unprocessed))
    )

    // Delete Confirmation Dialog
    var modelToDelete by remember { mutableStateOf<ModelInfo?>(null) }

    if (modelToDelete != null) {
        AlertDialog(
            onDismissRequest = { modelToDelete = null },
            title = { Text(stringResource(R.string.delete_model_title)) },
            text = { Text(stringResource(R.string.delete_model_text, modelToDelete?.name ?: "")) },
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
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    Log.d("SettingsScreen", "[USER_ACTION] Cancelled delete dialog for: ${modelToDelete?.id}")
                    modelToDelete = null
                }) { Text(stringResource(R.string.cancel)) }
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
                    
                    Toast.makeText(context, context.getString(R.string.importing_file, fileName), Toast.LENGTH_SHORT).show()
                    modelManager.importLiteRtModel(uri, fileName)
                    Toast.makeText(context, context.getString(R.string.import_success), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.import_failed, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // 内容驱动导入：不询问模型类型，压缩包里的 .onnx + tokens.txt 原样解压，
    // 类型由 WhisperWrapper 按文件自动识别，自动分配 custom-asr-* 目录。
    val asrArchivePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            Log.i("SettingsScreen", "[USER_ACTION] ASR archive picker returned uri=$uri")
            scope.launch {
                try {
                    Toast.makeText(context, context.getString(R.string.importing_archive), Toast.LENGTH_SHORT).show()
                    modelManager.importAsrArchive(uri)
                    Toast.makeText(context, context.getString(R.string.import_success), Toast.LENGTH_SHORT).show()
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

                    Toast.makeText(context, context.getString(R.string.importing_file, fileName), Toast.LENGTH_SHORT).show()
                    modelManager.importSingleFileModel(uri, fileName)
                    Toast.makeText(context, context.getString(R.string.import_success), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e("SettingsScreen", "[SPEAKER_IMPORT] failed", e)
                    Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.model_settings)) },
                navigationIcon = {
                    IconButton(onClick = {
                        Log.d("SettingsScreen", "[USER_ACTION] Back button pressed")
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
                    text = stringResource(R.string.language),
                    style = MaterialTheme.typography.titleLarge
                )
                ExposedDropdownMenuBox(
                    expanded = languageExpanded,
                    onExpandedChange = { languageExpanded = it }
                ) {
                    OutlinedTextField(
                        value = languageOptions.first { it.first == selectedLanguage.value }.second,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.language)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = true)
                    )
                    ExposedDropdownMenu(
                        expanded = languageExpanded,
                        onDismissRequest = { languageExpanded = false }
                    ) {
                        languageOptions.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    Log.i("SettingsScreen", "[USER_ACTION] Language selected: $value")
                                    LocaleHelper.setLanguage(context, value)
                                    selectedLanguage.value = value
                                    languageExpanded = false
                                    // 立即重建 Activity 以套用新语言
                                    (context as? ComponentActivity)?.recreate()
                                }
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.audio_settings),
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = stringResource(R.string.audio_settings_desc),
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
                    text = stringResource(R.string.vad_model),
                    style = MaterialTheme.typography.titleLarge
                )
                // VAD 已打包进 APK（assets/silero_vad.onnx），运行时直接从 asset 加载，
                // 始终可用，无需导入、也不提供下载链接。这里只做状态说明。
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.silero_vad_builtin),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.vad_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.status_ready),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.asr_models),
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = stringResource(R.string.asr_models_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        Log.d("SettingsScreen", "[USER_ACTION] Opening ASR archive import picker (content-driven)")
                        asrArchivePickerLauncher.launch(arrayOf("application/x-bzip2", "application/octet-stream", "*/*"))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.import_asr_archive))
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.optional_default_downloads),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.Gray
                )
                // 可点击的下载链接列表（不再提供下载按钮，仅作参考链接）
                val asrLinks = modelManager.defaultDownloadLinks.filter {
                    it.id.startsWith("whisper") || it.id.contains("sense", ignoreCase = true)
                }
                asrLinks.forEach { link ->
                    DownloadLinkRow(name = link.name, url = link.encoderUrl)
                }
            }

            // 只展示已安装且归类为 ASR 的模型（含历史遗留的 whisper-small 等）。静态占位项
            // （whisper-tiny/base/small、SenseVoiceSmall）不会被应用内安装，故不列出，避免
            // 永远显示 "Not Installed" 的死行。customModels 仅含磁盘上真实存在的目录。
            items(customModels.filter { it.category == ModelCategory.ASR }) { model ->
                val status = modelStatuses[model.id] ?: ModelStatus.NOT_DOWNLOADED
                val isSelected = selectedModelId.value == model.id

                ModelItem(
                    model = model,
                    status = status,
                    isSelected = isSelected,
                    onSelect = {
                        if (status == ModelStatus.READY) {
                            Log.i("SettingsScreen", "[USER_ACTION] ASR model selected: ${model.id}")
                            modelManager.setSelectedModelId(model.id)
                            selectedModelId.value = model.id
                        } else {
                            Log.w("SettingsScreen", "[USER_ACTION] ASR model '${model.id}' not READY, selection ignored")
                        }
                    },
                    onDelete = { modelToDelete = it }
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.speaker_diarization),
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = stringResource(R.string.speaker_diarization_desc),
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
                    Text(stringResource(R.string.import_speaker_model))
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.optional_default_downloads),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.Gray
                )
                modelManager.defaultDownloadLinks.filter { it.id == "speaker-ecapa" }.forEach { link ->
                    DownloadLinkRow(name = link.name, url = link.encoderUrl)
                }
            }

            // 只展示已安装且归类为说话人的模型（含历史遗留的 speaker-ecapa 等）。静态占位
            // speaker-ecapa 不会被应用内安装，故不列出，避免永远显示 "Not Installed" 的死行。
            // customModels 仅含磁盘上真实存在的目录。
            items(customModels.filter { it.category == ModelCategory.SPEAKER }) { model ->
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
                    onDelete = { modelToDelete = it }
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.local_llm),
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = stringResource(R.string.local_llm_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(8.dp))

                // LLM 润色开关：关闭后不再对原始稿做语义分段润色，仅保留实时原始转写
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.enable_llm_polishing),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(R.string.enable_llm_polishing_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                        Switch(
                            checked = llmPolishingEnabled.value,
                            onCheckedChange = {
                                Log.i("SettingsScreen", "[USER_ACTION] LLM polishing toggled: $it")
                                modelManager.setLlmPolishingEnabled(it)
                                llmPolishingEnabled.value = it
                            }
                        )
                    }
                }

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
                    Text(stringResource(R.string.import_litert_model))
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.optional_default_downloads),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.Gray
                )
                modelManager.defaultDownloadLinks.filter { it.id.startsWith("llm") }.forEach { link ->
                    DownloadLinkRow(name = link.name, url = link.encoderUrl)
                }
            }

            // 只展示已安装且归类为 LLM 的模型（含历史遗留的 llm-* 等）。静态占位
            // llm-gemma-2b 不会被应用内安装，故不列出，避免永远显示 "Not Installed" 的死行。
            // customModels 仅含磁盘上真实存在的目录。
            items(customModels.filter { it.category == ModelCategory.LLM }) { model ->
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
                    onDelete = { modelToDelete = it }
                )
            }
        }
    }
}

// 可点击的下载链接行：仅作为参考链接，点击后用浏览器打开，不触发应用内下载。
@Composable
fun DownloadLinkRow(name: String, url: String) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                Log.i("SettingsScreen", "[USER_ACTION] Opening download link: $url")
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Log.e("SettingsScreen", "[DOWNLOAD_LINK] failed to open: $url", e)
                }
            }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Link,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = buildAnnotatedString {
                withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)) {
                    append(name)
                }
            },
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun ModelItem(
    model: ModelInfo,
    status: ModelStatus,
    isSelected: Boolean,
    onSelect: () -> Unit,
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
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete), tint = Color.Gray)
                            }
                            RadioButton(selected = isSelected, onClick = onSelect)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(R.string.status_ready),
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
                    Text(stringResource(R.string.not_installed), color = Color.Gray)
                }
                is ModelStatus.ERROR -> {
                    Text(stringResource(R.string.error), color = Color.Red)
                }
            }
        }
    }
}
