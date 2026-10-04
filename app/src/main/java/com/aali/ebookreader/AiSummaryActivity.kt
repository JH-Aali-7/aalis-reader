package com.aali.ebookreader

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.button.MaterialButton
import java.io.File

class AiSummaryActivity : AppCompatActivity() {

    private lateinit var bookPath: String
    private lateinit var webView: ReaderWebView
    private var chapter = -1
    private var page = -1
    private var resultRaw = ""
    private var resultPlain = ""
    private var working = false
    private var tts: TtsManager? = null
    private var lastSelection = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_summary)

        bookPath = intent.getStringExtra("path") ?: run { finish(); return }
        chapter = intent.getIntExtra("chapter", -1)
        page = intent.getIntExtra("page", -1)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        supportActionBar?.subtitle = File(bookPath).nameWithoutExtension

        tts = TtsManager(this)

        findViewById<RadioButton>(R.id.scopePart).text = when {
            chapter >= 0 -> "This chapter"
            page >= 1 -> "Around page $page"
            else -> "First part only"
        }

        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.allowHighlight = false
        webView.addJavascriptInterface(Bridge(), "Android")
        webView.onReaderAction = { action -> handleSelectionAction(action) }
        webView.setBackgroundColor(Color.parseColor("#F3EDFF"))
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                webView.evaluateJavascript(
                    "setReaderTheme('theme-lavender', ${Prefs.fontSize(this@AiSummaryActivity)}," +
                        " ${Prefs.lineHeight(this@AiSummaryActivity)}, 18)",
                    null
                )
                webView.evaluateJavascript("readerReady()", null)
            }
        }
        render("<p style=\"opacity:.6\">Choose what to summarize, then tap " +
            "<b>Generate summary</b>.</p>")

        findViewById<MaterialButton>(R.id.btnGo).setOnClickListener { start() }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.btnShare).setOnClickListener { share() }
        findViewById<MaterialButton>(R.id.btnSpeak).setOnClickListener {
            val t = tts ?: return@setOnClickListener
            if (t.speaking) {
                t.stop()
                Toast.makeText(this, "Stopped", Toast.LENGTH_SHORT).show()
            } else if (resultPlain.isNotEmpty()) {
                t.speakBlocks(PdfTextExtractor.splitSentences(resultPlain), 0)
            }
        }
        DictionaryHelper.warmUp(this)
        UrduDictionary.warmUp(this)
    }

    // ------------------------------------------------ rendering

    private fun render(bodyHtml: String) {
        val css = assets.open("reader.css").bufferedReader().readText()
        val js = assets.open("reader.js").bufferedReader().readText()
        val extra = """
            h2,h3,h4 { color:#5B21B6; margin: 18px 0 6px; }
            ul { padding-left: 22px; }
            li { margin: 6px 0; }
            code { background:#EDE4FF; padding:1px 4px; border-radius:4px; font-size:0.92em; }
            hr { border:none; border-top:1px solid #D9C6FF; margin:16px 0; }
            b { color:#3C1470; }
        """.trimIndent()
        val html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>" +
            "<style>$css\n$extra</style></head><body class=\"theme-lavender\">" +
            bodyHtml + "<script>$js</script></body></html>"
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    inner class Bridge {
        @JavascriptInterface
        fun onWordTap(word: String, para: Int, context: String) {
            runOnUiThread {
                DefinitionPopup.show(this@AiSummaryActivity, word, tts, bookPath, context)
            }
        }

        @JavascriptInterface
        fun onSelectionChanged(text: String) {
            lastSelection = text
        }

        @JavascriptInterface
        fun onBlankTap() {
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

    private fun handleSelectionAction(action: String) {
        val text = lastSelection.trim()
        if (text.isEmpty()) return
        when (action) {
            "note" -> NotesUi.showAddNote(this, text) { noteText ->
                Db.get(this).addNote(bookPath, 0, page.coerceAtLeast(0), text, noteText)
                Toast.makeText(this, "Note saved", Toast.LENGTH_SHORT).show()
            }
            "define" -> {
                val first = text.split(Regex("\\s+")).firstOrNull() ?: return
                DefinitionPopup.show(this, first, tts, bookPath, text)
            }
            "wordlist" -> {
                val first = text.split(Regex("\\s+")).firstOrNull()?.lowercase() ?: return
                Db.get(this).addWord(bookPath, first, "")
                Toast.makeText(this, "\"$first\" saved to word list", Toast.LENGTH_SHORT).show()
            }
            "speak" -> tts?.speakOnce(text)
            else -> {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("text", text))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
        webView.evaluateJavascript("clearSelection()", null)
    }

    // ------------------------------------------------ generation

    private fun start() {
        if (working) return
        val key = Prefs.apiKey(this)
        if (key.isEmpty()) {
            Toast.makeText(
                this,
                "Add your free Gemini API key first (Settings). Get one at aistudio.google.com",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        val whole = findViewById<RadioButton>(R.id.scopeWhole).isChecked
        val status = findViewById<TextView>(R.id.txtStatus)
        working = true
        status.text = "Reading the book…"
        render("<p style=\"opacity:.6\">Working…</p>")

        Thread {
            try {
                val title = File(bookPath).nameWithoutExtension
                val text = extractText(whole)
                if (text.isBlank()) {
                    runOnUiThread {
                        status.text = "No text found"
                        render(
                            "<p>This book has no readable text in that range. " +
                                "If it is a scanned PDF, open it and use " +
                                "<b>Recognise text</b> in the menu first.</p>"
                        )
                        working = false
                    }
                    return@Thread
                }
                val summary = GeminiClient.summarize(
                    key, Prefs.model(this), title, text
                ) { msg -> runOnUiThread { status.text = msg } }
                runOnUiThread {
                    resultRaw = summary
                    resultPlain = TextFormat.markdownToPlain(summary)
                    render(TextFormat.markdownToHtml(summary))
                    status.text = "Done"
                    findViewById<View>(R.id.actionBar).visibility = View.VISIBLE
                    findViewById<View>(R.id.setupBox).visibility = View.GONE
                    working = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Failed"
                    render("<p><b>Could not get a summary.</b></p><p>${
                        (e.message ?: "Unknown error")
                            .replace("&", "&amp;").replace("<", "&lt;")
                    }</p>")
                    working = false
                }
            }
        }.start()
    }

    private fun extractText(whole: Boolean): String {
        val f = File(bookPath)
        return when (f.extension.lowercase()) {
            "pdf" -> {
                PdfTextExtractor.preloadOcr(this, bookPath)
                val count = PdfTextExtractor.pageCount(bookPath)
                val range = if (whole) 1..count else {
                    val center = page.coerceIn(1, count.coerceAtLeast(1))
                    (center - 4).coerceAtLeast(1)..(center + 5).coerceAtMost(count)
                }
                val sb = StringBuilder()
                for (p in range) {
                    sb.append(PdfTextExtractor.pageText(bookPath, p)).append('\n')
                    if (sb.length > 500000) break
                }
                sb.toString()
            }
            else -> {
                val parsed = EpubParser.parse(this, f)
                val chapters = if (whole || chapter !in parsed.chapters.indices) parsed.chapters
                else listOf(parsed.chapters[chapter])
                val sb = StringBuilder()
                for (c in chapters) {
                    sb.append(EpubParser.chapterText(c)).append('\n')
                    if (sb.length > 500000) break
                }
                sb.toString()
            }
        }
    }

    private fun save() {
        if (resultPlain.isEmpty()) return
        try {
            Storage.ensureFolders()
            val name = Storage.safeName(File(bookPath).nameWithoutExtension) + " - summary.txt"
            val f = File(Storage.summaries, name)
            f.writeText(
                "AI summary of: ${File(bookPath).name}\n" +
                    "Generated: ${
                        java.text.SimpleDateFormat(
                            "yyyy-MM-dd HH:mm", java.util.Locale.US
                        ).format(java.util.Date())
                    }\n\n" + resultPlain
            )
            Toast.makeText(this, "Saved to EbookReader/Summaries/$name", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun share() {
        if (resultPlain.isEmpty()) return
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(
                        Intent.EXTRA_SUBJECT,
                        "Summary of ${File(bookPath).nameWithoutExtension}"
                    )
                    putExtra(Intent.EXTRA_TEXT, resultPlain)
                },
                "Share summary"
            )
        )
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
