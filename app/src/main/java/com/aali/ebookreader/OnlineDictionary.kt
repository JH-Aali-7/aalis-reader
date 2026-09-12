package com.aali.ebookreader

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class OnlineResult(val source: String, val text: String)

/**
 * Looks words up on the internet when the offline dictionary has nothing,
 * or when a fuller explanation is wanted. Uses Wiktionary for definitions
 * and Wikipedia for scientific and technical subjects. No key needed.
 */
object OnlineDictionary {

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private const val UA = "AaliReader/1.4 (personal ebook reader)"

    fun isOnline(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Exception) {
        false
    }

    private fun get(url: String): String? = try {
        val req = Request.Builder().url(url).header("User-Agent", UA).get().build()
        http.newCall(req).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }

    private fun enc(s: String): String = URLEncoder.encode(s.trim(), "UTF-8").replace("+", "%20")

    private fun stripHtml(s: String): String = s
        .replace(Regex("<[^>]+>"), "")
        .replace("&quot;", "\"").replace("&amp;", "&")
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&nbsp;", " ").replace("&#39;", "'")
        .replace(Regex("\\s{2,}"), " ")
        .trim()

    /** Wiktionary definitions, grouped by part of speech. */
    fun wiktionary(word: String): OnlineResult? {
        val body = get(
            "https://en.wiktionary.org/api/rest_v1/page/definition/${enc(word)}"
        ) ?: return null
        return try {
            val root = gson.fromJson(body, JsonObject::class.java)
            val en = root.getAsJsonArray("en") ?: return null
            val sb = StringBuilder()
            var count = 0
            for (block in en) {
                val o = block.asJsonObject
                val pos = o.get("partOfSpeech")?.asString ?: ""
                val defs = o.getAsJsonArray("definitions") ?: continue
                if (defs.size() == 0) continue
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(pos.lowercase()).append("\n")
                var i = 1
                for (d in defs) {
                    if (i > 5) break
                    val dob = d.asJsonObject
                    val text = stripHtml(dob.get("definition")?.asString ?: "")
                    if (text.isEmpty()) continue
                    sb.append("$i. ").append(text).append("\n")
                    val ex = dob.getAsJsonArray("examples")
                    if (ex != null && ex.size() > 0) {
                        val e = stripHtml(ex.get(0).asString)
                        if (e.isNotEmpty()) sb.append("   e.g. \"").append(e).append("\"\n")
                    }
                    i++
                    count++
                }
            }
            if (count == 0) null else OnlineResult("Wiktionary", sb.toString().trim())
        } catch (e: Exception) {
            null
        }
    }

    /** Wikipedia summary, which covers modern scientific vocabulary well. */
    fun wikipedia(word: String): OnlineResult? {
        val direct = summary(word)
        if (direct != null) return direct
        // fall back to a search when the exact title does not exist
        val body = get(
            "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=" +
                "${enc(word)}&srlimit=1&format=json"
        ) ?: return null
        return try {
            val title = gson.fromJson(body, JsonObject::class.java)
                .getAsJsonObject("query").getAsJsonArray("search")
                .get(0).asJsonObject.get("title").asString
            summary(title)
        } catch (e: Exception) {
            null
        }
    }

    private fun summary(title: String): OnlineResult? {
        val body = get(
            "https://en.wikipedia.org/api/rest_v1/page/summary/${enc(title)}"
        ) ?: return null
        return try {
            val o = gson.fromJson(body, JsonObject::class.java)
            if (o.get("type")?.asString == "disambiguation") return null
            val extract = o.get("extract")?.asString?.trim() ?: return null
            if (extract.length < 20) return null
            val name = o.get("title")?.asString ?: title
            OnlineResult("Wikipedia", "$name\n\n$extract")
        } catch (e: Exception) {
            null
        }
    }

    /** Asks Gemini to explain the word as used in this particular sentence. */
    fun aiExplain(context: Context, word: String, sentence: String): OnlineResult? {
        val key = Prefs.apiKey(context)
        if (key.isEmpty()) return null
        return try {
            val prompt = buildString {
                append("Explain the word or term \"").append(word)
                append("\" for a science student, in 2 or 3 short sentences.")
                if (sentence.isNotBlank()) {
                    append(" Explain what it means in this passage:\n\n\"")
                    append(sentence.take(600)).append("\"")
                }
                append("\n\nWrite plain text with no LaTeX, no dollar signs and no markdown. ")
                append("Use Unicode subscripts for formulas, for example V₂O₅.")
            }
            val reply = GeminiClient.generate(key, Prefs.model(context), prompt)
            OnlineResult("AI explanation", TextFormat.markdownToPlain(reply))
        } catch (e: Exception) {
            OnlineResult("AI explanation", "Could not reach the AI: ${e.message}")
        }
    }
}
