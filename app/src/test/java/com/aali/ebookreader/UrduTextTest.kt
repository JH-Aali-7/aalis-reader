package com.aali.ebookreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs on the computer, not the phone. Checks that the app's Urdu spelling
 * normaliser gives exactly the keys the bundled dictionary was built with,
 * that inflected words reach their dictionary form, and that the OCR repair
 * pass fixes the misreadings measured on real Tesseract output.
 */
class UrduTextTest {

    private fun resource(name: String): List<String> =
        javaClass.classLoader!!.getResourceAsStream(name)!!
            .bufferedReader(Charsets.UTF_8).readLines()

    @Test
    fun normaliseMatchesDictionaryKeys() {
        val pairs = resource("norm_pairs.tsv").filter { it.contains('\t') }
        var bad = 0
        val examples = ArrayList<String>()
        for (line in pairs) {
            val (word, key) = line.split('\t', limit = 2)
            val got = UrduText.normalize(word)
            if (got != key) {
                bad++
                if (examples.size < 5) examples.add("$word -> $got (want $key)")
            }
        }
        println("normalise: ${pairs.size - bad} of ${pairs.size} match")
        assertEquals("mismatches: $examples", 0, bad)
    }

    @Test
    fun spellingVariantsMatch() {
        assertEquals("کتاب", UrduText.normalize("كتاب"))      // Arabic kaf and yeh
        assertEquals("کتاب", UrduText.normalize("ﻛﺘﺎﺏ"))      // PDF presentation forms
        assertEquals("کتاب", UrduText.normalize("کِتاب"))     // vowel mark
        assertEquals("ہوا", UrduText.normalize("ہَوا۔"))       // full stop
        assertTrue(UrduText.isUrdu("پاکستان زندہ باد"))
        assertTrue(!UrduText.isUrdu("photosynthesis"))
    }

    @Test
    fun inflectedWordsReachTheirLemma() {
        val cases = mapOf(
            "کتابوں" to "کتاب", "کتابیں" to "کتاب", "لڑکیاں" to "لڑکی", "لڑکے" to "لڑکا",
            "بچوں" to "بچہ", "کرتے" to "کرنا", "کرنے" to "کرنا", "خیالات" to "خیال",
            "اچھی" to "اچھا", "لکھا" to "لکھنا"
        )
        for ((form, lemma) in cases) {
            val c = UrduText.lemmaCandidates(UrduText.normalize(form))
            assertTrue("$form should reach $lemma, got $c", lemma in c)
        }
    }

    @Test
    fun ocrRepairFixesNastaliqMisreadings() {
        val known = resource("known_words.txt").toHashSet()
        "کا کی کے کو سے میں نے ہے ہیں تھا تھی تھے اور پر یہ وہ جو کہ ہو ہوا ہوئی گیا گئی گئے بھی تو ایک کر کیا نہیں ان اس"
            .split(' ').forEach { known.add(UrduText.normalize(it)) }
        // real Tesseract output for a Nastaliq line rendered at about 290 dpi
        val raw = "علم حاص لکرنا ہر ملمان مرد اور عورت پر فرض ہے ۔کتاب انسا نکی بہترین ووست ہے۔"
        val fixed = UrduText.fixOcrLine(raw.split(' ')) { it in known }.joinToString(" ")
        println("ocr before: $raw")
        println("ocr after : $fixed")
        assertTrue(fixed, fixed.contains("حاصل کرنا"))
        assertTrue(fixed, fixed.contains("انسان کی"))
        assertTrue(fixed, fixed.contains("دوست"))
    }
}
