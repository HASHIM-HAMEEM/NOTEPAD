package com.hashim.notepad.pdf

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class PdfWordSnapTest {
    @Test fun snapsWordsInLatinAndArabicScripts() {
        assertEquals(TextSelectionRange(0, 5), PdfWordSnap.wordRange("hello world", 2, Locale.ENGLISH))
        assertEquals(TextSelectionRange(0, 7), PdfWordSnap.wordRange("میخواهم کتاب", 2, Locale("fa")))
        assertEquals(TextSelectionRange(0, 4), PdfWordSnap.wordRange("کتاب اردو", 2, Locale("ur")))
        assertEquals(TextSelectionRange(0, 4), PdfWordSnap.wordRange("عربی متن", 2, Locale("ar")))
    }

    @Test fun mapsCharacterHitToWordAndPdfQuadGeometry() {
        val boxes = listOf(
            PdfTextRect(10f, 20f, 18f, 32f),
            PdfTextRect(19f, 20f, 27f, 32f),
        )
        assertEquals(1, PdfWordSnap.nearestCharacterIndex(boxes, 26f, 27f, 2f, 4f))
        assertEquals(null, PdfWordSnap.nearestCharacterIndex(boxes, 40f, 40f, 2f, 4f))
        val rect = listOf(PdfTextRect(10f, 20f, 27f, 32f))
        assertEquals(
            listOf(10f, 80f, 27f, 80f, 10f, 68f, 27f, 68f),
            PdfWordSnap.quadPoints(rect, 100f),
        )
        assertEquals(rect, PdfWordSnap.topLeftRects(PdfWordSnap.quadPoints(rect, 100f), 100f))
    }

    @Test fun snapsEveryHighlighterSampleAndReturnsTheWholeWordRange() {
        val text = "hello"
        val boxes = text.indices.map { index ->
            PdfTextRect(index * 10f, 0f, (index + 1) * 10f, 10f)
        }
        val layer = PdfGeometryText.order(text, boxes)

        assertEquals(
            TextSelectionRange(0, text.length),
            PdfWordSnap.wordRangeForPoints(
                layer,
                listOf(PdfUserPoint(2f, 5f), PdfUserPoint(42f, 5f)),
                Locale.ENGLISH,
                2f,
                2f,
            ),
        )
    }
}
