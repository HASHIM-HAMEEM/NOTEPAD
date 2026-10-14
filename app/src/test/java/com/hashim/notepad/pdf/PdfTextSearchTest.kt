package com.hashim.notepad.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

class PdfTextSearchTest {
    @Test fun returnsOriginalCharacterRangeAndShortSnippet() {
        val text = "Intro ".repeat(10) + "اَلْعَرَبِي " + "end ".repeat(10)
        val hit = findPdfTextHits("page", 4, text, "العربي").single()
        assertEquals(text.indexOf("اَلْعَرَبِي"), hit.matchStart)
        assertEquals(text.indexOf("اَلْعَرَبِي") + "اَلْعَرَبِي".length, hit.matchEnd)
        assertEquals(text.substring(hit.matchStart, hit.matchEnd), hit.snippet.substring(hit.snippetMatchStart, hit.snippetMatchEnd))
        assertEquals("page", hit.pageId)
    }

    @Test fun matchesPdfiumTextWithDetachedTashkeelFromCapturedCodepoints() {
        val extracted = "\u064Eع\u064Eر\u0650ب\u064E\u0651ي\u064Cة"
        val hit = findPdfTextHits("page", 0, extracted, "عربية").single()
        assertEquals(1, hit.matchStart)
        assertEquals(extracted.length, hit.matchEnd)
    }

    @Test fun geometryOrdersCapturedArabicFragmentsAndMatchesBothFormsAcrossWholeWord() {
        val extracted = "اختبار ترتيب العربية في ملف PDF\r\n" +
            "\u064Cة\r\n" +
            "\u064Eغ\u064Cة \u064Eع\u064Eر\u0650ب\u064E\u0651ي\u064Cة \u064Eج\u0650م\u064A\u064Eل\r\n" +
            "\u064Fل"
        val boxes = extracted.indices.map { index ->
            val line = when (index) {
                in 0..32 -> 0f
                in 33..36 -> 40f
                in 37..41, in 63..64 -> 60f
                in 42..60 -> 100f
                else -> 140f
            }
            val x = when (index) {
                37, 38 -> 20f
                39, 40 -> 10f
                41 -> 0f
                63, 64 -> 30f
                else -> (index % 8) * 12f
            }
            PdfTextRect(x, line, x + 8f, line + 10f)
        }
        val layer = PdfGeometryText.order(extracted, boxes)
        val first = findPdfTextHits("arabic", 0, layer, "لغه").single()
        val second = findPdfTextHits("arabic", 0, layer, "لغة").single()

        assertEquals(first.matchStart, second.matchStart)
        assertEquals(first.matchEnd, second.matchEnd)
        assertEquals("لُغَةٌ", layer.text.substring(first.matchStart, first.matchEnd))
        assertEquals(listOf(37, 38, 39, 40, 63, 64), first.sourceIndices.sorted())
        assertEquals(1, first.rects.size)
        assertEquals(PdfTextRect(10f, 60f, 38f, 70f), first.rects.single())
        val selection = PdfWordSnap.wordRange(layer.text, first.matchStart, Locale("ar"))!!
        val copied = Normalizer.normalize(
            layer.text.substring(selection.start, selection.end),
            Normalizer.Form.NFC,
        )
        assertEquals("لُغَةٌ", copied)
    }

    @Test fun rtlSnippetsPreserveWordAndGraphemeBoundaries() {
        val text = "مقدمة ".repeat(10) + "لُغَةٌ" + " خاتمة".repeat(10)
        val hit = findPdfTextHits("page", 0, text, "لغة").single()
        val snippetStart = hit.matchStart - hit.snippetMatchStart
        val snippetEnd = snippetStart + hit.snippet.length
        val words = BreakIterator.getWordInstance(Locale.ROOT).apply { setText(text) }
        val graphemes = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }

        assertTrue(words.isBoundary(snippetStart))
        assertTrue(words.isBoundary(snippetEnd))
        assertTrue(graphemes.isBoundary(snippetStart))
        assertTrue(graphemes.isBoundary(snippetEnd))
        assertEquals(text.substring(hit.matchStart, hit.matchEnd), hit.snippet.substring(hit.snippetMatchStart, hit.snippetMatchEnd))
    }

    @Test fun duplicateNormalizedMatchesAreDeduplicatedBySourceRange() {
        val hit = findPdfTextHits("page", 0, "\u2161", "i")

        assertEquals(1, hit.size)
        assertEquals(0, hit.single().matchStart)
        assertEquals(1, hit.single().matchEnd)
    }

    @Test fun textAndMultipleMarksHaveDistinctNamespacedResultKeys() {
        val textHit = findPdfTextHits("page", 0, "hello", "hello").single()
        val markHits = listOf(textHit.copy(resultKey = PdfSearchResultKey.mark("mark-one")),
            textHit.copy(resultKey = PdfSearchResultKey.mark("mark-two")))

        assertEquals("pdf-text-hit:page:0:5", textHit.resultKey)
        assertEquals("pdf-mark-hit:mark-one", markHits.first().resultKey)
        assertEquals(3, (listOf(textHit) + markHits).map { it.resultKey }.distinct().size)
    }
}
