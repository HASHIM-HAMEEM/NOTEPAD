package com.hashim.notepad.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfTextSearchTest {
    @Test fun returnsOriginalCharacterRangeAndShortSnippet() {
        val text = "Intro ".repeat(10) + "اَلْعَرَبِي " + "end ".repeat(10)
        val hit = findPdfTextHits("page", 4, text, "العربي").single()
        assertEquals(text.indexOf("اَلْعَرَبِي"), hit.matchStart)
        assertEquals(text.indexOf("اَلْعَرَبِي") + "اَلْعَرَبِي".length, hit.matchEnd)
        assertEquals(text.substring(hit.matchStart, hit.matchEnd), hit.snippet.substring(hit.snippetMatchStart, hit.snippetMatchEnd))
        assertEquals("page", hit.pageId)
    }

    @Test fun findsArabicQueryWhenPdfiumReturnsVisualOrder() {
        val hit = findPdfTextHits("page", 0, "هغل", "لغه").single()
        assertEquals(0, hit.matchStart)
        assertEquals(3, hit.matchEnd)
    }

    @Test fun matchesPdfiumTextWithDetachedTashkeelFromCapturedCodepoints() {
        val extracted = "\u064Eع\u064Eر\u0650ب\u064E\u0651ي\u064Cة"
        val hit = findPdfTextHits("page", 0, extracted, "عربية").single()
        assertEquals(1, hit.matchStart)
        assertEquals(extracted.length, hit.matchEnd)
    }

    @Test fun findsTashkeelQueryInCapturedPdfiumVisualOrder() {
        val extracted = "اختبار ترتيب العربية في ملف PDF\r\n" +
            "\u064Cة\r\n" +
            "\u064Eغ\u064Cة \u064Eع\u064Eر\u0650ب\u064E\u0651ي\u064Cة \u064Eج\u0650م\u064A\u064Eل\r\n" +
            "\u064Fل"

        val hit = findPdfTextHits("arabic", 0, extracted, "لغه").first {
            it.sourceIndices == listOf(34, 38, 60)
        }

        assertEquals(listOf(34, 38, 60), hit.sourceIndices)
        assertEquals('ة', extracted[hit.sourceIndices[0]])
        assertEquals('غ', extracted[hit.sourceIndices[1]])
        assertEquals('ل', extracted[hit.sourceIndices[2]])
    }
}
