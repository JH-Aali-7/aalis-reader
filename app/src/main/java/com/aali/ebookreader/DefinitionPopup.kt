package com.aali.ebookreader

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * The meaning panel. Shows the offline dictionary first, then can fetch
 * Wiktionary, Wikipedia or an AI explanation of the word in its sentence.
 */
object DefinitionPopup {

    fun show(
        activity: Activity,
        word: String,
        tts: TtsManager?,
        bookPath: String? = null,
        contextText: String = "",
        onHighlightWord: ((String) -> Unit)? = null,
        onReadFromHere: (() -> Unit)? = null
    ) {
        val dialog = BottomSheetDialog(activity)
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val night = (activity.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val accent = if (night) Color.parseColor("#BFA1FA") else Color.parseColor("#7C3AED")
        val fg = if (night) Color.parseColor("#EBE3F5") else Color.parseColor("#1C1520")
        val dim = if (night) Color.parseColor("#9C93A8") else Color.parseColor("#6B6376")

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(10))
        }

        root.addView(android.view.View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(10)
            }
            setBackgroundColor(
                if (night) Color.parseColor("#4A3D5C") else Color.parseColor("#DDD1F2")
            )
        })

        root.addView(TextView(activity).apply {
            text = word
            textSize = 25f
            setTextColor(accent)
            setTypeface(typeface, Typeface.BOLD)
        })

        val bodyView = TextView(activity).apply {
            textSize = 16f
            setTextColor(fg)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(8), 0, dp(10))
            setTextIsSelectable(true)
            text = "Looking up…"
        }
        root.addView(ScrollView(activity).apply {
            addView(bodyView)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        })

        val offlineText = SpannableStringBuilder()
        val extras = LinkedHashMap<String, String>()   // source -> text
        var firstMeaning = ""
        var alreadySaved = bookPath != null &&
            Db.get(activity).wordsFor(bookPath).any { it.word == DictionaryHelper.clean(word) }

        fun redraw() {
            val sb = SpannableStringBuilder()
            sb.append(offlineText)
            for ((src, text) in extras) {
                if (sb.isNotEmpty()) sb.append("\n")
                val start = sb.length
                sb.append("— $src —\n")
                sb.setSpan(
                    ForegroundColorSpan(accent), start, sb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                sb.setSpan(
                    StyleSpan(Typeface.BOLD), start, sb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                sb.setSpan(
                    RelativeSizeSpan(0.85f), start, sb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                sb.append(text).append("\n\n")
            }
            bodyView.text = sb
        }

        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun addBtn(label: String, primary: Boolean = false): Button {
            val b = Button(activity, null, android.R.attr.borderlessButtonStyle).apply {
                text = label
                isAllCaps = false
                textSize = 14f
                setTypeface(typeface, if (primary) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(accent)
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(11), dp(6), dp(11), dp(6))
            }
            buttonRow.addView(b)
            return b
        }

        /** Runs an online lookup and appends the answer. */
        fun fetch(name: String, btn: Button, work: () -> OnlineResult?) {
            if (extras.containsKey(name)) return
            if (!OnlineDictionary.isOnline(activity)) {
                Ui.toast(activity, "No internet connection")
                return
            }
            btn.isEnabled = false
            btn.text = "…"
            Thread {
                val res = work()
                activity.runOnUiThread {
                    btn.isEnabled = true
                    if (res == null) {
                        btn.text = "$name ✕"
                        Ui.toast(activity, "$name has nothing for \"$word\"")
                    } else {
                        btn.text = "✓ $name"
                        extras[res.source] = res.text
                        redraw()
                    }
                }
            }.start()
        }

        addBtn("🔊").setOnClickListener { tts?.speakOnce(word) }

        if (bookPath != null) {
            val saveBtn = addBtn(if (alreadySaved) "✓ Saved" else "＋ Save", true)
            saveBtn.setOnClickListener {
                if (alreadySaved) {
                    Ui.toast(activity, "Already in this book's word list")
                    return@setOnClickListener
                }
                Db.get(activity).addWord(bookPath, word, firstMeaning)
                alreadySaved = true
                saveBtn.text = "✓ Saved"
                Ui.toast(activity, "Saved to word list (txt file updated)")
            }
        }

        lateinit var wiktBtn: Button
        lateinit var wikiBtn: Button
        wiktBtn = addBtn("Wiktionary")
        wiktBtn.setOnClickListener {
            fetch("Wiktionary", wiktBtn) { OnlineDictionary.wiktionary(word) }
        }
        wikiBtn = addBtn("Wikipedia")
        wikiBtn.setOnClickListener {
            fetch("Wikipedia", wikiBtn) { OnlineDictionary.wikipedia(word) }
        }
        val aiBtn = addBtn("Ask AI")
        aiBtn.setOnClickListener {
            if (Prefs.apiKey(activity).isEmpty()) {
                Ui.toast(activity, "Add your Gemini API key in Settings to use this")
                return@setOnClickListener
            }
            fetch("AI explanation", aiBtn) {
                OnlineDictionary.aiExplain(activity, word, contextText)
            }
        }

        if (onHighlightWord != null) {
            addBtn("Highlight").setOnClickListener {
                onHighlightWord(word)
                dialog.dismiss()
            }
        }
        if (onReadFromHere != null) {
            addBtn("Read on").setOnClickListener {
                dialog.dismiss()
                onReadFromHere()
            }
        }
        addBtn("Close").setOnClickListener { dialog.dismiss() }

        root.addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(buttonRow)
        })

        /** Looks the term up online by itself when nothing was found locally. */
        fun autoOnline(showNotFound: Boolean) {
            Thread {
                val wikt = OnlineDictionary.wiktionary(word)
                val wiki = if (wikt == null) OnlineDictionary.wikipedia(word) else null
                activity.runOnUiThread {
                    if (wikt == null && wiki == null) {
                        if (showNotFound) {
                            offlineText.clear()
                            offlineText.append(
                                "No definition found offline or online.\n" +
                                    "Try \"Ask AI\" for an explanation in context.\n\n"
                            )
                        }
                    } else if (showNotFound) {
                        offlineText.clear()
                    }
                    wikt?.let {
                        extras[it.source] = it.text
                        wiktBtn.text = "✓ Wiktionary"
                        if (firstMeaning.isEmpty()) {
                            firstMeaning = it.text.lineSequence()
                                .firstOrNull { l -> l.startsWith("1. ") }
                                ?.removePrefix("1. ") ?: ""
                        }
                    }
                    wiki?.let {
                        extras[it.source] = it.text
                        wikiBtn.text = "✓ Wikipedia"
                        if (firstMeaning.isEmpty()) {
                            firstMeaning = it.text.substringAfter("\n\n").take(220)
                        }
                    }
                    redraw()
                }
            }.start()
        }

        root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (activity.resources.displayMetrics.heightPixels * 0.55).toInt()
        )
        dialog.setContentView(root)
        dialog.show()

        Thread {
            var entries = DictionaryHelper.lookup(activity, word)
            // a phrase with no entry of its own: explain each word instead
            var perWord: List<Pair<String, List<DictEntry>>> = emptyList()
            if (entries.isEmpty()) {
                val parts = DictionaryHelper.wordsOf(word)
                if (parts.size > 1) {
                    perWord = parts.map { it to DictionaryHelper.lookup(activity, it) }
                        .filter { it.second.isNotEmpty() }
                }
            }
            activity.runOnUiThread {
                offlineText.clear()
                if (entries.isEmpty() && perWord.isNotEmpty()) {
                    val head = offlineText.length
                    offlineText.append("No entry for the whole phrase, so here is each word.\n\n")
                    offlineText.setSpan(
                        ForegroundColorSpan(dim), head, offlineText.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    offlineText.setSpan(
                        RelativeSizeSpan(0.85f), head, offlineText.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    for ((w, list) in perWord) {
                        val st = offlineText.length
                        offlineText.append(w).append("\n")
                        offlineText.setSpan(
                            StyleSpan(Typeface.BOLD), st, offlineText.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        offlineText.setSpan(
                            ForegroundColorSpan(accent), st, offlineText.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        for (e in list.take(2)) {
                            val pos = DictionaryHelper.posLabel(e.pos)
                            if (pos.isNotEmpty()) offlineText.append(pos).append(": ")
                            offlineText.append(
                                e.defs.lineSequence().firstOrNull()?.trim()
                                    ?.removePrefix("1. ") ?: ""
                            ).append("\n")
                        }
                        offlineText.append("\n")
                    }
                    firstMeaning = perWord.joinToString("; ") { (w, l) ->
                        w + ": " + (l.first().defs.lineSequence().firstOrNull()
                            ?.trim()?.removePrefix("1. ") ?: "")
                    }
                    redraw()
                    if (OnlineDictionary.isOnline(activity)) autoOnline(false)
                    return@runOnUiThread
                }
                if (entries.isEmpty()) {
                    offlineText.append("Not in the offline dictionary.\n")
                    if (OnlineDictionary.isOnline(activity)) {
                        offlineText.append("Searching online…\n\n")
                    } else {
                        offlineText.append(
                            "Connect to the internet and tap Wiktionary or " +
                                "Wikipedia above.\n\n"
                        )
                    }
                } else {
                    firstMeaning = entries.take(2).joinToString("\n") { e ->
                        val p = DictionaryHelper.posLabel(e.pos)
                        (if (p.isEmpty()) "" else "$p: ") +
                            (e.defs.lineSequence().firstOrNull()?.trim()
                                ?.removePrefix("1. ") ?: "")
                    }.trim()

                    for (e in entries) {
                        val start = offlineText.length
                        val pos = DictionaryHelper.posLabel(e.pos)
                        val src = DictionaryHelper.sourceLabel(e.source)
                        val header = when {
                            pos.isNotEmpty() && src.isNotEmpty() -> "$pos  ·  $src"
                            pos.isNotEmpty() -> pos
                            src.isNotEmpty() -> src
                            else -> "definition"
                        }
                        offlineText.append(header).append("\n")
                        offlineText.setSpan(
                            StyleSpan(Typeface.BOLD_ITALIC), start, offlineText.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        offlineText.setSpan(
                            ForegroundColorSpan(if (e.source == "science") accent else dim),
                            start, offlineText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        offlineText.setSpan(
                            RelativeSizeSpan(0.85f), start, offlineText.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        offlineText.append(e.defs.trim()).append("\n\n")
                    }
                }
                redraw()

                // nothing offline: go and find it online straight away
                if (entries.isEmpty() && OnlineDictionary.isOnline(activity)) {
                    autoOnline(true)
                }
            }
        }.start()
    }
}
