package com.mantraproductions.ndi

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Testing keys on purpose, which is the one time speculative testing is right.
 *
 * The ring never probes on its own. This runs only when the operator presses
 * Test, and then it follows the two rules the manifest paid for:
 *
 * **A list call is the wrong question.** Asking for the model list tells you
 * the key is real. A spent account is a real account and answers 200 to that
 * question, so the test shows green, the ring hands the key out, and the real
 * call fails. The probe therefore asks the provider to do the smallest
 * billable thing it sells: one token of text. It costs a fraction of a cent on
 * a live account and nothing at all on a dead one.
 *
 * **Never name a model in a probe.** A hard-coded model has an expiry date,
 * and when it is retired the 404 it produces looks exactly like a broken key,
 * which is the worst possible way for a key tester to fail. So the list is
 * fetched first and the first usable model taken from it.
 */
object KeyProbe {

    data class Result(val key: String, val state: KeyRing.State)

    /**
     * @param onProgress called after each key, so a long ring shows movement
     */
    fun testAll(
        entries: List<KeyRing.Entry>,
        onProgress: (done: Int, total: Int, result: Result) -> Unit,
        onFinished: () -> Unit
    ) {
        thread(name = "key-probe") {
            entries.forEachIndexed { index, entry ->
                val state = test(entry.key)
                onProgress(index + 1, entries.size, Result(entry.key, state))
            }
            onFinished()
        }
    }

    private fun test(key: String): KeyRing.State {
        val model = firstUsableModel(key) ?: return KeyRing.State.UNKNOWN

        // One token. The smallest billable thing this provider sells.
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 1)
            .put("temperature", 0)
            .put(
                "messages",
                org.json.JSONArray().put(
                    JSONObject().put("role", "user").put("content", "hi")
                )
            )
            .toString()

        val (code, text) = request(key, "$BASE/chat/completions", body)
        return KeyRing.classify(code, text)
    }

    /**
     * The first model the account actually has that can hold a conversation.
     * Speech and image models are skipped: asking a text to speech model for a
     * completion is a 400 that also reads like a dead key.
     */
    private fun firstUsableModel(key: String): String? {
        val (code, text) = request(key, "$BASE/models", null)
        if (code !in 200..299) {
            // The list refusing is itself informative, and classify will read
            // it, so return null and let the caller record UNKNOWN only when
            // there is genuinely nothing to go on.
            return null
        }
        return try {
            val data = JSONObject(text).getJSONArray("data")
            val names = (0 until data.length()).mapNotNull {
                data.optJSONObject(it)?.optString("id")
            }
            names.firstOrNull { id ->
                val lower = id.lowercase()
                listOf("whisper", "tts", "playai", "orpheus", "guard", "embed")
                    .none { it in lower }
            }
        } catch (e: Exception) {
            Log.w(TAG, "model list", e)
            null
        }
    }

    private fun request(key: String, url: String, body: String?): Pair<Int, String> = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = if (body == null) "GET" else "POST"
        connection.setRequestProperty("Authorization", "Bearer $key")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = connection.responseCode
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        code to text
    } catch (e: Exception) {
        0 to (e.message ?: "")
    }

    private const val BASE = "https://api.groq.com/openai/v1"
    private const val TAG = "KeyProbe"
}
