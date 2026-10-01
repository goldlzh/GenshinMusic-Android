package com.example.lyretest

import android.content.Context
import android.view.MotionEvent
import android.widget.ListView

/** 从 DOWN 就保留整次滑动，父 ScrollView 不得在 MOVE 时抢走曲库手势。 */
class SongListView(context: Context) : ListView(context) {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val ended = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
        if (!ended) parent?.requestDisallowInterceptTouchEvent(true)
        return try { super.dispatchTouchEvent(event) }
        finally { if (ended) parent?.requestDisallowInterceptTouchEvent(false) }
    }
}
