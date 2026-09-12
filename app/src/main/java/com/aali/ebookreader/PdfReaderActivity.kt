package com.aali.ebookreader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle
import java.io.File

class PdfReaderActivity : AppCompatActivity(), PdfSelectionOverlay.Host {

    private lateinit var pdfView: PDFView
    private lateinit var overlay: PdfSelectionOverlay
    private lateinit var selectionBar: HorizontalScrollView
    private lateinit var selectionButtons: LinearLayout
    private lateinit var hintChip: TextView
    private lateinit var txtPage: TextView
    private lateinit var btnTts: ImageButton
    private lateinit var btnAuto: ImageButton
    private lateinit var toolbar: Toolbar
    private lateinit var bottomBar: View
    private lateinit var bookFile: File
    private lateinit var bookPath: String

    private var currentPage = 0 // zero based
    private var pageCount = 0
    private var nightMode = false
    private var immersive = false
    private var tts: TtsManager? = null
    private var ttsReadingPage = -1
    private var timer: ReadingTimer? = null

    /** 0 = horizontal pages, 1 = vertical pages, 2 = continuous vertical scroll */
    private var viewMode = 0

    private var autoOn = false
    private var pxPerSec = 0f
    private val handler = Handler(Looper.getMainLooper())

    /** One spoken sentence, and the characters it covers on the page. */
    private class SpeechBlock(
        val page1: Int,
        /** index in the page text where this sentence starts, or -1 */
        val fromChar: Int,
        val toChar: Int,
        val text: String
    )

    private var speechBlocks: List<SpeechBlock> = emptyList()
    private var followVoice = true

    /** Books above this size get a lighter touch so small phones cope. */
    private val hugeBook: Boolean by lazy { bookFile.length() > 60L * 1024 * 1024 }

    private val wordsByPage = HashMap<Int, PdfPageText>()
    private val loadingPages = HashSet<Int>()
    private val highlightsByPage = HashMap<Int, List<String>>()

    private val flipNext = Runnable {
        if (autoOn) {
            if (currentPage < pageCount - 1) pdfView.jumpTo(currentPage + 1, true)
            else stopAuto("End of book")
        }
    }

