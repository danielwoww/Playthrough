package com.playthrough.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class LogicTest {

    @Test
    fun noteNames() {
        assertEquals(60, NoteNames.parse("C4"))
        assertEquals(75, NoteNames.parse("D#5"))
        assertEquals(58, NoteNames.parse("Bb3"))
        assertEquals("D#5", NoteNames.nameWithOctave(75))
        assertTrue(NoteNames.isBlack(61))
        assertFalse(NoteNames.isBlack(64))
    }

    @Test
    fun builtInSongsParse() {
        val songs = SongLibrary.builtIn()
        assertEquals(5, songs.size)
        val elise = songs.first { it.title.startsWith("Für Elise") }
        // starts with E5 D#5 E5 D#5
        assertEquals(listOf(76, 75, 76, 75), elise.notes.take(4).map { it.midi })
        assertTrue(elise.hasLeftHand)
    }

    /** Two tracks at 120 bpm, 480 ticks/quarter: RH C5 D5, LH C3. */
    private fun tinyMidi(): ByteArray {
        val out = ByteArrayOutputStream()
        fun w(vararg b: Int) = b.forEach { out.write(it) }
        fun track(events: IntArray) {
            w(0x4D, 0x54, 0x72, 0x6B)
            val n = events.size
            w(n ushr 24 and 0xFF, n ushr 16 and 0xFF, n ushr 8 and 0xFF, n and 0xFF)
            w(*events)
        }
        w(0x4D, 0x54, 0x68, 0x64, 0, 0, 0, 6, 0, 1, 0, 2, 0x01, 0xE0)
        track(intArrayOf(
            0x00, 0xFF, 0x51, 0x03, 0x07, 0xA1, 0x20,   // tempo 500000 us
            0x00, 0x90, 72, 100,                          // C5 on
            0x83, 0x60, 0x80, 72, 0,                      // 480 ticks later off
            0x00, 0x90, 74, 100,                          // D5 on (note: running status not used)
            0x83, 0x60, 74, 0,                            // running status note-on vel 0 = off
            0x00, 0xFF, 0x2F, 0x00,
        ))
        track(intArrayOf(
            0x00, 0x91, 48, 90,
            0x87, 0x40, 0x81, 48, 0,                      // 960 ticks
            0x00, 0xFF, 0x2F, 0x00,
        ))
        return out.toByteArray()
    }

    @Test
    fun midiWriterRoundTripKeepsNotesHandsAndBeat() {
        val original = Song("x", listOf(
            Note(76, 0.0, 0.24, Hand.RIGHT),
            Note(75, 0.24, 0.24, Hand.RIGHT),
            Note(45, 0.48, 0.72, Hand.LEFT),
            Note(64, 0.48, 0.24, Hand.RIGHT),
            Note(40, 1.2, 0.5, Hand.LEFT),        // low note in LH, high-ish note in RH: names decide
            Note(81, 1.2, 0.5, Hand.LEFT),
        ), beatSeconds = 0.24)
        val back = MidiFileParser.parse(MidiFileWriter.write(original), "x")
        assertEquals(0.24, back.beatSeconds, 1e-6)
        assertEquals(original.notes.size, back.notes.size)
        for ((a, b) in original.notes.zip(back.notes)) {
            assertEquals(a.midi, b.midi)
            assertEquals(a.hand, b.hand)
            assertEquals(a.start, b.start, 0.002)
            assertEquals(a.duration, b.duration, 0.002)
        }
    }

    @Test
    fun songStoreNamesKeepAccents() {
        assertEquals("Für Elise (practice)", SongStore.sanitize("Für Elise (practice)"))
        assertEquals("a_b_c", SongStore.sanitize("a/b:c.mid"))
        assertEquals("Untitled", SongStore.sanitize("  "))
    }

    @Test
    fun midiWriterKeepsLeadingSilence() {
        val s = Song("x", listOf(Note(60, 2.0, 0.5, Hand.RIGHT)), beatSeconds = 0.5)
        assertEquals(2.0, MidiFileParser.parse(MidiFileWriter.write(s), "x").notes[0].start, 0.002)
    }

    @Test
    fun midiFileParser() {
        val song = MidiFileParser.parse(tinyMidi(), "tiny")
        assertEquals(3, song.notes.size)
        val c5 = song.notes.first { it.midi == 72 }
        val d5 = song.notes.first { it.midi == 74 }
        val c3 = song.notes.first { it.midi == 48 }
        assertEquals(0.0, c5.start, 1e-6)
        assertEquals(0.5, c5.duration, 1e-6)
        assertEquals(0.5, d5.start, 1e-6)
        assertEquals(1.0, c3.duration, 1e-6)
        assertEquals(Hand.RIGHT, c5.hand)
        assertEquals(Hand.LEFT, c3.hand)
    }

    @Test
    fun waitModeStopsUntilCorrectKey() {
        val song = Song("t", listOf(
            Note(60, 0.0, 0.5, Hand.RIGHT),
            Note(62, 0.5, 0.5, Hand.RIGHT),
        ))
        val e = PracticeEngine()
        e.load(song)
        e.playing = true
        repeat(400) { e.update(1.0 / 60) }   // way past the lead-in
        assertTrue(e.waiting)
        assertEquals(0.0, e.time, 1e-9)

        e.noteOn(61, InputSource.MIDI)          // wrong key
        assertEquals(1, e.wrongCount)
        e.update(0.1)
        assertEquals(0.0, e.time, 1e-9)

        e.noteOn(60, InputSource.MIDI)          // right key -> clock resumes
        e.computeKeyStates()
        assertEquals(PracticeEngine.KEY_CORRECT, e.keyPressed[60])
        e.update(0.1)
        assertTrue(e.time > 0.0)
        repeat(100) { e.update(1.0 / 60) }
        assertEquals(0.5, e.time, 1e-9)         // waiting on D4 now
        e.noteOn(62, InputSource.MIC)
        repeat(200) { e.update(1.0 / 60) }
        assertTrue(e.finished)
        assertEquals(2, e.correctCount)
    }

    @Test
    fun chordMicNeedsAllButOneNoteOfBigChords() {
        val chord = listOf(60, 64, 67).map { Note(it, 0.0, 1.0, Hand.RIGHT) }
        val e = PracticeEngine()
        e.load(Song("t", chord + Note(72, 1.0, 1.0, Hand.RIGHT)))
        e.playing = true
        repeat(300) { e.update(1.0 / 60) }
        assertTrue(e.waiting)
        e.noteOn(60, InputSource.MIC_CHORD)
        e.update(0.1)
        assertEquals(0.0, e.time, 1e-9)          // one of three is not enough
        e.noteOn(61, InputSource.MIC_CHORD)      // ghost note: shown red, not counted
        assertEquals(0, e.wrongCount)
        e.noteOn(67, InputSource.MIC_CHORD)      // two of three -> chord accepted
        e.update(0.1)
        assertTrue(e.time > 0.0)
    }

    @Test
    fun abLoopRepeatsSectionAndResetsIt() {
        val song = Song("t", (0 until 8).map { Note(60 + it, it * 1.0, 0.5, Hand.RIGHT) })
        val e = PracticeEngine()
        e.load(song)
        e.waitMode = false
        e.playing = true
        e.seek(2.0)
        e.setLoopStart(2.0)
        e.setLoopEnd(4.0)                     // loop notes at 2s and 3s
        assertEquals(2.0 - PracticeEngine.LOOP_LEAD, e.time, 1e-9)
        assertEquals(PracticeEngine.SKIPPED, e.states[1])
        assertEquals(PracticeEngine.PENDING, e.states[2])
        // play through the section once, hitting both notes
        while (e.time < 2.0) e.update(1.0 / 60)
        e.noteOn(62, InputSource.MIDI)
        while (e.time < 3.0) e.update(1.0 / 60)
        e.noteOn(63, InputSource.MIDI)
        assertEquals(PracticeEngine.HIT, e.states[3])
        // reaching B jumps back and makes the section playable again
        repeat(120) { e.update(1.0 / 60) }
        assertEquals(2, e.loopPass)
        assertTrue(e.time < 3.0)
        assertEquals(PracticeEngine.PENDING, e.states[3])
        e.clearLoop()
        assertFalse(e.looping)
    }

    @Test
    fun scrubbingBackMakesNotesPlayableAgain() {
        val song = Song("t", listOf(Note(60, 0.0, 0.5, Hand.RIGHT), Note(62, 1.0, 0.5, Hand.RIGHT)))
        val e = PracticeEngine()
        e.load(song)
        e.playing = true
        repeat(400) { e.update(1.0 / 60) }
        e.noteOn(60, InputSource.MIDI)
        assertEquals(PracticeEngine.HIT, e.states[0])
        e.seek(-0.5)                          // scroll back before the first note
        assertEquals(PracticeEngine.PENDING, e.states[0])
    }

    @Test
    fun songNotesFireOnceInOrderAndAfterSeek() {
        val song = Song("t", listOf(
            Note(60, 0.0, 0.5, Hand.RIGHT), Note(48, 0.0, 1.0, Hand.LEFT), Note(62, 1.0, 0.5, Hand.RIGHT)))
        val e = PracticeEngine()
        val fired = ArrayList<Pair<Int, Boolean>>()
        e.onSongNote = { n, practiced -> fired.add(n.midi to practiced) }
        e.load(song)
        e.practiceLeft = false
        e.waitMode = false
        e.playing = true
        repeat(300) { e.update(1.0 / 60) }
        assertEquals(listOf(48 to false, 60 to true, 62 to true), fired)
        fired.clear()
        e.seek(0.5)
        e.playing = true
        repeat(60) { e.update(1.0 / 60) }
        assertEquals(listOf(62 to true), fired)     // only notes after the seek point
    }

    @Test
    fun waitModeFiresWaitingNoteAsCue() {
        val e = PracticeEngine()
        val fired = ArrayList<Int>()
        e.onSongNote = { n, _ -> fired.add(n.midi) }
        e.load(Song("t", listOf(Note(60, 0.0, 0.5, Hand.RIGHT), Note(62, 1.0, 0.5, Hand.RIGHT))))
        e.playing = true
        repeat(300) { e.update(1.0 / 60) }
        assertTrue(e.waiting)
        assertEquals(listOf(60), fired)            // heard while waiting, before it's played
    }

    /** Repeated note, struck again too early: must not look green while the song still waits. */
    @Test
    fun earlyRestrikeOfRepeatedNoteIsNotGreen() {
        val e = PracticeEngine()
        e.load(Song("t", listOf(Note(76, 0.0, 0.45, Hand.RIGHT), Note(76, 0.6, 0.45, Hand.RIGHT))))
        e.playing = true
        repeat(300) { e.update(1.0 / 60) }
        e.noteOn(76, InputSource.MIDI); e.noteOff(76, InputSource.MIDI)    // first E5: counts
        repeat(6) { e.update(1.0 / 60) }                                  // ~0.1 s later
        e.noteOn(76, InputSource.MIDI)                                     // 0.5 s early
        e.computeKeyStates()
        assertTrue("shown green although it didn't count", e.keyPressed[76] != PracticeEngine.KEY_CORRECT)
        assertEquals(PracticeEngine.PENDING, e.states[1])
    }

    /** Key still held from the previous same-pitch note: shown as "play again", not green. */
    @Test
    fun heldKeyFromPreviousNoteAsksForRestrike() {
        val e = PracticeEngine()
        e.load(Song("t", listOf(Note(60, 0.0, 0.4, Hand.RIGHT), Note(60, 0.5, 0.4, Hand.RIGHT))))
        e.playing = true
        repeat(300) { e.update(1.0 / 60) }
        e.noteOn(60, InputSource.MIDI)                                     // counts, key stays down
        repeat(60) { e.update(1.0 / 60) }
        assertTrue(e.waiting)
        e.computeKeyStates()
        assertTrue(e.restrikeNeeded)
        assertEquals(PracticeEngine.KEY_NEUTRAL, e.keyPressed[60])
        e.noteOff(60, InputSource.MIDI)
        e.noteOn(60, InputSource.MIDI)                                     // fresh strike counts
        assertEquals(PracticeEngine.HIT, e.states[1])
    }

    @Test
    fun playAlongCountsMisses() {
        val song = Song("t", listOf(Note(60, 0.0, 0.5, Hand.RIGHT), Note(62, 1.0, 0.5, Hand.RIGHT)))
        val e = PracticeEngine()
        e.load(song)
        e.waitMode = false
        e.playing = true
        while (e.time < 0.0) e.update(1.0 / 60)
        e.noteOn(60, InputSource.MIDI)
        repeat(200) { e.update(1.0 / 60) }
        assertEquals(1, e.correctCount)
        assertEquals(1, e.missedCount)
    }
}
