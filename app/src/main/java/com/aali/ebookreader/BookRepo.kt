package com.aali.ebookreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream

data class BookItem(val file: File, val title: String, val format: String, val cover: File?)

object BookRepo {

    fun load(context: Context): List<BookItem> {
        val db = Db.get(context)
        return Storage.listBooks().map { f ->
            BookItem(f, f.nameWithoutExtension, f.extension.uppercase(), cover(context, f))
        }.sortedByDescending { db.lastOpened(it.file.absolutePath) }
    }

    private fun cover(context: Context, book: File): File? {
        val dir = File(context.cacheDir, "covers").apply { mkdirs() }
        val out = File(dir, Storage.safeName(book.name) + "-" + (book.length() % 1000000) + ".png")
        if (out.exists()) return out
        return try {
            when (book.extension.lowercase()) {
                "pdf" -> pdfCover(book, out)
                "epub" -> epubCover(context, book, out)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun pdfCover(book: File, out: File): File? {
        ParcelFileDescriptor.open(book, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount == 0) return null
                renderer.openPage(0).use { page ->
                    val w = 360
                    val h = (w.toFloat() / page.width * page.height).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 85, it) }
                    bmp.recycle()
                }
            }
        }
        return out
    }

    private fun epubCover(context: Context, book: File, out: File): File? {
        val parsed = EpubParser.parse(context, book)
        val src = parsed.coverImage ?: return null
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        val bmp = BitmapFactory.decodeFile(src.absolutePath, opts) ?: return null
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 85, it) }
        bmp.recycle()
        return out
    }
}
