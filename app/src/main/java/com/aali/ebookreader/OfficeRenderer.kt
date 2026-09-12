package com.aali.ebookreader

import android.content.Context
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import kotlin.math.roundToInt

/**
 * Draws PowerPoint and Word files the way they actually look, instead of
 * flattening them into plain text.
 *
 * A pptx or docx is a zip of XML that describes every shape, its position in
 * English Metric Units, its fill, its font and its picture. This reads that
 * description and rebuilds it as absolutely positioned HTML at a fixed slide
 * size, so the reader shows the original layout and the page can be pinched
 * and zoomed exactly like a PDF.
 */
object OfficeRenderer {

    private const val EMU_PER_PX = 9525.0          // 914400 EMU per inch at 96 dpi
    private const val SLIDE_W_DEFAULT = 12192000.0 // 16:9
    private const val SLIDE_H_DEFAULT = 6858000.0

    // ------------------------------------------------ shared helpers

    private class Pack(val zip: ZipFile) {
        val cache = HashMap<String, Element?>()

        fun xml(path: String): Element? = cache.getOrPut(path) {
            try {
                val e = zip.getEntry(path) ?: return@getOrPut null
                org.jsoup.Jsoup.parse(
                    zip.getInputStream(e).bufferedReader().readText(), "", Parser.xmlParser()
                )
            } catch (ex: Exception) {
                null
            }
        }

        /** Relationship targets of a part, by relationship id. */
        fun rels(partPath: String): Map<String, String> {
            val dir = partPath.substringBeforeLast('/', "")
            val name = partPath.substringAfterLast('/')
            val relPath = if (dir.isEmpty()) "_rels/$name.rels" else "$dir/_rels/$name.rels"
            val doc = xml(relPath) ?: return emptyMap()
            val out = HashMap<String, String>()
            for (r in doc.select("Relationship")) {
                val id = r.attr("Id")
                var target = r.attr("Target")
                if (target.startsWith("../")) {
                    val parent = dir.substringBeforeLast('/', "")
                    target = if (parent.isEmpty()) target.removePrefix("../")
                    else parent + "/" + target.removePrefix("../")
                } else if (!target.startsWith("/") && dir.isNotEmpty()) {
                    target = "$dir/$target"
                }
                out[id] = target.removePrefix("/")
            }
            return out
        }

