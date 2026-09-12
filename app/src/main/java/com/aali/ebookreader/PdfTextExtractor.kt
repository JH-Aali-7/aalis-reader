package com.aali.ebookreader

import android.content.Context
import android.graphics.RectF
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** One character on the page. Spaces carry an empty rectangle. */
class PdfChar(val ch: Char, val rect: RectF, val isSpace: Boolean)

/** One word, used for tapping and for reading aloud. */
data class PdfWord(val text: String, val rect: RectF, val fromChar: Int, val toChar: Int)

/** Everything readable on a page, in reading order. */
class PdfPageText(
    val words: List<PdfWord>,
    val chars: List<PdfChar>,
    val text: String,
    val widthPt: Float,
    val heightPt: Float,
    val fromOcr: Boolean = false
) {
    companion object {
        val EMPTY = PdfPageText(emptyList(), emptyList(), "", 612f, 792f)
    }
}

/**
 * Reads text out of PDF pages with pdfbox-android, keeping the position of
 * every character so words can be tapped, selected letter by letter and
 * followed while being read aloud.
 *
 * Large files are parsed through a temporary file rather than memory, which
 * is what lets a 200 MB book open on a phone with little free RAM.
 */
object PdfTextExtractor {

    private const val MAX_WORD_PAGES = 6
    private const val MAX_TEXT_PAGES = 40

    private var openPath: String? = null
    private var doc: PDDocument? = null
    private var tempDir: File? = null

