package com.hashim.notepad.pdf

import com.hashim.notepad.data.OcrWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrGeometryTest {
    @Test
    fun detectsPagesWithFewerThanTwentyExtractableCharacters() {
        assertTrue(OcrGeometry.isScannedPage("1234567890123456789"))
        assertFalse(OcrGeometry.isScannedPage("12345678901234567890"))
        assertTrue(OcrGeometry.isScannedPage("  \n\t"))
    }

    @Test
    fun mapsBitmapWordBoxesIntoPdfPointCoordinates() {
        assertEquals(
            PdfTextRect(100f, 50f, 200f, 100f),
            OcrGeometry.bitmapRectToPdf(200, 100, 400, 200, 1000, 2000, 500f, 1000f),
        )
    }

    @Test
    fun ocrTextLayerSupportsMultiWordSearchAndCharacterGeometry() {
        val layer = OcrGeometry.toTextLayer(
            listOf(
                OcrWord("page", 1, "survives", 50f, 20f, 110f, 34f, 92f),
                OcrWord("page", 0, "text", 10f, 20f, 42f, 34f, 95f),
            ),
        )
        assertEquals("text survives", layer.text)
        assertEquals(1, findPdfTextHits("page", 0, layer, "text survives").size)
        assertEquals(2, layer.rectsForRange(0, layer.text.length).size)
        assertTrue(layer.characters.all { it.box.right > it.box.left })
    }
}
