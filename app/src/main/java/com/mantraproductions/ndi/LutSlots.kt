package com.mantraproductions.ndi

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Eleven LUT slots, kept on this phone.
 *
 * Eleven because that is how many keys fit down the side of a 16:9 picture on
 * this phone without any of them becoming too small to hit, and because the
 * ARRI LogC4 set is eleven files — the whole family, one per slot, which is
 * the shape the operator already has on their storage.
 *
 * A slot is a file and a name. The file is copied into the app's own storage
 * on import rather than referenced where it sits, because a content Uri from
 * a file picker is a loan: it is good for this launch and gone by the next,
 * and a LUT that quietly stops existing overnight is the worst kind of
 * setting. The name is what the operator called the file, shortened to
 * something that fits on a key.
 *
 * Nothing is loaded means nothing is corrected. There is no generated
 * fallback here on purpose: these slots are the operator's own LUTs and an
 * empty one that silently showed a computed curve instead would be lying
 * about what it is displaying.
 */
class LutSlots(private val context: Context) {

    /** A slot the operator sees: its number, what is in it, and how big. */
    data class Slot(val index: Int, val label: String?, val size: Int) {
        val loaded: Boolean get() = label != null
        /** Four characters is what fits on a key at this font size. */
        val key: String get() = label?.take(4)?.uppercase() ?: index.toString()
    }

    private fun fileFor(index: Int) = File(context.filesDir, "lut_slot_$index.cube")
    private fun nameFor(index: Int) = File(context.filesDir, "lut_slot_$index.name")

    /**
     * Copies the file in and parses it once to prove it is usable.
     *
     * @return the number of points a side, or null if the file was not a
     *   readable 3D cube. The size is worth reporting: an operator who
     *   expected 33 and got 17 has grabbed the wrong file.
     */
    fun import(index: Int, uri: Uri, displayName: String?): Int? {
        if (index !in 1..COUNT) return null
        return try {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() } ?: return null
            val parsed = CubeLut.parse(text) ?: run {
                Trace.refused("LUT slot $index", "not a readable 3D cube")
                return null
            }
            fileFor(index).writeText(text)
            val name = displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                ?: parsed.title.takeIf { it.isNotBlank() }
                ?: "LUT $index"
            nameFor(index).writeText(name)
            cache.remove(index)
            Trace.control("LUT slot $index", name, "${parsed.size} cubed")
            parsed.size
        } catch (e: Exception) {
            Trace.fault("LUT slot $index import", e)
            null
        }
    }

    fun clear(index: Int) {
        runCatching { fileFor(index).delete() }
        runCatching { nameFor(index).delete() }
        cache.remove(index)
        Trace.control("LUT slot $index", "cleared", "empty")
    }

    /**
     * The parsed cube in a slot, or null if it is empty.
     *
     * Parsed once and kept. A 33 cube is 35,937 triplets of text and reparsing
     * it on every tap is a visible pause on the one control an operator uses
     * while the camera is live.
     */
    fun cube(index: Int): CubeLut? {
        cache[index]?.let { return it }
        val file = fileFor(index)
        if (!file.exists()) return null
        val parsed = runCatching { CubeLut.parse(file.readText()) }.getOrNull()
        if (parsed == null) {
            // A file that will not parse is treated as an empty slot rather
            // than as a failure: the key goes grey and says so by being grey.
            Trace.refused("LUT slot $index", "stored file no longer parses")
            return null
        }
        cache[index] = parsed
        return parsed
    }

    fun slot(index: Int): Slot {
        val name = runCatching { nameFor(index).readText() }.getOrNull()
        val size = if (fileFor(index).exists()) cube(index)?.size ?: 0 else 0
        return Slot(index, if (size > 0) name ?: "LUT $index" else null, size)
    }

    fun all(): List<Slot> = (1..COUNT).map { slot(it) }

    fun loadedCount(): Int = (1..COUNT).count { fileFor(it).exists() }

    private val cache = HashMap<Int, CubeLut>()

    companion object {
        const val COUNT = 11
    }
}
