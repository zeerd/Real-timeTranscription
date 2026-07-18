package com.zeerd.real_timetranscriptionapp

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class SemanticBuffer {
    private val activeTurns = mutableListOf<RawSpeakerTurn>()
    
    // Limits the payload to prevent high latency on local LLM inference
    private val TRIGGER_TURN_COUNT = Constants.SEMANTIC_BUFFER_TRIGGER_COUNT

    private val _bufferFullFlow = MutableSharedFlow<List<RawSpeakerTurn>>()
    val bufferFullFlow = _bufferFullFlow.asSharedFlow()

    suspend fun addTurn(turn: RawSpeakerTurn) {
        activeTurns.add(turn)
        Log.d("SemanticBuffer", "addTurn: speaker=${turn.speakerId}, turns=${activeTurns.size}/$TRIGGER_TURN_COUNT")
        if (activeTurns.size >= TRIGGER_TURN_COUNT) {
            val batch = activeTurns.toList()
            activeTurns.clear() // Flush for next batch
            Log.i("SemanticBuffer", "Buffer full, emitting ${batch.size} turns to LLM")
            _bufferFullFlow.emit(batch)
        }
    }
}
