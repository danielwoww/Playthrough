package com.playthrough.app

/**
 * Built-in public-domain practice pieces.
 *
 * Notation for [Builder.seq]: space-separated tokens "NOTE/LENGTH", where LENGTH is in
 * the song's unit (default 1). "R" is a rest, "C4+E4" is a chord, "|" is ignored (bar line).
 */
object SongLibrary {

    fun builtIn(): List<Song> = listOf(
        cMajorScale(),
        twinkle(),
        odeToJoy(),
        minuetInG(),
        furElise(),
    )

    private class Builder(private val unitSeconds: Double) {
        val notes = ArrayList<Note>()

        fun seq(hand: Hand, startUnit: Double, text: String) {
            var t = startUnit
            for (raw in text.split(' ', '\n', '\t')) {
                val token = raw.trim()
                if (token.isEmpty() || token == "|") continue
                val parts = token.split('/')
                val len = if (parts.size > 1) parts[1].toDouble() else 1.0
                val head = parts[0]
                if (head != "R") {
                    for (name in head.split('+')) {
                        notes.add(Note(NoteNames.parse(name), t * unitSeconds, len * unitSeconds * 0.92, hand))
                    }
                }
                t += len
            }
        }

        fun right(text: String, start: Double = 0.0) = seq(Hand.RIGHT, start, text)
        fun left(text: String, start: Double = 0.0) = seq(Hand.LEFT, start, text)
    }

    private fun song(title: String, unitSeconds: Double, block: Builder.() -> Unit): Song {
        val b = Builder(unitSeconds)
        b.block()
        return Song(title, b.notes, beatSeconds = unitSeconds)
    }

    private fun cMajorScale() = song("C major scale (warm-up)", 0.6) {
        right("C4 D4 E4 F4 G4 A4 B4 C5 | B4 A4 G4 F4 E4 D4 C4/2")
    }

    private fun twinkle() = song("Twinkle Twinkle Little Star", 0.55) {
        right(
            """
            C4 C4 G4 G4 | A4 A4 G4/2 | F4 F4 E4 E4 | D4 D4 C4/2 |
            G4 G4 F4 F4 | E4 E4 D4/2 | G4 G4 F4 F4 | E4 E4 D4/2 |
            C4 C4 G4 G4 | A4 A4 G4/2 | F4 F4 E4 E4 | D4 D4 C4/2
            """
        )
        left(
            """
            C3/4 | F3/2 C3/2 | F3/2 C3/2 | G2/2 C3/2 |
            C3/2 F3/2 | C3/2 G2/2 | C3/2 F3/2 | C3/2 G2/2 |
            C3/4 | F3/2 C3/2 | F3/2 C3/2 | G2/2 C3/2
            """
        )
    }

    private fun odeToJoy() = song("Ode to Joy (Beethoven)", 0.55) {
        right(
            """
            E4 E4 F4 G4 | G4 F4 E4 D4 | C4 C4 D4 E4 | E4/1.5 D4/0.5 D4/2 |
            E4 E4 F4 G4 | G4 F4 E4 D4 | C4 C4 D4 E4 | D4/1.5 C4/0.5 C4/2
            """
        )
        left(
            """
            C3/4 | G2/4 | C3/4 | G2/4 |
            C3/4 | G2/4 | C3/4 | G2/2 C3/2
            """
        )
    }

    private fun minuetInG() = song("Minuet in G (Petzold)", 0.5) {
        right(
            """
            D5 G4/0.5 A4/0.5 B4/0.5 C5/0.5 | D5 G4 G4 |
            E5 C5/0.5 D5/0.5 E5/0.5 F#5/0.5 | G5 G4 G4 |
            C5 D5/0.5 C5/0.5 B4/0.5 A4/0.5 | B4 C5/0.5 B4/0.5 A4/0.5 G4/0.5 |
            F#4 G4/0.5 A4/0.5 B4/0.5 G4/0.5 | A4/3
            """
        )
        left(
            """
            G3+B3/2 A3 | B3/3 | C4/3 | B3/3 |
            A3/3 | G3/3 | D4 B3 G3 | D4 D3/2
            """
        )
    }

    /** Opening of "Für Elise" (Beethoven), unit = one sixteenth note. */
    private fun furElise() = song("Für Elise (opening)", 0.24) {
        right(
            """
            E5 D#5 |
            E5 D#5 E5 B4 D5 C5 | A4/2 R C4 E4 A4 | B4/2 R E4 G#4 B4 | C5/2 R E4 E5 D#5 |
            E5 D#5 E5 B4 D5 C5 | A4/2 R C4 E4 A4 | B4/2 R E4 C5 B4 | A4/4
            """
        )
        left(
            """
            A2 E3 A3 R/3 | E2 E3 G#3 R/3 | A2 E3 A3 R/3 |
            R/6 | A2 E3 A3 R/3 | E2 E3 G#3 R/3 | A2 E3 A3/2
            """,
            start = 8.0
        )
    }
}
