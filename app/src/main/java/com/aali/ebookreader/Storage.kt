package com.aali.ebookreader

import android.os.Environment
import java.io.File

/**
 * All app data lives in a visible folder on shared storage:
 *   /storage/emulated/0/EbookReader/
 *     Books/       put or import your PDF, EPUB and TXT books here
 *     Highlights/  one txt file per book with page numbers
 *     Summaries/   saved AI summaries
 *     Backups/     exported backup zips
 */
object Storage {

    val root: File
        get() = File(Environment.getExternalStorageDirectory(), "EbookReader")

    val books: File get() = File(root, "Books")
    val highlights: File get() = File(root, "Highlights")
    val notes: File get() = File(root, "Notes")
    val wordlists: File get() = File(root, "Wordlist")
    val summaries: File get() = File(root, "Summaries")
    val exports: File get() = File(root, "Exports")
    val backups: File get() = File(root, "Backups")

    fun ensureFolders() {
        try {
            listOf(root, books, highlights, notes, wordlists, summaries, exports, backups)
                .forEach { it.mkdirs() }
        } catch (_: Exception) {
        }
    }

    fun safeName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "book" }

    fun listBooks(): List<File> {
        ensureFolders()
        val exts = setOf("pdf", "epub", "txt", "pptx", "docx")
        return (books.listFiles() ?: emptyArray())
            .filter { it.isFile && it.extension.lowercase() in exts }
            .sortedBy { it.name.lowercase() }
    }
}
