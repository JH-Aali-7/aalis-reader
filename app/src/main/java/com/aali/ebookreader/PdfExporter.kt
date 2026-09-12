package com.aali.ebookreader

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns the highlights, notes and word list of a book into a clean, printable
 * PDF with a purple cover, section headings and page numbers.
 */
object PdfExporter {

    private const val PAGE_W = 595 // A4 at 72 dpi
    private const val PAGE_H = 842
    private const val MARGIN = 54f

    private val purple = Color.parseColor("#5B21B6")
    private val purpleLight = Color.parseColor("#7C3AED")
    private val ink = Color.parseColor("#1C1520")
    private val soft = Color.parseColor("#6B6376")

    private class Writer(private val doc: PdfDocument, private val title: String) {
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f
        var pageNo = 0

        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = 11.5f
        }
        val quote = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = 12f
        }
        val meta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = soft
            textSize = 9.5f
        }
        val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = purple
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = purpleLight }
        val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E3D8F5")
        }
        val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = soft
            textSize = 9f
        }

        fun newPage() {
            finishPage()
            pageNo++
            val p = doc.startPage(
                PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create()
            )
            page = p
            canvas = p.canvas
            y = MARGIN + 14f
            // running header
            canvas?.drawText(title.take(70), MARGIN, MARGIN - 16f, meta)
            canvas?.drawLine(MARGIN, MARGIN - 8f, PAGE_W - MARGIN, MARGIN - 8f, rule)
        }

        fun finishPage() {
            val p = page ?: return
            canvas?.drawText(
                "Page $pageNo", (PAGE_W / 2 - 16).toFloat(), (PAGE_H - 30).toFloat(), footer
            )
            doc.finishPage(p)
            page = null
            canvas = null
        }

        fun ensure(space: Float) {
            if (canvas == null || y + space > PAGE_H - MARGIN - 20f) newPage()
        }

        fun gap(h: Float) {
            y += h
        }

        fun section(text: String) {
            ensure(60f)
            y += 10f
            canvas?.drawRect(RectF(MARGIN, y - 11f, MARGIN + 3.5f, y + 5f), bar)
            canvas?.drawText(text, MARGIN + 12f, y + 3f, heading)
            y += 22f
        }

        /** Word wrapped paragraph. Returns the height used. */
        fun paragraph(text: String, paint: Paint, indent: Float = 0f, lineGap: Float = 3.5f) {
            val maxW = PAGE_W - MARGIN * 2 - indent
            for (rawLine in text.split('\n')) {
                if (rawLine.isBlank()) {
                    ensure(paint.textSize)
                    y += paint.textSize * 0.6f
                    continue
                }
                var line = StringBuilder()
                for (word in rawLine.split(' ')) {
                    val candidate = if (line.isEmpty()) word else "$line $word"
                    if (paint.measureText(candidate) > maxW && line.isNotEmpty()) {
                        ensure(paint.textSize + lineGap)
                        canvas?.drawText(line.toString(), MARGIN + indent, y, paint)
                        y += paint.textSize + lineGap
                        line = StringBuilder(word)
                    } else {
                        line = StringBuilder(candidate)
                    }
                }
                if (line.isNotEmpty()) {
                    ensure(paint.textSize + lineGap)
                    canvas?.drawText(line.toString(), MARGIN + indent, y, paint)
                    y += paint.textSize + lineGap
                }
            }
        }

        fun quoteBlock(text: String, label: String) {
            ensure(46f)
            val startY = y - 10f
            canvas?.drawText(label, MARGIN + 12f, y, meta)
            y += 14f
            paragraph(text, quote, indent = 12f)
            y += 4f
            canvas?.drawRect(RectF(MARGIN, startY, MARGIN + 2.5f, y - 8f), bar)
            y += 10f
        }

        fun divider() {
            ensure(14f)
            canvas?.drawLine(MARGIN, y, PAGE_W - MARGIN, y, rule)
            y += 12f
        }
    }

    fun export(context: Context, bookPath: String): File {
        Storage.ensureFolders()
        val db = Db.get(context)
        val title = File(bookPath).nameWithoutExtension
        val isPdf = bookPath.lowercase().endsWith(".pdf")
        val highlights = db.highlightsFor(bookPath)
        val notes = db.notesFor(bookPath)
        val words = db.wordsFor(bookPath)

        val doc = PdfDocument()
        val w = Writer(doc, title)

        // ---- cover ----
        w.newPage()
        w.canvas?.drawRect(RectF(0f, 0f, PAGE_W.toFloat(), 150f), Paint().apply { color = purple })
        w.canvas?.drawText(
            "Reading notes",
            MARGIN,
            72f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 13f
                letterSpacing = 0.16f
            }
        )
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        var ty = 106f
        var line = StringBuilder()
        for (word in title.split(' ')) {
            val cand = if (line.isEmpty()) word else "$line $word"
            if (titlePaint.measureText(cand) > PAGE_W - MARGIN * 2 && line.isNotEmpty()) {
                w.canvas?.drawText(line.toString(), MARGIN, ty, titlePaint)
                ty += 27f
                line = StringBuilder(word)
            } else line = StringBuilder(cand)
        }
        if (line.isNotEmpty()) w.canvas?.drawText(line.toString(), MARGIN, ty, titlePaint)

        w.y = 190f
        w.paragraph(
            "Exported from Aali's Reader on " +
                SimpleDateFormat("d MMMM yyyy, HH:mm", Locale.US).format(Date()),
            w.meta
        )
        w.gap(8f)
        w.paragraph(
            "${highlights.size} highlights   ·   ${notes.size} notes   ·   " +
                "${words.size} saved words",
            w.body
        )
        w.gap(6f)
        w.divider()

        // ---- highlights ----
        if (highlights.isNotEmpty()) {
            w.section("Highlights")
            for (h in highlights) {
                val label = if (isPdf) "Page ${h.page}"
                else "Chapter ${h.chapter + 1} · page ${h.page}"
                w.quoteBlock(h.text, label)
            }
        }

        // ---- notes ----
        if (notes.isNotEmpty()) {
            w.section("Notes")
            for (n in notes) {
                val label = if (isPdf) "Page ${n.page}"
                else "Chapter ${n.chapter + 1} · page ${n.page}"
                w.ensure(50f)
                w.canvas?.drawText(label, MARGIN, w.y, w.meta)
                w.y += 14f
                if (n.refText.isNotEmpty()) {
                    w.paragraph("“${n.refText.take(300)}”", w.meta, indent = 10f)
                    w.gap(3f)
                }
                w.paragraph(n.text, w.body)
                w.gap(10f)
                w.divider()
            }
        }

        // ---- word list ----
        if (words.isNotEmpty()) {
            w.section("Word list")
            for (v in words) {
                w.ensure(34f)
                w.canvas?.drawText(
                    v.word,
                    MARGIN,
                    w.y,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = purpleLight
                        textSize = 12.5f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    }
                )
                w.y += 15f
                if (v.meaning.isNotEmpty()) w.paragraph(v.meaning, w.meta, indent = 12f)
                w.gap(8f)
            }
        }

        if (highlights.isEmpty() && notes.isEmpty() && words.isEmpty()) {
            w.section("Nothing saved yet")
            w.paragraph(
                "Highlight some text, write a note or save a word, then export again.",
                w.body
            )
        }

        w.finishPage()

        val out = File(Storage.exports, Storage.safeName(title) + " - notes.pdf")
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
        return out
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(
            context, "com.aali.ebookreader.fileprovider", file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
