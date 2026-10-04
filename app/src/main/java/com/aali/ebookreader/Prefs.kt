package com.aali.ebookreader

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

object Prefs {
    fun sp(c: Context): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(c)

    const val DEFAULT_MODEL = "gemini-flash-latest"

    fun apiKey(c: Context): String = sp(c).getString("gemini_key", "")!!.trim()

    fun model(c: Context): String =
        sp(c).getString("gemini_model", DEFAULT_MODEL)!!.trim()
            .removePrefix("models/")
            .ifEmpty { DEFAULT_MODEL }

    fun setModel(c: Context, m: String) =
        sp(c).edit().putString("gemini_model", m.removePrefix("models/")).apply()

    fun ttsRate(c: Context): Float = sp(c).getInt("tts_rate", 100) / 100f
    fun ttsPitch(c: Context): Float = sp(c).getInt("tts_pitch", 100) / 100f

    fun fontSize(c: Context): Int = sp(c).getInt("font_size", 20)
    fun setFontSize(c: Context, v: Int) = sp(c).edit().putInt("font_size", v).apply()

    fun lineHeight(c: Context): Int = sp(c).getInt("line_height", 160)
    fun setLineHeight(c: Context, v: Int) = sp(c).edit().putInt("line_height", v).apply()

    fun margin(c: Context): Int = sp(c).getInt("margin", 20)
    fun setMargin(c: Context, v: Int) = sp(c).edit().putInt("margin", v).apply()

    /** 0 = light, 1 = sepia, 2 = dark, 3 = lavender */
    fun readerTheme(c: Context): Int = sp(c).getInt("reader_theme", 3)
    fun setReaderTheme(c: Context, v: Int) = sp(c).edit().putInt("reader_theme", v).apply()

    /** PDF page layout: 0 = horizontal pages, 1 = vertical pages, 2 = continuous scroll */
    fun pdfMode(c: Context): Int = sp(c).getInt("pdf_mode", 2)
    fun setPdfMode(c: Context, v: Int) = sp(c).edit().putInt("pdf_mode", v).apply()

    fun keepScreenOn(c: Context): Boolean = sp(c).getBoolean("keep_screen_on", true)

    fun tapWordInPdf(c: Context): Boolean = sp(c).getBoolean("tap_word_pdf", true)

    /** Auto scroll reading speed in words per minute. */
    fun autoWpm(c: Context): Int = sp(c).getInt("auto_wpm", 250)
    fun setAutoWpm(c: Context, v: Int) = sp(c).edit().putInt("auto_wpm", v).apply()
    fun autoWpmChosen(c: Context): Boolean = sp(c).contains("auto_wpm")

    /** Show the Urdu meaning under English definitions. */
    fun urduMeanings(c: Context): Boolean = sp(c).getBoolean("urdu_meanings", true)

    /** Read Urdu passages aloud in an Urdu voice when the phone has one. */
    fun urduVoice(c: Context): Boolean = sp(c).getBoolean("urdu_voice", true)

    /** Text recognition language chosen for a book: "eng" or "urd". */
    fun ocrLang(c: Context, book: String): String =
        sp(c).getString("ocr_lang:" + book.hashCode(), "")!!
    fun setOcrLang(c: Context, book: String, lang: String) =
        sp(c).edit().putString("ocr_lang:" + book.hashCode(), lang).apply()
}
