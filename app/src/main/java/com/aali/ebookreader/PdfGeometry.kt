package com.aali.ebookreader

import com.github.barteksc.pdfviewer.PDFView

/**
 * Where each page sits inside the PDF view. The library computes this
 * internally, so the exact values are read from it by reflection, with a
 * matching hand calculation as a safety net if that ever stops working.
 */
object PdfGeometry {

    private var pdfFileField: java.lang.reflect.Field? = null
    private var pageOffsetMethod: java.lang.reflect.Method? = null
    private var secondaryOffsetMethod: java.lang.reflect.Method? = null
    private var reflectionBroken = false

    private fun pdfFileOf(view: PDFView): Any? {
        if (reflectionBroken) return null
        return try {
            if (pdfFileField == null) {
                pdfFileField = PDFView::class.java.getDeclaredField("pdfFile")
                    .apply { isAccessible = true }
            }
            pdfFileField?.get(view)
        } catch (e: Throwable) {
            reflectionBroken = true
            null
        }
    }

    fun pageOffset(view: PDFView, page: Int, zoom: Float): Float {
        val file = pdfFileOf(view)
        if (file != null) {
            try {
                if (pageOffsetMethod == null) {
                    pageOffsetMethod = file.javaClass.getMethod(
                        "getPageOffset", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType
                    ).apply { isAccessible = true }
                }
                val v = pageOffsetMethod?.invoke(file, page, zoom)
                if (v is Float) return v
            } catch (e: Throwable) {
                reflectionBroken = true
            }
        }
        return manualPageOffset(view, page, zoom)
    }

    fun secondaryOffset(view: PDFView, page: Int, zoom: Float): Float {
        val file = pdfFileOf(view)
        if (file != null) {
            try {
                if (secondaryOffsetMethod == null) {
                    secondaryOffsetMethod = file.javaClass.getMethod(
                        "getSecondaryPageOffset",
                        Int::class.javaPrimitiveType, Float::class.javaPrimitiveType
                    ).apply { isAccessible = true }
                }
                val v = secondaryOffsetMethod?.invoke(file, page, zoom)
                if (v is Float) return v
            } catch (e: Throwable) {
                reflectionBroken = true
            }
        }
        return manualSecondaryOffset(view, page, zoom)
    }

    private fun manualPageOffset(view: PDFView, page: Int, zoom: Float): Float {
        var offset = 0f
        val spacing = view.spacingPx.toFloat()
        for (i in 0 until page) {
            val s = view.getPageSize(i) ?: continue
            offset += if (view.isSwipeVertical) s.height else s.width
            offset += spacing
        }
        return offset * zoom
    }

    private fun manualSecondaryOffset(view: PDFView, page: Int, zoom: Float): Float {
        val s = view.getPageSize(page) ?: return 0f
        var maxSize = 0f
        for (i in 0 until view.pageCount) {
            val p = view.getPageSize(i) ?: continue
            val v = if (view.isSwipeVertical) p.width else p.height
            if (v > maxSize) maxSize = v
        }
        val own = if (view.isSwipeVertical) s.width else s.height
        return (maxSize - own) / 2f * zoom
    }
}
