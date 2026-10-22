package com.quizsolver.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : Exception(message)

class GeminiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(4000, TimeUnit.MILLISECONDS)
        .writeTimeout(2500, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val models = listOf(
        "gemini-2.5-flash",
        "gemini-2.0-flash"
    )

    suspend fun generate(apiKey: String, imageBase64: String): String =
        withContext(Dispatchers.IO) {
            var lastError: Exception? = null

            for (model in models) {
                val includeThinking = model.startsWith("gemini-2.5")

                try {
                    return@withContext callModel(
                        apiKey,
                        model,
                        imageBase64,
                        includeThinking
                    )
                } catch (e: ApiException) {
                    if (e.code == 429 || e.code == 403 || e.code >= 500) {
                        throw e
                    }

                    lastError = e

                    if (e.code == 400) {
                        continue
                    }
                } catch (e: SocketTimeoutException) {
                    throw ApiException(0, "Err: Time")
                } catch (e: IOException) {
                    throw ApiException(0, "Err: Net")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw ApiException(0, "Err: Parse")
                }
            }

            throw lastError ?: ApiException(0, "Err: API")
        }

    private fun callModel(
        apiKey: String,
        model: String,
        imageBase64: String,
        includeThinking: Boolean
    ): String {
        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val body = buildBody(imageBase64, includeThinking)
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                return parseResponse(text)
            }

            if (response.code == 429 || response.code == 403) {
                throw ApiException(response.code, shortBody(text))
            }

            if (response.code >= 500) {
                throw ApiException(response.code, "Err: API")
            }

            if (response.code == 400) {
                throw ApiException(400, shortBody(text))
            }

            throw ApiException(response.code, "Err: ${response.code}")
        }
    }

    private fun buildBody(imageBase64: String, includeThinking: Boolean): String {
        val root = JSONObject()

        val contents = JSONArray()
        val userContent = JSONObject()
        userContent.put("role", "user")

        val parts = JSONArray()

        parts.put(
            JSONObject().put("text", PromptFactory.build())
        )

        parts.put(
            JSONObject().put(
                "inline_data",
                JSONObject()
                    .put("mime_type", "image/jpeg")
                    .put("data", imageBase64)
            )
        )

        userContent.put("parts", parts)
        contents.put(userContent)
        root.put("contents", contents)

        val generationConfig = JSONObject()
        generationConfig.put("temperature", 0.0)
        generationConfig.put("topP", 0.1)
        generationConfig.put("maxOutputTokens", 1024)

        if (includeThinking) {
            generationConfig.put(
                "thinkingConfig",
                JSONObject().put("thinkingBudget", 128)
            )
        }

        root.put("generationConfig", generationConfig)

        val safetySettings = JSONArray()

        val categories = arrayOf(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT"
        )

        for (category in categories) {
            safetySettings.put(
                JSONObject()
                    .put("category", category)
                    .put("threshold", "BLOCK_ONLY_HIGH")
            )
        }

        root.put("safetySettings", safetySettings)

        return root.toString()
    }

    private fun parseResponse(body: String): String {
        val root = JSONObject(body)

        val candidates = root.optJSONArray("candidates")

        if (candidates != null) {
            for (i in 0 until candidates.length()) {
                val candidate = candidates.optJSONObject(i) ?: continue
                val content = candidate.optJSONObject("content") ?: continue
                val parts = content.optJSONArray("parts") ?: continue

                for (j in 0 until parts.length()) {
                    val part = parts.optJSONObject(j) ?: continue
                    val text = part.optString("text", "").trim()

                    if (text.isNotEmpty()) {
                        return text
                    }
                }
            }
        }

        val feedback = root.optJSONObject("promptFeedback")
        val reason = feedback?.optString("blockReason") ?: ""

        if (reason.isNotEmpty()) {
            throw ApiException(0, "Err: Blocked")
        }

        throw ApiException(0, "Err: Empty")
    }

    private fun shortBody(body: String): String {
        return body.replace("\\s+".toRegex(), " ").take(140)
    }
}
