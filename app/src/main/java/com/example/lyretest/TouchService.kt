package com.example.lyretest

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.example.lyretest.arrange.Chord
import com.example.lyretest.core.Display
import com.example.lyretest.player.PlayParams
import com.example.lyretest.player.Player
import com.example.lyretest.player.PlayerCallbacks
import com.example.lyretest.player.TapBatch
import com.example.lyretest.player.TouchContinuations
import com.example.lyretest.player.TouchPoint
import com.example.lyretest.player.effectiveTouchLimit
import com.example.lyretest.midi.LiveMidiSession
import com.example.lyretest.midi.displayName
import com.example.lyretest.midi.isGenshinPackageName
import com.example.lyretest.storage.KeyLayout
import com.example.lyretest.storage.KeyLayoutStore
import com.example.lyretest.storage.KeyPoint
import com.example.lyretest.storage.rotationName
import com.example.lyretest.ui.CalibrationOverlay
import com.example.lyretest.ui.PlayControlBar
import com.example.lyretest.ui.LiveMidiControlBar
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class TouchService : AccessibilityService() {
    private enum class GestureAttempt {
        PENDING, COMPLETED, CANCELLED, SUBMIT_FAILED, CALLBACK_TIMEOUT
    }

    companion object {
        private const val TAG = "LyreTouchService"

        var instance: TouchService? = null

        /** 两次手势之间的间隙，留给系统收尾，太短会互相取消 */
        const val GESTURE_GAP_MS = 5L

        /** 等手势完成回调的额外超时，防回调丢失卡死演奏线程 */
        const val GESTURE_TIMEOUT_MS = 200L

        /** 悬浮条进度每秒最多刷新 5 次，避免后台主线程队列堆积。 */
        const val PROGRESS_UPDATE_INTERVAL_NS = 200_000_000L
    }

    private val maxStrokes = effectiveTouchLimit(GestureDescription.getMaxStrokeCount())
    private val touchChain = TouchContinuations<GestureDescription.StrokeDescription>(maxStrokes) { spec ->
        val path = Path().apply {
            moveTo(spec.from.x, spec.from.y)
            lineTo(spec.to.x, spec.to.y)
        }
        spec.previous?.continueStroke(path, spec.offsetMs, spec.durationMs, spec.continues)
            ?: GestureDescription.StrokeDescription(path, spec.offsetMs, spec.durationMs, spec.continues)
    }
    private var currentInsertLabelAction: (() -> Unit)? = null
    private var currentPreviousAction: (() -> Unit)? = null
    private var calibration: CalibrationOverlay? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var gestureHandlerThread: HandlerThread? = null
    private var gestureHandler: Handler? = null
    private var midiManager: MidiManager? = null
    private var midiCallbackRegistered = false
    private var foregroundIsGenshin = false
    private var liveStartedAutomatically = false
    private var liveMidiDeviceId: Int? = null

    private val midiDeviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) {
            maybeStartAutoMidi(info)
        }

        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            if (info.id == liveMidiDeviceId) {
                stopLiveMidiPlaying()
                Toast.makeText(this@TouchService, "电子琴已断开，实时演奏已停止", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 当前加载的键位配置（播放阶段会用到）
    var layout: KeyLayout? = null
        private set

    /** 上次加载键位时的屏幕方向，用于检测旋转 */
    private var loadedRotation: Int? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        RuntimeLog.init(this)
        RuntimeLog.info("无障碍服务已连接")
        RuntimeLog.info("触摸预算=$maxStrokes，系统每批路径上限=${GestureDescription.getMaxStrokeCount()}；实际并发能力需设备验证")
        instance = this
        // 这里抛异常会让系统直接关掉无障碍服务，且表现为反复闪退，
        // 所以初始化失败只记日志，不让它冒出去。
        runCatching { reloadLayout() }.onFailure {
            Log.e(TAG, "加载键位失败", it)
        }
        runCatching { registerMidiDeviceCallback() }.onFailure {
            Log.e(TAG, "注册 MIDI 设备监听失败", it)
        }
    }

    /** 按当前屏幕方向重新加载精确校准的键位 */
    fun reloadLayout(): KeyLayout? {
        val rot = Display.rotation(this)
        loadedRotation = rot
        layout = runCatching { KeyLayoutStore(this).loadCurrentFor(rot) }
            .onFailure { Log.e(TAG, "读取键位配置失败", it) }
            .getOrNull()
        RuntimeLog.info(
            "加载键位：方向=${rotationName(rot)}，结果=" +
                    (layout?.let { "成功，${it.points.size} 个点，${it.screenWidth}x${it.screenHeight}" }
                        ?: "无配置")
        )
        return layout
    }

    /**
     * 取当前方向下可用的键位。屏幕转过了就先重新加载，
     * 避免拿着旧方向的坐标去点，键位整体错位。
     */
    fun currentLayout(): KeyLayout? {
        if (loadedRotation != Display.rotation(this)) reloadLayout()
        return layout
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString().orEmpty()
        if (packageName.isEmpty()) return
        when {
            isGenshinPackageName(packageName) -> {
                foregroundIsGenshin = true
                maybeStartAutoMidi()
            }
            isTransientSystemPackage(packageName) || packageName == this.packageName -> {
                // USB 授权框、系统栏和本服务的悬浮窗不代表用户离开了游戏。
            }
            else -> {
                foregroundIsGenshin = false
                if (liveMidiSession != null) {
                    stopLiveMidiPlaying()
                    Toast.makeText(this, "已离开原神，电子琴自动演奏已停止", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val currentRotation = runCatching { Display.rotation(this) }.getOrNull()
        val rotatedWhilePlaying = playingRotation != null &&
                currentRotation != null && currentRotation != playingRotation
        val rotatedWhileLive = livePlayingRotation != null &&
                currentRotation != null && currentRotation != livePlayingRotation
        // 屏幕旋转后原坐标失效，立刻换成对应方向的键位
        reloadLayout()
        calibration?.onDisplayConfigurationChanged()
        if ((rotatedWhilePlaying && player != null) ||
            (rotatedWhileLive && liveMidiSession != null)) {
            // 演奏中转屏：坐标系变了，继续点下去只会乱点
            stopPlaying()
            Toast.makeText(
                this,
                "屏幕方向改变，已停止演奏。\n请保持方向不变后重新开始；" +
                        "若要在新方向下演奏，需先为该方向校准一套键位。",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroy() {
        RuntimeLog.info("无障碍服务销毁")
        super.onDestroy()
        if (midiCallbackRegistered) {
            runCatching { midiManager?.unregisterDeviceCallback(midiDeviceCallback) }
        }
        midiCallbackRegistered = false
        midiManager = null
        stopPlaying()
        hideControlBar()
        calibration?.dismiss()
        calibration = null
        gestureHandler = null
        gestureHandlerThread?.quitSafely()
        gestureHandlerThread = null
        instance = null
    }

    @Suppress("DEPRECATION")
    private fun registerMidiDeviceCallback() {
        if (midiCallbackRegistered) return
        val manager = getSystemService(MIDI_SERVICE) as? MidiManager ?: return
        midiManager = manager
        manager.registerDeviceCallback(midiDeviceCallback, mainHandler)
        midiCallbackRegistered = true
    }

    /** 主界面出现时停止自动会话，防止用户在设置参数时误触屏幕。 */
    fun onHostActivityVisible() {
        foregroundIsGenshin = false
        if (liveMidiSession != null) stopLiveMidiPlaying()
    }

    private fun isTransientSystemPackage(name: String): Boolean {
        val lower = name.lowercase()
        return lower == "android" || lower.contains("systemui") ||
                lower.contains("permissioncontroller") || lower.contains("usb")
    }

    // ---------- 校准 ----------

    fun startCalibration(countdownSeconds: Int = 5) {
        if (calibration != null) return
        RuntimeLog.info("开始键位校准：倒计时=${countdownSeconds}s")
        val store = KeyLayoutStore(this)
        calibration = CalibrationOverlay(
            service = this,
            onDispatchTestTaps = { points, done -> runTestTaps(points, done) },
            onSave = { points, w, h, rot ->
                val profile = "默认"
                store.save(KeyLayout(profile, w, h, points, rot))
                reloadLayout()
                RuntimeLog.info("键位校准已保存：方向=${rotationName(rot)}，点数=${points.size}，屏幕=${w}x$h")
                // 覆盖层自己会 dismiss，这里再兜一次：残留的全屏窗口会吞掉后续手势，
                // 表现就是「只能弹一次」。
                calibration?.dismiss()
                calibration = null
                Toast.makeText(
                    this,
                    "键位已保存（$profile / ${rotationName(rot)}）",
                    Toast.LENGTH_SHORT
                ).show()
            },
            onCancel = {
                calibration?.dismiss()
                calibration = null
                Toast.makeText(this, "已取消校准", Toast.LENGTH_SHORT).show()
            }
        )
        // 立刻上屏，倒计时由覆盖层自己走。
        // 不要等倒计时结束再 addView：那时 App 已在后台，被冻结的进程添加窗口可能不会
        // 立即渲染，得划一下后台才出现。
        calibration?.show(countdownSeconds = countdownSeconds.coerceIn(5, 10))
    }

    /** 依次点击一遍所有记录的点，用于校准确认 */
    private fun runTestTaps(points: List<KeyPoint>, onDone: () -> Unit) {
        RuntimeLog.info("开始校准测试点击：${points.size} 个点")
        val startDelay = 500L
        val interval = 250L
        points.forEachIndexed { i, p ->
            mainHandler.postDelayed({ dispatchTap(p.x, p.y) }, startDelay + i * interval)
        }
        mainHandler.postDelayed({ onDone() }, startDelay + points.size * interval + 300)
    }

    // ---------- 点击派发 ----------

    /** 单点点击（供播放阶段调用） */
    fun tapPoint(x: Float, y: Float, holdMs: Long = 50L) = dispatchTap(x, y, holdMs)

    /**
     * 同时按下多个琴键。
     *
     * 无障碍手势同一时刻只能有一个在派发（后来的会取消前一个），
     * 所以和弦必须合成一个 GestureDescription、每个键一个 stroke。
     * 手机端最多 10 条路径，同时受当前系统每批手势的路径数上限约束。
     *
     * @return 派发是否成功提交
     */
    fun tapKeys(
        keyNames: List<String>,
        holdMs: Long,
        callback: GestureResultCallback? = null
    ): Boolean {
        val points = currentLayout()?.points ?: return false
        val byName = points.associateBy { it.name }
        val targets = keyNames.distinct().mapNotNull { byName[it] }.take(maxStrokes)
        if (targets.isEmpty()) return false

        val builder = GestureDescription.Builder()
        for (p in targets) {
            val path = Path().apply {
                moveTo(p.x, p.y)
                lineTo(p.x, p.y)
            }
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, holdMs))
        }
        return runCatching {
            dispatchGesture(builder.build(), callback, null)
        }.getOrDefault(false)
    }

    /** 单线程终结每个现存触点；不会产生新的 DOWN。 */
    private fun releaseAutomaticStrokes() {
        val batch = touchChain.endingBatch() ?: return
        try { attemptAutomaticGesture(batch, automaticDispatchMode) }
        finally { touchChain.clear() }
    }

    private fun ensureGestureHandler(): Handler {
        gestureHandler?.let { return it }
        val thread = HandlerThread(
            "LyreGestureDispatch",
            Process.THREAD_PRIORITY_DISPLAY
        ).apply { start() }
        gestureHandlerThread = thread
        return Handler(thread.looper).also { gestureHandler = it }
    }

    /** 只切换 dispatchGesture 的提交线程；播放器计时和等待始终留在后台。 */
    private fun submitAutomaticGesture(
        gesture: GestureDescription,
        callback: GestureResultCallback?,
        mode: AutomaticDispatchMode
    ): Boolean {
        val compatibilityHandler = ensureGestureHandler()
        val callbackHandler = when (mode) {
            AutomaticDispatchMode.MAIN_THREAD -> mainHandler
            AutomaticDispatchMode.LOW_JITTER,
            AutomaticDispatchMode.HANDLER_THREAD -> compatibilityHandler
        }
        fun submit(): Boolean = runCatching {
            dispatchGesture(gesture, callback, callbackHandler)
        }.onFailure {
            RuntimeLog.error("自动弹琴手势提交异常：模式=${mode.label}", it)
        }.getOrDefault(false)

        if (mode == AutomaticDispatchMode.LOW_JITTER) return submit()

        val targetHandler = when (mode) {
            AutomaticDispatchMode.HANDLER_THREAD -> compatibilityHandler
            AutomaticDispatchMode.MAIN_THREAD -> mainHandler
            AutomaticDispatchMode.LOW_JITTER -> error("已在上方处理")
        }
        if (Looper.myLooper() == targetHandler.looper) return submit()

        val posted = java.util.concurrent.CountDownLatch(1)
        val result = AtomicBoolean(false)
        val expired = AtomicBoolean(false)
        val task = Runnable {
            if (expired.get()) { posted.countDown(); return@Runnable }
            result.set(submit())
            posted.countDown()
        }
        if (mode == AutomaticDispatchMode.MAIN_THREAD) {
            targetHandler.postAtFrontOfQueue(task)
        } else {
            targetHandler.post(task)
        }
        return try {
            if (!posted.await(500L, TimeUnit.MILLISECONDS)) {
                expired.set(true)
                targetHandler.removeCallbacks(task)
                RuntimeLog.error("向${mode.label}投递自动弹琴手势超时")
                false
            } else {
                result.get()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** 续接状态只接受完成回调；取消/超时终止本次演奏，不重放已可能发声的音。 */
    private fun attemptAutomaticGesture(batch: TapBatch, mode: AutomaticDispatchMode): GestureAttempt {
        if (touchChain.needsBridge(batch)) {
            val bridge = touchChain.endingBatch(batch.noteIds.toSet())!!
            val result = attemptAutomaticGesture(bridge, mode)
            if (result != GestureAttempt.COMPLETED) return result
        }
        val points = currentLayout()?.points?.associate { it.name to TouchPoint(it.x, it.y) }
            ?: return GestureAttempt.SUBMIT_FAILED
        var prepared = touchChain.prepare(batch, points)
        if (prepared.requiresRestart) {
            RuntimeLog.info("持续触摸指针预算用尽，终结旧手势后恢复当前音符")
            val remaining = touchChain.batchAfterRestartRelease(batch)
            releaseAutomaticStrokes()
            if (remaining.keys.isEmpty()) return GestureAttempt.COMPLETED
            prepared = touchChain.prepare(remaining, points)
            check(!prepared.requiresRestart) { "单批次触点超出 Android 指针预算" }
        }
        val builder = GestureDescription.Builder()
        prepared.strokes.forEach { builder.addStroke(it) }
        val completed = java.util.concurrent.CountDownLatch(1)
        val outcome = AtomicReference(GestureAttempt.PENDING)
        val callback = object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) {
                outcome.compareAndSet(GestureAttempt.PENDING, GestureAttempt.COMPLETED)
                completed.countDown()
            }
            override fun onCancelled(d: GestureDescription?) {
                outcome.compareAndSet(GestureAttempt.PENDING, GestureAttempt.CANCELLED)
                completed.countDown()
            }
        }
        if (!submitAutomaticGesture(builder.build(), callback, mode)) return GestureAttempt.SUBMIT_FAILED
        val result = try {
            if (completed.await(prepared.durationMs + GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) outcome.get()
            else GestureAttempt.CALLBACK_TIMEOUT
        } catch (_: InterruptedException) {
            // 清除中断后 finally 才能等待终结触点。
            Thread.interrupted()
            GestureAttempt.CALLBACK_TIMEOUT
        }
        if (result == GestureAttempt.COMPLETED) {
            touchChain.commit(prepared)
        } else {
            // CANCELLED 已经由系统释放。超时则尝试终结已提交的新链，绝不重新按下。
            touchChain.clear()
            if (result == GestureAttempt.CALLBACK_TIMEOUT && prepared.held.isNotEmpty()) {
                val release = GestureDescription.Builder()
                prepared.held.values.forEach { old ->
                    val path = Path().apply { moveTo(old.end.x, old.end.y) }
                    release.addStroke(old.stroke.continueStroke(path, 0, 1, false))
                }
                val done = java.util.concurrent.CountDownLatch(1)
                val endCallback = object : GestureResultCallback() {
                    override fun onCompleted(d: GestureDescription?) { done.countDown() }
                    override fun onCancelled(d: GestureDescription?) { done.countDown() }
                }
                if (submitAutomaticGesture(release.build(), endCallback, mode))
                    done.await(GESTURE_TIMEOUT_MS + 1, TimeUnit.MILLISECONDS)
            }
        }
        return result
    }

    private fun dispatchTap(x: Float, y: Float, holdMs: Long = 50L) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, holdMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    // ---------- 演奏 ----------

    var player: Player? = null
        private set

    private var controlBar: PlayControlBar? = null
    private var currentSongName: String = ""
    private var currentNextAction: (() -> Unit)? = null
    private var currentStopAction: (() -> Unit)? = null
    private var automaticDispatchCount = 0
    private var automaticDispatchMode = AutomaticDispatchMode.LOW_JITTER
    private var dispatchModeConfirmed = false
    private var manuallyRequestedDispatchMode: AutomaticDispatchMode? = null
    private val dispatchModeStability = DispatchModeStability()
    private var lastProgressUiPostNanos = 0L

    /** 真正开始派发点击时的方向；null 表示还在开始前的倒计时。 */
    private var playingRotation: Int? = null

    private var liveMidiSession: LiveMidiSession? = null
    private var liveMidiControlBar: LiveMidiControlBar? = null
    private var livePlayingRotation: Int? = null
    private var liveGeneration = 0
    private var liveAutoSuspended = false
    fun refreshLiveMidiAutoConnect(enabled: Boolean) {
        liveAutoSuspended = !enabled
        if (enabled) maybeStartAutoMidi()
    }

    val isLiveMidiPlaying: Boolean get() = liveMidiSession != null

    /** 手动选择只作为待验证模式；连续成功前不会写入永久配置。 */
    fun selectAutomaticDispatchMode(mode: AutomaticDispatchMode) {
        manuallyRequestedDispatchMode = mode
        automaticDispatchMode = mode
        dispatchModeConfirmed = false
        dispatchModeStability.reset()
        RuntimeLog.info("用户选择自动演奏触摸模式：${mode.label}，等待连续成功验证")
    }

    fun clearAutomaticDispatchModeConfig() {
        AutomaticDispatchModeConfig.clear(this)
        manuallyRequestedDispatchMode = null
        automaticDispatchMode = AutomaticDispatchMode.LOW_JITTER
        dispatchModeConfirmed = false
        dispatchModeStability.reset()
        RuntimeLog.info("已清除自动演奏触摸模式配置，下次从低抖动模式重新测试")
    }

    private fun isDispatchSuccessEligibleForConfirmation(): Boolean {
        val expectedRotation = playingRotation ?: return false
        return foregroundIsGenshin &&
                runCatching { Display.rotation(this) == expectedRotation }.getOrDefault(false)
    }

    /** 演奏中显示悬浮控制条：用户人在游戏里，回不到主界面操作 */
    private fun showControlBar(songName: String, visible: Boolean = true) {
        currentSongName = songName
        if (controlBar == null) {
            controlBar = PlayControlBar(
                context = this,
                onPlayPause = {
                    val p = player ?: return@PlayControlBar
                    if (p.isPaused) p.resume() else p.pause()
                    controlBar?.setPaused(p.isPaused)
                },
                onStop = { stopPlaying() },
                onNext = {
                    val action = currentNextAction
                    stopAutomaticPlaying(keepControlBar = true)
                    controlBar?.showLoadingNext()
                    action?.invoke()
                },
                onClose = {
                    stopPlaying()
                    hideControlBar()
                },
                onSeek = { ratio -> player?.seekRatio(ratio) },
                onInteraction = { active -> player?.setInteracting(active) },
                onInsertLabel = { currentInsertLabelAction?.invoke() },
                onPrevious = {
                    val action = currentPreviousAction
                    stopAutomaticPlaying(keepControlBar = true)
                    controlBar?.showLoadingNext()
                    action?.invoke()
                }
            )
        }
        controlBar?.show(songName, visible)
    }

    private fun hideControlBar() {
        controlBar?.dismiss()
        controlBar = null
    }

    /**
     * 开始演奏。会先检查当前屏幕方向下有没有可用键位。
     *
     * @param onProgress 进度回调，在主线程调用
     * @param onFinish   结束回调，在主线程调用
     */
    fun startPlaying(
        chords: List<Chord>,
        songName: String = "",
        speed: Double = 1.0,
        startRatio: Double = 0.0,
        startDelayMs: Long = 0L,
        playParams: PlayParams = PlayParams(),
        onProgress: (Float, Double, Double) -> Unit = { _, _, _ -> },
        onNext: () -> Unit = {},
        onPrevious: () -> Unit = {},
        onInsertLabel: (Float) -> Unit = {},
        onStarted: () -> Unit = {},
        onStopRequested: () -> Unit = {},
        keepControlBarOnFinish: Boolean = false,
        onFinish: (Boolean) -> Unit = {}
    ): Boolean {
        RuntimeLog.info(
            "请求自动演奏：曲目=$songName，和弦=${chords.size}，速度=$speed，准备=${startDelayMs}ms"
        )
        // 这里【不能】按当前方向校验键位：点「开始演奏」时用户还在竖屏的 App 界面里，
        // 而键位是在游戏（横屏）下校准的。真正需要键位的是延迟结束、用户已切回游戏的那一刻，
        // 所以只在这里确认「至少校准过一套」，方向校验推迟到 startDelay 结束后。
        val store = KeyLayoutStore(this)
        val profile = store.getCurrentProfileName()
        if (profile == null || store.listRotations(profile).isEmpty()) {
            RuntimeLog.error("自动演奏拒绝：没有任何键位配置")
            Toast.makeText(this, "还没有校准过键位，请先完成键位校准", Toast.LENGTH_LONG).show()
            return false
        }
        // 悬浮条“下一首”会先停掉旧播放器，再异步解析新曲。此时复用已挂载窗口，
        // 避免应用在游戏后台 remove/add 后被系统错误压缩或延迟显示。
        val reuseControlBar = player == null && controlBar != null
        stopLiveMidiPlaying()
        if (!reuseControlBar) stopAutomaticPlaying()
        currentNextAction = onNext
        currentStopAction = onStopRequested
        currentPreviousAction = onPrevious
        currentInsertLabelAction = {
            player?.let { p -> if (p.total > 0) onInsertLabel((p.playTime / p.total).toFloat().coerceIn(0f, 1f)) }
        }
        automaticDispatchCount = 0
        lastProgressUiPostNanos = 0L
        val savedMode = AutomaticDispatchModeConfig.load(this)
        automaticDispatchMode = manuallyRequestedDispatchMode ?: savedMode
                ?: AutomaticDispatchMode.LOW_JITTER
        dispatchModeConfirmed = manuallyRequestedDispatchMode == null && savedMode != null
        dispatchModeStability.reset()
        RuntimeLog.info(
            "自动演奏派发策略：设备=${Build.MANUFACTURER} ${Build.MODEL}，" +
                    "模式=${automaticDispatchMode.label}，" +
                    if (dispatchModeConfirmed) "已确认" else "待连续成功验证"
        )

        lateinit var newPlayer: Player
        val callbacks = object : PlayerCallbacks {
            override fun onStarted() { mainHandler.post { if (player === newPlayer) onStarted() } }
            override fun releaseAll() { releaseAutomaticStrokes() }
            override fun dispatch(batch: TapBatch): Boolean {
                if (newPlayer.inputSuspended) return false
                val inputVersion = newPlayer.inputChangeVersion
                val sequence = ++automaticDispatchCount
                val recordSuccess = sequence <= 20 || sequence % 100 == 0
                var usedMode = automaticDispatchMode
                var outcome = GestureAttempt.SUBMIT_FAILED
                val candidates = listOf(automaticDispatchMode) + AutomaticDispatchMode.entries.filter { it != automaticDispatchMode }
                for (mode in candidates) {
                    if (newPlayer.inputSuspended) return false
                    usedMode = mode
                    outcome = attemptAutomaticGesture(batch, mode)
                    // 只对从未提交成功的批次尝试其它线程；取消/超时可能已经部分发声。
                    if (outcome != GestureAttempt.SUBMIT_FAILED) break
                    RuntimeLog.error("触摸#$sequence 未能提交：${mode.label}")
                }
                if (outcome == GestureAttempt.CANCELLED) {
                    // 系统可能先回调取消，再把悬浮条 DOWN 派给主线程；等其处理当前输入。
                    val uiCaughtUp = java.util.concurrent.CountDownLatch(1)
                    mainHandler.post { uiCaughtUp.countDown() }
                    uiCaughtUp.await(50L, TimeUnit.MILLISECONDS)
                    if (newPlayer.inputSuspended || inputVersion != newPlayer.inputChangeVersion) return false
                }

                if (outcome == GestureAttempt.COMPLETED && usedMode != automaticDispatchMode) {
                    automaticDispatchMode = usedMode
                    dispatchModeConfirmed = false
                    manuallyRequestedDispatchMode = null
                    dispatchModeStability.reset()
                    RuntimeLog.info("${usedMode.label}重试成功，继续验证稳定性")
                }

                if (outcome == GestureAttempt.COMPLETED) {
                    val eligible = isDispatchSuccessEligibleForConfirmation()
                    if (!dispatchModeConfirmed &&
                        dispatchModeStability.recordSuccess(usedMode, eligible)) {
                        AutomaticDispatchModeConfig.save(this@TouchService, usedMode)
                        dispatchModeConfirmed = true
                        manuallyRequestedDispatchMode = null
                        RuntimeLog.info(
                            "${usedMode.label}在原神前台连续成功5次，已永久记住当前设备模式"
                        )
                    }
                    if (recordSuccess) RuntimeLog.info(
                        "触摸#$sequence 完成：模式=${usedMode.label}，" +
                                "键=${batch.keys.joinToString()}，偏移=${batch.offsetsMs}"
                    )
                } else {
                    AutomaticDispatchModeConfig.clear(this@TouchService)
                    dispatchModeConfirmed = false
                    manuallyRequestedDispatchMode = null
                    RuntimeLog.error(
                        "触摸#$sequence 最终失败：$outcome，模式=${usedMode.label}，" +
                                "键=${batch.keys.joinToString()}，当前键位点数=${currentLayout()?.points?.size ?: 0}"
                    )
                    error("触摸输入中断：$outcome（${usedMode.label}）")
                }
                return true
            }

            override fun log(message: String) {
                RuntimeLog.error(message)
                mainHandler.post {
                    if (player === newPlayer) Toast.makeText(this@TouchService,
                        "$message。请确认原神乐器界面和无障碍服务后重新开始。", Toast.LENGTH_LONG).show()
                }
            }
            override fun onProgress(ratio: Float, playTime: Double, total: Double) {
                val now = System.nanoTime()
                // 结尾的 100% 进度始终发送；其余更新严格限制为每秒最多 5 次。
                if (ratio < 1f && now - lastProgressUiPostNanos < PROGRESS_UPDATE_INTERVAL_NS) {
                    return
                }
                lastProgressUiPostNanos = now
                mainHandler.post {
                    if (player !== newPlayer) return@post
                    controlBar?.updateProgress(currentSongName, playTime, total)
                    onProgress(ratio, playTime, total)
                }
            }

            override fun onFinish(completed: Boolean) {
                RuntimeLog.info(
                    "自动演奏结束：曲目=$songName，自然结束=$completed，已派发=$automaticDispatchCount"
                )
                mainHandler.post {
                    // “下一首”会先停止旧播放器再立刻启动新播放器；旧播放器迟到的
                    // 结束回调不能把新播放器和新悬浮条清掉。
                    if (player !== newPlayer) return@post
                    player = null
                    playingRotation = null
                    currentNextAction = null
                    if (completed && keepControlBarOnFinish) {
                        controlBar?.showLoadingNext()
                    } else {
                        hideControlBar()
                    }
                    if (completed) Toast.makeText(
                        this@TouchService, "演奏结束", Toast.LENGTH_SHORT
                    ).show()
                    onFinish(completed)
                }
            }
        }

        val effectiveParams = playParams.copy(maxStrokes = playParams.maxStrokes.coerceIn(1, maxStrokes))
        newPlayer = Player(chords, speed, startRatio, effectiveParams, callbacks)
        player = newPlayer
        // 留时间让用户切回游戏、打开风物之诗琴
        if (startDelayMs > 0) {
            // 在 App 仍在前台时先挂载不可见窗口，避免部分系统限制后台首次 addView；
            // 它会在倒计时内跟随屏幕从竖屏重排为横屏。
            showControlBar(songName, visible = reuseControlBar)
            Toast.makeText(
                this,
                "${startDelayMs / 1000} 秒后开始演奏，请切回游戏打开乐器。\n" +
                        "演奏期间请保持手机方向不变。",
                Toast.LENGTH_LONG
            ).show()
            mainHandler.postDelayed({
                // 这段延迟里用户可能已经点了停止
                if (player !== newPlayer) return@postDelayed
                // 此刻用户应该已经切回游戏，方向才是真实的演奏方向
                if (!ensureLayoutForCurrentRotation()) {
                    stopPlaying()
                    return@postDelayed
                }
                // 到这里游戏已切到横屏。此时再创建控制条，窗口会使用游戏的
                // 实际方向和尺寸；倒计时期间的竖屏旋转也不会被误判为演奏中转屏。
                playingRotation = Display.rotation(this)
                showControlBar(songName)
                Log.i(TAG, "开始演奏：${rotationName(playingRotation!!)}，控制条已显示")
                RuntimeLog.info("自动演奏正式开始：方向=${rotationName(playingRotation!!)}")
                newPlayer.start()
            }, startDelayMs)
        } else {
            if (!ensureLayoutForCurrentRotation()) {
                stopPlaying()
                return false
            }
            playingRotation = Display.rotation(this)
            showControlBar(songName)
            RuntimeLog.info("自动演奏正式开始：方向=${rotationName(playingRotation!!)}")
            newPlayer.start()
        }
        return true
    }

    /**
     * 连接实时电子琴。设备会立即打开，但倒计时结束、用户切到游戏后才接受音符。
     */
    @Suppress("DEPRECATION")
    private fun maybeStartAutoMidi(preferredDevice: MidiDeviceInfo? = null) {
        if (!foregroundIsGenshin || liveAutoSuspended || player != null || liveMidiSession != null || controlBar?.isShowing == true) return
        val settings = UserSettingsStore(this).loadLiveMidi()
        if (!settings.autoConnectEnabled) return
        // 自动触摸前必须确认当前实际横屏方向已经精确校准。
        if (currentLayout() == null) {
            Log.w(TAG, "即插即弹未启动：当前方向没有键位校准")
            return
        }
        val manager = midiManager ?: (getSystemService(MIDI_SERVICE) as? MidiManager) ?: return
        val preferred = preferredDevice?.takeIf {
            it.type == MidiDeviceInfo.TYPE_USB && it.outputPortCount > 0
        }
        val device = preferred ?: manager.devices.firstOrNull {
            it.type == MidiDeviceInfo.TYPE_USB && it.outputPortCount > 0
        } ?: return

        val started = startLiveMidi(
            deviceInfo = device,
            transpose = if (settings.transposeEnabled) settings.transpose else 0,
            fold = settings.octaveFoldEnabled,
            mergeSimultaneousNotes = settings.mergeSimultaneousNotes,
            mergeWindowMs = settings.mergeWindowMs,
            followRelease = settings.followRelease, sustainPedal = settings.sustainPedal,
            startDelayMs = 0L
        )
        if (started) {
            liveStartedAutomatically = true
            liveMidiDeviceId = device.id
            Toast.makeText(
                this,
                "即插即弹：正在连接 ${device.displayName()}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun startLiveMidi(
        deviceInfo: MidiDeviceInfo,
        transpose: Int = 0,
        fold: Boolean = true,
        mergeSimultaneousNotes: Boolean = true,
        mergeWindowMs: Int = 30,
        followRelease: Boolean = true,
        sustainPedal: Boolean = true,
        startDelayMs: Long = 5_000L
    ): Boolean {
        val store = KeyLayoutStore(this)
        val profile = store.getCurrentProfileName()
        if (profile == null || store.listRotations(profile).isEmpty()) {
            RuntimeLog.error("实时电子琴拒绝：没有任何键位配置")
            Toast.makeText(this, "还没有校准过键位，请先完成键位校准", Toast.LENGTH_LONG).show()
            return false
        }
        if (deviceInfo.outputPortCount <= 0) {
            Toast.makeText(this, "所选设备没有 MIDI 输出端口", Toast.LENGTH_LONG).show()
            return false
        }
        val manager = getSystemService(MIDI_SERVICE) as? MidiManager ?: run {
            Toast.makeText(this, "这台手机不支持 Android MIDI", Toast.LENGTH_LONG).show()
            return false
        }

        stopPlaying()
        liveAutoSuspended = false
        liveStartedAutomatically = false
        liveMidiDeviceId = deviceInfo.id
        val generation = ++liveGeneration
        val deviceName = deviceInfo.displayName()
        liveMidiControlBar = LiveMidiControlBar(this) { liveAutoSuspended = true; stopLiveMidiPlaying() }.also {
            // 在 App 前台预挂载，避免切入全屏游戏后系统阻止后台首次添加悬浮窗。
            it.show(deviceName, visible = false)
        }

        lateinit var newSession: LiveMidiSession
        newSession = LiveMidiSession(
            midiManager = manager,
            deviceInfo = deviceInfo,
            handler = mainHandler,
            transpose = transpose,
            fold = fold,
            // 只延迟起音；松键与踏板仍实时进入状态机。
            chordWindowMs = if (mergeSimultaneousNotes) mergeWindowMs.coerceIn(0, 200).toLong() else 0L,
            followRelease = followRelease, sustain = sustainPedal,
            maxStrokes = maxStrokes,
            onRelease = { releaseAutomaticStrokes() },
            onBatch = { batch ->
                if (!foregroundIsGenshin) error("请切回原神后重新连接")
                if (attemptAutomaticGesture(batch, automaticDispatchMode) != GestureAttempt.COMPLETED)
                    error("触摸派发失败，请重新连接电子琴")
            },
            onConnected = {
                if (liveMidiSession === newSession) {
                    Toast.makeText(this, "电子琴已连接：$deviceName", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { message ->
                mainHandler.post {
                    if (liveMidiSession === newSession) {
                        stopLiveMidiPlaying()
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
        liveMidiSession = newSession
        if (startDelayMs > 0) {
            Toast.makeText(
                this,
                "${startDelayMs / 1000} 秒后开启电子琴输入，请切回游戏打开乐器。",
                Toast.LENGTH_LONG
            ).show()
        }
        mainHandler.postDelayed({
            if (generation != liveGeneration || liveMidiSession !== newSession) return@postDelayed
            if (!ensureLayoutForCurrentRotation()) {
                stopLiveMidiPlaying()
                return@postDelayed
            }
            livePlayingRotation = Display.rotation(this)
            liveMidiControlBar?.show(deviceName, visible = true)
            newSession.enable()
            Toast.makeText(this, "实时电子琴已开启", Toast.LENGTH_SHORT).show()
        }, startDelayMs)
        return true
    }

    /**
     * 确认当前屏幕方向下有可用键位，没有就提示用户去校准这个方向。
     * 演奏真正开始前调用——那时用户已经切回游戏，方向才是有意义的。
     */
    private fun ensureLayoutForCurrentRotation(): Boolean {
        if (reloadLayout() != null) return true
        val rot = Display.rotation(this)
        val store = KeyLayoutStore(this)
        val done = store.getCurrentProfileName()
            ?.let { store.listRotations(it) }
            ?.map { rotationName(it) }
            ?.joinToString("、")
            .orEmpty()
        Toast.makeText(
            this,
            "当前是${rotationName(rot)}，还没有这个方向的键位" +
                    (if (done.isNotEmpty()) "（已校准：$done）" else "") +
                    "。\n请在这个方向下再校准一套键位。",
            Toast.LENGTH_LONG
        ).show()
        RuntimeLog.error("当前方向没有键位：${rotationName(rot)}，已校准=$done")
        return false
    }

    fun stopPlaying() {
        liveAutoSuspended = true
        currentStopAction?.invoke()
        currentStopAction = null
        stopLiveMidiPlaying()
        stopAutomaticPlaying()
    }

    /** 只停止实时电子琴，不触碰 MIDI 文件自动弹琴播放器和控制条。 */
    private fun stopLiveMidiPlaying() {
        liveGeneration++
        liveMidiSession?.close()
        liveMidiSession = null
        livePlayingRotation = null
        liveMidiDeviceId = null
        liveStartedAutomatically = false
        liveMidiControlBar?.dismiss()
        liveMidiControlBar = null
    }

    /** 只停止 MIDI 文件自动弹琴，不触碰实时电子琴会话。 */
    private fun stopAutomaticPlaying(keepControlBar: Boolean = false) {
        player?.let {
            it.stop()
        }
        player = null
        playingRotation = null
        currentNextAction = null
        if (!keepControlBar) hideControlBar()
    }
}
