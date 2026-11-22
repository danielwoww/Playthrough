package com.playthrough.app

import android.content.Context

/** User-adjustable look of the practice screen, saved between sessions. */
data class DisplaySettings(
    /** Keyboard height as a fraction of the screen's short side. */
    val keyboardSize: Float = 0.36f,
    /** 0 = flat, 1 = strong perspective and thick keys. */
    val depth3d: Float = 0.5f,
    /** Number of white keys shown (7..52); 0 = fit to the song automatically. */
    val visibleWhiteKeys: Int = 0,
    /** Seconds of music visible above the keyboard (lower = tiles fall faster). */
    val lookAheadSeconds: Float = 3.0f,
    val showTileLabels: Boolean = true,
    val showKeyLabels: Boolean = true,
    val showBeams: Boolean = true,
    /** Practice screen held upright (portrait) instead of sideways. */
    val portrait: Boolean = false,
    /** Playback speed in percent, 5..125 in steps of 5. */
    val speedPercent: Int = 100,
    /** When locked, pinching and dragging the handle can't change the view. */
    val viewLocked: Boolean = false,
) {
    companion object {
        private const val PREFS = "display"
        const val MIN_WHITE_KEYS = 7
        const val MAX_WHITE_KEYS = 52      // the full 88-key piano
        const val MIN_LOOK_AHEAD = 1.5f
        const val MAX_LOOK_AHEAD = 8f
        const val MIN_KEYBOARD = 0.15f
        const val MAX_KEYBOARD = 0.60f

        fun load(context: Context): DisplaySettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = DisplaySettings()
            return DisplaySettings(
                keyboardSize = p.getFloat("keyboardSize", d.keyboardSize),
                depth3d = p.getFloat("depth3d", d.depth3d),
                // older versions stored whole octaves
                visibleWhiteKeys = if (p.contains("visibleWhiteKeys")) p.getInt("visibleWhiteKeys", 0)
                                   else p.getInt("octaves", 0) * 7,
                lookAheadSeconds = p.getFloat("lookAheadSeconds", d.lookAheadSeconds),
                showTileLabels = p.getBoolean("showTileLabels", d.showTileLabels),
                showKeyLabels = p.getBoolean("showKeyLabels", d.showKeyLabels),
                showBeams = p.getBoolean("showBeams", d.showBeams),
                portrait = p.getBoolean("portrait", d.portrait),
                speedPercent = p.getInt("speedPercent", d.speedPercent).coerceIn(5, 125),
                viewLocked = p.getBoolean("viewLocked", d.viewLocked),
            )
        }
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("keyboardSize", keyboardSize)
            .putFloat("depth3d", depth3d)
            .putInt("visibleWhiteKeys", visibleWhiteKeys)
            .remove("octaves")
            .putFloat("lookAheadSeconds", lookAheadSeconds)
            .putBoolean("showTileLabels", showTileLabels)
            .putBoolean("showKeyLabels", showKeyLabels)
            .putBoolean("showBeams", showBeams)
            .putBoolean("portrait", portrait)
            .putInt("speedPercent", speedPercent)
            .putBoolean("viewLocked", viewLocked)
            .apply()
    }
}
