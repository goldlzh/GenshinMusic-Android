package com.example.lyretest.midi

import com.example.lyretest.player.TapBatch
import com.example.lyretest.player.REQUESTED_TOUCH_LIMIT
import kotlin.math.roundToLong

data class LiveMidiMessage(val status: Int, val data1: Int = 0, val data2: Int = 0)

/** MIDI 松键、踏板和折叠同键的独立所有权；一个手势结束时只松开已经结束的触点。 */
class LiveTouchState(private val transpose: Int = 0, private val fold: Boolean = true,
    private val followRelease: Boolean = true, private val sustain: Boolean = true,
    private val mergeWindowMs: Long = 0, private val maxStrokes: Int = REQUESTED_TOUCH_LIMIT) {
    init { require(mergeWindowMs in 0..200); require(maxStrokes > 0) }
    private data class Voice(val key: String, var held: Boolean, var until: Double,
        val ready: Double, val order: Long, var triggered: Boolean = false,
        var suppressed: Boolean = false)
    private data class Contact(val id: Int, val start: Double)
    private val voices = mutableMapOf<Pair<Int, Int>, MutableList<Voice>>()
    private val pedal = mutableSetOf<Int>()
    private val contacts = linkedMapOf<String, Contact>()
    private val blockedUntil = mutableMapOf<String, Double>()
    private var nextId = 1
    private var nextOrder = 0L
    private var mergeDeadline: Double? = null
    val hasContacts get() = contacts.isNotEmpty()

    fun feed(message: LiveMidiMessage, now: Double) {
        val channel = message.status and 15
        if (message.status in listOf(0xFF, 0xFC)) { voices.clear(); pedal.clear(); mergeDeadline = null; return }
        if (channel == DRUM_CHANNEL) return
        val source = channel to message.data1
        when (message.status and 0xF0) {
            0x90 -> if (message.data2 > 0) {
                val key = mapLiveNote(message.data1, transpose, fold) ?: return
                if (mergeDeadline == null || now > mergeDeadline!! + 1e-9)
                    mergeDeadline = now + mergeWindowMs / 1000.0
                val ready = maxOf(now, mergeDeadline!!)
                val row = voices.getOrPut(source) { mutableListOf() }
                row.removeAll { !it.held && (it.triggered || it.suppressed) }
                row.add(Voice(key, true, if (followRelease) Double.POSITIVE_INFINITY else ready + .04,
                    ready, nextOrder++))
            } else release(source, now)
            0x80 -> release(source, now)
            0xB0 -> when (message.data1) {
                64 -> if (message.data2 >= 64) pedal.add(channel) else {
                    pedal.remove(channel); releasePedal(channel, now)
                }
                120, 123 -> { voices.keys.filter { it.first == channel }.forEach { voices.remove(it) }; pedal.remove(channel) }
                121 -> { pedal.remove(channel); releasePedal(channel, now) }
            }
        }
    }
    private fun release(source: Pair<Int, Int>, now: Double) {
        val voice = voices[source]?.firstOrNull { it.held } ?: return
        voice.held = false
        if (voice.suppressed) return // 被挤出的旧 note_on 仍占据它自己的 FIFO 松键位置。
        if (followRelease && !(sustain && source.first in pedal))
            voice.until = maxOf(now, voice.ready + .04, (contacts[voice.key]?.start ?: voice.ready) + .04)
    }
    private fun releasePedal(channel: Int, now: Double) {
        voices.filterKeys { it.first == channel }.values.flatten().filter { !it.held && !it.suppressed }.forEach {
            it.until = minOf(it.until, maxOf(now, it.ready + .04, (contacts[it.key]?.start ?: it.ready) + .04))
        }
    }
    fun frame(now: Double, sliceMs: Long = 20): TapBatch? {
        voices.values.forEach { row ->
            // 固定短按和抢占都可能早于真人松键结束；保留 held 记录，避免旧 note_off 松掉后来的同音。
            row.forEach { if (it.triggered && it.until <= now) it.suppressed = true }
            row.removeAll { it.suppressed && !it.held }
        }
        voices.entries.removeAll { it.value.isEmpty() }
        val active = voices.values.flatten().filter { !it.suppressed && it.ready <= now + 1e-9 }
            .sortedWith(compareBy({ it.ready }, { it.order }))
        val byKey = active.groupBy { it.key }
        val retrigger = active.filter { !it.triggered && contacts[it.key]?.let { old -> now - old.start > .015 } == true }
            .mapTo(mutableSetOf()) { it.key }
        val ending = contacts.keys.filter { it !in byKey || it in retrigger }
        if (ending.isNotEmpty()) return releaseBridge(ending.toSet(), now)

        // 多通道/折叠同键只占一个触点；同一短起音内新增的所有者不再次抢占触点。
        active.filter { !it.triggered && it.key in contacts }.forEach { trigger(it, now) }
        val waiting = byKey.keys.filter { it !in contacts && now >= (blockedUntil[it] ?: 0.0) }
            .sortedWith(compareBy({ key -> byKey.getValue(key).firstOrNull { !it.triggered }?.ready
                ?: byKey.getValue(key).first().ready },
                { key -> byKey.getValue(key).firstOrNull { !it.triggered }?.order
                    ?: byKey.getValue(key).first().order }))
        if (contacts.size == maxStrokes && waiting.isNotEmpty()) {
            val victims = contacts.entries.sortedWith(compareBy({ it.value.start }, { it.value.id }))
                .take(minOf(waiting.size, maxStrokes)).mapTo(mutableSetOf()) { it.key }
            // 只淘汰已经输出的触点所有者；尚在合并窗口内的新 note_on 保持独立的待起音身份。
            voices.values.flatten().filter { it.key in victims && (it.triggered || it.ready <= now + 1e-9) }
                .forEach { it.suppressed = true }
            return releaseBridge(victims, now)
        }
        // 本帧刚接纳的新音尚未实际输出，不能在同一次规划中再次充当“最老触点”被抢占。
        for (key in waiting.take(maxStrokes - contacts.size)) {
            byKey.getValue(key).filter { !it.triggered }.forEach { trigger(it, now) }
            byKey.getValue(key).forEach { it.until = maxOf(it.until, now + .04) }
            contacts[key] = Contact(nextId++, now)
        }
        val pendingTime = voices.values.flatten().filter { !it.suppressed && !it.triggered }.minOfOrNull { it.ready }
        val slice = pendingTime?.let { ((it - now) * 1000).roundToLong().coerceIn(1L, sliceMs) } ?: sliceMs
        val keys = mutableListOf<String>(); val ids = mutableListOf<Int>()
        val durations = mutableListOf<Long>(); val continues = mutableListOf<Boolean>()
        for ((key, contact) in contacts.toMap()) {
            val until = byKey.getValue(key).maxOf { it.until }
            val duration = if (until.isFinite()) ((until - now) * 1000).roundToLong().coerceIn(1L, slice) else slice
            val more = until > now + duration / 1000.0 + .0000001
            keys.add(key); ids.add(contact.id); durations.add(duration); continues.add(more)
            if (!more) contacts.remove(key)
        }
        return if (keys.isEmpty()) null else TapBatch(keys, sliceMs, durationsMs = durations, noteIds = ids, continues = continues)
    }
    private fun trigger(voice: Voice, now: Double) {
        voice.triggered = true
        voice.until = maxOf(voice.until, now + .04)
    }
    /** 终结也占 stroke；先用 1ms 释放旧音并续接其余音，下一批才为新音分配触点。 */
    private fun releaseBridge(ending: Set<String>, now: Double): TapBatch {
        val old = contacts.toMap()
        ending.forEach { contacts.remove(it); blockedUntil[it] = now + .011 }
        return TapBatch(old.keys.toList(), 1L, noteIds = old.values.map { it.id },
            continues = old.keys.map { it !in ending })
    }
    fun clear() { voices.clear(); pedal.clear(); contacts.clear(); blockedUntil.clear(); mergeDeadline = null }
}
