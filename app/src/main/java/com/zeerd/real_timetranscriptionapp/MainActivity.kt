package com.zeerd.real_timetranscriptionapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.content.res.Configuration
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.zeerd.real_timetranscriptionapp.ui.theme.RealtimeTranscriptionAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {
    override fun applyOverrideConfiguration(newConfig: Configuration?) {
        super.applyOverrideConfiguration(newConfig ?: LocaleHelper.newConfiguration(this))
    }

    // 注意：管线已迁移到 TranscriptionService（前台服务），Activity 不再持有任何
    // Native 资源或录音协程。这里只保留 UI 状态，全部来自 TranscriptionState 单例。
    private val transcriptions = mutableStateListOf<String>()
    private val regularizedTranscriptions = mutableStateListOf<String>()
    private val isRecording = mutableStateOf(false)
    private val volumeLevel = mutableStateOf(0f)
    private val saveLocationDescription = mutableStateOf("")

    private lateinit var app: TranscriptionApplication

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        Log.d(TAG, "Permission RECORD_AUDIO granted: $isGranted")
        if (isGranted) {
            // 授权后启动前台服务（服务内部会做管线同步检查）
            TranscriptionService.start(this)
        } else {
            val msg = "Permission denied. App cannot record audio."
            Log.e(TAG, "[UI_MSG] $msg")
            transcriptions.add(0, msg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        app = application as TranscriptionApplication
        saveLocationDescription.value = app.fileManager.getCurrentSaveLocation()

        enableEdgeToEdge()
        setContent {
            RealtimeTranscriptionAppTheme {
                val navController = rememberNavController()

                NavHost(navController = navController, startDestination = "transcription") {
                    composable("transcription") {
                        val openDocumentTreeLauncher = rememberLauncherForActivityResult(
                            contract = ActivityResultContracts.OpenDocumentTree()
                        ) { uri ->
                            if (uri != null) {
                                Log.d(TAG, "[USER_ACTION] User selected a save directory via picker: $uri")
                                // 持久化目录访问权限，避免后续写入被系统拒绝
                                contentResolver.takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                )
                                lifecycleScope.launch {
                                    app.fileManager.setUserSelectedDir(uri)
                                    saveLocationDescription.value = app.fileManager.getCurrentSaveLocation()
                                    Log.d(TAG, "User selected save dir: $uri")
                                }
                            } else {
                                Log.d(TAG, "[USER_ACTION] User cancelled save-dir picker (no URI returned)")
                            }
                        }

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
                                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.model_settings))
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                    FloatingActionButton(onClick = {
                                        Log.d(TAG, "[USER_ACTION] Opening save-dir picker")
                                        openDocumentTreeLauncher.launch(null)
                                    }) {
                                        Icon(Icons.Default.Save, contentDescription = stringResource(R.string.saving_to))
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
                                llmPolishingEnabled = app.modelManager.isLlmPolishingEnabled(),
                                onResetHistory = {
                                    lifecycleScope.launch {
                                        app.fileManager.clearAutosaveHistory()
                                        withContext(Dispatchers.Main) {
                                            transcriptions.clear()
                                            regularizedTranscriptions.clear()
                                        }
                                    }
                                },
                                modifier = Modifier.padding(innerPadding)
                            )
                        }
                    }
                    composable("settings") {
                        SettingsScreen(
                            modelManager = app.modelManager,
                            onBack = {
                                Log.d(TAG, "[USER_ACTION] Returning from Settings screen")
                                navController.popBackStack()
                            }
                        )
                    }
                }
            }
        }

        // 订阅 TranscriptionState：把后台服务的状态同步到本 Activity 的 UI 列表
        lifecycleScope.launch {
            TranscriptionState.transcriptions.collect { list ->
                transcriptions.clear()
                transcriptions.addAll(list)
            }
        }
        lifecycleScope.launch {
            TranscriptionState.regularizedTranscriptions.collect { list ->
                regularizedTranscriptions.clear()
                regularizedTranscriptions.addAll(list)
            }
        }
        lifecycleScope.launch {
            TranscriptionState.isRecording.collect { isRecording.value = it }
        }
        lifecycleScope.launch {
            TranscriptionState.volumeLevel.collect { volumeLevel.value = it }
        }

        // 统一入口：模型/设置变化时，确保前台服务在运行并做管线同步检查
        lifecycleScope.launch {
            launch {
                app.modelManager.settingsChanged.collect {
                    Log.i(TAG, "Settings change signal received (#$it)")
                    ensureServiceRunning()
                }
            }
            launch {
                app.modelManager.modelStatuses.collect {
                    ensureServiceRunning()
                }
            }
        }
    }

    /**
     * 确保前台转写服务在运行：若已就绪的 ASR 模型存在，则启动服务（服务内部会
     * 检查权限与模型变化并自行启动/重启管线）。缺少麦克风权限时在此请求。
     */
    private fun ensureServiceRunning() {
        val selectedId = app.modelManager.getSelectedModelId()
        if (selectedId.isEmpty() || !app.modelManager.isModelReady(selectedId)) {
            Log.w(TAG, "No ready ASR model, not starting service")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "RECORD_AUDIO permission not granted, requesting...")
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        Log.i(TAG, "Ensuring TranscriptionService is running")
        TranscriptionService.start(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy (UI only; service keeps running in background)")
    }
}

