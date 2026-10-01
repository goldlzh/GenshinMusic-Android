package com.example.lyretest.midi

import java.io.EOFException
import java.io.InputStream

/**
 * 标准 MIDI 文件（SMF）解析器。
 *
 * Android 上没有 mido / javax.sound.midi，这里自己解析二进制。
 * 提取 note_on / note_off、速度、音色与 CC64 / CC120 / CC121 / CC123。
 */

/** 一个发音事件 */
data class NoteEvent(
    /** 绝对时间（秒） */
    val time: Double,
    /** MIDI 音高，中央 C = 60 */
    val note: Int,
    /** MIDI 通道 0..15 */
    val channel: Int,
    /** 所属物理音轨下标 */
    val track: Int,
    val end: Double = time + .25,
    val pedalEnd: Double = end
)

/** 单个通道的乐器信息 */
data class ChannelInfo(
    val channel: Int,
    val programs: Set<Int>,
    val noteCount: Int
)

data class MidiFileData(
    val trackCount: Int,
    /** 按时间排序的全部 note_on（不含鼓组通道） */
    val notes: List<NoteEvent>,
    val channels: List<ChannelInfo>,
    /** 鼓组（通道 9）音符数，已从 [notes] 中排除 */
    val drumNotes: Int
) {
    /** 含音符的物理音轨下标 */
    val noteBearingTracks: List<Int> get() = notes.map { it.track }.distinct().sorted()
}

/** MIDI 通道 9 固定是鼓组/打击乐，演奏时排除 */
const val DRUM_CHANNEL = 9

private const val DEFAULT_TEMPO = 500_000  // 微秒/四分音符，即 120 BPM

class MidiParseException(message: String) : Exception(message)

/** 顺序读取字节流的小工具 */
private class ByteReader(private val buf: ByteArray) {
    var pos = 0
        private set

    val remaining: Int get() = buf.size - pos

    fun u8(): Int {
        if (pos >= buf.size) throw EOFException("MIDI 数据意外结束")
        return buf[pos++].toInt() and 0xFF
    }

    fun u16(): Int = (u8() shl 8) or u8()

    fun u32(): Long {
        var v = 0L
        repeat(4) { v = (v shl 8) or u8().toLong() }
        return v
    }

    fun bytes(n: Int): ByteArray {
        if (n < 0 || pos + n > buf.size) throw EOFException("MIDI 数据意外结束")
        return buf.copyOfRange(pos, pos + n).also { pos += n }
    }

    fun skip(n: Int) {
        pos = (pos + n).coerceAtMost(buf.size)
    }

    /** 退回一个字节，用于 running status 判断后重读数据字节 */
    fun pushBack() {
        if (pos > 0) pos--
    }

    /** SMF 变长数值（每字节 7 位，最高位表示后续还有字节） */
    fun varLen(): Int {
        var v = 0
        var count = 0
        while (true) {
            val b = u8()
            v = (v shl 7) or (b and 0x7F)
            if (b and 0x80 == 0) return v
            if (++count > 4) throw MidiParseException("变长数值格式错误")
        }
    }

    fun ascii(n: Int): String = String(bytes(n), Charsets.US_ASCII)
}

/** 解析过程中的中间事件，时间还是 tick */
private sealed class RawEvent {
    abstract val tick: Long

    data class NoteOn(
        override val tick: Long, val note: Int, val channel: Int, val track: Int
    ) : RawEvent()

    data class Tempo(override val tick: Long, val usPerBeat: Int) : RawEvent()
    data class NoteOff(override val tick: Long, val note: Int, val channel: Int, val track: Int) : RawEvent()
    data class Control(override val tick: Long, val channel: Int, val control: Int, val value: Int) : RawEvent()
    data class End(override val tick: Long) : RawEvent()

    data class Program(
        override val tick: Long, val channel: Int, val program: Int
    ) : RawEvent()
}

/** tempo 变化表：(起始 tick, 微秒/四分音符)，保证首项 tick = 0 */
private class TempoMap(entries: List<Pair<Long, Int>>, private val ticksPerBeat: Int) {

    private val ticks: LongArray
    private val tempos: IntArray

    /** 每段起点对应的绝对秒数，避免每次换算都从头累加 */
    private val secondsAt: DoubleArray

