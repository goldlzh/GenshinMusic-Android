package com.example.lyretest

import android.content.Context
import android.os.Build

/** MIDI 文件自动演奏使用的无障碍手势提交方式；实时电子琴不经过这里。 */
enum class AutomaticDispatchMode(val label: String, val displayLabel: String) {
    LOW_JITTER("低抖动线程", "低抖动模式"),
    HANDLER_THREAD("HandlerThread兼容", "兼容模式（HandlerThread）"),
    MAIN_THREAD("主线程兼容", "主线程模式")
}

/**
 * 模式稳定性计数器。模式变化、失败或离开游戏都会打断连续成功计数，
 * 防止把切回 LyreTest 后偶然完成的一次手势误记成可用模式。
 */
class DispatchModeStability(
    private val successesToConfirm: Int = 5,
    private val failuresToForget: Int = 3
) {
    private var trackedMode: AutomaticDispatchMode? = null
    private var consecutiveSuccesses = 0
    private var consecutiveFailures = 0

    fun recordSuccess(mode: AutomaticDispatchMode, eligibleForConfirmation: Boolean): Boolean {
        switchTo(mode)
        consecutiveFailures = 0
        if (!eligibleForConfirmation) {
            consecutiveSuccesses = 0
            return false
        }
        consecutiveSuccesses++
        return consecutiveSuccesses >= successesToConfirm
    }

    fun recordFailure(mode: AutomaticDispatchMode): Boolean {
        switchTo(mode)
        consecutiveSuccesses = 0
        consecutiveFailures++
        return consecutiveFailures >= failuresToForget
    }

    fun reset() {
        trackedMode = null
        consecutiveSuccesses = 0
        consecutiveFailures = 0
    }

    private fun switchTo(mode: AutomaticDispatchMode) {
        if (trackedMode == mode) return
        trackedMode = mode
        consecutiveSuccesses = 0
        consecutiveFailures = 0
    }
}

/** 只保存经过连续成功验证的设备模式；旧版本的一次成功记录会被忽略。 */
object AutomaticDispatchModeConfig {
    private const val PREFS = "gesture_dispatch_mode"
    private const val CONFIG_VERSION = 2
    private const val KEY_VERSION = "confirmed_version"
    private const val KEY_DEVICE = "confirmed_device"
    private const val KEY_MODE = "confirmed_mode"

    fun load(context: Context): AutomaticDispatchMode? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_VERSION, 0) != CONFIG_VERSION) return null
        if (prefs.getString(KEY_DEVICE, null) != deviceSignature()) return null
        return runCatching {
            AutomaticDispatchMode.valueOf(prefs.getString(KEY_MODE, null).orEmpty())
        }.getOrNull()
    }

    fun save(context: Context, mode: AutomaticDispatchMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .clear()
            .putInt(KEY_VERSION, CONFIG_VERSION)
            .putString(KEY_DEVICE, deviceSignature())
            .putString(KEY_MODE, mode.name)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun deviceSignature(): String =
        "${Build.MANUFACTURER.trim()}|${Build.MODEL.trim()}".lowercase()
}
