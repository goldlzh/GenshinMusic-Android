package com.example.lyretest.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import com.example.lyretest.core.Display
import com.example.lyretest.core.LyreKeys
import com.example.lyretest.storage.KeyPoint
import com.example.lyretest.storage.rotationName

/**
 * 键位校准悬浮窗。
 * 流程：全屏透明覆盖在原神琴界面上 → 用户依次点击 21 个键 → 记录物理坐标
 *       → 测试点击确认 → 保存。
 *
 * @param onDispatchTestTaps 委托给 TouchService 派发一轮测试点击（点完回调）
 * @param onSave             保存回调，返回记录好的 21 个点
 * @param onCancel           取消回调
 */
class CalibrationOverlay(
    private val service: android.accessibilityservice.AccessibilityService,
    private val onDispatchTestTaps: (List<KeyPoint>, () -> Unit) -> Unit,
    private val onSave: (List<KeyPoint>, Int, Int, Int) -> Unit,
    private val onCancel: () -> Unit
) {
    private val context: Context = service
    private val host = OverlayHost(service)
    private var root: FrameLayout? = null
    private lateinit var drawView: DrawView
    private lateinit var controlBar: LinearLayout

    private val recorded = mutableListOf<KeyPoint>()
    private var currentIndex = 0
    private var state = State.COUNTDOWN

    /** 倒计时剩余秒数，倒计时期间覆盖层已在屏上但不接收点击 */
    private var countdown = 0
    private val handler = Handler(Looper.getMainLooper())

    private var screenW = 0
    private var screenH = 0

    /** 正式开始采集时的屏幕方向，键位坐标只在这个方向下有效 */
    private var rotation = 0

    private enum class State { COUNTDOWN, RECORDING, CONFIRM }

    /**
     * 显示校准覆盖层。
     *
     * 窗口是【立刻】加上去的，倒计时在覆盖层内部走：
     * 如果等倒计时结束再 addView，此时 App 已退到后台，被系统冻结的进程添加窗口
     * 不一定会立即上屏（需要用户划一下后台才刷新）。先上屏再倒计时可以避开这个问题。
     *
     * @param countdownSeconds 开始记录前留给用户切回游戏的秒数
     */
    @SuppressLint("ClickableViewAccessibility")
    fun show(countdownSeconds: Int = 5) {
        if (root != null) return
        countdown = countdownSeconds
        state = if (countdownSeconds > 0) State.COUNTDOWN else State.RECORDING
        touchEnabled = state != State.COUNTDOWN

        captureDisplayState()

        drawView = DrawView(context)
        controlBar = buildControlBar()
        controlBar.visibility = View.GONE

        root = FrameLayout(context).apply {
            addView(drawView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(controlBar, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ))
        }

        // 倒计时期间触摸穿透，用户才能切到游戏并打开乐器。
        host.attach(root!!, touchable = state != State.COUNTDOWN)
        if (state == State.COUNTDOWN) {
            tickCountdown()
        } else {
            beginRecording()
        }
    }

    /** 每秒刷新一次倒计时，归零后开始接收点击 */
    private fun tickCountdown() {
        drawView.invalidate()
        handler.postDelayed({
            if (root == null) return@postDelayed
            countdown--
            if (countdown > 0) {
                tickCountdown()
            } else {
                beginRecording()
            }
        }, 1000L)
    }

    /**
     * 倒计时结束后才锁定尺寸和方向。此时用户已经从竖屏 App 切到横屏游戏，
     * 保存的坐标才属于真正的演奏界面。
     */
    private fun beginRecording() {
        captureDisplayState()
        state = State.RECORDING
        setTouchable(true)
        drawView.invalidate()
        Log.i(TAG, "开始采集键位：${screenW}x$screenH，${rotationName(rotation)}")
    }

    private fun captureDisplayState() {
        val size = Display.realSize(context)
        screenW = size.first
        screenH = size.second
        rotation = Display.rotation(context)
    }

    /** 由无障碍服务转发显示方向变化。倒计时内允许切到横屏，采集开始后禁止旋转。 */
    fun onDisplayConfigurationChanged() {
        if (root == null) return
        if (state == State.COUNTDOWN) {
            captureDisplayState()
            drawView.invalidate()
            return
        }
        if (Display.rotation(context) != rotation) cancelForRotationChange()
    }

    fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        host.detach()
        root = null
    }

    /** 是否消费触摸事件；配合 [DrawView.onTouchEvent] 里的判断实现穿透 */
    private var touchEnabled = false

    private fun setTouchable(touchable: Boolean) {
        touchEnabled = touchable
        host.setTouchable(touchable)
    }

    private fun buildControlBar(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC000000.toInt())
            setPadding(16, 16, 16, 16)

            addView(Button(context).apply {
                text = "测试点击"
                setOnClickListener { runTest() }
            })
            addView(Button(context).apply {
                text = "保存"
                setOnClickListener {
                    if (!ensureSameRotation()) return@setOnClickListener
                    onSave(
                        recorded.toList(), screenW, screenH,
                        this@CalibrationOverlay.rotation
                    )
                    dismiss()
                }
            })
            addView(Button(context).apply {
                text = "重新校准"
                setOnClickListener { restart() }
            })
            addView(Button(context).apply {
                text = "取消"
                setOnClickListener {
                    onCancel()
                    dismiss()
                }
            })
        }
    }

    private fun runTest() {
        if (!ensureSameRotation()) return
        Toast.makeText(context, "开始测试，注意游戏里琴键是否逐个亮起", Toast.LENGTH_SHORT).show()
        // 测试期间让注入的点击穿透到游戏
        setTouchable(false)
        onDispatchTestTaps(recorded.toList()) {
            setTouchable(true)
        }
    }

    private fun restart() {
        recorded.clear()
        currentIndex = 0
        state = State.RECORDING
        setTouchable(true)   // 可能是从倒计时/确认态回来的，确保能收到点击
        controlBar.visibility = View.GONE
        drawView.invalidate()
    }

    /**
     * 屏幕方向变了就作废本次校准：已记录的坐标属于旧方向，混着用必定错位。
     * @return true 表示方向没变，可以继续
     */
    private fun ensureSameRotation(): Boolean {
        if (Display.rotation(context) == rotation) return true
        cancelForRotationChange()
        return false
    }

    private fun cancelForRotationChange() {
        Toast.makeText(
            context,
            "屏幕方向已改变（校准时为${rotationName(rotation)}），本次校准作废，请重新开始",
            Toast.LENGTH_LONG
        ).show()
        onCancel()
        dismiss()
    }

    /** 自定义绘制层：半透明提示条 + 已记录圆点 */
    private inner class DrawView(ctx: Context) : View(ctx) {
        private val bannerPaint = Paint().apply { color = 0xB0000000.toInt() }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 44f
            textAlign = Paint.Align.CENTER
        }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFD54F.toInt()
            textSize = 34f
            textAlign = Paint.Align.CENTER
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF4CAF50.toInt()
        }
        private val dotTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // 顶部提示条
            val bannerH = 160f
            canvas.drawRect(0f, 0f, width.toFloat(), bannerH, bannerPaint)
            if (state == State.COUNTDOWN) {
                canvas.drawText("$countdown 秒后开始校准", width / 2f, 70f, textPaint)
                canvas.drawText(
                    "请切到原神并打开风物之诗琴，保持手机方向不变",
                    width / 2f, 120f, subPaint
                )
            } else if (state == State.RECORDING) {
                val key = LyreKeys.ORDER[currentIndex]
                canvas.drawText(
                    "请点击琴键  【$key】",
                    width / 2f, 70f, textPaint
                )
                canvas.drawText(
                    "第 ${currentIndex + 1} / ${LyreKeys.COUNT} 个",
                    width / 2f, 120f, subPaint
                )
            } else {
                canvas.drawText(
                    "已记录 21 个键位（${rotationName(this@CalibrationOverlay.rotation)}）",
                    width / 2f, 70f, textPaint
                )
                canvas.drawText("点「测试点击」确认，无误后「保存」", width / 2f, 120f, subPaint)
            }
            // 已记录的点
            // KeyPoint 保存的是 dispatchGesture 使用的屏幕绝对坐标；Canvas 使用的是
            // View 局部坐标。横屏挖孔/刘海会让覆盖 View 的原点不一定是 (0, 0)，
            // 绘制前必须减去 View 在屏幕上的偏移。
            val location = IntArray(2)
            getLocationOnScreen(location)
            for (p in recorded) {
                val localX = p.x - location[0]
                val localY = p.y - location[1]
                canvas.drawCircle(localX, localY, 22f, dotPaint)
                canvas.drawText(p.name, localX, localY + 10f, dotTextPaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            // 倒计时期间不消费事件，让用户能操作下面的游戏
            if (!touchEnabled) return false
            if (state == State.RECORDING && event.action == MotionEvent.ACTION_DOWN) {
                // 提示条区域不记录，避免误触
                if (event.y < 160f) return true
                if (!ensureSameRotation()) return true
                // rawX/rawY 是物理屏幕坐标，和 AccessibilityService.dispatchGesture
                // 使用同一坐标系；不能保存 event.x/event.y，否则挖孔安全区会造成偏移。
                recorded.add(KeyPoint(LyreKeys.ORDER[currentIndex], event.rawX, event.rawY))
                currentIndex++
                if (currentIndex >= LyreKeys.COUNT) {
                    state = State.CONFIRM
                    controlBar.visibility = View.VISIBLE
                }
                invalidate()
                return true
            }
            return true
        }
    }

    private companion object {
        const val TAG = "LyreCalibration"
    }
}