    init {
        val sorted = entries.sortedBy { it.first }.toMutableList()
        if (sorted.isEmpty() || sorted[0].first != 0L) {
            sorted.add(0, 0L to DEFAULT_TEMPO)
        }
        ticks = LongArray(sorted.size) { sorted[it].first }
        tempos = IntArray(sorted.size) { sorted[it].second }
        secondsAt = DoubleArray(sorted.size)
        for (i in 1 until sorted.size) {
            secondsAt[i] = secondsAt[i - 1] +
                    tickToSec(ticks[i] - ticks[i - 1], tempos[i - 1])
        }
    }

    private fun tickToSec(deltaTicks: Long, usPerBeat: Int): Double =
        deltaTicks.toDouble() * usPerBeat / ticksPerBeat / 1_000_000.0

    fun toSeconds(targetTick: Long): Double {
        // 找到 targetTick 落在哪一段（二分）
        var lo = 0
        var hi = ticks.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (ticks[mid] <= targetTick) lo = mid else hi = mid - 1
        }
        return secondsAt[lo] + tickToSec(targetTick - ticks[lo], tempos[lo])
    }
}

object MidiParser {

    /**
     * 解析单个 MTrk 块，把感兴趣的事件按绝对 tick 追加到 [out]。
     * 处理 running status：状态字节可省略，沿用上一个事件的。
     */
    private fun readTrack(chunk: ByteArray, trackIndex: Int, out: MutableList<RawEvent>) {
        val r = ByteReader(chunk)
        var tick = 0L
        var runningStatus = 0

        while (r.remaining > 0) {
            tick += r.varLen()
            var status = r.u8()
            if (status < 0x80) {
                // running status：这个字节其实是第一个数据字节
                if (runningStatus == 0) throw MidiParseException("音轨数据损坏：缺少状态字节")
                r.pushBack()
                status = runningStatus
            } else if (status < 0xF0) {
                runningStatus = status
            }

            when (status) {
                0xFF -> {  // meta 事件
                    val type = r.u8()
                    val len = r.varLen()
                    if (type == 0x51 && len == 3) {
                        val us = (r.u8() shl 16) or (r.u8() shl 8) or r.u8()
                        out.add(RawEvent.Tempo(tick, us))
                    } else {
                        r.skip(len)
                    }
                    if (type == 0x2F) { out.add(RawEvent.End(tick)); return }  // end of track
                }
                0xF0, 0xF7 -> r.skip(r.varLen())  // SysEx，整段跳过
                else -> {
                    val cmd = status and 0xF0
                    val ch = status and 0x0F
                    when (cmd) {
                        0x90 -> {  // note on，velocity 0 视为 note off
                            val note = r.u8()
                            val vel = r.u8()
                            if (vel > 0) out.add(RawEvent.NoteOn(tick, note, ch, trackIndex))
                            else out.add(RawEvent.NoteOff(tick, note, ch, trackIndex))
                        }
                        0x80 -> { val note = r.u8(); r.u8(); out.add(RawEvent.NoteOff(tick, note, ch, trackIndex)) }
                        0xB0 -> out.add(RawEvent.Control(tick, ch, r.u8(), r.u8()))
                        0xA0, 0xE0 -> r.skip(2)  // note off / 触后 / 控制器 / 弯音
                        0xC0 -> out.add(RawEvent.Program(tick, ch, r.u8()))
                        0xD0 -> r.skip(1)  // 通道触后
                        else -> throw MidiParseException(
                            "未知的 MIDI 事件类型 0x${status.toString(16)}"
                        )
                    }
                }
            }
        }
    }

    fun parse(stream: InputStream): MidiFileData = parse(stream.readBytes())

