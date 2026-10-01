package com.example.lyretest.player

/** 手机端最多保持十个琴键；超限时结束最早的旧音，为新起音腾出触点。 */
const val REQUESTED_TOUCH_LIMIT = 10

/** 所有输出层共用十点预算，同时兼容路径数上限更低的系统。 */
fun effectiveTouchLimit(systemMaxStrokes: Int): Int =
    minOf(REQUESTED_TOUCH_LIMIT, systemMaxStrokes.coerceAtLeast(1))
