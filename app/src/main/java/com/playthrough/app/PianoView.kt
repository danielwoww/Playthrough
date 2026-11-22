package com.playthrough.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.SparseIntArray
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws falling note tiles above a perspective ("3D") piano keyboard.
 * The keyboard is seen from above and in front: the back edge is slightly narrower than the
 * front, white keys show their front face, and black keys are raised blocks with bevels.
 */
class PianoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Callbacks {
        fun onFrame(dt: Double)
        fun onTouchNoteOn(midi: Int)
        fun onTouchNoteOff(midi: Int)
        /** The user started dragging the falling notes. */
        fun onScrubStart()
        /** Song-time offset (seconds) since the drag started; positive = forward. */
        fun onScrub(deltaSeconds: Double)
        fun onScrubEnd()
        /** Keys shown, fall speed or piano size changed by a gesture; [s] is the new settings. */
        fun onViewChanged(s: DisplaySettings)
    }

    var callbacks: Callbacks? = null
    var engine: PracticeEngine? = null

    private val dp = resources.displayMetrics.density
    private val sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)

    // ---- key range / layout in "white key units" ----
    private var lowMidi = 48
    private var highMidi = 83
    private var numWhite = 21
    private val keyU0 = FloatArray(128)
    private val keyU1 = FloatArray(128)

    // ---- screen geometry ----
    private var kbTop = 0f
    private var topH = 0f
    private var frontH = 0f
    private var railTop = 0f
    private var railBottom = 0f
    private var hitY = 0f
    private var keyW = 0f
    private var cx = 0f
    private var blackRaise = 0f
    private var pressDrop = 0f
    private var sBack = 0.94f          // back edge scale -> perspective
    private val blackLen = 0.63f        // black key length as fraction of white key depth

    // ---- user settings ----
    private var settings = DisplaySettings()
    private val visibleSeconds: Double get() = settings.lookAheadSeconds.toDouble()
    private var currentSong: Song? = null

    // ---- paints ----
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bgPaint = Paint()
    private val gridPaint = Paint().apply { strokeWidth = 1f }
    private val whiteTopPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val whiteFrontPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blackTopPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blackFrontPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val railPaint = Paint()
    private val railGlowPaint = Paint()
    private val beamPaints = arrayOfNulls<Paint>(4)
    private val overlay = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val path = Path()
    private val rect = RectF()

    private var lastFrameNs = 0L
    private val touches = SparseIntArray()

    companion object {
        private const val C_CORRECT = 0xFF39D98A.toInt()
        private const val C_WRONG = 0xFFFF4D5E.toInt()
        private const val C_NEUTRAL = 0xFF4DA3FF.toInt()
        private const val C_RIGHT = 0xFFFF9A3C.toInt()
        private const val C_RIGHT_DARK = 0xFFE0702A.toInt()
        private const val C_LEFT = 0xFF6F8BFF.toInt()
        private const val C_LEFT_DARK = 0xFF4A64E0.toInt()
        private const val C_MISSED = 0xFF6E6670.toInt()

        /** MIDI numbers of the 52 white keys of an 88-key piano (A0..C8). */
        private val WHITE_MIDI = (21..108).filter { !NoteNames.isBlack(it) }.toIntArray()

        /** Index into [WHITE_MIDI] of [midi], or of the white key just below it if it is black. */
        private fun whiteIndex(midi: Int): Int {
            val m = midi.coerceIn(21, 108)
            var i = 0
            while (i + 1 < WHITE_MIDI.size && WHITE_MIDI[i + 1] <= m) i++
            return i
        }
    }

    init {
        setRange(48, 83)
    }

    // ------------------------------------------------------------------ range

    fun applySettings(s: DisplaySettings) {
        // a new key count from the settings re-centres on the song
        if (s.visibleWhiteKeys != settings.visibleWhiteKeys) viewLo = -1
        settings = s
        updateRange()
    }

    fun fitToSong(song: Song?) {
        if (song !== currentSong) viewLo = -1
        currentSong = song
        updateRange()
    }

    /**
     * First shown white key (index into [WHITE_MIDI]) when a fixed number of keys is shown;
     * -1 = centre on the song. Set by two-finger dragging, reset when the song changes.
     */
    private var viewLo = -1

    private fun updateRange() {
        val song = currentSong
        val minM = song?.takeIf { it.notes.isNotEmpty() }?.minMidi ?: 60
        val maxM = song?.takeIf { it.notes.isNotEmpty() }?.maxMidi ?: 71
        var lo: Int
        var hi: Int
        val n = settings.visibleWhiteKeys
        if (n > 0) {
            // fixed zoom: n white keys, centred on the song unless the user moved the view
            val count = n.coerceIn(DisplaySettings.MIN_WHITE_KEYS, DisplaySettings.MAX_WHITE_KEYS)
            if (viewLo < 0) viewLo = (whiteIndex(minM) + whiteIndex(maxM) + 1) / 2 - count / 2
            viewLo = viewLo.coerceIn(0, WHITE_MIDI.size - count)
            setRange(WHITE_MIDI[viewLo], WHITE_MIDI[viewLo + count - 1])
            return
        } else {
            lo = (minM / 12) * 12
            hi = (maxM / 12) * 12 + 11
            // at least 3 octaves, or 2 on an upright screen so the keys stay wide enough to tap
            val minKeys = if (isPortrait) 24 else 36
            var grow = true
            while (hi - lo + 1 < minKeys) {
                if (grow) hi += 12 else lo -= 12
                grow = !grow
            }
        }
        if (lo < 21) { hi += 21 - lo; lo = 21 }
        if (hi > 108) { lo -= hi - 108; hi = 108 }
        // range must start on a white key and end on one
        while (NoteNames.isBlack(lo)) lo++
        while (NoteNames.isBlack(hi)) hi--
        setRange(max(lo, 21), min(hi, 108))
    }

    private fun setRange(low: Int, high: Int) {
        lowMidi = low
        highMidi = high
        var w = 0
        for (m in low..high) {
            if (!NoteNames.isBlack(m)) {
                keyU0[m] = w.toFloat(); keyU1[m] = (w + 1).toFloat(); w++
            }
        }
        numWhite = max(w, 1)
        val bw = 0.58f
        for (m in low..high) {
            if (NoteNames.isBlack(m)) {
                val boundary = if (m + 1 <= high) keyU0[m + 1] else numWhite.toFloat()
                val off = when (m % 12) {
                    1 -> -0.08f; 3 -> 0.08f; 6 -> -0.10f; 8 -> 0f; 10 -> 0.10f; else -> 0f
                }
                keyU0[m] = boundary + off - bw / 2
                keyU1[m] = boundary + off + bw / 2
            }
        }
        computeGeometry()
        invalidate()
    }

    // ---------------------------------------------------------------- geometry

    private val isPortrait get() = height > width

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // rotated: the automatic key range depends on the screen shape
        if ((h > w) != (oldh > oldw) && settings.visibleWhiteKeys == 0) updateRange() else computeGeometry()
    }

    private fun computeGeometry() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val depth = settings.depth3d.coerceIn(0f, 1f)
        // Sized from the short side: in landscape that is the height (as before); in portrait the
        // width, which keeps the keys a natural shape instead of very long and thin.
        val kbH = min(min(w, h) * settings.keyboardSize.coerceIn(0.12f, 0.7f), h * 0.7f)
        kbTop = h - kbH
        sBack = 1f - 0.12f * depth
        frontH = kbH * (0.04f + 0.16f * depth)
        topH = kbH * 0.90f - frontH
        blackRaise = max(frontH * 0.85f, 3 * dp)
        pressDrop = frontH * 0.45f
        railTop = kbTop - blackRaise - 5 * dp
        railBottom = kbTop + 5 * dp
        hitY = railTop
        val margin = 6 * dp
        keyW = (w - 2 * margin) / numWhite
        cx = w / 2f

        bgPaint.shader = LinearGradient(0f, 0f, 0f, hitY,
            0xFF1B1D25.toInt(), 0xFF111217.toInt(), Shader.TileMode.CLAMP)
        whiteTopPaint.shader = LinearGradient(0f, kbTop, 0f, kbTop + topH,
            intArrayOf(0xFFCFD0D7.toInt(), 0xFFF1F1F4.toInt(), 0xFFFFFFFF.toInt()),
            floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP)
        whiteFrontPaint.shader = LinearGradient(0f, kbTop + topH, 0f, kbTop + topH + frontH,
            0xFFD3D4DA.toInt(), 0xFF8A8B94.toInt(), Shader.TileMode.CLAMP)
        blackTopPaint.shader = LinearGradient(0f, kbTop - blackRaise, 0f, yAt(blackLen) - blackRaise,
            intArrayOf(0xFF141418.toInt(), 0xFF26272D.toInt(), 0xFF4A4B54.toInt()),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        blackFrontPaint.shader = LinearGradient(0f, yAt(blackLen) - blackRaise, 0f, yAt(blackLen),
            0xFF2A2B31.toInt(), 0xFF050507.toInt(), Shader.TileMode.CLAMP)
        railPaint.shader = LinearGradient(0f, railTop, 0f, railBottom,
            0xFF2E313B.toInt(), 0xFF0E0F13.toInt(), Shader.TileMode.CLAMP)
        railGlowPaint.shader = LinearGradient(0f, railTop - 14 * dp, 0f, railTop,
            0x009CC4F0, 0x669CC4F0, Shader.TileMode.CLAMP)

        val beamH = 110 * dp
        val colors = intArrayOf(0, C_NEUTRAL, C_CORRECT, C_WRONG)
        for (i in 1..3) {
            beamPaints[i] = Paint().apply {
                shader = LinearGradient(0f, hitY - beamH, 0f, hitY,
                    colors[i] and 0x00FFFFFF, (colors[i] and 0x00FFFFFF) or 0x99000000.toInt(),
                    Shader.TileMode.CLAMP)
            }
        }
    }

    private fun xAt(u: Float, d: Float): Float =
        cx + (u - numWhite / 2f) * keyW * (sBack + (1f - sBack) * d)

    private fun yAt(d: Float): Float = kbTop + d * topH

    // ------------------------------------------------------------------- draw

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrameNs = 0L
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0.0 else ((now - lastFrameNs) / 1e9).coerceAtMost(0.1)
        lastFrameNs = now
        callbacks?.onFrame(dt)

        val e = engine
        val pulse = (0.5 + 0.5 * sin(now / 1e9 * 6.0)).toFloat()

        drawBackground(canvas, e)
        drawLoop(canvas, e)
        drawTiles(canvas, e, pulse)
        drawBeams(canvas, e)
        drawKeyboard(canvas, e, pulse)
        drawRail(canvas)
        drawResizeHandle(canvas)
        drawFinished(canvas, e)

        postInvalidateOnAnimation()
    }

    private fun drawBackground(c: Canvas, e: PracticeEngine?) {
        val w = width.toFloat()
        c.drawRect(0f, 0f, w, hitY, bgPaint)
        // vertical lines at every C (strong) and F (faint)
        for (m in lowMidi..highMidi) {
            val pc = m % 12
            if (pc != 0 && pc != 5) continue
            val x = xAt(keyU0[m], 0f)
            gridPaint.color = if (pc == 0) 0xFF353946.toInt() else 0xFF22252E.toInt()
            c.drawLine(x, 0f, x, hitY, gridPaint)
        }
        // horizontal lines every second of song time, scrolling with the music
        val t = e?.time ?: 0.0
        val pps = hitY / visibleSeconds
        gridPaint.color = 0xFF1F222A.toInt()
        var k = kotlin.math.ceil(t).toInt()
        while (true) {
            val y = (hitY - (k - t) * pps).toFloat()
            if (y < 0) break
            c.drawLine(0f, y, w, y, gridPaint)
            k++
        }
    }

    /** Shades everything outside the A-B loop and draws the A / B marker lines. */
    private fun drawLoop(c: Canvas, e: PracticeEngine?) {
        e ?: return
        val a = e.loopStart
        val b = e.loopEnd
        if (a == null && b == null) return
        val pps = hitY / visibleSeconds
        val w = width.toFloat()
        fun yOf(t: Double) = (hitY - (t - e.time) * pps).toFloat()
        fill.shader = null
        if (a != null && b != null) {
            val yb = yOf(b).coerceIn(0f, hitY)
            val ya = yOf(a).coerceIn(0f, hitY)
            fill.color = 0x66000000
            if (yb > 0f) c.drawRect(0f, 0f, w, yb, fill)           // after B
            if (ya < hitY) c.drawRect(0f, ya, w, hitY, fill)        // before A
        }
        for ((t, label) in listOf(a to "A", b to "B")) {
            t ?: continue
            val y = yOf(t)
            if (y < 0f || y > hitY) continue
            fill.color = 0xFF39D98A.toInt()
            c.drawRect(0f, y - 1.5f * dp, w, y + 1.5f * dp, fill)
            text.textSize = 14 * sp
            val fm = text.fontMetrics
            val boxH = (fm.descent - fm.ascent) + 6 * dp
            rect.set(8 * dp, y - boxH - 2 * dp, 8 * dp + boxH * 1.2f, y - 2 * dp)
            c.drawRoundRect(rect, 5 * dp, 5 * dp, fill)
            text.color = 0xFF0E0F13.toInt()
            c.drawText(label, rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) / 2f, text)
        }
    }

    private fun drawTiles(c: Canvas, e: PracticeEngine?, pulse: Float) {
        val s = e?.song ?: return
        val t = e.time
        val pps = hitY / visibleSeconds
        val minTileH = 10 * dp
        val inset = max(1.5f * dp, keyW * 0.06f)
        val radius = 6 * dp
        val states = e.states
        val waitingSet = if (e.waiting) e.group else emptyList()

        var i = e.lowerBound(s, t - s.maxDuration - 0.1)
        while (i < s.notes.size) {
            val n = s.notes[i]
            val ds = n.start - t
            if (ds > visibleSeconds + 0.3) break
            if (n.midi < lowMidi || n.midi > highMidi) { i++; continue }
            val bottom = (hitY - ds * pps).toFloat()
            var top = (hitY - (ds + n.duration) * pps).toFloat()
            if (bottom - top < minTileH) top = bottom - minTileH
            if (top >= hitY) { i++; continue }
            val clippedBottom = min(bottom, hitY)

            val black = NoteNames.isBlack(n.midi)
            val x0 = xAt(keyU0[n.midi], 0f) + inset
            val x1 = xAt(keyU1[n.midi], 0f) - inset
            val practiced = e.isPracticed(n)
            val state = states[i]
            var color = when {
                state == PracticeEngine.MISSED -> C_MISSED
                n.hand == Hand.RIGHT -> if (black) C_RIGHT_DARK else C_RIGHT
                else -> if (black) C_LEFT_DARK else C_LEFT
            }
            val alpha = if (practiced) 255 else 95
            color = (color and 0x00FFFFFF) or (alpha shl 24)

            rect.set(x0, top, x1, clippedBottom)
            val r = min(radius, (x1 - x0) / 3f)
            fill.color = color
            c.drawRoundRect(rect, r, r, fill)

            // top sheen + border for a little depth
            stroke.strokeWidth = 1.2f * dp
            stroke.color = (0xFFFFFF) or ((alpha * 0.45f).toInt() shl 24)
            rect.inset(0.6f * dp, 0.6f * dp)
            c.drawRoundRect(rect, r, r, stroke)

            if (state == PracticeEngine.HIT) {
                fill.color = 0x55FFFFFF
                rect.set(x0, top, x1, clippedBottom)
                c.drawRoundRect(rect, r, r, fill)
            }

            if (i in waitingSet) {
                stroke.strokeWidth = 3 * dp
                stroke.color = Color.argb((120 + 135 * pulse).toInt(), 255, 255, 255)
                rect.set(x0 - 2 * dp, top - 2 * dp, x1 + 2 * dp, clippedBottom + 2 * dp)
                c.drawRoundRect(rect, r + 2 * dp, r + 2 * dp, stroke)
            }

            // note label at the bottom of the tile
            val tw = x1 - x0
            val ts = min(13 * sp, tw * 0.5f)
            if (settings.showTileLabels && ts >= 7 * sp && clippedBottom - top > ts * 1.5f) {
                text.textSize = ts
                text.color = (0xFFFFFF) or (alpha shl 24)
                c.drawText(NoteNames.name(n.midi), (x0 + x1) / 2f, clippedBottom - ts * 0.45f, text)
            }
            i++
        }
    }

    private fun drawBeams(c: Canvas, e: PracticeEngine?) {
        e ?: return
        if (!settings.showBeams) return
        val beamH = 110 * dp
        for (m in lowMidi..highMidi) {
            val st = e.keyPressed[m]
            if (st == PracticeEngine.KEY_NONE) continue
            val p = beamPaints[st] ?: continue
            c.drawRect(xAt(keyU0[m], 0f), hitY - beamH, xAt(keyU1[m], 0f), hitY, p)
        }
    }

    private fun drawKeyboard(c: Canvas, e: PracticeEngine?, pulse: Float) {
        fill.shader = null
        fill.color = 0xFF07070A.toInt()
        c.drawRect(0f, kbTop - blackRaise, width.toFloat(), height.toFloat(), fill)

        for (m in lowMidi..highMidi) if (!NoteNames.isBlack(m)) drawWhiteKey(c, m, e, pulse)
        for (m in lowMidi..highMidi) if (NoteNames.isBlack(m)) drawBlackShadow(c, m)
        for (m in lowMidi..highMidi) if (NoteNames.isBlack(m)) drawBlackKey(c, m, e, pulse)
    }

    /** Returns the overlay ARGB color for a key, or 0 for none. */
    private fun keyOverlay(m: Int, e: PracticeEngine?, pulse: Float, base: Int): Int {
        e ?: return 0
        return when (e.keyPressed[m]) {
            PracticeEngine.KEY_CORRECT -> (C_CORRECT and 0xFFFFFF) or (base shl 24)
            PracticeEngine.KEY_WRONG -> (C_WRONG and 0xFFFFFF) or (base shl 24)
            PracticeEngine.KEY_NEUTRAL -> (C_NEUTRAL and 0xFFFFFF) or (base shl 24)
            else -> {
                val tgt = e.keyTarget[m]
                if (tgt == PracticeEngine.TARGET_NONE) 0 else {
                    val col = if (tgt == PracticeEngine.TARGET_RIGHT) C_RIGHT else C_LEFT
                    val a = if (e.keyWaiting[m]) (base * (0.35f + 0.5f * pulse)).toInt() else (base * 0.65f).toInt()
                    (col and 0xFFFFFF) or (a shl 24)
                }
            }
        }
    }

    private fun drawWhiteKey(c: Canvas, m: Int, e: PracticeEngine?, pulse: Float) {
        val pressed = (e?.keyPressed?.get(m) ?: 0) != PracticeEngine.KEY_NONE
        val drop = if (pressed) pressDrop else 0f
        val g = 0.03f
        val u0 = keyU0[m] + g
        val u1 = keyU1[m] - g
        val yb = kbTop
        val yf = kbTop + topH
        val xb0 = xAt(u0, 0f); val xb1 = xAt(u1, 0f)
        val xf0 = xAt(u0, 1f); val xf1 = xAt(u1, 1f)

        c.save()
        c.translate(0f, drop)

        path.reset()
        path.moveTo(xb0, yb); path.lineTo(xb1, yb); path.lineTo(xf1, yf); path.lineTo(xf0, yf); path.close()
        c.drawPath(path, whiteTopPaint)
        val ov = keyOverlay(m, e, pulse, 200)
        if (ov != 0) { overlay.color = ov; c.drawPath(path, overlay) }

        // front face (shorter when pressed: the key sinks into the bed)
        val fh = frontH - drop
        rect.set(xf0, yf, xf1, yf + fh)
        c.drawRoundRect(rect, 2 * dp, 2 * dp, whiteFrontPaint)
        if (ov != 0) {
            overlay.color = (ov and 0xFFFFFF) or (((ov ushr 24) * 0.6f).toInt() shl 24)
            c.drawRoundRect(rect, 2 * dp, 2 * dp, overlay)
        }
        // front edge highlight
        stroke.strokeWidth = 1f * dp
        stroke.color = 0xCCFFFFFF.toInt()
        c.drawLine(xf0 + dp, yf, xf1 - dp, yf, stroke)

        val cxKey = (xf0 + xf1) / 2f
        val keyWidthPx = xf1 - xf0
        // labels
        val showName = pressed || (settings.showKeyLabels && e?.keyWaiting?.get(m) == true)
        if (settings.showKeyLabels && m % 12 == 0 && !showName) {
            text.textSize = min(10 * sp, keyWidthPx * 0.38f)
            text.color = 0xFF8C8F9A.toInt()
            c.drawText(NoteNames.nameWithOctave(m), cxKey, yf - 7 * dp, text)
        }
        if (showName) {
            text.textSize = min(15 * sp, keyWidthPx * 0.55f)
            text.color = if (pressed) 0xFFFFFFFF.toInt() else 0xFF3A2410.toInt()
            c.drawText(NoteNames.name(m), cxKey, yf - topH * 0.22f, text)
        }
        c.restore()
    }

    private fun drawBlackShadow(c: Canvas, m: Int) {
        val u0 = keyU0[m] + 0.02f
        val u1 = keyU1[m] + 0.12f
        val d = blackLen + 0.07f
        path.reset()
        path.moveTo(xAt(u0, 0f), kbTop)
        path.lineTo(xAt(u1, 0f), kbTop)
        path.lineTo(xAt(u1, d), yAt(d))
        path.lineTo(xAt(u0, d), yAt(d))
        path.close()
        fill.color = 0x50000000
        c.drawPath(path, fill)
    }

    private fun drawBlackKey(c: Canvas, m: Int, e: PracticeEngine?, pulse: Float) {
        val pressed = (e?.keyPressed?.get(m) ?: 0) != PracticeEngine.KEY_NONE
        val u0 = keyU0[m]
        val u1 = keyU1[m]
        val ti = (u1 - u0) * 0.11f
        val raise = if (pressed) blackRaise * 0.45f else blackRaise
        val topFrontD = blackLen - 0.04f
        val baseY = yAt(blackLen)
        val topBackY = kbTop - raise
        val topFrontY = yAt(topFrontD) - raise

        val b0x = xAt(u0, 0f); val b1x = xAt(u1, 0f)
        val b2x = xAt(u1, blackLen); val b3x = xAt(u0, blackLen)
        val t0x = xAt(u0 + ti, 0f); val t1x = xAt(u1 - ti, 0f)
        val t2x = xAt(u1 - ti, topFrontD); val t3x = xAt(u0 + ti, topFrontD)
        val leftOfCenter = (u0 + u1) / 2f < numWhite / 2f
        val litSide = 0xFF3C3D45.toInt()
        val darkSide = 0xFF141418.toInt()

        fill.shader = null
        // left bevel
        poly(t0x, topBackY, t3x, topFrontY, b3x, baseY, b0x, kbTop)
        fill.color = if (leftOfCenter) darkSide else litSide
        c.drawPath(path, fill)
        // right bevel
        poly(t1x, topBackY, t2x, topFrontY, b2x, baseY, b1x, kbTop)
        fill.color = if (leftOfCenter) litSide else darkSide
        c.drawPath(path, fill)
        // front face
        poly(t3x, topFrontY, t2x, topFrontY, b2x, baseY, b3x, baseY)
        c.drawPath(path, blackFrontPaint)
        val ov = keyOverlay(m, e, pulse, 210)
        if (ov != 0) {
            overlay.color = (ov and 0xFFFFFF) or (((ov ushr 24) * 0.5f).toInt() shl 24)
            c.drawPath(path, overlay)
        }
        // top surface
        poly(t0x, topBackY, t1x, topBackY, t2x, topFrontY, t3x, topFrontY)
        c.drawPath(path, blackTopPaint)
        if (ov != 0) { overlay.color = ov; c.drawPath(path, overlay) }
        // glossy highlight near the front
        stroke.strokeWidth = 1f * dp
        stroke.color = 0x55FFFFFF
        c.drawLine(t3x + dp, topFrontY - 2 * dp, t2x - dp, topFrontY - 2 * dp, stroke)

        val showName = pressed || (settings.showKeyLabels && e?.keyWaiting?.get(m) == true)
        if (showName) {
            text.textSize = min(12 * sp, (t2x - t3x) * 0.6f)
            text.color = 0xFFFFFFFF.toInt()
            c.drawText(NoteNames.name(m), (t2x + t3x) / 2f, topFrontY - 10 * dp, text)
        }
    }

    private fun poly(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        path.reset()
        path.moveTo(x0, y0); path.lineTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3); path.close()
    }

    private fun drawRail(c: Canvas) {
        val w = width.toFloat()
        c.drawRect(0f, railTop - 14 * dp, w, railTop, railGlowPaint)
        c.drawRect(0f, railTop, w, railBottom, railPaint)
        fill.shader = null
        fill.color = 0xFFAFD0F5.toInt()
        c.drawRect(0f, railTop, w, railTop + 2.5f * dp, fill)
        fill.color = 0x66000000
        c.drawRect(0f, railBottom, w, railBottom + 3 * dp, fill)
    }

    private fun drawFinished(c: Canvas, e: PracticeEngine?) {
        if (e == null || !e.finished) return
        val w = width.toFloat()
        val boxW = min(w - 32 * dp, 360 * dp)
        val boxH = 120 * dp
        val cy = hitY * 0.55f
        rect.set(w / 2 - boxW / 2, cy - boxH / 2, w / 2 + boxW / 2, cy + boxH / 2)
        fill.shader = null
        fill.color = 0xEE1E2029.toInt()
        c.drawRoundRect(rect, 16 * dp, 16 * dp, fill)
        stroke.strokeWidth = 1.5f * dp
        stroke.color = C_RIGHT
        c.drawRoundRect(rect, 16 * dp, 16 * dp, stroke)
        text.color = Color.WHITE
        text.textSize = 24 * sp
        c.drawText("Song complete!", w / 2, cy - 14 * dp, text)
        text.color = 0xFFC9CCD6.toInt()
        val acc = e.accuracy?.let { "${(it * 100).toInt()}% accuracy" } ?: "No notes judged"
        val stats = "$acc  ·  ✓ ${e.correctCount}  ✗ ${e.wrongCount}  missed ${e.missedCount}"
        text.textSize = 15 * sp
        val fit = (boxW - 20 * dp) / text.measureText(stats)
        if (fit < 1f) text.textSize = 15 * sp * fit          // narrow (portrait) screens
        c.drawText(stats, w / 2, cy + 16 * dp, text)
        text.textSize = 12 * sp
        text.color = 0xFF8C8F9A.toInt()
        c.drawText("Press ▶ to play again", w / 2, cy + 40 * dp, text)
    }

    // ------------------------------------------------------------------ touch

    private fun keyAt(x: Float, y: Float): Int {
        if (y < kbTop - blackRaise || y > kbTop + topH + frontH) return -1
        val d = ((y - kbTop) / topH).coerceIn(0f, 1f)
        val u = (x - cx) / (keyW * (sBack + (1f - sBack) * d)) + numWhite / 2f
        if (d <= blackLen) {
            for (m in lowMidi..highMidi) if (NoteNames.isBlack(m) && u >= keyU0[m] && u < keyU1[m]) return m
        }
        for (m in lowMidi..highMidi) if (!NoteNames.isBlack(m) && u >= keyU0[m] && u < keyU1[m]) return m
        return -1
    }

    // ------------------------------------------------- scrolling and view gestures
    //
    // In the falling-notes area:
    //  - one finger, up/down: scroll through the song (always)
    //  - two fingers (only while the view is unlocked):
    //      pinch sideways or drag sideways -> zoom / move along the keyboard (keys shown)
    //      pinch up/down                   -> note fall speed (seconds visible)
    //  - the handle just above the keys (only while unlocked): drag to resize the piano
    // A scroll that has already started never turns into a view change, and one finger alone
    // can never change the view (except on the small handle), so scrolling can't zoom by accident.

    private enum class Gesture { NONE, SCROLL, VIEW, RESIZE, DONE }
    private var gesture = Gesture.NONE
    private var scrubPointer = -1
    private var scrubStartY = 0f
    private var scrubbing = false
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    // two-finger view gesture
    private var viewAxis = 0                  // 0 = undecided, 1 = sideways (keys), 2 = up/down (fall speed)
    private var spanX0 = 0f
    private var spanY0 = 0f
    private var midX0 = 0f
    private var keys0 = 0
    private var keyUnderFingers0 = 0f         // absolute white-key position under the fingers' midpoint
    private var lookAhead0 = 0f
    private var viewChanged = false

    // piano resize handle
    private var resizeStartY = 0f
    private var resizeStartKbH = 0f

    private val handleHalfW get() = 70 * dp

    private fun onResizeHandle(x: Float, y: Float) =
        !settings.viewLocked && kotlin.math.abs(x - cx) < handleHalfW &&
            y >= railTop - 36 * dp && y <= railTop + 4 * dp

    /** White-key position (in keys from the left edge of the shown range) at screen x, for [n] keys. */
    private fun keysFromLeft(x: Float, n: Int): Float {
        val kw = (width - 12 * dp) / n
        return (x - cx) / (kw * sBack) + n / 2f
    }

    private fun startViewGesture(ev: MotionEvent) {
        gesture = Gesture.VIEW
        if (scrubbing) callbacks?.onScrubEnd()
        scrubbing = false
        viewAxis = 0
        viewChanged = false
        captureViewStart(ev)
    }

    /** Remembers where the two fingers started, so zooming is relative to that. */
    private fun captureViewStart(ev: MotionEvent) {
        spanX0 = kotlin.math.abs(ev.getX(0) - ev.getX(1))
        spanY0 = kotlin.math.abs(ev.getY(0) - ev.getY(1))
        midX0 = (ev.getX(0) + ev.getX(1)) / 2f
        keys0 = numWhite
        keyUnderFingers0 = whiteIndex(lowMidi) + keysFromLeft(midX0, numWhite)
        lookAhead0 = settings.lookAheadSeconds
    }

    // ---- pinching on the keyboard itself (only while unlocked) ----
    // Two fingers on the keys play notes as usual. Only when they clearly move apart or together
    // (more than a held chord drifts) do the notes stop and the pinch zoom the keys instead.
    private var keyPinchCandidate = false
    private val keyPinchThreshold get() = 24 * dp

    /** Returns true if the two-finger touch on the keys has turned into a zoom. */
    private fun checkKeyPinch(ev: MotionEvent): Boolean {
        if (!keyPinchCandidate || ev.pointerCount < 2) return false
        val spanX = kotlin.math.abs(ev.getX(0) - ev.getX(1))
        if (kotlin.math.abs(spanX - spanX0) < keyPinchThreshold) return false
        keyPinchCandidate = false
        for (k in 0 until touches.size()) callbacks?.onTouchNoteOff(touches.valueAt(k))
        touches.clear()
        gesture = Gesture.VIEW                     // the rest is handled like a pinch on the notes
        viewAxis = 1
        viewChanged = false
        moveViewGesture(ev)
        return true
    }

    private fun moveViewGesture(ev: MotionEvent) {
        if (ev.pointerCount < 2) return
        val spanX = kotlin.math.abs(ev.getX(0) - ev.getX(1))
        val spanY = kotlin.math.abs(ev.getY(0) - ev.getY(1))
        val midX = (ev.getX(0) + ev.getX(1)) / 2f
        if (viewAxis == 0) {
            val sideways = max(kotlin.math.abs(spanX - spanX0), kotlin.math.abs(midX - midX0))
            val upDown = kotlin.math.abs(spanY - spanY0)
            if (max(sideways, upDown) < touchSlop * 1.5f) return
            viewAxis = if (sideways >= upDown) 1 else 2
        }
        if (viewAxis == 1) {
            // fingers apart = fewer keys (zoom in); the key under the fingers stays under them
            val minSpan = 30 * dp
            val n = (keys0 * max(spanX0, minSpan) / max(spanX, minSpan)).roundToInt()
                .coerceIn(DisplaySettings.MIN_WHITE_KEYS, DisplaySettings.MAX_WHITE_KEYS)
            val lo = (keyUnderFingers0 - keysFromLeft(midX, n)).roundToInt().coerceIn(0, WHITE_MIDI.size - n)
            if (n != settings.visibleWhiteKeys || lo != viewLo) {
                settings = settings.copy(visibleWhiteKeys = n)
                viewLo = lo
                updateRange()
                viewChanged = true
            }
        } else {
            // fingers apart vertically = notes spread out = fewer seconds visible
            val minSpan = 40 * dp
            val secs = (lookAhead0 * max(spanY0, minSpan) / max(spanY, minSpan))
                .coerceIn(DisplaySettings.MIN_LOOK_AHEAD, DisplaySettings.MAX_LOOK_AHEAD)
            val rounded = (secs * 10).roundToInt() / 10f
            if (rounded != settings.lookAheadSeconds) {
                settings = settings.copy(lookAheadSeconds = rounded)
                viewChanged = true
            }
        }
    }

    private fun finishViewChange() {
        if (viewChanged) callbacks?.onViewChanged(settings)
        viewChanged = false
    }

    /** Falling-notes area: scrolling and (when unlocked) view gestures. Returns true if used. */
    private fun handleNotesArea(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesture = Gesture.NONE
                if (onResizeHandle(ev.x, ev.y)) {
                    gesture = Gesture.RESIZE
                    resizeStartY = ev.y
                    resizeStartKbH = height - kbTop
                    viewChanged = false
                    return true
                }
                if (ev.y < hitY && engine?.song != null) {
                    gesture = Gesture.SCROLL
                    scrubPointer = ev.getPointerId(0)
                    scrubStartY = ev.y
                    scrubbing = false
                    return true
                }
                return false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // a second finger before the scroll got going: a view gesture (if unlocked)
                if (gesture == Gesture.SCROLL && !scrubbing && !settings.viewLocked &&
                    ev.pointerCount == 2 && ev.getY(ev.actionIndex) < hitY) {
                    startViewGesture(ev)
                }
                return gesture != Gesture.NONE
            }
            MotionEvent.ACTION_MOVE -> when (gesture) {
                Gesture.SCROLL -> {
                    val i = ev.findPointerIndex(scrubPointer)
                    if (i < 0) return true
                    val dy = ev.getY(i) - scrubStartY
                    if (!scrubbing && kotlin.math.abs(dy) > touchSlop) {
                        scrubbing = true
                        scrubStartY = ev.getY(i)
                        callbacks?.onScrubStart()
                    } else if (scrubbing) {
                        // content follows the finger: drag down = notes move down = forward in time
                        callbacks?.onScrub(dy / (hitY / visibleSeconds))
                    }
                    return true
                }
                Gesture.VIEW -> { moveViewGesture(ev); return true }
                Gesture.RESIZE -> {
                    val shortSide = min(width, height).toFloat()
                    val kbH = resizeStartKbH - (ev.y - resizeStartY)
                    val size = (kbH / shortSide).coerceIn(DisplaySettings.MIN_KEYBOARD, DisplaySettings.MAX_KEYBOARD)
                    if (size != settings.keyboardSize) {
                        settings = settings.copy(keyboardSize = size)
                        computeGeometry()
                        viewChanged = true
                    }
                    return true
                }
                Gesture.DONE -> return true
                Gesture.NONE -> return false
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (gesture == Gesture.VIEW) {
                    finishViewChange()
                    gesture = Gesture.DONE            // ignore the rest until all fingers are up
                }
                return gesture != Gesture.NONE
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = gesture
                if (was == Gesture.SCROLL && scrubbing) callbacks?.onScrubEnd()
                if (was == Gesture.VIEW || was == Gesture.RESIZE) finishViewChange()
                gesture = Gesture.NONE
                scrubPointer = -1
                scrubbing = false
                return was != Gesture.NONE
            }
        }
        return gesture != Gesture.NONE
    }

    /** Grip above the keys, shown only while the view is unlocked: drag it to resize the piano. */
    private fun drawResizeHandle(c: Canvas) {
        if (settings.viewLocked) return
        val y = railTop - 16 * dp
        fill.shader = null
        fill.color = if (gesture == Gesture.RESIZE) 0xFFAFD0F5.toInt() else 0x66AFD0F5
        rect.set(cx - 28 * dp, y - 3 * dp, cx + 28 * dp, y + 3 * dp)
        c.drawRoundRect(rect, 3 * dp, 3 * dp, fill)
        rect.set(cx - 16 * dp, y - 11 * dp, cx + 16 * dp, y - 8 * dp)
        c.drawRoundRect(rect, 1.5f * dp, 1.5f * dp, fill)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (handleNotesArea(ev)) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                val m = keyAt(ev.getX(i), ev.getY(i))
                if (m >= 0) {
                    touches.put(ev.getPointerId(i), m)
                    callbacks?.onTouchNoteOn(m)
                }
                // exactly two fingers on the keys: could become a pinch
                keyPinchCandidate = !settings.viewLocked && ev.pointerCount == 2 &&
                    touches.size() == 2 && touches.indexOfKey(ev.getPointerId(0)) >= 0 &&
                    touches.indexOfKey(ev.getPointerId(1)) >= 0
                if (keyPinchCandidate) captureViewStart(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                if (checkKeyPinch(ev)) return true
                for (i in 0 until ev.pointerCount) {
                    val id = ev.getPointerId(i)
                    val old = touches.get(id, -1)
                    if (old < 0) continue
                    val m = keyAt(ev.getX(i), ev.getY(i))
                    if (m != old) {
                        callbacks?.onTouchNoteOff(old)
                        if (m >= 0) {
                            touches.put(id, m)
                            callbacks?.onTouchNoteOn(m)
                        } else {
                            touches.delete(id)
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                keyPinchCandidate = false
                val id = ev.getPointerId(ev.actionIndex)
                val old = touches.get(id, -1)
                if (old >= 0) callbacks?.onTouchNoteOff(old)
                touches.delete(id)
            }
            MotionEvent.ACTION_CANCEL -> {
                keyPinchCandidate = false
                for (k in 0 until touches.size()) callbacks?.onTouchNoteOff(touches.valueAt(k))
                touches.clear()
            }
        }
        return true
    }
}
