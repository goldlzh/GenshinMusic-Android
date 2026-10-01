package com.example.lyretest.ui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * 把一个 View 显示在所有窗口之上（包括原神这类全屏沉浸式游戏）。
 *
 * 校准窗口必须在倒计时期间允许触摸穿透，开始采集后再拦截触摸。
 * TYPE_ACCESSIBILITY_OVERLAY 原生支持动态切换 FLAG_NOT_TOUCHABLE，也会随显示方向
 * 自动重新布局；SurfaceControlViewHost 的公开 API 无法可靠完成这两个动作。
 */
class OverlayHost(private val service: AccessibilityService) {

    private var windowManagerView: View? = null

    val isAttached: Boolean
        get() = windowManagerView != null

    /**
     * 显示 [view]，铺满整个屏幕。
     *
     * @param touchable false 表示触摸穿透到下层（如倒计时期间让用户操作游戏）
     */
    fun attach(view: View, touchable: Boolean) {
        if (isAttached) return
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            // 横屏时允许透明覆盖层延伸到挖孔区域。即使系统仍为内容添加 Insets，
            // 校准层也会用 getLocationOnScreen 正确换算局部与屏幕坐标。
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(view, params)
        windowManagerView = view
        Log.d(TAG, "覆盖层已挂载，touchable=$touchable")
    }

    fun setTouchable(touchable: Boolean) {
        val v = windowManagerView ?: return
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = v.layoutParams as? WindowManager.LayoutParams ?: return
        params.flags = if (touchable)
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(v, params) }
            .onSuccess { Log.d(TAG, "覆盖层触摸状态已切换：touchable=$touchable") }
            .onFailure { Log.e(TAG, "切换覆盖层触摸状态失败", it) }
    }

    fun detach() {
        val v = windowManagerView ?: return
        run {
            val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            runCatching { wm.removeView(v) }
        }
        windowManagerView = null
        Log.d(TAG, "覆盖层已移除")
    }

    companion object {
        private const val TAG = "LyreOverlayHost"
    }
}
