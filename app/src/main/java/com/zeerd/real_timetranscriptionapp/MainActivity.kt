package com.zeerd.real_timetranscriptionapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.content.res.Configuration
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
import androidx.compose.material.icons.filled.Close
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
    private val isRecording = mutableStateOf(false)
    private val isCapturing = mutableStateOf(false)
    private val summarizing = mutableStateOf(false)
    private val volumeLevel = mutableStateOf(0f)
    private val saveLocationDescription = mutableStateOf("")
    // 停止采集后，服务推送的待总结文本；非空时弹出「是否总结」对话框
    private val pendingSummaryText = mutableStateOf<String?>(null)
    // 最近一次生成的 LLM 总结文本；非空时在界面顶部展示
    private val summaryResult = mutableStateOf<String?>(null)

    private lateinit var app: TranscriptionApplication

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        Log.d(TAG, "Permission RECORD_AUDIO granted: $isGranted")
        if (isGranted) {
            // 授权后仅启动前台服务（挂在前台、恢复历史），不自动录音。
            // 录音由 UI 的「开始」按钮手动触发。
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
                        val createDocumentLauncher = rememberLauncherForActivityResult(
                            contract = ActivityResultContracts.CreateDocument("text/plain")
                        ) { uri ->
                            if (uri != null) {
                                Log.d(TAG, "[USER_ACTION] User created a save file via picker: $uri")
                                // 持久化文件访问权限，避免后续写入被系统拒绝
                                contentResolver.takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                )
                                lifecycleScope.launch {
                                    app.fileManager.setUserSelectedFile(uri)
                                    saveLocationDescription.value = app.fileManager.getCurrentSaveLocation()
                                    Log.d(TAG, "User selected save file: $uri")
                                }
                            } else {
                                Log.d(TAG, "[USER_ACTION] User cancelled save-file picker (no URI returned)")
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
                                        Log.d(TAG, "[USER_ACTION] Opening save-file picker")
                                        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                                        createDocumentLauncher.launch("transcription_$ts.txt")
                                    }) {
                                        Icon(Icons.Default.Save, contentDescription = stringResource(R.string.saving_to))
                                    }
                                }
                            }
                        ) { innerPadding ->
                            TranscriptionScreen(
                                transcriptions = transcriptions,
                                isRecording = isRecording.value,
                                isCapturing = isCapturing.value,
                                summarizing = summarizing.value,
                                pendingSummaryText = pendingSummaryText.value,
                                summaryResult = summaryResult.value,
                                onDismissSummaryDialog = {
                                    pendingSummaryText.value = null
                                    summaryResult.value = null
                                },
                                volumeLevel = volumeLevel.value,
                                saveLocation = saveLocationDescription.value,
                                onStartCapture = {
                                    Log.d(TAG, "[USER_ACTION] Start capture button pressed")
                                    TranscriptionService.startCapture(this@MainActivity)
                                },
                                onStopCapture = {
                                    Log.d(TAG, "[USER_ACTION] Stop capture button pressed")
                                    TranscriptionService.stopCapture(this@MainActivity)
                                },
                                onConfirmSummary = { text ->
                                    Log.d(TAG, "[USER_ACTION] Confirmed summarize, sending to service")
                                    pendingSummaryText.value = null
                                    TranscriptionService.requestSummary(this@MainActivity, text)
                                },
                                onResetHistory = {
                                    lifecycleScope.launch {
                                        app.fileManager.clearAutosaveHistory()
                                        withContext(Dispatchers.Main) {
                                            transcriptions.clear()
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
            TranscriptionState.isRecording.collect { isRecording.value = it }
        }
        lifecycleScope.launch {
            TranscriptionState.isCapturing.collect { isCapturing.value = it }
        }
        lifecycleScope.launch {
            TranscriptionState.summarizing.collect { summarizing.value = it }
        }
        lifecycleScope.launch {
            TranscriptionState.pendingSummary.collect { text ->
                pendingSummaryText.value = text
            }
        }
        lifecycleScope.launch {
            TranscriptionState.summaryResult.collect { text ->
                summaryResult.value = text
            }
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
     * 确保前台转写服务在运行：若已就绪的 ASR 模型存在，则启动服务（服务仅挂在前台、
     * 恢复历史，不会自动录音）。缺少麦克风权限时在此请求。录音由 UI 的「开始」按钮触发。
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
        Log.i(TAG, "Ensuring TranscriptionService is running (foreground only, no auto-capture)")
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
    isRecording: Boolean,
    isCapturing: Boolean,
    summarizing: Boolean,
    pendingSummaryText: String?,
    summaryResult: String?,
    onDismissSummaryDialog: () -> Unit,
    volumeLevel: Float,
    saveLocation: String,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onConfirmSummary: (String) -> Unit,
    onResetHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
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

    // 停止采集后，询问是否对本次会话做 LLM 总结
    if (pendingSummaryText != null) {
        AlertDialog(
            onDismissRequest = onDismissSummaryDialog,
            title = { Text(stringResource(R.string.summarize_title)) },
            text = { Text(stringResource(R.string.summarize_text)) },
            confirmButton = {
                TextButton(
                    onClick = { onConfirmSummary(pendingSummaryText) },
                    enabled = !summarizing
                ) { Text(stringResource(R.string.summarize_yes)) }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismissSummaryDialog,
                    enabled = !summarizing
                ) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // 总结进行中的进度提示
    if (summarizing) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.summarizing_title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(R.string.summarizing_text))
                }
            },
            confirmButton = { }
        )
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

        if (summaryResult != null) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.summary_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        IconButton(
                            onClick = { onDismissSummaryDialog() },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.dismiss_summary))
                        }
                    }
                    SelectionContainer {
                        Text(
                            text = summaryResult,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
        }

        StatusCard(isRecording, volumeLevel)

        Spacer(modifier = Modifier.height(12.dp))

        // 开始 / 停止 采集按钮（手动控制录音）
        Button(
            onClick = {
                if (isCapturing) onStopCapture() else onStartCapture()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isCapturing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(if (isCapturing) stringResource(R.string.stop_capture) else stringResource(R.string.start_capture))
        }

        Spacer(modifier = Modifier.height(16.dp))
        
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
                Text(
                    text = stringResource(R.string.speak_to_start),
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