        /** Copies an image out of the archive and returns its file name. */
        fun extractMedia(path: String, into: File): String? {
            return try {
                val entry = zip.getEntry(path) ?: return null
                val safe = "media_" + path.substringAfterLast('/')
                val out = File(into, safe)
                if (!out.exists() || out.length() == 0L) {
                    zip.getInputStream(entry).use { ins ->
                        FileOutputStream(out).use { os -> ins.copyTo(os) }
                    }
                }
                safe
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun px(emu: String?): Double =
        (emu?.toDoubleOrNull() ?: 0.0) / EMU_PER_PX

    // ------------------------------------------------ colours

    /** Theme colour names mapped to hex, read from the theme part. */
    private fun themeColours(pack: Pack, themePath: String): Map<String, String> {
        val out = HashMap<String, String>()
        val doc = pack.xml(themePath) ?: return out
        val scheme = doc.selectFirst("a|clrScheme") ?: return out
        for (child in scheme.children()) {
            val name = child.tagName().substringAfter(':')
            val srgb = child.selectFirst("a|srgbClr")?.attr("val")
            val sys = child.selectFirst("a|sysClr")?.attr("lastClr")
            val hex = srgb ?: sys ?: continue
            out[name] = "#$hex"
        }
        // PowerPoint swaps these two pairs when referring to them from slides
        out["tx1"] = out["dk1"] ?: "#000000"
        out["bg1"] = out["lt1"] ?: "#FFFFFF"
        out["tx2"] = out["dk2"] ?: "#000000"
        out["bg2"] = out["lt2"] ?: "#FFFFFF"
        return out
    }

    /** The heading and body typefaces named by the theme. */
    private fun themeFonts(pack: Pack, themePath: String): Pair<String?, String?> {
        val doc = pack.xml(themePath) ?: return null to null
        val major = doc.selectFirst("a|fontScheme > a|majorFont > a|latin")?.attr("typeface")
        val minor = doc.selectFirst("a|fontScheme > a|minorFont > a|latin")?.attr("typeface")
        return major?.takeIf { it.isNotBlank() } to minor?.takeIf { it.isNotBlank() }
    }

    /** Resolves a fill or text colour element to a CSS colour. */
    private fun colourOf(holder: Element?, theme: Map<String, String>): String? {
        holder ?: return null
        holder.selectFirst("a|srgbClr")?.let { c ->
            var hex = "#" + c.attr("val")
            c.selectFirst("a|alpha")?.let { a ->
                val pct = a.attr("val").toIntOrNull()
                if (pct != null && pct < 100000) {
                    return rgba(hex, pct / 100000.0)
                }
            }
            return hex
        }
        holder.selectFirst("a|schemeClr")?.let { c ->
            val base = theme[c.attr("val")] ?: return@let
            val lumMod = c.selectFirst("a|lumMod")?.attr("val")?.toIntOrNull()
            val lumOff = c.selectFirst("a|lumOff")?.attr("val")?.toIntOrNull()
            return shade(base, lumMod, lumOff)
        }
        return null
    }

    private fun rgba(hex: String, alpha: Double): String {
        val h = hex.removePrefix("#")
        if (h.length < 6) return hex
        val r = h.substring(0, 2).toIntOrNull(16) ?: 0
        val g = h.substring(2, 4).toIntOrNull(16) ?: 0
        val b = h.substring(4, 6).toIntOrNull(16) ?: 0
        return "rgba($r,$g,$b,${"%.2f".format(alpha)})"
    }

    /** Applies PowerPoint's lighten and darken modifiers to a colour. */
    private fun shade(hex: String, lumMod: Int?, lumOff: Int?): String {
        if (lumMod == null && lumOff == null) return hex
        val h = hex.removePrefix("#")
        if (h.length < 6) return hex
        var r = (h.substring(0, 2).toIntOrNull(16) ?: 0) / 255.0
        var g = (h.substring(2, 4).toIntOrNull(16) ?: 0) / 255.0
        var b = (h.substring(4, 6).toIntOrNull(16) ?: 0) / 255.0
        val mod = (lumMod ?: 100000) / 100000.0
        val off = (lumOff ?: 0) / 100000.0
        r = (r * mod + off).coerceIn(0.0, 1.0)
        g = (g * mod + off).coerceIn(0.0, 1.0)
        b = (b * mod + off).coerceIn(0.0, 1.0)
        return "#%02X%02X%02X".format(
            (r * 255).roundToInt(), (g * 255).roundToInt(), (b * 255).roundToInt()
        )
    }

    // ==================================================================
    //                          POWERPOINT
    // ==================================================================

    fun renderPptx(context: Context, book: File, outDir: File): ParsedBook {
        val chapters = ArrayList<Chapter>()
        var cover: File? = null

        ZipFile(book).use { zf ->
            val pack = Pack(zf)

            val pres = pack.xml("ppt/presentation.xml")
            val sz = pres?.selectFirst("p|sldSz")
            val slideW = sz?.attr("cx")?.toDoubleOrNull() ?: SLIDE_W_DEFAULT
            val slideH = sz?.attr("cy")?.toDoubleOrNull() ?: SLIDE_H_DEFAULT
            val pageW = (slideW / EMU_PER_PX).roundToInt()
            val pageH = (slideH / EMU_PER_PX).roundToInt()

            // slides in the order the presentation lists them
            val presRels = pack.rels("ppt/presentation.xml")
            val ordered = ArrayList<String>()
            pres?.select("p|sldIdLst > p|sldId")?.forEach { sld ->
                val rid = sld.attr("r:id").ifEmpty { sld.attr("id") }
                presRels[rid]?.let { ordered.add(it) }
            }
            if (ordered.isEmpty()) {
                val names = ArrayList<String>()
                val en = zf.entries()
                while (en.hasMoreElements()) {
                    val n = en.nextElement().name
                    if (n.matches(Regex("ppt/slides/slide\\d+\\.xml"))) names.add(n)
                }
                names.sortBy { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toInt() ?: 0 }
                ordered.addAll(names)
            }

            for ((index, slidePath) in ordered.withIndex()) {
                val slide = pack.xml(slidePath) ?: continue
                val slideRels = pack.rels(slidePath)

                val layoutPath = slideRels.values.firstOrNull { it.contains("slideLayout") }
                val layout = layoutPath?.let { pack.xml(it) }
                val layoutRels = layoutPath?.let { pack.rels(it) } ?: emptyMap()
                val masterPath = layoutRels.values.firstOrNull { it.contains("slideMaster") }
                val master = masterPath?.let { pack.xml(it) }
                val masterRels = masterPath?.let { pack.rels(it) } ?: emptyMap()
                val themePath = masterRels.values.firstOrNull { it.contains("theme") }
                    ?: "ppt/theme/theme1.xml"
                // the font names travel with the colours, under their own keys
                val fonts = themeFonts(pack, themePath)
                val theme = themeColours(pack, themePath) + buildMap {
                    fonts.first?.let { put("+mj-lt", it) }
                    fonts.second?.let { put("+mn-lt", it) }
                }
                val baseFont = fonts.second ?: fonts.first ?: "Arial"

                val body = StringBuilder()

                // background: slide, else layout, else master
                val bg = listOfNotNull(
                    slide.selectFirst("p|cSld > p|bg"),
                    layout?.selectFirst("p|cSld > p|bg"),
                    master?.selectFirst("p|cSld > p|bg")
                ).firstOrNull()
                val bgColour = colourOf(bg?.selectFirst("p|bgPr > a|solidFill"), theme)
                    ?: colourOf(bg?.selectFirst("a|solidFill"), theme)
                    ?: "#FFFFFF"

                // decorations from the master and layout sit underneath
                master?.selectFirst("p|cSld > p|spTree")?.let {
                    renderTree(it, pack, masterPath!!, theme, body, outDir, onlyDecoration = true)
                }
                layout?.selectFirst("p|cSld > p|spTree")?.let {
                    renderTree(it, pack, layoutPath!!, theme, body, outDir, onlyDecoration = true)
                }
                // then the slide's own shapes
                slide.selectFirst("p|cSld > p|spTree")?.let {
                    renderTree(
                        it, pack, slidePath, theme, body, outDir,
                        onlyDecoration = false, layout = layout, master = master
                    )
                }

                val titleText = slide.select("p|sp").firstOrNull { sp ->
                    sp.selectFirst("p|ph")?.attr("type")?.let { t ->
                        t == "title" || t == "ctrTitle"
                    } == true
                }?.select("a|t")?.joinToString(" ") { it.text() }?.trim()
                val title = if (!titleText.isNullOrBlank()) titleText.take(60)
                else "Slide ${index + 1}"

                // speaker notes, shown under the slide so they stay readable
                val notesPath = slideRels.values.firstOrNull { it.contains("notesSlide") }
                val notes = notesPath?.let { pack.xml(it) }
                    ?.select("p|sp")?.toList()
                    ?.filter { it.selectFirst("p|ph")?.attr("type") != "sldNum" }
                    ?.flatMap { sp -> sp.select("a|p").map { p ->
                        p.select("a|t").joinToString("") { it.text() }.trim()
                    } }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList()

                val html = slideDocument(
                    pageW, pageH, bgColour, body.toString(), index + 1, ordered.size,
                    notes, baseFont
                )
                val f = File(outDir, "slide%04d.html".format(index + 1))
                f.writeText(html)
                chapters.add(Chapter("${index + 1}. $title", f))

                if (cover == null) {
                    val firstPic = slide.selectFirst("p|pic a|blip")?.attr("r:embed")
                        ?.let { slideRels[it] }
                    if (firstPic != null) {
                        pack.extractMedia(firstPic, outDir)?.let { cover = File(outDir, it) }
                    }
                }
            }
        }

        if (chapters.isEmpty()) {
            val f = File(outDir, "slide0001.html")
            f.writeText(
                "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/></head><body>" +
                    "<p>This presentation could not be read.</p></body></html>"
            )
            chapters.add(Chapter("Empty", f))
        }
        return ParsedBook(book.nameWithoutExtension, chapters, cover)
    }

    /** The page shell for one slide. */
    private fun slideDocument(
        w: Int, h: Int, bg: String, inner: String,
        number: Int, total: Int, notes: List<String>, baseFont: String = "Arial"
    ): String {
        val notesHtml = if (notes.isEmpty()) "" else buildString {
            append("<div class=\"notes\"><h4>SPEAKER NOTES</h4>")
            notes.forEach { append("<p>").append(esc(it)).append("</p>") }
            append("</div>")
        }
        return """<!DOCTYPE html><html><head><meta charset="utf-8"/>
<meta name="viewport" content="width=$w"/>
<style>
  html{margin:0;padding:0;background:#3A2E4A;}
  body{margin:0;padding:0;background:#3A2E4A;min-height:100vh;
       display:flex;flex-direction:column;justify-content:center;}
  #slide{position:relative;width:${w}px;height:${h}px;background:$bg;
         overflow:hidden;margin:0 auto;flex:none;
         font-family:'${esc(baseFont)}',Arial,Helvetica,sans-serif;
         box-shadow:0 2px 18px rgba(0,0,0,.45);}
  #slide .sh{position:absolute;box-sizing:border-box;}
  #slide .tx{display:flex;flex-direction:column;overflow:visible;}
  #slide p{margin:0;padding:0;}
  #slide img{display:block;width:100%;height:100%;}
  .below{width:${w}px;margin:0 auto;color:#EDE4FF;font-family:sans-serif;}
  .pagenum{padding:10px 4px;font-size:20px;opacity:.75;letter-spacing:.1em;}
  .notes{background:#F7F2FF;color:#241A33;border-radius:10px;padding:18px 22px;
         margin:0 0 26px 0;font-size:22px;line-height:1.5;}
  .notes h4{margin:0 0 10px 0;font-size:18px;letter-spacing:.08em;color:#5B21B6;}
  .notes p{margin:0 0 10px 0;}
</style></head>
<body class="fixed-layout">
<div id="slide">$inner</div>
<div class="below"><div class="pagenum">SLIDE $number OF $total</div>$notesHtml</div>
</body></html>"""
    }

    /** Walks a shape tree and writes each shape as positioned HTML. */
    private fun renderTree(
        tree: Element,
        pack: Pack,
        partPath: String,
        theme: Map<String, String>,
        out: StringBuilder,
        outDir: File,
        onlyDecoration: Boolean,
        layout: Element? = null,
        master: Element? = null,
        dx: Double = 0.0,
        dy: Double = 0.0
    ) {
        val rels = pack.rels(partPath)
        for (node in tree.children()) {
            when (node.tagName().substringAfter(':')) {
                "sp" -> {
                    val ph = node.selectFirst("p|ph")
                    // master and layout only contribute their non placeholder art
                    if (onlyDecoration && ph != null) continue
                    renderShape(node, ph, theme, out, layout, master, dx, dy)
                }
                "pic" -> renderPicture(node, rels, pack, out, outDir, dx, dy)
                "graphicFrame" -> renderTable(node, theme, out, dx, dy)
                "cxnSp" -> renderShape(node, null, theme, out, null, null, dx, dy)
                "grpSp" -> {
                    val off = node.selectFirst("p|grpSpPr a|off")
                    val chOff = node.selectFirst("p|grpSpPr a|chOff")
                    val gx = px(off?.attr("x")) - px(chOff?.attr("x"))
                    val gy = px(off?.attr("y")) - px(chOff?.attr("y"))
                    renderTree(
                        node, pack, partPath, theme, out, outDir,
                        onlyDecoration, layout, master, dx + gx, dy + gy
                    )
                }
            }
        }
    }

    /** The shape in a layout or master that a slide placeholder inherits from. */
    private fun matchPlaceholder(source: Element?, ph: Element?): Element? {
        source ?: return null
        val type = ph?.attr("type") ?: ""
        val idx = ph?.attr("idx") ?: ""
        return source.select("p|sp").firstOrNull { cand ->
            val cph = cand.selectFirst("p|ph") ?: return@firstOrNull false
            val sameType = cph.attr("type") == type && type.isNotEmpty()
            val sameIdx = cph.attr("idx") == idx && idx.isNotEmpty()
            sameType || sameIdx
        }
    }

    /** Position of a shape, falling back to its placeholder in the layout. */
    private fun frameOf(
        sp: Element, ph: Element?, layout: Element?, master: Element?
    ): Element? {
        sp.selectFirst("p|spPr a|xfrm")?.let { return it }
        for (source in listOfNotNull(layout, master)) {
            matchPlaceholder(source, ph)?.selectFirst("p|spPr a|xfrm")?.let { return it }
        }
        return null
    }

    /**
     * The list style parts a paragraph inherits from, weakest first: the
     * master's text styles, then the master and layout placeholders, then the
     * shape's own list style. PowerPoint resolves sizes, colours and bullets
     * through exactly this chain, so following it keeps slides looking right.
     */
    private fun styleSources(
        sp: Element, ph: Element?, layout: Element?, master: Element?
    ): List<Element> {
        val type = ph?.attr("type") ?: ""
        val kind = when (type) {
            "title", "ctrTitle" -> "p|titleStyle"
            "body", "subTitle", "" -> if (ph == null) "p|otherStyle" else "p|bodyStyle"
            else -> "p|otherStyle"
        }
        val out = ArrayList<Element>()
        master?.selectFirst("p|txStyles")?.selectFirst(kind)?.let { out.add(it) }
        matchPlaceholder(master, ph)?.selectFirst("p|txBody a|lstStyle")?.let { out.add(it) }
        matchPlaceholder(layout, ph)?.selectFirst("p|txBody a|lstStyle")?.let { out.add(it) }
        sp.selectFirst("p|txBody a|lstStyle")?.let { out.add(it) }
        return out
    }

    /** The level paragraph properties from every source, weakest first. */
    private fun levelChain(sources: List<Element>, level: Int): List<Element> {
        val tag = "a|lvl${(level + 1).coerceIn(1, 9)}pPr"
        return sources.mapNotNull { it.selectFirst(tag) }
    }

    private fun renderShape(
        sp: Element,
        ph: Element?,
        theme: Map<String, String>,
        out: StringBuilder,
        layout: Element?,
        master: Element?,
        dx: Double,
        dy: Double
    ) {
        val xfrm = frameOf(sp, ph, layout, master)
        val off = xfrm?.selectFirst("a|off")
        val ext = xfrm?.selectFirst("a|ext")
        val x = px(off?.attr("x")) + dx
        val y = px(off?.attr("y")) + dy
        val w = px(ext?.attr("cx"))
        val h = px(ext?.attr("cy"))
        val rot = xfrm?.attr("rot")?.toDoubleOrNull()?.div(60000.0) ?: 0.0

        val spPr = sp.selectFirst("p|spPr")
        val noFill = spPr?.selectFirst("a|noFill") != null
        // a shape with no fill of its own takes the one its theme style names
        val fill = colourOf(spPr?.selectFirst("a|solidFill"), theme)
            ?: colourOf(sp.selectFirst("p|style > a|fillRef"), theme)
        val line = spPr?.selectFirst("a|ln")
        val lineColour = colourOf(line?.selectFirst("a|solidFill"), theme)
            ?: colourOf(sp.selectFirst("p|style > a|lnRef"), theme)
        val lineW = line?.attr("w")?.toDoubleOrNull()?.div(EMU_PER_PX) ?: 0.0
        val geom = spPr?.selectFirst("a|prstGeom")?.attr("prst") ?: ""
        val radius = when {
            geom.contains("ellipse") || geom == "circle" -> "50%"
            geom.contains("round") -> "${(minOf(w, h) * 0.16).roundToInt()}px"
            else -> "0"
        }

        val hasText = sp.select("a|t").any { it.text().isNotBlank() }
        if (w <= 0 || h <= 0) {
            if (!hasText) return
        }

        val style = StringBuilder()
        style.append("left:${x.roundToInt()}px;top:${y.roundToInt()}px;")
        if (w > 0) style.append("width:${w.roundToInt()}px;")
        if (h > 0) style.append("height:${h.roundToInt()}px;")
        if (fill != null && !noFill) style.append("background:$fill;")
        val lineOff = line?.selectFirst("a|noFill") != null
        if (lineColour != null && !lineOff) {
            style.append("border:${maxOf(1.0, lineW).roundToInt()}px solid $lineColour;")
        }
        if (radius != "0") style.append("border-radius:$radius;")
        if (rot != 0.0) style.append("transform:rotate(${"%.2f".format(rot)}deg);")

        // vertical placement of the text inside its box. A drawn shape centres
        // its text unless it says otherwise, the way PowerPoint does.
        val isDrawnShape = ph == null &&
            sp.selectFirst("p|nvSpPr p|cNvSpPr")?.attr("txBox") != "1" &&
            spPr?.selectFirst("a|prstGeom") != null
        val anchor = sp.selectFirst("p|txBody a|bodyPr")?.attr("anchor")
            ?.takeIf { it.isNotEmpty() } ?: if (isDrawnShape) "ctr" else "t"
        style.append(
            when (anchor) {
                "ctr" -> "justify-content:center;"
                "b" -> "justify-content:flex-end;"
                else -> "justify-content:flex-start;"
            }
        )
        if (isDrawnShape) style.append("text-align:center;")
        val ins = sp.selectFirst("p|txBody a|bodyPr")
        val padL = px(ins?.attr("lIns")).takeIf { ins?.hasAttr("lIns") == true } ?: 9.6
        val padR = px(ins?.attr("rIns")).takeIf { ins?.hasAttr("rIns") == true } ?: 9.6
        val padT = px(ins?.attr("tIns")).takeIf { ins?.hasAttr("tIns") == true } ?: 4.8
        val padB = px(ins?.attr("bIns")).takeIf { ins?.hasAttr("bIns") == true } ?: 4.8
        style.append(
            "padding:${padT.roundToInt()}px ${padR.roundToInt()}px " +
                "${padB.roundToInt()}px ${padL.roundToInt()}px;"
        )

        // text inside a styled shape takes the colour that style names, so
        // white lettering on a coloured shape stays white
        val fontColour = colourOf(sp.selectFirst("p|style > a|fontRef"), theme)
        val text = textBody(
            sp.selectFirst("p|txBody"), theme, ph,
            styleSources(sp, ph, layout, master), fontColour
        )
        if (text.isBlank() && (fill == null || noFill) && lineColour == null) return

        out.append("<div class=\"sh tx\" style=\"").append(style).append("\">")
            .append(text).append("</div>")
    }

    /** Paragraphs and runs inside a shape, with everything they inherit. */
    private fun textBody(
        txBody: Element?,
        theme: Map<String, String>,
        ph: Element?,
        sources: List<Element> = emptyList(),
        defColour: String? = null
    ): String {
        txBody ?: return ""
        val isTitle = ph?.attr("type")?.let { it == "title" || it == "ctrTitle" } == true
        val sb = StringBuilder()

        for (p in txBody.select("a|p")) {
            val pPr = p.selectFirst("a|pPr")
            val level = pPr?.attr("lvl")?.toIntOrNull() ?: 0
            // weakest first, so later entries win
            val chain = levelChain(sources, level) + listOfNotNull(pPr)

            val align = when (chain.lastOrNull { it.hasAttr("algn") }?.attr("algn")) {
                "ctr" -> "center"
                "r" -> "right"
                "just" -> "justify"
                else -> null
            }

            // bullets: the last source that says anything about them wins
            var bullet: String? = null
            for (c in chain) {
                if (c.selectFirst("a|buNone") != null) bullet = null
                c.selectFirst("a|buChar")?.let { bullet = it.attr("char").ifEmpty { "•" } }
                if (c.selectFirst("a|buAutoNum") != null) bullet = "•"
            }

            val marL = chain.lastOrNull { it.hasAttr("marL") }?.attr("marL")?.toDoubleOrNull()
            val hang = chain.lastOrNull { it.hasAttr("indent") }?.attr("indent")?.toDoubleOrNull()
            val marginPx = marL?.div(EMU_PER_PX) ?: (level * 36.0)
            val hangPx = hang?.div(EMU_PER_PX) ?: -18.0
            val defRuns = chain.mapNotNull { it.selectFirst("a|defRPr") }

            val runs = StringBuilder()
            for (child in p.children()) {
                when (child.tagName().substringAfter(':')) {
                    "r", "fld" -> {
                        val t = child.selectFirst("a|t")?.text() ?: ""
                        if (t.isEmpty()) continue
                        runs.append(
                            run(t, child.selectFirst("a|rPr"), defRuns, theme,
                                isTitle, defColour)
                        )
                    }
                    "br" -> runs.append("<br/>")
                }
            }
            if (runs.isBlank()) {
                sb.append("<p style=\"height:0.6em\"></p>")
                continue
            }

            val style = StringBuilder("margin:0.15em 0;")
            if (align != null) style.append("text-align:$align;")
            if (marginPx > 0) style.append("margin-left:${marginPx.roundToInt()}px;")
            if (bullet != null) {
                val pad = maxOf(12.0, -hangPx)
                style.append("padding-left:${pad.roundToInt()}px;")
                style.append("text-indent:-${pad.roundToInt()}px;")
                // the marker matches the text it belongs to, never the page default
                val first = p.selectFirst("a|r")
                val size = runSize(first?.selectFirst("a|rPr"), defRuns, isTitle)
                val colour = colourOf(first?.selectFirst("a|rPr a|solidFill"), theme)
                    ?: defColour
                    ?: defRuns.reversed().firstNotNullOfOrNull {
                        colourOf(it.selectFirst("a|solidFill"), theme)
                    } ?: "#111111"
                sb.append("<p style=\"$style\">")
                    .append("<span style=\"font-size:${"%.1f".format(size * 1.333)}px;")
                    .append("color:$colour;\">").append(esc(bullet!!)).append("</span>")
                    .append("&nbsp;")
                    .append(runs).append("</p>")
            } else {
                sb.append("<p style=\"$style\">").append(runs).append("</p>")
            }
        }
        return sb.toString()
    }

    /** Font size of a run in points, following what it inherits. */
    private fun runSize(rPr: Element?, defRuns: List<Element>, isTitle: Boolean): Double {
        val own = rPr?.attr("sz")?.takeIf { it.isNotEmpty() }
            ?: defRuns.lastOrNull { it.hasAttr("sz") }?.attr("sz")
        return own?.toDoubleOrNull()?.div(100.0) ?: if (isTitle) 44.0 else 18.0
    }

    private fun run(
        text: String,
        rPr: Element?,
        defRuns: List<Element>,
        theme: Map<String, String>,
        isTitle: Boolean,
        defColour: String? = null
    ): String {
        // the run's own value first, then whatever the slide inherits
        fun attrOf(name: String): String? =
            rPr?.attr(name)?.takeIf { it.isNotEmpty() }
                ?: defRuns.lastOrNull { it.hasAttr(name) }?.attr(name)?.takeIf { it.isNotEmpty() }

        val size = runSize(rPr, defRuns, isTitle)
        val bold = attrOf("b") == "1"
        val italic = attrOf("i") == "1"
        val u = attrOf("u")
        val underline = u != null && u != "none"
        val colour = colourOf(rPr?.selectFirst("a|solidFill"), theme)
            ?: defColour
            ?: defRuns.reversed().firstNotNullOfOrNull {
                colourOf(it.selectFirst("a|solidFill"), theme)
            }
            ?: "#111111"
        val rawFont = (rPr?.selectFirst("a|latin")?.attr("typeface")
            ?: defRuns.lastOrNull { it.selectFirst("a|latin") != null }
                ?.selectFirst("a|latin")?.attr("typeface"))
            ?.takeIf { it.isNotBlank() }
        // "+mj-lt" and "+mn-lt" mean the theme's heading and body typefaces
        val font = if (rawFont != null && rawFont.startsWith("+")) theme[rawFont] else rawFont

        val s = StringBuilder()
        s.append("font-size:${"%.1f".format(size * 1.333)}px;")   // points to pixels
        s.append("color:$colour;")
        s.append("line-height:1.22;")
        if (bold) s.append("font-weight:700;")
        if (italic) s.append("font-style:italic;")
        if (underline) s.append("text-decoration:underline;")
        if (font != null) s.append("font-family:'${esc(font)}',sans-serif;")
        return "<span style=\"$s\">${esc(text)}</span>"
    }

    private fun renderPicture(
        pic: Element,
        rels: Map<String, String>,
        pack: Pack,
        out: StringBuilder,
        outDir: File,
        dx: Double,
        dy: Double
    ) {
        val rid = pic.selectFirst("a|blip")?.attr("r:embed") ?: return
        val target = rels[rid] ?: return
        val name = pack.extractMedia(target, outDir) ?: return
        val xfrm = pic.selectFirst("p|spPr a|xfrm")
        val off = xfrm?.selectFirst("a|off")
        val ext = xfrm?.selectFirst("a|ext")
        val x = px(off?.attr("x")) + dx
        val y = px(off?.attr("y")) + dy
        val w = px(ext?.attr("cx"))
        val h = px(ext?.attr("cy"))
        if (w <= 0 || h <= 0) return
        val rot = xfrm?.attr("rot")?.toDoubleOrNull()?.div(60000.0) ?: 0.0
        val style = StringBuilder(
            "left:${x.roundToInt()}px;top:${y.roundToInt()}px;" +
                "width:${w.roundToInt()}px;height:${h.roundToInt()}px;"
        )
        if (rot != 0.0) style.append("transform:rotate(${"%.2f".format(rot)}deg);")
        out.append("<div class=\"sh\" style=\"$style\"><img src=\"$name\" alt=\"\"/></div>")
    }

    private fun renderTable(
        frame: Element, theme: Map<String, String>, out: StringBuilder, dx: Double, dy: Double
    ) {
        val tbl = frame.selectFirst("a|tbl") ?: return
        val xfrm = frame.selectFirst("p|xfrm")
        val off = xfrm?.selectFirst("a|off")
        val ext = xfrm?.selectFirst("a|ext")
        val x = px(off?.attr("x")) + dx
        val y = px(off?.attr("y")) + dy
        val w = px(ext?.attr("cx"))

        val sb = StringBuilder()
        sb.append("<table style=\"border-collapse:collapse;width:100%;\">")
        for (tr in tbl.select("a|tr")) {
            sb.append("<tr>")
            for (tc in tr.select("a|tc")) {
                val fill = colourOf(tc.selectFirst("a|tcPr > a|solidFill"), theme)
                val cellStyle = StringBuilder(
                    "border:1px solid #999;padding:6px 10px;vertical-align:middle;"
                )
                if (fill != null) cellStyle.append("background:$fill;")
                sb.append("<td style=\"$cellStyle\">")
                    .append(textBody(tc.selectFirst("a|txBody"), theme, null))
                    .append("</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</table>")

        out.append(
            "<div class=\"sh\" style=\"left:${x.roundToInt()}px;top:${y.roundToInt()}px;" +
                "width:${w.roundToInt()}px;\">$sb</div>"
        )
    }

    // ==================================================================
    //                             WORD
    // ==================================================================

    fun renderDocx(context: Context, book: File, outDir: File): ParsedBook {
        val chapters = ArrayList<Chapter>()
        // the real paper size and margins, in pixels at 96 dpi (20 twips = 1px)
        var pageW = 816
        var padT = 96; var padR = 96; var padB = 96; var padL = 96
        var baseSize = 15.0     // pixels, replaced by the document's own default
        var baseFont = "Calibri"
        val body = StringBuilder()

        ZipFile(book).use { zf ->
            val pack = Pack(zf)
            val doc = pack.xml("word/document.xml")
                ?: return ParsedBook(book.nameWithoutExtension, chapters, null)
            val rels = pack.rels("word/document.xml")
            val styles = docxStyleSizes(pack)
            val root = doc.selectFirst("w|body") ?: doc

            fun twips(v: String?): Int? = v?.toDoubleOrNull()?.div(15.0)?.roundToInt()
            root.selectFirst("w|sectPr")?.let { sect ->
                twips(sect.selectFirst("w|pgSz")?.attr("w:w"))?.let { if (it > 200) pageW = it }
                sect.selectFirst("w|pgMar")?.let { m ->
                    twips(m.attr("w:top"))?.let { padT = it.coerceIn(0, 400) }
                    twips(m.attr("w:right"))?.let { padR = it.coerceIn(0, 400) }
                    twips(m.attr("w:bottom"))?.let { padB = it.coerceIn(0, 400) }
                    twips(m.attr("w:left"))?.let { padL = it.coerceIn(0, 400) }
                }
            }
            pack.xml("word/styles.xml")?.selectFirst("w|docDefaults w|rPrDefault w|rPr")
                ?.let { d ->
                    d.selectFirst("w|sz")?.attr("w:val")?.toDoubleOrNull()
                        ?.let { baseSize = it / 2.0 * 1.333 }
                    d.selectFirst("w|rFonts")?.attr("w:ascii")
                        ?.takeIf { it.isNotBlank() }?.let { baseFont = it }
                }

            for (node in root.children()) {
                when (node.tagName().substringAfter(':')) {
                    "p" -> body.append(docxParagraph(node, rels, pack, outDir, styles))
                    "tbl" -> {
                        // real column widths, so the table keeps its shape
                        val cols = node.select("w|tblGrid > w|gridCol")
                            .mapNotNull { twips(it.attr("w:w")) }
                        val total = cols.sum()
                        val widthStyle =
                            if (total > 0) "width:${total}px;" else "width:100%;"
                        body.append("<table class=\"wtbl\" style=\"$widthStyle\">")
                        if (cols.isNotEmpty()) {
                            body.append("<colgroup>")
                            cols.forEach { body.append("<col style=\"width:${it}px\"/>") }
                            body.append("</colgroup>")
                        }
                        for (tr in node.select("w|tr")) {
                            body.append("<tr>")
                            for (tc in tr.select("w|tc")) {
                                body.append("<td>")
                                for (p in tc.select("w|p")) {
                                    body.append(docxParagraph(p, rels, pack, outDir, styles))
                                }
                                body.append("</td>")
                            }
                            body.append("</tr>")
                        }
                        body.append("</table>")
                    }
                }
            }
        }

        val html = """<!DOCTYPE html><html><head><meta charset="utf-8"/>
<meta name="viewport" content="width=$pageW"/>
<style>
  html,body{margin:0;padding:0;background:#3A2E4A;}
  #page{width:${pageW}px;min-height:1056px;margin:0 auto;background:#FFFFFF;
        padding:${padT}px ${padR}px ${padB}px ${padL}px;box-sizing:border-box;color:#111;
        font-family:'${esc(baseFont)}',Calibri,Arial,sans-serif;
        font-size:${"%.1f".format(baseSize)}px;line-height:1.45;
        box-shadow:0 2px 18px rgba(0,0,0,.45);}
  #page p{margin:0 0 10px 0;}
  #page img{max-width:100%;height:auto;}
  .wtbl{border-collapse:collapse;max-width:100%;margin:12px 0;table-layout:fixed;}
  .wtbl td{border:1px solid #bbb;padding:8px 10px;vertical-align:top;}
</style></head>
<body class="fixed-layout"><div id="page">$body</div></body></html>"""

        val f = File(outDir, "document.html")
        f.writeText(html)
        chapters.add(Chapter(book.nameWithoutExtension, f))
        return ParsedBook(book.nameWithoutExtension, chapters, null)
    }

    /** Heading sizes declared in the style sheet, in half points. */
    private fun docxStyleSizes(pack: Pack): Map<String, Pair<Double, Boolean>> {
        val out = HashMap<String, Pair<Double, Boolean>>()
        val doc = pack.xml("word/styles.xml") ?: return out
        for (st in doc.select("w|style")) {
            val id = st.attr("w:styleId")
            val sz = st.selectFirst("w|rPr > w|sz")?.attr("w:val")?.toDoubleOrNull()
            val bold = st.selectFirst("w|rPr > w|b") != null
            if (sz != null || bold) out[id] = (sz ?: 0.0) to bold
        }
        return out
    }

    private fun docxParagraph(
        p: Element,
        rels: Map<String, String>,
        pack: Pack,
        outDir: File,
        styles: Map<String, Pair<Double, Boolean>>
    ): String {
        val pPr = p.selectFirst("w|pPr")
        val styleId = pPr?.selectFirst("w|pStyle")?.attr("w:val") ?: ""
        val align = when (pPr?.selectFirst("w|jc")?.attr("w:val")) {
            "center" -> "center"
            "right" -> "right"
            "both" -> "justify"
            else -> null
        }
        val isList = pPr?.selectFirst("w|numPr") != null ||
            Regex("List(Bullet|Number)").containsMatchIn(styleId)
        val indent = pPr?.selectFirst("w|ind")?.attr("w:left")?.toDoubleOrNull()
            ?.div(20.0)?.times(1.333) ?: 0.0

        val headingLevel = Regex("Heading(\\d)").find(styleId)?.groupValues?.get(1)?.toIntOrNull()
        val styleSize = styles[styleId]?.first?.takeIf { it > 0 }?.div(2.0)
        val styleBold = styles[styleId]?.second ?: false

        val runs = StringBuilder()
        for (child in p.children()) {
            when (child.tagName().substringAfter(':')) {
                "r" -> runs.append(docxRun(child, rels, pack, outDir, styleSize, styleBold))
                "hyperlink" -> child.select("w|r").forEach {
                    runs.append(docxRun(it, rels, pack, outDir, styleSize, styleBold))
                }
            }
        }
        if (runs.isBlank()) return "<p style=\"height:0.6em\"></p>"

        val style = StringBuilder()
        if (align != null) style.append("text-align:$align;")
        if (indent > 0) style.append("margin-left:${indent.roundToInt()}px;")
        if (headingLevel != null) {
            style.append("margin:18px 0 8px 0;font-weight:700;")
            val size = styleSize ?: when (headingLevel) {
                1 -> 26.0; 2 -> 22.0; 3 -> 19.0; else -> 17.0
            }
            style.append("font-size:${"%.1f".format(size * 1.333)}px;")
        }
        val prefix = if (isList) "• " else ""
        if (isList) style.append("padding-left:1.2em;text-indent:-1.2em;")
        return "<p style=\"$style\">$prefix$runs</p>"
    }

    private fun docxRun(
        r: Element,
        rels: Map<String, String>,
        pack: Pack,
        outDir: File,
        styleSize: Double?,
        styleBold: Boolean
    ): String {
        // an inline picture
        r.selectFirst("a|blip")?.attr("r:embed")?.takeIf { it.isNotEmpty() }?.let { rid ->
            val target = rels[rid]
            if (target != null) {
                val name = pack.extractMedia(target, outDir)
                if (name != null) {
                    val ext = r.selectFirst("wp|extent")
                    val w = px(ext?.attr("cx"))
                    val widthStyle = if (w > 0) "width:${w.roundToInt()}px;" else ""
                    return "<img src=\"$name\" style=\"$widthStyle\" alt=\"\"/>"
                }
            }
        }

        val rPr = r.selectFirst("w|rPr")
        val sb = StringBuilder()
        for (child in r.children()) {
            when (child.tagName().substringAfter(':')) {
                "t" -> sb.append(esc(child.text()))
                "br" -> sb.append("<br/>")
                "tab" -> sb.append("&nbsp;&nbsp;&nbsp;&nbsp;")
            }
        }
        if (sb.isEmpty()) return ""

        val size = rPr?.selectFirst("w|sz")?.attr("w:val")?.toDoubleOrNull()?.div(2.0)
            ?: styleSize
        val bold = rPr?.selectFirst("w|b") != null || styleBold
        val italic = rPr?.selectFirst("w|i") != null
        val underline = rPr?.selectFirst("w|u")?.attr("w:val")
            ?.let { it.isNotEmpty() && it != "none" } == true
        val colour = rPr?.selectFirst("w|color")?.attr("w:val")
            ?.takeIf { it.isNotEmpty() && it != "auto" }
        val font = rPr?.selectFirst("w|rFonts")?.attr("w:ascii")?.takeIf { it.isNotBlank() }
        val highlight = rPr?.selectFirst("w|highlight")?.attr("w:val")
            ?.takeIf { it.isNotEmpty() && it != "none" }

        val style = StringBuilder()
        if (size != null) style.append("font-size:${"%.1f".format(size * 1.333)}px;")
        if (bold) style.append("font-weight:700;")
        if (italic) style.append("font-style:italic;")
        if (underline) style.append("text-decoration:underline;")
        if (colour != null) style.append("color:#$colour;")
        if (font != null) style.append("font-family:'${esc(font)}',Georgia,serif;")
        if (highlight != null) style.append("background:$highlight;")
        return if (style.isEmpty()) sb.toString() else "<span style=\"$style\">$sb</span>"
    }
}
