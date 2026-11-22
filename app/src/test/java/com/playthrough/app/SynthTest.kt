package com.playthrough.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Renders a short phrase with every instrument: checks the audio is finite, audible and not
 * clipping, and writes WAV files to app/build/instrument-samples/ for listening on a PC.
 */
class SynthTest {
    private val sr = 44100

    private fun renderPhrase(inst: Instrument): FloatArray {
        val core = SynthCore(sr)
        val out = FloatArray(sr * 4)
        val buf = FloatArray(256)
        // (time s, midi, hold s): arpeggio, then a two-hand chord, then a high run
        val events = listOf(
            Triple(0.0, 60, 0.3), Triple(0.25, 64, 0.3), Triple(0.5, 67, 0.3),
            Triple(0.9, 36, 1.2), Triple(0.9, 48, 1.2), Triple(0.9, 60, 1.2), Triple(0.9, 64, 1.2), Triple(0.9, 67, 1.2),
            Triple(2.4, 84, 0.2), Triple(2.6, 88, 0.2), Triple(2.8, 91, 0.2), Triple(3.0, 96, 0.5),
        )
        var ei = 0
        var pos = 0
        while (pos < out.size) {
            val t = pos.toDouble() / sr
            while (ei < events.size && events[ei].first <= t) {
                core.noteOn(events[ei].second, 0.8f, events[ei].third, inst); ei++
            }
            val n = minOf(buf.size, out.size - pos)
            core.render(buf, n)
            System.arraycopy(buf, 0, out, pos, n)
            pos += n
        }
        return out
    }

    private fun writeWav(file: File, data: FloatArray) {
        val pcm = ByteArrayOutputStream()
        for (v in data) {
            val s = (v.coerceIn(-1f, 1f) * 32000).roundToInt()
            pcm.write(s and 0xFF); pcm.write(s shr 8 and 0xFF)
        }
        val body = pcm.toByteArray()
        val out = ByteArrayOutputStream()
        fun i32(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF); out.write(v shr 16 and 0xFF); out.write(v shr 24 and 0xFF) }
        fun i16(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF) }
        out.write("RIFF".toByteArray()); i32(36 + body.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(sr); i32(sr * 2); i16(2); i16(16)
        out.write("data".toByteArray()); i32(body.size); out.write(body)
        file.parentFile?.mkdirs()
        file.writeBytes(out.toByteArray())
    }

    @Test fun everyInstrumentRendersCleanAudio() {
        for (inst in Instrument.ALL) {
            val audio = renderPhrase(inst)
            assertTrue("${inst.name}: NaN/Inf", audio.all { it.isFinite() })
            val peak = audio.maxOf { abs(it) }
            assertTrue("${inst.name}: too quiet (peak $peak)", peak > 0.1f)
            assertTrue("${inst.name}: clipping (peak $peak)", peak < 0.98f)
            // each section is audible: the high run at ~3 s too
            val high = audio.copyOfRange((3.0 * sr).toInt(), (3.3 * sr).toInt()).maxOf { abs(it) }
            assertTrue("${inst.name}: high notes inaudible", high > 0.02f)
            writeWav(File("app/build/instrument-samples/${inst.id}.wav"), audio)
        }
    }

    @Test fun releasedNotesFallSilent() {
        for (inst in Instrument.ALL) {
            val core = SynthCore(sr)
            val buf = FloatArray(sr)
            core.noteOn(60, 0.8f, 0.2, inst)
            core.render(buf, sr)            // 1 s: note held 0.2 s then released
            val tail = buf.copyOfRange(sr - 2000, sr).maxOf { abs(it) }
            assertTrue("${inst.name} still sounding after release ($tail)", tail < 1e-4f)
        }
    }
}
