package com.aali.ebookreader

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.gson.Gson
import java.io.File

class HtmlReaderActivity : AppCompatActivity() {

    private lateinit var webView: ReaderWebView
    private lateinit var txtPage: TextView
    private lateinit var btnTts: ImageButton
    private lateinit var btnAuto: ImageButton
    private lateinit var toolbar: Toolbar
    private lateinit var bottomBar: View

    private lateinit var bookFile: File
    /** True for files shown in their original design instead of reflowed. */
    private var fixedLayout = false
    private lateinit var bookPath: String
    private var parsed: ParsedBook? = null

    private var chapterIndex = 0
    private var currentPage = 1
    private var totalPages = 1
    private var currentFrac = 0.0
    private var restoreFrac = -1.0
    private var pendingFind: String? = null
    private var pendingAutoTts = false
    private var pendingAutoScroll = false
    private var autoScrolling = false
    private var ttsStartPara = 0
    private var immersive = false
    private var lastSelection = ""
    private var speakingBlock = -1

    private var tts: TtsManager? = null
    private var timer: ReadingTimer? = null
    private val gson = Gson()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_html_reader)

        bookPath = intent.getStringExtra("path") ?: run { finish(); return }
        bookFile = File(bookPath)
        timer = ReadingTimer(this, bookPath)

        toolbar = findViewById(R.id.toolbar)
        bottomBar = findViewById(R.id.bottomBar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        supportActionBar?.title = bookFile.nameWithoutExtension

        webView = findViewById(R.id.webView)
        txtPage = findViewById(R.id.txtPage)
        btnTts = findViewById(R.id.btnTts)
        btnAuto = findViewById(R.id.btnAuto)

        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = true
        // lets the page load the bundled Urdu font and an EPUB's own fonts, which
        // Chromium otherwise blocks as cross origin requests between file:// URLs
        @Suppress("DEPRECATION")
        webView.settings.allowFileAccessFromFileURLs = true
        webView.settings.domStorageEnabled = true
        // PowerPoint and Word files keep their original page design, so they
        // are shown whole and scaled to the screen, and pinch zoomed, exactly
        // like a PDF page. EPUB and TXT keep reflowing to your font size.
        fixedLayout = bookFile.extension.lowercase() in setOf("pptx", "docx")
        webView.settings.useWideViewPort = fixedLayout
        webView.settings.loadWithOverviewMode = fixedLayout
        webView.settings.setSupportZoom(fixedLayout)
        webView.settings.builtInZoomControls = fixedLayout
        webView.settings.displayZoomControls = false
        webView.addJavascriptInterface(Bridge(), "Android")
        webView.onReaderAction = { action -> handleSelectionAction(action) }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                onChapterLoaded()
            }
        }
        applyWebViewBackground()

        if (Prefs.keepScreenOn(this)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        tts = TtsManager(this)
        tts?.listener = object : TtsManager.Listener {
            override fun onBlockStarted(index: Int) {
                webView.evaluateJavascript("markPara($index)", null)
                speakingBlock = index
                updateTtsButton()
            }

            override fun onWordRange(index: Int, start: Int, end: Int) {
                speakingBlock = index
                webView.evaluateJavascript("markSpokenRange($index, $start, $end)", null)
            }

            override fun onFinishedAll() {
                webView.evaluateJavascript("clearTtsMark()", null)
                updateTtsButton()
                val p = parsed ?: return
                if (chapterIndex < p.chapters.size - 1) {
                    pendingAutoTts = true
                    ttsStartPara = 0
                    loadChapter(chapterIndex + 1)
                }
            }
        }
        DictionaryHelper.warmUp(this)
        UrduDictionary.warmUp(this)

        findViewById<ImageButton>(R.id.btnContents).setOnClickListener { showContents() }
        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { relativeChapter(-1) }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener { relativeChapter(1) }
        findViewById<ImageButton>(R.id.btnDisplay).setOnClickListener { showDisplayDialog() }
        btnTts.setOnClickListener { toggleTts() }
        btnAuto.setOnClickListener { toggleAutoScroll() }
        btnAuto.setOnLongClickListener {
            SpeedDialog.show(this) { wpm -> startAutoScroll(wpm) }
            true
        }

        Thread {
            try {
                val p = EpubParser.parse(this, bookFile)
                runOnUiThread {
                    parsed = p
                    supportActionBar?.title = p.title
                    val prog = Db.get(this).getProgress(bookPath)
                    chapterIndex = (prog?.chapter ?: 0).coerceIn(0, p.chapters.size - 1)
                    restoreFrac = prog?.scroll ?: 0.0
                    loadChapter(chapterIndex)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Could not open book: ${e.message}", Toast.LENGTH_LONG)
                        .show()
                    finish()
                }
            }
        }.start()
    }

    // ------------------------------------------------ chapter loading

    private fun loadChapter(index: Int) {
        val p = parsed ?: return
        if (index !in p.chapters.indices) return
        stopTtsOnly()
        stopAutoScroll(sendJs = false)
        chapterIndex = index
        currentPage = 1
        val ch = p.chapters[index]
        supportActionBar?.subtitle = ch.title
        Thread {
            val html = buildChapterHtml(ch)
            runOnUiThread {
                val base = "file://" + (ch.file.parentFile?.absolutePath ?: "/") + "/"
                webView.loadDataWithBaseURL(base, html, "text/html", "utf-8", null)
            }
        }.start()
    }

    private fun buildChapterHtml(ch: Chapter): String {
        var html = try {
            ch.file.readText()
        } catch (e: Exception) {
            "<html><body><p>Could not read chapter.</p></body></html>"
        }
        val css = assets.open("reader.css").bufferedReader().readText()
        val js = assets.open("reader.js").bufferedReader().readText()
        val inject = "<style>\n$css\n</style>"
        val script = "<script>\n$js\n</script>"
        // Spliced by position, never through a regex replacement: the reader
        // script contains characters such as "$/" and backslashes that Java
        // would read as group references and refuse.
        html = insertBefore(html, "</head>", inject, fallbackAtStart = true)
        html = insertBefore(html, "</body>", script, fallbackAtStart = false)
        return html
    }

    /** Puts [content] just before [tag], or at the start or end if it is absent. */
    private fun insertBefore(
        html: String,
        tag: String,
        content: String,
        fallbackAtStart: Boolean
    ): String {
        val idx = html.indexOf(tag, ignoreCase = true)
        return when {
            idx >= 0 -> html.substring(0, idx) + content + html.substring(idx)
            fallbackAtStart -> content + html
            else -> html + content
        }
    }

    private fun onChapterLoaded() {
        applyThemeJs()
        val hs = Db.get(this).highlightsFor(bookPath).filter { it.chapter == chapterIndex }
        if (hs.isNotEmpty()) {
            val json = gson.toJson(gson.toJson(hs.map { it.text }))
            webView.evaluateJavascript("applyHighlights($json)", null)
        }
        webView.evaluateJavascript("readerReady()", null)
        if (restoreFrac > 0) {
            val f = restoreFrac
            restoreFrac = -1.0
            webView.postDelayed({ webView.evaluateJavascript("scrollToFrac($f)", null) }, 220)
        }
        pendingFind?.let { q ->
            pendingFind = null
            val json = gson.toJson(q)
            webView.postDelayed({ webView.evaluateJavascript("findAndFlash($json)", null) }, 320)
        }
        if (pendingAutoTts) {
            pendingAutoTts = false
            webView.postDelayed({ startTtsFromPara(ttsStartPara) }, 500)
        }
        if (pendingAutoScroll) {
            pendingAutoScroll = false
            webView.postDelayed({ startAutoScroll(Prefs.autoWpm(this)) }, 600)
        }
    }

    private fun applyWebViewBackground() {
        webView.setBackgroundColor(
            when (Prefs.readerTheme(this)) {
                0 -> Color.WHITE
                2 -> Color.parseColor("#121016")
                3 -> Color.parseColor("#F3EDFF")
                else -> Color.parseColor("#F4ECD8")
            }
        )
    }

    private fun applyThemeJs() {
        val cls = when (Prefs.readerTheme(this)) {
            0 -> "theme-light"
            2 -> "theme-dark"
            3 -> "theme-lavender"
            else -> "theme-sepia"
        }
        webView.evaluateJavascript(
            "setReaderTheme('$cls', ${Prefs.fontSize(this)}, " +
                "${Prefs.lineHeight(this)}, ${Prefs.margin(this)})",
            null
        )
        applyWebViewBackground()
    }

    private fun relativeChapter(delta: Int) {
        val p = parsed ?: return
        val target = chapterIndex + delta
        if (target in p.chapters.indices) {
            restoreFrac = 0.0
            loadChapter(target)
        } else {
            Toast.makeText(
                this, if (delta > 0) "End of book" else "Start of book", Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ------------------------------------------------ selection actions

    private fun handleSelectionAction(action: String) {
        val text = lastSelection.trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "Select some text first", Toast.LENGTH_SHORT).show()
            return
        }
        when (action) {
            "highlight" -> addHighlight(text)
            "note" -> NotesUi.showAddNote(this, text) { noteText ->
                Db.get(this).addNote(bookPath, chapterIndex, currentPage, text, noteText)
                Toast.makeText(this, "Note saved (txt file updated)", Toast.LENGTH_SHORT).show()
            }
            "define" -> {
                val first = text.split(Regex("\\s+")).firstOrNull()?.trim() ?: return
                DefinitionPopup.show(
                    this, first, tts, bookPath, text,
                    onHighlightWord = { w -> addHighlight(w) }
                )
            }
            "wordlist" -> {
                val first = text.split(Regex("\\s+")).firstOrNull()?.trim()?.lowercase() ?: return
                Thread {
                    val meaning = DictionaryHelper.shortMeaning(this, first)
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
        webView.evaluateJavascript("clearSelection()", null)
        lastSelection = ""
    }

    // ------------------------------------------------ JS bridge

    inner class Bridge {
        @JavascriptInterface
        fun onWordTap(word: String, para: Int, context: String) {
            runOnUiThread {
                DefinitionPopup.show(
                    this@HtmlReaderActivity, word, tts, bookPath, context,
                    onHighlightWord = { w -> addHighlight(w) },
                    onReadFromHere = { startTtsFromPara(para.coerceAtLeast(0)) }
                )
            }
        }

        @JavascriptInterface
        fun onSelectionChanged(text: String) {
            lastSelection = text
        }

        @JavascriptInterface
        fun onBlankTap() {
            runOnUiThread { applyImmersiveMode(!immersive) }
        }

        @JavascriptInterface
        fun onScroll(page: Int, total: Int, frac: Double) {
            runOnUiThread {
                currentPage = page
                totalPages = total
                currentFrac = frac
                updatePageLabel()
                Db.get(this@HtmlReaderActivity)
                    .saveProgress(bookPath, chapterIndex, currentPage, frac)
            }
        }

        @JavascriptInterface
        fun onChapterReady(total: Int) {
            runOnUiThread {
                totalPages = total
                updatePageLabel()
            }
        }

        @JavascriptInterface
        fun onAutoScrollEnd() {
            runOnUiThread {
                val p = parsed ?: return@runOnUiThread
                if (autoScrolling && chapterIndex < p.chapters.size - 1) {
                    pendingAutoScroll = true
                    restoreFrac = 0.0
                    loadChapter(chapterIndex + 1)
                } else {
                    stopAutoScroll(sendJs = false)
                    Toast.makeText(this@HtmlReaderActivity, "End of book", Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }

    private fun updatePageLabel() {
        val p = parsed
        val chLabel = if (p != null) "Ch ${chapterIndex + 1}/${p.chapters.size}" else ""
        txtPage.text = "$chLabel  ·  Page $currentPage/$totalPages"
    }

    private fun addHighlight(text: String) {
        if (text.isBlank()) return
        Db.get(this).addHighlight(bookPath, chapterIndex, currentPage, text)
        val json = gson.toJson(gson.toJson(listOf(text)))
        webView.evaluateJavascript("applyHighlights($json)", null)
        Toast.makeText(this, "Highlight saved (txt file updated)", Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------ immersive

    private fun applyImmersiveMode(on: Boolean) {
        immersive = on
        Immersive.apply(this, on, toolbar, bottomBar)
    }

    // ------------------------------------------------ auto scroll

    private fun toggleAutoScroll() {
        if (autoScrolling) stopAutoScroll(sendJs = true)
        else if (!Prefs.autoWpmChosen(this)) SpeedDialog.show(this) { w -> startAutoScroll(w) }
        else startAutoScroll(Prefs.autoWpm(this))
    }

    private fun startAutoScroll(wpm: Int) {
        stopTtsOnly()
        autoScrolling = true
        webView.evaluateJavascript("startAutoScroll($wpm)", null)
        btnAuto.setImageResource(R.drawable.ic_pause)
        Toast.makeText(this, "Auto scroll · $wpm wpm", Toast.LENGTH_SHORT).show()
        applyImmersiveMode(true)
    }

    private fun stopAutoScroll(sendJs: Boolean) {
        if (sendJs && autoScrolling) webView.evaluateJavascript("stopAutoScroll()", null)
        autoScrolling = false
        btnAuto.setImageResource(R.drawable.ic_play)
    }

    // ------------------------------------------------ TTS

    private fun toggleTts() {
        val t = tts ?: return
        if (t.speaking) stopTtsOnly()
        else {
            stopAutoScroll(sendJs = true)
            startTtsFromPara(0)
        }
    }

    private fun startTtsFromPara(startIndex: Int) {
        webView.evaluateJavascript("getParagraphsJson()") { raw ->
            try {
                val jsonStr = gson.fromJson(raw, String::class.java) ?: return@evaluateJavascript
                val arr = gson.fromJson(jsonStr, Array<String>::class.java) ?: emptyArray()
                if (arr.isEmpty()) {
                    Toast.makeText(this, "Nothing to read here", Toast.LENGTH_SHORT).show()
                    return@evaluateJavascript
                }
                tts?.speakBlocks(arr.toList(), startIndex.coerceIn(0, arr.size - 1))
                updateTtsButton()
            } catch (e: Exception) {
                Toast.makeText(this, "Read aloud failed to start", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopTtsOnly() {
        tts?.stop()
        speakingBlock = -1
        webView.evaluateJavascript("clearTtsMark()", null)
        updateTtsButton()
    }

    private fun updateTtsButton() {
        btnTts.setImageResource(
            if (tts?.speaking == true) R.drawable.ic_stop else R.drawable.ic_speaker
        )
    }

    // ------------------------------------------------ dialogs

    private fun showContents() {
        val p = parsed ?: return
        val titles = p.chapters.mapIndexed { i, c -> "${i + 1}. ${c.title}" }.toTypedArray()
        Ui.builder(this)
            .setTitle(R.string.contents)
            .setItems(titles) { _, which ->
                restoreFrac = 0.0
                loadChapter(which)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showDisplayDialog() {
        val col = Ui.verticalBox(this)

        col.addView(Ui.sectionLabel(this, "PAGE COLOUR"))
        val themeRow = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        listOf("Light", "Sepia", "Dark", "Lavender").forEachIndexed { i, n ->
            themeRow.addView(RadioButton(this).apply {
                text = n
                id = 1000 + i
                textSize = 13f
            })
        }
        themeRow.check(1000 + Prefs.readerTheme(this))
        themeRow.setOnCheckedChangeListener { _, id ->
            Prefs.setReaderTheme(this, id - 1000)
            applyThemeJs()
        }
        col.addView(themeRow)

        fun slider(label: String, max: Int, value: Int, apply: (Int) -> Unit) {
            col.addView(Ui.sectionLabel(this, label))
            col.addView(SeekBar(this).apply {
                this.max = max
                progress = value.coerceIn(0, max)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, v: Int, user: Boolean) {
                        apply(v)
                        applyThemeJs()
                    }

                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            })
        }

        slider("TEXT SIZE", 26, Prefs.fontSize(this) - 14) { Prefs.setFontSize(this, 14 + it) }
        slider("LINE SPACING", 12, (Prefs.lineHeight(this) - 120) / 10) {
            Prefs.setLineHeight(this, 120 + it * 10)
        }
        slider("SIDE MARGIN", 12, (Prefs.margin(this) - 8) / 4) {
            Prefs.setMargin(this, 8 + it * 4)
        }

        Ui.builder(this)
            .setTitle("Display")
            .setView(android.widget.ScrollView(this).apply { addView(col) })
            .setPositiveButton("Done", null)
            .show()
    }

    private fun runSearch(q: String) {
        val p = parsed ?: return
        Toast.makeText(this, "Searching…", Toast.LENGTH_SHORT).show()
        Thread {
            data class Hit(val chapter: Int, val snippet: String)

            val hits = ArrayList<Hit>()
            for ((i, ch) in p.chapters.withIndex()) {
                if (hits.size >= 60) break
                val text = EpubParser.chapterText(ch)
                var idx = text.indexOf(q, 0, true)
                while (idx >= 0 && hits.size < 60) {
                    val start = (idx - 40).coerceAtLeast(0)
                    val end = (idx + q.length + 40).coerceAtMost(text.length)
                    hits.add(Hit(i, "…" + text.substring(start, end).trim() + "…"))
                    idx = text.indexOf(q, idx + q.length, true)
                }
            }
            runOnUiThread {
                if (hits.isEmpty()) {
                    Toast.makeText(this, "No results for \"$q\"", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val labels = hits.map { "Ch ${it.chapter + 1}: ${it.snippet}" }.toTypedArray()
                Ui.builder(this)
                    .setTitle("\"$q\" — ${hits.size} results")
                    .setItems(labels) { _, which ->
                        pendingFind = q
                        restoreFrac = 0.0
                        loadChapter(hits[which].chapter)
                    }
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
            .setItems(labels) { _, which ->
                restoreFrac = 0.0
                loadChapter(list[which].chapter)
            }
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
            Toast.makeText(this, "No highlights yet", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = list.map {
            "[Ch ${it.chapter + 1} · p${it.page}] " + it.text.take(80)
        }.toTypedArray()
        Ui.builder(this)
            .setTitle(R.string.highlights)
            .setItems(labels) { _, which ->
                pendingFind = list[which].text.take(60)
                restoreFrac = 0.0
                loadChapter(list[which].chapter)
            }
            .setNegativeButton("Delete one") { _, _ ->
                Ui.builder(this)
                    .setTitle("Delete highlight")
                    .setItems(labels) { _, w2 ->
                        db.deleteHighlight(list[w2].id, bookPath)
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
                val p = parsed
                val label = "Ch ${chapterIndex + 1}" +
                    (p?.chapters?.getOrNull(chapterIndex)?.title?.let { " · $it" } ?: "") +
                    " · page $currentPage"
                Db.get(this).addBookmark(bookPath, chapterIndex, currentPage, label)
                Toast.makeText(this, "Bookmark added", Toast.LENGTH_SHORT).show()
            }
            R.id.action_add_note -> NotesUi.showAddNote(this, "") { noteText ->
                Db.get(this).addNote(bookPath, chapterIndex, currentPage, "", noteText)
                Toast.makeText(this, "Note saved (txt file updated)", Toast.LENGTH_SHORT).show()
            }
            R.id.action_notes -> NotesUi.showNotesList(this, bookPath, isPdf = false) { n ->
                if (n.refText.isNotEmpty()) pendingFind = n.refText.take(60)
                restoreFrac = 0.0
                loadChapter(n.chapter)
            }
            R.id.action_wordlist -> WordListUi.show(this, bookPath)
            R.id.action_ocr -> Toast.makeText(
                this, "Text recognition is only needed for scanned PDFs", Toast.LENGTH_SHORT
            ).show()
            R.id.action_export_pdf -> exportPdf()
            R.id.action_stats -> startActivity(Intent(this, StatsActivity::class.java))
            R.id.action_fullscreen -> applyImmersiveMode(!immersive)
            R.id.action_bookmarks -> showBookmarks()
            R.id.action_highlights -> showHighlights()
            R.id.action_summary -> startActivity(
                Intent(this, AiSummaryActivity::class.java)
                    .putExtra("path", bookPath)
                    .putExtra("chapter", chapterIndex)
            )
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        timer?.start()
    }

    override fun onPause() {
        super.onPause()
        Db.get(this).saveProgress(bookPath, chapterIndex, currentPage, currentFrac)
        timer?.stopAndSave()
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
