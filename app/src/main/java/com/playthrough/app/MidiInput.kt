package com.playthrough.app

import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Listens to every connected MIDI keyboard (USB, or Bluetooth devices already connected
 * through the system) and reports note on/off on the main thread.
 */
class MidiInput(context: Context, private val listener: Listener) {

    interface Listener {
        fun onMidiNoteOn(midi: Int, velocity: Int)
        fun onMidiNoteOff(midi: Int)
        fun onMidiDevicesChanged(names: List<String>)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val manager: MidiManager? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI))
            context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
        else null

    val supported: Boolean get() = manager != null

    private class Connection(val name: String, val device: MidiDevice, val ports: List<MidiOutputPort>)

    private val connections = HashMap<Int, Connection>()
    private val opening = HashSet<Int>()
    private var started = false

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) = open(device)
        override fun onDeviceRemoved(device: MidiDeviceInfo) = close(device.id)
    }

    @Suppress("DEPRECATION")
    fun start() {
        val m = manager ?: return
        if (started) return
        started = true
        m.registerDeviceCallback(callback, handler)
        val devices = if (Build.VERSION.SDK_INT >= 33) {
            m.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).toList()
        } else {
            m.devices.toList()
        }
        devices.forEach { open(it) }
        notifyNames()
    }

    fun stop() {
        val m = manager ?: return
        if (!started) return
        started = false
        m.unregisterDeviceCallback(callback)
        for (id in connections.keys.toList()) close(id)
    }

    val deviceNames: List<String> get() = connections.values.map { it.name }

    private fun nameOf(info: MidiDeviceInfo): String {
        val p = info.properties
        return p.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: p.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: p.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)
            ?: "MIDI device"
    }

    private fun open(info: MidiDeviceInfo) {
        val m = manager ?: return
        // A device's *output* ports are where its key presses come out.
        if (info.outputPortCount == 0) return
        if (connections.containsKey(info.id) || !opening.add(info.id)) return
        m.openDevice(info, { device ->
            opening.remove(info.id)
            if (device == null) return@openDevice
            if (!started) { device.close(); return@openDevice }
            val ports = (0 until info.outputPortCount).mapNotNull { device.openOutputPort(it) }
            ports.forEach { it.connect(Parser()) }
            connections[info.id] = Connection(nameOf(info), device, ports)
            notifyNames()
        }, handler)
    }

    private fun close(id: Int) {
        val c = connections.remove(id) ?: return
        c.ports.forEach { runCatching { it.close() } }
        runCatching { c.device.close() }
        notifyNames()
    }

    private fun notifyNames() {
        listener.onMidiDevicesChanged(deviceNames)
    }

    /** Byte-stream parser with running status; ignores realtime, sysex and non-note messages. */
    private inner class Parser : MidiReceiver() {
        private var running = 0
        private var data1 = -1
        private var inSysex = false

        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            for (i in offset until offset + count) {
                val b = msg[i].toInt() and 0xFF
                if (b >= 0xF8) continue                   // realtime (clock, active sensing)
                if (b == 0xF0) { inSysex = true; continue }
                if (b == 0xF7) { inSysex = false; continue }
                if (b and 0x80 != 0) {
                    inSysex = false
                    running = if (b >= 0xF0) 0 else b
                    data1 = -1
                    continue
                }
                if (inSysex || running == 0) continue
                val type = running and 0xF0
                if (type == 0xC0 || type == 0xD0) { data1 = -1; continue }
                if (data1 < 0) { data1 = b; continue }
                val note = data1
                val velocity = b
                data1 = -1
                when {
                    type == 0x90 && velocity > 0 -> handler.post { listener.onMidiNoteOn(note, velocity) }
                    type == 0x80 || type == 0x90 -> handler.post { listener.onMidiNoteOff(note) }
                }
            }
        }
    }
}
