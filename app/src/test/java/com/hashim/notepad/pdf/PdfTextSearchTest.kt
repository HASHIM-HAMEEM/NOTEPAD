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
}
