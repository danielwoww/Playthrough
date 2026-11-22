package com.playthrough.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Piano-roll note editor. Time runs left to right, pitch bottom to top, with a key strip on the left.
 *
 * Navigation: drag with one finger to scroll (with fling), pinch to zoom. A horizontal pinch zooms
 * time, a vertical pinch zooms pitch, a diagonal pinch zooms both.
 *
 * Editing ([Mode.ADD]): tap empty space to add a note, tap a note to select it. Drag the selected
 * note to move it, or drag its right edge (the white handle) to change its length.
 * [Mode.ERASE]: tap notes to delete them.
 */
class PianoRollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onSelectionChanged(note: Note?)
        fun onEdited()
        /** A note was added, tapped or moved to a new pitch: play it briefly. */
        fun onPreviewNote(midi: Int) {}
        /** The user moved the playhead by tapping the time ruler. */
        fun onPlayheadMoved(seconds: Double) {}
    }

    /** Playback position in seconds (green line). */
    var playhead = 0.0
        set(v) { field = max(0.0, v); invalidate() }
    /** While true, notes under the playhead light up. */
    var playing = false
        set(v) { field = v; invalidate() }

    /** Scrolls so the playhead stays visible during playback. */
    fun followPlayhead() {
        if (width == 0) return
        val x = xOf(playhead)
        if (x > width * 0.88f || x < keysW) {
            val visibleSec = (width - keysW) / pxPerSec
            t0 = playhead - visibleSec * 0.12
            clampView()
            invalidate()
        }
    }

    enum class Mode { ADD, ERASE }

    var listener: Listener? = null
    var mode = Mode.ADD
    var addHand = Hand.RIGHT

    /** Snap grid in beats; 0 = no snapping. */
    var gridBeats = 1.0
        set(v) { field = v; invalidate() }
    var beatSeconds = 0.5
        private set
    val gridSeconds: Double get() = gridBeats * beatSeconds

    private val notes = ArrayList<Note>()
    var selected: Note? = null
        private set
    private var defaultLength = 0.5

    private val undoStack = ArrayList<List<Note>>()
    private val redoStack = ArrayList<List<Note>>()
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    private val dp = resources.displayMetrics.density
    private val sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)
    private val keysW = 58 * dp
    private val rulerH = 26 * dp

    // ---- viewport ----
    private var t0 = 0.0                 // seconds at the left edge of the note area
    private var pxPerSec = 150.0 * dp
    private var topMidi = 84f            // pitch whose row top sits at the ruler's bottom edge
    private var rowH = 22 * dp
    private var needsInitialView = true
    private val minPxPerSec = 6.0 * dp
    private val maxPxPerSec = 4000.0 * dp
    private val minRowH = 5 * dp
    private val maxRowH = 70 * dp

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint().apply { strokeWidth = 1f }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val rect = RectF()

    companion object {
        private const val C_RIGHT = 0xFFFF9A3C.toInt()
        private const val C_LEFT = 0xFF6F8BFF.toInt()
    }

    // ------------------------------------------------------------- content

    fun setSong(song: Song) {
        notes.clear()
        notes.addAll(song.notes)
        beatSeconds = song.beatSeconds.takeIf { it in 0.05..10.0 } ?: 0.5
        defaultLength = beatSeconds
        gridBeats = 1.0
        undoStack.clear(); redoStack.clear()
        select(null)
        needsInitialView = true
        invalidate()
    }

    fun toSong(title: String) = Song(title, ArrayList(notes), beatSeconds)

    val noteCount: Int get() = notes.size

    private fun pushUndo() {
        undoStack.add(ArrayList(notes))
        if (undoStack.size > 200) undoStack.removeAt(0)
        redoStack.clear()
    }

    private fun edited() {
        listener?.onEdited()
        invalidate()
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.add(ArrayList(notes))
        notes.clear(); notes.addAll(undoStack.removeAt(undoStack.size - 1))
        select(null); edited()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.add(ArrayList(notes))
        notes.clear(); notes.addAll(redoStack.removeAt(redoStack.size - 1))
        select(null); edited()
    }

    fun select(n: Note?) {
        selected = n
        listener?.onSelectionChanged(n)
        invalidate()
    }

    private fun replaceSelected(n: Note) {
        val i = notes.indexOfFirst { it === selected }
        if (i < 0) return
        notes[i] = n
        selected = n
        listener?.onSelectionChanged(n)
        invalidate()
    }

    fun deleteSelected() {
        val s = selected ?: return
        pushUndo()
        notes.removeAll { it === s }
        select(null); edited()
    }

    /** Lengthens (+1) or shortens (-1) the selected note by one grid step (or 50 ms without a grid). */
    fun nudgeSelectedLength(direction: Int) {
        val s = selected ?: return
        val step = if (gridSeconds > 0) gridSeconds else 0.05
        val dur = max(step, s.duration + direction * step)
        if (abs(dur - s.duration) < 1e-9) return
        pushUndo()
        replaceSelected(s.copy(duration = dur))
        defaultLength = dur
        edited()
    }

    fun toggleSelectedHand() {
        val s = selected ?: return
        pushUndo()
        replaceSelected(s.copy(hand = if (s.hand == Hand.RIGHT) Hand.LEFT else Hand.RIGHT))
        edited()
    }

    // ------------------------------------------------------------ viewport

    private fun xOf(t: Double): Float = (keysW + (t - t0) * pxPerSec).toFloat()
    private fun tOf(x: Float): Double = t0 + (x - keysW) / pxPerSec
    private fun yOf(midi: Int): Float = rulerH + (topMidi - midi) * rowH
    private fun midiAt(y: Float): Int = ceil(topMidi - (y - rulerH) / rowH).toInt()
    private val visibleRows: Float get() = (height - rulerH) / rowH
    private val songEnd: Double get() = notes.maxOfOrNull { it.start + it.duration } ?: 0.0

    private fun clampView() {
        pxPerSec = pxPerSec.coerceIn(minPxPerSec, maxPxPerSec)
        rowH = rowH.coerceIn(minRowH, maxRowH)
        val rows = visibleRows
        topMidi = if (rows >= 89) 109f else topMidi.coerceIn(21 + rows - 1, 109f)
        val visibleSec = (width - keysW) / pxPerSec
        val before = min(visibleSec * 0.1, 2 * beatSeconds)   // a little room before 0:00
        t0 = t0.coerceIn(-before, max(max(songEnd, 0.0) + visibleSec * 0.5 - visibleSec, -before))
    }

    /** Opening view: the start of the song at a comfortable size, pitch range centred. */
    private fun initialView() {
        val availW = width - keysW
        pxPerSec = max(availW / (16 * beatSeconds), 40.0 * dp)
        t0 = (notes.minOfOrNull { it.start } ?: 0.0) - beatSeconds * 0.5
        val lo = notes.minOfOrNull { it.midi } ?: 55
        val hi = notes.maxOfOrNull { it.midi } ?: 77
        rowH = ((height - rulerH) / (hi - lo + 4f)).coerceIn(14 * dp, 30 * dp)
        // whole range fits: centre it; otherwise show the top (where the melody usually is)
        topMidi = if (hi - lo + 3 <= visibleRows) (lo + hi) / 2f + visibleRows / 2f else hi + 1.5f
        clampView()
    }

    /** Shows the whole song. */
    fun fitAll() {
        if (width == 0) { needsInitialView = true; return }
        val start = notes.minOfOrNull { it.start } ?: 0.0
        val end = max(songEnd, start + 4 * beatSeconds)
        pxPerSec = (width - keysW - 16 * dp) / (end - start)
        t0 = start - 8 * dp / pxPerSec
        val lo = notes.minOfOrNull { it.midi } ?: 55
        val hi = notes.maxOfOrNull { it.midi } ?: 77
        rowH = (height - rulerH) / (hi - lo + 3f)
        topMidi = hi + 1.5f
        clampView()
        invalidate()
    }

    fun zoomBy(factor: Float) = zoomAt(keysW + (width - keysW) / 2f, rulerH + (height - rulerH) / 2f, factor, factor)

    private fun zoomAt(fx: Float, fy: Float, sx: Float, sy: Float) {
        val tFocus = tOf(fx)
        pxPerSec = (pxPerSec * sx).coerceIn(minPxPerSec, maxPxPerSec)
        t0 = tFocus - (fx - keysW) / pxPerSec
        val kFocus = topMidi - (fy - rulerH) / rowH
        rowH = (rowH * sy).coerceIn(minRowH, maxRowH)
        topMidi = kFocus + (fy - rulerH) / rowH
        clampView()
        invalidate()
    }

    // --------------------------------------------------------------- snap

    private fun snapFloor(t: Double): Double {
        val g = gridSeconds
        return if (g <= 0) (t * 100).roundToInt() / 100.0 else floor(t / g + 1e-6) * g
    }

    private fun snapRound(t: Double): Double {
        val g = gridSeconds
        return if (g <= 0) (t * 100).roundToInt() / 100.0 else Math.round(t / g) * g
    }

    // ---------------------------------------------------------------- draw

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (needsInitialView || oldw == 0) return
        clampView()
    }

    override fun onDraw(c: Canvas) {
        if (width == 0) return
        if (needsInitialView) { initialView(); needsInitialView = false }
        val w = width.toFloat()
        val h = height.toFloat()
        val lowM = max(21, midiAt(h))
        val highM = min(108, midiAt(rulerH + 1))

        c.drawColor(0xFF101116.toInt())
        c.save()
        c.clipRect(keysW, rulerH, w, h)

        // pitch rows
        for (m in lowM..highM) {
            val y = yOf(m)
            fill.color = if (NoteNames.isBlack(m)) 0xFF15161C.toInt() else 0xFF1D1F27.toInt()
            c.drawRect(keysW, y, w, y + rowH, fill)
            if (m % 12 == 0) {
                line.color = 0xFF454A5A.toInt(); c.drawLine(keysW, y + rowH, w, y + rowH, line)
            } else if (rowH > 10 * dp) {
                line.color = 0xFF23262F.toInt(); c.drawLine(keysW, y + rowH, w, y + rowH, line)
            }
        }

        // time grid: beats, grid subdivisions, and every 4th beat stronger
        val tStart = tOf(keysW)
        val tEnd = tOf(w)
        val g = gridSeconds
        if (g > 0 && g * pxPerSec >= 6 * dp && g < beatSeconds - 1e-9) {
            line.color = 0xFF1A1C23.toInt()
            var k = floor(tStart / g).toLong()
            while (k * g <= tEnd) { val x = xOf(k * g); c.drawLine(x, rulerH, x, h, line); k++ }
        }
        if (beatSeconds * pxPerSec >= 5 * dp) {
            var k = floor(tStart / beatSeconds).toLong()
            while (k * beatSeconds <= tEnd) {
                val x = xOf(k * beatSeconds)
                line.color = if (k % 4 == 0L) 0xFF4A4F60.toInt() else 0xFF2C2F3A.toInt()
                c.drawLine(x, rulerH, x, h, line)
                k++
            }
        }

        // notes
        val r = min(5 * dp, rowH * 0.3f)
        for (n in notes) {
            val x0 = xOf(n.start)
            val x1 = max(xOf(n.start + n.duration), x0 + 3 * dp)
            if (x1 < keysW || x0 > w) continue
            val y = yOf(n.midi)
            if (y > h || y + rowH < rulerH) continue
            val isSel = n === selected
            rect.set(x0, y + 1f, x1, y + rowH - 1f)
            fill.color = if (n.hand == Hand.RIGHT) C_RIGHT else C_LEFT
            if (mode == Mode.ERASE) fill.alpha = 200
            c.drawRoundRect(rect, r, r, fill)
            fill.alpha = 255
            stroke.strokeWidth = if (isSel) 2.5f * dp else 1f
            stroke.color = if (isSel) 0xFFFFFFFF.toInt() else 0x66000000
            c.drawRoundRect(rect, r, r, stroke)
            if (isSel) {
                // resize handle on the right edge
                val hw = 4 * dp
                rect.set(x1 - hw - 2 * dp, y + rowH * 0.2f, x1 - 2 * dp, y + rowH * 0.8f)
                fill.color = 0xFFFFFFFF.toInt()
                c.drawRoundRect(rect, hw / 2, hw / 2, fill)
            }
            if (playing && playhead >= n.start && playhead < n.start + n.duration) {
                rect.set(x0, y + 1f, x1, y + rowH - 1f)
                fill.color = 0x88FFFFFF.toInt()
                c.drawRoundRect(rect, r, r, fill)
            }
            if (x1 - x0 > 30 * dp && rowH >= 13 * dp) {
                text.textSize = min(rowH * 0.55f, 12 * sp)
                text.textAlign = Paint.Align.LEFT
                text.color = 0xE6101116.toInt()
                c.drawText(NoteNames.nameWithOctave(n.midi), x0 + 5 * dp, y + rowH / 2 - (text.descent() + text.ascent()) / 2, text)
            }
        }
        // playhead
        val px = xOf(playhead)
        if (px >= keysW && px <= w) {
            fill.color = 0xFF39D98A.toInt()
            c.drawRect(px - 1f * dp, rulerH, px + 1f * dp, h, fill)
        }

        // length readout while dragging the end handle
        val sel = selected
        if (drag == Drag.RESIZE && dragMoved && sel != null) {
            val beats = sel.duration / beatSeconds
            val label = "%.2f s  ·  %s beat".format(sel.duration, formatBeats(beats))
            text.textSize = 13 * sp
            text.textAlign = Paint.Align.LEFT
            val tw = text.measureText(label)
            val lx = min(xOf(sel.start + sel.duration) + 10 * dp, w - tw - 16 * dp)
            val ly = max(yOf(sel.midi) - 10 * dp, rulerH + 24 * dp)
            rect.set(lx - 8 * dp, ly - 18 * dp, lx + tw + 8 * dp, ly + 7 * dp)
            fill.color = if (resizeSnapped) 0xF039D98A.toInt() else 0xF02A2D38.toInt()
            c.drawRoundRect(rect, 6 * dp, 6 * dp, fill)
            text.color = if (resizeSnapped) 0xFF0E0F13.toInt() else 0xFFFFFFFF.toInt()
            c.drawText(label, lx, ly, text)
        }
        c.restore()

        // key strip
        c.save()
        c.clipRect(0f, rulerH, keysW, h)
        for (m in lowM..highM) {
            val y = yOf(m)
            val black = NoteNames.isBlack(m)
            fill.color = if (black) 0xFF2A2B31.toInt() else 0xFFE6E7EB.toInt()
            c.drawRect(0f, y, keysW, y + rowH, fill)
            if (black) {
                fill.color = 0xFFE6E7EB.toInt()
                c.drawRect(keysW * 0.62f, y, keysW, y + rowH, fill)
            }
            line.color = 0xFF9A9CA6.toInt()
            c.drawLine(0f, y + rowH, keysW, y + rowH, line)
            val showName = (m % 12 == 0 && rowH >= 7 * dp) || (!black && rowH >= 16 * dp)
            if (showName) {
                text.textAlign = Paint.Align.RIGHT
                text.textSize = min(rowH * 0.6f, 11 * sp)
                text.color = if (m % 12 == 0) 0xFF15161C.toInt() else 0xFF7A7D88.toInt()
                c.drawText(NoteNames.nameWithOctave(m), keysW - 5 * dp, y + rowH / 2 - (text.descent() + text.ascent()) / 2, text)
            }
        }
        c.restore()
        line.color = 0xFF454A5A.toInt()
        c.drawLine(keysW, rulerH, keysW, h, line)

        // time ruler
        fill.color = 0xFF16171D.toInt()
        c.drawRect(0f, 0f, w, rulerH, fill)
        val steps = doubleArrayOf(0.05, 0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0)
        val step = steps.firstOrNull { it * pxPerSec >= 64 * dp } ?: 120.0
        text.textAlign = Paint.Align.LEFT
        text.textSize = 11 * sp
        text.color = 0xFFA0A4B0.toInt()
        line.color = 0xFF5A5E6C.toInt()
        var k = floor(tStart / step).toLong()
        while (k * step <= tEnd) {
            val t = k * step
            val x = xOf(t)
            if (x >= keysW) {
                c.drawLine(x, rulerH * 0.55f, x, rulerH, line)
                c.drawText(formatTime(t), x + 3 * dp, rulerH * 0.62f, text)
            }
            k++
        }
        // playhead marker on the ruler (tap the ruler to move it)
        if (px >= keysW && px <= w) {
            fill.color = 0xFF39D98A.toInt()
            val tri = android.graphics.Path()
            tri.moveTo(px - 6 * dp, rulerH * 0.35f); tri.lineTo(px + 6 * dp, rulerH * 0.35f); tri.lineTo(px, rulerH)
            tri.close()
            c.drawPath(tri, fill)
        }
        fill.color = 0xFF16171D.toInt()
        c.drawRect(0f, 0f, keysW, rulerH, fill)

        if (notes.isEmpty()) {
            text.textAlign = Paint.Align.CENTER
            text.textSize = 15 * sp
            text.color = 0xFF8C8F9A.toInt()
            c.drawText("Tap to add notes  ·  pinch to zoom  ·  drag to scroll", keysW + (w - keysW) / 2, h / 2, text)
        }
    }

    /** 1.5 -> "1½", 0.25 -> "¼", 2.333 -> "2.33" */
    private fun formatBeats(b: Double): String {
        val whole = floor(b + 1e-6).toInt()
        val frac = b - whole
        val sym = when {
            abs(frac) < 0.01 -> ""
            abs(frac - 0.25) < 0.01 -> "¼"
            abs(frac - 0.5) < 0.01 -> "½"
            abs(frac - 0.75) < 0.01 -> "¾"
            else -> return "%.2f".format(b)
        }
        return if (whole == 0 && sym.isNotEmpty()) sym else "$whole$sym"
    }

    private fun formatTime(t: Double): String {
        if (t < 0) return ""
        val m = (t / 60).toInt()
        val s = t - m * 60
        return if (s == floor(s)) "%d:%02d".format(m, s.toInt()) else "%d:%04.1f".format(m, s)
    }

    // --------------------------------------------------------------- touch

    private enum class Drag { NONE, MOVE, RESIZE }

    private var drag = Drag.NONE
    private var resizeSnapped = false
    private var dragOriginal: Note? = null
    private var downX = 0f
    private var downY = 0f
    private var dragMoved = false
    private var ignoreUntilUp = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val scroller = OverScroller(context)
    private var flingLastX = 0
    private var flingLastY = 0

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val minSpan = 40 * dp
            val sx = if (d.previousSpanX > minSpan && d.currentSpanX > minSpan) d.currentSpanX / d.previousSpanX else 1f
            val sy = if (d.previousSpanY > minSpan && d.currentSpanY > minSpan) d.currentSpanY / d.previousSpanY else 1f
            zoomAt(d.focusX, d.focusY, sx, sy)
            return true
        }
    }).apply { isQuickScaleEnabled = false }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            t0 += dx / pxPerSec
            topMidi -= dy / rowH
            clampView()
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaleDetector.isInProgress) return false
            flingLastX = 0; flingLastY = 0
            scroller.fling(0, 0, -vx.toInt(), -vy.toInt(), -1_000_000, 1_000_000, -1_000_000, 1_000_000)
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            handleTap(e.x, e.y)
            return true
        }
    })

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val dx = scroller.currX - flingLastX
            val dy = scroller.currY - flingLastY
            flingLastX = scroller.currX; flingLastY = scroller.currY
            t0 += dx / pxPerSec
            topMidi -= dy / rowH
            clampView()
            postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            ignoreUntilUp = false
            drag = Drag.NONE
            val sel = selected
            if (mode == Mode.ADD && sel != null && ev.x > keysW && ev.y > rulerH) {
                val hit = hitSelected(sel, ev.x, ev.y)
                if (hit != Drag.NONE) {
                    scroller.forceFinished(true)
                    drag = hit; dragOriginal = sel
                    downX = ev.x; downY = ev.y; dragMoved = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        if (ignoreUntilUp) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) ignoreUntilUp = false
            return true
        }
        if (drag != Drag.NONE) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // a second finger means the user wants to zoom/scroll, not edit
                    if (dragMoved) listener?.onEdited()
                    drag = Drag.NONE
                    ignoreUntilUp = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragMoved && hypot(ev.x - downX, ev.y - downY) > touchSlop) {
                        dragMoved = true
                        pushUndo()
                    }
                    if (dragMoved) applyDrag(ev.x, ev.y)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    drag = Drag.NONE
                    if (dragMoved) edited() else invalidate()
                }
            }
            return true
        }
        scaleDetector.onTouchEvent(ev)
        gestures.onTouchEvent(ev)
        return true
    }

    /** Where on the selected note did the finger land: right edge = resize, body = move. */
    private fun hitSelected(n: Note, x: Float, y: Float): Drag {
        val x0 = xOf(n.start)
        val x1 = max(xOf(n.start + n.duration), x0 + 3 * dp)
        val yc = yOf(n.midi) + rowH / 2
        val halfH = max(rowH, 30 * dp) / 2
        if (abs(y - yc) > halfH) return Drag.NONE
        val edgeZone = max(14 * dp, min(26 * dp, (x1 - x0) * 0.35f))
        return when {
            x in (x1 - edgeZone)..(x1 + 16 * dp) -> Drag.RESIZE
            x in (x0 - 6 * dp)..x1 -> Drag.MOVE
            else -> Drag.NONE
        }
    }

    private fun applyDrag(x: Float, y: Float) {
        val orig = dragOriginal ?: return
        when (drag) {
            Drag.RESIZE -> {
                // Fine steps (1/24 beat: covers 8ths, 16ths and triplets), but "magnetic" grid
                // lines: within ~10 dp of one the end clicks onto it.
                val fine = max(0.005, beatSeconds / 24)
                val raw = tOf(x)
                val g = gridSeconds
                var end = Math.round(raw / fine) * fine
                resizeSnapped = false
                if (g > 0) {
                    val nearest = Math.round(raw / g) * g
                    if (abs(xOf(nearest) - x) <= 10 * dp) { end = nearest; resizeSnapped = true }
                }
                val dur = max(max(fine, 0.03), end - orig.start)
                replaceSelected(orig.copy(duration = dur))
                defaultLength = dur
            }
            Drag.MOVE -> {
                val dt = (x - downX) / pxPerSec
                val dm = ((downY - y) / rowH).roundToInt()
                val before = selected?.midi
                replaceSelected(orig.copy(
                    start = max(0.0, snapRound(orig.start + dt)),
                    midi = (orig.midi + dm).coerceIn(21, 108),
                ))
                val after = selected?.midi
                if (after != null && after != before) listener?.onPreviewNote(after)
            }
            Drag.NONE -> {}
        }
    }

    private fun noteAt(x: Float, y: Float): Note? {
        val m = midiAt(y)
        val tol = 6 * dp
        var best: Note? = null
        var bestDist = Float.MAX_VALUE
        for (i in notes.indices.reversed()) {
            val n = notes[i]
            if (n.midi != m) continue
            val x0 = xOf(n.start)
            val x1 = max(xOf(n.start + n.duration), x0 + 3 * dp)
            val d = when { x < x0 -> x0 - x; x > x1 -> x - x1; else -> 0f }
            if (d <= tol && d < bestDist) { best = n; bestDist = d; if (d == 0f) break }
        }
        return best
    }

    private fun handleTap(x: Float, y: Float) {
        if (x < keysW) return
        if (y < rulerH) {
            // tap the time ruler to put the playhead there
            playhead = max(0.0, tOf(x))
            listener?.onPlayheadMoved(playhead)
            return
        }
        val hit = noteAt(x, y)
        when (mode) {
            Mode.ADD -> {
                if (hit != null) { select(hit); listener?.onPreviewNote(hit.midi); return }
                val m = midiAt(y)
                if (m !in 21..108) return
                pushUndo()
                val n = Note(m, max(0.0, snapFloor(tOf(x))), defaultLength, addHand)
                notes.add(n)
                select(n)
                listener?.onPreviewNote(m)
                edited()
            }
            Mode.ERASE -> {
                if (hit == null) return
                pushUndo()
                notes.removeAll { it === hit }
                if (selected === hit) select(null)
                edited()
            }
        }
    }
}
