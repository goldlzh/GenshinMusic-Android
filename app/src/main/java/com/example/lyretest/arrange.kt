package com.example.lyretest.arrange

import com.example.lyretest.midi.ChannelInfo
import com.example.lyretest.midi.MidiFileData
import com.example.lyretest.midi.NoteEvent

/**
 * 编曲流水线：MIDI 音符 → 可弹奏的琴键发音组。
 * 与电脑版 midi_engine.py 通过同一组乐谱验证起止时间。
 * 旋律与扫音筛选 → 按折叠开关推荐转调 → 映射 → 四种音长 → 变速与同键冲突 → 真正空白压缩。
 */

/** 微调参数，默认值和电脑版一致 */
data class ArrangeParams(
    /** 和弦分组窗口：这个时间内的音符算同一个发音组 */
    val chordWindow: Double = 0.080,
    /** 天际线取旋律的窗口 */
    val skylineWindow: Double = 0.015,
    /** 自动保留音符数最多的前几个通道 */
    val autoKeepChannels: Int = 3,
    /** 扫音判定：连续音数下限 */
    val glissMinLen: Int = 6,
    /** 扫音判定：相邻音最大间隔 */
    val glissMaxGap: Double = 0.060,
    /** 扫音判定：相邻音最大半音跨度 */
    val glissMaxStep: Int = 2
)

/** 带主旋律标记的音符 */
data class TaggedNote(val time: Double, val note: Int, val isMelody: Boolean,
    val end: Double = time + .25, val pedalEnd: Double = end)

/** 发音组里的一个键 */
data class ChordKey(
    /** 琴键名，如 "Q" */
    val key: String,
    /** 相对发音组起始时间的偏移（秒） */
    val offset: Double,
    val isMelody: Boolean,
    val duration: Double = KEY_HOLD,
    val sustained: Boolean = false
)

/** 一个发音组：同一时刻（窗口内）要按下的键 */
data class Chord(val time: Double, val keys: List<ChordKey>)

/**
 * 原神乐器 21 键映射：MIDI 音高 → 琴键名。
 * 低音 ZXCVBNM / 中音 ASDFGHJ / 高音 QWERTYU，只有白键。
 */
val NOTE_MAP: Map<Int, String> = mapOf(
    48 to "Z", 50 to "X", 52 to "C", 53 to "V", 55 to "B", 57 to "N", 59 to "M",
    60 to "A", 62 to "S", 64 to "D", 65 to "F", 67 to "G", 69 to "H", 71 to "J",
    72 to "Q", 74 to "W", 76 to "E", 77 to "R", 79 to "T", 81 to "Y", 83 to "U"
)

private val WHITE_KEYS: Set<Int> = NOTE_MAP.keys
private val LOW_KEY = NOTE_MAP.keys.min()
private val HIGH_KEY = NOTE_MAP.keys.max()

/** 超出音域的音符按八度折进来，落在黑键上则返回 null */
fun foldToRange(note: Int): Int? {
    var n = note
    while (n > HIGH_KEY) n -= 12
    while (n < LOW_KEY) n += 12
    return if (n in NOTE_MAP) n else null
}

/** 把音符映射到可弹音域，[enableFold] 允许八度折叠 */
fun mapNote(note: Int, enableFold: Boolean): Int? = when {
    note in NOTE_MAP -> note
    enableFold -> foldToRange(note)
    else -> null
}

// ==================== 通道筛选 ====================

/** 音符数优先；并列时保持解析器提供的首次发声顺序，与电脑版一致。 */
fun autoSelectChannels(channels: List<ChannelInfo>, maxKeep: Int): Set<Int> =
    channels.sortedByDescending { it.noteCount }.take(maxKeep).map { it.channel }.toSet()

fun filterByChannels(notes: List<NoteEvent>, keep: Set<Int>): List<NoteEvent> =
    notes.filter { it.channel in keep }

// ==================== 主旋律识别 ====================

/**
 * 天际线：每 [window] 秒的窗口内取最高音作为主旋律。
 * 用于单音轨曲目——所有声部混在一条轨里，只能靠音高区分。
 */
