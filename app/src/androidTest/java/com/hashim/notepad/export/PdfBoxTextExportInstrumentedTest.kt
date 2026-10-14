package com.hashim.notepad.export

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
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
}
