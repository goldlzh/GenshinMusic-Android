package com.example.lyretest

import com.example.lyretest.player.*
import com.example.lyretest.arrange.ScheduledNote
import com.example.lyretest.arrange.NOTE_MAP
import com.example.lyretest.arrange.limitPolyphony
import com.example.lyretest.midi.LiveMidiMessage
import com.example.lyretest.midi.LiveTouchState
import org.junit.Assert.*
import org.junit.Test

private data class FakeStroke(val id: Int, val spec: TouchStroke<FakeStroke>)

/** 模拟 AOSP 的续接身份、起点和空事件检查，避免只测乐谱而漏测触摸协议。 */
private class TouchDevice(private val maxStrokes: Int = REQUESTED_TOUCH_LIMIT) {
    var nextId = 1
    var downs = 0
    var ups = 0
    private var active = emptyMap<Int, TouchPoint>()
    val chain = TouchContinuations<FakeStroke>(maxStrokes) { FakeStroke(nextId++, it) }
    val points = com.example.lyretest.arrange.NOTE_MAP.values
        .mapIndexed { i, key -> key to TouchPoint(100f + i * 100, 200f) }.toMap()

    fun submit(prepared: PreparedTouch<FakeStroke>): Boolean {
        val strokes = prepared.strokes
        assertTrue("计划不能超过配置的手势预算", strokes.size <= maxStrokes)
        val continuing = strokes.filter { it.spec.previous != null }
        if (continuing.isNotEmpty()) {
            if (continuing.map { it.spec.previous!!.id }.toSet() != active.keys) return false
            if (strokes.count { it.spec.offsetMs == 0L } != active.size) return false
            if (continuing.any { active[it.spec.previous!!.id] != it.spec.from }) return false
        }
        // 静止且没有按下/松开的续接没有 MotionEvent，会被系统取消。
        if (strokes.none { it.spec.previous == null || !it.spec.continues || it.spec.from != it.spec.to }) return false
        downs += strokes.count { it.spec.previous == null }
        ups += strokes.count { !it.spec.continues }
        active = strokes.filter { it.spec.continues }.associate { it.id to it.spec.to }
        chain.commit(prepared)
        return true
    }
    fun play(batch: TapBatch): PreparedTouch<FakeStroke> {
        if (chain.needsBridge(batch)) play(chain.endingBatch(batch.noteIds.toSet())!!)
        var prepared = chain.prepare(batch, points)
        if (prepared.requiresRestart) {
            val remaining = chain.batchAfterRestartRelease(batch)
            play(chain.endingBatch()!!)
            chain.clear()
            prepared = chain.prepare(remaining, points)
        }
        assertFalse(prepared.requiresRestart)
        assertTrue("手势必须产生有效事件并续接所有旧触点", submit(prepared))
        return prepared
    }
}

class TouchContinuationsTest {
    @Test fun `stationary continuation reproduces alternating cancellation from runtime log`() {
        val d = TouchDevice()
        val first = d.play(TapBatch(listOf("A"), 80, noteIds=listOf(7), continues=listOf(true)))
        val previous = first.strokes.single()
        val end = previous.spec.to
        val bad = FakeStroke(999, TouchStroke(previous, end, end, 0, 80, true))
        assertFalse(d.submit(PreparedTouch(listOf(bad), emptyMap(), 80)))
    }

    @Test fun `five hundred long note continuations produce only one key down`() {
        val d = TouchDevice()
        repeat(500) {
            val prepared = d.play(TapBatch(listOf("A"), 80, noteIds=listOf(7), continues=listOf(true)))
            assertEquals(80, prepared.durationMs)
            assertEquals(1f, kotlin.math.abs(prepared.strokes.single().spec.from.x - prepared.strokes.single().spec.to.x), 0f)
        }
        d.play(d.chain.endingBatch()!!)
        assertEquals(1, d.downs); assertEquals(1, d.ups); assertNull(d.chain.endingBatch())
    }

