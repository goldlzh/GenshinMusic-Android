package com.example.lyretest

import com.example.lyretest.arrange.NOTE_MAP
import com.example.lyretest.arrange.ScheduledNote
import com.example.lyretest.arrange.limitPolyphony
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolyphonyLimitTest {
    @Test fun `oldest held melody is released for a new accompaniment note`() {
        val notes = listOf(
            ScheduledNote(0.0, 2.0, "Q", true),
            ScheduledNote(.1, 1.0, "A", false),
            ScheduledNote(.2, .5, "S", false)
        )
        val result = limitPolyphony(notes, limit = 2).associateBy { it.key }

        assertEquals(setOf("Q", "A", "S"), result.keys)
        assertEquals(.2, result.getValue("Q").end, 1e-9)
        assertEquals(notes[1], result.getValue("A"))
        assertEquals(notes[2], result.getValue("S"))
    }

    @Test fun `successive overflow admits every later note and releases voices in onset order`() {
        val notes = listOf("Q", "A", "S", "D", "F", "G").mapIndexed { i, key ->
            ScheduledNote(i / 10.0, 2.0, key, i % 2 == 0)
        }
        val result = limitPolyphony(notes, limit = 2).associateBy { it.key }

        assertEquals(notes.map { it.key }.toSet(), result.keys)
        notes.forEachIndexed { i, note ->
            val actual = result.getValue(note.key)
            assertEquals(note.start, actual.start, 1e-9)
            assertEquals(if (i + 2 < notes.size) notes[i + 2].start else note.end, actual.end, 1e-9)
        }
        notes.forEach { onset ->
            assertTrue(result.values.count { it.start <= onset.start && it.end > onset.start } <= 2)
        }
    }

    @Test fun `a naturally released note frees capacity without truncating another held note`() {
        val notes = listOf(
            ScheduledNote(0.0, .2, "Q", true),
            ScheduledNote(.1, 1.0, "A", false),
            ScheduledNote(.2, .5, "S", false)
        )

        assertEquals(notes, limitPolyphony(notes, limit = 2))
    }

    @Test fun `more than ten simultaneous notes use stable input order and remove zero lengths`() {
        val keys = NOTE_MAP.values.toList().reversed().take(13)
        val notes = keys.mapIndexed { i, key -> ScheduledNote(0.0, 1.0, key, i < 3) }
        val result = limitPolyphony(notes, limit = 10)

        assertEquals(10, result.size)
        assertEquals(keys.takeLast(10).toSet(), result.map { it.key }.toSet())
        assertTrue(result.none { it.melody })
        assertTrue(result.all { it.start == 0.0 && it.end == 1.0 })
        assertEquals(result, limitPolyphony(notes, limit = 10))
    }

    @Test fun `oldest means earliest onset even when input notes are out of order`() {
        val notes = listOf(
            ScheduledNote(.3, 1.0, "F", false),
            ScheduledNote(0.0, 1.0, "Q", false),
            ScheduledNote(.1, 1.0, "A", true),
            ScheduledNote(.2, 1.0, "S", false)
        )
        val result = limitPolyphony(notes, limit = 2).associateBy { it.key }

        assertEquals(.2, result.getValue("Q").end, 1e-9)
        assertEquals(.3, result.getValue("A").end, 1e-9)
        assertEquals(1.0, result.getValue("S").end, 1e-9)
        assertEquals(1.0, result.getValue("F").end, 1e-9)
    }
}
