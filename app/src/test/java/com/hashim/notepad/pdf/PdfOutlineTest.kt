package com.hashim.notepad.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PdfOutlineTest {
    private val outline = listOf(
        PdfOutlineItem("Chapter 1", 0, listOf(PdfOutlineItem("Section 1.1", 2), PdfOutlineItem("Section 1.2", 6))),
        PdfOutlineItem("Chapter 2", 10, listOf(PdfOutlineItem("Section 2.1", 12))),
        PdfOutlineItem("Chapter 3", 20),
    )

    @Test fun flattenIncludesOnlyExpandedBranches() {
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), flattenOutline(outline).map { it.item.title })
        assertEquals(
            listOf("Chapter 1", "Section 1.1", "Section 1.2", "Chapter 2", "Chapter 3"),
            flattenOutline(outline, setOf(listOf(0))).map { it.item.title },
        )
    }

    @Test fun findsDeepestOutlineItemForPage() {
        assertEquals("Section 1.2", chapterForPage(outline, 7)?.title)
        assertEquals("Chapter 2", chapterForPage(outline, 11)?.title)
        assertEquals("Chapter 3", chapterForPage(outline, 25)?.title)
        assertNull(chapterForPage(outline, -1))
    }
}
