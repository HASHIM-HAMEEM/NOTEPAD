package com.hashim.notepad.export

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfBoxExportContentTest {
    @Test
    fun translucentHighlighterUsesMultiplyBlendMode() {
        val document = PDDocument()
        val page = PDPage(PDRectangle(300f, 300f))
        document.addPage(page)
        PDPageContentStream(document, page).use { stream ->
            applyStrokeGraphicsState(stream, alpha = 0.42f, multiply = true)
            stream.setStrokingColor(0.95f, 0.78f, 0.1f)
            stream.setLineWidth(12f)
            stream.moveTo(24f, 260f)
            stream.lineTo(240f, 260f)
            stream.stroke()
        }
        val output = ByteArrayOutputStream()
        document.save(output)
        document.close()

        PDDocument.load(ByteArrayInputStream(output.toByteArray())).use { exported ->
            val states = exported.getPage(0).resources.extGStateNames
                .mapNotNull { exported.getPage(0).resources.getExtGState(it) }
            val translucentMultiply = states.firstOrNull {
                (it.nonStrokingAlphaConstant ?: 1f) < 1f
            }
            assertNotNull(translucentMultiply)
            assertTrue(translucentMultiply!!.nonStrokingAlphaConstant!! < 1f)
            assertEquals(BlendMode.MULTIPLY, translucentMultiply.blendMode)
        }
    }
}
