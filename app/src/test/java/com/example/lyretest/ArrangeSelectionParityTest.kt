package com.example.lyretest

import com.example.lyretest.arrange.arrangeDetailed
import com.example.lyretest.arrange.autoSelectChannels
import com.example.lyretest.midi.ChannelInfo
import com.example.lyretest.midi.MidiFileData
import com.example.lyretest.midi.MidiParser
import com.example.lyretest.midi.NoteEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class ArrangeSelectionParityTest {
    private fun melodyFixture(): MidiFileData {
        val notes = listOf(81, 48, 60).flatMapIndexed { track, pitch ->
            listOf(NoteEvent(0.0, pitch, track, track), NoteEvent(0.5, pitch + 2, track, track))
        }.sortedBy { it.time }
        return MidiFileData(3, notes, (0..2).map { ChannelInfo(it, setOf(0), 2) }, 0)
    }

    @Test fun `filtered manual melody track falls back to highest remaining track`() {
        val result = arrangeDetailed(melodyFixture(), channels = setOf(1, 2), forcedTrack = 0,
            transpose = 0, enableFold = false, blockGliss = false)!!

        assertEquals("多音轨模式：自动选择音轨 2 作为主旋律", result.melodyDescription)
        assertEquals(listOf(60, 62), result.tagged.filter { it.isMelody }.map { it.note })
        assertEquals(listOf(48, 50), result.tagged.filterNot { it.isMelody }.map { it.note })
    }

    @Test fun `melody only still plays when channel filtering removes manual melody track`() {
        val result = arrangeDetailed(melodyFixture(), channels = setOf(1, 2), forcedTrack = 0,
            transpose = 0, enableFold = false, blockGliss = false, melodyOnly = true)

        assertNotNull("剩余的两个音轨中仍有可演奏旋律", result)
        assertEquals(listOf("A", "S"), result!!.chords.flatMap { it.keys }.map { it.key })
        assertTrue(result.tagged.all { it.isMelody })
    }

    @Test fun `manual melody track is retained when it survives channel filtering`() {
        val result = arrangeDetailed(melodyFixture(), channels = setOf(1, 2), forcedTrack = 1,
            transpose = 0, enableFold = false, blockGliss = false, melodyOnly = true)!!

        assertEquals("多音轨模式：手动指定音轨 1 作为主旋律", result.melodyDescription)
        assertEquals(listOf("Z", "X"), result.chords.flatMap { it.keys }.map { it.key })
    }

    @Test fun `channel ties use first sounding order across the merged timeline`() {
        // 物理轨顺序为 7,1,8,6,2；发声顺序为 8,6,7,1,2，与通道编号也不同。
        // 通道 1 的提前 program_change、鼓组、零力度 note_on 都不能改变并列顺序。
        val midi = MidiParser.parse(smf(
            track(event(0, 0xC1, 40), event(30, 0x97, 60, 90), event(10, 0x87, 60, 0),
                event(10, 0x91, 62, 90), event(10, 0x81, 62, 0)),
            track(event(0, 0x98, 64, 90), event(5, 0x88, 64, 0),
                event(5, 0x96, 65, 90), event(5, 0x86, 65, 0),
                event(5, 0x99, 36, 90), event(2, 0x90, 60, 0)),
            track(event(60, 0x92, 67, 90), event(5, 0x82, 67, 0),
                event(15, 0x92, 69, 90), event(5, 0x82, 69, 0))
        ))

        assertEquals(listOf(8, 6, 7, 1, 2), midi.channels.map { it.channel })
        assertEquals(setOf(40), midi.channels.single { it.channel == 1 }.programs)
        assertEquals(1, midi.drumNotes)
        // 音符数优先；只在计数相同时沿用第一次发声顺序。
        assertEquals(setOf(2, 8, 6), autoSelectChannels(midi.channels, 3))
        assertEquals(setOf(2, 8, 6), arrangeDetailed(midi, transpose = 0, blockGliss = false)!!.keptChannels)
    }

    @Test fun `simultaneous first notes break channel ties by track then event order`() {
        val midi = MidiParser.parse(smf(
            track(event(20, 0x97, 60, 90), event(0, 0x91, 62, 90)),
            track(event(20, 0x98, 64, 90)),
            track(event(20, 0x96, 65, 90))
        ))

        assertEquals(listOf(7, 1, 8, 6), midi.channels.map { it.channel })
        assertEquals(setOf(7, 1, 8), autoSelectChannels(midi.channels, 3))
    }

    private fun event(delta: Int, vararg message: Int): IntArray {
        require(delta in 0..127) // 这些小型 SMF 用例只需要单字节 delta。
        return intArrayOf(delta, *message)
    }

    private fun track(vararg events: IntArray): ByteArray = ByteArrayOutputStream().apply {
        events.forEach { event -> event.forEach { write(it) } }
        write(byteArrayOf(0, 0xFF.toByte(), 0x2F, 0))
    }.toByteArray()

    private fun smf(vararg tracks: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeBytes("MThd")
            out.writeInt(6)
            out.writeShort(1)
            out.writeShort(tracks.size)
            out.writeShort(480)
            tracks.forEach { track ->
                out.writeBytes("MTrk")
                out.writeInt(track.size)
                out.write(track)
            }
        }
        return bytes.toByteArray()
    }
}
