package com.playthrough.app

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Writes a [Song] as a Standard MIDI File (format 1): a tempo track, then one track per hand
 * named "Right hand" / "Left hand", so hands survive a save/load round trip.
 */
object MidiFileWriter {
    const val TRACK_RIGHT = "Right hand"
    const val TRACK_LEFT = "Left hand"
    private const val PPQ = 480

    fun write(song: Song): ByteArray {
        val beat = song.beatSeconds.takeIf { it in 0.05..10.0 } ?: 0.5
        fun tick(sec: Double): Long = Math.round(sec.coerceAtLeast(0.0) / beat * PPQ)

        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray(Charsets.ISO_8859_1))
        out.int32(6)
        out.int16(1)      // format 1
        out.int16(3)      // tempo + right + left
        out.int16(PPQ)

        // track 0: tempo
        val t0 = ByteArrayOutputStream()
        val us = Math.round(beat * 1e6).toInt().coerceIn(1, 0xFFFFFF)
        t0.varLen(0); t0.write(byteArrayOf(0xFF.toByte(), 0x51, 0x03))
        t0.write(us ushr 16 and 0xFF); t0.write(us ushr 8 and 0xFF); t0.write(us and 0xFF)
        t0.varLen(0); t0.write(byteArrayOf(0xFF.toByte(), 0x2F, 0x00))
        out.chunk(t0.toByteArray())

        for ((hand, name, channel) in listOf(
            Triple(Hand.RIGHT, TRACK_RIGHT, 0), Triple(Hand.LEFT, TRACK_LEFT, 1))) {
            // (tick, isOn, midi); offs sort before ons at the same tick
            val events = ArrayList<Triple<Long, Boolean, Int>>()
            for (n in song.notes) {
                if (n.hand != hand) continue
                val s = tick(n.start)
                val e = maxOf(s + 1, tick(n.start + n.duration))
                events.add(Triple(s, true, n.midi))
                events.add(Triple(e, false, n.midi))
            }
            events.sortWith(compareBy<Triple<Long, Boolean, Int>>({ it.first }, { it.second }))
            val tr = ByteArrayOutputStream()
            val nameBytes = name.toByteArray(Charsets.ISO_8859_1)
            tr.varLen(0); tr.write(0xFF); tr.write(0x03); tr.varLen(nameBytes.size); tr.write(nameBytes)
            var last = 0L
            for ((t, on, midi) in events) {
                tr.varLen((t - last).toInt())
                last = t
                if (on) { tr.write(0x90 or channel); tr.write(midi); tr.write(80) }
                else { tr.write(0x80 or channel); tr.write(midi); tr.write(0) }
            }
            tr.varLen(0); tr.write(byteArrayOf(0xFF.toByte(), 0x2F, 0x00))
            out.chunk(tr.toByteArray())
        }
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.int16(v: Int) { write(v ushr 8 and 0xFF); write(v and 0xFF) }
    private fun ByteArrayOutputStream.int32(v: Int) { int16(v ushr 16); int16(v and 0xFFFF) }
    private fun ByteArrayOutputStream.chunk(data: ByteArray) {
        write("MTrk".toByteArray(Charsets.ISO_8859_1)); int32(data.size); write(data)
    }
    private fun ByteArrayOutputStream.varLen(value: Int) {
        var v = value.coerceAtLeast(0)
        val buf = IntArray(4)
        var n = 0
        do { buf[n++] = v and 0x7F; v = v ushr 7 } while (v > 0 && n < 4)
        for (i in n - 1 downTo 0) write(if (i > 0) buf[i] or 0x80 else buf[i])
    }
}

/** The user's own songs (imported or created in the editor), stored as .mid files. */
object SongStore {
    fun dir(context: Context): File = File(context.filesDir, "midi").apply { mkdirs() }

    /** File-system-safe song title (this is also the title the song gets when loaded). */
    fun sanitize(title: String): String {
        val base = when {
            title.endsWith(".midi", ignoreCase = true) -> title.dropLast(5)
            title.endsWith(".mid", ignoreCase = true) -> title.dropLast(4)
            else -> title
        }
        // keep letters of any language (ü, é, ß…), only strip characters file systems reject
        return base.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.')
            .ifEmpty { "Untitled" }.take(80)
    }

    fun fileFor(context: Context, title: String) = File(dir(context), sanitize(title) + ".mid")

    fun exists(context: Context, title: String) = fileFor(context, title).exists()

    /** Saves [song] under [title]; returns the title it will load with. */
    fun save(context: Context, song: Song, title: String): String {
        fileFor(context, title).writeBytes(MidiFileWriter.write(song))
        return sanitize(title)
    }

    fun saveRaw(context: Context, bytes: ByteArray, title: String): String {
        fileFor(context, title).writeBytes(bytes)
        return sanitize(title)
    }

    fun delete(context: Context, title: String) = fileFor(context, title).delete()

    fun loadAll(context: Context): List<Song> =
        dir(context).listFiles()
            ?.filter { it.extension.equals("mid", true) || it.extension.equals("midi", true) }
            ?.sortedBy { it.name.lowercase() }
            ?.mapNotNull { f -> runCatching { MidiFileParser.parse(f.readBytes(), f.nameWithoutExtension) }.getOrNull() }
            ?: emptyList()
}
