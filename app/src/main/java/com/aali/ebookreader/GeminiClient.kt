package com.aali.ebookreader

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class GeminiModel(val id: String, val label: String)

/**
 * Minimal Google Gemini REST client (free tier key from aistudio.google.com).
 *
 * Google retires model names over time, so this client:
 *  - defaults to the moving alias "gemini-flash-latest"
 *  - can list the models the key can actually use
 *  - retries automatically when the error names a replacement model
 */
object GeminiClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    class GeminiException(message: String) : Exception(message)

    /** Models the key can call for text generation, newest looking first. */
    fun listModels(apiKey: String): List<GeminiModel> {
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey&pageSize=200")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw GeminiException(errorMessage(text, resp.code))
            val out = ArrayList<GeminiModel>()
            try {
                val arr = gson.fromJson(text, JsonObject::class.java)
                    .getAsJsonArray("models") ?: return emptyList()
                for (e in arr) {
                    val o = e.asJsonObject
                    val methods = o.getAsJsonArray("supportedGenerationMethods")
                        ?.map { it.asString } ?: emptyList()
                    if (!methods.contains("generateContent")) continue
                    val id = o.get("name").asString.removePrefix("models/")
                    // skip media only models, they cannot summarize text
                    if (id.contains("image") || id.contains("tts") || id.contains("banana") ||
                        id.contains("robotics") || id.contains("lyria") ||
                        id.contains("embedding") || id.contains("computer-use")
                    ) continue
                    val label = o.get("displayName")?.asString ?: id
                    out.add(GeminiModel(id, label))
                }
            } catch (e: Exception) {
                throw GeminiException("Could not read the model list")
            }
            // put the stable aliases and flash models at the top
            return out.sortedWith(
                compareBy(
                    { !it.id.endsWith("-latest") },
                    { !it.id.contains("flash") },
                    { it.id.contains("preview") },
                    { it.id }
                )
            )
        }
    }

    private fun errorMessage(body: String, code: Int): String = try {
        gson.fromJson(body, JsonObject::class.java)
            .getAsJsonObject("error").get("message").asString
    } catch (e: Exception) {
        "HTTP $code"
    }

    /** Pulls "models/xyz" out of a message such as "please use models/xyz". */
    private fun suggestedModel(message: String, tried: String): String? {
        val m = Regex("models/([A-Za-z0-9._-]+)").findAll(message)
            .map { it.groupValues[1] }
            .firstOrNull { it != tried }
        return m
    }

    fun generate(apiKey: String, model: String, prompt: String): String =
        generateInner(apiKey, model, prompt, allowRetry = true).first

    /** Returns the reply plus the model that actually answered. */
    private fun generateInner(
        apiKey: String,
        model: String,
        prompt: String,
        allowRetry: Boolean
    ): Pair<String, String> {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            "$model:generateContent?key=$apiKey"
        val body = JsonObject()
        val contents = com.google.gson.JsonArray()
        val content = JsonObject()
        val parts = com.google.gson.JsonArray()
        val part = JsonObject()
        part.addProperty("text", prompt)
        parts.add(part)
        content.add("parts", parts)
        contents.add(content)
        body.add("contents", contents)

        val req = Request.Builder()
            .url(url)
            .post(gson.toJson(body).toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                val msg = errorMessage(text, resp.code)
                if (allowRetry) {
                    // Google usually names the replacement model in the message
                    val replacement = suggestedModel(msg, model)
                    if (replacement != null) {
                        return generateInner(apiKey, replacement, prompt, false)
                    }
                    // otherwise fall back to the moving alias
                    if (model != Prefs.DEFAULT_MODEL &&
                        (msg.contains("not found", true) ||
                            msg.contains("no longer available", true) ||
                            msg.contains("not supported", true))
                    ) {
                        return generateInner(apiKey, Prefs.DEFAULT_MODEL, prompt, false)
                    }
                }
                throw GeminiException(msg)
            }
            val reply = try {
                gson.fromJson(text, JsonObject::class.java)
                    .getAsJsonArray("candidates").get(0).asJsonObject
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts").get(0).asJsonObject
                    .get("text").asString
            } catch (e: Exception) {
                throw GeminiException("Empty response from Gemini")
            }
            return reply to model
        }
    }

    /**
     * Summarize long text with a map then reduce strategy so books of any
     * size fit inside free tier limits.
     */
    /** Keeps answers readable on a phone: no LaTeX, no heavy markdown. */
    private const val STYLE =
        "Formatting rules you must follow: write plain readable English for a phone screen. " +
            "Never use LaTeX or dollar signs. Never write commands like \\text{} or \\frac{}. " +
            "Write chemical formulas and units as ordinary characters with Unicode " +
            "subscripts and superscripts, for example V2O5 as V₂O₅, " +
            "and 1.06 F g^-1 as 1.06 F g⁻¹. " +
            "Use short paragraphs, and simple \"- \" bullets for lists. " +
            "You may use **bold** for a few key terms only. Do not use tables or code blocks."

    fun summarize(
        apiKey: String,
        model: String,
        title: String,
        text: String,
        onProgress: (String) -> Unit
    ): String {
        val chunkSize = 14000
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw GeminiException("No text to summarize")

        // one probe call so a retired model is swapped before the long loop
        var useModel = model
        if (trimmed.length > chunkSize) {
            onProgress("Checking the AI model…")
            useModel = generateInner(apiKey, model, "Reply with the single word: ready", true).second
        }

        if (trimmed.length <= chunkSize) {
            onProgress("Summarizing…")
            return generate(
                apiKey, useModel,
                "Summarize the following part of the book \"$title\" in clear English. " +
                    "Cover the key points, characters and arguments.\n\n" + STYLE +
                    "\n\n" + trimmed
            )
        }

        val chunks = ArrayList<String>()
        var i = 0
        while (i < trimmed.length && chunks.size < 40) {
            var end = (i + chunkSize).coerceAtMost(trimmed.length)
            if (end < trimmed.length) {
                val lastBreak = trimmed.lastIndexOf('.', end)
                if (lastBreak > i + chunkSize / 2) end = lastBreak + 1
            }
            chunks.add(trimmed.substring(i, end))
            i = end
        }

        val partials = ArrayList<String>()
        for ((idx, c) in chunks.withIndex()) {
            onProgress("Summarizing part ${idx + 1} of ${chunks.size}…")
            partials.add(
                generate(
                    apiKey, useModel,
                    "Summarize this part (${idx + 1}/${chunks.size}) of the book \"$title\" " +
                        "in 5 to 8 sentences, keeping every important fact.\n\n" + STYLE +
                        "\n\n" + c
                )
            )
            Thread.sleep(1200) // stay inside the free tier rate limit
        }
        onProgress("Combining ${partials.size} part summaries…")
        return generate(
            apiKey, useModel,
            "Below are ordered part summaries of the book \"$title\". Combine them into one " +
                "well structured final summary with: a short overview paragraph, then the main " +
                "points or plot, then key takeaways.\n\n" + STYLE + "\n\n" +
                partials.joinToString("\n\n---\n\n")
        )
    }
}
