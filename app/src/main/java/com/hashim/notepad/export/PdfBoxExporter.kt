package com.hashim.notepad.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.data.Page
import com.hashim.notepad.data.PageKind
import com.hashim.notepad.data.PdfMark
import com.hashim.notepad.data.TextBlock
import com.hashim.notepad.ink.InkBrushFamilies
import com.hashim.notepad.ink.StrokeStore
import com.hashim.notepad.ink.TextBlockPainter
import com.hashim.notepad.pdf.PdfMarkGeometry
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.util.Matrix
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.Bidi

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
        PDFBoxResourceLoader.init(context.applicationContext)
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
                if (options.includeText) drawOcrText(output, target, page)
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
                        annotation.page = target
                        annotation.setPrinted(true)
                        annotation.constructAppearances(output)
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
        val alpha = android.graphics.Color.alpha(color) / 255f
        val highlighter = stroke.brush.family == StockBrushes.highlighter()
        val red = android.graphics.Color.red(color) / 255f
        val green = android.graphics.Color.green(color) / 255f
        val blue = android.graphics.Color.blue(color) / 255f
        stream.saveGraphicsState()
        if (alpha < 1f || highlighter) {
            applyStrokeGraphicsState(stream, alpha, highlighter)
        }
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
            val fontAsset = if (block.content.any { it in "ٹڈڑںھہ" }) {
                "fonts/NotoNastaliqUrdu.ttf"
            } else {
                "fonts/NotoNaskhArabic.ttf"
            }
            context.assets.open(fontAsset).use { font ->
                drawTextBlock(
                    document = document,
                    stream = stream,
                    fontFile = font,
                    block = block,
                    pageHeight = page.heightPt,
                    context = context,
                )
            }
        }
    }

    private suspend fun drawOcrText(document: PDDocument, page: PDPage, model: Page) {
        val words = repo.ocrWords(model.id)
        if (words.isEmpty()) return
        context.assets.open("fonts/NotoNaskhArabic.ttf").use { input ->
            val font = PDType0Font.load(document, input, true)
            PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                stream.setRenderingMode(RenderingMode.NEITHER)
                stream.setFont(font, 10f)
                words.sortedBy { it.index }.forEach { word ->
                    val sanitizedText = PdfTextSanitizer.sanitize(word.text) { codePoint ->
                        fontCanEncode(font, codePoint)
                    }
                    if (sanitizedText.isEmpty()) return@forEach
                    stream.beginText()
                    try {
                        stream.setFont(font, (word.bottom - word.top).coerceAtLeast(1f))
                        stream.newLineAtOffset(word.left, model.heightPt - word.bottom)
                        stream.showText(pdfTextLayerOrder(sanitizedText))
                    } finally {
                        stream.endText()
                    }
                }
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

internal fun applyStrokeGraphicsState(
    stream: PDPageContentStream,
    alpha: Float,
    multiply: Boolean,
) {
    val state = PDExtendedGraphicsState().apply {
        strokingAlphaConstant = alpha.coerceIn(0f, 1f)
        nonStrokingAlphaConstant = alpha.coerceIn(0f, 1f)
        if (multiply) blendMode = BlendMode.MULTIPLY
    }
    stream.setGraphicsStateParameters(state)
}

internal fun drawTextBlock(
    document: PDDocument,
    stream: PDPageContentStream,
    fontFile: java.io.InputStream,
    text: String,
    colorArgb: Int,
    fontSize: Float,
    x: Float,
    baseline: Float,
    rotationDegrees: Float = 0f,
) {
    val font = PDType0Font.load(document, fontFile, true)
    val sanitizedText = PdfTextSanitizer.sanitize(text) { codePoint ->
        fontCanEncode(font, codePoint)
    }
    if (sanitizedText.isEmpty()) return
    drawVectorTextBlock(stream, font, pdfTextLayerOrder(sanitizedText), colorArgb, fontSize, x, baseline, rotationDegrees)
}

private fun drawTextBlock(
    document: PDDocument,
    stream: PDPageContentStream,
    fontFile: java.io.InputStream,
    block: TextBlock,
    pageHeight: Float,
    context: Context,
) {
    val font = PDType0Font.load(document, fontFile, true)
    val canEncode: (Int) -> Boolean = { codePoint -> fontCanEncode(font, codePoint) }
    val baseline = pageHeight - block.yPt - block.fontSizePt
    val rotationDegrees = -block.rotationDegrees
    if (requiresRasterText(block.content, canEncode)) {
        drawRasterTextBlock(document, stream, block, pageHeight, context)
        val searchableText = PdfTextSanitizer.sanitize(block.content, canEncode)
        if (searchableText.isNotEmpty()) {
            drawInvisibleTextBlock(
                stream,
                font,
                pdfTextLayerOrder(searchableText),
                block.fontSizePt,
                block.xPt,
                baseline,
                rotationDegrees,
            )
        }
    } else {
        val sanitizedText = PdfTextSanitizer.sanitize(block.content, canEncode)
        if (sanitizedText.isNotEmpty()) {
            drawVectorTextBlock(
                stream,
                font,
                pdfTextLayerOrder(sanitizedText),
                block.colorArgb ?: android.graphics.Color.BLACK,
                block.fontSizePt,
                block.xPt,
                baseline,
                rotationDegrees,
            )
        }
    }
}

private fun drawVectorTextBlock(
    stream: PDPageContentStream,
    font: PDType0Font,
    text: String,
    colorArgb: Int,
    fontSize: Float,
    x: Float,
    baseline: Float,
    rotationDegrees: Float,
) {
    stream.setNonStrokingColor(
        colorArgb ushr 16 and 0xff,
        colorArgb ushr 8 and 0xff,
        colorArgb and 0xff,
    )
    stream.beginText()
    try {
        stream.setFont(font, fontSize.coerceAtLeast(1f))
        stream.setTextMatrix(
            Matrix.getRotateInstance(
                Math.toRadians(rotationDegrees.toDouble()),
                x,
                baseline,
            ),
        )
        stream.showText(text)
    } finally {
        stream.endText()
    }
}

private fun drawRasterTextBlock(
    document: PDDocument,
    stream: PDPageContentStream,
    block: TextBlock,
    pageHeight: Float,
    context: Context,
) {
    val layout = TextBlockPainter.layout(block, android.graphics.Color.WHITE, context)
    val width = layout.width.coerceAtLeast(1)
    val height = layout.height.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width * 4, height * 4, Bitmap.Config.ARGB_8888)
    try {
        bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
        Canvas(bitmap).apply {
            scale(4f, 4f)
            layout.draw(this)
        }
        val image = LosslessFactory.createFromImage(document, bitmap)
        stream.saveGraphicsState()
        try {
            val angle = Math.toRadians(-block.rotationDegrees.toDouble())
            val cosine = Math.cos(angle).toFloat()
            val sine = Math.sin(angle).toFloat()
            val pivotX = block.xPt
            val pivotY = pageHeight - block.yPt - block.fontSizePt
            val translateX = pivotX - cosine * pivotX + sine * pivotY
            val translateY = pivotY - sine * pivotX - cosine * pivotY
            stream.transform(
                Matrix(
                    cosine,
                    sine,
                    -sine,
                    cosine,
                    translateX,
                    translateY,
                ),
            )
            stream.drawImage(
                image,
                block.xPt,
                pageHeight - block.yPt - height,
                width.toFloat(),
                height.toFloat(),
            )
        } finally {
            stream.restoreGraphicsState()
        }
    } finally {
        bitmap.recycle()
    }
}

private fun drawInvisibleTextBlock(
    stream: PDPageContentStream,
    font: PDType0Font,
    text: String,
    fontSize: Float,
    x: Float,
    baseline: Float,
    rotationDegrees: Float,
) {
    stream.saveGraphicsState()
    try {
        stream.setRenderingMode(RenderingMode.NEITHER)
        stream.beginText()
        try {
            stream.setFont(font, fontSize.coerceAtLeast(1f))
            stream.setTextMatrix(
                Matrix.getRotateInstance(
                    Math.toRadians(rotationDegrees.toDouble()),
                    x,
                    baseline,
                ),
            )
            stream.showText(text)
        } finally {
            stream.endText()
        }
    } finally {
        stream.restoreGraphicsState()
    }
}

private fun requiresRasterText(text: String, canEncode: (Int) -> Boolean): Boolean {
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        if (codePoint in 0x0590..0x08FF ||
            codePoint in 0xFB50..0xFDFF ||
            codePoint in 0xFE70..0xFEFF ||
            !canEncode(codePoint)
        ) {
            return true
        }
        index += Character.charCount(codePoint)
    }
    return false
}

private fun fontCanEncode(font: PDType0Font, codePoint: Int): Boolean =
    runCatching { font.encode(String(Character.toChars(codePoint))).isNotEmpty() }.getOrDefault(false)

private fun pdfTextLayerOrder(text: String): String {
    val bidi = Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
    if (bidi.runCount == 0) return text
    return buildString(text.length) {
        for (run in 0 until bidi.runCount) {
            val start = bidi.getRunStart(run)
            val end = bidi.getRunLimit(run)
            val runText = text.substring(start, end)
            if (bidi.getRunLevel(run) and 1 == 0) {
                append(runText)
            } else {
                var index = runText.length
                while (index > 0) {
                    val codePoint = runText.codePointBefore(index)
                    index -= Character.charCount(codePoint)
                    appendCodePoint(codePoint)
                }
            }
        }
    }
}
