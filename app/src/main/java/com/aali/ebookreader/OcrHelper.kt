package com.aali.ebookreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch

/**
 * Reads text out of scanned (picture only) PDF pages on the phone itself,
 * with no internet connection.
 *
 *  - English: Google ML Kit, whose model ships inside the app.
 *  - Urdu: Tesseract with the Urdu LSTM model (tessdata_best), also bundled.
 *    Nastaliq is hard for any recogniser, so the result is passed through a
 *    dictionary check that repairs the commonest misreadings.
 */
object OcrHelper {

    const val ENGLISH = "eng"
    const val URDU = "urd"

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    class OcrResult(val page: PdfPageText, val text: String)

    fun recognisePage(
        context: Context,
        pdfPath: String,
        page1: Int,
        lang: String = ENGLISH
    ): OcrResult? = if (lang == URDU) recogniseUrdu(context, pdfPath, page1)
    else recogniseEnglish(pdfPath, page1)

    /** Renders one page to a white bitmap, [targetWidth] pixels wide at most. */
    private fun render(
        pdfPath: String,
        page1: Int,
        targetWidth: Float,
        maxW: Int,
        maxH: Int
    ): Triple<Bitmap, Float, Float>? {
        ParcelFileDescriptor.open(File(pdfPath), ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (page1 < 1 || page1 > renderer.pageCount) return null
                renderer.openPage(page1 - 1).use { page ->
                    val widthPt = page.width.toFloat()
                    val heightPt = page.height.toFloat()
                    val scale = (targetWidth / page.width).coerceIn(1.5f, 5f)
                    val w = (page.width * scale).toInt().coerceAtMost(maxW)
                    val h = (page.height * scale).toInt().coerceAtMost(maxH)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return Triple(bmp, widthPt, heightPt)
                }
            }
        }
    }

    // ------------------------------------------------ English (ML Kit)

    private fun recogniseEnglish(pdfPath: String, page1: Int): OcrResult? {
        // about 200 dpi, capped so very large pages stay safe
        val (bmp, widthPt, heightPt) = try {
            render(pdfPath, page1, 2200f, 3200, 4400)
        } catch (e: Throwable) {
            null
        } ?: return null

        val boxes = ArrayList<Pair<String, RectF>>()
        val sb = StringBuilder()
        val latch = CountDownLatch(1)
        var failed = false

        try {
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { visionText ->
                    val sx = widthPt / bmp.width
                    val sy = heightPt / bmp.height
                    for (block in visionText.textBlocks) {
                        for (line in block.lines) {
                            for (element in line.elements) {
                                val b = element.boundingBox ?: continue
                                boxes.add(
                                    element.text to
                                        RectF(b.left * sx, b.top * sy,
                                            b.right * sx, b.bottom * sy)
                                )
                            }
                            sb.append(line.text).append('\n')
                        }
                        sb.append('\n')
                    }
                    latch.countDown()
                }
                .addOnFailureListener {
                    failed = true
                    latch.countDown()
                }
            latch.await()
        } catch (e: Throwable) {
            failed = true
        }
        bmp.recycle()
        if (failed) return null

        return OcrResult(
            PdfTextExtractor.fromWordBoxes(boxes, widthPt, heightPt),
            sb.toString().trim()
        )
    }

    // ------------------------------------------------ Urdu (Tesseract)

    private const val URDU_MODEL = "urd.traineddata"
    private const val URDU_MODEL_VERSION = 1

    /** Copies the Urdu model out of the APK once; Tesseract needs a real file. */
    private fun tessDataPath(context: Context): String? {
        val root = File(context.filesDir, "tesseract")
        val dir = File(root, "tessdata")
        val target = File(dir, URDU_MODEL)
        val stamp = File(dir, "urd.v$URDU_MODEL_VERSION")
        if (target.exists() && stamp.exists() && target.length() > 0) return root.absolutePath
        return try {
            dir.mkdirs()
            val tmp = File(dir, "$URDU_MODEL.part")
            context.assets.open("tessdata/$URDU_MODEL").use { input ->
                FileOutputStream(tmp).use { out -> input.copyTo(out) }
            }
            target.delete()
            tmp.renameTo(target)
            stamp.writeText("ok")
            root.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun recogniseUrdu(context: Context, pdfPath: String, page1: Int): OcrResult? {
        val dataPath = tessDataPath(context) ?: return null
        // Nastaliq needs tall letters to be read well: render at about 300 dpi
        val (bmp, widthPt, heightPt) = try {
            render(pdfPath, page1, 2400f, 2500, 3500)
        } catch (e: Throwable) {
            null
        } ?: return null

        val tess = TessBaseAPI()
        val known = UrduDictionary.knownWords(context)
        val boxes = ArrayList<Pair<String, RectF>>()
        val text = StringBuilder()
        try {
            if (!tess.init(dataPath, "urd", TessBaseAPI.OEM_LSTM_ONLY)) {
                bmp.recycle()
                tess.recycle()
                return null
            }
            tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            tess.setImage(bmp)
            tess.getUTF8Text() // runs the recognition

            val sx = widthPt / bmp.width
            val sy = heightPt / bmp.height
            val iter = tess.resultIterator
            val word = TessBaseAPI.PageIteratorLevel.RIL_WORD
            val lineLevel = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE

            // collect word by word, one line at a time, in reading order
            val lineWords = ArrayList<String>()
            val lineRects = ArrayList<RectF>()
            fun flushLine() {
                if (lineWords.isEmpty()) return
                val fixed = UrduText.fixOcrLine(lineWords) { n -> n in known }
                for ((i, w) in fixed.withIndex()) {
                    if (w.isNotBlank()) boxes.add(w to lineRects[i])
                }
                text.append(fixed.filter { s -> s.isNotBlank() }.joinToString(" ")).append('\n')
                lineWords.clear()
                lineRects.clear()
            }

            if (iter != null) {
                try {
                    iter.begin()
                    do {
                        if (iter.isAtBeginningOf(lineLevel)) flushLine()
                        val w = iter.getUTF8Text(word)?.trim() ?: ""
                        val r = iter.getBoundingRect(word)
                        if (w.isNotEmpty() && r != null && iter.confidence(word) > 15f) {
                            lineWords.add(w)
                            lineRects.add(
                                RectF(r.left * sx, r.top * sy, r.right * sx, r.bottom * sy)
                            )
                        }
                    } while (iter.next(word))
                    flushLine()
                } finally {
                    iter.delete()
                }
            }
        } catch (e: Throwable) {
            // keep whatever was read before the failure
        } finally {
            try {
                tess.recycle()
            } catch (_: Throwable) {
            }
            bmp.recycle()
        }

        if (boxes.isEmpty()) return null
        return OcrResult(
            PdfTextExtractor.fromWordBoxes(boxes, widthPt, heightPt, fromOcr = true, keepOrder = true),
            text.toString().trim()
        )
    }

    fun isAvailable(): Boolean = try {
        recognizer
        true
    } catch (e: Throwable) {
        false
    }
}
