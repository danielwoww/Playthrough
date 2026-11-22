package com.playthrough.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Simple MIDI editor for practice versions: add/remove notes, change length, move notes,
 * set hands. Always saves as a song in the user's library (built-in songs are never changed).
 */
class EditorActivity : Activity(), PianoRollView.Listener {

    companion object {
        const val EXTRA_PATH = "path"            // MIDI file to edit (absent = new empty song)
        const val EXTRA_TITLE = "title"
        const val EXTRA_USER_SONG = "userSong"   // true when editing a song from the user's library
        const val RESULT_TITLE = "savedTitle"

        fun intent(context: Context, song: Song?, userSong: Boolean): Intent {
            val i = Intent(context, EditorActivity::class.java)
            if (song != null) {
                val f = File(context.cacheDir, "editor_input.mid")
                f.writeBytes(MidiFileWriter.write(song))
                i.putExtra(EXTRA_PATH, f.absolutePath)
                i.putExtra(EXTRA_TITLE, song.title)
            } else {
                i.putExtra(EXTRA_TITLE, "New song")
            }
            i.putExtra(EXTRA_USER_SONG, userSong)
            return i
        }

        private val GRIDS = doubleArrayOf(0.25, 0.5, 1.0, 2.0, 0.0)
        private val GRID_NAMES = arrayOf("Grid ¼", "Grid ½", "Grid 1", "Grid 2", "No grid")
    }

    private lateinit var roll: PianoRollView
    private lateinit var titleText: TextView
    private lateinit var undoBtn: TextView
    private lateinit var redoBtn: TextView
    private lateinit var modeBtn: TextView
    private lateinit var handBtn: TextView
    private lateinit var gridBtn: TextView
    private lateinit var selectionBar: View
    private lateinit var selInfo: TextView

    private var title = "New song"
    private var userSong = false
    private var dirty = false
    private var savedTitle: String? = null
    private var gridIndex = 2

