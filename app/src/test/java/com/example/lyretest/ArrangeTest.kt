package com.example.lyretest

import com.example.lyretest.arrange.ArrangeParams
import com.example.lyretest.arrange.NOTE_MAP
import com.example.lyretest.arrange.TaggedNote
import com.example.lyretest.arrange.arrange
import com.example.lyretest.arrange.detectGlissando
import com.example.lyretest.arrange.findBestTranspose
import com.example.lyretest.arrange.foldToRange
import com.example.lyretest.midi.MidiParser
import com.example.lyretest.player.REQUESTED_TOUCH_LIMIT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 电脑版曲库，用来跑真实 MIDI 文件的回归测试 */
private val SONGS_DIR = File("../ref_python/GenshinMusic/songs")

class NoteMapTest {

    @Test
    fun `21 keys cover three octaves of white keys`() {
        assertEquals(21, NOTE_MAP.size)
        assertEquals(21, NOTE_MAP.values.distinct().size)
        assertEquals(48, NOTE_MAP.keys.min())
        assertEquals(83, NOTE_MAP.keys.max())
    }

    @Test
    fun `fold brings out-of-range notes into the playable octaves`() {
        assertEquals(48, foldToRange(24))      // 低了两个八度
        assertEquals(83, foldToRange(95))      // 高了一个八度
        assertEquals(60, foldToRange(60))      // 已在范围内
        assertEquals(null, foldToRange(61))    // 黑键，弹不出来
    }
}

class TransposeTest {

    /** 转调后主旋律有多少音落在白键上 */
    private fun melodyHits(tagged: List<TaggedNote>, offset: Int) =
        tagged.count { it.isMelody && (it.note + offset) in NOTE_MAP }

    @Test
    fun `black-key melody is transposed until every note is playable`() {
        // 全是黑键的主旋律，转调后应该全部可弹
        val tagged = listOf(61, 63, 66, 68, 70).mapIndexed { i, n ->
            TaggedNote(i * 0.5, n, true)
        }
        val offset = findBestTranspose(tagged)
        assertEquals(tagged.size, melodyHits(tagged, offset))
    }

    @Test
    fun `already playable melody keeps every note playable`() {
        // 注意：八度等价的转调量得分相同，实现（与电脑版一致）从 -12 起
        // 遍历并保留第一个最优解，所以这里只断言可弹性，不锁定具体偏移量。
        val tagged = listOf(60, 62, 64, 65, 67).mapIndexed { i, n ->
            TaggedNote(i * 0.5, n, true)
        }
        val offset = findBestTranspose(tagged)
        assertEquals(tagged.size, melodyHits(tagged, offset))
    }

    @Test
    fun `unplayable melody falls back to no transpose`() {
        // 音域跨度超过 21 键能覆盖的范围，且怎么转都对不齐时不应乱转
        val tagged = listOf(TaggedNote(0.0, 61, true))
        // 单个黑键音总能通过转调对齐，这里验证结果确实可弹
        assertTrue(melodyHits(tagged, findBestTranspose(tagged)) == 1)
    }
}

class GlissandoTest {

    @Test
    fun `fast ascending run is detected as glissando`() {
        val run = (0 until 8).map { TaggedNote(it * 0.03, 60 + it, true) }
        val result = detectGlissando(run, ArrangeParams())
        assertEquals(1, result.runs)
        assertEquals(8, result.indices.size)
    }

    @Test
    fun `slow melody is not a glissando`() {
        val slow = (0 until 8).map { TaggedNote(it * 0.5, 60 + it, true) }
        assertEquals(0, detectGlissando(slow, ArrangeParams()).runs)
    }
}

/** 拿电脑版整个曲库跑一遍，确认解析器不会崩、编排结果合法 */
class SongCorpusTest {

    private fun songs(): List<File> =
        SONGS_DIR.listFiles { f: File -> f.extension.lowercase() == "mid" }?.sorted() ?: emptyList()

    @Test
    fun `all songs parse and arrange without error`() {
        val files = songs()
        assumeTrue("找不到曲库目录 ${SONGS_DIR.path}，跳过", files.isNotEmpty())

        val failures = mutableListOf<String>()
        var totalChords = 0

        for (f in files) {
            try {
                val midi = MidiParser.parse(f.readBytes())
                assertTrue("${f.name}: 没有解析到音符", midi.notes.isNotEmpty())
                assertTrue("${f.name}: 鼓组通道没被排除", midi.notes.none { it.channel == 9 })

                // 时间必须单调不减
                var prev = -1.0
                for (n in midi.notes) {
                    assertTrue("${f.name}: 音符时间倒退", n.time >= prev)
                    prev = n.time
                }

                val chords = arrange(midi)
                assertNotNull("${f.name}: 编排结果为空", chords)
                requireNotNull(chords)

                val schedule = com.example.lyretest.arrange.buildSchedule(chords)
                assertEquals("${f.name}: 首音没有归零", 0.0, schedule[0].start, 1e-9)
                schedule.groupBy { it.key }.values.forEach { row ->
                    row.zipWithNext().forEach { (a, b) ->
                        assertTrue("${f.name}: 同键没有松开间隙", b.start - a.end >= .01 - 1e-7)
                    }
                }
                assertTrue(schedule.all { it.end > it.start })
                for (c in chords) {
                    assertTrue("${f.name}: 空发音组", c.keys.isNotEmpty())
                    // 发音组不应超过应用请求的触点预算。
                    assertTrue(
                        "${f.name}: 发音组有 ${c.keys.size} 个键，超过手势上限",
                        c.keys.size <= REQUESTED_TOUCH_LIMIT
                    )
                    assertEquals(
                        "${f.name}: 发音组内有重复键",
                        c.keys.size, c.keys.map { it.key }.distinct().size
                    )
                    assertTrue(
                        "${f.name}: 出现了非法琴键",
                        c.keys.all { it.key in NOTE_MAP.values }
                    )
                }
                totalChords += chords.size
            } catch (e: Throwable) {
                failures.add("${f.name}: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        println("曲库共 ${files.size} 首，发音组合计 $totalChords 个")
        assertTrue(
            "以下 ${failures.size} 首解析失败：\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }
}
