package com.zeerd.real_timetranscriptionapp

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SemanticBuffer {
    private val activeTurns = mutableListOf<RawSpeakerTurn>()
    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    // 空闲超时任务：若超过阈值时间仍无新段到来，则把最后一批送出去
    private var idleFlushJob: Job? = null

    private val _bufferFullFlow = MutableSharedFlow<List<RawSpeakerTurn>>()
    val bufferFullFlow = _bufferFullFlow.asSharedFlow()

    // 估算已累积批次的总字符数（用于超长强制切批）
    private fun batchChars(): Int = activeTurns.sumOf { it.rawText.length }

    /**
     * 判断在上一段 [prev] 与当前段 [cur] 之间是否应触发切批。
     * 优先在说话人切换处切分；其次在明显停顿处切分；最后由调用方根据累积长度兜底。
     */
    private fun shouldSplitBetween(prev: RawSpeakerTurn, cur: RawSpeakerTurn): Boolean {
        // 1. 说话人切换：最高优先级，天然语义边界
        if (prev.speakerId != cur.speakerId) {
            Log.d("SemanticBuffer", "Split reason: SPEAKER_CHANGE (${prev.speakerId} -> ${cur.speakerId})")
            return true
        }
        // 2. 明显停顿：上一段的结束时间到当前段的开始时间之间的间隔超过阈值
        //    （cur.timestampMs 为当前段结束时间，cur.startTimestampMs 为当前段开始时间；
        //      prev.timestampMs 为上一时段结束时间，二者之差才是真实停顿，避免把当前段时长算进去）
        val pauseMs = cur.startTimestampMs - prev.timestampMs
        if (pauseMs >= Constants.BATCH_SPLIT_PAUSE_MS) {
            Log.d("SemanticBuffer", "Split reason: PAUSE (${pauseMs}ms >= ${Constants.BATCH_SPLIT_PAUSE_MS}ms)")
            return true
        }
        return false
    }

    suspend fun addTurn(turn: RawSpeakerTurn) {
        mutex.withLock {
            // 若当前已有累积批次，且新段与最后一段之间跨越了语义边界，则先 flush 当前批次
            val last = activeTurns.lastOrNull()
            if (last != null && shouldSplitBetween(last, turn)) {
                flushLocked()
            }

            activeTurns.add(turn)
            Log.d("SemanticBuffer", "addTurn: ts=${turn.timestampMs}, speaker=${turn.speakerId}, turns=${activeTurns.size}, chars=${batchChars()}")

            // 3. 累积长度超阈值：兜底强制切批，避免单次 LLM 推理过长
            if (batchChars() >= Constants.BATCH_SPLIT_MAX_CHARS) {
                Log.i("SemanticBuffer", "Split reason: MAX_CHARS (${batchChars()} >= ${Constants.BATCH_SPLIT_MAX_CHARS})")
                flushLocked()
                return
            }
        }

        // 安排空闲超时：若超过停顿阈值仍无新段，则把当前批次送出去（避免最后一段永远滞留）
        scheduleIdleFlush()
    }

    // 取消旧任务并启动一个新的空闲超时任务。
    // 计时基准是「最后一段的真实结束时刻」(turn.timestampMs = segmentEndTimestampMs)，
    // 而非收到 turn 的时刻，否则会被 Whisper/diarization 的推理延迟吃掉，
    // 导致上一段在下一段还没转写完时就被切走，同说话人连续语句无法合并。
    private fun scheduleIdleFlush() {
        idleFlushJob?.cancel()
        val lastEnd = activeTurns.lastOrNull()?.timestampMs ?: return
        val elapsedSinceEnd = System.currentTimeMillis() - lastEnd
        val waitMs = (Constants.BATCH_SPLIT_IDLE_MS - elapsedSinceEnd).coerceAtLeast(0)
        idleFlushJob = scope.launch {
            delay(waitMs)
            mutex.withLock {
                if (activeTurns.isNotEmpty()) {
                    Log.i("SemanticBuffer", "Split reason: IDLE_TIMEOUT (no new turn for ${Constants.BATCH_SPLIT_IDLE_MS}ms after last segment end)")
                    flushLocked()
                }
            }
        }
    }

    // 将当前累积的批次整体提交给 LLM，并清空（仅在此处清空，不再按固定段数硬切）
    private suspend fun flushLocked() {
        if (activeTurns.isNotEmpty()) {
            val batch = activeTurns.toList()
            activeTurns.clear()
            Log.i("SemanticBuffer", "Flushing ${batch.size} turns (${batchChars()} chars before clear) to LLM")
            _bufferFullFlow.emit(batch)
        }
    }

    // 释放资源（在 Activity/Service 销毁时调用）
    fun release() {
        idleFlushJob?.cancel()
        scope.cancel()
    }
}
