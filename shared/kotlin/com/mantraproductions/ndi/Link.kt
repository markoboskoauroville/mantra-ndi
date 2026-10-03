package com.mantraproductions.ndi

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * MANTRA LINK (v131): the camera and the monitor over one plain TCP socket, the way scrcpy does it, without NDI.
 *
 * Marko, 3.10.2026: "remote control should be simplified simply having a remote session view like VNC ... I'm actually
 * pressing everything on remote"; "whatever protocol is used by SCRCPY ... the result is fantastic"; and the go-ahead
 * for the design: the camera sends ITS OWN WINDOW (picture, keys, settings) as H.264, the monitor sends every touch
 * back and the camera plays it into its own window; FULL on the monitor asks for the CLEAN picture instead, and the
 * monitor shows only the record key and its counter. No adb, no developer mode, no NDI licence.
 *
 * Every message: type (1 byte), length (4 bytes, big-endian), payload.
 *   camera → monitor   CONFIG  w, h (int), csd-0 and csd-1 (len + bytes each)      the decoder's setup
 *                      FRAME   flags (byte: 1 = keyframe), pts µs (long), H.264 access unit
 *                      STATE   UTF-8 "key=value;…"  (rec 0/1, since ms of the take, clean 0/1, name)
 *   monitor → camera   TOUCH   actionMasked | pointer index << 4 (byte), then per pointer: id (byte), x, y (float, 0…1 of the picture)
 *                      KEY     UTF-8 name: a rail key ("REC", "L", …) or "BACK"
 *                      MODE    byte: 0 the window, 1 the clean picture
 */
object Link {
    const val PORT = 48100
    const val SERVICE = "_mantralink._tcp."

    const val CONFIG: Byte = 1
    const val FRAME: Byte = 2
    const val STATE: Byte = 3
    const val TOUCH: Byte = 10
    const val KEY: Byte = 11
    const val MODE: Byte = 12

    fun write(out: DataOutputStream, type: Byte, payload: ByteArray, len: Int = payload.size) {
        synchronized(out) {
            out.writeByte(type.toInt())
            out.writeInt(len)
            out.write(payload, 0, len)
            out.flush()
        }
    }

    /** One message, or null at the end of the stream. A length past 16 MB is a broken stream. */
    fun read(inp: DataInputStream): Pair<Byte, ByteArray>? {
        val t = try { inp.readByte() } catch (e: java.io.EOFException) { return null }
        val n = inp.readInt()
        require(n in 0..(16 shl 20)) { "bad length $n" }
        val b = ByteArray(n)
        inp.readFully(b)
        return t to b
    }

    fun state(map: Map<String, String>) = map.entries.joinToString(";") { "${it.key}=${it.value.replace(";", ",")}" }.toByteArray()
    fun parseState(b: ByteArray): Map<String, String> = String(b).split(";").mapNotNull {
        val i = it.indexOf('='); if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
    }.toMap()
}
