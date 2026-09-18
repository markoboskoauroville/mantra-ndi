package com.mantraproductions.ndi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The ring, on this phone only.
 *
 * Never in the build and never in the repository: these releases are public
 * and anything compiled into an APK can be read back out of it. Imported from
 * a file the operator picks, kept in the app's own preferences, and shown as
 * last four digits wherever a person can see it.
 */
class KeyRingStore(context: Context) {

    private val prefs = context.getSharedPreferences("groq_ring", Context.MODE_PRIVATE)

    fun load(): List<KeyRing.Entry> {
        val raw = prefs.getString(KEY_RING, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                KeyRing.Entry(
                    key = o.getString("k"),
                    state = runCatching { KeyRing.State.valueOf(o.getString("s")) }
                        .getOrDefault(KeyRing.State.UNKNOWN)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(entries: List<KeyRing.Entry>) {
        val array = JSONArray()
        entries.forEach {
            array.put(JSONObject().put("k", it.key).put("s", it.state.name))
        }
        prefs.edit().putString(KEY_RING, array.toString()).apply()
    }

    /**
     * Adds what a file contained, keeping what is already known.
     *
     * A re-import must not resurrect a key the ring has already buried:
     * somebody handing the same note over twice is not evidence that a
     * refused account has been fixed.
     */
    fun import(text: String): Int {
        val existing = load()
        val known = existing.associateBy { it.key }
        val incoming = KeyRing.parse(text)
        val merged = existing.toMutableList()
        var added = 0
        for (key in incoming) {
            if (known.containsKey(key)) continue
            merged.add(KeyRing.Entry(key))
            added++
        }
        save(merged)
        return added
    }

    fun record(key: String, state: KeyRing.State) {
        save(KeyRing.record(load(), key, state))
    }

    /** Forgets everything, for when a ring is replaced rather than extended. */
    fun clear() = prefs.edit().remove(KEY_RING).apply()

    /**
     * Puts every buried key back in play. For after topping an account up,
     * which is the one thing the ring cannot find out for itself.
     */
    fun revive() {
        save(load().map { it.copy(state = KeyRing.State.UNKNOWN) })
    }

    private companion object {
        const val KEY_RING = "entries"
    }
}
