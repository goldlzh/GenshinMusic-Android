package com.example.lyretest.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.example.lyretest.UserSettingsStore
import com.example.lyretest.controlBarVisibility

/**
 * 演奏控制悬浮条。演奏时用户人在游戏里，回不到主界面，
 * 所以播放/暂停/停止和进度都得靠这个悬浮条。可拖动，避免挡住琴键。
 */
class PlayControlBar(
    private val context: Context,
    private val onPlayPause: () -> Unit,
    private val onStop: () -> Unit,
    private val onNext: () -> Unit,
    private val onClose: () -> Unit,
    private val onSeek: (Float) -> Unit,
    private val onPrevious: () -> Unit = {},
    private val onInsertLabel: () -> Unit = {},
    private val onInteraction: (Boolean) -> Unit = {}
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    // 控制条是小窗口、需要接收点击且可拖动，使用 TYPE_ACCESSIBILITY_OVERLAY。
    private var root: FrameLayout? = null
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var dragHandle: TextView
    private lateinit var titleView: TextView
    private lateinit var playButton: Button
    private lateinit var nextButton: Button
    private lateinit var stopButton: Button
    private lateinit var closeButton: Button
    private lateinit var seekBar: SeekBar
    private val settingsStore = UserSettingsStore(context)
    private var barSettings = settingsStore.loadControlBar()
    private var userSeeking = false

    private companion object {
        const val MIN_WIDTH_DP = 180
    }

    val isShowing: Boolean get() = root != null

    @SuppressLint("ClickableViewAccessibility")
    fun show(songName: String, visible: Boolean = true) {
        root?.let {
            titleView.text = songName
            seekBar.progress = 0
            playButton.isEnabled = true
            setPaused(false)
            params.width = desiredWidthPx()
            updateResponsiveControls(params.width)
            if (visible && it.visibility != View.VISIBLE) {
                // 窗口已在 App 前台时预挂载。切到游戏后绝不能 remove/add：
                // 三星等系统可能冻结后台进程的新窗口事务，直到用户触碰侧边栏才刷新。
                // 保留原窗口，只让 WindowManager 按当前横屏尺寸重新测量。
                it.visibility = View.VISIBLE
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                params.x = dp(8)
                params.y = dp(8)
                it.requestLayout()
                it.invalidate()
                runCatching { wm.updateViewLayout(it, params) }
                constrainToScreen()
            } else {
                it.visibility = if (visible) View.VISIBLE else View.INVISIBLE
                if (visible) constrainToScreen()
            }
            return
        }

        barSettings = settingsStore.loadControlBar()
        dragHandle = TextView(context).apply {
            text = "≡"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            contentDescription = "拖动悬浮条"
        }
        titleView = TextView(context).apply {
            text = songName
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            minWidth = 0
            setPadding(dp(4), 0, dp(6), 0)
        }
        playButton = compactButton("暂停", onPlayPause)
        nextButton = compactButton("下一首", onNext)
        stopButton = compactButton("停止", onStop)
        closeButton = compactButton("×", onClose).apply {
            contentDescription = "关闭悬浮条并停止演奏"
        }
        seekBar = SeekBar(context).apply {
            max = 1000
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) onSeek(value / 1000f)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {
                    userSeeking = true
                }
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    userSeeking = false
                    onSeek(progress / 1000f)
                }
            })
        }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dragHandle, LinearLayout.LayoutParams(dp(28), dp(38)))
            addView(titleView, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))
            addView(playButton)
            addView(nextButton)
            addView(stopButton)
            addView(closeButton)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(3), if (barSettings.resizeEnabled) dp(18) else dp(6), dp(3))
            addView(topRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            // 独立操作行，在最窄悬浮条中仍能插入标签、前后切歌。
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(compactButton("‹", onPrevious).apply { contentDescription = "上一首" },
                    LinearLayout.LayoutParams(0, dp(36), 1f))
                addView(compactButton("插入标签", onInsertLabel), LinearLayout.LayoutParams(0, dp(36), 2.5f))
                addView(compactButton("›", onNext).apply { contentDescription = "下一首" },
                    LinearLayout.LayoutParams(0, dp(36), 1f))
            })
            // 进度条单独占一整行，是任何宽度下最优先保留的控件。
            addView(seekBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)
            ))
        }

        root = object : FrameLayout(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) onInteraction(true)
                return try { super.dispatchTouchEvent(event) }
                finally {
                    if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
                        onInteraction(false)
                }
            }
        }.apply {
            visibility = if (visible) View.VISIBLE else View.INVISIBLE
            background = GradientDrawable().apply {
                setColor(0xDD000000.toInt())
                setStroke(dp(1), 0xFFAAAAAA.toInt())
                cornerRadius = dp(6).toFloat()
            }
            addView(content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
            if (barSettings.resizeEnabled) {
                addView(TextView(context).apply {
                    text = "↔"
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setBackgroundColor(0x66444444)
                    contentDescription = "拖动右边框调整悬浮条宽度"
                    enableResizing(this)
                }, FrameLayout.LayoutParams(dp(22), FrameLayout.LayoutParams.MATCH_PARENT).apply {
                    gravity = Gravity.END
                })
            }
        }

        // 同校准覆盖层：用无障碍层，否则会被全屏游戏盖住
        val type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

        params = WindowManager.LayoutParams(
            desiredWidthPx(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // NOT_FOCUSABLE 保证游戏仍能收到输入；不要 NOT_TOUCHABLE，按钮得能点
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(8)
        }
        updateResponsiveControls(params.width)
        enableDragging()
        wm.addView(root, params)
        if (visible) constrainToScreen()
    }

    /** 长按标题拖动整条，免得挡住琴键 */
    @SuppressLint("ClickableViewAccessibility")
    private fun enableDragging() {
        var downX = 0f
        var downY = 0f
        var origX = 0
        var origY = 0
        val listener = View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    origX = params.x
                    origY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = origX + (event.rawX - downX).toInt()
                    params.y = origY + (event.rawY - downY).toInt()
                    constrainToScreen()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    constrainToScreen()
                    true
                }
                else -> true
            }
        }
        dragHandle.setOnTouchListener(listener)
        titleView.setOnTouchListener(listener)
    }

    /** 调整模式下拖动右边框；松手后按 dp 保存，后续歌曲继续复用。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun enableResizing(handle: View) {
        var downX = 0f
        var originalWidth = 0
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    originalWidth = params.width
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val safe = safeBounds()
                    params.width = (originalWidth + event.rawX - downX).toInt()
                        .coerceIn(dp(MIN_WIDTH_DP), safe.width().coerceAtLeast(dp(MIN_WIDTH_DP)))
                    params.x = params.x.coerceAtMost(
                        (safe.right - params.width).coerceAtLeast(safe.left)
                    )
                    updateResponsiveControls(params.width)
                    root?.let { runCatching { wm.updateViewLayout(it, params) } }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val widthDp = (params.width / context.resources.displayMetrics.density)
                        .toInt().coerceIn(MIN_WIDTH_DP, 900)
                    barSettings = barSettings.copy(widthDp = widthDp)
                    settingsStore.saveControlBar(barSettings)
                    constrainToScreen()
                    true
                }
                else -> true
            }
        }
    }

    private fun desiredWidthPx(): Int {
        val safeWidth = safeBounds().width().coerceAtLeast(dp(MIN_WIDTH_DP))
        return dp(barSettings.widthDp).coerceIn(dp(MIN_WIDTH_DP), safeWidth)
    }

    /** 空间不足时只隐藏可选项；进度、暂停/继续和关闭按钮永不隐藏。 */
    private fun updateResponsiveControls(widthPx: Int) {
        val widthDp = (widthPx / context.resources.displayMetrics.density).toInt()
        val state = controlBarVisibility(widthDp)
        titleView.visibility = if (state.showTitle) View.VISIBLE else View.GONE
        nextButton.visibility = if (state.showNext) View.VISIBLE else View.GONE
        stopButton.visibility = if (state.showStop) View.VISIBLE else View.GONE
    }

    /** 始终把控制条限制在当前屏幕安全区域内，防止横竖屏或拖动后跑出屏幕。 */
    private fun constrainToScreen() {
        val view = root ?: return
        view.post {
            if (!view.isAttachedToWindow) return@post
            val safe = safeBounds()
            val width = view.width.coerceAtLeast(1)
            val height = view.height.coerceAtLeast(1)
            params.x = params.x.coerceIn(safe.left, (safe.right - width).coerceAtLeast(safe.left))
            params.y = params.y.coerceIn(safe.top, (safe.bottom - height).coerceAtLeast(safe.top))
            runCatching { wm.updateViewLayout(view, params) }
        }
    }

    @Suppress("DEPRECATION")
    private fun safeBounds(): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars() or
                        android.view.WindowInsets.Type.displayCutout()
            )
            return Rect(
                metrics.bounds.left + insets.left + dp(4),
                metrics.bounds.top + insets.top + dp(4),
                metrics.bounds.right - insets.right - dp(4),
                metrics.bounds.bottom - insets.bottom - dp(4)
            )
        }
        val metrics = context.resources.displayMetrics
        return Rect(dp(4), dp(4), metrics.widthPixels - dp(4), metrics.heightPixels - dp(4))
    }

    private fun compactButton(label: String, action: () -> Unit) = Button(context).apply {
        text = label
        textSize = 12f
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(10), dp(2), dp(10), dp(2))
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    /** 更新进度显示，须在主线程调用 */
    fun updateProgress(songName: String, playTime: Double, total: Double) {
        titleView.text = "$songName  ${formatTime(playTime)}/${formatTime(total)}"
        if (!userSeeking) {
            seekBar.progress = if (total <= 0.0) 0
            else ((playTime / total).coerceIn(0.0, 1.0) * seekBar.max).toInt()
        }
    }

    fun setPaused(paused: Boolean) {
        if (root != null) playButton.text = if (paused) "继续" else "暂停"
    }

    fun showLoadingNext() {
        if (root == null) return
        titleView.text = "正在加载下一首…"
        seekBar.progress = 0
        playButton.isEnabled = false
    }

    fun dismiss() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    private fun formatTime(sec: Double): String {
        val s = sec.toInt().coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }
}

