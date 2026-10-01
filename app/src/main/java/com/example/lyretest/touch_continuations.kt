package com.example.lyretest.player

/** 与 Android 对象分离的续接计划，测试可以验证真实触点身份和路径端点。 */
data class TouchPoint(val x: Float, val y: Float)
data class TouchStroke<S>(val previous: S?, val from: TouchPoint, val to: TouchPoint,
    val offsetMs: Long, val durationMs: Long, val continues: Boolean)
data class HeldTouch<S>(val stroke: S, val key: String, val base: TouchPoint, val end: TouchPoint)
data class PreparedTouch<S>(val strokes: List<S>, val held: Map<Int, HeldTouch<S>>, val durationMs: Long,
    val pointerCount: Int = 0, val requiresRestart: Boolean = false)

class TouchContinuations<S>(private val maxStrokes: Int = REQUESTED_TOUCH_LIMIT,
    private val create: (TouchStroke<S>) -> S) {
    init { require(maxStrokes > 0) }
    private var held = emptyMap<Int, HeldTouch<S>>()
    private var pointerCount = 0

    /** 只在系统完成回调后提交；被取消的手势不能成为下一次续接的父节点。 */
    fun commit(prepared: PreparedTouch<S>) { held = prepared.held; pointerCount = prepared.pointerCount }
    fun clear() { held = emptyMap(); pointerCount = 0 }

    fun endingBatch(keep: Set<Int> = emptySet()): TapBatch? {
        if (held.isEmpty()) return null
        return TapBatch(held.values.map { it.key }, 1, noteIds = held.keys.toList(),
            continues = held.keys.map { it in keep })
    }

    /** 过期音的终结也占 stroke；极密集帧先用 1ms 释放过期音并续接其余音。 */
    fun needsBridge(batch: TapBatch) = (held.keys + batch.noteIds).size > maxStrokes

    /** 重建先抬起所有旧点，旧音的 1ms 终结项已完成，不能再当新音按下。 */
    fun batchAfterRestartRelease(batch: TapBatch): TapBatch {
        val keep = batch.keys.indices.filterNot { i ->
            batch.noteIds[i] in held && batch.offsetsMs[i] == 0L &&
                batch.durationsMs[i] == 1L && !batch.continues[i]
        }
        return batch.copy(keys = keep.map { batch.keys[it] },
            offsetsMs = keep.map { batch.offsetsMs[it] },
            durationsMs = keep.map { batch.durationsMs[it] },
            noteIds = keep.map { batch.noteIds[it] },
            continues = keep.map { batch.continues[it] })
    }

    fun prepare(batch: TapBatch, points: Map<String, TouchPoint>): PreparedTouch<S> {
        require(batch.noteIds.distinct().size == batch.noteIds.size)
        val missing = held.keys - batch.noteIds.toSet()
        require(batch.keys.size + missing.size <= maxStrokes) { "触点超出本次手势的 $maxStrokes 点预算" }
        val next = linkedMapOf<Int, HeldTouch<S>>()
        val strokes = mutableListOf<S>()
        // 所有手指抬起前，Android 仍保留旧指针映射；自然空隙才重置预算。
        val edges = mutableListOf<Triple<Long, Boolean, Int>>()
        var duration = 1L
        fun add(id: Int, key: String, offset: Long, length: Long, more: Boolean) {
            val old = held[id]
            require(old == null || old.key == key) { "续接音符的琴键发生变化" }
            val base = old?.base ?: requireNotNull(points[key]) { "琴键 $key 未校准" }
            val from = old?.end ?: base
            // 静止续接没有 DOWN/UP/MOVE，AOSP 会以空事件取消它。
            // 在同一琴键内来回移动 1 个物理像素，保持同一个触点，不重新按键。
            val to = if (more) {
                if (from == base) TouchPoint(base.x + 1f, base.y) else base
            } else from
            // AOSP 要求续接帧第 0ms 只有旧触点；新触点最早第 1ms 加入。
            val start = if (old != null) 0L else if (held.isNotEmpty()) maxOf(1L, offset) else offset
            val hold = (offset + length - start).coerceAtLeast(1L)
            val stroke = create(TouchStroke(old?.stroke, from, to, start, hold, more))
            if (old == null) edges.add(Triple(start, true, id))
            if (!more) edges.add(Triple(start + hold, false, id))
            strokes.add(stroke)
            duration = maxOf(duration, start + hold)
            if (more) next[id] = HeldTouch(stroke, key, base, to)
        }
        for (id in missing) add(id, held.getValue(id).key, 0, 1, false)
        batch.keys.indices.forEach { i ->
            add(batch.noteIds[i], batch.keys[i], batch.offsetsMs[i], batch.durationsMs[i], batch.continues[i])
        }
        var count = pointerCount
        var restart = false
        val active = held.keys.toMutableSet()
        for ((_, down, id) in edges.sortedWith(compareBy({ it.first }, { it.second }))) {
            if (down) {
                if (active.isEmpty()) count = 0
                count++
                if (count > maxStrokes) restart = true
                active.add(id)
            } else {
                active.remove(id)
                if (active.isEmpty()) count = 0
            }
        }
        return PreparedTouch(strokes, next, duration, count, restart)
    }
}
