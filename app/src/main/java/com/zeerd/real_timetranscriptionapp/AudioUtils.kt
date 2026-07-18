package com.zeerd.real_timetranscriptionapp

import java.io.File
import java.io.FileOutputStream

fun ByteArray.toFloatArrayNormalized(): FloatArray {
    val floatArray = FloatArray(this.size / 2)
    for (i in floatArray.indices) {
        // Combine 2 Bytes into 16-bit Short (Little Endian)
        val low = this[i * 2].toInt() and 0xFF
        val high = this[i * 2 + 1].toInt()
        val sample = ((high shl 8) or low).toShort()
        // Normalize to [-1.0, 1.0]
        floatArray[i] = sample.toFloat() / 32768.0f
    }
    return floatArray
}

/**
 * 将归一化后的 Float 音频（[-1,1]）写成 16-bit PCM 单声道 WAV 文件，
 * 方便直接拷到电脑/手机上用播放器试听，核对识别结果是否漏字。
 */
fun writeWavFile(samples: FloatArray, file: File, sampleRate: Int = Constants.SAMPLE_RATE) {
    val data = ByteArray(samples.size * 2)
    for (i in samples.indices) {
        val s = samples[i].coerceIn(-1f, 1f)
        val intSample = (s * 32767).toInt()
        data[i * 2] = (intSample and 0xFF).toByte()
        data[i * 2 + 1] = ((intSample shr 8) and 0xFF).toByte()
    }

    val chunkSize = 36 + data.size
    val header = ByteArray(44)
    header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
    header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
    header[4] = (chunkSize and 0xFF).toByte(); header[5] = ((chunkSize shr 8) and 0xFF).toByte()
    header[6] = ((chunkSize shr 16) and 0xFF).toByte(); header[7] = ((chunkSize shr 24) and 0xFF).toByte()
    header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
    header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
    header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
    header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
    header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
    header[20] = 1; header[21] = 0 // PCM
    header[22] = 1; header[23] = 0 // mono
    header[24] = (sampleRate and 0xFF).toByte(); header[25] = ((sampleRate shr 8) and 0xFF).toByte()
    header[26] = ((sampleRate shr 16) and 0xFF).toByte(); header[27] = ((sampleRate shr 24) and 0xFF).toByte()
    val byteRate = sampleRate * 2
    header[28] = (byteRate and 0xFF).toByte(); header[29] = ((byteRate shr 8) and 0xFF).toByte()
    header[30] = ((byteRate shr 16) and 0xFF).toByte(); header[31] = ((byteRate shr 24) and 0xFF).toByte()
    header[32] = 2; header[33] = 0 // block align
    header[34] = 16; header[35] = 0 // bits per sample
    header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
    header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
    header[40] = (data.size and 0xFF).toByte(); header[41] = ((data.size shr 8) and 0xFF).toByte()
    header[42] = ((data.size shr 16) and 0xFF).toByte(); header[43] = ((data.size shr 24) and 0xFF).toByte()

    FileOutputStream(file).use {
        it.write(header)
        it.write(data)
    }
}
