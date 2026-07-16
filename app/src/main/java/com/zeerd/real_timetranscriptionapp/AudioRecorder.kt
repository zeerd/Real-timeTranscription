package com.zeerd.real_timetranscriptionapp

import android.annotation.SuppressLint
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class AudioRecorder(private val audioChannel: Channel<ByteArray>) {
    private val TAG = "AudioRecorder"

    @SuppressLint("MissingPermission")
    suspend fun startRecording() = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting AudioRecorder...")
        val minBufferSize = AudioRecord.getMinBufferSize(
            Constants.SAMPLE_RATE,
            Constants.CHANNEL_CONFIG,
            Constants.AUDIO_FORMAT
        )
        
        if (minBufferSize == AudioRecord.ERROR_BAD_VALUE || minBufferSize == AudioRecord.ERROR) {
            Log.e(TAG, "Invalid AudioRecord parameters")
            return@withContext
        }

        val bufferSize = Math.max(minBufferSize, Constants.BYTE_BUFFER_SIZE)
        Log.d(TAG, "Min buffer size: $minBufferSize, final buffer size: $bufferSize")
        
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                Constants.SAMPLE_RATE,
                Constants.CHANNEL_CONFIG,
                Constants.AUDIO_FORMAT,
                bufferSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioRecord: ${e.message}")
            return@withContext
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord state not initialized")
            recorder.release()
            return@withContext
        }

        Log.d(TAG, "AudioRecord initialized, starting recording...")
        try {
            recorder.startRecording()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to start recording: ${e.message}")
            recorder.release()
            return@withContext
        }

        try {
            val buffer = ByteArray(Constants.BYTE_BUFFER_SIZE)
            while (isActive) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    audioChannel.send(buffer.copyOf(read))
                } else if (read < 0) {
                    Log.e(TAG, "AudioRecord read error: $read")
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recording loop error: ${e.message}", e)
        } finally {
            Log.d(TAG, "Stopping AudioRecorder...")
            try {
                recorder.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recorder: ${e.message}")
            }
            recorder.release()
        }
    }
}
