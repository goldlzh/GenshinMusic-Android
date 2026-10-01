package com.example.lyretest
import com.example.lyretest.arrange.*
import com.example.lyretest.player.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
private fun note(t:Double,k:String,d:Double=.04,s:Boolean=false,m:Boolean=false)=Chord(t,listOf(ChordKey(k,0.0,m,d,s)))
private open class Recorder:PlayerCallbacks {
    val batches=CopyOnWriteArrayList<TapBatch>();val done=CountDownLatch(1);val started=CountDownLatch(1);val released=CountDownLatch(1)
    @Volatile var completed=false
    override fun dispatch(batch:TapBatch):Boolean {batches.add(batch);return true}
    override fun onStarted(){started.countDown()}
    override fun releaseAll(){released.countDown()}
    override fun onProgress(ratio:Float,playTime:Double,total:Double){}
    override fun onFinish(completed:Boolean){this.completed=completed;done.countDown()}
    fun await()=assertTrue(done.await(3,TimeUnit.SECONDS))
}
class PlaybackTest {
    @Test fun `toolbar interaction suspends input without changing manual pause choice`() {
        val r=Recorder();val p=Player(listOf(note(0.0,"A",1.0,true)),1.0,0.0,PlayParams(),r)
        try {
            p.setInteracting(true);p.start();assertTrue(r.started.await(1,TimeUnit.SECONDS))
            Thread.sleep(40);assertTrue(r.batches.isEmpty());assertFalse(p.isPaused)
            p.pause();p.setInteracting(false);Thread.sleep(40);assertTrue(r.batches.isEmpty());assertTrue(p.isPaused)
            p.resume();Thread.sleep(100);assertFalse(r.batches.isEmpty())
        } finally {p.stop();r.await()}
    }
    @Test fun `dispatch failure stops progress and never advances playlist as completed`() {
        val r=object:Recorder(){override fun dispatch(batch:TapBatch):Boolean {super.dispatch(batch);error("触摸取消")}}
        val p=Player(listOf(note(0.0,"A",2.0,true),note(1.0,"S")),1.0,0.0,PlayParams(),r)
        p.start();r.await();assertFalse(r.completed);assertEquals(1,r.batches.size)
        assertEquals(0.0,p.playTime,1e-6);assertEquals(0L,r.released.count)
    }
    @Test fun `speed scales offsets while fixed presses remain 40 ms`() {
        val notes=buildSchedule(listOf(Chord(0.0,listOf(ChordKey("A",0.0,false),ChordKey("S",.05,false),ChordKey("D",.08,false)))),2.0)
        val b=GesturePlanner(notes).frame(0.0).batch
        assertEquals(listOf(0L,25L,40L),b.offsetsMs);assertEquals(listOf(40L,40L,40L),b.durationsMs)
    }
    @Test fun `long touch continues with same identity until release`() {
        val p=GesturePlanner(listOf(ScheduledNote(0.0,.21,"Q",true)))
        val a=p.frame(0.0);val b=p.frame(a.end);val c=p.frame(b.end)
        assertEquals(a.batch.noteIds,b.batch.noteIds);assertEquals(a.batch.noteIds,c.batch.noteIds)
        assertEquals(listOf(true),a.batch.continues);assertEquals(listOf(true),b.batch.continues)
        assertEquals(listOf(false),c.batch.continues);assertEquals(listOf(50L),c.batch.durationsMs)
    }
    @Test fun `same key retriggers after 10 ms without cutting held melody for accompaniment`() {
        val n=buildSchedule(listOf(note(0.0,"A",1.0,true),note(.2,"A",1.0,true)))
        assertEquals(2,n.size);assertEquals(.19,n[0].end,1e-9)
        assertEquals(1,buildSchedule(listOf(note(0.0,"A",1.0,true,true),note(.2,"A"))).size)
    }
    @Test fun `touch limiting keeps the latest simultaneous notes and later notes`() {
        val notes=NOTE_MAP.values.mapIndexed{i,k->ScheduledNote(0.0,1.0,k,i==0)}+ScheduledNote(2.0,3.0,"A",false)
        val limited=limitPolyphony(notes,limit=10)
        assertEquals(11,limited.size);assertFalse(limited.any{it.melody});assertEquals(2.0,limited.last().start,1e-9)
        assertEquals(NOTE_MAP.values.toList().takeLast(10).toSet(),limited.filter{it.start==0.0}.map{it.key}.toSet())
    }
    @Test fun `planning keeps ten simultaneous notes within the default budget`() {
        val chords=NOTE_MAP.values.take(10).map{note(0.0,it,1.0,true)}
        val scheduled=buildSchedule(chords)
        assertEquals(10,PlayParams().maxStrokes)
        assertEquals(10,limitPolyphony(scheduled).size)
        assertEquals(10,Player(chords,1.0,0.0,PlayParams(),Recorder()).notes.size)
        val first=GesturePlanner(scheduled).frame(0.0).batch
        assertEquals(10,first.keys.size)
        assertTrue(first.offsetsMs.all{it==0L});assertTrue(first.continues.all{it})
    }
    @Test fun `player admits eleventh note after ending the oldest held melody`() {
        val keys=NOTE_MAP.values.toList()
        val chords=listOf(note(0.0,keys[0],1.0,true,true))+
            keys.subList(1,10).map{note(.1,it,1.0,true)}+note(.2,keys[10],1.0,true)
        val planned=Player(chords,1.0,0.0,PlayParams(),Recorder()).notes
        assertEquals(11,planned.size)
        assertEquals(.2,planned.single{it.key==keys[0]}.end,1e-9)
        assertEquals(.2,planned.single{it.key==keys[10]}.start,1e-9)
        assertEquals(10,planned.count{it.start<=.2 && it.end>.2})
    }
    @Test fun `dense sequential strokes are split and overdue notes skipped`() {
        val p=GesturePlanner((0 until 16).map{ScheduledNote(it*.004,it*.004+.002,"A",false)},maxStrokes=10)
        val a=p.frame(0.0);val b=p.frame(a.end)
        assertEquals(10,a.batch.keys.size);assertEquals(6,b.batch.keys.size)
        val q=GesturePlanner(listOf(ScheduledNote(0.0,.02,"A",false),ScheduledNote(.06,.1,"S",true)))
        assertEquals(listOf("S"),q.frame(0.0,.05).batch.keys)
    }
    @Test fun `duration includes release and speed is capped at 2`() {
        assertEquals(1.0,Player(listOf(note(0.0,"A",2.0,true)),4.0,0.0,PlayParams(),Recorder()).total,1e-9)
        assertEquals(.44,Player(listOf(note(0.0,"A"),note(.8,"S")),2.0,0.0,PlayParams(),Recorder()).total,1e-9)
    }
    @Test fun `plays original onsets and finishes after release`() {
        val r=Recorder();val p=Player(listOf(note(0.0,"A"),note(.12,"S"),note(.24,"D")),1.0,0.0,PlayParams(),r)
        p.start();r.await();assertTrue(r.completed);assertEquals(setOf("A","S","D"),r.batches.flatMap{it.keys}.toSet())
        assertEquals(.28,p.playTime,1e-9)
    }
    @Test fun `pause releases and seek resume skips remaining old note`() {
        val r=Recorder();val p=Player(listOf(note(0.0,"A",2.0,true)),1.0,0.0,PlayParams(),r)
        try {
            p.start();assertTrue(r.started.await(1,TimeUnit.SECONDS));Thread.sleep(40);p.pause()
            assertTrue(r.released.await(1,TimeUnit.SECONDS));val n=r.batches.size;Thread.sleep(40);assertEquals(n,r.batches.size)
            p.seek(1.0);Thread.sleep(30);assertEquals(1.0,p.playTime,1e-6);p.resume();Thread.sleep(60)
            assertEquals(n,r.batches.size)
        } finally {p.stop();r.await()}
        assertFalse(r.completed)
    }
    @Test fun `middle start skips old tails and includes the exact boundary onset`() {
        val r=Recorder()
        val p=Player(listOf(note(0.0,"A",.4,true),note(.2,"D",.1,true)),1.0,.5,PlayParams(),r)
        p.start();r.await()
        assertTrue(r.completed)
        assertEquals(setOf("D"),r.batches.flatMap{it.keys}.toSet())
        assertEquals(.4,p.playTime,1e-9)
    }
    @Test fun `starting inside the last long note waits for the end without pressing it`() {
        val r=Recorder();val p=Player(listOf(note(0.0,"A",.2,true)),1.0,.75,PlayParams(),r)
        p.start();r.await();assertTrue(r.completed);assertTrue(r.batches.isEmpty())
    }
    @Test fun `latest seek before start ignores earlier tails and plays later heads`() {
        val r=Recorder()
        val p=Player(listOf(note(0.0,"A",.4,true),note(.1,"D",.2,true),note(.3,"G")),1.0,0.0,PlayParams(),r)
        p.seek(.1);p.seek(.3);p.start();r.await()
        assertTrue(r.completed);assertEquals(setOf("G"),r.batches.flatMap{it.keys}.toSet())
    }
    @Test fun `pause after middle start restores confirmed held notes but never old tails`() {
        lateinit var p:Player
        val r=object:Recorder(){override fun dispatch(batch:TapBatch):Boolean {
            val first=batches.isEmpty();super.dispatch(batch)
            if(first) p.pause()
            return true
        }}
        p=Player(listOf(note(0.0,"A",.5,true),note(.2,"D",.2,true),note(.42,"G")),1.0,.4,PlayParams(),r)
        try {
            p.start();assertTrue(r.released.await(1,TimeUnit.SECONDS))
            assertTrue(p.isPaused);p.resume();r.await()
            assertTrue(r.completed);assertFalse(r.batches.any{"A" in it.keys})
            assertTrue(r.batches.count{"D" in it.keys}>1)
            assertTrue(r.batches.any{"G" in it.keys})
        } finally {p.stop();p.join()}
    }
    @Test fun `forward seek during playback skips old tails and passed heads`() {
        lateinit var p:Player
        val r=object:Recorder(){override fun dispatch(batch:TapBatch):Boolean {
            super.dispatch(batch)
            if(batches.size==1) p.seek(.3)
            return true
        }}
        p=Player(listOf(note(0.0,"A",.5,true),note(.12,"D",.3,true),note(.4,"G")),1.0,0.0,PlayParams(),r)
        p.start();r.await()
        assertTrue(r.completed);assertEquals(setOf("G"),r.batches.drop(1).flatMap{it.keys}.toSet())
    }
    @Test fun `backward seek can replay later heads without restoring earlier tails`() {
        lateinit var p:Player
        var jumped=false;var boundary=0
        val r=object:Recorder(){override fun dispatch(batch:TapBatch):Boolean {
            super.dispatch(batch)
            if(!jumped && "D" in batch.keys){jumped=true;boundary=batches.size;p.seek(.1)}
            return true
        }}
        p=Player(listOf(note(0.0,"A",.4,true),note(.12,"D",.1,true)),1.0,0.0,PlayParams(),r)
        p.start();r.await()
        assertTrue(r.completed);assertTrue(jumped)
        assertEquals(setOf("D"),r.batches.drop(boundary).flatMap{it.keys}.toSet())
    }
    @Test fun `cancelled batch is not restored and its future onset keeps its offset`() {
        lateinit var p:Player
        var cancelled=false
        val r=object:Recorder(){override fun dispatch(batch:TapBatch):Boolean {
            if(!cancelled){cancelled=true;p.pause();return false}
            return super.dispatch(batch)
        }}
        p=Player(listOf(note(0.0,"A",.3,true),note(.06,"D",.2,true)),1.0,0.0,PlayParams(),r)
        try {
            p.start();assertTrue(r.released.await(1,TimeUnit.SECONDS))
            assertTrue(p.playTime<.06);assertTrue(r.batches.isEmpty())
            p.resume();r.await()
            assertTrue(r.completed);assertFalse(r.batches.any{"A" in it.keys})
            assertEquals(listOf("D"),r.batches.first().keys)
            assertTrue(r.batches.first().offsetsMs.single()>0L)
        } finally {p.stop();p.join()}
    }
    @Test fun `planner excludes old tails before applying the stroke budget`() {
        val old=NOTE_MAP.values.take(10).map{ScheduledNote(0.0,1.0,it,false)}
        val p=GesturePlanner(old+ScheduledNote(.5,.8,"Q",true),maxStrokes=10)
        val batch=p.frame(.5,onsetFloor=.5+EPSILON/2).batch
        assertEquals(listOf("Q"),batch.keys)
        assertEquals(listOf(0L),batch.offsetsMs)
    }
    @Test fun `seek queued while the final gesture completes is processed before finishing`() {
        lateinit var p:Player
        val r=object:Recorder(){override fun dispatch(batch:TapBatch):Boolean {
            super.dispatch(batch)
            if(batches.size==1){Thread.sleep(60);p.seek(0.0)}
            return true
        }}
        p=Player(listOf(note(0.0,"A")),1.0,0.0,PlayParams(),r)
        p.start();r.await()
        assertTrue(r.completed);assertEquals(2,r.batches.size)
    }
    @Test fun `seek and resume together clear the paused held notes`() {
        lateinit var p:Player
        val r=object:Recorder(){override fun dispatch(batch:TapBatch):Boolean {
            super.dispatch(batch)
            if(batches.size==1)p.pause()
            return true
        }}
        p=Player(listOf(note(0.0,"A",.4,true),note(.3,"G")),1.0,0.0,PlayParams(),r)
        try {
            p.start();assertTrue(r.released.await(1,TimeUnit.SECONDS))
            p.seek(.2);p.resume();r.await()
            assertTrue(r.completed);assertEquals(setOf("G"),r.batches.drop(1).flatMap{it.keys}.toSet())
        } finally {p.stop();p.join()}
    }
    @Test fun `cancel before start does not record play or dispatch`() {
        val r=Recorder();val p=Player(listOf(note(0.0,"A")),1.0,0.0,PlayParams(),r)
        p.stop();p.start();r.await();assertEquals(1L,r.started.count);assertTrue(r.batches.isEmpty());assertFalse(r.completed)
    }
    @Test fun `new input owner waits until stopped worker releases`() {
        val entered=CountDownLatch(1);val exit=CountDownLatch(1)
        val a=object:Recorder(){override fun dispatch(batch:TapBatch):Boolean {entered.countDown();exit.await(1,TimeUnit.SECONDS);return super.dispatch(batch)}}
        val b=Recorder();val p=Player(listOf(note(0.0,"A",1.0,true)),1.0,0.0,PlayParams(),a)
        val q=Player(listOf(note(0.0,"S")),1.0,0.0,PlayParams(),b)
        try {p.start();assertTrue(entered.await(1,TimeUnit.SECONDS));q.start();Thread.sleep(30);assertTrue(b.batches.isEmpty())
            p.stop();exit.countDown();a.await();b.await();assertTrue(b.completed)
        } finally {exit.countDown();p.stop();q.stop();p.join();q.join()}
    }
}
