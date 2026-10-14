package com.hashim.notepad.export

import com.hashim.notepad.data.Page

internal data class PdfExportPage(val sourceIndex: Int, val page: Page)

internal fun planPdfExportPages(pages: List<Page>, range: IntRange?): List<PdfExportPage> {
    range?.let {
        require(it.first >= 0 && it.last < pages.size && !it.isEmpty()) { "Invalid PDF page range" }
    }
    return pages.mapIndexedNotNull { index, page ->
        if (range == null || index in range) PdfExportPage(index, page) else null
    }
}
