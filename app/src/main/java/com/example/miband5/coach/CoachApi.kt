package com.example.miband5.coach

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks to the SAME server this project already runs for the web app's
 * /api/coach endpoint (Node/Express, proxying to NVIDIA's cloud API or a
 * self-hosted NIM container). Nothing about the AI backend needs to be
 * reimplemented here -- the model-discovery, deprecation-retry, and system
 * prompt logic all already live server-side and are reused unchanged.
 *
 * Point [baseUrl] at wherever that server is deployed (e.g. your Render
 * URL). There is no API key here on purpose -- the key lives only on that
 * server, never on-device.
 */
class CoachApi(private val baseUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    sealed interface Result {
        data class Success(val reply: String) : Result
        data class Failure(val message: String) : Result
    }

    suspend fun ask(context: JSONObject, question: String): Result = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("context", context)
            put("question", question)
        }
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/coach")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val err = try { JSONObject(text).optString("error", text) } catch (e: Exception) { text }
                    return@withContext Result.Failure(err.ifBlank { "Coach request failed (${response.code})" })
                }
                val json = JSONObject(text)
                val reply = json.optString("reply", "")
                if (reply.isBlank()) Result.Failure("Coach returned an empty reply.")
                else Result.Success(reply)
            }
        } catch (e: Exception) {
            Result.Failure("Could not reach the Coach server: ${e.message}")
        }
    }
}
