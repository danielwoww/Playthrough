package com.playthrough.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Listens to the microphone and turns played piano notes into note on/off events.
 *
 * - [Mode.SINGLE]: one note at a time, using the YIN pitch detector (fast, robust).
 * - [Mode.CHORDS]: EXPERIMENTAL, several simultaneous notes, using [PolyPitchDetector]
 *   (FFT + harmonic summation). It is slower to react (~150-200 ms) and can mistake notes.
 *
 * Both modes use a loudness-based onset detector, so pressing the same key again is
 * recognised as a new note.
 */
class MicNoteDetector(
    private val context: Context,
    private val listener: Listener,
    private val mode: Mode = Mode.SINGLE,
) {

    enum class Mode { SINGLE, CHORDS }

    interface Listener {
        fun onMicNoteOn(midi: Int)
        fun onMicNoteOff(midi: Int)
        /** level: RMS 0..1, notes: currently detected notes. */
        fun onMicLevel(level: Float, notes: List<Int>)
    }

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val FRAME = 2048          // YIN analysis window
        private const val HOP = 1024            // YIN hop (~23 ms)
        private const val POLY_FRAME = 8192     // chord analysis window (~186 ms)
        private const val POLY_HOP = 2048       // chord hop (~46 ms)
        /** Enable with: adb shell setprop log.tag.PlaythroughMic DEBUG */
        private const val TAG = "PlaythroughMic"
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        val bufSize = max(minBuf, POLY_FRAME * 2)

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val sources = ArrayList<Int>()
        // UNPROCESSED avoids automatic gain control / noise suppression that would hide sustained notes.
        if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") {
            sources.add(MediaRecorder.AudioSource.UNPROCESSED)
        }
        sources.add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        sources.add(MediaRecorder.AudioSource.MIC)

        var rec: AudioRecord? = null
        for (src in sources) {
            try {
                val r = AudioRecord(src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize)
                if (r.state == AudioRecord.STATE_INITIALIZED) { rec = r; break }
                r.release()
            } catch (_: Exception) {
            }
        }
        if (rec == null) return false
        try {
            rec.startRecording()
        } catch (_: Exception) {
            rec.release(); return false
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release(); return false
        }
        record = rec
        running = true
        thread = Thread({
            if (mode == Mode.CHORDS) polyLoop(rec) else monoLoop(rec)
        }, "MicPitch").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    fun stop() {
        running = false
        thread?.join(800)
        thread = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
    }

    /** Reads up to [buf].size samples, slides them into [window], returns RMS of the new samples (or -1). */
    private fun readInto(rec: AudioRecord, buf: ShortArray, window: FloatArray): Float {
        val n = rec.read(buf, 0, buf.size)
        if (n <= 0) {
            try { Thread.sleep(5) } catch (_: InterruptedException) {}
            return -1f
        }
        val size = window.size
        System.arraycopy(window, n, window, 0, size - n)
        var sum = 0.0
        for (i in 0 until n) {
            val v = buf[i] / 32768f
            window[size - n + i] = v
            sum += v * v
        }
        return sqrt(sum / n).toFloat()
    }

    // ------------------------------------------------------- single-note mode

    private val mono = MonoPitchDetector(SAMPLE_RATE, FRAME)

    private fun monoLoop(rec: AudioRecord) {
        val shorts = ShortArray(HOP)
        val window = FloatArray(FRAME)
        val gate = OnsetGate(refractoryFrames = 5)      // ~115 ms between strikes
        var current = -1        // note currently reported as "down"
        var candidate = -1
        var candidateCount = 0
        var quietFrames = 0
        var levelTick = 0

        while (running) {
            val rms = readInto(rec, shorts, window)
            if (rms < 0) continue
            val attack = gate.update(rms)

            var detected = -1
            if (rms > gate.gate) {
                val f = mono.detect(window)
                if (f > 0) {
                    val exact = 69 + 12 * (ln(f / 440.0) / ln(2.0))
                    val nearest = exact.roundToInt()
                    if (abs(exact - nearest) < 0.45 && nearest in 21..108) detected = nearest
                }
            }

            if (detected >= 0) {
                quietFrames = 0
                if (detected == current) {
                    candidate = -1; candidateCount = 0
                    if (attack) {                       // same key struck again
                        emitOff(current); emitOn(current)
                    }
                } else {
                    if (detected == candidate) candidateCount++ else { candidate = detected; candidateCount = 1 }
                    if (candidateCount >= 2) {          // stable for ~46 ms
                        if (current >= 0) emitOff(current)
                        current = detected
                        emitOn(current)
                        candidate = -1; candidateCount = 0
                    }
                }
            } else {
                candidate = -1; candidateCount = 0
                if (current >= 0 && rms < gate.gate) {
                    quietFrames++
                    if (quietFrames >= 3) { emitOff(current); current = -1 }
                }
            }

            if (++levelTick % 2 == 0) {
                val lvl = rms
                val notes = if (current >= 0) listOf(current) else emptyList()
                main.post { listener.onMicLevel(lvl, notes) }
            }
        }
        if (current >= 0) emitOff(current)
        main.post { listener.onMicLevel(0f, emptyList()) }
    }

    // ------------------------------------------------- chord mode (experimental)

    private fun polyLoop(rec: AudioRecord) {
        val shorts = ShortArray(POLY_HOP)
        val window = FloatArray(POLY_FRAME)
        val detector = PolyPitchDetector(SAMPLE_RATE, POLY_FRAME)
        val gate = OnsetGate(refractoryFrames = 2)      // ~90 ms between strikes (46 ms hops)
        val history = IntArray(128)   // bit per recent frame: 1 = detected
        val missing = IntArray(128)   // consecutive frames an active note was absent
        val active = BooleanArray(128)
        var recentAttack = 0

        while (running) {
            val rms = readInto(rec, shorts, window)
            if (rms < 0) continue
            if (gate.update(rms)) recentAttack = 3   // the long window "sees" an attack for a few hops

            val detected = if (rms > gate.gate) detector.analyze(window) else emptyList()
            if (detected.isNotEmpty() && Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "rms=%.4f gate=%.4f hears %s".format(rms, gate.gate,
                    detected.joinToString(" ") { NoteNames.nameWithOctave(it) }))
            }

            for (m in 0 until 128) {
                history[m] = ((history[m] shl 1) or (if (m in detected) 1 else 0)) and 0xF
            }
            for (m in detected) {
                missing[m] = 0
                if (!active[m]) {
                    // heard in 3 of the last 4 frames (~140-190 ms): tolerates one dropped frame
                    if (Integer.bitCount(history[m]) >= 3) { active[m] = true; emitOn(m) }
                } else if (recentAttack == 3) {
                    emitOff(m); emitOn(m)                               // chord struck again
                }
            }
            for (m in 0 until 128) {
                if (m in detected) continue
                if (active[m]) {
                    missing[m]++
                    if (missing[m] >= 3) { active[m] = false; emitOff(m) }
                }
            }
            if (recentAttack > 0) recentAttack--

            val lvl = rms
            val notes = (0 until 128).filter { active[it] }
            main.post { listener.onMicLevel(lvl, notes) }
        }
        for (m in 0 until 128) if (active[m]) emitOff(m)
        main.post { listener.onMicLevel(0f, emptyList()) }
    }

    private fun emitOn(midi: Int) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "note on ${NoteNames.nameWithOctave(midi)}")
        main.post { listener.onMicNoteOn(midi) }
    }
    private fun emitOff(midi: Int) { main.post { listener.onMicNoteOff(midi) } }
}