    /** Small caches that forget the least recently used page. */
    private val textCache = object : LinkedHashMap<Int, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?): Boolean =
            size > MAX_TEXT_PAGES
    }
    private val wordCache = object : LinkedHashMap<Int, PdfPageText>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Int, PdfPageText>?
        ): Boolean = size > MAX_WORD_PAGES
    }

    fun init(context: Context) {
        tempDir = context.cacheDir
        try {
            System.setProperty("java.io.tmpdir", context.cacheDir.absolutePath)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun pageCount(path: String): Int = try {
        ensure(path)?.numberOfPages ?: 0
    } catch (e: Throwable) {
        0
    }

    /** Plain text of a page, including anything recovered earlier by OCR. */
    @Synchronized
    fun pageText(path: String, page1: Int): String {
        if (openPath == path) textCache[page1]?.let { return it }
        val text = try {
            val d = ensure(path) ?: return ""
            val stripper = PDFTextStripper()
            stripper.startPage = page1
            stripper.endPage = page1
            stripper.getText(d).trim()
        } catch (e: Throwable) {
            ""
        }
        textCache[page1] = text
        return text
    }

    /** Characters and words with positions. Empty when the page holds no text. */
    @Synchronized
    fun pageWords(path: String, page1: Int): PdfPageText {
        if (openPath == path) wordCache[page1]?.let { return it }
        val result = try {
            val d = ensure(path) ?: return PdfPageText.EMPTY
            if (page1 < 1 || page1 > d.numberOfPages) return PdfPageText.EMPTY
            val page = d.getPage(page1 - 1)
            val box = page.cropBox ?: page.mediaBox
            val rot = ((page.rotation % 360) + 360) % 360
            val wPt = if (rot == 90 || rot == 270) box.height else box.width
            val hPt = if (rot == 90 || rot == 270) box.width else box.height

            val collector = CharCollector()
            collector.sortByPosition = true
            collector.startPage = page1
            collector.endPage = page1
            collector.getText(d)
            collector.build(wPt, hPt)
        } catch (e: Throwable) {
            // out of memory or a damaged page: carry on without the text layer
            PdfPageText.EMPTY
        }
        wordCache[page1] = result
        return result
    }

    /**
     * Builds a page from word boxes (used by OCR and by saved OCR results).
     * Character rectangles are spread evenly across each word so selection
     * still works letter by letter on a scanned page.
     */
    fun fromWordBoxes(
        boxes: List<Pair<String, RectF>>,
        widthPt: Float,
        heightPt: Float,
        fromOcr: Boolean = true
    ): PdfPageText {
        val sorted = boxes.sortedWith(
            compareBy({ (it.second.centerY() / 6f).toInt() }, { it.second.left })
        )
        val chars = ArrayList<PdfChar>()
        val words = ArrayList<PdfWord>()
        val sb = StringBuilder()
        for ((text, r) in sorted) {
            if (text.isEmpty()) continue
            if (chars.isNotEmpty()) {
                val prev = chars.last()
                chars.add(
                    PdfChar(' ', RectF(prev.rect.right, prev.rect.top,
                        prev.rect.right, prev.rect.bottom), true)
                )
                sb.append(' ')
            }
            val start = chars.size
            val each = (r.right - r.left) / text.length
            for ((i, c) in text.withIndex()) {
                val cl = r.left + each * i
                chars.add(PdfChar(c, RectF(cl, r.top, cl + each, r.bottom), false))
                sb.append(c)
            }
            words.add(PdfWord(text, RectF(r), start, chars.size - 1))
        }
        return PdfPageText(words, chars, sb.toString(), widthPt, heightPt, fromOcr)
    }

    /** Stores a page recovered by OCR so it behaves like a text page. */
    @Synchronized
    fun putOcrWords(page1: Int, data: PdfPageText, plainText: String) {
        wordCache[page1] = data
        textCache[page1] = plainText
    }

    @Synchronized
    fun hasCachedWords(page1: Int): Boolean = wordCache.containsKey(page1)

    @Synchronized
    fun close() {
        try {
            doc?.close()
        } catch (_: Exception) {
        }
        doc = null
        openPath = null
        textCache.clear()
        wordCache.clear()
    }

    /** Frees the page caches without closing the document. */
    @Synchronized
    fun trimMemory() {
        textCache.clear()
        wordCache.clear()
    }

    private fun ensure(path: String): PDDocument? {
        if (openPath != path) {
            close()
            // scratch data goes to a temp file, so a huge PDF never fills the heap
            val setting = MemoryUsageSetting.setupTempFileOnly()
            tempDir?.let { setting.setTempDir(it) }
            doc = PDDocument.load(File(path), setting)
            openPath = path
        }
        return doc
    }

    fun preloadOcr(context: Context, path: String) {
        try {
            for ((page, pair) in Db.get(context).allOcr(path)) {
                putOcrWords(page, pair.first, pair.second)
            }
        } catch (_: Exception) {
        }
    }

    fun splitSentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        val parts = cleaned.split(Regex("(?<=[.!?;:])\\s+"))
        val out = ArrayList<String>()
        var buf = StringBuilder()
        for (p in parts) {
            if (buf.length + p.length > 300 && buf.isNotEmpty()) {
                out.add(buf.toString().trim())
                buf = StringBuilder()
            }
            buf.append(p).append(' ')
        }
        if (buf.isNotBlank()) out.add(buf.toString().trim())
        return out.filter { it.length > 1 }
    }

    /** Builds a page of positioned characters and the words they form. */
    class CharCollector : PDFTextStripper() {

        private class Raw(val ch: Char, val l: Float, val t: Float, val r: Float, val b: Float)

        private val raw = ArrayList<Raw>()

        override fun writeString(text: String, textPositions: MutableList<TextPosition>?) {
            val list = textPositions ?: return
            for (tp in list) {
                val u = tp.unicode ?: continue
                if (u.isEmpty()) continue
                val h = tp.heightDir
                val x = tp.xDirAdj
                val y = tp.yDirAdj - h
                val w = tp.widthDirAdj
                // a TextPosition can carry a ligature such as "fi"
                val each = if (u.length > 1) w / u.length else w
                for ((k, c) in u.withIndex()) {
                    if (c.isWhitespace()) continue
                    val cl = x + each * k
                    raw.add(Raw(c, cl, y, cl + each, y + h))
                }
            }
        }

        /** Sorts into reading order and inserts the spaces between words. */
        fun build(widthPt: Float, heightPt: Float): PdfPageText {
            if (raw.isEmpty()) return PdfPageText(emptyList(), emptyList(), "", widthPt, heightPt)

            val sorted = raw.sortedWith(
                compareBy({ (((it.t + it.b) / 2f) / 6f).toInt() }, { it.l })
            )

            val chars = ArrayList<PdfChar>()
            val words = ArrayList<PdfWord>()
            val sb = StringBuilder()

            var wordStart = -1
            var wl = 0f
            var wt = 0f
            var wr = 0f
            var wb = 0f
            var wordSb = StringBuilder()

            fun closeWord() {
                if (wordStart >= 0 && wordSb.isNotEmpty()) {
                    words.add(
                        PdfWord(
                            wordSb.toString(), RectF(wl, wt, wr, wb),
                            wordStart, chars.size - 1
                        )
                    )
                }
                wordStart = -1
                wordSb = StringBuilder()
            }

            fun addSpace() {
                val prev = chars.lastOrNull() ?: return
                chars.add(PdfChar(' ', RectF(prev.rect.right, prev.rect.top,
                    prev.rect.right, prev.rect.bottom), true))
                sb.append(' ')
            }

            var prev: Raw? = null
            for (c in sorted) {
                val p = prev
                if (p != null) {
                    val lineChanged = kotlin.math.abs(
                        ((c.t + c.b) / 2f) - ((p.t + p.b) / 2f)
                    ) > (c.b - c.t) * 0.6f
                    val gap = c.l - p.r
                    if (lineChanged || gap > (c.b - c.t) * 0.32f) {
                        closeWord()
                        addSpace()
                    }
                }
                if (wordStart < 0) {
                    wordStart = chars.size
                    wl = c.l; wt = c.t; wr = c.r; wb = c.b
                } else {
                    wl = min(wl, c.l); wt = min(wt, c.t)
                    wr = max(wr, c.r); wb = max(wb, c.b)
                }
                chars.add(PdfChar(c.ch, RectF(c.l, c.t, c.r, c.b), false))
                sb.append(c.ch)
                wordSb.append(c.ch)
                prev = c
            }
            closeWord()

            return PdfPageText(words, chars, sb.toString(), widthPt, heightPt)
        }
    }
}
