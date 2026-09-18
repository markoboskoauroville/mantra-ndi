package com.mantraproductions.ndi

import android.content.Context
import android.os.Build

/**
 * The name this phone announces itself under on the network.
 *
 * With more than one camera this is the only thing distinguishing them. NDI
 * does prefix a machine name, but on Android that is often unhelpful or
 * identical across devices, so the name set here is what an operator actually
 * reads in a monitor or a vision mixer. It has to be deliberate.
 *
 * The default is derived from the device model rather than being the same
 * string on every install, so two phones out of the box don't both announce
 * "Mantra NDI".
 */
class SourceIdentity(context: Context) {

    private val prefs = context.getSharedPreferences("source_identity", Context.MODE_PRIVATE)

    var name: String
        get() = prefs.getString(KEY_NAME, null) ?: defaultName()
        set(value) {
            prefs.edit().putString(KEY_NAME, sanitize(value)).apply()
        }

    /** True once the operator has chosen a name rather than inheriting the default. */
    val isCustom: Boolean get() = prefs.getString(KEY_NAME, null) != null

    private fun defaultName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        return if (model.isEmpty()) "Mantra Cam" else sanitize("Mantra Cam $model")
    }

    companion object {
        private const val KEY_NAME = "name"
        const val MAX_LENGTH = Mechanism.MAX_NAME_LENGTH

        /**
         * NDI names travel through mDNS and get parsed by a lot of other
         * software, so keep them to plain characters and a sane length.
         */
        fun sanitize(raw: String): String = Mechanism.sanitizeSourceName(raw)

        /**
         * NDI advertises sources as "MACHINE (Source Name)", so a name collision
         * shows up as the same text inside the brackets. Compares that part.
         */
        fun clashesWith(candidate: String, existingSources: List<String>): Boolean =
            Mechanism.nameClashes(candidate, existingSources)
    }
}
