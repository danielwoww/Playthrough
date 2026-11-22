package com.playthrough.app

/** Minimal Standard MIDI File (.mid, format 0/1) reader that extracts notes for practice. */
object MidiFileParser {

    class MidiFormatException(message: String) : Exception(message)

    private class Reader(val b: ByteArray) {
        var pos = 0
        fun remaining() = b.size - pos
        fun u8(): Int {
            if (pos >= b.size) throw MidiFormatException("Unexpected end of file")
            return b[pos++].toInt() and 0xFF
        }
        fun peek(): Int {
            if (pos >= b.size) throw MidiFormatException("Unexpected end of file")
            return b[pos].toInt() and 0xFF
        }
        fun u16() = (u8() shl 8) or u8()
        fun u32() = (u16() shl 16) or u16()
        fun varLen(): Int {
            var v = 0
            for (i in 0 until 4) {
                val c = u8()
                v = (v shl 7) or (c and 0x7F)
                if (c and 0x80 == 0) return v
            }
            return v
        }
        fun str4(): String = String(CharArray(4) { u8().toChar() })
    }

    private class RawNote(val track: Int, val midi: Int, val startTick: Long, val endTick: Long)

    fun parse(data: ByteArray, title: String): Song {
        val r = Reader(data)
        if (r.remaining() < 14 || r.str4() != "MThd") throw MidiFormatException("Not a MIDI file")
        val headerLen = r.u32()
        r.u16() // format
        val nTracks = r.u16()
        val division = r.u16()
        r.pos += (headerLen - 6).coerceAtLeast(0)

        val smpte = division and 0x8000 != 0
        val ticksPerQuarter = if (smpte) 0 else division.coerceAtLeast(1)
        val smpteTicksPerSecond = if (smpte) {
            val fps = -((division shr 8).toByte().toInt())
            (fps * (division and 0xFF)).coerceAtLeast(1).toDouble()
        } else 0.0

        val raws = ArrayList<RawNote>()
        val tempos = ArrayList<Pair<Long, Int>>()
        val trackNames = HashMap<Int, String>()

        for (t in 0 until nTracks) {
            if (r.remaining() < 8) break
            val id = r.str4()
            val len = r.u32()
            val end = (r.pos.toLong() + len).coerceAtMost(data.size.toLong()).toInt()
            if (id != "MTrk") { r.pos = end; continue }

            var tick = 0L
            var running = 0
            val open = HashMap<Int, ArrayDeque<Long>>()
            try {
                while (r.pos < end) {
                    tick += r.varLen()
                    var status = r.peek()
                    if (status and 0x80 != 0) {
                        r.pos++
                    } else {
                        if (running == 0) throw MidiFormatException("Bad running status")
                        status = running
                    }
                    when {
                        status == 0xFF -> {
                            val type = r.u8()
                            val l = r.varLen()
                            val dataStart = r.pos
                            if (type == 0x51 && l >= 3) {
                                val us = (r.u8() shl 16) or (r.u8() shl 8) or r.u8()
                                if (us > 0) tempos.add(tick to us)
                            } else if (type == 0x03 && l in 1..64 && dataStart + l <= data.size) {
                                trackNames[t] = String(data, dataStart, l, Charsets.ISO_8859_1)
                            }
                            r.pos = dataStart + l
                            if (type == 0x2F) r.pos = end
                        }
                        status == 0xF0 || status == 0xF7 -> {
                            val l = r.varLen()
                            r.pos += l
                        }
                        else -> {
                            running = status
                            val type = status and 0xF0
                            val ch = status and 0x0F
                            val d1 = r.u8()
                            val d2 = if (type == 0xC0 || type == 0xD0) 0 else r.u8()
                            if (ch == 9) continue // drums
                            val key = ch * 128 + d1
                            if (type == 0x90 && d2 > 0) {
                                open.getOrPut(key) { ArrayDeque() }.addLast(tick)
                            } else if (type == 0x80 || type == 0x90) {
                                val st = open[key]?.removeFirstOrNull()
                                if (st != null) raws.add(RawNote(t, d1, st, tick))
                            }
                        }
                    }
                }
            } catch (e: MidiFormatException) {
                // Keep whatever we could read from a truncated track.
            }
            for ((key, q) in open) for (st in q) raws.add(RawNote(t, key % 128, st, maxOf(tick, st + 1)))
            r.pos = end
        }

        if (raws.isEmpty()) throw MidiFormatException("No notes found in this file")

        tempos.sortBy { it.first }
        if (tempos.isEmpty() || tempos[0].first > 0) tempos.add(0, 0L to 500_000)

        fun toSeconds(tick: Long): Double {
            if (smpte) return tick / smpteTicksPerSecond
            var sec = 0.0
            var lastTick = 0L
            var us = 500_000
            for ((tt, tempo) in tempos) {
                if (tt >= tick) break
                sec += (tt - lastTick).toDouble() * us / 1e6 / ticksPerQuarter
                lastTick = tt
                us = tempo
            }
            sec += (tick - lastTick).toDouble() * us / 1e6 / ticksPerQuarter
            return sec
        }

        // Hand assignment: tracks named "Right"/"Left" (as Playthrough writes them) win; otherwise with
        // several note tracks the highest-pitched track is the right hand; otherwise split at C4.
        val tracks = raws.groupBy { it.track }
        fun namedHand(track: Int): Hand? {
            val n = trackNames[track]?.lowercase() ?: return null
            return when {
                "right" in n || n == "rh" -> Hand.RIGHT
                "left" in n || n == "lh" -> Hand.LEFT
                else -> null
            }
        }
        val rightTrack: Int? = if (tracks.size >= 2) {
            tracks.maxByOrNull { (_, list) -> list.sumOf { it.midi }.toDouble() / list.size }?.key
        } else null

        val notes = raws.map { rn ->
            val start = toSeconds(rn.startTick)
            val end = toSeconds(rn.endTick)
            val hand = namedHand(rn.track) ?: if (rightTrack != null) {
                if (rn.track == rightTrack) Hand.RIGHT else Hand.LEFT
            } else {
                if (rn.midi >= 60) Hand.RIGHT else Hand.LEFT
            }
            Note(rn.midi.coerceIn(21, 108), start, (end - start).coerceAtLeast(0.06), hand)
        }
        // keep time 0 when the file was written by us (so edits don't shift), else trim silence
        val offset = if (trackNames.values.any { it == MidiFileWriter.TRACK_RIGHT || it == MidiFileWriter.TRACK_LEFT }) 0.0
                     else notes.minOf { it.start }
        val beat = if (smpte) 0.5 else tempos[0].second / 1e6
        return Song(title, notes.map { it.copy(start = it.start - offset) }, beatSeconds = beat)
    }
}
