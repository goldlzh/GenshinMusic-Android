package com.example.lyretest.arrange

import com.example.lyretest.player.REQUESTED_TOUCH_LIMIT
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

val DURATION_MODES = linkedMapOf("fixed" to "固定短按", "score" to "原谱音长",
    "melody" to "旋律长音", "chord" to "和弦长音")
const val EPSILON = 0.0000001
const val KEY_HOLD = 0.040
const val RETRIGGER_GAP = 0.010

data class ScheduledNote(val start: Double, val end: Double, val key: String, val melody: Boolean)

fun sustainedFlags(tagged: List<TaggedNote>, mode: String, transpose: Int, fold: Boolean): List<Boolean> {
    if (mode == "score") return tagged.map { true }
    if (mode == "melody") return tagged.map { it.isMelody }
    val flags = MutableList(tagged.size) { false }
    if (mode != "chord") return flags
    val pitches = tagged.map { mapNote(it.note + transpose, fold) }
    val acc = tagged.indices.filter { !tagged[it].isMelody && pitches[it] != null }.sortedBy { tagged[it].time }
    val melody = tagged.indices.filter { tagged[it].isMelody && pitches[it] != null }.sortedBy { tagged[it].time }
    fun lowerBound(time: Double): Int {
        var lo = 0; var hi = melody.size
        while (lo < hi) { val mid = (lo + hi) / 2; if (tagged[melody[mid]].time < time) lo = mid + 1 else hi = mid }
        return lo
    }
    var i = 0
    while (i < acc.size) {
        val first = tagged[acc[i]].time
        var j = i + 1
        while (j < acc.size && tagged[acc[j]].time - first <= .015 + EPSILON) j++
        val group = acc.subList(i, j)
        if (group.map { pitches[it] }.toSet().size >= 2) {
            val last = tagged[group.last()].time
            val melodyKeys = mutableSetOf<Int?>()
            var k = lowerBound(first - .015 - EPSILON)
            while (k < melody.size && tagged[melody[k]].time <= last + .015 + EPSILON) melodyKeys.add(pitches[melody[k++]])
            group.forEach { flags[it] = pitches[it] !in melodyKeys }
        }
        i = j
    }
    return flags
}

/** 与电脑版一致：整体变速、同键截断、10ms 重触发；最后才压缩真正的空白。 */
fun buildSchedule(chords: List<Chord>, speed: Double = 1.0): List<ScheduledNote> {
    require(speed.isFinite() && speed > 0)
    val notes = chords.flatMap { chord -> chord.keys.map { key ->
        val start = (chord.time + key.offset) / speed
        val length = if (key.sustained) key.duration / speed else KEY_HOLD
        ScheduledNote(start, start + max(KEY_HOLD, length), key.key, key.isMelody)
    } }.sortedBy { it.start }
    val byKey = linkedMapOf<String, MutableList<ScheduledNote>>()
    for (note in notes) {
        val previous = byKey.getOrPut(note.key) { mutableListOf() }
        val old = previous.lastOrNull()
        if (old != null && abs(note.start - old.start) <= EPSILON) {
            previous[previous.lastIndex] = old.copy(end = max(old.end, note.end), melody = old.melody || note.melody)
            continue
        }
        if (old != null) {
            if (old.melody && !note.melody && note.start < old.end - EPSILON) continue
            val end = min(old.end, note.start - RETRIGGER_GAP)
            if (end <= old.start + EPSILON) {
                if (old.melody && !note.melody) continue
                previous.removeAt(previous.lastIndex)
            } else previous[previous.lastIndex] = old.copy(end = end)
        }
        previous.add(note)
    }
    val resolved = byKey.values.flatten().sortedWith(compareBy({ it.start }, { it.key }))
    if (resolved.isEmpty()) return emptyList()
    var shift = resolved.first().start
    var soundingUntil = shift
    val result = ArrayList<ScheduledNote>()
    for (note in resolved) {
        val gap = note.start - soundingUntil
        if (gap > 1.0 / speed) shift += gap - 1.0 / speed
        result.add(note.copy(start = note.start - shift, end = note.end - shift))
        soundingUntil = max(soundingUntil, note.end)
    }
    return result
}

fun performanceDuration(chords: List<Chord>, speed: Double = 1.0): Double =
    buildSchedule(chords, speed).maxOfOrNull { it.end } ?: 0.0

/** 触点满时先结束最早起音且仍按住的音，再接纳新音；同刻按输入顺序处理。 */
fun limitPolyphony(notes: List<ScheduledNote>, limit: Int = REQUESTED_TOUCH_LIMIT): List<ScheduledNote> {
    require(limit > 0)
    val result = mutableListOf<ScheduledNote?>()
    val active = mutableListOf<Int>()
    for (note in notes.sortedBy { it.start }) {
        active.removeAll { result[it]!!.end <= note.start + EPSILON }
        if (active.size >= limit) {
            val victim = active.removeAt(0)
            val old = result[victim]!!
            if (note.start > old.start + EPSILON) result[victim] = old.copy(end = note.start)
            else result[victim] = null
        }
        active.add(result.size)
        result.add(note)
    }
    return result.filterNotNull().sortedWith(compareBy({ it.start }, { it.key }))
}
