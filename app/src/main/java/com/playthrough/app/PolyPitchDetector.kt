package com.playthrough.app

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * EXPERIMENTAL multiple-note (chord) detector for microphone audio.
 *
 * Method (simplified from Klapuri, "Multiple fundamental frequency estimation by summing
 * harmonic amplitudes", 2006):
 *  1. Hann-windowed FFT of a long frame (8192 samples ≈ 186 ms, so low notes are resolvable).
 *  2. Spectral whitening: divide by the local spectral envelope so all registers count equally.
 *  3. For every candidate piano key, salience = weighted sum of the spectral peaks found at its
 *     harmonic positions (with a little piano inharmonicity).
 *  4. Iteratively pick the strongest candidate, then cancel its harmonics from the residual
 *     spectrum. Cancellation uses spectral smoothing, so a note an octave higher, which shares
 *     every other harmonic, is not wiped out.
 *  5. Stop when the next candidate is weak relative to the first one or to the noise level.
 *
 * Pure Kotlin (no Android APIs), so it can be unit-tested on the JVM.
 */
class PolyPitchDetector(
    private val sampleRate: Int = 44100,
    val fftSize: Int = 8192,
) {
    var minMidi = 33          // A1
    var maxMidi = 108         // C8
    var maxPolyphony = 5
    /** Candidate salience must exceed this multiple of what pure noise would give it. */
    var noiseRatio = 4f
    /** Later notes must reach this fraction of the first note's salience. */
    var relativeThreshold = 0.22f

    private val half = fftSize / 2
    private val binHz = sampleRate.toFloat() / fftSize
    private val maxFreq = 6000f
    private val maxHarmonics = 20
    private val inharmonicity = 0.0003f

    private val window = FloatArray(fftSize) { (0.5 - 0.5 * cos(2 * PI * it / (fftSize - 1))).toFloat() }
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val mag = FloatArray(half)
    private val prefix = DoubleArray(half + 1)
    private val residual = FloatArray(half)
    private val amps = FloatArray(maxHarmonics + 2)
    private val peakBins = IntArray(maxHarmonics + 2)

    // precomputed twiddles + bit reversal
    private val cosT = FloatArray(half) { cos(-2 * PI * it / fftSize).toFloat() }
    private val sinT = FloatArray(half) { sin(-2 * PI * it / fftSize).toFloat() }
    private val rev = IntArray(fftSize).also { r ->
        val bits = Integer.numberOfTrailingZeros(fftSize)
        for (i in 0 until fftSize) r[i] = Integer.reverse(i) ushr (32 - bits)
    }

    init {
        require(fftSize and (fftSize - 1) == 0) { "fftSize must be a power of two" }
    }

    /**
     * Analyses [samples] (exactly [fftSize] samples, oldest first, range about -1..1).
     * Returns the detected MIDI notes, strongest first.
     */
    fun analyze(samples: FloatArray): List<Int> {
        for (i in 0 until fftSize) {
            re[rev[i]] = samples[i] * window[i]
            im[rev[i]] = 0f
        }
        fft()
        for (k in 0 until half) mag[k] = sqrt(re[k] * re[k] + im[k] * im[k])
        whiten()
        System.arraycopy(residual, 0, whitened, 0, half)

        val floor = noiseFloor()
        val result = ArrayList<Int>(maxPolyphony)
        var firstSal = 0f
        for (q in 1..maxPolyphony) {
            var best = -1
            var bestRatio = 0f
            var bestSal = 0f
            for (m in minMidi..maxMidi) {
                if (m in result) continue
                val s = salience(m)
                // compare against what pure noise would give this candidate
                val ratio = s / (floor * noiseSalience(m))
                if (ratio <= noiseRatio || s <= bestSal) continue
                // a real string has several clear partials; thumps and clicks don't
                var strong = 0
                for (h in 1..lastHarmonics) if (amps[h] > floor * partialRatio) strong++
                if (strong < requiredStrongPartials(m)) continue
                bestSal = s; best = m; bestRatio = ratio
            }
            if (best < 0) break
            if (q == 1) firstSal = bestSal
            else if (bestSal < firstSal * relativeThreshold) break
            if (debug) println("q=$q best=${NoteNames.nameWithOctave(best)} sal=$bestSal snr=$bestRatio")
            result.add(best)
            cancel(best)
        }
        octaveCorrect(result, floor)
        return result
    }

    /**
     * Octave-error guard, run on the finished note list against the original whitened spectrum.
     * A note's upper octave shares every other partial with it, so when the lower note's fundamental
     * is weak (small mics/speakers) the upper octave can win. Move a note down an octave when the
     * lower note has a strong fundamental that no other detected note explains, plus at least one
     * more strong odd partial.
     */
    private fun octaveCorrect(result: MutableList<Int>, floor: Float) {
        System.arraycopy(whitened, 0, residual, 0, half)   // findPeaks reads residual
        val strong = floor * partialRatio
        for (i in result.indices) {
            val m = result[i]
            val lower = m - 12
            if (lower < minMidi || lower in result) continue
            val n = findPeaks(lower)
            if (n < 3 || amps[1] <= strong) continue
            // the lower fundamental must be a real part of the tone, not a stray peak
            if (amps[1] < 0.5f * amps[2]) continue
            val f0 = f0Of(lower)
            if (explainedByOthers(f0, result, m)) continue
            var odd = 0
            var h = 3
            while (h <= n && h <= 9) {
                val fh = h * f0 * sqrt(1f + inharmonicity * h * h)
                if (amps[h] > strong && !explainedByOthers(fh, result, m)) odd++
                h += 2
            }
            if (odd >= 1) {
                if (debug) println("octave fix ${NoteNames.nameWithOctave(m)} -> ${NoteNames.nameWithOctave(lower)}")
                result[i] = lower
            }
        }
    }

    /** True when [freq] is (close to) a partial of some detected note other than [except]. */
    private fun explainedByOthers(freq: Float, notes: List<Int>, except: Int): Boolean {
        for (o in notes) {
            if (o == except) continue
            val f0 = f0Of(o)
            var h = 1
            while (h * f0 <= freq * 1.05f) {
                val fh = h * f0 * sqrt(1f + inharmonicity * h * h)
                if (kotlin.math.abs(fh - freq) / freq < 0.02f) return true
                h++
            }
        }
        return false
    }

    private val whitened = FloatArray(half)

    /** Sum of harmonic weights times a peak-picking factor: expected salience on a flat floor of 1. */
    private fun noiseSalience(midi: Int): Float {
        val f0 = f0Of(midi)
        var s = 0f
        var h = 1
        while (h <= maxHarmonics && h * f0 <= maxFreq) {
            s += (f0 + 52f) / (h * f0 + 320f)
            h++
        }
        return s * 2.2f
    }

    /** Median of the whitened spectrum between 50 Hz and maxFreq: the typical non-peak level. */
    private fun noiseFloor(): Float {
        val lo = (50f / binHz).toInt()
        val hi = min(half - 1, (maxFreq / binHz).toInt())
        val n = hi - lo + 1
        if (floorBuf.size < n) floorBuf = FloatArray(n)
        System.arraycopy(residual, lo, floorBuf, 0, n)
        java.util.Arrays.sort(floorBuf, 0, n)
        return floorBuf[n / 2] + 1e-9f
    }

    private var floorBuf = FloatArray(0)

    var debug = false

    /**
     * High notes are nearly pure tones and phone mics lose their upper partials, so the
     * "several clear partials" rule relaxes with pitch: 3 below 500 Hz, 2 up to 1 kHz, 1 above.
     */
    private fun requiredStrongPartials(midi: Int): Int {
        val f0 = f0Of(midi)
        val need = when {
            f0 >= 1000f -> 1
            f0 >= 500f -> min(2, minStrongPartials)
            else -> minStrongPartials
        }
        return min(need, lastHarmonics)
    }
    /** A note needs this many partials (or all it has below maxFreq) above [partialRatio] x floor. */
    var minStrongPartials = 3
    var partialRatio = 6f
    private var lastHarmonics = 0

    private fun f0Of(midi: Int) = (440.0 * 2.0.pow((midi - 69) / 12.0)).toFloat()

    /** Fills peak arrays for [midi] over [residual] and returns harmonic count. */
    private fun findPeaks(midi: Int): Int {
        val f0 = f0Of(midi)
        var n = 0
        for (h in 1..maxHarmonics) {
            val fh = h * f0 * sqrt(1f + inharmonicity * h * h)
            if (fh > maxFreq) break
            val center = fh / binHz
            val tol = max(1.2f, center * 0.017f)
            val lo = max(1, (center - tol).toInt())
            val hi = min(half - 2, (center + tol + 0.999f).toInt())
            if (lo > hi) break
            var pk = lo
            for (b in lo..hi) if (residual[b] > residual[pk]) pk = b
            n++
            amps[n] = residual[pk]
            peakBins[n] = pk
        }
        return n
    }

    private fun salience(midi: Int): Float {
        val f0 = f0Of(midi)
        val n = findPeaks(midi)
        var s = 0f
        for (h in 1..n) s += (f0 + 52f) / (h * f0 + 320f) * amps[h]
        lastHarmonics = n
        return s
    }

    private fun cancel(midi: Int) {
        val n = findPeaks(midi)
        for (h in 1..n) {
            val a = amps[h]
            if (a <= 0f) continue
            val prev = if (h > 1) amps[h - 1] else a
            val next = if (h < n) amps[h + 1] else a
            // spectral smoothness: remove at most the local average of neighbouring partials
            val remove = min(a, (prev + a + next) / 3f)
            val keep = 1f - remove / a
            val c = peakBins[h]
            for (b in max(1, c - 2)..min(half - 1, c + 2)) residual[b] *= keep
        }
    }

    private fun whiten() {
        prefix[0] = 0.0
        for (k in 0 until half) prefix[k + 1] = prefix[k] + mag[k]
        val globalMean = (prefix[half] / half).toFloat()
        val eps = globalMean * 0.2f + 1e-9f
        val kMax = min(half - 1, (maxFreq * 1.1f / binHz).toInt())
        for (k in 0 until half) {
            if (k > kMax) { residual[k] = 0f; continue }
            val w = max(4, (k * 0.08f).toInt())
            val lo = max(0, k - w)
            val hi = min(half, k + w + 1)
            val env = ((prefix[hi] - prefix[lo]) / (hi - lo)).toFloat()
            residual[k] = mag[k] / (env + eps).pow(0.67f)
        }
    }

    private fun fft() {
        var len = 2
        while (len <= fftSize) {
            val halfLen = len / 2
            val step = fftSize / len
            var i = 0
            while (i < fftSize) {
                var t = 0
                for (k in 0 until halfLen) {
                    val a = i + k
                    val b = a + halfLen
                    val wr = cosT[t]; val wi = sinT[t]
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    t += step
                }
                i += len
            }
            len = len shl 1
        }
    }
}
