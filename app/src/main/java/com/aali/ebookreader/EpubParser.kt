package com.aali.ebookreader

import android.content.Context
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.io.FileOutputStream
import java.util.TreeMap
import java.util.zip.ZipFile

data class Chapter(val title: String, val file: File)
data class ParsedBook(val title: String, val chapters: List<Chapter>, val coverImage: File?)

/**
 * Extracts EPUB files (zip of XHTML) into the app cache and resolves the
 * reading order (spine). Also converts TXT files into HTML chapters so both
 * formats share one reader.
 */
object EpubParser {

    fun parse(context: Context, book: File): ParsedBook {
        return when (book.extension.lowercase()) {
            "txt" -> parseTxt(context, book)
            "pptx" -> parseOffice(context, book, presentation = true)
            "docx" -> parseOffice(context, book, presentation = false)
            else -> parseEpub(context, book)
        }
    }

    // ------------------------------------------------ PowerPoint and Word

    /**
     * PowerPoint and Word files keep their real design. OfficeRenderer turns
     * every slide, and the Word document itself, into a fixed size page laid
     * out exactly like the original, which the reader then shows the way it
     * shows a PDF page. If a file is too unusual to lay out, the plain text
     * reader below takes over so the book still opens.
     */
    private fun parseOffice(context: Context, book: File, presentation: Boolean): ParsedBook {
        val dir = extractDir(context, book)
        // a new marker name, so pages built by the old text only reader are
        // thrown away and rebuilt with the real layout
        val marker = File(dir, ".layout1")
        val index = File(dir, "index.tsv")

        if (marker.exists() && index.exists()) {
            cachedOffice(book, dir, index)?.let { return it }
        }

        dir.deleteRecursively()
        dir.mkdirs()

        val rendered = try {
            if (presentation) OfficeRenderer.renderPptx(context, book, dir)
            else OfficeRenderer.renderDocx(context, book, dir)
        } catch (e: Throwable) {
            null
        }

        val result =
            if (rendered != null && rendered.chapters.isNotEmpty()) rendered
            else {
                dir.deleteRecursively()
                dir.mkdirs()
                parseOfficeText(book, dir, presentation)
            }

        try {
            index.writeText(buildString {
                result.coverImage?.let { append("#cover\t").append(it.name).append('\n') }
                result.chapters.forEach { c ->
                    append(c.file.name).append('\t')
                        .append(c.title.replace('\t', ' ').replace('\n', ' ')).append('\n')
                }
            })
            marker.writeText("ok")
        } catch (e: Exception) {
            // caching is a convenience, never a reason to fail the open
        }
        return result
    }

    /** Rebuilds a book from pages laid out on an earlier open. */
    private fun cachedOffice(book: File, dir: File, index: File): ParsedBook? {
        return try {
            var cover: File? = null
            val chapters = ArrayList<Chapter>()
            for (line in index.readLines()) {
                val parts = line.split('\t')
                if (parts.size < 2) continue
                if (parts[0] == "#cover") {
                    cover = File(dir, parts[1]).takeIf { it.exists() }
                    continue
                }
                val f = File(dir, parts[0])
                if (f.exists()) chapters.add(Chapter(parts[1], f))
            }
            if (chapters.isEmpty()) null
            else ParsedBook(book.nameWithoutExtension, chapters, cover)
        } catch (e: Exception) {
            null
        }
    }

