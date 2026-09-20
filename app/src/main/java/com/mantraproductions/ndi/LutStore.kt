package com.mantraproductions.ndi

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * One LUT slot per log curve, kept on this phone.
 *
 * Per curve rather than one for everything, because switching to V-Log should
 * pick up the V-Log LUT without anybody remembering to change a second setting.
 * On a shoot that second setting is the one that gets forgotten, and a
 * correction from the wrong format looks plausible enough not to be noticed.
 *
 * Nothing loaded means the generated table is used, which is a real correction
 * rather than a placeholder. Reset simply deletes the file and the generated
 * one comes back.
 */
class LutStore(private val context: Context) {

    private fun fileFor(curve: LogCurves.Curve) =
        File(context.filesDir, "lut_${curve.name.lowercase()}.cube")

    fun hasCustom(curve: LogCurves.Curve): Boolean = fileFor(curve).exists()

    /**
     * @return the number of points a side, or null if the file was not a
     *         readable 3D cube. The size is worth reporting: an operator who
     *         expected 33 and got 17 has grabbed the wrong file.
     */
    fun import(curve: LogCurves.Curve, uri: Uri): Int? = try {
        val text = context.contentResolver.openInputStream(uri)
            ?.bufferedReader()?.use { it.readText() }
        val parsed = text?.let { CubeLut.parse(it) }
        if (parsed == null) null
        else {
            fileFor(curve).writeText(text)
            parsed.size
        }
    } catch (e: Exception) {
        Log.w(TAG, "import", e)
        null
    }

    fun clear(curve: LogCurves.Curve) {
        runCatching { fileFor(curve).delete() }
    }

    /**
     * The table to use for this curve right now.
     *
     * A loaded file that will not parse is treated as no file rather than as a
     * failure, because a correction that silently stops working is worse than
     * one that quietly falls back to the derived table.
     */
    fun load(curve: LogCurves.Curve): CubeLut {
        val file = fileFor(curve)
        if (file.exists()) {
            runCatching { CubeLut.parse(file.readText()) }.getOrNull()?.let { return it }
        }
        return CubeLut.generate(curve)
    }

    private companion object {
        const val TAG = "LutStore"
    }
}
