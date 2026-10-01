package com.example.lyretest

import com.example.lyretest.arrange.NOTE_MAP
import com.example.lyretest.midi.LiveMidiMessage
import com.example.lyretest.midi.LiveTouchState
import com.example.lyretest.player.TapBatch
import org.junit.Assert.*
import org.junit.Test

class LiveVoiceStealingTest {
    private fun on(pitch: Int, channel: Int = 0) = LiveMidiMessage(0x90 + channel, pitch, 100)
    private fun off(pitch: Int, channel: Int = 0) = LiveMidiMessage(0x80 + channel, pitch)
    private fun pedal(down: Boolean, channel: Int = 0) = LiveMidiMessage(0xB0 + channel, 64, if (down) 127 else 0)
    private fun ended(batch: TapBatch) = batch.keys.filterIndexed { index, _ -> !batch.continues[index] }
    private fun ids(batch: TapBatch) = batch.keys.zip(batch.noteIds).toMap()

    @Test fun `equal time FIFO follows message order and repeated overflow keeps surviving identities`() {
        val state = LiveTouchState(maxStrokes = 2)
        state.feed(on(60), 0.0); state.feed(on(48), 0.0)
        val first = state.frame(0.0)!!
        assertEquals(listOf("A", "Z"), first.keys)
        state.feed(on(72), .1)
        assertEquals(listOf("A"), ended(state.frame(.1)!!))
        val second = state.frame(.101)!!
        assertEquals(listOf("Z", "Q"), second.keys)
        assertEquals(ids(first)["Z"], ids(second)["Z"])
        state.feed(on(62), .2)
        val bridge = state.frame(.2)!!
        assertEquals(listOf("Z"), ended(bridge)); assertEquals(1L, bridge.durationMs)
        val third = state.frame(.201)!!
        assertEquals(listOf("Q", "S"), third.keys)
        assertEquals(ids(second)["Q"], ids(third)["Q"])
        assertEquals(third.keys, state.frame(.5)!!.keys)
    }

    @Test fun `stolen pedal voices do not return when capacity opens or pedal is released`() {
        val state = LiveTouchState(maxStrokes = 2)
        state.feed(pedal(true), 0.0)
        state.feed(on(60), 0.0); state.feed(on(48), 0.0); state.frame(0.0)
        state.feed(off(60), .05); state.feed(off(48), .05)
        state.feed(on(72, 1), .1)
        assertEquals(listOf("A"), ended(state.frame(.1)!!)); state.frame(.101)
        state.feed(off(72, 1), .2)
        assertEquals(listOf("Q"), ended(state.frame(.2)!!))
        assertEquals(listOf("Z"), state.frame(.3)!!.keys)
        state.feed(pedal(false), .4)
        assertEquals(listOf("Z"), ended(state.frame(.4)!!))
        assertNull(state.frame(.5))
        state.feed(pedal(true), .6); assertNull(state.frame(.6))
    }

    @Test fun `folded sources use one slot and stolen held sources retain their note off FIFO positions`() {
        val state = LiveTouchState(maxStrokes = 1)
        state.feed(on(36), 0.0); state.feed(on(48, 1), .001)
        val first = state.frame(.001)!!; assertEquals(listOf("Z"), first.keys)
        state.feed(on(60), .1)
        assertEquals(listOf("Z"), ended(state.frame(.1)!!)); state.frame(.101)
        state.feed(on(36), .2)
        assertEquals(listOf("A"), ended(state.frame(.2)!!))
        val replacement = state.frame(.201)!!
        assertEquals(listOf("Z"), replacement.keys); assertNotEquals(first.noteIds, replacement.noteIds)
        state.feed(off(36), .3) // Consume the stolen channel-0 note, not its new repeated note.
        assertEquals(replacement.noteIds, state.frame(.3)!!.noteIds)
        state.feed(off(48, 1), .31) // The other folded owner was stolen too.
        assertEquals(listOf(true), state.frame(.31)!!.continues)
        state.feed(off(36), .4)
        assertEquals(listOf("Z"), ended(state.frame(.4)!!))
        assertNull(state.frame(.5)) // The stolen A is still physically held but must stay silent.
    }

    @Test fun `more than ten pending keys are emitted in receive order without stealing unplayed voices`() {
        val pitches = NOTE_MAP.keys.toList().reversed()
        val state = LiveTouchState(maxStrokes = 10)
        pitches.forEach { state.feed(on(it), 0.0) }
        val frames = listOf(0.0, .001, .002, .003, .004).map { state.frame(it)!! }
        assertTrue(frames.all { it.keys.size <= 10 })
        assertEquals(pitches.take(10).map { NOTE_MAP.getValue(it) }, frames[0].keys)
        assertEquals(frames[0].keys, ended(frames[1])); assertEquals(1L, frames[1].durationMs)
        assertEquals(pitches.drop(10).take(10).map { NOTE_MAP.getValue(it) }, frames[2].keys)
        assertEquals(listOf(NOTE_MAP.getValue(pitches[10])), ended(frames[3]))
        val seen = mutableSetOf<Int>()
        val starts = frames.flatMap { batch ->
            batch.keys.indices.filter { seen.add(batch.noteIds[it]) }.map { batch.keys[it] }
        }
        assertEquals(pitches.map { NOTE_MAP.getValue(it) }, starts)
        val finalKeys = pitches.takeLast(10).map { NOTE_MAP.getValue(it) }
        assertEquals(finalKeys, frames[4].keys)
        assertEquals(finalKeys, state.frame(.1)!!.keys)
        pitches.forEach { state.feed(off(it), .2) }
        assertEquals(finalKeys, ended(state.frame(.2)!!)); assertNull(state.frame(.3))
    }

    @Test fun `overflow waits for the merge deadline and keeps the earliest merged message first`() {
        val state = LiveTouchState(mergeWindowMs = 30, maxStrokes = 2)
        state.feed(on(60), 0.0); state.feed(on(48), .01)
        state.frame(.03)
        state.feed(on(72), .1); state.feed(on(62), .11)
        assertEquals(listOf(true, true), state.frame(.12)!!.continues)
        val bridge = state.frame(.13)!!
        assertEquals(listOf("A", "Z"), ended(bridge)); assertEquals(1L, bridge.durationMs)
        assertEquals(listOf("Q", "S"), state.frame(.131)!!.keys)
    }
}
