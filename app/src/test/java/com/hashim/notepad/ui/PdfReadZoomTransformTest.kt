package com.hashim.notepad.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfReadZoomTransformTest {
    @Test
    fun keepsThePinchFocusOnTheSamePagePoint() {
        val transform = zoomPdfAtFocus(
            currentZoom = 1f,
            currentPanX = 0f,
            currentPanY = 0f,
            previousFocusX = 180f,
            previousFocusY = 260f,
            currentFocusX = 190f,
            currentFocusY = 250f,
            requestedZoom = 2f,
        )

        assertEquals(190f, transform.zoom * 180f + transform.panX, 0.001f)
        assertEquals(250f, transform.zoom * 260f + transform.panY, 0.001f)
    }

    @Test
    fun returnsToDefaultPanWhenZoomResets() {
        assertEquals(
            PdfReadZoomTransform(1f, 0f, 0f),
            zoomPdfAtFocus(2f, -100f, -120f, 80f, 90f, 80f, 90f, 1f),
        )
    }

    @Test
    fun rendersHigherResolutionPreviewsUpToThePixelBudgetCap() {
        assertEquals(2400, pdfPreviewRenderWidth(1200, 1f, 2f))
        assertEquals(MAX_PDF_PREVIEW_WIDTH_PX, pdfPreviewRenderWidth(1800, 1f, 4f))
    }
}
