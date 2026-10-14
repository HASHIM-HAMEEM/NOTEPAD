package com.hashim.notepad.export

import android.graphics.Color as AndroidColor
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import org.junit.Assert.assertNotNull
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
                    val annotation = exported.getPage(0).annotations.single()
                    assertTrue(annotation.isPrinted())
                    assertNotNull(annotation.normalAppearanceStream)
                }
            } finally {
                repo.helper.close()
                context.deleteDatabase(databaseName)
                source.delete()
                File(context.cacheDir, "exports/legacy-arabic-export.pdf").delete()
            }
        }
    }
}