    @Test fun `new chord while holding melody starts after continuation step zero`() {
        val d = TouchDevice()
        d.play(TapBatch(listOf("A"), 80, noteIds=listOf(1), continues=listOf(true)))
        val p = d.play(TapBatch(listOf("A", "S", "D"), 80, noteIds=listOf(1,2,3), continues=listOf(true,true,true)))
        assertEquals(listOf(0L,1L,1L), p.strokes.map { it.spec.offsetMs })
        assertEquals(80, p.durationMs)
        assertEquals(3,d.downs);assertEquals(0,d.ups)
        d.play(TapBatch(listOf("A", "D"), 80, noteIds=listOf(1,3), continues=listOf(true,true)))
        assertEquals(3,d.downs); assertEquals(1,d.ups) // 只释放已结束的 S。
    }

    @Test fun `dense frame rebuilds exhausted pointer budget once`() {
        val d = TouchDevice(10); val names=d.points.keys.toList()
        d.play(TapBatch(names.take(10), 80, noteIds=(0..9).toList(), continues=List(10){true}))
        d.play(TapBatch(names.take(9)+names[10], 80, noteIds=(0..8).toList()+10, continues=List(10){true}))
        assertEquals(20,d.downs);assertEquals(10,d.ups)
    }

    @Test fun `natural all up gaps reset pointer budget without unnecessary retriggers`() {
        val d=TouchDevice()
        repeat(100) { d.play(TapBatch(listOf("A","S","D"),40,noteIds=listOf(it*3,it*3+1,it*3+2))) }
        assertEquals(300,d.downs);assertEquals(300,d.ups)
    }

    @Test fun `ten simultaneous touches preserve identities until released`() {
        val d=TouchDevice();val names=d.points.keys.take(10)
        val batch=TapBatch(names,80,noteIds=(0 until 10).toList(),continues=List(10){true})
        val first=d.play(batch)
        val next=d.play(batch)
        assertEquals(10,first.strokes.size);assertEquals(10,d.downs);assertEquals(0,d.ups)
        assertEquals(first.strokes,next.strokes.map{it.spec.previous})
        d.play(d.chain.endingBatch()!!)
        assertEquals(10,d.ups)
    }

    @Test fun `full chord replacement releases the victim before starting its replacement`() {
        val d=TouchDevice();val names=d.points.keys.toList()
        d.play(TapBatch(names.take(10),80,noteIds=(0 until 10).toList(),continues=List(10){true}))
        val replacement=TapBatch(names.slice(1..10),80,noteIds=(1..10).toList(),continues=List(10){true})
        assertTrue(d.chain.needsBridge(replacement))
        val bridge=d.play(d.chain.endingBatch(replacement.noteIds.toSet())!!)
        assertEquals(1,d.ups)
        assertFalse(bridge.strokes.first().spec.continues)
        val next=d.play(replacement)
        assertEquals((1..10).toSet(),next.held.keys)
        assertFalse(0 in next.held)
        d.play(d.chain.endingBatch()!!);assertEquals(d.downs,d.ups)
    }

    @Test fun `long melody with successive chords cannot exhaust native pointers silently`() {
        val d=TouchDevice()
        d.play(TapBatch(listOf("A"),80,noteIds=listOf(0),continues=listOf(true)))
        repeat(30) { i ->
            d.play(TapBatch(listOf("A","S","D"),40,noteIds=listOf(0,1+i*2,2+i*2),continues=listOf(true,false,false)))
        }
        assertTrue(d.downs in 61..70)
    }

    @Test fun `pointer restart does not press an already terminated old voice again`() {
        val d=TouchDevice(); val names=d.points.keys.toList()
        d.play(TapBatch(names.take(10),80,noteIds=(0..9).toList(),continues=List(10){true}))
        d.play(TapBatch(names.take(9),80,noteIds=(0..8).toList(),continues=List(9){true}))
        val replacement=TapBatch(names.take(9)+names[10],80,
            durationsMs=listOf(1L)+List(9){80L},noteIds=(0..8).toList()+10,
            continues=listOf(false)+List(9){true})
        assertTrue(d.chain.prepare(replacement,d.points).requiresRestart)
        val next=d.play(replacement)
        assertEquals((1..8).toSet()+10,next.held.keys)
        assertEquals(9,next.strokes.size)
        assertEquals(19,d.downs) // 旧 10 音各一次，重建 8 音与新音；被淘汰音不重放。
        assertEquals(10,d.ups)
        d.play(d.chain.endingBatch()!!); assertEquals(d.downs,d.ups)
    }

