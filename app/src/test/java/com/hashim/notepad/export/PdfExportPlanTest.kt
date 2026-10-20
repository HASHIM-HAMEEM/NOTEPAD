package com.hashim.notepad.export

import com.hashim.notepad.data.Page
import com.hashim.notepad.data.PageKind
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfExportPlanTest {
    @Test
    fun pageRangePreservesDocumentOrderAndRotation() {
        val pages = listOf(page("first", 0), page("second", 90), page("third", 270))

        val plan = planPdfExportPages(pages, 1..2)

        assertEquals(listOf(1, 2), plan.map { it.sourceIndex })
        assertEquals(listOf("second", "third"), plan.map { it.page.id })
        assertEquals(listOf(90, 270), plan.map { it.page.rotation })
    }

    private fun page(id: String, rotation: Int) = Page(
        id = id,
        notebookId = "book",
        sortKey = 0.0,
        kind = PageKind.PDF,
        assetId = "asset",
        pdfPageIndex = 0,
        widthPt = 600f,
        heightPt = 800f,
        createdAt = 1,
        updatedAt = 1,
        rotation = rotation,
    )
}