fun skylineTag(notes: List<NoteEvent>, window: Double): List<TaggedNote> {
    val result = ArrayList<TaggedNote>(notes.size)
    var i = 0
    while (i < notes.size) {
        val t0 = notes[i].time
        var j = i
        while (j < notes.size && notes[j].time - t0 <= window) j++
        val group = notes.subList(i, j)
        val maxPitch = group.maxOf { it.note }
        var melodyMarked = false
        for (n in group) {
            val isMel = n.note == maxPitch && !melodyMarked
            if (isMel) melodyMarked = true
            result.add(TaggedNote(n.time, n.note, isMel, n.end, n.pedalEnd))
        }
        i = j
    }
    return result
}

/** 主旋律识别结果 */
data class MelodyResult(val tagged: List<TaggedNote>, val description: String)

/**
 * 标记主旋律。多音轨时整条轨作为旋律（默认取平均音高最高的一轨，
 * 也可以用 [forcedTrack] 手动指定）；单音轨时退回天际线。
 */
fun detectMelody(
    notes: List<NoteEvent>,
    params: ArrangeParams,
    forcedTrack: Int? = null
): MelodyResult {
    val tracks = notes.groupBy { it.track }.toSortedMap()
    if (tracks.size >= 2) {
        // 通道筛选可能移除原先指定的轨；与电脑版一样从剩余音轨重新自动识别。
        val survivingForcedTrack = forcedTrack?.takeIf { it in tracks }
        val melodyTrack = survivingForcedTrack
            ?: tracks.maxBy { entry -> entry.value.sumOf { it.note }.toDouble() / entry.value.size }.key
        val desc = if (survivingForcedTrack != null)
            "多音轨模式：手动指定音轨 $melodyTrack 作为主旋律"
        else
            "多音轨模式：自动选择音轨 $melodyTrack 作为主旋律"
        val tagged = notes
            .map { TaggedNote(it.time, it.note, it.track == melodyTrack, it.end, it.pedalEnd) }
            .sortedBy { it.time }
        return MelodyResult(tagged, desc)
    }
    val single = notes.sortedBy { it.time }
    return MelodyResult(
        skylineTag(single, params.skylineWindow),
        "单音轨模式：天际线（${(params.skylineWindow * 1000).toInt()}ms 窗口最高音）作为主旋律"
    )
}

// ==================== 自动转调 ====================

/**
 * 在 -12..+12 半音内找最优转调量：优先让主旋律尽量多地落在白键上，
 * 其次照顾伴奏。主旋律一个都对不上时返回 0（说明这曲子本来就不适配）。
 */
fun findBestTranspose(tagged: List<TaggedNote>, enableFold: Boolean = false): Int {
    if (tagged.isEmpty()) return 0
    val melodyLine = skylineTag(tagged.filter { it.isMelody }.map {
        NoteEvent(it.time, it.note, 0, 0, it.end, it.pedalEnd)
    }.sortedBy { it.time }, .015).filter { it.isMelody }
    var bestOffset = 0
    var bestScore: List<Int>? = null
    for (offset in -12..12) {
        val (mel, acc) = countPlayable(tagged, offset, enableFold)
        var contour = 0
        var folding = 0
        var collisions = 0
        var previous: Pair<Int, Int>? = null
        for (note in melodyLine) {
            val mapped = mapNote(note.note + offset, enableFold) ?: continue
            previous?.let { contour += kotlin.math.abs((mapped - it.second) - (note.note - it.first)) }
            previous = note.note to mapped
        }
        val sounding = mutableMapOf<Int, TaggedNote>()
        for (note in tagged) {
            val mapped = mapNote(note.note + offset, enableFold) ?: continue
            folding += kotlin.math.abs(mapped - note.note - offset) / 12
            val old = sounding[mapped]
            if (old != null && old.end > note.time + EPSILON && old.note != note.note &&
                (old.isMelody || note.isMelody)) collisions++
            if (old == null || note.end > old.end) sounding[mapped] = note
        }
        val score = listOf(mel, -contour, -collisions, acc, -folding, -kotlin.math.abs(offset))
        val better = bestScore?.let { old ->
            score.zip(old).firstOrNull { it.first != it.second }?.let { it.first > it.second } ?: false
        } ?: true
        if (better) { bestScore = score; bestOffset = offset }
    }
    return if (bestScore!![0] + bestScore!![3] > 0) bestOffset else 0
}

