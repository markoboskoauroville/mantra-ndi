package com.mantraproductions.ndi

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Asks a vision model what is in the frame, so focus can be chosen by name.
 *
 * A focus box is a place. This is the other way round: the operator is shown
 * what the camera is looking at, picks the thing they mean, and the box goes
 * there. On a busy frame that is faster and more certain than aiming a
 * rectangle at a face across a road.
 *
 * The model is asked for a position with every object, because a list without
 * coordinates could name the right subject and still leave nowhere to focus.
 * Positions come back as fractions of the frame, which survive any resolution.
 *
 * The key never ships. It is typed into settings and lives in the phone's own
 * storage, because this app's builds are published and anything compiled into
 * an APK can be read back out of it.
 */
object VisionFocus {

    /** A thing the model found, and where it is. */
    data class Subject(val label: String, val x: Float, val y: Float)

    /**
     * Groq retires vision models from time to time, so this is a default
     * rather than a constant, and a failure names the model so the next
     * session knows which one went.
     */
    const val DEFAULT_MODEL = "meta-llama/llama-4-scout-17b-16e-instruct"

    fun findSubjects(
        apiKey: String,
        frame: Bitmap,
        model: String = DEFAULT_MODEL,
        onResult: (List<Subject>) -> Unit,
        onError: (String) -> Unit
    ) {
        thread(name = "vision-focus") {
            try {
                // Small on purpose: the model is being asked what is in the
                // shot, not to read the newspaper in it, and a 640 wide frame
                // is a fraction of the upload and the latency of a full one.
                val scaled = Bitmap.createScaledBitmap(frame, 640, 360, true)
                val jpeg = ByteArrayOutputStream().also {
                    scaled.compress(Bitmap.CompressFormat.JPEG, 80, it)
                }.toByteArray()
                val base64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)

                val body = buildRequest(model, base64)
                val reply = post(apiKey, body)
                val subjects = parseSubjects(reply)

                if (subjects.isEmpty()) onError("Nothing recognisable in the frame")
                else onResult(subjects)
            } catch (e: Exception) {
                Log.w(TAG, "vision focus", e)
                onError(e.message ?: "Vision request failed")
            }
        }
    }

    private fun buildRequest(model: String, base64Jpeg: String): String {
        val instruction = """
            List the distinct subjects a camera operator might focus on in this frame.
            Reply with JSON only, no prose: {"subjects":[{"label":"...","x":0.0,"y":0.0}]}
            x and y are the centre of each subject as fractions of the frame,
            0,0 top left and 1,1 bottom right. At most 6 subjects, nearest first.
        """.trimIndent()

        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", instruction))
            .put(
                JSONObject().put("type", "image_url").put(
                    "image_url",
                    JSONObject().put("url", "data:image/jpeg;base64,$base64Jpeg")
                )
            )

        return JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", content))
            )
            .toString()
    }

    private fun post(apiKey: String, body: String): String {
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000

        connection.outputStream.use { it.write(body.toByteArray()) }

        val code = connection.responseCode
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()

        if (code !in 200..299) {
            // Never echo the key, not even inside an error the model returned.
            throw IllegalStateException("Groq returned $code")
        }
        return text
    }

    private fun parseSubjects(reply: String): List<Subject> {
        val content = JSONObject(reply)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")

        // Models wrap JSON in prose or fences however firmly they are told not
        // to, so the object is found rather than assumed.
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()

        val array = JSONObject(content.substring(start, end + 1)).optJSONArray("subjects")
            ?: return emptyList()

        val out = mutableListOf<Subject>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val label = item.optString("label").takeIf { it.isNotBlank() } ?: continue
            out.add(
                Subject(
                    label = label,
                    x = item.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                    y = item.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f)
                )
            )
        }
        return out
    }

    private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
    private const val TAG = "VisionFocus"
}
