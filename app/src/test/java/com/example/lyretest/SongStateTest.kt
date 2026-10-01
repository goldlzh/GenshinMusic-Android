package com.example.lyretest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class SongStateTest {
    @Test fun `queue stops at end but manual next wraps and random never repeats with alternatives`() {
        val a=File("A.mid"); val b=File("B.mid"); val queue=listOf(a,b)
        assertNull(nextSong(queue,b,"SEQUENTIAL"))
        assertEquals(a,nextSong(queue,b,"SEQUENTIAL",1))
        assertEquals(b,nextSong(queue,b,"SINGLE"))
        assertEquals(b,nextSong(queue,a,"RANDOM",1))
        assertEquals(b,nextSong(queue,a,"RANDOM",-1))
        assertEquals(a,nextSong(queue,File("removed.mid"),"SEQUENTIAL"))
    }
    @Test fun `manual memory has priority and automatic remains available`() {
        val a=SongArrangeSettings(speed=.5,durationMode="melody")
        val m=SongArrangeSettings(speed=2.0,durationMode="chord")
        val r=SongRecord(manual=m,automatic=a)
        assertEquals(m,r.memory());assertEquals(a,r.copy(manual=null).memory())
    }
    @Test fun `label movement updates remembered start and deletion retains last position`() {
        val m=SongArrangeSettings(startRatio=.1,startLabelId="a")
        val r=SongRecord(manual=m,automatic=m,labels=listOf(SongLabel("a","副歌",.7)))
        assertEquals(.7,r.startRatio(m),0.0)
        val deleted=r.withoutLabel("a")
        assertNull(deleted.manual!!.startLabelId);assertEquals(.7,deleted.manual!!.startRatio,0.0)
        assertEquals(.7,deleted.automatic!!.startRatio,0.0)
    }
    @Test fun `three sorts use actual timestamps with alphabetical ties and unplayed last`() {
        val a=File("Alpha.mid");val b=File("Beta.mid");val c=File("Gamma.mid")
        val records=mapOf(a.path to SongRecord(100,300),b.path to SongRecord(200,100),c.path to SongRecord(200,0))
        val files=listOf(c,b,a)
        assertEquals(listOf(a,b,c),sortSongFiles(files,SongSort.NAME,records){it.lowercase()})
        assertEquals(listOf(b,c,a),sortSongFiles(files,SongSort.ADDED,records){it.lowercase()})
        assertEquals(listOf(a,b,c),sortSongFiles(files,SongSort.PLAYED,records){it.lowercase()})
        val queue=sortSongFiles(files,SongSort.PLAYED,records){it.lowercase()}
        sortSongFiles(files,SongSort.PLAYED,records+(c.path to SongRecord(200,400))){it.lowercase()}
        assertEquals(listOf(a,b,c),queue)
    }
}