    /** Plain text fallback, used only when the real layout cannot be built. */
    private fun parseOfficeText(book: File, dir: File, presentation: Boolean): ParsedBook {
        val chapters = ArrayList<Chapter>()

        val slides = TreeMap<Int, String>()
        val notes = HashMap<Int, String>()
        var wordBody: String? = null
        var cover: File? = null

        ZipFile(book).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.isDirectory) continue
                val name = e.name.replace('\\', '/')
                when {
                    presentation && name.matches(Regex("ppt/slides/slide(\\d+)\\.xml")) -> {
                        val n = Regex("slide(\\d+)").find(name)!!.groupValues[1].toInt()
                        slides[n] = zip.getInputStream(e).bufferedReader().readText()
                    }
                    presentation &&
                        name.matches(Regex("ppt/notesSlides/notesSlide(\\d+)\\.xml")) -> {
                        val n = Regex("notesSlide(\\d+)").find(name)!!.groupValues[1].toInt()
                        notes[n] = zip.getInputStream(e).bufferedReader().readText()
                    }
                    !presentation && name == "word/document.xml" -> {
                        wordBody = zip.getInputStream(e).bufferedReader().readText()
                    }
                    name.startsWith("docProps/thumbnail") -> {
                        val out = File(dir, "cover." + name.substringAfterLast('.'))
                        zip.getInputStream(e).use { ins ->
                            FileOutputStream(out).use { os -> ins.copyTo(os) }
                        }
                        cover = out
                    }
                }
            }
        }

        if (presentation) {
            for ((n, xml) in slides) {
                val paras = officeParagraphs(xml, "a|t", "a|p")
                val noteText = notes[n]?.let { officeParagraphs(it, "a|t", "a|p") } ?: emptyList()
                val title = paras.firstOrNull()?.take(60)?.ifBlank { null } ?: "Slide $n"
                val sb = StringBuilder()
                sb.append("<!--title:Slide ").append(n).append(" · ").append(title)
                    .append("-->\n")
                sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>")
                sb.append("<style>.slide-no{opacity:.55;font-size:.8em;letter-spacing:.08em}")
                sb.append(".notes{margin-top:26px;padding-top:12px;")
                sb.append("border-top:1px solid rgba(124,58,237,.35);opacity:.85}")
                sb.append(".notes h4{margin:0 0 6px 0;font-size:.85em;letter-spacing:.06em}")
                sb.append("</style></head><body>")
                sb.append("<p class=\"slide-no\">SLIDE ").append(n).append(" OF ")
                    .append(slides.size).append("</p>")
                paras.forEachIndexed { i, t ->
                    val esc = escapeHtml(t)
                    if (i == 0) sb.append("<h2>").append(esc).append("</h2>")
                    else sb.append("<p>").append(esc).append("</p>")
                }
                if (paras.isEmpty()) sb.append("<p><i>This slide has no text.</i></p>")
                if (noteText.isNotEmpty()) {
                    sb.append("<div class=\"notes\"><h4>SPEAKER NOTES</h4>")
                    noteText.forEach { sb.append("<p>").append(escapeHtml(it)).append("</p>") }
                    sb.append("</div>")
                }
                sb.append("</body></html>")
                val f = File(dir, "slide%04d.html".format(n))
                f.writeText(sb.toString())
                chapters.add(Chapter("Slide $n · $title", f))
            }
        } else {
            val paras = wordBody?.let { officeParagraphs(it, "w|t", "w|p") } ?: emptyList()
            var part = 1
            var sb = StringBuilder()
            var count = 0
            fun flush() {
                if (sb.isEmpty()) return
                val f = File(dir, "part%04d.html".format(part))
                f.writeText(
                    "<!--title:Part " + part + "-->\n" +
                        "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/></head><body>" +
                        sb + "</body></html>"
                )
                chapters.add(Chapter("Part $part", f))
                part++
                sb = StringBuilder()
                count = 0
            }
            for (t in paras) {
                if (t.isBlank()) continue
                val esc = escapeHtml(t)
                // short lines in a Word file are usually headings
                if (t.length < 80 && !t.trimEnd().endsWith(".")) {
                    sb.append("<h3>").append(esc).append("</h3>")
                } else {
                    sb.append("<p>").append(esc).append("</p>")
                }
                count++
                if (count > 120) flush()
            }
            flush()
        }

        if (chapters.isEmpty()) {
            val f = File(dir, "part0001.html")
            f.writeText(
                "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/></head><body>" +
                    "<p>No readable text was found in this file.</p></body></html>"
            )
            chapters.add(Chapter("Empty", f))
        }
        return ParsedBook(book.nameWithoutExtension, chapters, cover)
    }

    /** Text of each paragraph in an Office XML part. */
    private fun officeParagraphs(xml: String, textTag: String, paraTag: String): List<String> {
        return try {
            val doc = Jsoup.parse(xml, "", Parser.xmlParser())
            doc.select(paraTag).map { p ->
                p.select(textTag).joinToString("") { it.text() }.trim()
            }.filter { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractDir(context: Context, book: File): File {
        val key = Storage.safeName(book.name) + "-" + (book.length() % 1000000)
        return File(context.cacheDir, "books/$key")
    }

    // ------------------------------------------------ EPUB

    private fun parseEpub(context: Context, book: File): ParsedBook {
        val dir = extractDir(context, book)
        val marker = File(dir, ".done")
        if (!marker.exists()) {
            dir.deleteRecursively()
            dir.mkdirs()
            ZipFile(book).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.isDirectory) continue
                    val name = e.name.replace('\\', '/')
                    if (name.contains("..")) continue
                    val out = File(dir, name)
                    out.parentFile?.mkdirs()
                    zip.getInputStream(e).use { ins ->
                        FileOutputStream(out).use { os -> ins.copyTo(os) }
                    }
                }
            }
            marker.writeText("ok")
        }

        // 1. container.xml -> OPF path
        val containerFile = File(dir, "META-INF/container.xml")
        var opfPath = ""
        if (containerFile.exists()) {
            val cdoc = Jsoup.parse(containerFile.readText(), "", Parser.xmlParser())
            opfPath = cdoc.select("rootfile").attr("full-path")
        }
        if (opfPath.isEmpty()) {
            opfPath = dir.walkTopDown().firstOrNull { it.extension.lowercase() == "opf" }
                ?.relativeTo(dir)?.path?.replace('\\', '/') ?: ""
        }
        val opfFile = File(dir, opfPath)
        val opfDir = opfFile.parentFile ?: dir

        var bookTitle = book.nameWithoutExtension
        val chapters = ArrayList<Chapter>()
        var cover: File? = null

        if (opfFile.exists()) {
            val opf = Jsoup.parse(opfFile.readText(), "", Parser.xmlParser())
            opf.select("metadata > dc|title, title").firstOrNull()?.text()?.let {
                if (it.isNotBlank()) bookTitle = it
            }
            val idToHref = HashMap<String, String>()
            val idToType = HashMap<String, String>()
            for (item in opf.select("manifest > item")) {
                idToHref[item.attr("id")] = item.attr("href")
                idToType[item.attr("id")] = item.attr("media-type")
            }
            // cover image
            val coverId = opf.select("meta[name=cover]").attr("content")
            val coverHref = idToHref[coverId]
                ?: idToHref.entries.firstOrNull {
                    it.key.contains("cover", true) &&
                        (idToType[it.key] ?: "").startsWith("image")
                }?.value
            if (coverHref != null) {
                val cf = File(opfDir, urlDecode(coverHref))
                if (cf.exists()) cover = cf
            }
            // spine order
            val titles = ncxTitles(opfDir, idToHref, opf)
            var n = 1
            for (itemref in opf.select("spine > itemref")) {
                val href = idToHref[itemref.attr("idref")] ?: continue
                val f = File(opfDir, urlDecode(href))
                if (f.exists()) {
                    val t = titles[f.canonicalPath] ?: "Chapter $n"
                    chapters.add(Chapter(t, f))
                    n++
                }
            }
        }
        if (chapters.isEmpty()) {
            // fallback: every html file in archive order
            var n = 1
            dir.walkTopDown()
                .filter { it.extension.lowercase() in setOf("xhtml", "html", "htm") }
                .sortedBy { it.path }
                .forEach {
                    chapters.add(Chapter("Chapter $n", it))
                    n++
                }
        }
        if (cover == null) {
            cover = dir.walkTopDown().firstOrNull {
                it.extension.lowercase() in setOf("jpg", "jpeg", "png") &&
                    it.name.contains("cover", true)
            } ?: dir.walkTopDown().firstOrNull {
                it.extension.lowercase() in setOf("jpg", "jpeg", "png")
            }
        }
        return ParsedBook(bookTitle, chapters, cover)
    }

    /** Map chapter file -> title from toc.ncx or nav document when available. */
    private fun ncxTitles(
        opfDir: File,
        idToHref: Map<String, String>,
        opf: org.jsoup.nodes.Document
    ): Map<String, String> {
        val map = HashMap<String, String>()
        try {
            val ncxHref = idToHref.entries
                .firstOrNull { it.value.lowercase().endsWith(".ncx") }?.value
            if (ncxHref != null) {
                val ncxFile = File(opfDir, urlDecode(ncxHref))
                if (ncxFile.exists()) {
                    val ncx = Jsoup.parse(ncxFile.readText(), "", Parser.xmlParser())
                    for (np in ncx.select("navPoint")) {
                        val label = np.select("navLabel > text").firstOrNull()?.text() ?: continue
                        val src = np.select("content").attr("src").substringBefore('#')
                        if (src.isNotEmpty()) {
                            val f = File(ncxFile.parentFile, urlDecode(src))
                            if (f.exists() && !map.containsKey(f.canonicalPath)) {
                                map[f.canonicalPath] = label
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return map
    }

    private fun urlDecode(s: String): String = try {
        java.net.URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }

    // ------------------------------------------------ TXT

    private fun parseTxt(context: Context, book: File): ParsedBook {
        val dir = extractDir(context, book)
        val marker = File(dir, ".done")
        val chapters = ArrayList<Chapter>()
        if (marker.exists()) {
            val existing = (dir.listFiles() ?: emptyArray())
                .filter { it.extension == "html" }
                .sortedBy { it.name }
            existing.forEachIndexed { i, f -> chapters.add(Chapter("Part ${i + 1}", f)) }
            if (chapters.isNotEmpty()) {
                return ParsedBook(book.nameWithoutExtension, chapters, null)
            }
        }
        dir.deleteRecursively()
        dir.mkdirs()
        val text = readTextSmart(book)
        val paragraphs = text.replace("\r\n", "\n").replace('\r', '\n')
            .split(Regex("\n\\s*\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val parts = ArrayList<StringBuilder>()
        var cur = StringBuilder()
        for (p in paragraphs) {
            if (cur.length > 45000) {
                parts.add(cur)
                cur = StringBuilder()
            }
            cur.append("<p dir=\"auto\">").append(escapeHtml(p).replace("\n", "<br/>")).append("</p>\n")
        }
        if (cur.isNotEmpty()) parts.add(cur)
        if (parts.isEmpty()) parts.add(StringBuilder("<p>(empty file)</p>"))
        parts.forEachIndexed { i, sb ->
            val f = File(dir, "part%04d.html".format(i))
            f.writeText(
                "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/></head><body>" +
                    sb.toString() + "</body></html>"
            )
            chapters.add(Chapter("Part ${i + 1}", f))
        }
        marker.writeText("ok")
        return ParsedBook(book.nameWithoutExtension, chapters, null)
    }

    private fun readTextSmart(f: File): String {
        val bytes = f.readBytes()
        return try {
            when {
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                    String(bytes, Charsets.UTF_16LE)
                bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                    String(bytes, Charsets.UTF_16BE)
                else -> {
                    val utf8 = String(bytes, Charsets.UTF_8)
                    // older Urdu text files are often saved in the Windows Arabic
                    // code page; broken UTF-8 gives that away
                    val bad = utf8.count { it == '�' }
                    if (bad > 8 && bad * 100 > utf8.length) {
                        try {
                            String(bytes, charset("windows-1256"))
                        } catch (e: Exception) {
                            utf8
                        }
                    } else utf8
                }
            }
        } catch (e: Exception) {
            String(bytes)
        }
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Plain text of one chapter, used for TTS fallback, search and AI summary. */
    fun chapterText(chapter: Chapter): String = try {
        Jsoup.parse(chapter.file.readText()).body().text()
    } catch (e: Exception) {
        ""
    }
}