    // ---- playback ----
    private val synth = PianoSynth()
    private lateinit var playBtn: TextView
    private lateinit var instrumentBtn: TextView
    private var isPlaying = false
    private var playStart = 0.0          // where playback started (playhead returns here)
    private var playStartNs = 0L
    private var playNotes: List<Note> = emptyList()
    private var playIndex = 0
    private var playEnd = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        findViewById<View>(R.id.editorRoot).setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 28) {
                val c = insets.displayCutout
                v.setPadding(c?.safeInsetLeft ?: 0, 0, c?.safeInsetRight ?: 0, 0)
            }
            insets
        }

        roll = findViewById(R.id.roll)
        titleText = findViewById(R.id.titleText)
        undoBtn = findViewById(R.id.undoBtn)
        redoBtn = findViewById(R.id.redoBtn)
        modeBtn = findViewById(R.id.modeBtn)
        handBtn = findViewById(R.id.handBtn)
        gridBtn = findViewById(R.id.gridBtn)
        selectionBar = findViewById(R.id.selectionBar)
        selInfo = findViewById(R.id.selInfo)

        title = intent.getStringExtra(EXTRA_TITLE) ?: "New song"
        userSong = intent.getBooleanExtra(EXTRA_USER_SONG, false)
        val song = intent.getStringExtra(EXTRA_PATH)?.let { path ->
            runCatching { MidiFileParser.parse(File(path).readBytes(), title) }.getOrNull()
        } ?: Song(title, emptyList(), 0.5)
        roll.setSong(song)
        roll.listener = this
        titleText.text = "✎ $title"

        findViewById<View>(R.id.backBtn).setOnClickListener { attemptExit() }
        undoBtn.setOnClickListener { roll.undo() }
        redoBtn.setOnClickListener { roll.redo() }
        modeBtn.setOnClickListener {
            roll.mode = if (roll.mode == PianoRollView.Mode.ADD) PianoRollView.Mode.ERASE else PianoRollView.Mode.ADD
            if (roll.mode == PianoRollView.Mode.ERASE) roll.select(null)
            roll.invalidate()
            updateToolbar()
            toast(if (roll.mode == PianoRollView.Mode.ERASE) "Erase: tap notes to remove them"
                  else "Add: tap empty space to add, tap a note to select it")
        }
        handBtn.setOnClickListener {
            roll.addHand = if (roll.addHand == Hand.RIGHT) Hand.LEFT else Hand.RIGHT
            updateToolbar()
        }
        gridBtn.setOnClickListener {
            gridIndex = (gridIndex + 1) % GRIDS.size
            roll.gridBeats = GRIDS[gridIndex]
            updateToolbar()
        }
        findViewById<View>(R.id.zoomInBtn).setOnClickListener { roll.zoomBy(1.5f) }
        findViewById<View>(R.id.zoomOutBtn).setOnClickListener { roll.zoomBy(1 / 1.5f) }
        findViewById<View>(R.id.fitBtn).setOnClickListener { roll.fitAll() }
        findViewById<View>(R.id.saveBtn).setOnClickListener { showSaveDialog(thenExit = false) }

        playBtn = findViewById(R.id.playBtn)
        instrumentBtn = findViewById(R.id.instrumentBtn)
        synth.instrument = Instrument.load(this)
        playBtn.setOnClickListener { if (isPlaying) stopPlayback() else startPlayback() }
        instrumentBtn.setOnClickListener {
            val next = Instrument.next(synth.instrument)
            synth.instrument = next
            Instrument.save(this, next)
            updateToolbar()
            if (!isPlaying) synth.demo()
        }

        findViewById<View>(R.id.shorterBtn).setOnClickListener { roll.nudgeSelectedLength(-1) }
        findViewById<View>(R.id.longerBtn).setOnClickListener { roll.nudgeSelectedLength(+1) }
        findViewById<View>(R.id.selHandBtn).setOnClickListener { roll.toggleSelectedHand() }
        findViewById<View>(R.id.deleteBtn).setOnClickListener { roll.deleteSelected() }
        findViewById<View>(R.id.deselectBtn).setOnClickListener { roll.select(null) }

        // Android 13+ back gesture (onBackPressed is not called on Android 16 for this target)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { attemptExit() }
        }
        updateToolbar()
        if (savedInstanceState == null && song.notes.isEmpty()) {
            toast("Tap to add notes. Pinch to zoom, drag to scroll")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = attemptExit()

    override fun onResume() {
        super.onResume()
        synth.instrument = Instrument.load(this)
        synth.start()
        hideSystemUi()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
        }
    }

    // ---------------------------------------------------------------- UI

    override fun onPause() {
        super.onPause()
        stopPlayback()
        synth.stop()
    }

    // ------------------------------------------------------------ playback

    /** Plays the song from the playhead (green line; tap the time ruler to move it). */
    private fun startPlayback() {
        val song = roll.toSong(title)
        if (song.notes.isEmpty()) { toast("Add some notes first"); return }
        if (!synth.start()) { toast("Couldn't start audio output"); return }
        if (roll.playhead >= song.length) roll.playhead = 0.0
        playStart = roll.playhead
        playNotes = song.notes
        playEnd = song.length
        // notes already sounding at the start point play for their remaining time
        for (n in playNotes) {
            if (n.start < playStart - 1e-3 && n.start + n.duration > playStart + 0.05) {
                synth.play(n.midi, n.start + n.duration - playStart, velocity(n))
            }
        }
        playIndex = playNotes.indexOfFirst { it.start >= playStart - 1e-3 }.let { if (it < 0) playNotes.size else it }
        playStartNs = System.nanoTime()
        isPlaying = true
        roll.playing = true
        updateToolbar()
        roll.postOnAnimation(playTick)
    }

    private val playTick = object : Runnable {
        override fun run() {
            if (!isPlaying) return
            val t = playStart + (System.nanoTime() - playStartNs) / 1e9
            while (playIndex < playNotes.size && playNotes[playIndex].start <= t) {
                val n = playNotes[playIndex]
                synth.play(n.midi, n.duration, velocity(n))
                playIndex++
            }
            roll.playhead = t
            roll.followPlayhead()
            if (t > playEnd + 0.4) { stopPlayback(); return }
            roll.postOnAnimation(this)
        }
    }

    private fun velocity(n: Note) = if (n.hand == Hand.RIGHT) 0.85f else 0.7f

    private fun stopPlayback() {
        if (!isPlaying) return
        isPlaying = false
        roll.removeCallbacks(playTick)
        synth.allNotesOff()
        roll.playing = false
        roll.playhead = playStart          // back to where you started, ready to listen again
        updateToolbar()
    }

    override fun onPreviewNote(midi: Int) {
        if (!isPlaying) synth.play(midi, 0.35, 0.75f)
    }

    override fun onPlayheadMoved(seconds: Double) {
        if (isPlaying) { stopPlayback(); roll.playhead = seconds; startPlayback() }
    }

    private fun updateToolbar() {
        playBtn.text = if (isPlaying) "■ Stop" else "▶ Play"
        playBtn.setBackgroundResource(if (isPlaying) R.drawable.chip_bg_active else R.drawable.chip_bg)
        instrumentBtn.text = "🎹 " + synth.instrument.name
        undoBtn.alpha = if (roll.canUndo) 1f else 0.4f
        redoBtn.alpha = if (roll.canRedo) 1f else 0.4f
        val erase = roll.mode == PianoRollView.Mode.ERASE
        modeBtn.text = if (erase) "✕ Erase" else "+ Add"
        modeBtn.setBackgroundResource(if (erase) R.drawable.chip_bg_active else R.drawable.chip_bg)
        handBtn.text = if (roll.addHand == Hand.RIGHT) "Right" else "Left"
        handBtn.setTextColor(if (roll.addHand == Hand.RIGHT) 0xFFFF9A3C.toInt() else 0xFF8FA4FF.toInt())
        gridBtn.text = GRID_NAMES[gridIndex]
        titleText.text = (if (dirty) "✎ $title •" else "✎ $title")
    }

    override fun onSelectionChanged(note: Note?) {
        if (note == null) {
            selectionBar.visibility = View.GONE
            return
        }
        selectionBar.visibility = View.VISIBLE
        val hand = if (note.hand == Hand.RIGHT) "right" else "left"
        val beats = note.duration / roll.beatSeconds
        selInfo.text = "${NoteNames.nameWithOctave(note.midi)}  ·  ${"%.2f".format(note.duration)} s " +
            "(${"%.2f".format(beats).trimEnd('0').trimEnd('.')} beat)  ·  $hand"
    }

    override fun onEdited() {
        dirty = true
        updateToolbar()
    }

    // -------------------------------------------------------------- saving

    private fun showSaveDialog(thenExit: Boolean) {
        val input = EditText(this).apply {
            setText(if (userSong || savedTitle != null) (savedTitle ?: title) else "$title (practice)")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSelectAllOnFocus(true)
            setTextColor(0xFFFFFFFF.toInt())
        }
        val box = FrameLayout(this).apply {
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(input)
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Save as")
            .setMessage("Saved songs appear in your song list. Built-in songs are never changed.")
            .setView(box)
            .setPositiveButton(if (thenExit) "Save" else "Save & practice") { _, _ ->
                save(input.text.toString(), exitAfter = true)
            }
            .apply { if (!thenExit) setNeutralButton("Save") { _, _ -> save(input.text.toString(), exitAfter = false) } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun save(name: String, exitAfter: Boolean) {
        val clean = name.trim().ifEmpty { "Untitled" }
        val sameAsCurrent = SongStore.sanitize(clean) == SongStore.sanitize(savedTitle ?: if (userSong) title else "\u0000")
        if (!sameAsCurrent && SongStore.exists(this, clean)) {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Replace “${SongStore.sanitize(clean)}”?")
                .setMessage("A saved song with this name already exists.")
                .setPositiveButton("Replace") { _, _ -> doSave(clean, exitAfter) }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            doSave(clean, exitAfter)
        }
    }

    private fun doSave(name: String, exitAfter: Boolean) {
        try {
            val t = SongStore.save(this, roll.toSong(name), name)
            savedTitle = t
            title = t
            userSong = true
            dirty = false
            setResult(RESULT_OK, Intent().putExtra(RESULT_TITLE, t))
            updateToolbar()
            toast("Saved “$t” (${roll.noteCount} notes)")
            if (exitAfter) finish()
        } catch (e: Exception) {
            toast("Couldn't save: ${e.message}")
        }
    }

    private fun attemptExit() {
        if (!dirty) { finish(); return }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Unsaved changes")
            .setMessage("Save your changes before leaving the editor?")
            .setPositiveButton("Save…") { _, _ -> showSaveDialog(thenExit = true) }
            .setNegativeButton("Discard") { _, _ -> finish() }
            .setNeutralButton("Keep editing", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
