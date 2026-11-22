package com.playthrough.app

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * High piano notes are nearly pure tones: weak upper partials, fast decay, and some of them lie
 * above what a phone mic records well. These tests use that kind of spectrum.
 */
class HighNotesTest {
    private val sr = 44100

    /** Piano-like tone whose partials fall off steeply (amp = h^-rolloff), cut above [maxPartialHz]. */
    private fun tone(notes: List<Int>, n: Int, rolloff: Double = 2.5, noise: Float = 0.003f,
                     maxPartialHz: Double = 9000.0, seed: Int = 7): FloatArray {
        val rnd = Random(seed)
        val out = FloatArray(n)
        for (m in notes) {
            val f0 = 440.0 * 2.0.pow((m - 69) / 12.0)
            for (h in 1..12) {
                val fh = h * f0 * sqrt(1 + 0.0006 * h * h)
                if (fh > maxPartialHz || fh > sr / 2.2) break
                val amp = 0.2 / h.toDouble().pow(rolloff)
                val ph = rnd.nextDouble() * 2 * PI
                val decay = 3.0 + 0.04 * (m - 60) * h
                for (i in 0 until n) {
                    val t = i.toDouble() / sr
                    out[i] += (amp * exp(-decay * t) * sin(2 * PI * fh * t + ph)).toFloat()
                }
            }
        }
        for (i in 0 until n) out[i] += (rnd.nextFloat() * 2 - 1) * noise
        return out
    }

    private fun toMidi(f: Float): Int = if (f <= 0) -1 else (69 + 12 * ln(f / 440.0) / ln(2.0)).roundToInt()

    private val highNotes = listOf(84, 86, 88, 89, 91, 93, 95, 96, 98, 100, 103, 105, 108)

    @Test fun singleModeHighNotes() {
        val d = MonoPitchDetector(sr)
        val wrong = highNotes.filter { toMidi(d.detect(tone(listOf(it), 2048))) != it }
        assertEquals("missed: ${wrong.map { NoteNames.nameWithOctave(it) }}", emptyList<Int>(), wrong)
    }

    @Test fun singleModeMidNotesStillWork() {
        val d = MonoPitchDetector(sr)
        for (m in listOf(40, 48, 57, 60, 69, 76)) {
            assertEquals(NoteNames.nameWithOctave(m), m, toMidi(d.detect(tone(listOf(m), 2048, rolloff = 1.1))))
        }
    }

    /** Real high piano notes: three slightly detuned strings, hammer thump, more noise. */
    private fun realisticHigh(m: Int, seed: Int = 3): FloatArray {
        val rnd = Random(seed)
        val n = 2048
        val out = FloatArray(n)
        val f0 = 440.0 * 2.0.pow((m - 69) / 12.0)
        for (cents in doubleArrayOf(-2.5, 0.0, 3.0)) {
            val f = f0 * 2.0.pow(cents / 1200)
            for (h in 1..4) {
                val fh = h * f * sqrt(1 + 0.0008 * h * h)
                if (fh > 6000) break
                val amp = 0.07 / h.toDouble().pow(2.2)
                val ph = rnd.nextDouble() * 2 * PI
                for (i in 0 until n) out[i] += (amp * exp(-8.0 * i / sr) * sin(2 * PI * fh * i / sr + ph)).toFloat()
            }
        }
        for (i in 0 until n) {
            val thump = 0.05 * exp(-i / 150.0) * (rnd.nextDouble() * 2 - 1)
            out[i] += (thump + (rnd.nextFloat() * 2 - 1) * 0.03).toFloat()
        }
        return out
    }

    @Test fun singleModeRealisticHighNotes() {
        val d = MonoPitchDetector(sr)
        val wrong = highNotes.filter { toMidi(d.detect(realisticHigh(it))) != it }
        if (System.getProperty("polydebug") != null) for (m in listOf(105, 108)) println("$m -> ${d.detect(realisticHigh(m))} Hz (want ${440.0 * Math.pow(2.0, (m - 69) / 12.0)})")
        assertEquals("missed: ${wrong.map { NoteNames.nameWithOctave(it) }}", emptyList<Int>(), wrong)
    }

    @Test fun singleModeNoiseGivesNothing() {
        val r = Random(11)
        val d = MonoPitchDetector(sr)
        val noise = FloatArray(2048) { (r.nextFloat() * 2 - 1) * 0.05f }
        assertEquals(-1f, d.detect(noise), 0f)
    }

    @Test fun chordModeHighNotes() {
        val wrong = highNotes.filter {
            PolyPitchDetector(sr).analyze(tone(listOf(it), 8192)).toSet() != setOf(it)
        }
        assertEquals("missed: ${wrong.map { NoteNames.nameWithOctave(it) }}", emptyList<Int>(), wrong)
    }

    /** Phone-like: partials above 5 kHz lost, quieter note relative to room noise. */
    @Test fun singleModeHighNotesPhoneLike() {
        val d = MonoPitchDetector(sr)
        val wrong = highNotes.filter {
            toMidi(d.detect(tone(listOf(it), 2048, noise = 0.02f, maxPartialHz = 5000.0))) != it
        }
        assertEquals("missed: ${wrong.map { NoteNames.nameWithOctave(it) }}", emptyList<Int>(), wrong)
    }

    @Test fun chordModeHighNotesPhoneLike() {
        val wrong = highNotes.filter {
            PolyPitchDetector(sr).analyze(tone(listOf(it), 8192, noise = 0.02f, maxPartialHz = 5000.0)).toSet() != setOf(it)
        }
        assertEquals("missed: ${wrong.map { NoteNames.nameWithOctave(it) }}", emptyList<Int>(), wrong)
    }

    @Test fun chordModeHighChord() {
        assertEquals(setOf(84, 88, 91), PolyPitchDetector(sr).analyze(tone(listOf(84, 88, 91), 8192)).toSet())
    }

    @Test fun chordModeHighAndLowTogether() {
        // left hand mid-range chord + right hand high melody note
        val d = PolyPitchDetector(sr).apply { debug = System.getProperty("polydebug") != null }
        val got = d.analyze(tone(listOf(48, 55, 91), 8192, rolloff = 1.6)).toSet()
        assertEquals(setOf(48, 55, 91), got)
    }
}
