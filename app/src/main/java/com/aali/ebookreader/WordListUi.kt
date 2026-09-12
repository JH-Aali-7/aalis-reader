package com.aali.ebookreader

import android.app.Activity
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Viewer for the difficult word list of a book, plus the master list. */
object WordListUi {

    fun show(activity: Activity, bookPath: String) {
        val db = Db.get(activity)
        val forBook = db.wordsFor(bookPath)
        val all = db.allWords()
        val title = File(bookPath).nameWithoutExtension

        if (forBook.isEmpty() && all.isEmpty()) {
            Ui.toast(activity, "No saved words yet. Tap a word, then Save word.")
            return
        }

        val options = arrayOf(
            "This book (${forBook.size} words)",
            "All books (${all.size} words)"
        )
        Ui.builder(activity)
            .setTitle("Word list")
            .setItems(options) { _, which ->
                if (which == 0) showList(activity, bookPath, title, forBook, false)
                else showList(activity, bookPath, "All books", all, true)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showList(
        activity: Activity,
        bookPath: String,
        title: String,
        words: List<VocabWord>,
        showBook: Boolean
    ) {
        if (words.isEmpty()) {
            Ui.toast(activity, "Nothing saved here yet")
            return
        }
        val labels = words.map { v ->
            if (showBook) "${v.word}   ·   ${File(v.bookPath).nameWithoutExtension}"
            else v.word
        }.toTypedArray()

        Ui.builder(activity)
            .setTitle("$title (${words.size})")
            .setItems(labels) { _, which ->
                val v = words[which]
                Ui.builder(activity)
                    .setTitle(v.word)
                    .setMessage(
                        v.meaning.ifEmpty { "(no meaning saved)" } +
                            "\n\nFrom: " + File(v.bookPath).nameWithoutExtension
                    )
                    .setPositiveButton("Close", null)
                    .setNegativeButton("Delete") { _, _ ->
                        Db.get(activity).deleteWord(v.id, v.bookPath)
                        Ui.toast(activity, "Removed from word list")
                    }
                    .show()
            }
            .setNeutralButton("Share as text") { _, _ -> shareFile(activity, bookPath, showBook) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun shareFile(activity: Activity, bookPath: String, master: Boolean) {
        try {
            Db.get(activity).exportWordListTxt(bookPath)
            val f = if (master) File(Storage.wordlists, "_ALL BOOKS.txt")
            else File(
                Storage.wordlists,
                Storage.safeName(File(bookPath).nameWithoutExtension) + ".txt"
            )
            if (!f.exists()) {
                Ui.toast(activity, "File not created yet")
                return
            }
            val uri = FileProvider.getUriForFile(
                activity, "com.aali.ebookreader.fileprovider", f
            )
            activity.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "Share word list"
                )
            )
        } catch (e: Exception) {
            Ui.toast(activity, "Could not share: ${e.message}")
        }
    }
}