@Composable
fun TranscriptionScreen(
    transcriptions: List<String>,
    regularizedTranscriptions: List<String>,
    isRecording: Boolean,
    volumeLevel: Float,
    saveLocation: String,
    llmPolishingEnabled: Boolean,
    onResetHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 默认标签页：LLM 润色开启时显示「正式稿」(1)，关闭时显示「实时流」(0)
    var selectedTab by remember { mutableIntStateOf(if (llmPolishingEnabled) 1 else 0) }
    val tabs = listOf(stringResource(R.string.tab_raw), stringResource(R.string.tab_formal))
    var showResetDialog by remember { mutableStateOf(false) }
    var showSpeakersDialog by remember { mutableStateOf(false) }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.clear_history_title)) },
            text = { Text(stringResource(R.string.clear_history_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        Log.i(TAG, "[USER_ACTION] Confirmed clear autosave history")
                        showResetDialog = false
                        onResetHistory()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                ) { Text(stringResource(R.string.clear)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    Log.d(TAG, "[USER_ACTION] Cancelled clear autosave history dialog")
                    showResetDialog = false
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showSpeakersDialog) {
        ManageSpeakersDialog(onDismiss = { showSpeakersDialog = false })
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.real_time_transcription),
                style = MaterialTheme.typography.headlineSmall
            )
            Row {
                IconButton(
                    onClick = {
                        Log.d(TAG, "[USER_ACTION] Manage speakers button pressed")
                        showSpeakersDialog = true
                    }
                ) {
                    Icon(Icons.Default.Person, contentDescription = stringResource(R.string.manage_speakers))
                }
                IconButton(
                    onClick = {
                        Log.d(TAG, "[USER_ACTION] Reset autosave history button pressed")
                        showResetDialog = true
                    }
                ) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.clear_autosave_history))
                }
            }
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
                    text = stringResource(R.string.saving_to),
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
                    text = if (selectedTab == 0) stringResource(R.string.speak_to_start) else stringResource(R.string.waiting_llm),
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
fun ManageSpeakersDialog(onDismiss: () -> Unit) {
    // 收集当前所有出现过的说话人 ID（含未命名的），供用户命名。
    val knownIds = remember { SpeakerNameStore.getAllKnownIds() }

    // 每个说话人的编辑状态
    val nameEdits = remember { knownIds.associateWith { mutableStateOf(SpeakerNameStore.getDisplayName(it)) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.manage_speakers_title)) },
        text = {
            if (knownIds.isEmpty()) {
                Text(stringResource(R.string.no_speakers))
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(knownIds) { id ->
                        val edit = nameEdits[id]!!
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = id,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.width(90.dp)
                            )
                            OutlinedTextField(
                                value = edit.value,
                                onValueChange = { edit.value = it },
                                placeholder = { Text(stringResource(R.string.name_placeholder)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    Log.i(TAG, "[USER_ACTION] Saving speaker names")
                    knownIds.forEach { id ->
                        SpeakerNameStore.setName(id, nameEdits[id]!!.value)
                    }
                    onDismiss()
                }
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
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
            Text(text = if (isRecording) stringResource(R.string.recording_speech) else stringResource(R.string.listening_speech))
        }
    }
}
