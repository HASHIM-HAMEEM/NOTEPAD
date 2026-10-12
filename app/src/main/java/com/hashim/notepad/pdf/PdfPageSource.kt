package com.hashim.notepad.pdf

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfiumCore
import java.io.Closeable
import java.io.File

/** Opens a PDF with PDFium and renders single pages to bitmaps sized to a target width. */
class PdfPageSource(context: Context, file: File) : Closeable {
    private val core = PdfiumCore(context)
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val document: PdfDocument = core.newDocument(pfd)

    val pageCount: Int get() = document.getPageCount()

    /** Page sizes in PDF points, one entry per page. */
    fun pageSizesPt(): List<Pair<Float, Float>> = List(pageCount) { i ->
        val page = document.openPage(i) ?: return@List 595f to 842f
        page.use { it.getPageWidthPoint().toFloat() to it.getPageHeightPoint().toFloat() }
    }

    fun render(pageIndex: Int, targetWidthPx: Int): Bitmap {
        val page = document.openPage(pageIndex) ?: throw IllegalStateException("Cannot open page $pageIndex")
        page.use {
            val wPt = it.getPageWidthPoint().coerceAtLeast(1)
            val hPt = it.getPageHeightPoint().coerceAtLeast(1)
            val w = targetWidthPx.coerceIn(64, 4096)
            val h = (w.toLong() * hPt / wPt).toInt().coerceIn(64, 8192)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0xFFFFFFFF.toInt())
            it.renderPageBitmap(bitmap, 0, 0, w, h, true, false)
            return bitmap
        }
    }

    /** Text layer of a page ("" for scanned pages). */
    fun text(pageIndex: Int): String {
        val page = document.openPage(pageIndex) ?: return ""
        return page.use { pg ->
            pg.openTextPage().use { tp ->
                val n = tp.textPageCountChars()
                if (n <= 0) "" else (tp.textPageGetText(0, n) ?: "")
            }
        }
    }

    override fun close() { document.close(); pfd.close() }
}
