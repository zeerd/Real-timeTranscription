package com.zeerd.real_timetranscriptionapp

import android.media.AudioFormat

object Constants {
    const val SAMPLE_RATE = 16000 // 16kHz required by Silero & Whisper
    const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    const val FRAME_SIZE_SAMPLES = 512 // 32ms window at 16kHz
    const val BYTE_BUFFER_SIZE = FRAME_SIZE_SAMPLES * 2 // 16-bit = 2 Bytes per sample (1024 Bytes)

    const val SILENCE_THRESHOLD_LIMIT = 20 // ~640ms of continuous silence to trigger cut
    const val MAX_SPEECH_DURATION_SAMPLES = 16000 * 15 // 15 seconds safety cutoff
    const val SPEECH_PROBABILITY_THRESHOLD = 0.5f // Silero activation threshold
}
