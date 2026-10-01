package com.example.lyretest.midi

import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Handler
import android.util.Log
import com.example.lyretest.arrange.NOTE_MAP
import com.example.lyretest.arrange.mapNote
import java.io.Closeable
import com.example.lyretest.player.Player
import com.example.lyretest.player.TapBatch
import kotlin.concurrent.withLock
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 原神官方服、国际服及常见渠道服的包名判断。 */
fun isGenshinPackageName(packageName: CharSequence?): Boolean {
    val name = packageName?.toString()?.lowercase().orEmpty()
    if (name.isEmpty()) return false
    val publisher = name.contains("mihoyo") || name.contains("hoyoverse")
    val game = name.contains("yuanshen") || name.contains("genshinimpact") ||
            name.contains(".ys.") || name.endsWith(".ys")
    return publisher && game
}

/** Android MIDI 设备在界面中显示的名称。 */
fun MidiDeviceInfo.displayName(): String {
    val props = properties
    val parts = listOf(
        props.getString(MidiDeviceInfo.PROPERTY_NAME),
        props.getString(MidiDeviceInfo.PROPERTY_PRODUCT),
        props.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)
    ).filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    return parts.joinToString(" · ").ifEmpty { "MIDI 设备 #$id" }
}

/** 把电子琴音高映射到原神 21 键；黑键无法直接演奏，会返回 null。 */
fun mapLiveNote(note: Int, transpose: Int, fold: Boolean): String? =
    mapNote(note + transpose, fold)?.let { NOTE_MAP[it] }

/**
 * 可跨多个数据包工作的 MIDI 字节流解码器。
 * 接收起音、松键及控制消息，保留跨包 running status。
 */
class LiveMidiDecoder(private val onMessage: (LiveMidiMessage) -> Unit = {},
    private val onNoteOn: (note: Int, channel: Int) -> Unit = { _, _ -> }) {
    private var runningStatus = 0
    private var status = 0
    private var needed = 0
    private var dataCount = 0
    private val data = IntArray(2)
    private var inSysEx = false

    fun feed(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset) {
        val end = (offset + count).coerceAtMost(bytes.size)
        for (i in offset.coerceAtLeast(0) until end) feedByte(bytes[i].toInt() and 0xFF)
    }

    private fun feedByte(value: Int) {
        // MIDI 实时消息可插在任何消息中，不影响 running status。
        if (value >= 0xF8) {
            if (value == 0xFF || value == 0xFC) onMessage(LiveMidiMessage(value))
            return
        }
        if (inSysEx) {
            if (value == 0xF7) inSysEx = false
            return
        }
        if (value and 0x80 != 0) {
            when {
                value == 0xF0 -> {
                    inSysEx = true
                    status = 0
                    runningStatus = 0
                }
                value >= 0xF0 -> {
                    runningStatus = 0
                    startMessage(value, systemDataLength(value))
                }
                else -> {
                    runningStatus = value
                    startMessage(value, channelDataLength(value))
                }
            }
            return
        }

        if (needed == 0) {
            if (runningStatus == 0) return
            startMessage(runningStatus, channelDataLength(runningStatus))
        }
        if (dataCount < data.size) data[dataCount] = value
        dataCount++
        if (dataCount >= needed) {
            if (status < 0xF0) onMessage(LiveMidiMessage(status, data[0], if (needed > 1) data[1] else 0))
            if (status and 0xF0 == 0x90 && data[1] > 0) {
                onNoteOn(data[0], status and 0x0F)
            }
            needed = 0
            dataCount = 0
        }
    }

    private fun startMessage(newStatus: Int, length: Int) {
        status = newStatus
        needed = length
        dataCount = 0
    }

    private fun channelDataLength(s: Int): Int =
        if (s and 0xF0 == 0xC0 || s and 0xF0 == 0xD0) 1 else 2

    private fun systemDataLength(s: Int): Int = when (s) {
        0xF1, 0xF3 -> 1
        0xF2 -> 2
        else -> 0
    }
}

