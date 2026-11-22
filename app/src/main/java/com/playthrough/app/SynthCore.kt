package com.playthrough.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The sound generator behind [PianoSynth]: voices of decaying partials from a sine wavetable.
 * No Android APIs, so it can render to a buffer in unit tests. Not thread-safe; [PianoSynth]
 * calls it only from its audio thread.
 */
class SynthCore(val sampleRate: Int) {

    private inner class Voice {
        var midi = -1
        var age = 0.0
        var hold = Double.MAX_VALUE
        var released = false
        var releaseAge = 0.0
        var velocity = 1f
        var inst: Instrument = Instrument.ALL[0]
        var partials = 0
        var tremPhase = 0.0
        var tremInc = 0.0
        val phase = DoubleArray(MAX_PARTIALS)
        val inc = DoubleArray(MAX_PARTIALS)
        val level = FloatArray(MAX_PARTIALS)
        val mul = FloatArray(MAX_PARTIALS)
        val active get() = midi >= 0

        fun start(m: Int, vel: Float, holdSec: Double, instrument: Instrument) {
            midi = m; age = 0.0; hold = holdSec; released = false; releaseAge = 0.0
            velocity = vel; inst = instrument
            partials = minOf(instrument.ratios.size, MAX_PARTIALS)
            val f0 = 440.0 * 2.0.pow((m - 69) / 12.0)
            val baseDecay = instrument.decay + max(0, m - 21) * instrument.pitchDecay
            for (p in 0 until partials) {
                val r = instrument.ratios[p]
                val fh = r * f0 * sqrt(1 + instrument.inharmonicity * r * r)
                inc[p] = if (fh in 25.0..sampleRate * 0.45) fh / sampleRate * TABLE else 0.0
                phase[p] = 0.0
                mul[p] = exp(-baseDecay * (1 + instrument.partialDecay * p) / sampleRate).toFloat()
                val darken = if (instrument.darkenHighNotes && m > 84) 0.6.pow(p) else 1.0
                level[p] = (instrument.amps[p] * darken * instrument.gain).toFloat()
            }
            tremPhase = 0.0
            tremInc = instrument.tremoloHz / sampleRate * TABLE
        }
    }

    companion object {
        private const val MAX_PARTIALS = 8
        private const val TABLE = 4096
        private const val MAX_VOICES = 24
        private val SINE = FloatArray(TABLE + 1) { sin(2 * PI * it / TABLE).toFloat() }
    }

    private val voices = Array(MAX_VOICES) { Voice() }
    var volume = 0.7f

    fun noteOn(midi: Int, velocity: Float, holdSeconds: Double, instrument: Instrument) {
        if (midi !in 21..108) return
        // retrigger the same key, or take a free voice, or steal the oldest
        val v = voices.firstOrNull { it.midi == midi && !it.released }
            ?: voices.firstOrNull { !it.active }
            ?: voices.maxByOrNull { if (it.released) it.age + 1000 else it.age }!!
        v.start(midi, velocity, holdSeconds, instrument)
    }

    fun noteOff(midi: Int) {
        for (v in voices) if (v.midi == midi && !v.released) v.hold = v.age
    }

    fun reset() { for (v in voices) v.midi = -1 }

    /** Renders [n] samples (mono, about -1..1) into [buf], replacing its contents. */
    fun render(buf: FloatArray, n: Int) {
        val dt = 1.0 / sampleRate
        java.util.Arrays.fill(buf, 0, n, 0f)
        for (v in voices) {
            if (!v.active) continue
            val attack = v.inst.attack
            val release = v.inst.release
            val trem = v.inst.tremoloDepth.toFloat()
            for (i in 0 until n) {
                val a = v.age
                if (!v.released && a >= v.hold) { v.released = true; v.releaseAge = a }
                var env = if (a < attack) (a / attack).toFloat() else 1f
                if (v.released) env *= max(0.0, 1 - (a - v.releaseAge) / release).toFloat()
                if (trem > 0f) {
                    env *= 1f - trem * 0.5f * (1f + SINE[v.tremPhase.toInt()])
                    v.tremPhase += v.tremInc
                    if (v.tremPhase >= TABLE) v.tremPhase -= TABLE
                }
                var s = 0f
                for (p in 0 until v.partials) {
                    if (v.inc[p] == 0.0) continue
                    val ph = v.phase[p]
                    val idx = ph.toInt()
                    val frac = (ph - idx).toFloat()
                    s += (SINE[idx] + (SINE[idx + 1] - SINE[idx]) * frac) * v.level[p]
                    v.level[p] *= v.mul[p]
                    var np = ph + v.inc[p]
                    if (np >= TABLE) np -= TABLE
                    v.phase[p] = np
                }
                buf[i] += s * env * v.velocity
                v.age = a + dt
            }
            val finished = (v.released && v.age - v.releaseAge >= release) || (v.inst.decay > 0 && v.age > 12.0)
            if (finished) v.midi = -1
        }
        val vol = volume * 0.35f
        for (i in 0 until n) {
            val x = buf[i] * vol
            buf[i] = x / (1f + abs(x))          // soft clip when many notes sound together
        }
    }
}
