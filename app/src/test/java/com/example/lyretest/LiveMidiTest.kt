package com.example.lyretest

import com.example.lyretest.midi.LiveMidiDecoder
import com.example.lyretest.midi.mapLiveNote
import com.example.lyretest.midi.isGenshinPackageName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveMidiDecoderTest {
    @Test
    fun `decodes note on across packets and running status`() {
        val notes = mutableListOf<Pair<Int, Int>>()
        val decoder = LiveMidiDecoder { note, channel -> notes += note to channel }

        decoder.feed(byteArrayOf(0x92.toByte(), 60))
        decoder.feed(byteArrayOf(100, 62, 90, 64, 0))

        assertEquals(listOf(60 to 2, 62 to 2), notes)
    }

    @Test
    fun `ignores system messages note off and realtime bytes`() {
        val notes = mutableListOf<Int>()
        val decoder = LiveMidiDecoder { note, _ -> notes += note }

        decoder.feed(byteArrayOf(
            0xF0.toByte(), 1, 2, 0xF8.toByte(), 0xF7.toByte(),
            0x80.toByte(), 60, 100,
            0x90.toByte(), 65, 0xF8.toByte(), 100
        ))

        assertEquals(listOf(65), notes)
    }
}

class LiveMidiMappingTest {
    @Test
    fun `maps middle C and supports transpose`() {
        assertEquals("A", mapLiveNote(60, 0, false))
        assertEquals("Q", mapLiveNote(60, 12, false))
        assertNull(mapLiveNote(61, 0, true))
    }

    @Test
    fun `folds notes outside lyre range`() {
        assertEquals("Z", mapLiveNote(36, 0, true))
        assertNull(mapLiveNote(36, 0, false))
    }
}

class GenshinPackageTest {
    @Test
    fun `recognizes official and channel Genshin packages`() {
        assertEquals(true, isGenshinPackageName("com.miHoYo.Yuanshen"))
        assertEquals(true, isGenshinPackageName("com.miHoYo.GenshinImpact"))
        assertEquals(true, isGenshinPackageName("com.miHoYo.ys.bilibili"))
        assertEquals(false, isGenshinPackageName("com.example.lyretest"))
        assertEquals(false, isGenshinPackageName("com.android.systemui"))
    }
}
