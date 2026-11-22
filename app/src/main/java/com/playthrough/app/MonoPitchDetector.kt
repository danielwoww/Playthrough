package com.playthrough.app

/**
 * Single-note pitch detector (YIN, de Cheveigné & Kawahara 2002) for one analysis frame.
 * Pure Kotlin so it can be unit-tested on the JVM.
 */
class MonoPitchDetector(
    private val sampleRate: Int = 44100,
    val frameSize: Int = 2048,
) {
    var threshold = 0.15f
    /** Highest detectable fundamental (C8 ≈ 4186 Hz needs a period of ~10 samples). */
    var maxFrequency = 4400f

    /** Accept a less clean frame if its clearest dip is below this. */
    var fallbackThreshold = 0.35f

    private val half = frameSize / 2
    private val diff = FloatArray(half)

    private fun firstDipBelow(limit: Float, tauMin: Int, tauMax: Int): Int {
        var tau = tauMin
        while (tau < tauMax) {
            if (diff[tau] < limit) {
                while (tau + 1 < tauMax && diff[tau + 1] < diff[tau]) tau++
                return tau
            }
            tau++
        }
        return -1
    }

    /** Returns the fundamental frequency in Hz, or -1 if the frame isn't periodic enough. */
    fun detect(x: FloatArray): Float {
        val w = half
        val tauMax = half
        val tauMin = (sampleRate / maxFrequency).toInt().coerceAtLeast(2)
        for (tau in 1 until tauMax) {
            var s = 0f
            for (j in 0 until w) {
                val d = x[j] - x[j + tau]
                s += d * d
            }
            diff[tau] = s
        }
        diff[0] = 1f
        var runningSum = 0f
        for (tau in 1 until tauMax) {
            runningSum += diff[tau]
            diff[tau] = if (runningSum > 0f) diff[tau] * tau / runningSum else 1f
        }
        var found = firstDipBelow(threshold, tauMin, tauMax)
        if (found < 0) {
            // YIN's fallback for pitched-but-not-clean frames (detuned piano unisons, fast decay):
            // take the first dip close to the clearest one, if that one is clear enough.
            var g = Float.MAX_VALUE
            for (t in tauMin until tauMax) if (diff[t] < g) g = diff[t]
            if (g < fallbackThreshold) found = firstDipBelow(g * 1.15f + 0.02f, tauMin, tauMax)
        }
        if (found < 0) return -1f
        // Top-octave guard: for very short periods (above ~1.5 kHz) detuned unison strings blur
        // the true dip and YIN can lock onto twice the period. Prefer half if it also dips clearly.
        if (sampleRate / found > 1500f) {
            val h = found / 2
            var best = -1
            for (t in (h - 1)..(h + 1)) {
                if (t >= tauMin && t < tauMax && (best < 0 || diff[t] < diff[best])) best = t
            }
            if (best > 0 && diff[best] < 0.5f) found = best
        }
        var better = found.toFloat()
        if (found in 1 until tauMax - 1) {
            val s0 = diff[found - 1]; val s1 = diff[found]; val s2 = diff[found + 1]
            val denom = s0 - 2 * s1 + s2
            if (denom != 0f) better = found + (s0 - s2) / (2 * denom)
        }
        return sampleRate / better
    }
}
