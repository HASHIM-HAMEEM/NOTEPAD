package com.hashim.notepad.export

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.data.Page
import com.hashim.notepad.data.PageKind
import com.hashim.notepad.ink.InkTextures
import com.hashim.notepad.ink.PageTemplateRenderer
import com.hashim.notepad.ink.StrokeStore
import com.hashim.notepad.ink.TextBlockPainter
import com.hashim.notepad.ink.drawStrokes
import com.hashim.notepad.pdf.PdfPageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/** Flattens a notebook (PDF/template pages + ink) into a single PDF rendered with the same Ink renderer used on screen. */
class PdfExporter(private val context: Context, private val repo: NotePadRepository) {

    suspend fun exportNotebook(notebookId: String, title: String): File = withContext(Dispatchers.Default) {
        val pages = repo.pages(notebookId).first()
        val paperArgb = repo.notebooks().first().firstOrNull { it.id == notebookId }?.coverColor
            ?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: Color.WHITE
        val doc = PdfDocument()
        val renderer = CanvasStrokeRenderer.create(InkTextures.textureStore)
        val sources = HashMap<String, PdfPageSource>()
        try {
            pages.forEachIndexed { i, page ->
                val info = PdfDocument.PageInfo.Builder(page.widthPt.toInt(), page.heightPt.toInt(), i + 1).create()
                val pdfPage = doc.startPage(info)
                val canvas = pdfPage.canvas
                canvas.drawColor(paperArgb)
                drawBackground(canvas, page, sources, paperArgb)
                repo.pageImages(page.id).first().forEach { image ->
                    val bitmap = BitmapFactory.decodeFile(image.localPath) ?: return@forEach
                    canvas.drawBitmap(bitmap, null, RectF(image.xPt, image.yPt, image.xPt + image.wPt, image.yPt + image.hPt), Paint(Paint.FILTER_BITMAP_FLAG))
                    bitmap.recycle()
                }
                repo.loadStrokes(page.id)?.let { bytes ->
                    val strokes = runCatching { StrokeStore.decode(bytes) }.getOrDefault(emptyList())
                    drawStrokes(canvas, strokes, renderer, Matrix(), paperArgb)
                }
                for (b in repo.textBlocks(page.id).first()) TextBlockPainter.draw(canvas, b, false, paperArgb)
                doc.finishPage(pdfPage)
            }
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val safe = title.replace(Regex("[^\\p{L}\\p{N} _-]"), "_").ifBlank { "notebook" }
            val out = File(dir, "$safe.pdf")
            out.outputStream().use { doc.writeTo(it) }
            out
        } finally {
            doc.close()
            sources.values.forEach { it.close() }
        }
    }

    private suspend fun drawBackground(canvas: Canvas, page: Page, sources: HashMap<String, PdfPageSource>, paperArgb: Int) {
        when (page.kind) {
            PageKind.PDF -> {
                val asset = page.assetId?.let { repo.asset(it) } ?: return
                val src = sources.getOrPut(asset.id) {
                    PdfPageSource(context, File(asset.localPath), com.hashim.notepad.pdf.PdfPasswordVault(context).read(asset.id))
                }
                val bmp = src.render(page.pdfPageIndex ?: 0, (page.widthPt * 2).toInt())
                val m = Matrix().apply { setScale(page.widthPt / bmp.width, page.heightPt / bmp.height) }
                canvas.drawBitmap(bmp, m, Paint(Paint.FILTER_BITMAP_FLAG))
                bmp.recycle()
            }
            else -> {
                val darkPaper = Color.luminance(paperArgb)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (darkPaper < 0.5f) 0xFF3A3A40.toInt() else 0xFFC9CDD6.toInt()
                    strokeWidth = 0.5f
                }
                PageTemplateRenderer.draw(canvas, paint, page.widthPt, page.heightPt, page.kind)
            }
        }
    }

    fun share(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Export PDF").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
