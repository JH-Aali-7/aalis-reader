package com.aali.ebookreader

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

data class Highlight(
    val id: Long,
    val bookPath: String,
    val chapter: Int,
    val page: Int,
    val text: String,
    val created: Long
)

data class Bookmark(
    val id: Long,
    val bookPath: String,
    val chapter: Int,
    val page: Int,
    val label: String,
    val created: Long
)

data class Progress(val chapter: Int, val page: Int, val scroll: Double)

data class Note(
    val id: Long,
    val bookPath: String,
    val chapter: Int,
    val page: Int,
    val refText: String,
    val text: String,
    val created: Long
)

data class VocabWord(
    val id: Long,
    val bookPath: String,
    val word: String,
    val meaning: String,
    val created: Long
)

class Db(context: Context) : SQLiteOpenHelper(context.applicationContext, "reader.db", null, 4) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE progress (book TEXT PRIMARY KEY, chapter INTEGER DEFAULT 0, " +
                "page INTEGER DEFAULT 0, scroll REAL DEFAULT 0, last_opened INTEGER DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE highlights (id INTEGER PRIMARY KEY AUTOINCREMENT, book TEXT, " +
                "chapter INTEGER, page INTEGER, text TEXT, created INTEGER)"
        )
        db.execSQL(
            "CREATE TABLE bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT, book TEXT, " +
                "chapter INTEGER, page INTEGER, label TEXT, created INTEGER)"
        )
        db.execSQL(NOTES_TABLE)
        db.execSQL(SEARCH_TABLE)
        db.execSQL(VOCAB_TABLE)
        db.execSQL(OCR_TABLE)
        db.execSQL(TIME_TABLE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL(NOTES_TABLE)
        if (oldVersion < 3) {
            db.execSQL(SEARCH_TABLE)
            db.execSQL(VOCAB_TABLE)
        }
        if (oldVersion < 4) {
            db.execSQL(OCR_TABLE)
            db.execSQL(TIME_TABLE)
        }
    }

    // ---------- progress ----------

    fun saveProgress(book: String, chapter: Int, page: Int, scroll: Double) {
        val cv = ContentValues().apply {
            put("book", book)
            put("chapter", chapter)
            put("page", page)
            put("scroll", scroll)
            put("last_opened", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("progress", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getProgress(book: String): Progress? {
        readableDatabase.rawQuery(
            "SELECT chapter, page, scroll FROM progress WHERE book=?", arrayOf(book)
        ).use { c ->
            if (c.moveToFirst()) return Progress(c.getInt(0), c.getInt(1), c.getDouble(2))
        }
        return null
    }

    fun lastOpened(book: String): Long {
        readableDatabase.rawQuery(
            "SELECT last_opened FROM progress WHERE book=?", arrayOf(book)
        ).use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return 0L
    }

    // ---------- highlights ----------

    fun addHighlight(book: String, chapter: Int, page: Int, text: String): Long {
        val cv = ContentValues().apply {
            put("book", book)
            put("chapter", chapter)
            put("page", page)
            put("text", text.trim())
            put("created", System.currentTimeMillis())
        }
        val id = writableDatabase.insert("highlights", null, cv)
        exportHighlightsTxt(book)
        return id
    }

    fun deleteHighlight(id: Long, book: String) {
        writableDatabase.delete("highlights", "id=?", arrayOf(id.toString()))
        exportHighlightsTxt(book)
    }

    fun highlightsFor(book: String): List<Highlight> {
        val out = ArrayList<Highlight>()
        readableDatabase.rawQuery(
            "SELECT id, book, chapter, page, text, created FROM highlights WHERE book=? " +
                "ORDER BY chapter, page, id", arrayOf(book)
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Highlight(c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3),
                        c.getString(4), c.getLong(5))
                )
            }
        }
        return out
    }

    /** Writes EbookReader/Highlights/<book name>.txt with page numbers, as required. */
    fun exportHighlightsTxt(book: String) {
        try {
            Storage.ensureFolders()
            val title = File(book).nameWithoutExtension
            val isPdf = book.lowercase().endsWith(".pdf")
            val f = File(Storage.highlights, Storage.safeName(title) + ".txt")
            val list = highlightsFor(book)
            if (list.isEmpty()) {
                if (f.exists()) f.delete()
                return
            }
            val sb = StringBuilder()
            sb.append("Highlights for: ").append(title).append("\n")
            sb.append("=".repeat(40)).append("\n\n")
            for (h in list) {
                val where = if (isPdf) "[Page ${h.page}]"
                else "[Chapter ${h.chapter + 1} - Page ${h.page}]"
                sb.append(where).append("\n").append(h.text).append("\n\n")
            }
            f.writeText(sb.toString())
        } catch (_: Exception) {
        }
    }

    // ---------- notes ----------

    fun addNote(book: String, chapter: Int, page: Int, refText: String, text: String): Long {
        val cv = ContentValues().apply {
            put("book", book)
            put("chapter", chapter)
            put("page", page)
            put("ref_text", refText.trim())
            put("text", text.trim())
            put("created", System.currentTimeMillis())
        }
        val id = writableDatabase.insert("notes", null, cv)
        exportNotesTxt(book)
        return id
    }

    fun updateNote(id: Long, book: String, text: String) {
        val cv = ContentValues().apply { put("text", text.trim()) }
        writableDatabase.update("notes", cv, "id=?", arrayOf(id.toString()))
        exportNotesTxt(book)
    }

    fun deleteNote(id: Long, book: String) {
        writableDatabase.delete("notes", "id=?", arrayOf(id.toString()))
        exportNotesTxt(book)
    }

    fun notesFor(book: String): List<Note> {
        val out = ArrayList<Note>()
        readableDatabase.rawQuery(
            "SELECT id, book, chapter, page, ref_text, text, created FROM notes WHERE book=? " +
                "ORDER BY chapter, page, id", arrayOf(book)
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Note(c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3),
                        c.getString(4) ?: "", c.getString(5) ?: "", c.getLong(6))
                )
            }
        }
        return out
    }

    /** Writes EbookReader/Notes/<book name>.txt with page numbers. */
    fun exportNotesTxt(book: String) {
        try {
            Storage.ensureFolders()
            val title = File(book).nameWithoutExtension
            val isPdf = book.lowercase().endsWith(".pdf")
            val f = File(Storage.notes, Storage.safeName(title) + ".txt")
            val list = notesFor(book)
            if (list.isEmpty()) {
                if (f.exists()) f.delete()
                return
            }
            val sb = StringBuilder()
            sb.append("Notes for: ").append(title).append("\n")
            sb.append("=".repeat(40)).append("\n\n")
            for (n in list) {
                val where = if (isPdf) "[Page ${n.page}]"
                else "[Chapter ${n.chapter + 1} - Page ${n.page}]"
                sb.append(where).append("\n")
                if (n.refText.isNotEmpty()) {
                    sb.append("About: \"").append(n.refText.take(200)).append("\"\n")
                }
                sb.append("Note: ").append(n.text).append("\n\n")
            }
            f.writeText(sb.toString())
        } catch (_: Exception) {
        }
    }

    // ---------- search history ----------

    fun addSearch(book: String, query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        writableDatabase.delete("searches", "book=? AND query=?", arrayOf(book, q))
        val cv = ContentValues().apply {
            put("book", book)
            put("query", q)
            put("created", System.currentTimeMillis())
        }
        writableDatabase.insert("searches", null, cv)
        // keep the 40 most recent per book
        writableDatabase.execSQL(
            "DELETE FROM searches WHERE book=? AND id NOT IN " +
                "(SELECT id FROM searches WHERE book=? ORDER BY created DESC LIMIT 40)",
            arrayOf(book, book)
        )
    }

    fun searchHistory(book: String, limit: Int = 20): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT query FROM searches WHERE book=? ORDER BY created DESC LIMIT $limit",
            arrayOf(book)
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }

    /** Recent searches across every book, for the global history view. */
    fun searchHistoryAll(limit: Int = 60): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        readableDatabase.rawQuery(
            "SELECT query, book FROM searches ORDER BY created DESC LIMIT $limit", null
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0) to c.getString(1)) }
        return out
    }

    fun clearSearchHistory(book: String) {
        writableDatabase.delete("searches", "book=?", arrayOf(book))
    }

    // ---------- word list (per book and master) ----------

    fun addWord(book: String, word: String, meaning: String): Boolean {
        val w = word.trim().lowercase()
        if (w.isEmpty()) return false
        val exists = readableDatabase.rawQuery(
            "SELECT 1 FROM vocab WHERE book=? AND word=? LIMIT 1", arrayOf(book, w)
        ).use { it.moveToFirst() }
        if (exists) return false
        val cv = ContentValues().apply {
            put("book", book)
            put("word", w)
            put("meaning", meaning.trim())
            put("created", System.currentTimeMillis())
        }
        writableDatabase.insert("vocab", null, cv)
        exportWordListTxt(book)
        return true
    }

    fun deleteWord(id: Long, book: String) {
        writableDatabase.delete("vocab", "id=?", arrayOf(id.toString()))
        exportWordListTxt(book)
    }

    fun wordsFor(book: String): List<VocabWord> {
        val out = ArrayList<VocabWord>()
        readableDatabase.rawQuery(
            "SELECT id, book, word, meaning, created FROM vocab WHERE book=? ORDER BY word",
            arrayOf(book)
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    VocabWord(c.getLong(0), c.getString(1), c.getString(2),
                        c.getString(3) ?: "", c.getLong(4))
                )
            }
        }
        return out
    }

    fun allWords(): List<VocabWord> {
        val out = ArrayList<VocabWord>()
        readableDatabase.rawQuery(
            "SELECT id, book, word, meaning, created FROM vocab ORDER BY word", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    VocabWord(c.getLong(0), c.getString(1), c.getString(2),
                        c.getString(3) ?: "", c.getLong(4))
                )
            }
        }
        return out
    }

    fun wordCount(book: String): Int =
        readableDatabase.rawQuery(
            "SELECT count(*) FROM vocab WHERE book=?", arrayOf(book)
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /**
     * Writes EbookReader/Wordlist/<book name>.txt for this book and refreshes
     * the combined EbookReader/Wordlist/_ALL BOOKS.txt master list.
     */
    fun exportWordListTxt(book: String) {
        try {
            Storage.ensureFolders()
            val title = File(book).nameWithoutExtension
            val perBook = File(Storage.wordlists, Storage.safeName(title) + ".txt")
            val list = wordsFor(book)
            if (list.isEmpty()) {
                if (perBook.exists()) perBook.delete()
            } else {
                val sb = StringBuilder()
                sb.append("Word list for: ").append(title).append("\n")
                sb.append("Total words: ").append(list.size).append("\n")
                sb.append("=".repeat(40)).append("\n\n")
                for (v in list) {
                    sb.append(v.word).append("\n")
                    if (v.meaning.isNotEmpty()) {
                        sb.append("   ").append(v.meaning.replace("\n", "\n   ")).append("\n")
                    }
                    sb.append("\n")
                }
                perBook.writeText(sb.toString())
            }

            val all = allWords()
            val master = File(Storage.wordlists, "_ALL BOOKS.txt")
            if (all.isEmpty()) {
                if (master.exists()) master.delete()
                return
            }
            val sb = StringBuilder()
            sb.append("Master word list (all books)\n")
            sb.append("Total words: ").append(all.size).append("\n")
            sb.append("=".repeat(40)).append("\n\n")
            for (v in all) {
                sb.append(v.word)
                    .append("   [").append(File(v.bookPath).nameWithoutExtension).append("]\n")
                if (v.meaning.isNotEmpty()) {
                    sb.append("   ").append(v.meaning.replace("\n", "\n   ")).append("\n")
                }
                sb.append("\n")
            }
            master.writeText(sb.toString())
        } catch (_: Exception) {
        }
    }

    // ---------- OCR results for scanned pages ----------

    fun saveOcr(book: String, page: Int, data: PdfPageText, text: String) {
        try {
            val arr = com.google.gson.JsonArray()
            for (w in data.words) {
                val o = com.google.gson.JsonObject()
                o.addProperty("t", w.text)
                o.addProperty("l", w.rect.left)
                o.addProperty("y", w.rect.top)
                o.addProperty("r", w.rect.right)
                o.addProperty("b", w.rect.bottom)
                arr.add(o)
            }
            val cv = ContentValues().apply {
                put("book", book)
                put("page", page)
                put("text", text)
                put("words", arr.toString())
                put("w", data.widthPt)
                put("h", data.heightPt)
            }
            writableDatabase.insertWithOnConflict(
                "ocr", null, cv, SQLiteDatabase.CONFLICT_REPLACE
            )
        } catch (_: Exception) {
        }
    }

    fun ocrPageCount(book: String): Int =
        readableDatabase.rawQuery(
            "SELECT count(*) FROM ocr WHERE book=?", arrayOf(book)
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun allOcr(book: String): Map<Int, Pair<PdfPageText, String>> {
        val out = HashMap<Int, Pair<PdfPageText, String>>()
        try {
            readableDatabase.rawQuery(
                "SELECT page, text, words, w, h FROM ocr WHERE book=?", arrayOf(book)
            ).use { c ->
                while (c.moveToNext()) {
                    val page = c.getInt(0)
                    val text = c.getString(1) ?: ""
                    val json = c.getString(2) ?: "[]"
                    val boxes = ArrayList<Pair<String, android.graphics.RectF>>()
                    val arr = com.google.gson.JsonParser.parseString(json).asJsonArray
                    for (e in arr) {
                        val o = e.asJsonObject
                        boxes.add(
                            o.get("t").asString to android.graphics.RectF(
                                o.get("l").asFloat, o.get("y").asFloat,
                                o.get("r").asFloat, o.get("b").asFloat
                            )
                        )
                    }
                    out[page] = PdfTextExtractor.fromWordBoxes(
                        boxes, c.getFloat(3), c.getFloat(4)
                    ) to text
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    // ---------- reading time ----------

    fun addReadingSeconds(book: String, seconds: Long) {
        if (seconds < 3) return
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        writableDatabase.execSQL(
            "INSERT INTO reading_time (day, book, seconds) VALUES (?,?,?) " +
                "ON CONFLICT(day, book) DO UPDATE SET seconds = seconds + ?",
            arrayOf(day, book, seconds, seconds)
        )
    }

    /** Total seconds per day, newest first. */
    fun dailyTotals(limitDays: Int = 30): List<Pair<String, Long>> {
        val out = ArrayList<Pair<String, Long>>()
        readableDatabase.rawQuery(
            "SELECT day, SUM(seconds) FROM reading_time GROUP BY day " +
                "ORDER BY day DESC LIMIT $limitDays", null
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0) to c.getLong(1)) }
        return out
    }

    fun totalSeconds(): Long =
        readableDatabase.rawQuery("SELECT COALESCE(SUM(seconds),0) FROM reading_time", null)
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun secondsPerBook(limit: Int = 10): List<Pair<String, Long>> {
        val out = ArrayList<Pair<String, Long>>()
        readableDatabase.rawQuery(
            "SELECT book, SUM(seconds) s FROM reading_time GROUP BY book " +
                "ORDER BY s DESC LIMIT $limit", null
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0) to c.getLong(1)) }
        return out
    }

    fun countHighlights(): Int =
        readableDatabase.rawQuery("SELECT count(*) FROM highlights", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun countNotes(): Int =
        readableDatabase.rawQuery("SELECT count(*) FROM notes", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun countWords(): Int =
        readableDatabase.rawQuery("SELECT count(*) FROM vocab", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    // ---------- bookmarks ----------

    fun addBookmark(book: String, chapter: Int, page: Int, label: String) {
        val cv = ContentValues().apply {
            put("book", book)
            put("chapter", chapter)
            put("page", page)
            put("label", label)
            put("created", System.currentTimeMillis())
        }
        writableDatabase.insert("bookmarks", null, cv)
    }

    fun deleteBookmark(id: Long) {
        writableDatabase.delete("bookmarks", "id=?", arrayOf(id.toString()))
    }

    fun bookmarksFor(book: String): List<Bookmark> {
        val out = ArrayList<Bookmark>()
        readableDatabase.rawQuery(
            "SELECT id, book, chapter, page, label, created FROM bookmarks WHERE book=? " +
                "ORDER BY chapter, page", arrayOf(book)
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Bookmark(c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3),
                        c.getString(4), c.getLong(5))
                )
            }
        }
        return out
    }

    companion object {
        private const val NOTES_TABLE =
            "CREATE TABLE IF NOT EXISTS notes (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "book TEXT, chapter INTEGER, page INTEGER, ref_text TEXT, text TEXT, " +
                "created INTEGER)"

        private const val SEARCH_TABLE =
            "CREATE TABLE IF NOT EXISTS searches (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "book TEXT, query TEXT, created INTEGER)"

        private const val VOCAB_TABLE =
            "CREATE TABLE IF NOT EXISTS vocab (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "book TEXT, word TEXT, meaning TEXT, created INTEGER)"

        private const val OCR_TABLE =
            "CREATE TABLE IF NOT EXISTS ocr (book TEXT, page INTEGER, text TEXT, " +
                "words TEXT, w REAL, h REAL, PRIMARY KEY (book, page))"

        private const val TIME_TABLE =
            "CREATE TABLE IF NOT EXISTS reading_time (day TEXT, book TEXT, " +
                "seconds INTEGER, PRIMARY KEY (day, book))"

        @Volatile private var instance: Db? = null
        fun get(context: Context): Db =
            instance ?: synchronized(this) {
                instance ?: Db(context).also { instance = it }
            }

        /** Close and forget the singleton (used before restoring a backup). */
        fun reset() {
            synchronized(this) {
                try {
                    instance?.close()
                } catch (_: Exception) {
                }
                instance = null
            }
        }
    }
}
