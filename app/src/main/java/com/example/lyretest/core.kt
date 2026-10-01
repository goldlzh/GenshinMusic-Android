package com.example.lyretest.core

/** 原神风物之诗琴 21 键定义 */
object LyreKeys {
    // 从左到右、从上到下的校准顺序
    // 高音 Q W E R T Y U / 中音 A S D F G H J / 低音 Z X C V B N M
    val ORDER = listOf(
        "Q", "W", "E", "R", "T", "Y", "U",
        "A", "S", "D", "F", "G", "H", "J",
        "Z", "X", "C", "V", "B", "N", "M"
    )
    const val COUNT = 21
}

/**
 * 屏幕方向与真实尺寸的读取工具。
 *
 * 注意：这些方法会从无障碍服务里调用，而 Service 是「非视觉 Context」——
 * `Context.getDisplay()` 和 `WindowManager.currentWindowMetrics` 在这种 Context 上
 * 会抛 UnsupportedOperationException。所以统一走 DisplayManager 拿默认屏幕，
 * 它对任何 Context 都有效。
 */
object Display {

    private fun defaultDisplay(context: android.content.Context): android.view.Display? {
        val dm = context.getSystemService(android.content.Context.DISPLAY_SERVICE)
                as? android.hardware.display.DisplayManager ?: return null
        return dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
    }

    /** 当前显示方向，[android.view.Surface] 的 ROTATION_* 常量；读不到时按竖屏处理 */
    fun rotation(context: android.content.Context): Int =
        defaultDisplay(context)?.rotation ?: android.view.Surface.ROTATION_0

    /** 当前方向下的真实屏幕像素尺寸（含刘海/导航栏区域） */
    fun realSize(context: android.content.Context): Pair<Int, Int> {
        val display = defaultDisplay(context)
        if (display != null) {
            @Suppress("DEPRECATION")
            val metrics = android.util.DisplayMetrics().also { display.getRealMetrics(it) }
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                return Pair(metrics.widthPixels, metrics.heightPixels)
            }
        }
        // 兜底：用资源里的尺寸，虽然不含系统栏但不会是 0
        val dm = context.resources.displayMetrics
        return Pair(dm.widthPixels, dm.heightPixels)
    }
}