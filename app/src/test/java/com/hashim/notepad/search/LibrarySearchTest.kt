package com.hashim.notepad.search

import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySearchTest {
    private val documents = listOf(
        LibrarySearchDocument("nested", "nested-outline", "nested-outline.pdf"),
        LibrarySearchDocument("arabic", "لغة عربية", "arabic.pdf"),
    )

    @Test
    fun matchesDecryptedNotebookTitleAndPartialTitle() {
        assertEquals(
            listOf("title", "title"),
            LibrarySearch.findMatches("nested-outline", documents).map { it.source } +
                LibrarySearch.findMatches("nested", documents).map { it.source },
        )
    }

    @Test
    fun matchesOriginalFilenameAndNormalizedArabicTitle() {
        assertEquals("filename", LibrarySearch.findMatches("outline.pdf", documents).single().source)
        assertEquals("title", LibrarySearch.findMatches("لغة", documents).single().source)
        assertEquals("title", LibrarySearch.findMatches("لغه", documents).single().source)
    }
}
