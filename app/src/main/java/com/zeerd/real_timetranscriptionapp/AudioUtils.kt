package com.zeerd.real_timetranscriptionapp

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
