package com.hashim.notepad.pdf

import com.hashim.notepad.data.OcrWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

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

    @Test
    fun ocrWordSearchFoldsArabicAndMatchesInsideAndAcrossWords() {
        val words = listOf(
            OcrWord("page", 0, "لُغَةٌ", 10f, 20f, 50f, 34f, 98f),
            OcrWord("page", 1, "عَرَبِيَّةٌ", 55f, 20f, 120f, 34f, 98f),
        )
        val layer = OcrGeometry.toTextLayer(words)
        val language = findOcrWordHits("page", 0, words, "لغة").single()
        val arabic = findOcrWordHits("page", 0, words, "عربية").single()
        val adjacentWords = findOcrWordHits("page", 0, words, "لغة عربية").single()

        assertEquals("لُغَةٌ", layer.text.substring(language.matchStart, language.matchEnd))
        assertEquals("عَرَبِيَّةٌ", layer.text.substring(arabic.matchStart, arabic.matchEnd))
        assertEquals("لُغَةٌ عَرَبِيَّةٌ", layer.text.substring(adjacentWords.matchStart, adjacentWords.matchEnd))
        assertEquals(1, findOcrWordHits("page", 0, words, "لغـه").size)
        assertEquals(1, findOcrWordHits("page", 0, words, "عَرَبِیَّة").size)

        val characterIndex = requireNotNull(PdfWordSnap.nearestCharacterIndex(layer, 30f, 27f, 0f, 0f))
        val selectedRange = requireNotNull(
            PdfWordSnap.wordRange(layer.text, characterIndex, Locale.forLanguageTag("ar")),
        )
        assertEquals("لُغَةٌ", layer.text.substring(selectedRange.start, selectedRange.end))
    }

    @Test
    fun ocrSearchIgnoresInternalBidiControlAndMapsTheHitToOriginalText() {
        val source = "لـ\u200Eغة"
        val words = listOf(OcrWord("page", 0, source, 10f, 20f, 50f, 34f, 98f))

        val hit = findOcrWordHits("page", 0, words, "لغة").single()

        assertEquals(0, hit.matchStart)
        assertEquals(source.length, hit.matchEnd)
        assertEquals(source, hit.snippet.substring(hit.snippetMatchStart, hit.snippetMatchEnd))
    }
}
