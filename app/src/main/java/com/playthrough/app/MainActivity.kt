package com.playthrough.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity(),
    PianoView.Callbacks, MidiInput.Listener, MicNoteDetector.Listener {

    companion object {
        private const val REQ_MIC = 1
        private const val REQ_MIDI_FILE = 2
        private const val REQ_EDIT = 3
        private const val SOUND_OFF = 0
        private const val SOUND_SONG = 1
        private const val SOUND_OTHER_HAND = 2
        private const val STATUS_VISIBLE_MS = 4000L
    }

    private lateinit var piano: PianoView
    private lateinit var songBtn: TextView
    private lateinit var playBtn: TextView
    private lateinit var waitBtn: TextView
    private lateinit var handsBtn: TextView
    private lateinit var lockBtn: TextView
    private lateinit var micBtn: TextView
    private lateinit var loopBtn: TextView
    private lateinit var status: TextView
    private lateinit var display: DisplaySettings
    private lateinit var topBar: LinearLayout
    private var settingsDialog: AlertDialog? = null
    /** Which layout the top bar currently has (null = not arranged yet). */
    private var topBarPortrait: Boolean? = null
    private var scrubBase = 0.0
    private var scrubWasPlaying = false
    private lateinit var seek: SeekBar

    private val engine = PracticeEngine()
    private lateinit var midiInput: MidiInput
    private var mic: MicNoteDetector? = null
    /** 0 = off, 1 = single note, 2 = chords (experimental). */
    private var micMode = 0
    private val synth = PianoSynth()
    /** What the phone plays: nothing, every song note, or only the hand you're not practising. */
    private var soundMode = SOUND_OFF
    private lateinit var soundBtn: TextView
    private var micLevel = 0f
    private var micNotes: List<Int> = emptyList()
    private var midiNames: List<String> = emptyList()

    private val songs = ArrayList<Song>()
    private var builtInCount = 0
    private var handsMode = 0 // 0 both, 1 right, 2 left
    private var userSeeking = false
    private var frame = 0
    // The status line fades out after a few seconds so it doesn't cover the falling notes.
    // It comes back only when something worth reading changes (not the score or pressed keys).
    private var statusKey = ""
    private var statusShownAt = 0L
    private var statusVisible = true

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        display = DisplaySettings.load(this)
        applyOrientation()                         // before the first layout, to avoid a rotation flash
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        piano = findViewById(R.id.piano)
        songBtn = findViewById(R.id.songBtn)
        playBtn = findViewById(R.id.playBtn)
        waitBtn = findViewById(R.id.waitBtn)
        handsBtn = findViewById(R.id.handsBtn)
        lockBtn = findViewById(R.id.lockBtn)
        micBtn = findViewById(R.id.micBtn)
        loopBtn = findViewById(R.id.loopBtn)
        status = findViewById(R.id.status)
        seek = findViewById(R.id.seek)
        topBar = findViewById(R.id.topBar)
        soundBtn = findViewById(R.id.soundBtn)

        // keep content clear of camera cutouts (at the sides in landscape, at the top in portrait)
        val root = findViewById<View>(R.id.root)
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 28) {
                val c = insets.displayCutout
                v.setPadding(c?.safeInsetLeft ?: 0, c?.safeInsetTop ?: 0,
                    c?.safeInsetRight ?: 0, c?.safeInsetBottom ?: 0)
            }
            insets
        }

        piano.engine = engine
        piano.callbacks = this
        piano.applySettings(display)
        engine.speed = display.speedPercent / 100.0
        arrangeTopBar()

        songBtn.setOnClickListener { showSongPicker() }
        playBtn.setOnClickListener { togglePlay() }
        findViewById<View>(R.id.restartBtn).setOnClickListener {
            if (engine.looping) engine.restartLoop() else engine.restart()
            engine.playing = true
            updateButtons()
        }
        loopBtn.setOnClickListener { onLoopButton() }
        loopBtn.setOnLongClickListener {
            engine.clearLoop(); updateButtons(); toast("Loop cleared"); true
        }
        findViewById<View>(R.id.settingsBtn).setOnClickListener {
            settingsDialog = SettingsDialog.show(this, display, onInstrumentChanged = { inst -> previewInstrument(inst) }) { s ->
                val rotate = s.portrait != display.portrait
                display = s
                s.save(this)
                piano.applySettings(s)
                engine.speed = s.speedPercent / 100.0
                if (rotate) applyOrientation()
            }
        }
        waitBtn.setOnClickListener {
            engine.waitMode = !engine.waitMode
            updateButtons()
            toast(if (engine.waitMode) "Wait mode: the song waits for you to press each key"
                  else "Play-along mode: the song keeps going")
        }
        handsBtn.setOnClickListener {
            handsMode = (handsMode + 1) % 3
            applyHands()
        }
        lockBtn.setOnClickListener {
            display = display.copy(viewLocked = !display.viewLocked)
            display.save(this)
            piano.applySettings(display)
            updateButtons()
            toast(if (display.viewLocked) "View locked"
                  else "View unlocked: pinch with two fingers to zoom the keys or the fall speed, " +
                       "drag with two fingers to move along the keyboard, drag the handle above the keys to resize the piano",
                  long = !display.viewLocked)
        }
        micBtn.setOnClickListener { toggleMic() }
        soundMode = getSharedPreferences("practice", MODE_PRIVATE).getInt("soundMode", SOUND_OFF)
        soundBtn.setOnClickListener { cycleSound() }
        soundBtn.setOnLongClickListener { showInstrumentPicker(); true }
        engine.onSongNote = { n, practiced ->
            val play = soundMode == SOUND_SONG || (soundMode == SOUND_OTHER_HAND && !practiced)
            if (play) synth.play(n.midi, n.duration / engine.speed, if (practiced) 0.85f else 0.65f)
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) engine.song?.let { engine.seek(progress / 1000.0 * it.length - 0.5) }
            }
            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) { userSeeking = false }
        })

        songs.addAll(SongLibrary.builtIn())
        builtInCount = songs.size
        reloadUserSongs()
        loadSong(songs[0])

        midiInput = MidiInput(this, this)
        midiInput.start()

        handleViewIntent(intent)
        updateButtons()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        if (micMode != 0 && mic == null && hasMicPermission()) startMic()
        synth.instrument = Instrument.load(this)     // the editor may have changed it
        if (soundMode != SOUND_OFF) synth.start()
    }

    override fun onPause() {
        super.onPause()
        engine.playing = false
        stopMic()
        synth.stop()
        updateButtons()
    }

    override fun onDestroy() {
        midiInput.stop()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // rotation is handled here (see configChanges in the manifest): re-lay out the controls
        arrangeTopBar()
        settingsDialog?.takeIf { it.isShowing }?.let { SettingsDialog.fitWindow(this, it) }
    }

    // ------------------------------------------------------------ orientation

    private fun applyOrientation() {
        requestedOrientation = if (display.portrait) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                               else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    /**
     * Landscape: every control in one row. Portrait: the same controls in three rows so they all
     * stay visible on a narrow screen:
     *   [♫ Song ............] [▶] [⟲] [⚙]
     *   [===== seek bar =====] [Set A]
     *   [Wait] [Hands] [Lock] [Mic] [Sound]
     * Follows the actual screen shape, so it is right even before a requested rotation happens.
     */
    private fun arrangeTopBar() {
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        if (topBarPortrait == portrait) return
        topBarPortrait = portrait
        val dp = resources.displayMetrics.density
        fun px(v: Float) = (v * dp).toInt()
        val restart = findViewById<TextView>(R.id.restartBtn)
        val settings = findViewById<TextView>(R.id.settingsBtn)
        val chips = listOf(songBtn, playBtn, restart, loopBtn, waitBtn, handsBtn, lockBtn, micBtn, soundBtn, settings)
        (chips + seek + status).forEach { (it.parent as? ViewGroup)?.removeView(it) }
        topBar.removeAllViews()

        fun row(topMargin: Float = 0f) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            topBar.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                this.topMargin = px(topMargin)
            })
        }
        fun LinearLayout.put(v: View, weight: Float = 0f, end: Float = 6f,
                             height: Int = LinearLayout.LayoutParams.WRAP_CONTENT) =
            addView(v, LinearLayout.LayoutParams(
                if (weight > 0f) 0 else LinearLayout.LayoutParams.WRAP_CONTENT,
                height, weight).apply { marginEnd = px(end) })

        // back to the Chip style's look (portrait compacts some chips below)
        for (c in chips) {
            c.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
            c.setPadding(px(11f), c.paddingTop, px(11f), c.paddingBottom)
            c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            c.gravity = Gravity.CENTER
        }
        playBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        restart.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        settings.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)

        if (!portrait) {
            songBtn.maxWidth = px(170f)
            val r = row()
            r.put(songBtn); r.put(playBtn); r.put(restart)
            r.put(seek, weight = 1f, end = 0f)
            r.put(loopBtn); r.put(waitBtn); r.put(handsBtn); r.put(lockBtn); r.put(micBtn); r.put(soundBtn)
            r.put(settings, end = 0f)
            status.isSingleLine = true
        } else {
            songBtn.maxWidth = Int.MAX_VALUE
            songBtn.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            val r1 = row()
            r1.put(songBtn, weight = 1f); r1.put(playBtn); r1.put(restart)
            r1.put(settings, end = 0f)
            val r2 = row(6f)
            r2.put(seek, weight = 1f, end = 4f)
            r2.put(loopBtn, end = 0f)
            val r3 = row(6f)
            for (c in listOf(waitBtn, handsBtn, lockBtn, micBtn, soundBtn)) {
                c.setPadding(px(3f), c.paddingTop, px(3f), c.paddingBottom)
                // shrink the text (12sp down to 8sp) when a label like "🎤 Chords β" is too wide;
                // auto-size needs a fixed height, so these chips get exactly the Chip min height
                c.setAutoSizeTextTypeUniformWithConfiguration(8, 12, 1, TypedValue.COMPLEX_UNIT_SP)
                r3.put(c, weight = 1f, end = if (c === soundBtn) 0f else 5f, height = px(36f))
            }
            status.isSingleLine = false
            status.maxLines = 2
        }
        topBar.addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(5f)
        })
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
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    // ------------------------------------------------------------------- songs

    private fun loadSong(song: Song) {
        engine.playing = false
        engine.load(song)
        piano.fitToSong(song)
        songBtn.text = "♫  ${song.title}"
        // single-hand songs: practice whatever hand exists
        if (handsMode == 1 && !song.hasRightHand || handsMode == 2 && !song.hasLeftHand) handsMode = 0
        applyHands()
        updateButtons()
    }

    private fun applyHands() {
        engine.practiceRight = handsMode != 2
        engine.practiceLeft = handsMode != 1
        updateButtons()
    }

    private fun showSongPicker() {
        val current = engine.song
        val actions = listOfNotNull(
            current?.let { "✎  Edit “${it.title}”…" to { openEditor(it) } },
            ("＋  New empty song…" to { openEditor(null) }),
            ("⬆  Import MIDI file (.mid)…" to { pickMidiFile() }),
            if (songs.size > builtInCount) ("🗑  Delete a saved song…" to { showDeletePicker() }) else null,
        )
        val items = songs.mapIndexed { i, s -> if (i >= builtInCount) "📁  ${s.title}" else s.title } +
            actions.map { it.first }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Choose a song")
            .setItems(items.toTypedArray()) { _, which ->
                if (which < songs.size) loadSong(songs[which]) else actions[which - songs.size].second()
            }
            .show()
    }

    private fun isUserSong(song: Song) = songs.indexOf(song) >= builtInCount

    @Suppress("DEPRECATION")
    private fun openEditor(song: Song?) {
        engine.playing = false
        updateButtons()
        startActivityForResult(EditorActivity.intent(this, song, song != null && isUserSong(song)), REQ_EDIT)
    }

    private fun showDeletePicker() {
        val user = songs.drop(builtInCount)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Delete a saved song")
            .setItems(user.map { it.title }.toTypedArray()) { _, which ->
                val s = user[which]
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Delete “${s.title}”?")
                    .setMessage("This removes it from your song list. This can't be undone.")
                    .setPositiveButton("Delete") { _, _ ->
                        SongStore.delete(this, s.title)
                        reloadUserSongs()
                        if (engine.song === s) loadSong(songs[0])
                        toast("Deleted “${s.title}”")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .show()
    }

    /** Re-reads the user's saved songs from storage; returns the one titled [select], if any. */
    private fun reloadUserSongs(select: String? = null): Song? {
        while (songs.size > builtInCount) songs.removeAt(songs.size - 1)
        songs.addAll(SongStore.loadAll(this))
        return select?.let { t -> songs.drop(builtInCount).firstOrNull { it.title == t } }
    }

    @Suppress("DEPRECATION")
    private fun pickMidiFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "audio/midi", "audio/x-midi", "audio/mid", "application/x-midi",
                "audio/sp-midi", "application/octet-stream"
            ))
        }
        try {
            startActivityForResult(intent, REQ_MIDI_FILE)
        } catch (e: Exception) {
            toast("No file picker available")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_MIDI_FILE && resultCode == RESULT_OK) {
            data?.data?.let { importMidi(it) }
        }
        if (requestCode == REQ_EDIT && resultCode == RESULT_OK) {
            val title = data?.getStringExtra(EditorActivity.RESULT_TITLE) ?: return
            reloadUserSongs(title)?.let { loadSong(it) }
        }
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let { importMidi(it) }
    }

    private fun importMidi(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Couldn't open file")
            val name = displayName(uri) ?: "Imported song.mid"
            MidiFileParser.parse(bytes, name)          // validate before storing
            // keep a copy so it stays in the song list next time
            val title = SongStore.saveRaw(this, bytes, name)
            val song = reloadUserSongs(title) ?: throw IllegalStateException("Couldn't store file")
            loadSong(song)
            toast("Loaded “$title” (${song.notes.size} notes)")
        } catch (e: Exception) {
            toast("Couldn't read MIDI file: ${e.message}")
        }
    }

    private fun displayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    // ---------------------------------------------------------------- controls

    /** Set A -> Set B -> loop active (tap again to clear). */
    private fun onLoopButton() {
        val t = engine.time.coerceAtLeast(0.0)
        when {
            engine.looping -> {
                engine.clearLoop()
                toast("Loop cleared")
            }
            engine.loopStart == null -> {
                engine.setLoopStart(t)
                toast("Loop start (A) set. Drag the notes or play on to the end of the part, then tap Set B")
            }
            else -> {
                engine.setLoopEnd(t)
                engine.playing = true
                toast("Looping A-B. Restart retries the part; tap A-B to clear")
            }
        }
        updateButtons()
    }

    // ------------------------------------------------------------ scrolling

    override fun onScrubStart() {
        scrubWasPlaying = engine.playing
        engine.playing = false
        scrubBase = engine.time
        updateButtons()
    }

    override fun onScrub(deltaSeconds: Double) {
        engine.seek(scrubBase + deltaSeconds)
    }

    /** The view was zoomed, moved or resized with a gesture (only while unlocked). */
    override fun onViewChanged(s: DisplaySettings) {
        display = s
        s.save(this)
    }

    override fun onScrubEnd() {
        engine.playing = scrubWasPlaying
        updateButtons()
    }

    private fun togglePlay() {
        if (engine.song == null) return
        if (engine.finished) engine.restart()
        engine.playing = !engine.playing
        updateButtons()
    }

    private fun updateButtons() {
        playBtn.text = if (engine.playing) "❚❚" else "▶"
        waitBtn.text = if (engine.waitMode) "⏸ Wait" else "→ Flow"
        waitBtn.setBackgroundResource(if (engine.waitMode) R.drawable.chip_bg_active else R.drawable.chip_bg)
        handsBtn.text = when (handsMode) { 1 -> "✋ Right"; 2 -> "✋ Left"; else -> "✋ Both" }
        loopBtn.text = when {
            engine.looping -> "A-B ✕"
            engine.loopStart != null -> "Set B"
            else -> "Set A"
        }
        loopBtn.setBackgroundResource(
            if (engine.loopStart != null) R.drawable.chip_bg_active else R.drawable.chip_bg)
        lockBtn.text = if (display.viewLocked) "🔒" else "🔓"
        lockBtn.setBackgroundResource(if (display.viewLocked) R.drawable.chip_bg_active else R.drawable.chip_bg)
        val micOn = mic != null
        micBtn.text = when {
            !micOn -> "🎤 Off"
            micMode == 2 -> "🎤 Chords β"
            else -> "🎤 Single"
        }
        micBtn.setBackgroundResource(if (micOn) R.drawable.chip_bg_active else R.drawable.chip_bg)
        soundBtn.text = when (soundMode) {
            SOUND_SONG -> "🔊 Song"
            SOUND_OTHER_HAND -> "🔊 Other"
            else -> "🔇"
        }
        soundBtn.setBackgroundResource(if (soundMode != SOUND_OFF) R.drawable.chip_bg_active else R.drawable.chip_bg)
    }

    // --------------------------------------------------------------- microphone

    private fun hasMicPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Cycles the microphone: Off -> Single note -> Chords (experimental) -> Off. */
    private fun toggleMic() {
        val next = (micMode + 1) % 3
        stopMic()
        micMode = next
        if (micMode == 0) { updateButtons(); return }
        if (!hasMicPermission()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        startMic()
        announceMicMode()
    }

    /** Set when starting the mic switched phone sound off, so the announcement can say so. */
    private var soundTurnedOffForMic = false

    private fun announceMicMode() {
        if (mic == null) return
        val msg = if (micMode == 2) "Chord listening (experimental): hold chords briefly, may hear ghost notes"
                  else "Listening for single notes: play one note at a time"
        toast(if (soundTurnedOffForMic) "$msg. Phone sound is off while the mic listens" else msg)
        soundTurnedOffForMic = false
    }

    private fun startMic() {
        // The phone's own sound would be heard by the mic and counted as played notes,
        // so phone sound and mic listening are never on together.
        if (soundMode != SOUND_OFF) {
            setSoundMode(SOUND_OFF)
            soundTurnedOffForMic = true
        }
        val mode = if (micMode == 2) MicNoteDetector.Mode.CHORDS else MicNoteDetector.Mode.SINGLE
        val d = MicNoteDetector(this, this, mode)
        if (d.start()) {
            mic = d
        } else {
            micMode = 0
            toast("Couldn't open the microphone")
        }
        updateButtons()
    }

    private fun stopMic() {
        mic?.stop()
        mic = null
        micNotes = emptyList()
        engine.releaseAll(InputSource.MIC)
        engine.releaseAll(InputSource.MIC_CHORD)
    }

    private val micSource get() = if (micMode == 2) InputSource.MIC_CHORD else InputSource.MIC

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startMic()
                announceMicMode()
            } else {
                micMode = 0
                updateButtons()
                toast("Microphone permission is needed to listen to an acoustic piano")
            }
        }
    }

    // ------------------------------------------------------------------ inputs

    override fun onTouchNoteOn(midi: Int) {
        engine.noteOn(midi, InputSource.TOUCH)
        if (soundMode != SOUND_OFF) synth.noteOn(midi)
    }

    override fun onTouchNoteOff(midi: Int) {
        engine.noteOff(midi, InputSource.TOUCH)
        synth.noteOff(midi)
    }

    /** Off -> whole song -> only the hand you're not practising -> off. */
    private fun cycleSound() {
        val next = (soundMode + 1) % 3
        // never together with the mic: it would hear the phone and mark notes as played
        val micWasOn = next != SOUND_OFF && micMode != 0
        if (micWasOn) {
            stopMic()
            micMode = 0
        }
        setSoundMode(next)
        val msg = when (soundMode) {
            SOUND_SONG -> "Phone plays the song's notes (in Wait mode you hear each note as a cue)"
            SOUND_OTHER_HAND -> "Phone plays the hand you're not practising (choose it with ✋)"
            else -> "Phone sound off"
        }
        toast(if (micWasOn && soundMode != SOUND_OFF) "Mic turned off while the phone plays sound. $msg" else msg)
    }

    /** Long-press the sound button: choose the phone's instrument. */
    private fun showInstrumentPicker() {
        val current = Instrument.ALL.indexOf(Instrument.load(this))
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Phone sound")
            .setSingleChoiceItems(Instrument.ALL.map { it.name }.toTypedArray(), current) { _, which ->
                val inst = Instrument.ALL[which]
                Instrument.save(this, inst)
                previewInstrument(inst)
            }
            .setPositiveButton("Done", null)
            .show()
    }

    /** Switches instrument and plays a short sample (briefly starting audio if sound is off). */
    private fun previewInstrument(inst: Instrument) {
        synth.instrument = inst
        if (mic != null) {
            toast("${inst.name} selected (no sample while the mic listens)")
            return
        }
        val wasRunning = synth.isRunning
        if (synth.start()) {
            synth.demo()
            if (!wasRunning) piano.postDelayed({ if (soundMode == SOUND_OFF) synth.stop() }, 2500)
        }
    }

    private fun setSoundMode(mode: Int) {
        soundMode = mode
        if (soundMode == SOUND_OFF) {
            synth.stop()
        } else if (!synth.start()) {
            soundMode = SOUND_OFF
            toast("Couldn't start audio output")
        }
        getSharedPreferences("practice", MODE_PRIVATE).edit().putInt("soundMode", soundMode).apply()
        updateButtons()
    }

    override fun onMidiNoteOn(midi: Int, velocity: Int) = engine.noteOn(midi, InputSource.MIDI)
    override fun onMidiNoteOff(midi: Int) = engine.noteOff(midi, InputSource.MIDI)
    override fun onMidiDevicesChanged(names: List<String>) {
        val added = names.filterNot { it in midiNames }
        if (names.size < midiNames.size) engine.releaseAll(InputSource.MIDI)
        midiNames = names
        added.forEach { toast("MIDI connected: $it") }
    }

    override fun onMicNoteOn(midi: Int) {
        if (mic == null || soundMode != SOUND_OFF) return   // (they're never on together; belt and braces)
        engine.noteOn(midi, micSource)
    }
    override fun onMicNoteOff(midi: Int) = engine.noteOff(midi, micSource)
    override fun onMicLevel(level: Float, notes: List<Int>) {
        micLevel = level
        micNotes = notes
    }

    // ------------------------------------------------------------------- frame

    override fun onFrame(dt: Double) {
        val wasPlaying = engine.playing
        engine.update(dt)
        engine.computeKeyStates()
        if (wasPlaying != engine.playing) updateButtons()
        if (++frame % 6 == 0) updateStatus()
    }

    private fun updateStatus() {
        if (!userSeeking) seek.progress = (engine.progress * 1000).toInt()
        val sb = StringBuilder()
        sb.append(
            when {
                !midiInput.supported -> "MIDI: not supported"
                midiNames.isEmpty() -> "MIDI: no keyboard (connect via USB)"
                else -> "MIDI: ${midiNames.joinToString()}"
            }
        )
        if (mic != null) {
            val pct = (micLevel * 1000).toInt().coerceIn(0, 100)
            sb.append("   ·   Mic").append(if (micMode == 2) " (chords β) " else " ").append("$pct%")
            if (micNotes.isNotEmpty()) sb.append(" hears ").append(micNotes.joinToString(" ") { NoteNames.nameWithOctave(it) })
        }
        val pressed = (0 until 128).filter { engine.keyPressed[it] != PracticeEngine.KEY_NONE }
        if (pressed.isNotEmpty()) {
            sb.append("   ·   Pressed: ").append(pressed.joinToString(" ") { NoteNames.nameWithOctave(it) })
        }
        if (display.speedPercent != 100) sb.append("   ·   Speed ${display.speedPercent}%")
        sb.append("   ·   ✓ ${engine.correctCount}  ✗ ${engine.wrongCount}")
        if (!engine.waitMode) sb.append("  missed ${engine.missedCount}")
        if (engine.looping) sb.append("   ·   Loop pass ${engine.loopPass}")
        if (engine.waiting && engine.restrikeNeeded) {
            sb.append("   ·   Play ").append(engine.waitingNoteNames()).append(" again (lift the key, then strike)")
        } else if (engine.waiting) sb.append("   ·   Waiting for ").append(engine.waitingNoteNames())
        else if (!engine.playing && !engine.finished) sb.append("   ·   Press ▶ to start, drag the notes to scroll")
        status.text = sb
        fadeStatus()
    }

    private fun fadeStatus() {
        val key = listOf(
            System.identityHashCode(engine.song), midiInput.supported, midiNames, mic != null, micMode,
            display.speedPercent,
            engine.looping, engine.waiting && engine.restrikeNeeded, engine.finished,
        ).joinToString()
        val now = SystemClock.uptimeMillis()
        if (key != statusKey) {
            statusKey = key
            statusShownAt = now
            if (!statusVisible) {
                statusVisible = true
                status.animate().cancel()
                status.animate().alpha(1f).setDuration(150).start()
            }
        } else if (statusVisible && now - statusShownAt > STATUS_VISIBLE_MS) {
            statusVisible = false
            status.animate().alpha(0f).setDuration(800).start()
        }
    }

    private fun toast(msg: String, long: Boolean = false) =
        Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}