    fun parse(data: ByteArray): MidiFileData {
        val r = ByteReader(data)

        if (r.remaining < 14) throw MidiParseException("文件太小，不是有效的 MIDI 文件")
        if (r.ascii(4) != "MThd") throw MidiParseException("缺少 MThd 头，不是有效的 MIDI 文件")
        val headerLen = r.u32()
        val format = r.u16()
        val declaredTracks = r.u16()
        val division = r.u16()
        // 头块长度理论上是 6，多出来的按规范跳过
        if (headerLen > 6) r.skip((headerLen - 6).toInt())

        if (format == 2) {
            throw MidiParseException("不支持 format 2（每音轨独立乐曲）的 MIDI 文件")
        }
        if (division and 0x8000 != 0) {
            throw MidiParseException("不支持 SMPTE 时间码格式的 MIDI 文件")
        }
        val ticksPerBeat = if (division == 0) 480 else division

        // 逐块读取，只处理 MTrk，其他块按长度跳过
        val events = mutableListOf<RawEvent>()
        var trackIndex = 0
        while (r.remaining >= 8) {
            val id = r.ascii(4)
            val len = r.u32().toInt()
            if (id != "MTrk") {
                r.skip(len)
                continue
            }
            val chunk = r.bytes(len.coerceAtMost(r.remaining))
            readTrack(chunk, trackIndex, events)
            trackIndex++
        }
        if (trackIndex == 0) throw MidiParseException("文件中没有找到任何音轨")

        val tempoMap = TempoMap(
            events.filterIsInstance<RawEvent.Tempo>().map { it.tick to it.usPerBeat },
            ticksPerBeat
        )

        data class OpenNote(val start: Double, val pitch: Int, val channel: Int, val track: Int,
                            var end: Double? = null, var pedalEnd: Double = 0.0)
        val records = mutableListOf<OpenNote>()
        val active = mutableMapOf<Pair<Int, Int>, MutableList<OpenNote>>()
        val held = mutableMapOf<Int, MutableList<OpenNote>>()
        val pedal = mutableSetOf<Int>()
        var drumNotes = 0
        fun releasePedal(channel: Int, whenAt: Double) {
            held.remove(channel)?.forEach { it.pedalEnd = maxOf(it.end ?: whenAt, whenAt) }
        }
        fun finish(note: OpenNote, whenAt: Double, respectPedal: Boolean = true) {
            note.end = maxOf(note.start, whenAt)
            note.pedalEnd = note.end!!
            if (respectPedal && note.channel in pedal) held.getOrPut(note.channel) { mutableListOf() }.add(note)
        }
        for (event in events.sortedBy { it.tick }) {
            val whenAt = tempoMap.toSeconds(event.tick)
            when (event) {
                is RawEvent.NoteOn -> {
                    if (event.channel == DRUM_CHANNEL) { drumNotes++; continue }
                    val note = OpenNote(whenAt, event.note, event.channel, event.track)
                    records.add(note)
                    active.getOrPut(event.channel to event.note) { mutableListOf() }.add(note)
                }
                is RawEvent.NoteOff -> {
                    val pending = active[event.channel to event.note]
                    if (!pending.isNullOrEmpty()) {
                        val index = pending.indexOfFirst { it.track == event.track }.coerceAtLeast(0)
                        finish(pending.removeAt(index), whenAt)
                    }
                }
                is RawEvent.Control -> when (event.control) {
                    64 -> if (event.value >= 64) pedal.add(event.channel) else {
                        pedal.remove(event.channel); releasePedal(event.channel, whenAt)
                    }
                    120, 123 -> {
                        active.filterKeys { it.first == event.channel }.values.forEach { pending ->
                            pending.forEach { finish(it, whenAt, event.control != 120) }; pending.clear()
                        }
                        if (event.control == 120) releasePedal(event.channel, whenAt)
                    }
                    121 -> { pedal.remove(event.channel); releasePedal(event.channel, whenAt) }
                    else -> Unit
                }
                else -> Unit
            }
        }
        val fileEnd = tempoMap.toSeconds(events.maxOfOrNull { it.tick } ?: 0L)
        held.keys.toList().forEach { releasePedal(it, fileEnd) }
        val notes = records.map { note ->
            val end = note.end ?: (note.start + .25)
            NoteEvent(note.start, note.pitch, note.channel, note.track, end, maxOf(end, note.pedalEnd))
        }.sortedBy { it.time }

        return MidiFileData(
            trackCount = trackIndex,
            notes = notes,
            channels = buildChannelInfo(events),
            drumNotes = drumNotes
        )
    }

    /** 按合并时间轴首次发声顺序统计通道，音色按 program_change 的时间先后归属。 */
    private fun buildChannelInfo(events: List<RawEvent>): List<ChannelInfo> {
        val programs = HashMap<Int, MutableSet<Int>>()
        val counts = linkedMapOf<Int, Int>()
        val current = HashMap<Int, Int>()
        for (e in events.sortedBy { it.tick }) {
            when (e) {
                is RawEvent.Program -> current[e.channel] = e.program
                is RawEvent.NoteOn -> {
                    if (e.channel == DRUM_CHANNEL) continue
                    counts[e.channel] = (counts[e.channel] ?: 0) + 1
                    programs.getOrPut(e.channel) { mutableSetOf() }
                        .add(current[e.channel] ?: 0)
                }
                else -> {}
            }
        }
        return counts.keys.map {
            ChannelInfo(it, programs[it] ?: setOf(0), counts[it] ?: 0)
        }
    }
}