    @Test fun `restart keeps normal old tails and newly pressed one millisecond notes`() {
        val d=TouchDevice(3)
        d.play(TapBatch(listOf("A","S","D"),80,noteIds=listOf(0,1,2),continues=List(3){true}))
        d.play(TapBatch(listOf("A","S"),80,noteIds=listOf(0,1),continues=List(2){true}))
        val batch=TapBatch(listOf("A","S","F"),80,durationsMs=listOf(20,80,1),
            noteIds=listOf(0,1,3),continues=listOf(false,true,false))
        val next=d.play(batch)
        assertEquals(3,next.strokes.size)
        assertEquals(listOf(20L,80L,1L),next.strokes.map { it.spec.durationMs })
        assertEquals(setOf(1),next.held.keys)
        assertEquals(6,d.downs)
        d.play(d.chain.endingBatch()!!); assertEquals(d.downs,d.ups)
    }

    @Test fun `cancelled candidate is never used as continuation parent`() {
        val d=TouchDevice()
        val first=d.play(TapBatch(listOf("A"),80,noteIds=listOf(1),continues=listOf(true)))
        val abandoned=d.chain.prepare(TapBatch(listOf("A"),80,noteIds=listOf(1),continues=listOf(true)),d.points)
        val release=d.chain.prepare(d.chain.endingBatch()!!,d.points)
        assertSame(first.strokes.single(),release.strokes.single().spec.previous)
        assertNotSame(abandoned.strokes.single(),release.strokes.single().spec.previous)
        d.chain.clear()
        assertNull(d.chain.endingBatch())
    }

    @Test fun `file schedule holds melody once across accompaniment and overdue frames`() {
        val d=TouchDevice()
        val planner=GesturePlanner(listOf(ScheduledNote(0.0,2.0,"A",true),
            ScheduledNote(.12,.16,"S",false),ScheduledNote(.27,.31,"D",false)))
        var cursor=0.0
        while(cursor<2.0-1e-7) {
            val frame=planner.frame(cursor)
            if(frame.batch.keys.isNotEmpty()) d.play(frame.batch)
            cursor=frame.end
        }
        assertEquals(3,d.downs);assertEquals(3,d.ups)
    }

    @Test fun `file FIFO transitions stay within ten physical strokes and never restore the victim`() {
        val d=TouchDevice(); val names=d.points.keys.toList()
        val notes=names.take(11).mapIndexed { i,key -> ScheduledNote(i*.1,2.0,key,i==0) }
        val limited=limitPolyphony(notes)
        assertEquals(1.0,limited.first { it.key==names[0] }.end,1e-9)
        val planner=GesturePlanner(limited)
        var cursor=0.0
        while (cursor<planner.total-1e-7) {
            val frame=planner.frame(cursor)
            if (frame.batch.keys.isNotEmpty()) {
                val played=d.play(frame.batch)
                if (cursor>=1.0-1e-7) assertFalse(played.held.values.any { it.key==names[0] })
            }
            cursor=frame.end
        }
        assertNull(d.chain.endingBatch()); assertEquals(d.downs,d.ups)
    }

    @Test fun `live FIFO release bridge and replacement obey the physical touch budget`() {
        val state=LiveTouchState(); val d=TouchDevice(); val pitches=NOTE_MAP.keys.toList()
        pitches.take(10).forEach { state.feed(LiveMidiMessage(0x90,it,100),0.0) }
        d.play(state.frame(0.0)!!)
        pitches.slice(10..11).forEach { state.feed(LiveMidiMessage(0x90,it,100),.08) }
        val bridge=state.frame(.08)!!
        assertEquals(1L,bridge.durationMs)
        assertEquals(listOf(false,false)+List(8){true},bridge.continues)
        d.play(bridge)
        val next=d.play(state.frame(.081)!!)
        assertEquals(pitches.slice(2..11).map { NOTE_MAP.getValue(it) }.toSet(),
            next.held.values.map { it.key }.toSet())
        pitches.slice(2..11).forEach { state.feed(LiveMidiMessage(0x80,it),.2) }
        d.play(state.frame(.2)!!)
        assertNull(state.frame(.3)) // 被抢占的前两音仍收到不到 note_off，也不能复活。
        assertNull(d.chain.endingBatch()); assertEquals(d.downs,d.ups)
    }
}
