package com.example.lyretest

import com.example.lyretest.storage.KeyLayout
import com.example.lyretest.storage.KeyPoint
import org.junit.Assert.assertEquals
import org.junit.Test

/** 屏幕方向与键位绑定的数据模型 */
class RotationLayoutTest {

    private fun layout(rot: Int) = KeyLayout(
        "默认", 2400, 1080,
        listOf(KeyPoint("Q", 100f, 200f), KeyPoint("M", 2000f, 900f)),
        rot
    )

    @Test
    fun `layout preserves exact calibrated coordinates and rotation`() {
        val original = layout(1)
        assertEquals(1, original.rotation)
        assertEquals(100f, original.points[0].x, 1e-4f)
        assertEquals(200f, original.points[0].y, 1e-4f)
    }
}
