package com.aali.ebookreader

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.gson.Gson
import java.io.File

/**
 * Text mode for one PDF page: tap a word for the offline dictionary, select
 * text to highlight, note, save to the word list or hear it read aloud.
 * PDF pages are pictures, so this screen is where selection works.
 */
class PageTextActivity : AppCompatActivity() {

    private lateinit var webView: ReaderWebView
    private lateinit var toolbar: Toolbar
    private lateinit var bottomBar: View
    private lateinit var bookPath: String
    private var page = 1
    private var tts: TtsManager? = null
    private var lastSelection = ""
    private var immersive = false
    private val gson = Gson()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_html_reader)

        bookPath = intent.getStringExtra("path") ?: run { finish(); return }
        page = intent.getIntExtra("page", 1)

        toolbar = findViewById(R.id.toolbar)
        bottomBar = findViewById(R.id.bottomBar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        supportActionBar?.title = File(bookPath).nameWithoutExtension
        supportActionBar?.subtitle = "Page $page · text mode"

        // chapter controls do not apply to a single PDF page
        findViewById<View>(R.id.btnContents).visibility = View.GONE
        findViewById<View>(R.id.btnAuto).visibility = View.GONE
        findViewById<View>(R.id.btnDisplay).visibility = View.GONE
        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { goToPage(page - 1) }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener { goToPage(page + 1) }
        findViewById<android.widget.TextView>(R.id.txtPage).text = "Page $page"

        tts = TtsManager(this)
        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.addJavascriptInterface(Bridge(), "Android")
        webView.onReaderAction = { action -> handleSelectionAction(action) }
        webView.setBackgroundColor(Color.parseColor("#F3EDFF"))
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                webView.evaluateJavascript(
                    "setReaderTheme('theme-lavender', ${Prefs.fontSize(this@PageTextActivity)}, " +
                        "${Prefs.lineHeight(this@PageTextActivity)}, " +
                        "${Prefs.margin(this@PageTextActivity)})",
                    null
                )
                val hs = Db.get(this@PageTextActivity).highlightsFor(bookPath)
                    .filter { it.page == page }
                if (hs.isNotEmpty()) {
                    val json = gson.toJson(gson.toJson(hs.map { it.text }))
                    webView.evaluateJavascript("applyHighlights($json)", null)
                }
                webView.evaluateJavascript("readerReady()", null)
            }
        }

        findViewById<ImageButton>(R.id.btnTts).setOnClickListener {
            val t = tts ?: return@setOnClickListener
            if (t.speaking) t.stop()
            else Thread {
                val sentences = PdfTextExtractor.splitSentences(
                    PdfTextExtractor.pageText(bookPath, page)
                )
                runOnUiThread { t.speakBlocks(sentences, 0) }
            }.start()
        }

        DictionaryHelper.warmUp(this)
        UrduDictionary.warmUp(this)
        loadPage()
    }

    private fun goToPage(target: Int) {
        val count = PdfTextExtractor.pageCount(bookPath)
        if (target < 1 || (count > 0 && target > count)) {
            Toast.makeText(this, "No more pages", Toast.LENGTH_SHORT).show()
            return
        }
        tts?.stop()
        page = target
        supportActionBar?.subtitle = "Page $page · text mode"
        findViewById<android.widget.TextView>(R.id.txtPage).text = "Page $page"
        loadPage()
    }

    private fun loadPage() {
        Thread {
            val text = PdfTextExtractor.pageText(bookPath, page)
            val css = assets.open("reader.css").bufferedReader().readText()
            val js = assets.open("reader.js").bufferedReader().readText()
            val body = if (text.isBlank()) {
                "<p><i>No selectable text on this page. It may be a scanned image.</i></p>"
            } else {
                text.split(Regex("\n\\s*\n|\n"))
                    .filter { it.isNotBlank() }
                    .joinToString("\n") {
                        "<p>" + it.trim()
                            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") +
                            "</p>"
                    }
            }
            val html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>" +
                "<style>$css</style></head><body class=\"theme-lavender\">" +
                body + "<script>$js</script></body></html>"
            runOnUiThread {
                webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        }.start()
    }

    private fun handleSelectionAction(action: String) {
        val text = lastSelection.trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "Select some text first", Toast.LENGTH_SHORT).show()
            return
        }
        when (action) {
            "highlight" -> addHighlight(text)
            "note" -> NotesUi.showAddNote(this, text) { noteText ->
                Db.get(this).addNote(bookPath, 0, page, text, noteText)
                Toast.makeText(this, "Note saved (txt file updated)", Toast.LENGTH_SHORT).show()
            }
            "define" -> {
                val first = text.split(Regex("\\s+")).firstOrNull()?.trim() ?: return
                DefinitionPopup.show(this, first, tts, bookPath, text,
                    onHighlightWord = { w -> addHighlight(w) })
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

    inner class Bridge {
        @JavascriptInterface
        fun onWordTap(word: String, para: Int, context: String) {
            runOnUiThread {
                DefinitionPopup.show(
                    this@PageTextActivity, word, tts, bookPath, context,
                    onHighlightWord = { w -> addHighlight(w) }
                )
            }
        }

        @JavascriptInterface
        fun onSelectionChanged(text: String) {
            lastSelection = text
        }

        @JavascriptInterface
        fun onBlankTap() {
            runOnUiThread {
                immersive = !immersive
                Immersive.apply(this@PageTextActivity, immersive, toolbar, bottomBar)
            }
        }

        @JavascriptInterface
        fun onScroll(page: Int, total: Int, frac: Double) {
        }

        @JavascriptInterface
        fun onChapterReady(total: Int) {
        }

        @JavascriptInterface
        fun onAutoScrollEnd() {
        }
    }

    private fun addHighlight(text: String) {
        if (text.isBlank()) return
        Db.get(this).addHighlight(bookPath, 0, page, text)
        val json = gson.toJson(gson.toJson(listOf(text)))
        webView.evaluateJavascript("applyHighlights($json)", null)
        Toast.makeText(this, "Highlight saved (txt file updated)", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
