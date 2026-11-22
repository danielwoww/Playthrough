package com.playthrough.app

import java.util.Arrays

/** MIC = single-note microphone detection, MIC_CHORD = experimental polyphonic detection. */
enum class InputSource { TOUCH, MIDI, MIC, MIC_CHORD }

/**
 * Song clock + scoring. In wait mode the clock stops at each note (or chord) of the
 * practiced hand(s) until the player presses the right key(s).
 */
class PracticeEngine {

    companion object {
        const val PENDING = 0
        const val HIT = 1
        const val MISSED = 2
        const val SKIPPED = 3

        const val KEY_NONE = 0
        const val KEY_NEUTRAL = 1
        const val KEY_CORRECT = 2
        const val KEY_WRONG = 3

        const val TARGET_NONE = 0
        const val TARGET_RIGHT = 1
        const val TARGET_LEFT = 2

        /** Seconds of falling tiles before the first note. */
        const val LEAD_IN = 2.5
        /** Notes starting within this window form one chord. */
        private const val CHORD_EPS = 0.04
        /** How early (song seconds) a key may be pressed for the upcoming note in wait mode. */
        private const val EARLY_WINDOW = 0.30
        /** +/- tolerance (real seconds) for play-along mode. */
        private const val HIT_WINDOW = 0.22
        /** Song seconds shown before the loop start when a loop (re)starts. */
        const val LOOP_LEAD = 1.5
    }

    var song: Song? = null
        private set
    var states = IntArray(0)
        private set

    var time = -LEAD_IN
        private set
    var playing = false
    var finished = false
        private set
    var waitMode = true
    var speed = 1.0
    var practiceRight = true
        set(v) { field = v; cursor = 0 }
    var practiceLeft = true
        set(v) { field = v; cursor = 0 }
    var waiting = false
        private set

    var correctCount = 0
        private set
    var wrongCount = 0
        private set
    var missedCount = 0
        private set
    var lastPressed = -1
        private set

    /** Per-key render state, refreshed by [computeKeyStates]. */
    val keyPressed = IntArray(128)
    val keyTarget = IntArray(128)
    val keyWaiting = BooleanArray(128)

    /**
     * Called for every song note when the song clock reaches its start, so the app can play it.
     * `practiced` is true when the note belongs to a hand being practised.
     */
    var onSongNote: ((note: Note, practiced: Boolean) -> Unit)? = null
    private var fireCursor = 0

    private val held = Array(InputSource.values().size) { BooleanArray(128) }
    private val keyResult = IntArray(128)
    private var cursor = 0
    val group = ArrayList<Int>()

    fun load(s: Song) {
        song = s
        states = IntArray(s.notes.size)
        clearLoop()
        restart()
    }

    fun restart() {
        Arrays.fill(states, PENDING)
        time = -LEAD_IN
        cursor = 0
        fireCursor = 0
        correctCount = 0
        wrongCount = 0
        missedCount = 0
        finished = false
        waiting = false
    }

    /**
     * Jumps to song time [t]. Notes starting before [playFrom] are skipped, later ones become
     * playable again (so a section can be retried).
     */
    fun seek(t: Double, playFrom: Double = t) {
        val s = song ?: return
        time = t.coerceIn(-LEAD_IN, s.length)
        for (i in s.notes.indices) {
            states[i] = if (s.notes[i].start < playFrom - 0.01) SKIPPED else PENDING
        }
        cursor = 0
        fireCursor = lowerBound(s, time)
        finished = false
        waiting = false
    }

    // ------------------------------------------------------------- A-B loop

    /** Loop section in song seconds; looping is active when both are set. */
    var loopStart: Double? = null
        private set
    var loopEnd: Double? = null
        private set
    var loopPass = 0
        private set
    val looping: Boolean get() = loopStart != null && loopEnd != null

    fun setLoopStart(t: Double) {
        loopStart = t.coerceAtLeast(0.0)
        loopEnd = null
        loopPass = 0
    }

    fun setLoopEnd(t: Double) {
        val a = loopStart ?: return
        var b = t
        var start = a
        if (b < start) { val tmp = start; start = b; b = tmp }
        if (b - start < 0.3) b = start + 0.3
        loopStart = start
        loopEnd = b
        loopPass = 1
        restartLoop()
    }