/**
 * 一次实时电子琴会话。打开 Android MIDI 设备的输出端口，将同一个真人和弦中
 * 略有先后的 note_on 合并，再交给一次多点手势派发。批内所有触点都从 0ms 开始，
 * 不使用 MIDI 文件播放器的琶音偏移。
 */
class LiveMidiSession(
    private val midiManager: MidiManager,
    private val deviceInfo: MidiDeviceInfo,
    private val handler: Handler,
    private val transpose: Int,
    private val fold: Boolean,
    private val chordWindowMs: Long = 30L,
    private val followRelease: Boolean = true,
    private val sustain: Boolean = true,
    private val maxStrokes: Int = com.example.lyretest.player.REQUESTED_TOUCH_LIMIT,
    private val onRelease: () -> Unit = {},
    private val onBatch: (TapBatch) -> Unit,
    private val onConnected: () -> Unit,
    private val onError: (String) -> Unit
) : Closeable {
    private val running = AtomicBoolean(true)
    private val enabled = AtomicBoolean(false)
    private data class Received(val message: LiveMidiMessage, val time: Double)
    private val queue = LinkedBlockingQueue<Received>(4096)
    private val state = LiveTouchState(transpose, fold, followRelease, sustain, chordWindowMs, maxStrokes)
    private var device: MidiDevice? = null
    private var outputPort: android.media.midi.MidiOutputPort? = null
    private val decoder = LiveMidiDecoder(onMessage = { message ->
        if (enabled.get() && running.get() && !queue.offer(Received(message, System.nanoTime() / 1e9)))
            fail("电子琴输入过密，已停止并释放触点")
    })
    private val receiver = object : MidiReceiver() {
        override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
            decoder.feed(data, offset, count)
        }
    }
    private val worker = Thread({ Player.inputLock.withLock { consume() } }, "LiveMidiTouch").apply {
        priority = Thread.MAX_PRIORITY
        start()
    }

    init {
        midiManager.openDevice(deviceInfo, { opened ->
            if (!running.get()) {
                opened?.close()
                return@openDevice
            }
            if (opened == null) {
                fail("无法打开 ${deviceInfo.displayName()}")
                return@openDevice
            }
            val port = opened.openOutputPort(0)
            if (port == null) {
                opened.close()
                fail("设备没有可读取的 MIDI 输出端口")
                return@openDevice
            }
            device = opened
            outputPort = port
            port.connect(receiver)
            onConnected()
        }, handler)
    }

    /** 倒计时结束、游戏与琴界面就绪后才接受音符。 */
    fun enable() {
        queue.clear()
        enabled.set(true)
    }

    private fun consume() {
        fun now() = System.nanoTime() / 1e9
        try {
            while (running.get()) {
                val first = queue.poll(if (state.hasContacts) 0 else 2, TimeUnit.MILLISECONDS)
                if (!running.get()) break
                if (first != null) {
                    state.feed(first.message, first.time)
                    // 到达时间决定按下分组；松键和踏板不排在合并窗口后面。
                    while (true) { val event = queue.poll() ?: break; state.feed(event.message, event.time) }
                }
                if (enabled.get() && running.get()) state.frame(now())?.let(onBatch)
            }
        } catch (_: InterruptedException) {
            // 清除中断标记，使 finally 可以等待终结触点。
            Thread.interrupted()
        } catch (error: Exception) {
            Log.e(TAG, "实时 MIDI 派发失败", error)
            fail("电子琴演奏停止：${error.message}")
        } finally {
            Thread.interrupted()
            onRelease()
            state.clear()
        }
    }

    private fun fail(message: String) {
        if (running.getAndSet(false)) {
            worker.interrupt()
            onError(message)
        }
    }

    override fun close() {
        running.set(false)
        enabled.set(false)
        worker.interrupt()
        runCatching { outputPort?.disconnect(receiver) }
        runCatching { outputPort?.close() }
        runCatching { device?.close() }
        outputPort = null
        device = null
        queue.clear()
    }

    private companion object {
        const val TAG = "LiveMidiSession"
    }
}
