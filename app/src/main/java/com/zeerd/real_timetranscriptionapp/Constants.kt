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
    // 送给 Whisper 前在音频末尾补的静音长度，给模型收尾上下文，避免漏掉最后一个字
    const val TRAILING_SILENCE_SAMPLES = 16000 // 1.0s @16kHz

    const val DIARIZATION_SIMILARITY_THRESHOLD = 0.5f
    // 过短的片段 embedding 不可靠，低于此时长直接沿用上一位说话人，避免误判为新说话人
    const val MIN_DIARIZATION_SAMPLES = 16000 // 1 秒 @16kHz
    const val SEMANTIC_BUFFER_TRIGGER_COUNT = 4

    // ===== Speaker-Change-Driven Segmentation (SCD) 声纹滑窗换人检测 =====
    // 在 VAD 判定为连续语音时，仍按滑窗滚动提取声纹，检测相邻窗口相似度暴跌以强行切段，
    // 解决两人无缝接话/打断导致被并成一段的问题。
    const val SCD_SUB_WINDOW_SAMPLES = (16000 * 0.400).toInt() // 400ms 子窗口
    const val SCD_SLIDE_STEP_SAMPLES = (16000 * 0.200).toInt()  // 200ms 滑动步长
    // 余弦相似度跌破即判定换人。
    // 实测：同一说话人相邻窗口相似度约 0.35~0.60，不同说话人约 0.05。
    // 阈值必须落在两者之间（此处取 0.25），否则会把同一人的正常声纹波动误判为换人，
    // 导致每 ~0.75s 强制切段、片段过短而让 Whisper 输出乱码。
    const val SCD_CHANGE_THRESHOLD = 0.25f
    // 防抖：连续 N 次跌破阈值才真正切段，避免噪声导致的偶发误判
    const val SCD_CONFIRM_STREAK = 2
    // 单次切出的最小有效时长，过短片段交给后端 diarization 兜底，不在此硬切。
    // 提高到 2 秒，避免切出过短片段导致 Whisper 因音频不足而输出乱码/幻觉。
    const val SCD_MIN_CUT_SAMPLES = 16000 * 2 // 2 秒 @16kHz

    // 正式稿批次切分策略（以语义/说话人边界为主，避免硬切断导致 LLM 补 "..."）
    // 相邻两段语音之间的「真实停顿」（上一段真实结束时刻到下一段真实开始时刻）超过此值，
    // 视为明显停顿，触发切批。设为 2s：同一说话人正常换气/短停顿（<2s）不切、合并成一句；
    // 只有明显长停顿才切批，避免把同人连续语句拆散。
    const val BATCH_SPLIT_PAUSE_MS = 2000L
    // 空闲超时：距「最后一段真实结束时刻」超过此值仍无新段到达，则把当前批次送出去
    // （避免最后一段永远滞留）。必须明显大于单段语音时长（通常 1~4s），否则上一段会在
    // 下一段还没转写完时就被切走，导致同说话人连续语句无法合并。
    const val BATCH_SPLIT_IDLE_MS = 3500L
    // 累积批次字符数超过此值，强制切批（防止单次 LLM 推理过长）
    const val BATCH_SPLIT_MAX_CHARS = 1200
}
