package com.hashim.notepad.ui

import com.hashim.notepad.data.Notebook
import org.junit.Assert.assertEquals
import org.junit.Test

class StudyHubHelpersTest {
    private fun notebook(id: String, title: String, added: Long, updated: Long, opened: Long?) = Notebook(
        id = id,
        title = title,
        coverColor = null,
        language = "en",
        sortOrder = 0,
        createdAt = added,
        updatedAt = updated,
        pageCount = 10,
        lastOpenedAt = opened,
    )

    @Test fun sortModesUseTheirExpectedKeys() {
        val items = listOf(
            notebook("a", "Zebra", 1, 50, 4),
            notebook("b", "apple", 3, 5, 90),
            notebook("c", "Mango", 2, 20, null),
        )
        assertEquals(listOf("b", "c", "a"), sortNotebooks(items, LibrarySort.RECENT).map { it.id })
        assertEquals(listOf("b", "c", "a"), sortNotebooks(items, LibrarySort.NAME).map { it.id })
        assertEquals(listOf("b", "c", "a"), sortNotebooks(items, LibrarySort.DATE_ADDED).map { it.id })
    }

    @Test fun fileBadgePrefersKnownExtensionAndFallsBackToMime() {
        assertEquals("DOCX", fileTypeBadge("application/octet-stream", "lecture.docx"))
        assertEquals("PDF", fileTypeBadge("application/pdf", "download"))
        assertEquals("IMAGE", fileTypeBadge("image/jpeg", "download"))
    }

    @Test fun progressLabelsClampValues() {
        assertEquals("p. 34 of 210", progressLabel(33, 210))
        assertEquals("p. 1 of 1", progressLabel(-3, 0))
        assertEquals("Importing Book.pdf · 45%", importProgressLabel("Book.pdf", 45))
    }
}
