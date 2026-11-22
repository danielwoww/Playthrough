package com.playthrough.app

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class OnsetGateTest {
    private val hop = 1024.0 / 44100   // seconds per frame in single-note mode

    /** RMS per frame of a piano note envelope (with string beating) struck at the given times. */
    private fun envelope(strikes: List<Double>, seconds: Double, level: Double = 0.1): List<Float> {
        val n = (seconds / hop).toInt()
        return (0 until n).map { i ->
            val t = i * hop
            var e = 0.002                                   // room noise
            for (s in strikes) if (t >= s) {
                val a = t - s
                val attack = if (a < 0.03) a / 0.03 else 1.0  // ~30 ms rise, spans two frames
                e += level * attack * exp(-1.2 * a) * (1 + 0.15 * sin(2 * PI * 2.5 * a))
            }
            e.toFloat()
        }
    }

    private fun strikeCount(env: List<Float>): Int {
        val g = OnsetGate(refractoryFrames = 5)
        return env.count { g.update(it) }
    }

    @Test fun singleNoteIsOneStrike() = assertEquals(1, strikeCount(envelope(listOf(0.5), 3.0)))

    @Test fun repeatedNoteWhileRingingIsDetected() {
        // second strike 0.4 s later, first note still loud (~60%)
        assertEquals(2, strikeCount(envelope(listOf(0.5, 0.9), 3.0)))
    }

    @Test fun fastRepeatsAreDetected() {
        assertEquals(4, strikeCount(envelope(listOf(0.5, 0.8, 1.1, 1.4), 3.0)))
    }

    @Test fun beatingAloneIsNotAStrike() {
        // long sustained note with deeper beating: still one strike
        val n = (4.0 / hop).toInt()
        val env = (0 until n).map { i ->
            val t = i * hop
            (0.002 + if (t > 0.2) 0.1 * exp(-0.4 * (t - 0.2)) * (1 + 0.3 * sin(2 * PI * 1.5 * t)) else 0.0).toFloat()
        }
        assertEquals(1, strikeCount(env))
    }
}
