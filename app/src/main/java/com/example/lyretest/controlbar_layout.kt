package com.example.lyretest

/** 自动演奏悬浮条在不同宽度下的可选内容；进度、暂停和关闭始终显示。 */
data class ControlBarVisibility(
    val showTitle: Boolean,
    val showNext: Boolean,
    val showStop: Boolean
)

fun controlBarVisibility(widthDp: Int): ControlBarVisibility = ControlBarVisibility(
    showTitle = widthDp >= 470,
    showNext = widthDp >= 390,
    showStop = widthDp >= 300
)
