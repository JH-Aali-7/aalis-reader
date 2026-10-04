package com.aali.ebookreader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

data class DictEntry(val word: String, val pos: String, val defs: String, val source: String = "")

/**
 * Offline dictionary: about 206,000 headwords built from WordNet, Wordset,
 * Webster's and a glossary of modern science and engineering terms.
 */
object DictionaryHelper {

    /** Bumped whenever the bundled database changes, so it is re-extracted. */
    private const val DB_VERSION = 3
    private const val DB_NAME = "dict_v$DB_VERSION.db"

    @Volatile private var db: SQLiteDatabase? = null

    private fun open(context: Context): SQLiteDatabase? {
        db?.let { return it }
        synchronized(this) {
            db?.let { return it }
            return try {
                val target = File(context.filesDir, DB_NAME)
                if (!target.exists() || target.length() == 0L) {
                    // remove databases left by older versions of the app
                    context.filesDir.listFiles()?.forEach {
                        if (it.name.startsWith("dict") && it.name.endsWith(".db") &&
                            it.name != DB_NAME
                        ) it.delete()
                    }
                    val names = context.assets.list("")?.toSet() ?: emptySet()
                    if (names.contains("dict.db")) {
                        context.assets.open("dict.db").use { raw ->
                            FileOutputStream(target).use { out -> raw.copyTo(out) }
                        }
                    } else {
                        context.assets.open("dict.db.gz").use { raw ->
                            GZIPInputStream(raw).use { gz ->
                                FileOutputStream(target).use { out -> gz.copyTo(out) }
                            }
                        }
                    }
                }
                SQLiteDatabase.openDatabase(
                    target.absolutePath, null, SQLiteDatabase.OPEN_READONLY
                ).also { db = it }
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Call once early (background thread) so the first lookup is instant. */
    fun warmUp(context: Context) {
        Thread {
            val d = open(context)
            try {
                d?.rawQuery("SELECT defs FROM dict WHERE word='the' LIMIT 1", null)
                    ?.use { it.moveToFirst() }
            } catch (_: Exception) {
            }
        }.start()
    }

    fun lookup(context: Context, rawWord: String): List<DictEntry> {
        val d = open(context) ?: return emptyList()
        val word = clean(rawWord)
        if (word.isEmpty()) return emptyList()
        for (candidate in candidates(word)) {
            val res = query(d, candidate)
            if (res.isNotEmpty()) return res
        }
        return emptyList()
    }

    fun clean(raw: String): String = raw.trim().lowercase().trim(
        '\'', '"', '.', ',', ';', ':', '!', '?', '(', ')', '[', ']',
        '“', '”', '‘', '’', '-', '—'
    )

    /**
     * Tidies a selection into something worth looking up. Keeps up to five
     * words so phrases such as "activation energy" or "le chatelier's
     * principle" are looked up whole rather than only their first word.
     */
    fun phrase(raw: String): String {
        val words = raw.replace(Regex("\\s+"), " ").trim().split(' ')
            .filter { it.isNotBlank() }
        if (words.isEmpty()) return ""
        return words.take(5).joinToString(" ").trim(
            '\'', '"', '.', ',', ';', ':', '!', '?', '(', ')', '[', ']',
            '“', '”', '‘', '’', '—'
        )
    }

    /** Splits a phrase into its individual words, for the fallback lookup. */
    fun wordsOf(raw: String): List<String> =
        raw.replace(Regex("[^A-Za-z0-9'’\\- ]"), " ")
            .split(Regex("\\s+"))
            .map { clean(it) }
            .filter { it.length > 1 }

    private fun query(d: SQLiteDatabase, w: String): List<DictEntry> {
        val out = ArrayList<DictEntry>()
        try {
            d.rawQuery(
                "SELECT word, pos, defs, src FROM dict WHERE word=?", arrayOf(w)
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        DictEntry(
                            c.getString(0), c.getString(1) ?: "x",
                            c.getString(2) ?: "", c.getString(3) ?: ""
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // an old database without the src column
            try {
                d.rawQuery("SELECT word, pos, defs FROM dict WHERE word=?", arrayOf(w)).use { c ->
                    while (c.moveToNext()) {
                        out.add(
                            DictEntry(c.getString(0), c.getString(1) ?: "x", c.getString(2) ?: "")
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }
        // a science reader wants the subject meaning first
        return out.sortedBy {
            when (it.source) {
                "science" -> 0
                "biology" -> 1
                "medicine" -> 2
                "webster" -> 4
                else -> 3
            }
        }
    }

    /**
     * Word forms to try. Covers ordinary English endings plus the Latin and
     * Greek plurals that fill scientific writing.
     */
    private fun candidates(w: String): List<String> {
        val list = LinkedHashSet<String>()
        list.add(w)
        irregular[w]?.let { list.add(it) }

        // scientific plurals
        if (w.endsWith("a") && w.length > 3) {
            list.add(w.dropLast(1) + "on")   // mitochondria -> mitochondrion
            list.add(w.dropLast(1) + "um")   // bacteria -> bacterium
        }
        if (w.endsWith("ae") && w.length > 3) list.add(w.dropLast(1))       // algae -> alga
        if (w.endsWith("i") && w.length > 3) list.add(w.dropLast(1) + "us") // nuclei -> nucleus
        if (w.endsWith("ices") && w.length > 5) {
            list.add(w.dropLast(4) + "ix")   // matrices -> matrix
            list.add(w.dropLast(4) + "ex")   // indices -> index
        }
        if (w.endsWith("es") && w.length > 4) list.add(w.dropLast(2) + "is") // analyses -> analysis
        if (w.endsWith("ata") && w.length > 5) list.add(w.dropLast(2))       // stomata -> stoma

        // ordinary English endings
        if (w.endsWith("ies") && w.length > 4) list.add(w.dropLast(3) + "y")
        if (w.endsWith("es") && w.length > 3) {
            list.add(w.dropLast(2))
            list.add(w.dropLast(1))
        }
        if (w.endsWith("s") && w.length > 3) list.add(w.dropLast(1))
        if (w.endsWith("ing") && w.length > 5) {
            list.add(w.dropLast(3))
            list.add(w.dropLast(3) + "e")
            if (w.length > 6 && w[w.length - 4] == w[w.length - 5]) list.add(w.dropLast(4))
        }
        if (w.endsWith("ed") && w.length > 4) {
            list.add(w.dropLast(2))
            list.add(w.dropLast(1))
            if (w.length > 5 && w[w.length - 3] == w[w.length - 4]) list.add(w.dropLast(3))
        }
        if (w.endsWith("er") && w.length > 4) {
            list.add(w.dropLast(2))
            list.add(w.dropLast(1))
        }
        if (w.endsWith("est") && w.length > 5) {
            list.add(w.dropLast(3))
            list.add(w.dropLast(2))
        }
        if (w.endsWith("ly") && w.length > 4) list.add(w.dropLast(2))
        if (w.endsWith("ally") && w.length > 6) list.add(w.dropLast(4) + "al")
        if (w.endsWith("ity") && w.length > 5) list.add(w.dropLast(3) + "e")

        // hyphenated or spaced compounds: try the base word
        if (w.contains('-')) list.add(w.replace("-", " "))
        if (w.contains(' ')) list.add(w.replace(" ", "-"))
        return list.toList()
    }

    private val irregular = mapOf(
        "men" to "man", "women" to "woman", "children" to "child", "people" to "person",
        "feet" to "foot", "teeth" to "tooth", "mice" to "mouse", "geese" to "goose",
        "went" to "go", "gone" to "go", "was" to "be", "were" to "be", "is" to "be",
        "are" to "be", "am" to "be", "been" to "be", "had" to "have", "has" to "have",
        "did" to "do", "done" to "do", "said" to "say", "made" to "make", "took" to "take",
        "taken" to "take", "came" to "come", "saw" to "see", "seen" to "see",
        "knew" to "know", "known" to "know", "got" to "get", "gave" to "give",
        "given" to "give", "found" to "find", "thought" to "think", "told" to "tell",
        "became" to "become", "left" to "leave", "felt" to "feel", "put" to "put",
        "brought" to "bring", "began" to "begin", "begun" to "begin", "kept" to "keep",
        "held" to "hold", "wrote" to "write", "written" to "write", "stood" to "stand",
        "heard" to "hear", "let" to "let", "meant" to "mean", "met" to "meet",
        "ran" to "run", "paid" to "pay", "sat" to "sit", "spoke" to "speak",
        "spoken" to "speak", "lay" to "lie", "led" to "lead", "read" to "read",
        "grew" to "grow", "grown" to "grow", "lost" to "lose", "fell" to "fall",
        "fallen" to "fall", "sent" to "send", "built" to "build",
        "understood" to "understand", "drew" to "draw", "drawn" to "draw",
        "broke" to "break", "broken" to "break", "spent" to "spend", "cut" to "cut",
        "rose" to "rise", "risen" to "rise", "drove" to "drive", "driven" to "drive",
        "bought" to "buy", "wore" to "wear", "worn" to "wear", "chose" to "choose",
        "chosen" to "choose", "ate" to "eat", "eaten" to "eat", "flew" to "fly",
        "flown" to "fly", "threw" to "throw", "thrown" to "throw", "caught" to "catch",
        "taught" to "teach", "sold" to "sell", "fought" to "fight", "sought" to "seek",
        "slept" to "sleep", "won" to "win", "better" to "good", "best" to "good",
        "worse" to "bad", "worst" to "bad",
        // scientific irregulars
        "phenomena" to "phenomenon", "criteria" to "criterion", "data" to "datum",
        "bacteria" to "bacterium", "fungi" to "fungus", "nuclei" to "nucleus",
        "stimuli" to "stimulus", "radii" to "radius", "foci" to "focus",
        "vertices" to "vertex", "indices" to "index", "matrices" to "matrix",
        "analyses" to "analysis", "hypotheses" to "hypothesis", "theses" to "thesis",
        "axes" to "axis", "bases" to "basis", "crises" to "crisis", "larvae" to "larva",
        "algae" to "alga", "vertebrae" to "vertebra", "formulae" to "formula",
        "spectra" to "spectrum", "maxima" to "maximum", "minima" to "minimum",
        "strata" to "stratum", "genera" to "genus", "species" to "species",
        "apices" to "apex", "appendices" to "appendix", "cortices" to "cortex",
        "alumni" to "alumnus", "cacti" to "cactus", "syllabi" to "syllabus"
    )

    /**
     * A one line meaning for word lists and saved files. Urdu words get their
     * English meaning; English words get their Urdu meaning added when known.
     */
    fun shortMeaning(context: Context, word: String): String {
        if (UrduText.isUrdu(word)) {
            val e = UrduDictionary.lookup(context, word) ?: return ""
            return listOfNotNull(e.english.firstOrNull(), e.urdu.firstOrNull()).joinToString("  ·  ")
        }
        val e = lookup(context, word).firstOrNull()
        val en = e?.let {
            val p = posLabel(it.pos)
            (if (p.isEmpty()) "" else "$p: ") +
                (it.defs.lineSequence().firstOrNull()?.removePrefix("1. ") ?: "")
        } ?: ""
        val ur = if (Prefs.urduMeanings(context)) {
            UrduDictionary.englishToUrdu(context, listOfNotNull(e?.word, clean(word)))
                .firstOrNull()?.text ?: ""
        } else ""
        return listOf(en, if (ur.isEmpty()) "" else "اردو: $ur")
            .filter { it.isNotBlank() }.joinToString("  ·  ")
    }

    fun posLabel(pos: String): String = when (pos) {
        "n" -> "noun"
        "v" -> "verb"
        "a", "s" -> "adjective"
        "r" -> "adverb"
        "p" -> "word"
        else -> ""
    }

    fun sourceLabel(src: String): String = when (src) {
        "science" -> "science glossary"
        "biology" -> "biology"
        "medicine" -> "medicine"
        "wordnet" -> "WordNet"
        "wordset" -> "Wordset"
        "webster" -> "Webster's"
        else -> ""
    }
}