    fun clearLoop() {
        loopStart = null
        loopEnd = null
        loopPass = 0
    }

    /** Go back to just before A so the tiles fall in, and make the section playable again. */
    fun restartLoop() {
        val a = loopStart ?: return
        seek(a - LOOP_LEAD, playFrom = a)
    }

    val progress: Double
        get() {
            val s = song ?: return 0.0
            if (s.length <= 0) return 0.0
            return (time / s.length).coerceIn(0.0, 1.0)
        }

    val accuracy: Double?
        get() {
            val total = correctCount + wrongCount + missedCount
            return if (total == 0) null else correctCount.toDouble() / total
        }

    fun isPracticed(n: Note) =
        (n.hand == Hand.RIGHT && practiceRight) || (n.hand == Hand.LEFT && practiceLeft)

    private fun advanceCursor(s: Song) {
        val notes = s.notes
        while (cursor < notes.size && (states[cursor] != PENDING || !isPracticed(notes[cursor]))) cursor++
    }

    /** Fills [group] with the indices of the next unresolved practiced note/chord. */
    private fun computeGroup() {
        group.clear()
        val s = song ?: return
        advanceCursor(s)
        val notes = s.notes
        if (cursor >= notes.size) return
        val first = notes[cursor].start
        var i = cursor
        while (i < notes.size && notes[i].start - first < CHORD_EPS) {
            if (states[i] == PENDING && isPracticed(notes[i])) group.add(i)
            i++
        }
    }

    fun update(dt: Double) {
        val s = song ?: return
        if (!playing) return
        var nt = time + dt * speed
        if (waitMode) {
            computeGroup()
            if (group.isNotEmpty()) {
                val gs = s.notes[group[0]].start
                if (nt >= gs) {
                    nt = gs
                    waiting = true
                } else {
                    waiting = false
                }
            } else {
                waiting = false
            }
        } else {
            waiting = false
            advanceCursor(s)
            var j = cursor
            val limit = nt - HIT_WINDOW * speed
            while (j < s.notes.size && s.notes[j].start < limit) {
                if (states[j] == PENDING && isPracticed(s.notes[j])) {
                    states[j] = MISSED
                    missedCount++
                }
                j++
            }
        }
        time = nt
        // notes the clock has reached (in wait mode this includes the note being waited for,
        // so its sound works as a cue)
        val listener = onSongNote
        val stopAt = loopEnd
        while (fireCursor < s.notes.size && s.notes[fireCursor].start <= nt + 1e-6) {
            val n = s.notes[fireCursor]
            if (stopAt == null || n.start < stopAt) listener?.invoke(n, isPracticed(n))
            fireCursor++
        }
        val loopB = loopEnd
        if (loopB != null && loopStart != null && time >= loopB) {
            loopPass++
            restartLoop()
            return
        }
        if (time >= s.length + 0.8) {
            finished = true
            playing = false
            waiting = false
        }
    }

    fun noteOn(midi: Int, source: InputSource) {
        if (midi !in 0..127) return
        held[source.ordinal][midi] = true
        lastPressed = midi
        keyResult[midi] = judge(midi, source)
    }

    fun noteOff(midi: Int, source: InputSource) {
        if (midi !in 0..127) return
        held[source.ordinal][midi] = false
    }

    fun releaseAll(source: InputSource) {
        Arrays.fill(held[source.ordinal], false)
    }

