package com.example.lyretest

import com.example.lyretest.arrange.*
import com.example.lyretest.midi.MidiParser
import com.example.lyretest.player.GesturePlanner
import com.example.lyretest.player.REQUESTED_TOUCH_LIMIT
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PerformanceParityTest {
    @Test fun `desktop and Android produce identical note boundaries for all four modes`() {
        val lines = javaClass.classLoader!!.getResourceAsStream("desktop_cases.tsv")!!.bufferedReader().readLines()
        var header = emptyList<String>()
        var raw = mutableListOf<TaggedNote>()
        var expected = mutableListOf<ScheduledNote>()
        var cases = 0
        fun check() {
            if (header.isEmpty()) return
            val case = "case ${header[1]} ${header[2]} fold=${header[3]} speed=${header[4]} pedal=${header[5]}"
            assertEquals(case, header[7].toInt(), findBestTranspose(raw, false))
            assertEquals(case, header[8].toInt(), findBestTranspose(raw, true))
            val chords = finalizeChords(raw, header[6].toInt(), header[3] == "1",
                durationMode = header[2], sustainPedal = header[5] == "1").orEmpty()
            val actual = buildSchedule(chords, header[4].toDouble())
            assertEquals(case, expected.size, actual.size)
            expected.zip(actual).forEachIndexed { i, (a, b) ->
                assertEquals("$case note $i key", a.key, b.key)
                assertEquals("$case note $i melody", a.melody, b.melody)
                assertEquals("$case note $i start", a.start, b.start, 1e-7)
                assertEquals("$case note $i end", a.end, b.end, 1e-7)
            }
            cases++
        }
        for (line in lines) {
            val p = line.split('\t')
            when (p[0]) {
                "C" -> { check(); header = p; raw = mutableListOf(); expected = mutableListOf() }
                "N" -> raw.add(TaggedNote(p[1].toDouble(), p[3].toInt(), p[4] == "1", p[2].toDouble(), p[5].toDouble()))
                "E" -> expected.add(ScheduledNote(p[1].toDouble(), p[2].toDouble(), p[3], p[4] == "1"))
            }
        }
        check(); assertEquals(576, cases)
    }

    @Test fun `172 songs match desktop parsing transposition and final timelines`() {
        val songs = File("../ref_python/GenshinMusic/songs")
        val reference = javaClass.classLoader!!.getResource("desktop_corpus.tsv")
        // The public repository contains synthetic fixtures, but no personal MIDI library.
        assumeTrue("Local MIDI corpus and its desktop reference are optional", reference != null && songs.isDirectory)
        val lines = reference!!.openStream().bufferedReader().use { it.readLines() }
        assumeTrue("The complete local MIDI corpus is required", lines.all { File(songs, it.substringBefore('\t')).isFile })
        assertEquals(172, lines.size)
        for (line in lines) {
            val p = line.split('\t'); val name = p[0]
            val midi = MidiParser.parse(File(songs, name).readBytes())
            assertEquals("$name count", p[1].toInt(), midi.notes.size)
            assertEquals("$name start", p[2].toDouble(), midi.notes.sumOf { it.time }, .0001)
            assertEquals("$name release", p[3].toDouble(), midi.notes.sumOf { it.end }, .0001)
            assertEquals("$name pedal", p[4].toDouble(), midi.notes.sumOf { it.pedalEnd }, .0001)
            val r = arrangeDetailed(midi, enableFold = p[8] == "1", durationMode = p[7], sustainPedal = true)
            val tagged = r?.tagged.orEmpty()
            assertEquals("$name no fold", p[5].toInt(), findBestTranspose(tagged, false))
            assertEquals("$name fold", p[6].toInt(), findBestTranspose(tagged, true))
            val schedule = buildSchedule(r?.chords.orEmpty(), p[9].toDouble())
            assertEquals("$name scheduled count", p[10].toInt(), schedule.size)
            assertEquals("$name duration", p[11].toDouble(), schedule.maxOfOrNull { it.end } ?: 0.0, .0001)
            assertEquals("$name scheduled start", p[12].toDouble(), schedule.sumOf { it.start }, .0001)
            assertEquals("$name scheduled end", p[13].toDouble(), schedule.sumOf { it.end }, .0001)
            for (limit in listOf(5, REQUESTED_TOUCH_LIMIT)) {
                val limited = limitPolyphony(schedule, limit)
                val planner = GesturePlanner(limited, limit)
                var position = 0.0
                while (position < planner.total - EPSILON) {
                    val frame = planner.frame(position)
                    assertTrue("$name excessive strokes (limit=$limit)", frame.batch.keys.size <= limit)
                    assertTrue("$name stalled frame (limit=$limit)", frame.end > position)
                    position = frame.end
                }
            }
        }
    }
}