/** 统计某个转调量下能弹出来的音符数 */
fun countPlayable(tagged: List<TaggedNote>, offset: Int, enableFold: Boolean): Pair<Int, Int> {
    var mel = 0
    var acc = 0
    for (t in tagged) {
        if (mapNote(t.note + offset, enableFold) == null) continue
        if (t.isMelody) mel++ else acc++
    }
    return mel to acc
}

// ==================== 扫音（滑音）检测 ====================

data class GlissResult(val indices: Set<Int>, val runs: Int)

/**
 * 找出扫音段：连续 [ArrangeParams.glissMinLen] 个以上、间隔小、同方向小步进的音群。
 * 这种华丽的滑音在手机上靠手势派发根本跟不上，建议屏蔽。
 */
fun detectGlissando(tagged: List<TaggedNote>, params: ArrangeParams): GlissResult {
    val n = tagged.size
    val gliss = HashSet<Int>()
    var runs = 0
    var i = 0
    while (i < n - 1) {
        val run = arrayListOf(i)
        var direction = 0
        var j = i
        while (j + 1 < n) {
            val gap = tagged[j + 1].time - tagged[j].time
            val step = tagged[j + 1].note - tagged[j].note
            if (gap > params.glissMaxGap || step == 0 ||
                kotlin.math.abs(step) > params.glissMaxStep
            ) break
            val d = if (step > 0) 1 else -1
            if (direction == 0) direction = d else if (d != direction) break
            run.add(j + 1)
            j++
        }
        if (run.size >= params.glissMinLen) {
            gliss.addAll(run)
            runs++
            i = j + 1
        } else {
            i++
        }
    }
    return GlissResult(gliss, runs)
}

fun removeIndices(tagged: List<TaggedNote>, drop: Set<Int>): List<TaggedNote> =
    tagged.filterIndexed { idx, _ -> idx !in drop }

fun keepMelodyOnly(tagged: List<TaggedNote>): List<TaggedNote> = tagged.filter { it.isMelody }

// ==================== 后处理 ====================

/** 与电脑版一致，80ms 分组只用于报告；不改变实际音符起止。 */
fun countChords(tagged: List<TaggedNote>, transpose: Int, enableFold: Boolean,
    params: ArrangeParams = ArrangeParams()): Pair<Int, Int> {
    val times = tagged.filter { mapNote(it.note + transpose, enableFold) != null }.map { it.time }.sorted()
    var groups = 0; var chords = 0; var i = 0
    while (i < times.size) {
        var j = i + 1
        while (j < times.size && times[j] - times[i] <= params.chordWindow) j++
        groups++; if (j - i > 1) chords++; i = j
    }
    return groups to chords
}

/**
 * 走完整条后处理流水线，产出可直接交给播放引擎的发音组序列。
 * 返回 null 表示这个转调设置下没有任何音符可弹。
 */
fun finalizeChords(
    tagged: List<TaggedNote>, transpose: Int, enableFold: Boolean = false,
    log: (String) -> Unit = {},
    durationMode: String = "score", sustainPedal: Boolean = false
): List<Chord>? {
    val mode = durationMode.takeIf { it in DURATION_MODES } ?: "score"
    val sustained = sustainedFlags(tagged, mode, transpose, enableFold)
    val result = tagged.mapIndexedNotNull { index, note ->
        val mapped = mapNote(note.note + transpose, enableFold) ?: return@mapIndexedNotNull null
        val end = if (sustainPedal) maxOf(note.end, note.pedalEnd) else note.end
        val duration = if (sustained[index]) maxOf(0.0, end - note.time) else KEY_HOLD
        Chord(note.time, listOf(ChordKey(NOTE_MAP.getValue(mapped), 0.0, note.isMelody,
            duration, sustained[index])))
    }.sortedBy { it.time }
    if (result.isEmpty()) { log("当前设置没有可演奏的音符"); return null }
    log("解析完成：${result.size} 个可映射音符；音长模式：${DURATION_MODES[mode]}。")
    return result
}

