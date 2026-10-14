package com.hashim.notepad.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfiumCore
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.sqrt

/** Opens a PDF with PDFium and renders single pages to bitmaps sized to a target width. */
class PdfPageSource(context: Context, file: File, password: String? = null) : Closeable {
    private val core = synchronized(pdfiumLock) { PdfiumCore(context) }
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val document: PdfDocument = synchronized(pdfiumLock) {
        try {
            if (password.isNullOrEmpty()) core.newDocument(pfd) else core.newDocument(pfd, password)
        } catch (error: Throwable) {
            pfd.close()
            throw error
        }
    }

    val pageCount: Int get() = synchronized(pdfiumLock) { document.getPageCount() }

    /** Page sizes in PDF points, one entry per page. */
    fun pageSizesPt(): List<Pair<Float, Float>> = synchronized(pdfiumLock) {
        List(document.getPageCount()) { i ->
            val page = document.openPage(i) ?: return@List 595f to 842f
            page.use { it.getPageWidthPoint().toFloat() to it.getPageHeightPoint().toFloat() }
        }
    }

    fun render(pageIndex: Int, targetWidthPx: Int): Bitmap = synchronized(pdfiumLock) {
        val page = document.openPage(pageIndex) ?: throw IllegalStateException("Cannot open page $pageIndex")
        page.use {
            val wPt = it.getPageWidthPoint().coerceAtLeast(1)
            val hPt = it.getPageHeightPoint().coerceAtLeast(1)
            val (w, h) = pdfBitmapSize(targetWidthPx, wPt, hPt)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0xFFFFFFFF.toInt())
            it.renderPageBitmap(bitmap, 0, 0, w, h, true, false)
            bitmap
        }
    }

    /** Text layer of a page ("" for scanned pages). */
    fun text(pageIndex: Int): String = synchronized(pdfiumLock) {
        val page = document.openPage(pageIndex)
        if (page == null) {
            ""
        } else {
            page.use { pg ->
                pg.openTextPage().use { tp ->
                    val n = tp.textPageCountChars()
                    if (n <= 0) "" else (tp.textPageGetText(0, n) ?: "")
                }
            }
        }
    }

    fun textGeometry(pageIndex: Int): PdfGeometryTextLayer = synchronized(pdfiumLock) {
        val page = document.openPage(pageIndex) ?: return@synchronized PdfGeometryText.order("", emptyList())
        page.use { pg ->
            pg.openTextPage().use { textPage ->
                val count = textPage.textPageCountChars()
                val text = if (count <= 0) "" else textPage.textPageGetText(0, count).orEmpty()
                val pageHeight = pg.getPageHeightPoint().toFloat()
                val boxes = List(count.coerceAtLeast(0)) { index ->
                    textPage.textPageGetCharBox(index)?.let {
                        PdfTextRect(it.left, pageHeight - it.top, it.right, pageHeight - it.bottom)
                    } ?: PdfTextRect(0f, 0f, 0f, 0f)
                }
                PdfGeometryText.order(text, boxes)
            }
        }
    }

    fun textLineRects(pageIndex: Int, start: Int, end: Int): List<PdfTextRect> = synchronized(pdfiumLock) {
        val page = document.openPage(pageIndex) ?: return@synchronized emptyList()
        page.use { pg ->
            pg.openTextPage().use { textPage ->
                val boxes = (start until end).mapNotNull { index ->
                    if (index !in 0 until textPage.textPageCountChars()) return@mapNotNull null
                    textPage.textPageGetCharBox(index)?.let {
                        RectF(it.left, pg.getPageHeightPoint() - it.top, it.right, pg.getPageHeightPoint() - it.bottom)
                    }?.takeUnless { it.isEmpty }
                }.sortedWith(compareBy<RectF> { it.centerY() }.thenBy { it.left })
                val lines = ArrayList<RectF>()
                boxes.forEach { box ->
                    val line = lines.lastOrNull()
                    if (line != null && kotlin.math.abs(line.centerY() - box.centerY()) <= 3f) {
                        line.union(box)
                    } else {
                        lines += RectF(box)
                    }
                }
                lines.map { PdfTextRect(it.left, it.top, it.right, it.bottom) }
            }
        }
    }

    fun textCharBoxes(pageIndex: Int): List<PdfTextRect> = synchronized(pdfiumLock) {
        val page = document.openPage(pageIndex) ?: return@synchronized emptyList()
        page.use { pg ->
            pg.openTextPage().use { textPage ->
                val pageHeight = pg.getPageHeightPoint().toFloat()
                List(textPage.textPageCountChars()) { index ->
                    textPage.textPageGetCharBox(index)?.let {
                        PdfTextRect(it.left, pageHeight - it.top, it.right, pageHeight - it.bottom)
                    } ?: PdfTextRect(0f, 0f, 0f, 0f)
                }
            }
        }
    }

    fun tableOfContents(): List<PdfOutlineItem> = synchronized(pdfiumLock) {
        fun convert(items: List<io.legere.pdfiumandroid.api.Bookmark>): List<PdfOutlineItem> =
            items.map { bookmark ->
                PdfOutlineItem(bookmark.title.orEmpty(), bookmark.pageIdx.toInt(), convert(bookmark.children))
            }
        convert(document.getTableOfContents())
    }

    override fun close() = synchronized(pdfiumLock) {
        document.close()
        pfd.close()
    }

    companion object {
        private val pdfiumLock = Any()
        private const val MAX_BITMAP_PIXELS = 16_777_216L

        internal fun pdfBitmapSize(targetWidthPx: Int, pageWidthPt: Int, pageHeightPt: Int): Pair<Int, Int> {
            var width = targetWidthPx.coerceIn(64, 4096)
            var height = (width.toLong() * pageHeightPt.coerceAtLeast(1) / pageWidthPt.coerceAtLeast(1))
                .toInt().coerceIn(64, 8192)
            val pixelCount = width.toLong() * height
            if (pixelCount > MAX_BITMAP_PIXELS) {
                val scale = sqrt(MAX_BITMAP_PIXELS.toDouble() / pixelCount)
                width = (width * scale).toInt().coerceAtLeast(64)
                height = (height * scale).toInt().coerceAtLeast(64)
                while (width.toLong() * height > MAX_BITMAP_PIXELS) {
                    if (width >= height) width-- else height--
                }
            }
            return width to height
        }
    }
}

object PdfiumDispatcher {
    val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "NotePad-Pdfium").apply { isDaemon = true } }
        .asCoroutineDispatcher()
}
