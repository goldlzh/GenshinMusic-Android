package com.example.lyretest
import com.example.lyretest.arrange.NOTE_MAP
import com.example.lyretest.midi.*
import org.junit.Assert.*
import org.junit.Test
class LiveDurationTest {
    @Test fun `decoder emits release pedal and reset across packets`() {
        val out=mutableListOf<LiveMidiMessage>();val d=LiveMidiDecoder(onMessage={out.add(it)})
        d.feed(byteArrayOf(0x90.toByte(),60));d.feed(byteArrayOf(1,60,0,0xB0.toByte(),64,127,0xFF.toByte()))
        assertEquals(listOf(LiveMidiMessage(0x90,60,1),LiveMidiMessage(0x90,60,0),LiveMidiMessage(0xB0,64,127),LiveMidiMessage(0xFF)),out)
    }
    @Test fun `live hold ends on note off and fixed mode lasts 40 ms`() {
        val s=LiveTouchState();s.feed(LiveMidiMessage(0x90,60,1),0.0)
        val first=s.frame(0.0)!!;assertEquals(listOf(true),first.continues)
        assertEquals(first.noteIds,s.frame(.2)!!.noteIds)
        s.feed(LiveMidiMessage(0x80,60),.3);assertEquals(listOf(false),s.frame(.3)!!.continues)
        val f=LiveTouchState(followRelease=false);f.feed(LiveMidiMessage(0x90,60,127),0.0)
        f.frame(0.0);assertEquals(listOf(false),f.frame(.02)!!.continues);assertNull(f.frame(.041))
    }
    @Test fun `pedal and folded sources independently own held contact`() {
        val s=LiveTouchState();s.feed(LiveMidiMessage(0x90,36,100),0.0);s.feed(LiveMidiMessage(0x91,48,100),.001)
        val first=s.frame(.001)!!;assertEquals(listOf("Z"),first.keys)
        s.feed(LiveMidiMessage(0x80,36),.2);assertEquals(listOf(true),s.frame(.2)!!.continues)
        s.feed(LiveMidiMessage(0xB1,64,127),.21);s.feed(LiveMidiMessage(0x81,48),.3)
        assertEquals(listOf(true),s.frame(.4)!!.continues)
        s.feed(LiveMidiMessage(0xB1,64,0),.5);assertEquals(listOf(false),s.frame(.5)!!.continues)
    }
    @Test fun `retrigger releases old identity before assigning new identity`() {
        val s=LiveTouchState();s.feed(LiveMidiMessage(0x90,60,100),0.0);val old=s.frame(0.0)!!
        s.feed(LiveMidiMessage(0x90,60,100),.1);assertEquals(listOf(false),s.frame(.1)!!.continues)
        assertNull(s.frame(.102));assertNotEquals(old.noteIds,s.frame(.12)!!.noteIds)
    }
    @Test fun `eleventh key releases the oldest contact and keeps the other nine identities`() {
        val s=LiveTouchState(maxStrokes=10)
        listOf(48,50,52,53,55,57,59,60,62,64).forEach{s.feed(LiveMidiMessage(0x90,it,100),0.0)}
        val old=s.frame(0.0)!!;s.feed(LiveMidiMessage(0x90,72,100),.1)
        val bridge=s.frame(.1)!!;assertEquals(10,bridge.keys.size);assertEquals(old.noteIds,bridge.noteIds)
        assertEquals(listOf("Z"),bridge.keys.filterIndexed{i,_->!bridge.continues[i]})
        assertEquals(1L,bridge.durationMs)
        val next=s.frame(.101)!!;assertEquals(10,next.keys.size)
        assertFalse("Z" in next.keys);assertTrue("Q" in next.keys)
        val oldIds=old.keys.zip(old.noteIds).toMap()
        next.keys.zip(next.noteIds).filter{it.first!="Q"}.forEach{(key,id)->assertEquals(oldIds[key],id)}
    }
    @Test fun `live state defaults to ten contacts and admits overflow without waiting for note off`() {
        val pitches=NOTE_MAP.keys.toList()
        val s=LiveTouchState()
        pitches.take(10).forEach{s.feed(LiveMidiMessage(0x90,it,100),0.0)}
        val old=s.frame(0.0)!!
        assertEquals(10,old.keys.size);assertTrue(old.continues.all{it})
        s.feed(LiveMidiMessage(0x90,pitches.last(),100),.1)
        val release=s.frame(.1)!!
        assertEquals(10,release.keys.size);assertFalse(release.continues[release.keys.indexOf("Z")])
        assertEquals(1L,release.durationMs);assertFalse("U" in release.keys)
        val admitted=s.frame(.101)!!
        assertEquals(10,admitted.keys.size);assertTrue("U" in admitted.keys);assertFalse("Z" in admitted.keys)
    }
}
