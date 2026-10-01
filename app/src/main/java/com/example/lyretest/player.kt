package com.example.lyretest.player

import com.example.lyretest.arrange.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.roundToLong

data class PlayParams(val maxStrokes: Int = REQUESTED_TOUCH_LIMIT)

/** 一个手势片段中的触点拥有各自起止时间；continues=true 时保持触点供下一片段续接。 */
data class TapBatch(val keys: List<String>, val holdMs: Long,
    val offsetsMs: List<Long> = List(keys.size) { 0L },
    val durationsMs: List<Long> = List(keys.size) { holdMs },
    val noteIds: List<Int> = keys.indices.toList(),
    val continues: List<Boolean> = List(keys.size) { false }) {
    init {
        require(listOf(offsetsMs.size, durationsMs.size, noteIds.size, continues.size).all { it == keys.size })
        require(offsetsMs.all { it >= 0 } && durationsMs.all { it > 0 })
    }
    val durationMs: Long get() = keys.indices.maxOfOrNull { offsetsMs[it] + durationsMs[it] } ?: 0L
}

interface PlayerCallbacks {
    /** 只有手势完成才返回 true；未提交或被用户交互取消的批次返回 false。 */
    fun dispatch(batch: TapBatch): Boolean
    fun releaseAll() {}
    fun onStarted() {}
    fun onProgress(ratio: Float, playTime: Double, total: Double)
    fun onFinish(completed: Boolean)
    fun log(message: String) {}
}

data class GestureFrame(val end: Double, val batch: TapBatch)

/** 每键二分索引。片段最多 80ms，限制的是触点描述数量，不把乐曲量化到 80ms。 */
class GesturePlanner(val notes: List<ScheduledNote>, private val maxStrokes: Int = REQUESTED_TOUCH_LIMIT) {
    private val byKey = notes.withIndex().groupBy { it.value.key }
    val total = notes.maxOfOrNull { it.end } ?: 0.0
    private fun intersecting(start: Double, end: Double): List<IndexedValue<ScheduledNote>> =
        byKey.values.flatMap { row ->
            var lo = 0
            var hi = row.size
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (row[mid].value.end <= start + EPSILON) lo = mid + 1 else hi = mid
            }
            val result = mutableListOf<IndexedValue<ScheduledNote>>()
            while (lo < row.size && row[lo].value.start < end - EPSILON) result.add(row[lo++])
            result
        }.sortedWith(compareBy({ it.value.start }, { it.index }))

    fun frame(position: Double, actualPosition: Double = position,
        onsetFloor: Double = Double.NEGATIVE_INFINITY, resumedNoteIds: Set<Int> = emptySet()): GestureFrame {
        fun eligible(end: Double) = intersecting(position, end).filter {
            it.value.end > actualPosition + EPSILON &&
                (it.value.start >= onsetFloor - EPSILON || it.index in resumedNoteIds)
        }
        var end = minOf(total, position + .080)
        var selected = eligible(end)
        if (selected.size > maxStrokes) {
            end = selected[maxStrokes].value.start
            require(end > position + EPSILON) { "同时触点超出上限" }
            selected = eligible(end)
        }
        val offsets = selected.map { ((it.value.start - actualPosition).coerceAtLeast(0.0) * 1000).roundToLong() }
        val lengths = selected.map {
            ((minOf(it.value.end, end) - maxOf(it.value.start, actualPosition)) * 1000).roundToLong().coerceAtLeast(1L)
        }
        return GestureFrame(end, TapBatch(selected.map { it.value.key }, 40L, offsets, lengths,
            selected.map { it.index }, selected.map { it.value.end > end + EPSILON }))
    }
}

