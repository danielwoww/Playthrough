package com.playthrough.app

import kotlin.math.max
import kotlin.math.min

/**
 * Loudness tracker for the mic: an adaptive noise gate plus a strike (onset) detector.
 *
 * A strike is a clear rise above the quietest of the last few frames, so a key struck again while
 * the previous note still rings is recognised even when the rise is spread over two frames.
 * After a strike, new strikes are ignored for [refractoryFrames] so string beating can't fake one.
 * Pure Kotlin for unit tests.
 */
class OnsetGate(
    private val refractoryFrames: Int,
    private val riseRatio: Float = 1.4f,
    private val lookback: Int = 3,
) {
    var noiseFloor = 0.003f
        private set
    var gate = 0.006f
        private set

    private val recent = FloatArray(lookback) { Float.MAX_VALUE }
    private var pos = 0
    private var sinceStrike = Int.MAX_VALUE / 2

    /** Feed one frame's RMS; returns true when this frame is a strike. */
    fun update(rms: Float): Boolean {
        noiseFloor = if (rms < noiseFloor) noiseFloor * 0.8f + rms * 0.2f else noiseFloor * 1.003f
        noiseFloor = noiseFloor.coerceIn(0.0005f, 0.05f)
        gate = max(0.006f, noiseFloor * 3.5f)

        var lowest = Float.MAX_VALUE
        for (v in recent) lowest = min(lowest, v)
        val base = if (lowest == Float.MAX_VALUE) 0f else max(lowest, gate * 0.5f)
        sinceStrike++
        val strike = rms > gate * 1.5f && rms > base * riseRatio && sinceStrike > refractoryFrames
        if (strike) sinceStrike = 0
        recent[pos] = rms
        pos = (pos + 1) % lookback
        return strike
    }
}
