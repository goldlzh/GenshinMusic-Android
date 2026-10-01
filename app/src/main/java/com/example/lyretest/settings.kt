package com.example.lyretest

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 单首 MIDI 的独立编曲设置。null 通道/转调表示使用自动推荐。 */
data class SongArrangeSettings(
    val channels: Set<Int>? = null,
    val forcedTrack: Int? = null,
    val transpose: Int? = null,
    val melodyOnly: Boolean = false,
    val blockGliss: Boolean = true,
    val enableFold: Boolean = true,
    val durationMode: String = "score",
    val sustainPedal: Boolean = false,
    val speed: Double = 1.0,
    val startRatio: Double = 0.0,
    val startLabelId: String? = null
)

/** 实时电子琴的全局设置。 */
data class LiveMidiSettings(
    val autoConnectEnabled: Boolean = false,
    val mergeSimultaneousNotes: Boolean = true,
    val transposeEnabled: Boolean = false,
    val transpose: Int = 0,
    val octaveFoldEnabled: Boolean = true,
    val followRelease: Boolean = true,
    val sustainPedal: Boolean = true,
    val mergeWindowMs: Int = 30
)

/** 自动演奏悬浮条设置。宽度用 dp 保存，换分辨率后仍保持相近视觉尺寸。 */
data class ControlBarSettings(
    val resizeEnabled: Boolean = false,
    val widthDp: Int = 560
)

/**
 * 用户设置存储：
 * 保留旧版本的六项编曲设置供迁移；新版本完整歌曲记忆由 SongStateStore 保存。
 * 实时 MIDI、悬浮条和播放模式仍使用全局偏好。
 */
class UserSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadSpeed(): Double =
        prefs.getFloat(KEY_SPEED, 1.0f).toDouble().coerceIn(0.5, 2.0)

    fun saveSpeed(value: Double) {
        prefs.edit().putFloat(KEY_SPEED, value.coerceIn(0.5, 2.0).toFloat()).apply()
    }

    fun loadPlayMode(): String =
        prefs.getString(KEY_PLAY_MODE, "SEQUENTIAL") ?: "SEQUENTIAL"

    fun savePlayMode(value: String) {
        prefs.edit().putString(KEY_PLAY_MODE, value).apply()
    }

    fun loadLiveMidi(): LiveMidiSettings = LiveMidiSettings(
        autoConnectEnabled = prefs.getBoolean(KEY_LIVE_AUTO_CONNECT, false),
        mergeSimultaneousNotes = prefs.getBoolean(KEY_LIVE_MERGE, true),
        transposeEnabled = prefs.getBoolean(KEY_LIVE_TRANSPOSE_ENABLED, false),
        transpose = prefs.getInt(KEY_LIVE_TRANSPOSE, 0).coerceIn(-48, 48),
        octaveFoldEnabled = prefs.getBoolean(KEY_LIVE_FOLD, true),
        followRelease = prefs.getBoolean("live_follow_release", true),
        sustainPedal = prefs.getBoolean("live_sustain_pedal", true),
        mergeWindowMs = prefs.getInt("live_merge_window_ms", 30).coerceIn(0, 200)
    )

    fun saveLiveMidi(value: LiveMidiSettings) {
        prefs.edit()
            .putBoolean(KEY_LIVE_AUTO_CONNECT, value.autoConnectEnabled)
            .putBoolean(KEY_LIVE_MERGE, value.mergeSimultaneousNotes)
            .putBoolean(KEY_LIVE_TRANSPOSE_ENABLED, value.transposeEnabled)
            .putInt(KEY_LIVE_TRANSPOSE, value.transpose.coerceIn(-48, 48))
            .putBoolean(KEY_LIVE_FOLD, value.octaveFoldEnabled)
            .putBoolean("live_follow_release", value.followRelease)
            .putBoolean("live_sustain_pedal", value.sustainPedal)
            .putInt("live_merge_window_ms", value.mergeWindowMs.coerceIn(0, 200))
            .apply()
    }

    fun loadControlBar(): ControlBarSettings = ControlBarSettings(
        resizeEnabled = prefs.getBoolean(KEY_CONTROL_BAR_RESIZE, false),
        widthDp = prefs.getInt(KEY_CONTROL_BAR_WIDTH_DP, 560).coerceIn(180, 900)
    )

    fun saveControlBar(value: ControlBarSettings) {
        prefs.edit()
            .putBoolean(KEY_CONTROL_BAR_RESIZE, value.resizeEnabled)
            .putInt(KEY_CONTROL_BAR_WIDTH_DP, value.widthDp.coerceIn(180, 900))
            .apply()
    }

    fun loadSong(path: String): SongArrangeSettings? {
        val raw = prefs.getString(songKey(path), null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            val channels = if (obj.isNull("channels")) null else {
                val arr = obj.getJSONArray("channels")
                (0 until arr.length()).map { arr.getInt(it) }.toSet()
            }
            SongArrangeSettings(
                channels = channels,
                forcedTrack = obj.optNullableInt("forcedTrack"),
                transpose = obj.optNullableInt("transpose"),
                melodyOnly = obj.optBoolean("melodyOnly", false),
                blockGliss = obj.optBoolean("blockGliss", true),
                enableFold = obj.optBoolean("enableFold", true)
            )
        }.getOrNull()
    }

    fun saveSong(path: String, value: SongArrangeSettings) {
        val obj = JSONObject().apply {
            put(
                "channels",
                value.channels?.let { set ->
                    JSONArray().apply { set.sorted().forEach { put(it) } }
                } ?: JSONObject.NULL
            )
            put("forcedTrack", value.forcedTrack ?: JSONObject.NULL)
            put("transpose", value.transpose ?: JSONObject.NULL)
            put("melodyOnly", value.melodyOnly)
            put("blockGliss", value.blockGliss)
            put("enableFold", value.enableFold)
        }
        prefs.edit().putString(songKey(path), obj.toString()).apply()
    }

    fun deleteSong(path: String) {
        prefs.edit().remove(songKey(path)).apply()
    }

    private fun JSONObject.optNullableInt(name: String): Int? =
        if (!has(name) || isNull(name)) null else getInt(name)

    private fun songKey(path: String): String =
        KEY_SONG_PREFIX + android.util.Base64.encodeToString(
            path.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE
        )

    private companion object {
        const val PREFS = "user_settings"
        const val KEY_SPEED = "speed"
        const val KEY_PLAY_MODE = "play_mode"
        const val KEY_LIVE_AUTO_CONNECT = "live_auto_connect"
        const val KEY_LIVE_MERGE = "live_merge"
        const val KEY_LIVE_TRANSPOSE_ENABLED = "live_transpose_enabled"
        const val KEY_LIVE_TRANSPOSE = "live_transpose"
        const val KEY_LIVE_FOLD = "live_fold"
        const val KEY_CONTROL_BAR_RESIZE = "control_bar_resize"
        const val KEY_CONTROL_BAR_WIDTH_DP = "control_bar_width_dp"
        const val KEY_SONG_PREFIX = "song_"
    }
}
