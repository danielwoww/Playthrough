package com.playthrough.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Tests the chord detector on synthetic piano-like tones (decaying, slightly inharmonic partials). */
class PolyPitchTest {

    private val sr = 44100

    private fun pianoChord(
        notes: List<Int>, noise: Float = 0.002f, seed: Int = 1, fundamentalGain: Double = 1.0,
    ): FloatArray {
        val rnd = Random(seed)
        val n = 8192
        val out = FloatArray(n)
        for (m in notes) {
            val f0 = 440.0 * 2.0.pow((m - 69) / 12.0)
            val b = 0.0003
            for (h in 1..16) {
                val fh = h * f0 * sqrt(1 + b * h * h)
                if (fh > sr / 2.2) break
                // typical piano: strong 1st-3rd partials, weaker above; low notes have a weak fundamental
                var amp = 1.0 / h.toDouble().pow(1.1)
                if (m < 48 && h == 1) amp *= 0.35
                if (h == 1) amp *= fundamentalGain
                val phase = rnd.nextDouble() * 2 * PI
                val decay = 1.5 + h * 0.3
                for (i in 0 until n) {
                    val t = i.toDouble() / sr
                    out[i] += (0.15 * amp * exp(-decay * t) * sin(2 * PI * fh * t + phase)).toFloat()
                }
            }
        }
        for (i in 0 until n) out[i] += (rnd.nextFloat() * 2 - 1) * noise
        return out
    }

    private fun detect(notes: List<Int>, seed: Int = 1): Set<Int> {
        val d = PolyPitchDetector(sr)
        d.debug = System.getProperty("polydebug") != null
        if (d.debug) println("--- $notes")
        return d.analyze(pianoChord(notes, seed = seed)).toSet()
    }

    @Test fun singleNotes() {
        for (m in listOf(45, 52, 60, 69, 76, 84)) {
            assertEquals("note $m", setOf(m), detect(listOf(m)))
        }
    }

    @Test fun cMajorTriad() = assertEquals(setOf(60, 64, 67), detect(listOf(60, 64, 67)))

    @Test fun aMinorTriad() = assertEquals(setOf(57, 60, 64), detect(listOf(57, 60, 64)))

    @Test fun twoHandsChord() = assertEquals(setOf(48, 64, 67, 72), detect(listOf(48, 64, 67, 72)))

    @Test fun dyadThird() = assertEquals(setOf(65, 69), detect(listOf(65, 69)))

    @Test fun octaveKeepsBoth() {
        val d = detect(listOf(60, 72))
        assertTrue("got $d", 60 in d && 72 in d)
    }

    @Test fun noisyRoomTriad() {
        val d = PolyPitchDetector(sr)
        d.debug = System.getProperty("polydebug") != null
        // noise at roughly -25 dB relative to the chord
        assertEquals(setOf(60, 64, 67), d.analyze(pianoChord(listOf(60, 64, 67), noise = 0.03f)).toSet())
    }

    @Test fun loudNoiseOnlyGivesNothing() {
        val r = Random(5)
        val d = PolyPitchDetector(sr)
        d.debug = System.getProperty("polydebug") != null
        val noise = FloatArray(8192) { (r.nextFloat() * 2 - 1) * 0.1f }
        assertEquals(emptyList<Int>(), d.analyze(noise))
    }

    /** Phone mic / small speaker: weak fundamentals used to make C and F jump an octave up. */
    @Test fun weakFundamentalsKeepRightOctave() {
        for (chord in listOf(listOf(60, 64, 67), listOf(65, 69, 72), listOf(57, 60, 64))) {
            val d = PolyPitchDetector(sr)
            d.debug = System.getProperty("polydebug") != null
            val got = d.analyze(pianoChord(chord, fundamentalGain = 0.12)).toSet()
            assertEquals("chord $chord", chord.toSet(), got)
        }
    }

    @Test fun knockGivesNothing() {
        // a thump: low-passed decaying noise burst, like a knock on the table
        val r = Random(9)
        var y = 0f
        val d = PolyPitchDetector(sr)
        d.debug = System.getProperty("polydebug") != null
        val knock = FloatArray(8192) { i ->
            y = 0.92f * y + 0.08f * (r.nextFloat() * 2 - 1)
            y * 3f * exp(-i / 900.0).toFloat()
        }
        assertEquals(emptyList<Int>(), d.analyze(knock))
    }

    @Test fun silenceGivesNothing() {
        val r = Random(3)
        val noise = FloatArray(8192) { (r.nextFloat() * 2 - 1) * 0.002f }
        assertTrue(PolyPitchDetector(sr).analyze(noise).size <= 1)
    }
}
