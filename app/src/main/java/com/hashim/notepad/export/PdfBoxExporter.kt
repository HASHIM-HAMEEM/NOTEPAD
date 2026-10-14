package com.hashim.notepad.export

import android.content.Context
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.data.Page
import com.hashim.notepad.data.PageKind
import com.hashim.notepad.data.PdfMark
import com.hashim.notepad.ink.InkBrushFamilies
import com.hashim.notepad.ink.StrokeStore
import com.hashim.notepad.pdf.PdfMarkGeometry
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class PdfExportOptions(
    val pageRange: IntRange? = null,
    val includeInk: Boolean = true,
    val includeImages: Boolean = true,
    val includeText: Boolean = true,
    val includeMarks: Boolean = true,
    val flattenMarks: Boolean = false,
)

/**
 * Vector-first PDF exporter. Imported source pages are copied as PDF page objects and marks
 * remain real text-markup annotations; the legacy canvas exporter is used by the caller only
 * when PdfBox cannot parse a source document.
 */
class PdfBoxExporter(private val context: Context, private val repo: NotePadRepository) {
    suspend fun exportNotebook(
        notebookId: String,
        title: String,
        options: PdfExportOptions = PdfExportOptions(),
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val allPages = repo.pages(notebookId).first()
        val pagePlan = planPdfExportPages(allPages, options.pageRange)
        val pages = pagePlan.map { it.page }
        val marks = repo.pdfMarks(notebookId).first()
        val sources = HashMap<String, PDDocument>()
        val output = PDDocument()
        var temporaryOutput: File? = null
        try {
            pages.forEachIndexed { pageOffset, page ->
                currentCoroutineContext().ensureActive()
                val target: PDPage
                if (page.kind == PageKind.PDF && page.assetId != null) {
                    val asset = repo.asset(page.assetId) ?: error("Missing PDF asset")
                    val source = sources.getOrPut(asset.id) { PDDocument.load(File(asset.localPath)) }
                    target = output.importPage(source.getPage(page.pdfPageIndex ?: 0))
                } else {
                    target = PDPage(PDRectangle(page.widthPt, page.heightPt))
                    output.addPage(target)
                }
                target.rotation = pagePlan[pageOffset].page.rotation
                if (page.kind != PageKind.PDF) drawTemplate(output, target, page)
                if (options.includeInk || options.includeImages || options.includeText || options.flattenMarks) {
                    drawLocalContent(output, target, page, options, marks.filter { it.pageId == page.id && !it.deleted })
                }
                val pageMarks = marks.filter { it.pageId == page.id && !it.deleted }
                if (options.includeMarks && !options.flattenMarks && pageMarks.isNotEmpty()) {
                    val annotations = target.annotations
                    pageMarks.forEach { mark ->
                        val quads = PdfMarkGeometry.rotatedQuads(mark.quads, page.widthPt, page.heightPt, page.rotation)
                        val bounds = PdfMarkGeometry.quads(quads).let { boxes ->
                            if (boxes.isEmpty()) null else com.tom_roush.pdfbox.pdmodel.common.PDRectangle(
                                boxes.minOf { it.left }, boxes.minOf { it.top },
                                boxes.maxOf { it.right } - boxes.minOf { it.left },
                                boxes.maxOf { it.bottom } - boxes.minOf { it.top },
                            )
                        }
                        val annotation = PDAnnotationTextMarkup(PdfMarkGeometry.toMarkupSubtype(mark.kind))
                        annotation.quadPoints = quads.toFloatArray()
                        if (bounds != null) annotation.rectangle = bounds
                        annotation.color = PDColor(
                            floatArrayOf(
                                android.graphics.Color.red(mark.colorArgb) / 255f,
                                android.graphics.Color.green(mark.colorArgb) / 255f,
                                android.graphics.Color.blue(mark.colorArgb) / 255f,
                            ),
                            PDDeviceRGB.INSTANCE,
                        )
                        annotation.contents = mark.note ?: mark.text
                        annotations.add(annotation)
                    }
                }
                onProgress(pageOffset + 1, pages.size)
            }
            val outDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val safe = title.replace(Regex("[^\\p{L}\\p{N} _-]"), "_").ifBlank { "notebook" }
            val result = File(outDir, "$safe.pdf")
            val temporary = File(outDir, "$safe.pdf.part").also { temporaryOutput = it }
            temporary.delete()
            output.save(temporary)
            currentCoroutineContext().ensureActive()
            check(temporary.renameTo(result)) { "Could not commit export" }
            result
        } finally {
            temporaryOutput?.takeIf(File::exists)?.delete()
            output.close()
            sources.values.forEach { it.close() }
        }
    }

