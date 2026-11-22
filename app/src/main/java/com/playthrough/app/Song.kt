package com.playthrough.app

enum class Hand { RIGHT, LEFT }

/** A single note to play. Times are in seconds of song time. */
data class Note(val midi: Int, val start: Double, val duration: Double, val hand: Hand)

/**
 * @param beatSeconds length of one beat (MIDI quarter note) in seconds. The editor uses it for
 * its snap grid, and it's written as the tempo when saving to MIDI.
 */
class Song(val title: String, notes: List<Note>, val beatSeconds: Double = 0.5) {
    val notes: List<Note> = notes.sortedWith(compareBy<Note>({ it.start }, { it.midi }))
    val length: Double = this.notes.maxOfOrNull { it.start + it.duration } ?: 0.0
    val maxDuration: Double = this.notes.maxOfOrNull { it.duration } ?: 0.0
    val minMidi: Int = this.notes.minOfOrNull { it.midi } ?: 60
    val maxMidi: Int = this.notes.maxOfOrNull { it.midi } ?: 72
    val hasLeftHand: Boolean = this.notes.any { it.hand == Hand.LEFT }
    val hasRightHand: Boolean = this.notes.any { it.hand == Hand.RIGHT }
}

object NoteNames {
    private val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    fun name(midi: Int): String = names[((midi % 12) + 12) % 12]

    fun nameWithOctave(midi: Int): String = name(midi) + (midi / 12 - 1)

    fun isBlack(midi: Int): Boolean = when (((midi % 12) + 12) % 12) {
        1, 3, 6, 8, 10 -> true
        else -> false
    }

    /** Parses names like "C4", "D#5", "Bb3" into MIDI numbers (C4 = 60). */
    fun parse(text: String): Int {
        val s = text.trim()
        require(s.isNotEmpty()) { "Empty note name" }
        val base = when (s[0].uppercaseChar()) {
            'C' -> 0; 'D' -> 2; 'E' -> 4; 'F' -> 5; 'G' -> 7; 'A' -> 9; 'B' -> 11
            else -> throw IllegalArgumentException("Bad note name: $text")
        }
        var i = 1
        var accidental = 0
        while (i < s.length && (s[i] == '#' || s[i] == 'b')) {
            accidental += if (s[i] == '#') 1 else -1
            i++
        }
        val octave = s.substring(i).toInt()
        return (octave + 1) * 12 + base + accidental
    }
}
