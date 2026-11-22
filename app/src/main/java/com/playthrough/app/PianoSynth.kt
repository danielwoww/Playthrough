package com.playthrough.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max

/**
 * Plays notes on the phone: feeds [SynthCore] from a low-latency AudioTrack thread.
 * Note calls are thread-safe; they're queued and applied on the audio thread.
 */
class PianoSynth {

    private class Event(val on: Boolean, val midi: Int, val velocity: Float, val holdSeconds: Double, val inst: Instrument?)

    companion object {
        private const val BLOCK = 256
    }

    private val events = ConcurrentLinkedQueue<Event>()
    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null
    private var core: SynthCore? = null

    @Volatile var volume = 0.7f
    /** Sound used for notes started from now on. */
    @Volatile var instrument: Instrument = Instrument.ALL[0]

    val isRunning: Boolean get() = running

    fun start(): Boolean {
        if (running) return true
        val rate = AudioTrack.getNativeOutputSampleRate(AudioTrack.MODE_STREAM).takeIf { it in 8000..96000 } ?: 44100
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (minBuf <= 0) return false
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(max(minBuf, BLOCK * 4 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) { return false }
        if (t.state != AudioTrack.STATE_INITIALIZED) { t.release(); return false }
        track = t
        core = SynthCore(rate)
        running = true
        t.play()
        thread = Thread({ render(t) }, "PianoSynth").apply { start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
        core = null
        events.clear()
    }

    /** Plays a note that releases by itself after [holdSeconds]. */
    fun play(midi: Int, holdSeconds: Double, velocity: Float = 0.8f) {
        if (running && midi in 21..108) events.add(Event(true, midi, velocity, holdSeconds, instrument))
    }

    fun noteOn(midi: Int, velocity: Float = 0.8f) = play(midi, Double.MAX_VALUE, velocity)

    fun noteOff(midi: Int) {
        if (running) events.add(Event(false, midi, 0f, 0.0, null))
    }

    fun allNotesOff() {
        for (m in 21..108) noteOff(m)
    }

    /** A short C-major arpeggio + chord, to hear what an instrument sounds like. */
    fun demo() {
        if (!running) return
        Thread {
            for (m in intArrayOf(60, 64, 67)) { play(m, 0.25); Thread.sleep(160) }
            for (m in intArrayOf(48, 60, 64, 67, 72)) play(m, 0.9, 0.6f)
        }.start()
    }

    private fun render(t: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val c = core ?: return
        val buf = FloatArray(BLOCK)
        while (running) {
            while (true) {
                val e = events.poll() ?: break
                if (e.on) c.noteOn(e.midi, e.velocity, e.holdSeconds, e.inst ?: instrument)
                else c.noteOff(e.midi)
            }
            c.volume = volume
            c.render(buf, BLOCK)
            t.write(buf, 0, BLOCK, AudioTrack.WRITE_BLOCKING)
        }
    }
}