    private fun judge(midi: Int, source: InputSource): Int {
        val s = song ?: return KEY_NEUTRAL
        if (!playing) return KEY_NEUTRAL
        val notes = s.notes
        if (waitMode) {
            computeGroup()
            val idx = group.firstOrNull { notes[it].midi == midi }
            if (idx != null && notes[idx].start - time <= EARLY_WINDOW) {
                states[idx] = HIT
                correctCount++
                when (source) {
                    // Single-note mic hears one pitch, so one note of a chord counts as the chord.
                    InputSource.MIC -> for (g in group) if (states[g] == PENDING) states[g] = HIT
                    // Chord mic: big chords may lose one quiet note in detection; allow that.
                    InputSource.MIC_CHORD -> {
                        // whole chord = every practiced note starting with this one (incl. already hit)
                        val start = notes[idx].start
                        var lo = lowerBound(s, start - CHORD_EPS)
                        var size = 0
                        var hits = 0
                        while (lo < notes.size && notes[lo].start < start + CHORD_EPS) {
                            if (isPracticed(notes[lo])) {
                                size++
                                if (states[lo] == HIT) hits++
                            }
                            lo++
                        }
                        if (size >= 3 && hits >= size - 1) {
                            for (g in group) if (states[g] == PENDING) states[g] = HIT
                        }
                    }
                    else -> {}
                }
                return KEY_CORRECT
            }
            // Re-striking a note that is still sounding (or mic re-triggers) is not a mistake.
            if (isSoundingHit(s, midi)) return KEY_NEUTRAL   // harmless re-strike: blue, not green (it did not count)
            // Experimental chord detection can report ghost notes: show them, don't penalise.
            if (source != InputSource.MIC_CHORD) wrongCount++
            return KEY_WRONG
        } else {
            val window = HIT_WINDOW * speed
            var i = lowerBound(s, time - window)
            while (i < notes.size && notes[i].start <= time + window) {
                val n = notes[i]
                if (n.midi == midi && states[i] == PENDING && isPracticed(n)) {
                    states[i] = HIT
                    correctCount++
                    return KEY_CORRECT
                }
                i++
            }
            if (isSoundingHit(s, midi)) return KEY_NEUTRAL   // harmless re-strike: blue, not green (it did not count)
            if (source != InputSource.MIC_CHORD) wrongCount++
            return KEY_WRONG
        }
    }

    private fun isSoundingHit(s: Song, midi: Int): Boolean {
        var i = lowerBound(s, time - s.maxDuration - 0.05)
        while (i < s.notes.size && s.notes[i].start <= time + 0.05) {
            val n = s.notes[i]
            if (n.midi == midi && states[i] == HIT && time <= n.start + n.duration + 0.15) return true
            i++
        }
        return false
    }

    /** First index whose start >= t. */
    fun lowerBound(s: Song, t: Double): Int {
        var lo = 0
        var hi = s.notes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (s.notes[mid].start < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun computeKeyStates() {
        Arrays.fill(keyTarget, TARGET_NONE)
        Arrays.fill(keyWaiting, false)
        for (m in 0 until 128) {
            var down = false
            for (h in held) if (h[m]) { down = true; break }
            keyPressed[m] = if (down) keyResult[m].coerceAtLeast(KEY_NEUTRAL) else KEY_NONE
        }
        val s = song ?: return
        var i = lowerBound(s, time - s.maxDuration - 0.01)
        while (i < s.notes.size) {
            val n = s.notes[i]
            if (n.start > time + 0.001) break
            if (time < n.start + n.duration) {
                keyTarget[n.midi] = if (n.hand == Hand.RIGHT) TARGET_RIGHT else TARGET_LEFT
            }
            i++
        }
        restrikeNeeded = false
        if (waitMode) {
            computeGroup()
            for (g in group) {
                val n = s.notes[g]
                // A key that is down but whose note is still pending was pressed for an earlier
                // note (or too early): it hasn't counted, so never show it green.
                if (keyPressed[n.midi] != KEY_NONE) {
                    keyPressed[n.midi] = KEY_NEUTRAL
                    if (waiting) restrikeNeeded = true
                }
                if (waiting) {
                    keyTarget[n.midi] = if (n.hand == Hand.RIGHT) TARGET_RIGHT else TARGET_LEFT
                    keyWaiting[n.midi] = true
                }
            }
        }
    }

    /**
     * True while waiting for a note whose key is still held from before: the player has to lift
     * and strike it again (a held key or a still-ringing string can't count twice).
     */
    var restrikeNeeded = false
        private set

    fun waitingNoteNames(): String {
        val s = song ?: return ""
        return group.joinToString(" + ") { NoteNames.nameWithOctave(s.notes[it].midi) }
    }
}
