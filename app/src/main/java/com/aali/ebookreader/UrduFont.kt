package com.aali.ebookreader

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.MetricAffectingSpan

/**
 * The bundled Noto Nastaliq Urdu typeface (SIL Open Font License), so Urdu
 * looks like printed Urdu on every phone, not like Arabic Naskh.
 */
object UrduFont {

    @Volatile private var face: Typeface? = null

    fun get(context: Context): Typeface? {
        face?.let { return it }
        return try {
            Typeface.createFromAsset(context.assets, "fonts/NotoNastaliqUrdu.ttf").also { face = it }
        } catch (e: Exception) {
            null
        }
    }

    /** Applies the Urdu typeface to a run of text inside a TextView. */
    class Span(private val tf: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) = apply(tp)
        override fun updateMeasureState(tp: TextPaint) = apply(tp)
        private fun apply(p: Paint) {
            p.typeface = tf
        }
    }
}
