package com.hashim.notepad.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfiumCore
import java.io.Closeable

/** Opens a PDF with PDFium and renders single pages to bitmaps sized to a target width. */
class PdfPageSource(context: Context, uri: Uri) : Closeable {
    private val core = PdfiumCore(context)
    private val pfd = context.contentResolver.openFileDescriptor(uri, "r")
        ?: throw IllegalStateException("Cannot open $uri")
    private val document: PdfDocument = core.newDocument(pfd)

    val pageCount: Int get() = document.getPageCount()

    fun render(pageIndex: Int, targetWidthPx: Int): Bitmap {
        val opened = document.openPage(pageIndex) ?: throw IllegalStateException("Cannot open page $pageIndex")
        opened.use { page ->
            val wPt = page.getPageWidthPoint().coerceAtLeast(1)
            val hPt = page.getPageHeightPoint().coerceAtLeast(1)
            val w = targetWidthPx.coerceIn(64, 4096)
            val h = (w.toLong() * hPt / wPt).toInt().coerceIn(64, 8192)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0xFFFFFFFF.toInt())
            page.renderPageBitmap(bitmap, 0, 0, w, h, true, false)
            return bitmap
        }
    }

    override fun close() {
        document.close()
        pfd.close()
    }
}
