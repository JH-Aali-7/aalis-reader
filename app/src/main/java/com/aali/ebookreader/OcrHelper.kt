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
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * Reads text out of scanned (picture only) PDF pages using the on device
 * text recogniser. The model ships inside the app, so this works with no
 * internet connection.
 */
object OcrHelper {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    class OcrResult(val page: PdfPageText, val text: String)

    /** Renders one page large enough for the recogniser, then reads it. */
    fun recognisePage(context: Context, pdfPath: String, page1: Int): OcrResult? {
        var bitmap: Bitmap? = null
        var widthPt = 612f
        var heightPt = 792f
        try {
            ParcelFileDescriptor.open(
                File(pdfPath), ParcelFileDescriptor.MODE_READ_ONLY
            ).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (page1 < 1 || page1 > renderer.pageCount) return null
                    renderer.openPage(page1 - 1).use { page ->
                        widthPt = page.width.toFloat()
                        heightPt = page.height.toFloat()
                        // about 200 dpi, capped so very large pages stay safe
                        val scale = (2200f / page.width).coerceIn(1.5f, 3.5f)
                        val w = (page.width * scale).toInt().coerceAtMost(3200)
                        val h = (page.height * scale).toInt().coerceAtMost(4400)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap = bmp
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }

        val bmp = bitmap ?: return null
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

    fun isAvailable(): Boolean = try {
        recognizer
        true
    } catch (e: Throwable) {
        false
    }
}
