package com.playthrough.app

import android.content.Context

/**
 * A synth voice recipe: a few partials (frequency ratio + level), how fast they die away,
 * and envelope/tremolo settings. Used by [PianoSynth] for practice playback and the editor.
 */
class Instrument(
    val id: String,
    val name: String,
    /** Partial frequencies as multiples of the note's pitch. */
    val ratios: DoubleArray,
    /** Partial levels (same length as [ratios]). */
    val amps: DoubleArray,
    /** Decay per second of the first partial at the lowest key (0 = sustains while held). */
    val decay: Double,
    /** Extra decay per semitone above A0 (high notes die out faster). */
    val pitchDecay: Double,
    /** Each further partial decays this much faster (relative), e.g. 0.45 = +45 % per partial. */
    val partialDecay: Double,
    val attack: Double,
    val release: Double,
    /** String stiffness: stretches upper partials (pianos ~0.0004, organs 0). */
    val inharmonicity: Double = 0.0,
    /** High notes get purer (upper partials fade with pitch). */
    val darkenHighNotes: Boolean = false,
    val tremoloHz: Double = 0.0,
    val tremoloDepth: Double = 0.0,
    val gain: Double = 1.0,
) {
    companion object {
        val ALL = listOf(
            Instrument("grand", "Grand piano",
                ratios = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0),
                amps = doubleArrayOf(1.0, 0.45, 0.28, 0.16, 0.09, 0.05),
                decay = 0.5, pitchDecay = 0.055, partialDecay = 0.45,
                attack = 0.004, release = 0.12, inharmonicity = 0.0004, darkenHighNotes = true),
            Instrument("soft", "Soft piano",
                ratios = doubleArrayOf(1.0, 2.0, 3.0, 4.0),
                amps = doubleArrayOf(1.0, 0.22, 0.07, 0.025),
                decay = 0.35, pitchDecay = 0.04, partialDecay = 0.9,
                attack = 0.014, release = 0.3, inharmonicity = 0.0003, darkenHighNotes = true, gain = 1.25),
            Instrument("electric", "Electric piano",
                // tine-like: strong fundamental, quick metallic "bell" partial on top
                ratios = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 7.1),
                amps = doubleArrayOf(1.0, 0.25, 0.1, 0.05, 0.18),
                decay = 0.45, pitchDecay = 0.03, partialDecay = 0.6,
                attack = 0.003, release = 0.18, tremoloHz = 4.8, tremoloDepth = 0.18, gain = 1.15),
            Instrument("organ", "Organ",
                // drawbar-style: sub-octave, unison, octaves and fifths; sustains while held
                ratios = doubleArrayOf(0.5, 1.0, 2.0, 3.0, 4.0, 6.0, 8.0),
                amps = doubleArrayOf(0.45, 1.0, 0.6, 0.4, 0.3, 0.15, 0.1),
                decay = 0.0, pitchDecay = 0.0, partialDecay = 0.0,
                attack = 0.012, release = 0.08, gain = 0.7),
            Instrument("harpsichord", "Harpsichord",
                ratios = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0),
                amps = doubleArrayOf(1.0, 0.8, 0.65, 0.55, 0.45, 0.35, 0.28, 0.22),
                decay = 0.9, pitchDecay = 0.045, partialDecay = 0.2,
                attack = 0.001, release = 0.05, gain = 0.6),
        )

        fun byId(id: String?) = ALL.firstOrNull { it.id == id } ?: ALL[0]

        private const val PREFS = "practice"
        private const val KEY = "instrument"

        /** The chosen sound, shared by practice playback and the editor. */
        fun load(context: Context) =
            byId(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

        fun save(context: Context, instrument: Instrument) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, instrument.id).apply()
        }

        fun next(current: Instrument) = ALL[(ALL.indexOf(current) + 1) % ALL.size]
    }
}
