package com.example.lyretest

import android.content.Context
import android.icu.text.Transliterator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.UUID

val DURATION_LABELS = linkedMapOf("fixed" to "固定短按", "score" to "原谱音长",
    "melody" to "旋律长音", "chord" to "和弦长音")
enum class SongSort(val label: String) {
    NAME("首字母 A–Z"), ADDED("添加时间（最近在前）"), PLAYED("播放时间（最近在前）")
}
data class SongLabel(val id: String, val name: String, val ratio: Double)
fun nextSong(queue: List<File>, current: File?, mode: String, delta: Int? = null): File? {
    if (queue.isEmpty()) return null
    val index = queue.indexOfFirst { it.path == current?.path }
    return when {
        delta == -1 -> queue[if (index > 0) index - 1 else queue.lastIndex]
        mode == "RANDOM" -> queue.filter { it.path != current?.path }.ifEmpty { queue }.random()
        delta == 1 -> queue[(index + 1) % queue.size]
        mode == "SINGLE" -> queue.firstOrNull { it.path == current?.path }
        else -> queue.getOrNull(index + 1)
    }
}
data class SongRecord(val addedAt: Long = 0, val lastPlayedAt: Long = 0,
    val manual: SongArrangeSettings? = null, val automatic: SongArrangeSettings? = null,
    val labels: List<SongLabel> = emptyList()) {
    fun memory(): SongArrangeSettings? = manual ?: automatic
    fun startRatio(settings: SongArrangeSettings): Double =
        (labels.find { it.id == settings.startLabelId }?.ratio ?: settings.startRatio).coerceIn(0.0, 1.0)
    fun withoutLabel(id: String): SongRecord {
        fun detach(value: SongArrangeSettings?): SongArrangeSettings? = value?.let {
            if (it.startLabelId == id) it.copy(startLabelId = null, startRatio = startRatio(it)) else it
        }
        return copy(manual = detach(manual), automatic = detach(automatic), labels = labels.filter { it.id != id })
    }
}

/** Name key is injected in tests; Android uses built-in ICU for Chinese pinyin. */
fun sortSongFiles(files: List<File>, mode: SongSort, records: Map<String, SongRecord>,
    nameKey: (String) -> String): List<File> = files.sortedWith { a, b ->
    val ra = records[a.path] ?: SongRecord()
    val rb = records[b.path] ?: SongRecord()
    val time = when (mode) {
        SongSort.NAME -> 0
        SongSort.ADDED -> rb.addedAt.compareTo(ra.addedAt)
        SongSort.PLAYED -> rb.lastPlayedAt.compareTo(ra.lastPlayedAt)
    }
    if (time != 0) time else nameKey(a.nameWithoutExtension).compareTo(nameKey(b.nameWithoutExtension))
        .takeIf { it != 0 } ?: a.name.compareTo(b.name)
}