    private suspend fun drawLocalContent(
        document: PDDocument,
        page: PDPage,
        model: Page,
        options: PdfExportOptions,
        marks: List<PdfMark>,
    ) {
        PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
            if (options.includeImages) {
                repo.pageImages(model.id).first().forEach { image ->
                    currentCoroutineContext().ensureActive()
                    runCatching {
                        val xObject = PDImageXObject.createFromFileByContent(File(image.localPath), document)
                        stream.drawImage(
                            xObject,
                            image.xPt,
                            model.heightPt - image.yPt - image.hPt,
                            image.wPt,
                            image.hPt,
                        )
                    }
                }
            }
            if (options.includeInk) {
                repo.loadStrokes(model.id)?.let { bytes ->
                    runCatching { StrokeStore.decode(bytes) }.getOrDefault(emptyList()).forEach { stroke ->
                        drawStroke(stream, stroke, model.heightPt)
                    }
                }
            }
            if (options.includeText) drawTextBlocks(document, stream, model)
            if (options.flattenMarks && options.includeMarks) marks.forEach { mark ->
                stream.saveGraphicsState()
                val red = android.graphics.Color.red(mark.colorArgb) / 255f
                val green = android.graphics.Color.green(mark.colorArgb) / 255f
                val blue = android.graphics.Color.blue(mark.colorArgb) / 255f
                stream.setNonStrokingColor(red, green, blue)
                PdfMarkGeometry.quads(mark.quads).forEach { quad ->
                    val bottom = quad.bottom
                    val top = quad.top
                    when (mark.kind) {
                        com.hashim.notepad.data.PdfMarkKind.HIGHLIGHT -> stream.addRect(quad.left, bottom, quad.right - quad.left, top - bottom)
                        com.hashim.notepad.data.PdfMarkKind.UNDERLINE -> {
                            stream.setStrokingColor(red, green, blue)
                            stream.setLineWidth(1.5f)
                            stream.drawLine(quad.left, bottom, quad.right, bottom)
                        }
                        com.hashim.notepad.data.PdfMarkKind.STRIKE -> {
                            stream.setStrokingColor(red, green, blue)
                            stream.setLineWidth(1.5f)
                            stream.drawLine(quad.left, (bottom + top) / 2f, quad.right, (bottom + top) / 2f)
                        }
                        com.hashim.notepad.data.PdfMarkKind.SQUIGGLY -> {
                            stream.setStrokingColor(red, green, blue)
                            stream.setLineWidth(1f)
                            stream.moveTo(quad.left, bottom)
                            var x = quad.left
                            var up = true
                            while (x < quad.right) {
                                x = minOf(quad.right, x + 2f)
                                stream.lineTo(x, bottom + if (up) -1f else 1f)
                                up = !up
                            }
                            stream.stroke()
                        }
                    }
                    if (mark.kind == com.hashim.notepad.data.PdfMarkKind.HIGHLIGHT) stream.fill()
                }
                stream.restoreGraphicsState()
            }
        }
    }

    private fun drawStroke(stream: PDPageContentStream, stroke: Stroke, pageHeight: Float) {
        if (stroke.inputs.size < 2) return
        val color = stroke.brush.colorIntArgb
        val red = android.graphics.Color.red(color) / 255f
        val green = android.graphics.Color.green(color) / 255f
        val blue = android.graphics.Color.blue(color) / 255f
        stream.saveGraphicsState()
        stream.setStrokingColor(red, green, blue)
        stream.setNonStrokingColor(red, green, blue)
        stream.setLineWidth(stroke.brush.size.coerceAtLeast(0.35f))
        stream.setLineCapStyle(1)
        stream.setLineJoinStyle(1)
        val first = stroke.inputs[0]
        val last = stroke.inputs[stroke.inputs.size - 1]
        if (stroke.brush.family == InkBrushFamilies.tape) {
            val left = minOf(first.x, last.x)
            val right = maxOf(first.x, last.x)
            val y = pageHeight - (first.y + last.y) / 2f
            val thickness = stroke.brush.size.coerceAtLeast(1f)
            stream.addRect(left, y - thickness / 2f, maxOf(1f, right - left), thickness)
            stream.fill()
        } else {
            stream.moveTo(first.x, pageHeight - first.y)
            for (index in 1 until stroke.inputs.size) {
                val point = stroke.inputs[index]
                stream.lineTo(point.x, pageHeight - point.y)
            }
            stream.stroke()
        }
        stream.restoreGraphicsState()
    }

    private suspend fun drawTextBlocks(document: PDDocument, stream: PDPageContentStream, page: Page) {
        val blocks = repo.textBlocks(page.id).first()
        blocks.forEach { block ->
            val fontAsset = if (block.content.any { it in '\u0600'..'\u06FF' && it in "ٹڈڑںھہ" }) {
                "fonts/NotoNastaliqUrdu.ttf"
            } else "fonts/NotoNaskhArabic.ttf"
            runCatching {
                val font = PDType0Font.load(document, context.assets.open(fontAsset), true)
                stream.beginText()
                stream.setFont(font, block.fontSizePt)
                stream.newLineAtOffset(block.xPt, page.heightPt - block.yPt - block.fontSizePt)
                stream.showText(block.content)
                stream.endText()
            }
        }
    }

    private fun drawTemplate(document: PDDocument, page: PDPage, model: Page) {
        if (model.kind == PageKind.BLANK) return
        PDPageContentStream(document, page).use { stream ->
            stream.setStrokingColor(0.78f, 0.80f, 0.84f)
            stream.setLineWidth(0.45f)
            val spacing = 24f
            when (model.kind) {
                PageKind.GRID, PageKind.DOTS -> {
                    var x = 24f
                    while (x < model.widthPt) {
                        var y = 24f
                        while (y < model.heightPt) {
                            if (model.kind == PageKind.GRID) stream.drawLine(x, y, x, y + 0.1f)
                            else stream.addRect(x, y, 0.7f, 0.7f).also { stream.fill() }
                            y += spacing
                        }
                        x += spacing
                    }
                }
                else -> {
                    var y = 48f
                    while (y < model.heightPt) {
                        stream.drawLine(28f, model.heightPt - y, model.widthPt - 24f, model.heightPt - y)
                        y += spacing
                    }
                    if (model.kind == PageKind.CORNELL) stream.drawLine(model.widthPt * 0.68f, 32f, model.widthPt * 0.68f, model.heightPt - 32f)
                    if (model.kind == PageKind.MUSIC) repeat(4) { staff ->
                        repeat(5) { line ->
                            val y = model.heightPt - 60f - staff * 96f - line * 8f
                            stream.drawLine(30f, y, model.widthPt - 24f, y)
                        }
                    }
                }
            }
            stream.stroke()
        }
    }
}