/** 与电脑版共用起止事件语义。长触点通过 Android continueStroke 跨手势保持。 */
class Player(chords: List<Chord>, speed: Double, startRatio: Double,
    private val params: PlayParams, private val callbacks: PlayerCallbacks) {
    companion object { val inputLock = ReentrantLock() }
    val notes = limitPolyphony(buildSchedule(chords, speed.coerceIn(.5, 2.0)), params.maxStrokes)
    private val planner = GesturePlanner(notes, params.maxStrokes)
    val total = planner.total
    private val stopFlag = AtomicBoolean(false)
    private val pauseFlag = AtomicBoolean(false)
    private val interactionFlag = AtomicBoolean(false)
    private val interactionRelease = AtomicBoolean(false)
    private val inputVersion = java.util.concurrent.atomic.AtomicLong(0)
    private val seekRequest = AtomicReference<Double?>(null)
    @Volatile var suppressProgress = false
    @Volatile var playTime = startRatio.coerceIn(0.0, 1.0) * total
        private set
    private var thread: Thread? = null
    val isPlaying get() = thread?.isAlive == true
    val isPaused get() = pauseFlag.get()
    val inputChangeVersion get() = inputVersion.get()
    val inputSuspended get() = stopFlag.get() || pauseFlag.get() || interactionFlag.get() ||
        interactionRelease.get() || seekRequest.get() != null
    fun setInteracting(active: Boolean) {
        if (active) interactionRelease.set(true)
        interactionFlag.set(active); inputVersion.incrementAndGet(); LockSupport.unpark(thread)
    }
    fun start() {
        if (thread != null) return
        thread = Thread({ run() }, "LyrePlayer").apply { priority = Thread.MAX_PRIORITY; start() }
    }
    fun pause() { pauseFlag.set(true); inputVersion.incrementAndGet(); LockSupport.unpark(thread) }
    fun resume() { pauseFlag.set(false); LockSupport.unpark(thread) }
    fun stop() { stopFlag.set(true); pauseFlag.set(false); inputVersion.incrementAndGet(); LockSupport.unpark(thread) }
    fun join(timeoutMs: Long = 2000L) { thread?.join(timeoutMs) }
    fun seek(seconds: Double) { seekRequest.set(seconds.coerceIn(0.0, total)); inputVersion.incrementAndGet(); LockSupport.unpark(thread) }
    fun seekRatio(ratio: Float) = seek(ratio.toDouble() * total)
    private fun now() = System.nanoTime() / 1e9
    private fun progress() {
        if (!suppressProgress) callbacks.onProgress(if (total > 0) (playTime / total).coerceIn(0.0, 1.0).toFloat() else 0f,
            playTime, total)
    }
    private fun run() {
        var completed = false
        inputLock.lock()
        try {
            if (stopFlag.get()) return
            if (notes.isNotEmpty() && playTime < total) callbacks.onStarted()
            var reference = now() - playTime
            var cursor = playTime
            var paused = false
            // 与电脑一致：定位只播放目标处及之后的起音；普通暂停另行保存确实发出的长音。
            var onsetFloor = cursor
            var resumedNoteIds = emptySet<Int>()
            var dispatchedNoteIds = emptySet<Int>()
            var interruptedFrame = false
            // 先消费定位请求，再判断曲尾；最后一帧回调期间也可能收到向前跳转。
            while (!stopFlag.get()) {
                val releaseForInteraction = interactionRelease.getAndSet(false) || interruptedFrame
                interruptedFrame = false
                seekRequest.getAndSet(null)?.let {
                    callbacks.releaseAll(); cursor = it; playTime = it; reference = now() - it
                    onsetFloor = it; resumedNoteIds = emptySet(); dispatchedNoteIds = emptySet()
                    progress()
                }
                if (releaseForInteraction || pauseFlag.get() || interactionFlag.get()) {
                    if (!paused) {
                        resumedNoteIds = dispatchedNoteIds.filterTo(mutableSetOf()) {
                            notes[it].start < cursor - EPSILON && notes[it].end > cursor + EPSILON
                        }
                        onsetFloor = cursor
                        callbacks.releaseAll(); paused = true
                    }
                    if (pauseFlag.get() || interactionFlag.get()) {
                        LockSupport.parkNanos(5_000_000L)
                        continue
                    }
                }
                if (paused) { reference = now() - cursor; paused = false }
                if (cursor >= total - EPSILON) break
                val actual = maxOf(cursor, now() - reference).coerceAtMost(total)
                if (actual > cursor + .080 + EPSILON) { cursor = actual }
                if (cursor >= total - EPSILON) break
                val frame = planner.frame(cursor, actual, onsetFloor, resumedNoteIds)
                val frameVersion = inputChangeVersion
                if (frame.batch.keys.isNotEmpty()) {
                    if (callbacks.dispatch(frame.batch)) dispatchedNoteIds = frame.batch.noteIds.toSet()
                    else interruptedFrame = true
                } else {
                    callbacks.releaseAll(); dispatchedNoteIds = emptySet()
                }
                while (!inputSuspended && !interruptedFrame && frameVersion == inputChangeVersion &&
                    now() < reference + frame.end) {
                    playTime = (now() - reference).coerceIn(cursor, frame.end)
                    progress()
                    LockSupport.parkNanos(2_000_000L)
                }
                // 暂停/取消可能发生在预排帧中间，尚未开始的起音不能被这 80ms 一并跳过。
                interruptedFrame = interruptedFrame || frameVersion != inputChangeVersion
                cursor = if (inputSuspended || interruptedFrame)
                    (now() - reference).coerceIn(actual, frame.end) else frame.end
                playTime = cursor
                progress()
            }
            completed = !stopFlag.get() && cursor >= total - EPSILON
            if (completed) { playTime = total; progress() }
        } catch (error: Exception) {
            callbacks.log("演奏停止：${error.message}")
        } finally {
            try { callbacks.releaseAll() } finally { inputLock.unlock(); callbacks.onFinish(completed) }
        }
    }
}