    private val scrollTick = object : Runnable {
        override fun run() {
            if (!autoOn) return
            try {
                pdfView.moveRelativeTo(0f, -pxPerSec * TICK_MS / 1000f)
                pdfView.loadPages()
                overlay.invalidate()
                if (pdfView.positionOffset >= 0.9995f) {
                    stopAuto("End of book")
                    return
                }
            } catch (_: Exception) {
            }
            handler.postDelayed(this, TICK_MS.toLong())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_reader)

        bookPath = intent.getStringExtra("path") ?: run { finish(); return }
        bookFile = File(bookPath)
        viewMode = Prefs.pdfMode(this)
        timer = ReadingTimer(this, bookPath)

        toolbar = findViewById(R.id.toolbar)
        bottomBar = findViewById(R.id.bottomBar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        supportActionBar?.title = bookFile.nameWithoutExtension

        pdfView = findViewById(R.id.pdfView)
        overlay = findViewById(R.id.overlay)
        selectionBar = findViewById(R.id.selectionBar)
        selectionButtons = findViewById(R.id.selectionButtons)
        hintChip = findViewById(R.id.hintChip)
        txtPage = findViewById(R.id.txtPage)
        btnTts = findViewById(R.id.btnTts)
        btnAuto = findViewById(R.id.btnAuto)

        overlay.host = this
        overlay.attach(pdfView)
        buildSelectionBar()

        if (Prefs.keepScreenOn(this)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        tts = TtsManager(this)
        tts?.listener = object : TtsManager.Listener {
            override fun onBlockStarted(index: Int) {
                val b = speechBlocks.getOrNull(index)
                if (b != null && b.fromChar >= 0) {
                    overlay.setSpeaking(b.page1 - 1, b.fromChar, b.toChar, -1, -1)
                    if (b.page1 - 1 != currentPage) pdfView.jumpTo(b.page1 - 1, false)
                    scrollRangeIntoView(b.page1 - 1, b.fromChar, b.toChar)
                }
                updateTtsButton()
            }

            override fun onWordRange(index: Int, start: Int, end: Int) {
                val b = speechBlocks.getOrNull(index) ?: return
                if (b.fromChar < 0) return
                // the sentence text was taken straight from the page, so the
                // character offsets the voice reports line up exactly
                val wFrom = b.fromChar + start
                val wTo = b.fromChar + (end - 1).coerceAtLeast(start)
                overlay.setSpeaking(b.page1 - 1, b.fromChar, b.toChar, wFrom, wTo)
                scrollRangeIntoView(b.page1 - 1, wFrom, wTo)
            }

            override fun onFinishedAll() {
                if (ttsReadingPage in 0 until pageCount - 1) {
                    val next = ttsReadingPage + 1
                    runOnUiThread { pdfView.jumpTo(next, true) }
                    speakPage(next)
                } else {
                    ttsReadingPage = -1
                    overlay.clearSpeaking()
                    updateTtsButton()
                }
            }
        }
        DictionaryHelper.warmUp(this)
        Thread { PdfTextExtractor.preloadOcr(this, bookPath) }.start()
        reloadHighlights()

        val prog = Db.get(this).getProgress(bookPath)
        val startPage = prog?.page ?: 0

        findViewById<ImageButton>(R.id.btnJump).setOnClickListener { showJumpDialog() }
        findViewById<ImageButton>(R.id.btnMode).setOnClickListener { showModeDialog() }
        findViewById<ImageButton>(R.id.btnTextMode).setOnClickListener { openTextMode() }
        findViewById<ImageButton>(R.id.btnNight).setOnClickListener {
            nightMode = !nightMode
            loadPdf(currentPage)
        }
        btnTts.setOnClickListener { toggleTts() }
        btnAuto.setOnClickListener { toggleAuto() }
        btnAuto.setOnLongClickListener {
            SpeedDialog.show(this) { startAuto() }
            true
        }

        loadPdf(startPage)
        showHint("Tap a word for its meaning · hold to select", 4200)
    }

    // ------------------------------------------------ loading

    private fun loadPdf(page: Int) {
        val wasAuto = autoOn
        stopAuto(null)
        overlay.clearSelection()
        val continuous = viewMode == 2
        pdfView.fromFile(bookFile)
            .defaultPage(page)
            .swipeHorizontal(viewMode == 0)
            .pageSnap(!continuous)
            .autoSpacing(!continuous)
            .pageFling(!continuous)
            .spacing(if (continuous) 4 else 6)
            .nightMode(nightMode)
            .enableAntialiasing(true)
            .scrollHandle(DefaultScrollHandle(this))
            .onTap { e ->
                if (overlay.hasSelection) {
                    // a tap while selecting either extends it or clears it
                    if (!overlay.extendTo(e.x, e.y)) clearSelection()
                    return@onTap true
                }
                val word = if (Prefs.tapWordInPdf(this)) {
                    overlay.wordTextAt(e.x, e.y)
                } else null
                if (word != null) {
                    DefinitionPopup.show(
                        this, word, tts, bookPath, overlay.contextAt(e.x, e.y),
                        onHighlightWord = { w -> saveHighlight(w) }
                    )
                } else {
                    applyImmersiveMode(!immersive)
                }
                true
            }
            .onLongPress { e ->
                if (overlay.startSelectionAt(e.x, e.y)) {
                    showSelectionBar(true)
                } else if (wordsByPage[currentPage + 1]?.words?.isEmpty() != false) {
                    offerOcrIfNeeded()
                }
            }
            .onPageScroll { _, _ -> overlay.invalidate() }
            .onPageChange { p, count ->
                currentPage = p
                pageCount = count
                updateLabel()
                Db.get(this).saveProgress(bookPath, 0, p, 0.0)
                ensureWords(p + 1)
                if (!hugeBook) ensureWords(p + 2)
                overlay.invalidate()
                if (autoOn) {
                    if (viewMode == 2) recomputeSpeed(p) else scheduleFlip(p)
                }
            }
            .onLoad { count ->
                pageCount = count
                updateLabel()
                ensureWords(currentPage + 1)
                if (!hugeBook) ensureWords(currentPage + 2)
                if (wasAuto) pdfView.postDelayed({ startAuto() }, 400)
            }
            .onError { t ->
                Toast.makeText(this, "Could not open PDF: ${t.message}", Toast.LENGTH_LONG).show()
            }
            .load()
    }

    private fun updateLabel() {
        val mode = when (viewMode) {
            0 -> "pages ⇄"
            1 -> "pages ⇅"
            else -> "scroll ⇅"
        }
        txtPage.text = "Page ${currentPage + 1} / $pageCount  ·  $mode"
    }

    private fun showModeDialog() {
        val names = arrayOf(
            "Swipe sideways, page by page",
            "Swipe up, page by page (snaps)",
            "Continuous vertical scroll (best for zoom and line by line reading)"
        )
        Ui.builder(this)
            .setTitle("Page view mode")
            .setSingleChoiceItems(names, viewMode) { dlg, which ->
                viewMode = which
                Prefs.setPdfMode(this, which)
                dlg.dismiss()
                loadPdf(currentPage)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------ text layer

    override fun wordsForPage(page: Int): PdfPageText? {
        val data = wordsByPage[page + 1]
        if (data == null) ensureWords(page + 1)
        return data
    }

    override fun highlightsForPage(page: Int): List<String> =
        highlightsByPage[page + 1] ?: emptyList()

    override fun onSelectionChanged(text: String, active: Boolean) {
        showSelectionBar(active && text.isNotEmpty())
    }

    private fun ensureWords(page1: Int) {
        if (page1 < 1) return
        if (wordsByPage.containsKey(page1) || loadingPages.contains(page1)) return
        // a very large book keeps only what is on screen, to stay within memory
        if (wordsByPage.size > 6) {
            val keep = (currentPage)..(currentPage + 2)
            wordsByPage.keys.filter { it - 1 !in keep }.forEach { wordsByPage.remove(it) }
        }
        loadingPages.add(page1)
        Thread {
            val data = try {
                PdfTextExtractor.pageWords(bookPath, page1)
            } catch (e: Throwable) {
                null
            }
            runOnUiThread {
                loadingPages.remove(page1)
                if (data != null) {
                    wordsByPage[page1] = data
                    overlay.invalidate()
                }
            }
        }.start()
    }

    private fun reloadHighlights() {
        Thread {
            val map = Db.get(this).highlightsFor(bookPath).groupBy({ it.page }, { it.text })
            runOnUiThread {
                highlightsByPage.clear()
                highlightsByPage.putAll(map)
                overlay.invalidate()
            }
        }.start()
    }

    // ------------------------------------------------ selection actions

    private fun buildSelectionBar() {
        val actions = listOf(
            "Highlight" to "highlight",
            "Note" to "note",
            "Define" to "define",
            "Word list" to "wordlist",
            "Speak" to "speak",
            "Copy" to "copy",
            "✕" to "close"
        )
        val d = resources.displayMetrics.density
        for ((label, key) in actions) {
            selectionButtons.addView(
                Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                    text = label
                    isAllCaps = false
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    minWidth = 0
                    minimumWidth = 0
                    setPadding((13 * d).toInt(), 0, (13 * d).toInt(), 0)
                    setOnClickListener { onSelectionAction(key) }
                }
            )
        }
    }

    private fun showSelectionBar(show: Boolean) {
        selectionBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun clearSelection() {
        overlay.clearSelection()
        showSelectionBar(false)
    }

    private fun onSelectionAction(key: String) {
        val text = overlay.selectedText().trim()
        if (key == "close" || text.isEmpty()) {
            clearSelection()
            return
        }
        val page = overlay.selectedPage() + 1
        when (key) {
            "highlight" -> saveHighlight(text, page)
            "note" -> NotesUi.showAddNote(this, text) { noteText ->
                Db.get(this).addNote(bookPath, 0, page, text, noteText)
                Toast.makeText(this, "Note saved (txt file updated)", Toast.LENGTH_SHORT).show()
            }
            "define" -> {
                DefinitionPopup.show(
                    this, DictionaryHelper.phrase(text), tts, bookPath,
                    overlay.selectionContext(),
                    onHighlightWord = { w -> saveHighlight(w, page) }
                )
            }
            "wordlist" -> {
                val first = DictionaryHelper.phrase(text).lowercase()
                Thread {
                    val meaning = DictionaryHelper.lookup(this, first).firstOrNull()?.let {
                        DictionaryHelper.posLabel(it.pos) + ": " +
                            (it.defs.lineSequence().firstOrNull()?.removePrefix("1. ") ?: "")
                    } ?: ""
                    val ok = Db.get(this).addWord(bookPath, first, meaning)
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            if (ok) "\"$first\" saved to word list" else "\"$first\" already saved",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }.start()
            }
            "speak" -> tts?.speakOnce(text)
            "copy" -> {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("text", text))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
        clearSelection()
    }

    private fun saveHighlight(text: String, page: Int = currentPage + 1) {
        if (text.isBlank()) return
        Db.get(this).addHighlight(bookPath, 0, page, text)
        reloadHighlights()
        Toast.makeText(this, "Highlight saved (txt file updated)", Toast.LENGTH_SHORT).show()
    }

    private fun showHint(text: String, ms: Long) {
        hintChip.text = text
        hintChip.visibility = View.VISIBLE
        handler.postDelayed({ hintChip.visibility = View.GONE }, ms)
    }

    // ------------------------------------------------ OCR

    private fun offerOcrIfNeeded() {
        val page1 = currentPage + 1
        val data = wordsByPage[page1]
        if (data != null && data.words.isNotEmpty()) return
        Ui.builder(this)
            .setTitle("No text on this page")
            .setMessage(
                "This page looks like a scan, so there is no text to select.\n\n" +
                    "Recognise the text on it? This happens on your phone with no " +
                    "internet, and afterwards you can select, look up and listen to it."
            )
            .setPositiveButton("Recognise this page") { _, _ -> runOcr(page1, page1) }
            .setNeutralButton("Whole document") { _, _ -> runOcr(1, pageCount) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runOcr(from: Int, to: Int) {
        val total = to - from + 1
        val progress = Ui.builder(this)
            .setTitle("Recognising text")
            .setMessage("Starting…")
            .setCancelable(false)
            .setNegativeButton("Stop", null)
            .create()
        progress.show()
        var cancelled = false
        progress.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            cancelled = true
        }

        Thread {
            var done = 0
            var found = 0
            for (p in from..to) {
                if (cancelled) break
                runOnUiThread {
                    progress.setMessage("Page $p  ($done of $total done)")
                }
                val existing = wordsByPage[p]
                if (existing != null && existing.words.isNotEmpty() && !existing.fromOcr) {
                    done++
                    continue
                }
                val res = try {
                    OcrHelper.recognisePage(this, bookPath, p)
                } catch (e: Throwable) {
                    null
                }
                if (res != null && res.page.words.isNotEmpty()) {
                    found++
                    Db.get(this).saveOcr(bookPath, p, res.page, res.text)
                    PdfTextExtractor.putOcrWords(p, res.page, res.text)
                    runOnUiThread {
                        wordsByPage[p] = res.page
                        overlay.invalidate()
                    }
                }
                done++
            }
            runOnUiThread {
                progress.dismiss()
                Toast.makeText(
                    this,
                    if (found == 0) "No text could be recognised"
                    else "Text recognised on $found page" + if (found > 1) "s" else "",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    // ------------------------------------------------ immersive

    private fun applyImmersiveMode(on: Boolean) {
        immersive = on
        Immersive.apply(this, on, toolbar, bottomBar)
    }

    // ------------------------------------------------ auto reading

    private fun toggleAuto() {
        if (autoOn) stopAuto(null)
        else if (!Prefs.autoWpmChosen(this)) SpeedDialog.show(this) { startAuto() }
        else startAuto()
    }

    private fun startAuto() {
        tts?.stop()
        overlay.clearSpeaking()
        ttsReadingPage = -1
        clearSelection()
        autoOn = true
        btnAuto.setImageResource(R.drawable.ic_pause)
        val wpm = Prefs.autoWpm(this)
        Toast.makeText(
            this,
            if (viewMode == 2) "Auto scroll · $wpm wpm" else "Auto page turn · $wpm wpm",
            Toast.LENGTH_SHORT
        ).show()
        applyImmersiveMode(true)
        if (viewMode == 2) {
            recomputeSpeed(currentPage)
            handler.removeCallbacks(scrollTick)
            handler.postDelayed(scrollTick, TICK_MS.toLong())
        } else {
            scheduleFlip(currentPage)
        }
    }

    private fun stopAuto(msg: String?) {
        autoOn = false
        handler.removeCallbacks(flipNext)
        handler.removeCallbacks(scrollTick)
        btnAuto.setImageResource(R.drawable.ic_play)
        if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun secondsForPage(page: Int): Double {
        val words = PdfTextExtractor.pageText(bookPath, page + 1)
            .trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
        val wpm = Prefs.autoWpm(this)
        return if (words < 15) 5.0 else (words.toDouble() / wpm * 60.0).coerceIn(4.0, 600.0)
    }

    private fun scheduleFlip(page: Int) {
        handler.removeCallbacks(flipNext)
        Thread {
            val ms = (secondsForPage(page) * 1000).toLong()
            runOnUiThread { if (autoOn) handler.postDelayed(flipNext, ms) }
        }.start()
    }

    private fun recomputeSpeed(page: Int) {
        Thread {
            val seconds = secondsForPage(page)
            runOnUiThread {
                val heightPx = try {
                    pdfView.getPageSize(page).height * pdfView.zoom
                } catch (e: Exception) {
                    pdfView.height.toFloat()
                }
                pxPerSec = (heightPx / seconds).toFloat().coerceIn(4f, 900f)
            }
        }.start()
    }

    // ------------------------------------------------ TTS

    private fun toggleTts() {
        val t = tts ?: return
        if (t.speaking) {
            t.stop()
            ttsReadingPage = -1
            overlay.clearSpeaking()
            handler.removeCallbacks(followStep)
            updateTtsButton()
        } else {
            stopAuto(null)
            speakPage(currentPage)
        }
    }

    private fun speakPage(page: Int) {
        ttsReadingPage = page
        Thread {
            val page1 = page + 1
            val data = try {
                wordsByPage[page1] ?: PdfTextExtractor.pageWords(bookPath, page1)
            } catch (e: Throwable) {
                null
            }
            val blocks = if (data != null && data.text.isNotBlank()) {
                buildBlocks(page1, data)
            } else {
                PdfTextExtractor.splitSentences(PdfTextExtractor.pageText(bookPath, page1))
                    .map { SpeechBlock(page1, -1, -1, it) }
            }
            runOnUiThread {
                if (data != null && data.chars.isNotEmpty()) wordsByPage[page1] = data
                if (blocks.isEmpty()) {
                    if (page < pageCount - 1) {
                        pdfView.jumpTo(page + 1, true)
                        speakPage(page + 1)
                    } else {
                        Toast.makeText(this, "No readable text on this page", Toast.LENGTH_SHORT)
                            .show()
                        ttsReadingPage = -1
                    }
                    return@runOnUiThread
                }
                speechBlocks = blocks
                tts?.speakBlocks(blocks.map { it.text }, 0)
                updateTtsButton()
            }
        }.start()
    }

    /** Splits the page text into sentences, keeping where each one starts. */
    private fun buildBlocks(page1: Int, data: PdfPageText): List<SpeechBlock> {
        val text = data.text
        val out = ArrayList<SpeechBlock>()
        var i = 0
        while (i < text.length) {
            var end = i
            while (end < text.length) {
                val c = text[end]
                end++
                val long = end - i > 240
                if ((c == '.' || c == '!' || c == '?' || c == ';' || c == ':') &&
                    end - i > 24
                ) break
                if (long && c == ' ') break
            }
            val raw = text.substring(i, end)
            val trimmedStart = raw.indexOfFirst { !it.isWhitespace() }
            if (trimmedStart >= 0) {
                val from = i + trimmedStart
                val to = end - 1
                val sentence = text.substring(from, end).trim()
                if (sentence.length > 1) out.add(SpeechBlock(page1, from, to, sentence))
            }
            i = end
        }
        return out
    }

    /** Keeps the words being spoken comfortably inside the screen. */
    private fun scrollRangeIntoView(page: Int, from: Int, to: Int) {
        if (!followVoice || from < 0) return
        val r = overlay.rangeRectOnScreen(page, from, to) ?: return
        val h = pdfView.height.toFloat()
        if (h <= 0) return
        if (r.top >= h * 0.12f && r.bottom <= h * 0.78f) return
        val dy = r.top - h * 0.34f
        if (kotlin.math.abs(dy) < 8f) return
        smoothScrollBy(-dy)
    }

    /** Small animated nudge so following the voice does not feel jumpy. */
    private fun smoothScrollBy(dy: Float) {
        handler.removeCallbacks(followStep)
        followRemaining = dy
        handler.post(followStep)
    }

    private var followRemaining = 0f
    private val followStep = object : Runnable {
        override fun run() {
            if (kotlin.math.abs(followRemaining) < 1f) {
                followRemaining = 0f
                return
            }
            val step = followRemaining / 4f
            followRemaining -= step
            try {
                pdfView.moveRelativeTo(0f, step)
                pdfView.loadPages()
                overlay.invalidate()
            } catch (_: Exception) {
                followRemaining = 0f
                return
            }
            handler.postDelayed(this, 16)
        }
    }

    private fun updateTtsButton() {
        btnTts.setImageResource(
            if (tts?.speaking == true) R.drawable.ic_stop else R.drawable.ic_speaker
        )
    }

    // ------------------------------------------------ dialogs

    private fun showJumpDialog() {
        val (edit, wrap) = Ui.inputBox(
            this, "Page number (1 - $pageCount)", (currentPage + 1).toString(), numeric = true
        )
        Ui.builder(this)
            .setTitle("Go to page")
            .setView(wrap)
            .setPositiveButton("Go") { _, _ ->
                val p = edit.text.toString().toIntOrNull() ?: return@setPositiveButton
                pdfView.jumpTo((p - 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0)), true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openTextMode() {
        startActivity(
            Intent(this, PageTextActivity::class.java)
                .putExtra("path", bookPath)
                .putExtra("page", currentPage + 1)
        )
    }

    private fun runSearch(q: String) {
        Toast.makeText(this, "Searching…", Toast.LENGTH_SHORT).show()
        Thread {
            data class Hit(val page: Int, val snippet: String)

            val hits = ArrayList<Hit>()
            val count = PdfTextExtractor.pageCount(bookPath)
            for (p in 1..count) {
                if (hits.size >= 60) break
                val text = PdfTextExtractor.pageText(bookPath, p)
                val idx = text.indexOf(q, 0, true)
                if (idx >= 0) {
                    val start = (idx - 40).coerceAtLeast(0)
                    val end = (idx + q.length + 40).coerceAtMost(text.length)
                    hits.add(Hit(p, "…" + text.substring(start, end).trim() + "…"))
                }
            }
            runOnUiThread {
                if (hits.isEmpty()) {
                    Toast.makeText(this, "No results for \"$q\"", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val labels = hits.map { "Page ${it.page}: ${it.snippet}" }.toTypedArray()
                Ui.builder(this)
                    .setTitle("\"$q\" — ${hits.size} results")
                    .setItems(labels) { _, which -> pdfView.jumpTo(hits[which].page - 1, true) }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }.start()
    }

    private fun showBookmarks() {
        val db = Db.get(this)
        val list = db.bookmarksFor(bookPath)
        if (list.isEmpty()) {
            Toast.makeText(this, "No bookmarks yet", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = list.map { it.label }.toTypedArray()
        Ui.builder(this)
            .setTitle(R.string.bookmarks)
            .setItems(labels) { _, which -> pdfView.jumpTo(list[which].page, true) }
            .setNegativeButton("Delete one") { _, _ ->
                Ui.builder(this)
                    .setTitle("Delete bookmark")
                    .setItems(labels) { _, w2 ->
                        db.deleteBookmark(list[w2].id)
                        Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
            .show()
    }

    private fun showHighlights() {
        val db = Db.get(this)
        val list = db.highlightsFor(bookPath)
        if (list.isEmpty()) {
            Toast.makeText(
                this, "No highlights yet. Hold on any text to select it.", Toast.LENGTH_LONG
            ).show()
            return
        }
        val labels = list.map { "[Page ${it.page}] " + it.text.take(80) }.toTypedArray()
        Ui.builder(this)
            .setTitle(R.string.highlights)
            .setItems(labels) { _, which ->
                pdfView.jumpTo((list[which].page - 1).coerceAtLeast(0), true)
            }
            .setNegativeButton("Delete one") { _, _ ->
                Ui.builder(this)
                    .setTitle("Delete highlight")
                    .setItems(labels) { _, w2 ->
                        db.deleteHighlight(list[w2].id, bookPath)
                        reloadHighlights()
                        Toast.makeText(this, "Deleted (txt file updated)", Toast.LENGTH_SHORT)
                            .show()
                    }
                    .show()
            }
            .show()
    }

    private fun exportPdf() {
        Toast.makeText(this, "Building PDF…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val f = PdfExporter.export(this, bookPath)
                runOnUiThread {
                    Ui.builder(this)
                        .setTitle("Export ready")
                        .setMessage("Saved to EbookReader/Exports/${f.name}")
                        .setPositiveButton("Share") { _, _ ->
                            startActivity(
                                Intent.createChooser(
                                    PdfExporter.shareIntent(this, f), "Share notes PDF"
                                )
                            )
                        }
                        .setNegativeButton("Done", null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ------------------------------------------------ menu / lifecycle

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.reader_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_search -> SearchUi.show(this, bookPath) { q -> runSearch(q) }
            R.id.action_add_bookmark -> {
                Db.get(this).addBookmark(
                    bookPath, 0, currentPage,
                    "Page ${currentPage + 1} of ${bookFile.nameWithoutExtension}"
                )
                Toast.makeText(this, "Bookmark added", Toast.LENGTH_SHORT).show()
            }
            R.id.action_add_note -> NotesUi.showAddNote(this, "") { noteText ->
                Db.get(this).addNote(bookPath, 0, currentPage + 1, "", noteText)
                Toast.makeText(this, "Note saved (txt file updated)", Toast.LENGTH_SHORT).show()
            }
            R.id.action_notes -> NotesUi.showNotesList(this, bookPath, isPdf = true) { n ->
                pdfView.jumpTo((n.page - 1).coerceAtLeast(0), true)
            }
            R.id.action_wordlist -> WordListUi.show(this, bookPath)
            R.id.action_ocr -> offerOcrIfNeeded()
            R.id.action_export_pdf -> exportPdf()
            R.id.action_stats -> startActivity(Intent(this, StatsActivity::class.java))
            R.id.action_fullscreen -> applyImmersiveMode(!immersive)
            R.id.action_bookmarks -> showBookmarks()
            R.id.action_highlights -> showHighlights()
            R.id.action_summary -> startActivity(
                Intent(this, AiSummaryActivity::class.java)
                    .putExtra("path", bookPath)
                    .putExtra("page", currentPage + 1)
            )
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // the view is a different shape now, so re-lay the pages out and
        // bring the spot being read back into view
        pdfView.postDelayed({
            try {
                pdfView.jumpTo(currentPage, false)
                pdfView.loadPages()
            } catch (_: Exception) {
            }
            overlay.invalidate()
            if (tts?.speaking == true) {
                val b = speechBlocks.getOrNull(0)
                if (b != null) scrollRangeIntoView(currentPage, b.fromChar, b.toChar)
            }
        }, 220)
    }

    override fun onResume() {
        super.onResume()
        timer?.start()
        reloadHighlights()
    }

    override fun onPause() {
        super.onPause()
        stopAuto(null)
        timer?.stopAndSave()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        wordsByPage.clear()
        PdfTextExtractor.trimMemory()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            wordsByPage.clear()
            PdfTextExtractor.trimMemory()
        }
    }

    override fun onDestroy() {
        stopAuto(null)
        tts?.shutdown()
        tts = null
        PdfTextExtractor.close()
        super.onDestroy()
    }

    companion object {
        private const val TICK_MS = 16
    }
}