/** Per-song metadata is separate from the old settings so upgrades retain existing manual choices. */
class SongStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("song_state", Context.MODE_PRIVATE)
    private val legacy = UserSettingsStore(context)
    private val transliterator by lazy { Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower") }
    private val nameKeys = mutableMapOf<String, String>()
    fun loadSort(): SongSort = runCatching { SongSort.valueOf(prefs.getString("sort", "NAME")!!) }.getOrDefault(SongSort.NAME)
    fun saveSort(mode: SongSort) { prefs.edit().putString("sort", mode.name).apply() }
    fun load(path: String): SongRecord {
        val text = prefs.getString(path, null)
        if (text == null) return SongRecord(manual = legacy.loadSong(path)?.copy(speed = legacy.loadSpeed(), durationMode = "fixed"))
        return runCatching {
            val o = JSONObject(text)
            SongRecord(o.optLong("addedAt"), o.optLong("lastPlayedAt"),
                o.optJSONObject("manual")?.let(::decode), o.optJSONObject("automatic")?.let(::decode),
                o.optJSONArray("labels")?.let { a -> (0 until a.length()).map { i ->
                    val l = a.getJSONObject(i)
                    SongLabel(l.getString("id"), l.getString("name"), l.optDouble("ratio", 0.0).coerceIn(0.0, 1.0))
                } }.orEmpty())
        }.getOrElse { SongRecord(manual = legacy.loadSong(path)) }
    }
    fun save(path: String, record: SongRecord) {
        val o = JSONObject().put("addedAt", record.addedAt).put("lastPlayedAt", record.lastPlayedAt)
            .put("manual", record.manual?.let(::encode) ?: JSONObject.NULL)
            .put("automatic", record.automatic?.let(::encode) ?: JSONObject.NULL)
            .put("labels", JSONArray().apply { record.labels.forEach { l ->
                put(JSONObject().put("id", l.id).put("name", l.name).put("ratio", l.ratio))
            } })
        prefs.edit().putString(path, o.toString()).apply()
    }
    fun ensureAdded(file: File, time: Long = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()) {
        val old = load(file.path)
        if (old.addedAt == 0L) save(file.path, old.copy(addedAt = time))
    }
    fun played(path: String, settings: SongArrangeSettings, time: Long = System.currentTimeMillis()) {
        val old = load(path)
        save(path, old.copy(lastPlayedAt = time, automatic = settings))
    }
    fun saveManual(path: String, settings: SongArrangeSettings) = save(path, load(path).copy(manual = settings))
    fun clearManual(path: String) {
        save(path, load(path).copy(manual = null))
        legacy.deleteSong(path)
    }
    fun insertLabel(path: String, ratio: Double): SongLabel {
        val old = load(path)
        val names = old.labels.map { it.name }.toSet()
        var number = 1
        while ("标签 $number" in names) number++
        val label = SongLabel(UUID.randomUUID().toString(), "标签 $number", ratio.coerceIn(0.0, 1.0))
        save(path, old.copy(labels = (old.labels + label).sortedBy { it.ratio }))
        return label
    }
    fun delete(path: String) { prefs.edit().remove(path).apply(); legacy.deleteSong(path) }
    fun sorted(files: List<File>, mode: SongSort): List<File> = sortSongFiles(files, mode,
        files.associate { it.path to load(it.path) }) { name -> nameKeys.getOrPut(name) {
            val normalized = Normalizer.normalize(transliterator.transliterate(name), Normalizer.Form.NFKC)
                .filter { it.isLetterOrDigit() }
            (when { normalized.firstOrNull()?.isLetter() == true -> "0"; normalized.firstOrNull()?.isDigit() == true -> "1"; else -> "2" }) + normalized
        } }
    private fun encode(s: SongArrangeSettings) = JSONObject().apply {
        put("channels", s.channels?.let { JSONArray(it.sorted()) } ?: JSONObject.NULL)
        put("forcedTrack", s.forcedTrack ?: JSONObject.NULL); put("transpose", s.transpose ?: JSONObject.NULL)
        put("melodyOnly", s.melodyOnly); put("blockGliss", s.blockGliss); put("enableFold", s.enableFold)
        put("durationMode", s.durationMode); put("sustainPedal", s.sustainPedal); put("speed", s.speed.coerceIn(.5, 2.0))
        put("startRatio", s.startRatio); put("startLabelId", s.startLabelId ?: JSONObject.NULL)
    }
    private fun decode(o: JSONObject) = SongArrangeSettings(
        channels = o.optJSONArray("channels")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() },
        forcedTrack = if (o.isNull("forcedTrack")) null else o.getInt("forcedTrack"),
        transpose = if (o.isNull("transpose")) null else o.getInt("transpose"),
        melodyOnly = o.optBoolean("melodyOnly"), blockGliss = o.optBoolean("blockGliss", true), enableFold = o.optBoolean("enableFold", true),
        durationMode = o.optString("durationMode", "score").takeIf { it in DURATION_LABELS } ?: "score",
        sustainPedal = o.optBoolean("sustainPedal"), speed = o.optDouble("speed", 1.0).coerceIn(.5, 2.0),
        startRatio = o.optDouble("startRatio", 0.0).coerceIn(0.0, 1.0),
        startLabelId = if (o.isNull("startLabelId")) null else o.getString("startLabelId"))
}
