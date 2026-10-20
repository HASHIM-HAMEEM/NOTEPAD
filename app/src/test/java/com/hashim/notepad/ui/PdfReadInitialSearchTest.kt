package com.hashim.notepad.ui

import com.hashim.notepad.pdf.PdfTextSearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PdfReadInitialSearchTest {
    @Test
    fun choosesTheFirstSearchHitOnTheTargetPage() {
        val hits = listOf(
            hit(pageId = "other-page", matchStart = 0),
            hit(pageId = "target-page", matchStart = 10),
            hit(pageId = "target-page", matchStart = 20),
        )

        assertEquals(
            10,
            firstPdfTextSearchHitOnPage(hits, "target-page")?.matchStart,
        )
    }

    @Test
    fun returnsNoHitWhenTheTargetPageDoesNotMatch() {
        assertNull(firstPdfTextSearchHitOnPage(listOf(hit("other-page", 0)), "target-page"))
    }

    private fun hit(pageId: String, matchStart: Int) = PdfTextSearchHit(
        pageId = pageId,
        pageIndex = 0,
        snippet = "match",
        matchStart = matchStart,
        matchEnd = matchStart + 5,
        snippetMatchStart = 0,
        snippetMatchEnd = 5,
    )
}
