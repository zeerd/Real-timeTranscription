package com.zeerd.real_timetranscriptionapp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log

/**
 * 应用级 Application，持有跨 Activity / Service 共享的单例管理器。
 *
 * 之前 [ModelManager] 与 [TranscriptionFileManager] 在 MainActivity.onCreate 里各自 new，
 * 现在管线迁移到 [TranscriptionService]，服务也需要这两个管理器。
 * 把它们提升为 Application 级单例，保证 Activity 与 Service 看到的是同一份状态
 * （尤其是 ModelManager 的 settingsChanged / modelStatuses Flow，以及文件保存目录）。
 */
class TranscriptionApplication : Application() {
    private val TAG = "TranscriptionApplication"

    lateinit var modelManager: ModelManager
        private set
    lateinit var fileManager: TranscriptionFileManager
        private set

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "transcription_service_channel"
        const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: initializing shared managers")
        modelManager = ModelManager(this)
        fileManager = TranscriptionFileManager(this)
        // 初始化说话人命名映射（speakerId → 显示名），跨重启持久化
        SpeakerNameStore.init(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        // Android 8.0+ 要求前台服务先建好通知渠道
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "实时转写服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "在后台持续录音并转写"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created: ${NOTIFICATION_CHANNEL_ID}")
        }
    }
}
