package com.hashim.notepad.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class PdfPageSourceTest {
    @Test
    fun renderSizePreservesPageAspectWithinBitmapBudget() {
        val (width, height) = PdfPageSource.pdfBitmapSize(8192, 595, 842)

        assertTrue(width.toLong() * height <= 16_777_216L)
        assertTrue(kotlin.math.abs(width.toFloat() / height - 595f / 842f) < 0.001f)
    }

    @Test
    fun renderSizeKeepsNormalRequestsAtRequestedWidth() {
        assertEquals(1200 to 1698, PdfPageSource.pdfBitmapSize(1200, 595, 842))
    }

    @Test
    fun pdfiumDispatcherUsesOneDedicatedThread() = runBlocking {
        val first = withContext(PdfiumDispatcher.dispatcher) { Thread.currentThread().id }
        val second = withContext(PdfiumDispatcher.dispatcher) { Thread.currentThread().id }

        assertEquals(first, second)
    }
}
