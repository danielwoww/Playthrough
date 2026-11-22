package com.playthrough.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Display settings with live preview: every change is applied immediately through [onChange],
 * so you can see the keyboard resize behind the dialog.
 */
object SettingsDialog {

    fun show(
        activity: Activity,
        initial: DisplaySettings,
        onInstrumentChanged: ((Instrument) -> Unit)? = null,
        onChange: (DisplaySettings) -> Unit,
    ): AlertDialog {
        var s = initial
        val dp = activity.resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * dp).toInt(), pad, 0)
        }

        fun update(next: DisplaySettings) {
            s = next
            onChange(s)
        }

        /** Adds "Label ...... value" plus a SeekBar mapping 0..steps to a value. */
        fun slider(label: String, steps: Int, position: Int, format: (Int) -> String, set: (Int) -> Unit) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (12 * dp).toInt(), 0, 0)
            }
            val name = TextView(activity).apply {
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val value = TextView(activity).apply {
                text = format(position)
                setTextColor(0xFFFF9A3C.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.END
            }
            row.addView(name)
            row.addView(value)
            root.addView(row)
            root.addView(SeekBar(activity).apply {
                max = steps
                progress = position
                progressTintList = android.content.res.ColorStateList.valueOf(0xFFFF9A3C.toInt())
                thumbTintList = android.content.res.ColorStateList.valueOf(0xFFFFC48A.toInt())
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        value.text = format(p)
                        set(p)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            })
        }

        fun toggle(label: String, checked: Boolean, set: (Boolean) -> Unit) {
            root.addView(CheckBox(activity).apply {
                text = label
                isChecked = checked
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFFFF9A3C.toInt())
                setPadding(paddingLeft, (6 * dp).toInt(), paddingRight, (6 * dp).toInt())
                setOnCheckedChangeListener { _, c -> set(c) }
            })
        }

        toggle("Portrait view (hold the phone upright)", s.portrait) { update(s.copy(portrait = it)) }

        // playback speed: 5% .. 125% in 5% steps
        slider("Playback speed", 24, s.speedPercent / 5 - 1,
            { val pct = (it + 1) * 5; "$pct%" + if (pct == 100) " (normal)" else "" }) {
            update(s.copy(speedPercent = (it + 1) * 5))
        }

        // keyboard height: 15% .. 60% of the screen in 1% steps
        slider("Piano size", 45, (s.keyboardSize * 100).roundToInt() - 15,
            { "${it + 15}% of screen" }) { update(s.copy(keyboardSize = (it + 15) / 100f)) }

        slider("3D effect", 10, (s.depth3d * 10).roundToInt(),
            { if (it == 0) "Flat" else "${it * 10}%" }) { update(s.copy(depth3d = it / 10f)) }

        // 0 = fit the song, otherwise 7..52 white keys (52 = the whole piano)
        val minKeys = DisplaySettings.MIN_WHITE_KEYS
        slider("Keys shown", DisplaySettings.MAX_WHITE_KEYS - minKeys + 1,
            if (s.visibleWhiteKeys == 0) 0 else s.visibleWhiteKeys - minKeys + 1,
            { if (it == 0) "Fit song" else "${it + minKeys - 1} white keys" }) {
            update(s.copy(visibleWhiteKeys = if (it == 0) 0 else it + minKeys - 1))
        }

        // look-ahead 1.5 .. 8 s in 0.5 s steps
        slider("Note fall speed", 13, ((s.lookAheadSeconds - 1.5f) / 0.5f).roundToInt(),
            { val sec = 1.5f + it * 0.5f; "${"%.1f".format(sec)} s visible" + when {
                sec <= 2f -> " (fast)"; sec >= 5f -> " (slow)"; else -> "" } }) {
            update(s.copy(lookAheadSeconds = 1.5f + it * 0.5f))
        }

        if (onInstrumentChanged != null) {
            // phone sound: tap to cycle instruments (plays a short sample)
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (16 * dp).toInt(), 0, 0)
            }
            row.addView(TextView(activity).apply {
                text = "Phone sound"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(activity).apply {
                text = "🎹 ${Instrument.load(activity).name}  ›"
                setTextColor(0xFFFF9A3C.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setBackgroundResource(R.drawable.chip_bg)
                val p = (10 * dp).toInt()
                setPadding(p, p / 2, p, p / 2)
                setOnClickListener {
                    val next = Instrument.next(Instrument.load(activity))
                    Instrument.save(activity, next)
                    text = "🎹 ${next.name}  ›"
                    onInstrumentChanged(next)
                }
            })
            root.addView(row)
        }

        root.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (12 * dp).toInt())
        })
        toggle("Note names on tiles", s.showTileLabels) { update(s.copy(showTileLabels = it)) }
        toggle("Labels on keys (C4, target notes)", s.showKeyLabels) { update(s.copy(showKeyLabels = it)) }
        toggle("Light beams on pressed keys", s.showBeams) { update(s.copy(showBeams = it)) }

        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Display & sound")
            .setView(ScrollView(activity).apply { addView(root) })
            .setPositiveButton("Done", null)
            .setNeutralButton("Reset", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                update(DisplaySettings(portrait = s.portrait, viewLocked = s.viewLocked))   // keep orientation and lock
                dialog.dismiss()
                show(activity, s, onInstrumentChanged, onChange)   // reopen with default positions
            }
        }
        dialog.window?.setDimAmount(0.15f)
        dialog.show()
        fitWindow(activity, dialog)
        return dialog
    }

    /**
     * Keeps the dialog small so the piano stays visible while adjusting: to the side in landscape,
     * across the top half in portrait (the keyboard is at the bottom). Call again after rotating.
     */
    fun fitWindow(activity: Activity, dialog: AlertDialog) {
        val w = dialog.window ?: return
        val dm = activity.resources.displayMetrics
        val dp = dm.density
        if (dm.heightPixels > dm.widthPixels) {
            w.setGravity(Gravity.CENTER_HORIZONTAL or Gravity.TOP)
            w.setLayout(minOf((380 * dp).toInt(), (dm.widthPixels * 0.94f).toInt()), (dm.heightPixels * 0.58f).toInt())
        } else {
            w.setGravity(Gravity.END or Gravity.TOP)
            w.setLayout((380 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }
}
