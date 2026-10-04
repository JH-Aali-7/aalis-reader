package com.aali.ebookreader

import java.text.Normalizer

/**
 * Everything the app needs to treat Urdu text properly: recognising it,
 * reducing spelling variants to one form so lookups match, and guessing the
 * dictionary form of an inflected word.
 *
 * The normalisation here must match `urnorm.py`, which built the bundled
 * dictionary, character for character.
 */
object UrduText {

    /** Vowel marks, tatweel and joiner controls, ignored when matching. */
    private val MARKS = Regex("[ؐ-ًؚ-ٰٟۖ-ۭـ‌‍‎‏]")

    /** Arabic letters typed in place of their Urdu forms, and other variants. */
    private val MAP = mapOf(
        'ي' to 'ی', 'ى' to 'ی', 'ې' to 'ی',
        'ك' to 'ک',
        'ه' to 'ہ', 'ە' to 'ہ', 'ۂ' to 'ہ',
        'ۃ' to 'ہ', 'ة' to 'ہ',
        'أ' to 'ا', 'إ' to 'ا', 'ٱ' to 'ا',
        'ۓ' to 'ے'
    )

    private val PUNCT = Regex(
        "[\\s،؛؟۔٪-٭\"'()\\[\\]{}.,;:!?«»“”‘’\\-–—_/\\\\*]+"
    )

    fun isArabicScript(c: Char): Boolean =
        c in '؀'..'ۿ' || c in 'ݐ'..'ݿ' ||
            c in 'ﭐ'..'﷿' || c in 'ﹰ'..'﻿'

    /** True when at least half of the letters are Urdu (Arabic script). */
    fun isUrdu(s: String): Boolean {
        var letters = 0
        var ar = 0
        for (c in s) {
            if (!c.isLetter()) continue
            letters++
            if (isArabicScript(c)) ar++
        }
        return letters > 0 && ar * 2 >= letters
    }

    /** Presentation forms (as found in many PDFs) turned back into letters. */
    fun unpresent(s: String): String {
        if (s.none { it in 'ﭐ'..'﷿' || it in 'ﹰ'..'﻿' }) return s
        return Normalizer.normalize(s, Normalizer.Form.NFKC)
    }

    fun normalize(raw: String): String {
        var s = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        s = MARKS.replace(s, "")
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(MAP[c] ?: c)
        return PUNCT.replace(sb, " ").trim().replace(Regex(" +"), " ")
    }

    /**
     * Possible dictionary forms of an inflected Urdu word, most likely first.
     * Covers plural and oblique nouns, feminine adjectives, verb participles
     * and the Arabic plural in -aat that fills academic Urdu.
     */
    fun lemmaCandidates(norm: String): List<String> {
        val w = norm
        val out = LinkedHashSet<String>()
        out.add(w)
        if (w.contains(' ')) out.add(w.replace(" ", ""))
        fun rep(suffix: String, vararg with: String) {
            if (w.length > suffix.length + 1 && w.endsWith(suffix)) {
                val stem = w.dropLast(suffix.length)
                for (x in with) out.add(stem + x)
            }
        }
        rep("یوں", "ی")              // لڑکیوں -> لڑکی
        rep("یاں", "ی")              // لڑکیاں -> لڑکی
        rep("ئیں", "", "ئی")          // ہوائیں -> ہوا
        rep("وں", "", "ا", "ہ")       // کتابوں -> کتاب, لڑکوں -> لڑکا, بچوں -> بچہ
        rep("یں", "", "")             // کتابیں -> کتاب
        rep("ات", "", "ہ")            // خیالات -> خیال, حادثات -> حادثہ
        rep("نے", "نا")              // کرنے -> کرنا
        rep("نی", "نا")              // کرنی -> کرنا
        rep("تا", "نا")              // کرتا -> کرنا
        rep("تی", "نا")
        rep("تے", "نا")
        rep("گا", "نا", "")
        rep("گی", "نا", "")
        rep("گے", "نا", "")
        rep("ے", "ا", "ہ", "")        // لڑکے -> لڑکا, بچے -> بچہ
        rep("ی", "ا", "")             // اچھی -> اچھا
        rep("ا", "نا")               // چلا -> چلنا
        rep("یا", "نا")              // بنایا -> بنانا
        rep("ئے", "نا", "")
        return out.toList()
    }

    // ------------------------------------------------ OCR clean up

    /** Letters the Urdu recogniser confuses with each other in Nastaliq. */
    private val CONFUSABLE = listOf(
        "دو", "رز", "جحخچ", "بپتٹثنی", "سش", "صض", "یےئ", "ہھ", "فق", "عغ", "طظ", "کگ"
    )

    /**
     * Repairs the two most common mistakes the recogniser makes on Nastaliq:
     * a space placed one letter too early ("انسا نکی" for "انسان کی"), and a
     * single letter misread as a look alike ("ووست" for "دوست"). A change is
     * only made when the dictionary confirms the result is a real word.
     */
    fun fixOcrLine(tokens: List<String>, known: (String) -> Boolean): List<String> {
        val out = tokens.toMutableList()
        fun isKnown(t: String): Boolean {
            val n = normalize(t)
            return n.isNotEmpty() && known(n)
        }
        var i = 0
        while (i < out.size) {
            val a = out[i]
            if (!isUrdu(a)) { i++; continue }
            if (!isKnown(a) && i + 1 < out.size) {
                val b = out[i + 1]
                if (b.length > 1 && isUrdu(b) && isKnown(a + b[0]) && isKnown(b.substring(1))) {
                    out[i] = a + b[0]
                    out[i + 1] = b.substring(1)
                    i++
                    continue
                }
            }
            if (!isKnown(a)) {
                val found = HashSet<String>()
                for ((k, ch) in a.withIndex()) {
                    val group = CONFUSABLE.firstOrNull { it.indexOf(ch) >= 0 } ?: continue
                    for (o in group) {
                        if (o == ch) continue
                        val cand = a.substring(0, k) + o + a.substring(k + 1)
                        if (isKnown(cand)) found.add(cand)
                    }
                    if (found.size > 1) break
                }
                if (found.size == 1) out[i] = found.first()
            }
            i++
        }
        return out
    }
}
