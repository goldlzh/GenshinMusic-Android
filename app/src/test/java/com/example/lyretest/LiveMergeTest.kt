package com.example.lyretest

import com.example.lyretest.midi.*
import org.junit.Assert.*
import org.junit.Test

class LiveMergeTest {
    private fun on(n:Int=60,ch:Int=0)=LiveMidiMessage(0x90+ch,n,100)
    private fun off(n:Int=60,ch:Int=0)=LiveMidiMessage(0x80+ch,n)

    @Test fun `configurable windows merge down and retain independent up`() {
        for(ms in listOf(1L,10L,30L,75L,200L)) {
            val s=LiveTouchState(mergeWindowMs=ms);val end=ms/1000.0
            s.feed(on(60),0.0);s.feed(on(64),end*.8)
            assertNull(s.frame(end-.00001))
            val first=s.frame(end)!!;assertEquals(listOf("A","D"),first.keys)
            s.feed(off(60),end+.1)
            val released=s.frame(end+.1)!!
            assertEquals(mapOf("A" to false,"D" to true),released.keys.zip(released.continues).toMap())
            s.feed(off(64),end+.2);assertEquals(listOf(false),s.frame(end+.2)!!.continues)
        }
    }

    @Test fun `window anchored at first note while old key release is immediate`() {
        val s=LiveTouchState(mergeWindowMs=100)
        s.feed(on(),0.0);s.frame(.1)
        s.feed(on(62),.2);s.feed(off(),.21)
        assertEquals(listOf(false),s.frame(.21)!!.continues)
        s.feed(on(64),.29);assertNull(s.frame(.299))
        assertEquals(setOf("S","D"),s.frame(.3)!!.keys.toSet())
        s.feed(on(65),.301);assertFalse("F" in s.frame(.4)!!.keys)
        assertTrue("F" in s.frame(.401)!!.keys)
    }

    @Test fun `quick release before merge deadline still gets forty ms press`() {
        val s=LiveTouchState(mergeWindowMs=200)
        s.feed(on(),0.0);s.feed(off(),.01)
        assertNull(s.frame(.199));assertEquals(listOf(true),s.frame(.2)!!.continues)
        val end=s.frame(.221)!!;assertEquals(listOf(false),end.continues)
        assertEquals(listOf(19L),end.durationsMs);assertNull(s.frame(.25))
    }

    @Test fun `pedal up and panic do not wait for merge window`() {
        val s=LiveTouchState(mergeWindowMs=100)
        s.feed(on(),0.0);s.frame(.1);s.feed(LiveMidiMessage(0xB0,64,127),.2);s.feed(off(),.3)
        s.feed(on(64),.4);s.feed(LiveMidiMessage(0xB0,64,0),.41)
        assertEquals(listOf(false),s.frame(.41)!!.continues)
        s.feed(LiveMidiMessage(0xB0,123),.42);assertNull(s.frame(.6))
    }

    @Test fun `zero disables waiting and folded releases keep independent owners`() {
        val direct=LiveTouchState(mergeWindowMs=0);direct.feed(on(),0.0)
        assertEquals(listOf("A"),direct.frame(0.0)!!.keys)
        val s=LiveTouchState(mergeWindowMs=30)
        s.feed(on(36),0.0);s.feed(on(48,1),.01);val first=s.frame(.03)!!
        assertEquals(listOf("Z"),first.keys)
        s.feed(off(36),.1);assertEquals(listOf(true),s.frame(.1)!!.continues)
        s.feed(off(48,1),.2);assertEquals(listOf(false),s.frame(.2)!!.continues)
    }
}
