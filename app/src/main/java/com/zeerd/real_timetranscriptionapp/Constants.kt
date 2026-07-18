package com.zeerd.real_timetranscriptionapp

import android.media.AudioFormat

object Constants {
    const val SAMPLE_RATE = 16000 // 16kHz required by Silero & Whisper
    const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    const val FRAME_SIZE_SAMPLES = 512 // 32ms window at 16kHz
    const val BYTE_BUFFER_SIZE = FRAME_SIZE_SAMPLES * 2 // 16-bit = 2 Bytes per sample (1024 Bytes)

    const val SILENCE_THRESHOLD_LIMIT = 30 // ~1 second of continuous silence to trigger cut
    const val MAX_SPEECH_DURATION_SAMPLES = 16000 * 20 // 20 seconds safety cutoff
    const val SPEECH_PROBABILITY_THRESHOLD = 0.4f // Lowered threshold for better sensitivity
    
    const val PRE_SPEECH_BUFFER_FRAMES = 10 // Pre-roll: ~320ms of audio before detection
    const val MIN_SPEECH_DURATION_SAMPLES = 16000 * 0.5 // 0.5 seconds minimum to be valid

    const val DIARIZATION_SIMILARITY_THRESHOLD = 0.5f
    // 过短的片段 embedding 不可靠，低于此时长直接沿用上一位说话人，避免误判为新说话人
    const val MIN_DIARIZATION_SAMPLES = 16000 // 1 秒 @16kHz
    const val SEMANTIC_BUFFER_TRIGGER_COUNT = 4
}
