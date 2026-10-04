package com.aali.ebookreader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

/** One Urdu headword with its English meanings and any Urdu explanation. */
data class UrduEntry(
    val word: String,          // as written, with vowel marks when known
    val roman: String,
    val pos: String,
    val english: List<String>,
    val urdu: List<String>,
    val lemmaOf: String? = null // set when the word was found through its inflected form
)

/** An Urdu meaning of an English word, with its part of speech. */
data class UrduMeaning(val pos: String, val text: String)

/**
 * Offline Urdu dictionary, bundled as `urdu.db.gz` and built from English
 * Wiktionary (Urdu entries and translation tables) and Urdu Wiktionary:
 *
 *  - table `ur`: about 64,000 Urdu words, keyed by their normalised spelling,
 *    with English meanings and, where the source has them, Urdu meanings.
 *  - table `en_ur`: about 27,000 English words with their Urdu meanings.
 */
object UrduDictionary {

    private const val DB_VERSION = 1
    private const val DB_NAME = "urdu_v$DB_VERSION.db"

    @Volatile private var db: SQLiteDatabase? = null
    @Volatile private var wordSet: Set<String>? = null

    private fun open(context: Context): SQLiteDatabase? {
        db?.let { return it }
        synchronized(this) {
            db?.let { return it }
            return try {
                val target = File(context.filesDir, DB_NAME)
                if (!target.exists() || target.length() == 0L) {
                    context.filesDir.listFiles()?.forEach {
                        if (it.name.startsWith("urdu_v") && it.name != DB_NAME) it.delete()
                    }
                    val tmp = File(context.filesDir, "$DB_NAME.part")
                    // the Android build unpacks .gz assets and drops the extension,
                    // so the APK normally holds urdu.db; accept either
                    val names = context.assets.list("")?.toSet() ?: emptySet()
                    if (names.contains("urdu.db")) {
                        context.assets.open("urdu.db").use { raw ->
                            FileOutputStream(tmp).use { out -> raw.copyTo(out) }
                        }
                    } else {
                        context.assets.open("urdu.db.gz").use { raw ->
                            GZIPInputStream(raw).use { gz ->
                                FileOutputStream(tmp).use { out -> gz.copyTo(out) }
                            }
                        }
                    }
                    target.delete()
                    tmp.renameTo(target)
                }
                SQLiteDatabase.openDatabase(
                    target.absolutePath, null, SQLiteDatabase.OPEN_READONLY
                ).also { db = it }
            } catch (e: Exception) {
                null
            }
        }
    }

    fun warmUp(context: Context) {
        Thread { open(context) }.start()
    }

    private fun row(d: SQLiteDatabase, norm: String): UrduEntry? = try {
        d.rawQuery("SELECT word, roman, pos, en, ur FROM ur WHERE norm=?", arrayOf(norm)).use { c ->
            if (!c.moveToFirst()) null
            else UrduEntry(
                c.getString(0) ?: norm,
                c.getString(1) ?: "",
                c.getString(2) ?: "",
                (c.getString(3) ?: "").split('\n').filter { it.isNotBlank() },
                (c.getString(4) ?: "").split('\n').filter { it.isNotBlank() }
            )
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Looks an Urdu word or short phrase up. Tries the exact spelling, then
     * the dictionary forms of an inflected word, then the reversed string
     * (some PDFs store right to left text backwards).
     */
    fun lookup(context: Context, raw: String): UrduEntry? {
        val d = open(context) ?: return null
        val norm = UrduText.normalize(raw)
        if (norm.isEmpty()) return null
        for ((i, cand) in UrduText.lemmaCandidates(norm).withIndex()) {
            val e = row(d, cand) ?: continue
            return if (i == 0 || cand == norm) e else e.copy(lemmaOf = raw.trim())
        }
        val reversed = norm.reversed()
        if (reversed != norm) {
            for (cand in UrduText.lemmaCandidates(reversed)) {
                row(d, cand)?.let { return it }
            }
        }
        return null
    }

    /** Each word of an Urdu phrase that the dictionary knows, in order. */
    fun lookupWords(context: Context, raw: String): List<Pair<String, UrduEntry>> {
        val words = UrduText.normalize(raw).split(' ').filter { it.isNotBlank() }
        if (words.size < 2) return emptyList()
        return words.take(8).mapNotNull { w -> lookup(context, w)?.let { w to it } }
    }

    /**
     * Urdu meanings of an English word. Pass the forms worth trying, for
     * example the headword the English dictionary matched and the raw word.
     */
    fun englishToUrdu(context: Context, forms: List<String>): List<UrduMeaning> {
        val d = open(context) ?: return emptyList()
        for (f in forms) {
            val w = f.trim().lowercase()
            if (w.isEmpty()) continue
            try {
                d.rawQuery("SELECT ur FROM en_ur WHERE word=?", arrayOf(w)).use { c ->
                    if (c.moveToFirst()) {
                        val lines = (c.getString(0) ?: "").split('\n').filter { it.isNotBlank() }
                        if (lines.isNotEmpty()) {
                            return lines.map { l ->
                                val tab = l.indexOf('\t')
                                if (tab >= 0) UrduMeaning(l.substring(0, tab), l.substring(tab + 1).trim())
                                else UrduMeaning("", l.trim())
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return emptyList()
    }

    /**
     * Every single word Urdu headword, used to check and repair what the
     * text recogniser reads. Loaded once, on first use, in the background.
     */
    fun knownWords(context: Context): Set<String> {
        wordSet?.let { return it }
        synchronized(this) {
            wordSet?.let { return it }
            val set = HashSet<String>(90000)
            open(context)?.let { d ->
                try {
                    d.rawQuery("SELECT norm FROM ur", null).use { c ->
                        while (c.moveToNext()) {
                            val n = c.getString(0) ?: continue
                            if (n.indexOf(' ') < 0) set.add(n)
                        }
                    }
                } catch (_: Exception) {
                }
            }
            // short function words that a dictionary rarely lists on their own
            "کا کی کے کو سے میں نے ہے ہیں تھا تھی تھے اور پر یہ وہ جو کہ ہو ہوا ہوئی گیا گئی گئے بھی تو ایک کر کیا نہیں ان اس"
                .split(' ').forEach { set.add(UrduText.normalize(it)) }
            wordSet = set
            return set
        }
    }

    fun posLabel(pos: String): String = when (pos.lowercase()) {
        "noun", "n" -> "noun · اسم"
        "verb", "v" -> "verb · فعل"
        "adj", "adjective" -> "adjective · صفت"
        "adv", "adverb" -> "adverb · متعلق فعل"
        "pron" -> "pronoun · ضمیر"
        "name" -> "name · اسم معرفہ"
        "num" -> "number · عدد"
        "postp", "prep" -> "postposition · حرف جار"
        "conj" -> "conjunction · حرف عطف"
        "particle" -> "particle · حرف"
        "intj" -> "interjection · حرف ندا"
        else -> ""
    }
}