/** 实时电子琴模式的简化悬浮条；没有进度和暂停，保留拖动与停止。 */
class LiveMidiControlBar(
    private val context: Context,
    private val onStop: () -> Unit
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private lateinit var params: WindowManager.LayoutParams

    @SuppressLint("ClickableViewAccessibility")
    fun show(deviceName: String, visible: Boolean = true) {
        root?.let {
            it.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            if (visible) updateLayout(it)
            return
        }
        val title = TextView(context).apply {
            text = "电子琴：$deviceName"
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(220)
            setPadding(dp(8), 0, dp(8), 0)
        }
        root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = if (visible) View.VISIBLE else View.INVISIBLE
            setBackgroundColor(0xDD000000.toInt())
            setPadding(dp(6), dp(4), dp(6), dp(4))
            addView(title)
            addView(Button(context).apply {
                text = "停止"
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(dp(10), dp(2), dp(10), dp(2))
                setOnClickListener { onStop() }
            })
        }
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(8)
        }

        var downX = 0f
        var downY = 0f
        var originalX = 0
        var originalY = 0
        title.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    originalX = params.x
                    originalY = params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = originalX + (event.rawX - downX).toInt()
                    params.y = originalY + (event.rawY - downY).toInt()
                    root?.let { runCatching { wm.updateViewLayout(it, params) } }
                }
            }
            true
        }
        wm.addView(root, params)
    }

    private fun updateLayout(view: View) {
        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        view.requestLayout()
        runCatching { wm.updateViewLayout(view, params) }
    }

    fun dismiss() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