/**
 * 进程级的解析结果缓存。
 *
 * 演奏时 Activity 会退到后台，可能被系统回收；回来时是新实例，
 * 字段里的解析结果就没了，导致无法再次演奏。存在这里可以跨 Activity 重建存活。
 */
object ArrangeCache {
    /** 缓存条目：解析结果 + 已生成的报告文本，回到界面时直接复用 */
    class Entry(val result: ArrangeResult, val reportText: String)

    private var key: String? = null
    private var entry: Entry? = null

    fun put(path: String, result: ArrangeResult, reportText: String) {
        key = path
        entry = Entry(result, reportText)
    }

    fun get(path: String): Entry? = if (key == path) entry else null

    /** 当前缓存的曲目路径，用于 Activity 重建后恢复选中项 */
    fun currentPath(): String? = if (entry != null) key else null

    fun clear() {
        key = null
        entry = null
    }
}

/** 完整编排结果，附带生成解析报告需要的中间信息 */
data class ArrangeResult(
    val chords: List<Chord>,
    /** 屏蔽扫音后、用于最终映射的音符 */
    val tagged: List<TaggedNote>,
    val transpose: Int,
    val melodyDescription: String,
    val glissRuns: Int,
    val glissNotes: Int,
    val keptChannels: Set<Int>
) {
    /** 演奏总时长（秒） */
    val duration: Double get() = performanceDuration(chords)
}

/**
 * 一步到位：从解析好的 MIDI 数据得到发音组和解析报告信息。
 *
 * @param channels     保留的通道，null 表示自动选音符最多的几个
 * @param transpose    转调半音数，null 表示自动
 * @param blockGliss   是否屏蔽扫音段
 */
fun arrangeDetailed(
    midi: MidiFileData,
    channels: Set<Int>? = null,
    forcedTrack: Int? = null,
    transpose: Int? = null,
    enableFold: Boolean = true,
    blockGliss: Boolean = true,
    melodyOnly: Boolean = false,
    params: ArrangeParams = ArrangeParams(),
    durationMode: String = "score",
    sustainPedal: Boolean = false,
    log: (String) -> Unit = {}
): ArrangeResult? {
    val keep = channels ?: autoSelectChannels(midi.channels, params.autoKeepChannels)
    val notes = filterByChannels(midi.notes, keep)
    if (notes.isEmpty()) {
        log("警告：所选通道里没有音符！")
        return null
    }
    val melody = detectMelody(notes, params, forcedTrack)
    log(melody.description)

    var tagged = melody.tagged
    var glissRuns = 0
    var glissNotes = 0
    if (blockGliss) {
        val gliss = detectGlissando(tagged, params)
        glissRuns = gliss.runs
        glissNotes = gliss.indices.size
        if (glissNotes > 0) {
            log("扫音屏蔽：$glissRuns 段，共 $glissNotes 个音")
            tagged = removeIndices(tagged, gliss.indices)
        }
    }
    if (melodyOnly) tagged = keepMelodyOnly(tagged)

    val offset = transpose ?: findBestTranspose(tagged, enableFold)
    if (offset != 0) log("转调：%+d 个半音".format(offset))
    val chords = finalizeChords(tagged, offset, enableFold, log, durationMode, sustainPedal) ?: return null

    return ArrangeResult(
        chords = chords,
        tagged = tagged,
        transpose = offset,
        melodyDescription = melody.description,
        glissRuns = glissRuns,
        glissNotes = glissNotes,
        keptChannels = keep
    )
}

/** 只要发音组的简化版 */
fun arrange(
    midi: MidiFileData,
    channels: Set<Int>? = null,
    forcedTrack: Int? = null,
    transpose: Int? = null,
    enableFold: Boolean = true,
    blockGliss: Boolean = true,
    melodyOnly: Boolean = false,
    params: ArrangeParams = ArrangeParams(),
    log: (String) -> Unit = {}
): List<Chord>? = arrangeDetailed(
    midi, channels, forcedTrack, transpose, enableFold,
    blockGliss, melodyOnly, params, log = log
)?.chords
