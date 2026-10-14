package com.hashim.notepad.export

import android.graphics.Color as AndroidColor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hashim.notepad.data.Asset
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.data.PdfMark
import com.hashim.notepad.data.PdfMarkKind
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.hashim.notepad.pdf.PdfPageSource
import com.hashim.notepad.pdf.PdfGeometryText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfBoxTextExportInstrumentedTest {
    @Test
    fun textBlockExportsAsSearchableEmbeddedText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val font = File(context.cacheDir, "NotoNaskhArabic.ttf")
        context.assets.open("fonts/NotoNaskhArabic.ttf").use { input ->
            font.outputStream().use(input::copyTo)
        }
        val output = File(context.cacheDir, "text-export.pdf")
        PDDocument().use { document ->
            val page = PDPage(PDRectangle(300f, 300f))
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                font.inputStream().use { input ->
                    drawTextBlock(
                        document,
                        stream,
                        input,
                        "Release101 text survives",
                        0xff2355cc.toInt(),
                        14f,
                        24f,
                        220f,
                        12f,
                    )
                }
            }
            document.save(output)
        }
        PDDocument.load(output).use { exported ->
            assertTrue(PDFTextStripper().getText(exported).contains("Release101 text survives"))
        }
        val (sourceText, boxes, orderedText) = PdfPageSource(context, output).use { source ->
            val text = source.text(0)
            val boxes = source.textCharBoxes(0)
            val codePoints = text.codePoints().toArray()
            codePoints.forEachIndexed { index, codePoint ->
                val box = boxes[index]
                Log.i(
                    "PdfGeometryCapture",
                    "$index\t${codePoint.toString(16).uppercase()}\t${box.left}\t${box.top}\t${box.right}\t${box.bottom}",
                )
            }
            Triple(text, boxes, PdfGeometryText.order(text, boxes).text)
        }
        assertEquals("Release101 text survives", sourceText)
        assertEquals(sourceText, orderedText)
        assertEquals(sourceText.codePointCount(0, sourceText.length), boxes.size)
    }

    @Test
    fun textMarkupAnnotationsExportPrintableAppearanceStreams() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val output = File(context.cacheDir, "text-mark-appearance.pdf")
        PDDocument().use { document ->
            val pdfPage = PDPage(PDRectangle(300f, 300f))
            document.addPage(pdfPage)
            val annotation = PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT).apply {
                quadPoints = floatArrayOf(24f, 250f, 200f, 250f, 24f, 230f, 200f, 230f)
                rectangle = PDRectangle(24f, 230f, 176f, 20f)
                color = PDColor(floatArrayOf(1f, 0.8f, 0.1f), PDDeviceRGB.INSTANCE)
                contents = "Search"
                setPrinted(true)
                page = pdfPage
                constructAppearances(document)
            }
            pdfPage.annotations.add(annotation)
            document.save(output)
        }
        PDDocument.load(output).use { exported ->
            val annotation = exported.getPage(0).annotations.single()
            assertTrue(annotation.isPrinted())
            assertNotNull(annotation.normalAppearanceStream)
        }
    }

    @Test
    fun exportedHighlightRendersTintedPixelsThroughPdfium() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val output = File(context.cacheDir, "text-mark-pdfium-render.pdf")
        PDDocument().use { document ->
            val pdfPage = PDPage(PDRectangle(300f, 300f))
            document.addPage(pdfPage)
            val annotation = PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT).apply {
                quadPoints = floatArrayOf(24f, 250f, 200f, 250f, 24f, 230f, 200f, 230f)
                rectangle = PDRectangle(24f, 230f, 176f, 20f)
                color = PDColor(floatArrayOf(1f, 0.8f, 0.1f), PDDeviceRGB.INSTANCE)
                contents = "Search"
                setPrinted(true)
                page = pdfPage
                constructAppearances(document)
            }
            pdfPage.annotations.add(annotation)
            document.save(output)
        }

        val bitmap = PdfPageSource(context, output).use { it.render(0, 600) }
        val pixel = bitmap.getPixel(200, 120)
        assertTrue(
            "Expected the reopened highlight to tint its interior, got #${pixel.toUInt().toString(16)}",
            AndroidColor.red(pixel) > AndroidColor.blue(pixel) + 20 &&
                AndroidColor.green(pixel) > AndroidColor.blue(pixel) + 20,
        )
    }

    @Test
    fun exporterKeepsHighlightOnPreviouslyImportedArabicPdf() {
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            PDFBoxResourceLoader.init(context)
            val databaseName = "legacy-arabic-export-test.db"
            context.deleteDatabase(databaseName)
            val source = File(context.cacheDir, "arabic-legacy-import.pdf")
            instrumentation.context.assets.open("arabic-legacy.pdf").use { input ->
                source.outputStream().use(input::copyTo)
            }
            val repo = NotePadRepository(context, databaseName)
            try {
                val pageSizes = PdfPageSource(context, source).use { it.pageSizesPt() }
                val (pageWidth, pageHeight) = pageSizes.first()
                PDDocument.load(source).use { imported ->
                    val page = imported.getPage(0)
                    val right = (pageWidth - 30f).coerceAtMost(300f)
                    val top = pageHeight - 100f
                    val annotation = PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT).apply {
                        quadPoints = floatArrayOf(30f, top, right, top, 30f, top - 12f, right, top - 12f)
                        rectangle = PDRectangle(30f, top - 12f, right - 30f, 12f)
                        color = PDColor(floatArrayOf(0.15f, 0.35f, 1f), PDDeviceRGB.INSTANCE)
                        contents = "Existing PDF highlight"
                        setPrinted(true)
                        this.page = page
                        constructAppearances(imported)
                    }
                    page.annotations.add(annotation)
                    imported.save(source)
                }
                val asset = Asset(
                    id = UUID.randomUUID().toString(),
                    kind = "pdf",
                    localPath = source.absolutePath,
                    byteSize = source.length(),
                    sha256 = "legacy-arabic-fixture",
                    pageCount = pageSizes.size,
                    name = "arabic.pdf",
                )
                val notebookId = repo.createPdfNotebook("Legacy Arabic", asset, pageSizes, null)
                val page = repo.pages(notebookId).first().first()
                val right = (page.widthPt - 30f).coerceAtMost(300f)
                val top = page.heightPt - 50f
                repo.savePdfMark(
                    PdfMark(
                        id = UUID.randomUUID().toString(),
                        notebookId = notebookId,
                        pageId = page.id,
                        kind = PdfMarkKind.HIGHLIGHT,
                        colorArgb = 0x80FFEB3B.toInt(),
                        quads = listOf(30f, top, right, top, 30f, top - 20f, right, top - 20f),
                        charStart = 0,
                        charEnd = 5,
                        text = "العربية",
                        note = null,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                val exportedFile = PdfExporter(context, repo).exportNotebook(notebookId, "legacy-arabic-export")
                PDDocument.load(exportedFile).use { exported ->
                    val annotations = exported.getPage(0).annotations
                    assertEquals(2, annotations.size)
                    assertTrue(annotations.any { it.contents == "Existing PDF highlight" })
                    assertTrue(annotations.any { it.contents == "العربية" })
                    annotations.forEach { annotation ->
                        assertTrue(annotation.isPrinted())
                        assertNotNull(annotation.normalAppearanceStream)
                    }
                    Log.i(
                        "PdfAnnotsDump",
                        "page=0 /Annots count=${annotations.size} ${annotations.joinToString { "${it.subtype}:${it.contents}:printed=${it.isPrinted()}:appearance=${it.normalAppearanceStream != null}" }}",
                    )
                }
                val sourceBitmap = PdfPageSource(context, source).use { it.render(0, 600) }
                val exportedBitmap = PdfPageSource(context, exportedFile).use { it.render(0, 600) }
                var tintedPixels = 0
                for (y in 0 until minOf(sourceBitmap.height, exportedBitmap.height)) {
                    for (x in 0 until minOf(sourceBitmap.width, exportedBitmap.width)) {
                        val before = sourceBitmap.getPixel(x, y)
                        val after = exportedBitmap.getPixel(x, y)
                        if (before != after &&
                            AndroidColor.red(after) > AndroidColor.blue(after) + 20 &&
                            AndroidColor.green(after) > AndroidColor.blue(after) + 20
                        ) {
                            tintedPixels++
                        }
                    }
                }
                assertTrue("Expected PDFium to render the exported Arabic highlight", tintedPixels > 0)
                val invalidRangeExport = runCatching {
                    PdfExporter(context, repo).exportNotebook(
                        notebookId,
                        "legacy-arabic-fallback-guard",
                        PdfExportOptions(pageRange = 1..1),
                    )
                }
                assertTrue("Marked exports must not use the lossy fallback", invalidRangeExport.isFailure)
                assertFalse(File(context.cacheDir, "exports/legacy-arabic-fallback-guard.pdf").exists())
            } finally {
                repo.helper.close()
                context.deleteDatabase(databaseName)
                source.delete()
                File(context.cacheDir, "exports/legacy-arabic-export.pdf").delete()
                File(context.cacheDir, "exports/legacy-arabic-fallback-guard.pdf").delete()
            }
        }
    }
}
