package com.example.lyretest.storage

import android.content.Context
import android.view.Surface
import org.json.JSONArray
import org.json.JSONObject

/** 单个琴键的屏幕坐标 */
data class KeyPoint(val name: String, val x: Float, val y: Float)

/**
 * 一套键位配置（一个 profile 在某一个屏幕方向下的坐标）。
 *
 * 坐标是「当前显示方向」下的物理像素。设备翻转后游戏会重新布局，刘海/挖孔的
 * 安全区域也可能换边，所以每个实际使用的方向必须单独校准。
 *
 * @param rotation [Surface.ROTATION_0] / 90 / 180 / 270，校准时的屏幕方向
 */
data class KeyLayout(
    val profileName: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val points: List<KeyPoint>,
    val rotation: Int
)

fun rotationName(r: Int): String = when (r) {
    Surface.ROTATION_0 -> "竖屏"
    Surface.ROTATION_90 -> "横屏(左)"
    Surface.ROTATION_180 -> "竖屏(倒)"
    Surface.ROTATION_270 -> "横屏(右)"
    else -> "未知"
}

/**
 * 键位配置持久化。支持多套 profile，每套 profile 下按屏幕方向分别存储。
 * 用 SharedPreferences + JSON 存储。
 */
class KeyLayoutStore(context: Context) {

    private val prefs = context.getSharedPreferences("key_layouts", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PROFILES = "profiles"
        private const val KEY_CURRENT = "current"
        private const val PREFIX = "layout_"

        /** 存储 key：profile 名 + 方向。旧版本无方向后缀的数据不再读取。 */
        private fun storageKey(profile: String, rotation: Int) = "$PREFIX${profile}_r$rotation"
    }

    fun listProfiles(): List<String> {
        val arr = JSONArray(prefs.getString(KEY_PROFILES, "[]"))
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun getCurrentProfileName(): String? = prefs.getString(KEY_CURRENT, null)

    fun setCurrentProfile(name: String) {
        prefs.edit().putString(KEY_CURRENT, name).apply()
    }

    /** 该 profile 下已校准的所有方向 */
    fun listRotations(profile: String): List<Int> =
        listOf(Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270)
            .filter { prefs.contains(storageKey(profile, it)) }

    fun save(layout: KeyLayout) {
        val obj = JSONObject().apply {
            put("profileName", layout.profileName)
            put("screenWidth", layout.screenWidth)
            put("screenHeight", layout.screenHeight)
            put("rotation", layout.rotation)
            val pts = JSONArray()
            for (p in layout.points) {
                pts.put(JSONObject().apply {
                    put("name", p.name)
                    put("x", p.x.toDouble())
                    put("y", p.y.toDouble())
                })
            }
            put("points", pts)
        }
        prefs.edit()
            .putString(storageKey(layout.profileName, layout.rotation), obj.toString())
            .apply()

        val profiles = listProfiles().toMutableList()
        if (!profiles.contains(layout.profileName)) {
            profiles.add(layout.profileName)
            val arr = JSONArray()
            profiles.forEach { arr.put(it) }
            prefs.edit().putString(KEY_PROFILES, arr.toString()).apply()
        }
        setCurrentProfile(layout.profileName)
    }

    /** 精确读取某个方向的键位，没有则返回 null */
    fun load(name: String, rotation: Int): KeyLayout? {
        val s = prefs.getString(storageKey(name, rotation), null) ?: return null
        val obj = JSONObject(s)
        val pts = obj.getJSONArray("points")
        val points = (0 until pts.length()).map {
            val po = pts.getJSONObject(it)
            KeyPoint(
                po.getString("name"),
                po.getDouble("x").toFloat(),
                po.getDouble("y").toFloat()
            )
        }
        return KeyLayout(
            obj.getString("profileName"),
            obj.getInt("screenWidth"),
            obj.getInt("screenHeight"),
            points,
            obj.getInt("rotation")
        )
    }

    /** 只读取精确方向；游戏重排和非对称挖孔使 180° 坐标也不能可靠推导。 */
    fun loadFor(name: String, rotation: Int): KeyLayout? = load(name, rotation)

    fun loadCurrentFor(rotation: Int): KeyLayout? =
        getCurrentProfileName()?.let { loadFor(it, rotation) }

    /** 删除整个 profile（所有方向） */
    fun delete(name: String) {
        val e = prefs.edit()
        listRotations(name).forEach { e.remove(storageKey(name, it)) }
        val profiles = listProfiles().toMutableList()
        profiles.remove(name)
        val arr = JSONArray()
        profiles.forEach { arr.put(it) }
        e.putString(KEY_PROFILES, arr.toString())
        if (getCurrentProfileName() == name) e.remove(KEY_CURRENT)
        e.apply()
    }
}
