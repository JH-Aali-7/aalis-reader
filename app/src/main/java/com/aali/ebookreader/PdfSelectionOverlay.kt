package com.aali.ebookreader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.github.barteksc.pdfviewer.PDFView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Transparent layer over the PDF. It knows where every character sits, so the
 * reader can tap a word for its meaning and drag across the page to select any
 * run of text, down to a single letter, just like a normal document.
 *
 * While nothing is selected every touch passes through to the PDF view below,
 * so scrolling and zooming behave normally.
 */
class PdfSelectionOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Host {
        fun wordsForPage(page: Int): PdfPageText?
        fun highlightsForPage(page: Int): List<String>
        fun onSelectionChanged(text: String, active: Boolean)
    }

    var host: Host? = null
    private var pdfView: PDFView? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#557C3AED")
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66F5C518")
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7C3AED")
    }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val speakSentencePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E7C3AED")
    }
    private val speakWordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4C7C3AED")
    }
    private val speakUnderline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7C3AED")
    }

    // character indices
    private var selPage = -1
    private var selStart = -1
    private var selEnd = -1
    private var dragging = 0 // 0 none, 1 start handle, 2 end handle

    private var speakPage = -1
    private var speakSentFrom = -1
    private var speakSentTo = -1
    private var speakWordFrom = -1
    private var speakWordTo = -1

    private val handleRadius = 13f * resources.displayMetrics.density

    val hasSelection: Boolean get() = selPage >= 0 && selStart >= 0 && selEnd >= 0

    fun attach(view: PDFView) {
        pdfView = view
    }

    // ------------------------------------------------ geometry

    private class PageBox(val page: Int, val left: Float, val top: Float,
                          val w: Float, val h: Float)

    private fun pageBox(page: Int): PageBox? {
        val v = pdfView ?: return null
        return try {
            val zoom = v.zoom
            val size = v.getPageSize(page) ?: return null
            val w = size.width * zoom
            val h = size.height * zoom
            val main = PdfGeometry.pageOffset(v, page, zoom)
            val secondary = PdfGeometry.secondaryOffset(v, page, zoom)
            if (v.isSwipeVertical) {
                PageBox(page, v.currentXOffset + secondary, v.currentYOffset + main, w, h)
            } else {
                PageBox(page, v.currentXOffset + main, v.currentYOffset + secondary, w, h)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun visiblePages(): List<PageBox> {
        val v = pdfView ?: return emptyList()
        val out = ArrayList<PageBox>()
        val current = try {
            v.currentPage
        } catch (e: Exception) {
            0
        }
        for (p in (current - 2)..(current + 3)) {
            if (p < 0 || p >= v.pageCount) continue
            val box = pageBox(p) ?: continue
            if (box.top < height && box.top + box.h > 0 &&
                box.left < width && box.left + box.w > 0
            ) out.add(box)
        }
        return out
    }

    private fun toScreen(box: PageBox, data: PdfPageText, r: RectF): RectF {
        val sx = box.w / data.widthPt
        val sy = box.h / data.heightPt
        return RectF(
            box.left + r.left * sx, box.top + r.top * sy,
            box.left + r.right * sx, box.top + r.bottom * sy
        )
    }

    /** Nearest real character to a screen point, as (page, char index). */
    private fun charAt(x: Float, y: Float, maxMissDp: Float = 26f): Pair<Int, Int> {
        for (box in visiblePages()) {
            if (x < box.left || x > box.left + box.w) continue
            if (y < box.top || y > box.top + box.h) continue
            val data = host?.wordsForPage(box.page) ?: continue
            if (data.chars.isEmpty()) continue
            var best = -1
            var bestDist = Float.MAX_VALUE
            for ((i, c) in data.chars.withIndex()) {
                if (c.isSpace) continue
                val r = toScreen(box, data, c.rect)
                if (r.contains(x, y)) return box.page to i
                val cx = x.coerceIn(r.left, r.right)
                val cy = y.coerceIn(r.top, r.bottom)
                val d = hypot(x - cx, y - cy)
                if (d < bestDist) {
                    bestDist = d
                    best = i
                }
            }
            if (best >= 0 && bestDist < maxMissDp * resources.displayMetrics.density) {
                return box.page to best
            }
            return box.page to -1
        }
        return -1 to -1
    }

    /** The whole word under a point, for tap to define. */
    fun wordTextAt(x: Float, y: Float): String? {
        val (page, idx) = charAt(x, y)
        if (page < 0 || idx < 0) return null
        val data = host?.wordsForPage(page) ?: return null
        return data.words.firstOrNull { idx in it.fromChar..it.toChar }?.text
    }

    /** Text around a point, so an AI explanation has some context. */
    fun contextAt(x: Float, y: Float): String {
        val (page, idx) = charAt(x, y)
        if (page < 0 || idx < 0) return ""
        val data = host?.wordsForPage(page) ?: return ""
        val a = (idx - 260).coerceAtLeast(0)
        val b = (idx + 300).coerceAtMost(data.text.length)
        return if (a < b) data.text.substring(a, b) else ""
    }

    fun selectionContext(): String {
        if (!hasSelection) return ""
        val data = host?.wordsForPage(selPage) ?: return ""
        val a = (min(selStart, selEnd) - 200).coerceAtLeast(0)
        val b = (max(selStart, selEnd) + 240).coerceAtMost(data.text.length)
        return if (a < b) data.text.substring(a, b) else ""
    }

    // ------------------------------------------------ read aloud tracking

    fun setSpeaking(page: Int, sentFrom: Int, sentTo: Int, wordFrom: Int, wordTo: Int) {
        speakPage = page
        speakSentFrom = sentFrom
        speakSentTo = sentTo
        speakWordFrom = wordFrom
        speakWordTo = wordTo
        invalidate()
    }

    fun clearSpeaking() {
        speakPage = -1
        speakSentFrom = -1
        speakSentTo = -1
        speakWordFrom = -1
        speakWordTo = -1
        invalidate()
    }

    /** Screen rectangle of a character range, so the reader can scroll to it. */
    fun rangeRectOnScreen(page: Int, from: Int, to: Int): RectF? {
        val box = pageBox(page) ?: return null
        val data = host?.wordsForPage(page) ?: return null
        val rects = rectsFor(box, data, from, to)
        if (rects.isEmpty()) return null
        val out = RectF(rects.first())
        for (r in rects) out.union(r)
        return out
    }

    private fun rectsFor(
        box: PageBox,
        data: PdfPageText,
        from: Int,
        to: Int
    ): List<RectF> {
        if (data.chars.isEmpty()) return emptyList()
        val a = from.coerceIn(0, data.chars.size - 1)
        val b = to.coerceIn(a, data.chars.size - 1)
        val list = ArrayList<RectF>()
        for (i in a..b) {
            val c = data.chars[i]
            if (c.isSpace) continue
            list.add(toScreen(box, data, c.rect))
        }
        return mergeLines(list)
    }

    private fun mergeLines(rects: List<RectF>): List<RectF> {
        if (rects.isEmpty()) return rects
        val out = ArrayList<RectF>()
        var cur = RectF(rects[0])
        for (i in 1 until rects.size) {
            val r = rects[i]
            val sameLine = abs(r.centerY() - cur.centerY()) < cur.height() * 0.7f
            if (sameLine && r.left >= cur.left - 4f && r.left - cur.right < cur.height() * 1.6f) {
                cur.union(r)
            } else {
                out.add(cur)
                cur = RectF(r)
            }
        }
        out.add(cur)
        return out
    }

    // ------------------------------------------------ selection

    /** Starts a selection on the whole word under the finger. */
    fun startSelectionAt(x: Float, y: Float): Boolean {
        val (page, idx) = charAt(x, y)
        if (page < 0 || idx < 0) return false
        val data = host?.wordsForPage(page) ?: return false
        val w = data.words.firstOrNull { idx in it.fromChar..it.toChar }
        selPage = page
        selStart = w?.fromChar ?: idx
        selEnd = w?.toChar ?: idx
        notifySelection(true)
        invalidate()
        return true
    }

    fun clearSelection() {
        if (!hasSelection) return
        selPage = -1
        selStart = -1
        selEnd = -1
        dragging = 0
        notifySelection(false)
        invalidate()
    }

    fun selectedText(): String {
        if (!hasSelection) return ""
        val data = host?.wordsForPage(selPage) ?: return ""
        val a = min(selStart, selEnd).coerceIn(0, data.text.length - 1)
        val b = max(selStart, selEnd).coerceIn(a, data.text.length - 1)
        return data.text.substring(a, b + 1).trim()
    }

    fun selectedPage(): Int = selPage

    private fun notifySelection(active: Boolean) {
        host?.onSelectionChanged(selectedText(), active)
    }

    private fun selectionRects(): List<RectF> {
        if (!hasSelection) return emptyList()
        val box = pageBox(selPage) ?: return emptyList()
        val data = host?.wordsForPage(selPage) ?: return emptyList()
        return rectsFor(box, data, min(selStart, selEnd), max(selStart, selEnd))
    }

    // ------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // saved highlights
        for (box in visiblePages()) {
            val texts = host?.highlightsForPage(box.page) ?: continue
            if (texts.isEmpty()) continue
            val data = host?.wordsForPage(box.page) ?: continue
            if (data.chars.isEmpty()) continue
            for (t in texts) {
                for ((from, to) in findRanges(data, t)) {
                    for (r in rectsFor(box, data, from, to)) {
                        canvas.drawRoundRect(inflate(r), 3f, 3f, highlightPaint)
                    }
                }
            }
        }

        // words being read aloud
        if (speakPage >= 0 && speakSentFrom >= 0) {
            val box = pageBox(speakPage)
            val data = host?.wordsForPage(speakPage)
            if (box != null && data != null && data.chars.isNotEmpty()) {
                for (r in rectsFor(box, data, speakSentFrom, speakSentTo)) {
                    canvas.drawRoundRect(inflate(r), 4f, 4f, speakSentencePaint)
                }
                if (speakWordFrom >= 0) {
                    for (r in rectsFor(box, data, speakWordFrom, speakWordTo)) {
                        canvas.drawRoundRect(inflate(r), 3f, 3f, speakWordPaint)
                        canvas.drawRect(
                            r.left, r.bottom + 1f, r.right, r.bottom + 3.5f, speakUnderline
                        )
                    }
                }
            }
        }

        // live selection
        val sel = selectionRects()
        if (sel.isNotEmpty()) {
            for (r in sel) canvas.drawRoundRect(inflate(r), 3f, 3f, fillPaint)
            val first = sel.first()
            val last = sel.last()
            drawHandle(canvas, first.left, first.bottom)
            drawHandle(canvas, last.right, last.bottom)
        }
    }

    private fun inflate(r: RectF): RectF =
        RectF(r.left - 1.5f, r.top - 1.5f, r.right + 1.5f, r.bottom + 1.5f)

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y + handleRadius * 0.55f, handleRadius, handlePaint)
        canvas.drawCircle(x, y + handleRadius * 0.55f, handleRadius, handleRing)
    }

    /** Character ranges on this page whose text matches a saved highlight. */
    private fun findRanges(data: PdfPageText, text: String): List<Pair<Int, Int>> {
        val needle = text.replace(Regex("\\s+"), " ").trim()
        if (needle.length < 2) return emptyList()
        val out = ArrayList<Pair<Int, Int>>()
        var from = 0
        while (out.size < 40) {
            val i = data.text.indexOf(needle, from, ignoreCase = true)
            if (i < 0) break
            out.add(i to i + needle.length - 1)
            from = i + needle.length
        }
        return out
    }

    // ------------------------------------------------ touch

    private val down = PointF()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!hasSelection) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                down.set(event.x, event.y)
                val sel = selectionRects()
                if (sel.isNotEmpty()) {
                    val a = sel.first()
                    val b = sel.last()
                    val dStart = hypot(event.x - a.left, event.y - a.bottom)
                    val dEnd = hypot(event.x - b.right, event.y - b.bottom)
                    val grab = handleRadius * 2.6f
                    dragging = when {
                        dStart < grab && dStart <= dEnd -> 1
                        dEnd < grab -> 2
                        else -> 0
                    }
                }
                return dragging != 0
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging == 0) return false
                val (page, idx) = charAt(event.x, event.y, maxMissDp = 40f)
                if (page == selPage && idx >= 0) {
                    if (dragging == 1) selStart = idx else selEnd = idx
                    notifySelection(true)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = dragging != 0
                dragging = 0
                if (was) {
                    notifySelection(true)
                    return true
                }
                return false
            }
        }
        return false
    }

    /** Extends the selection to the character under a tap. */
    fun extendTo(x: Float, y: Float): Boolean {
        if (!hasSelection) return false
        val (page, idx) = charAt(x, y, maxMissDp = 40f)
        if (page != selPage || idx < 0) return false
        selEnd = idx
        notifySelection(true)
        invalidate()
        return true
    }
}
