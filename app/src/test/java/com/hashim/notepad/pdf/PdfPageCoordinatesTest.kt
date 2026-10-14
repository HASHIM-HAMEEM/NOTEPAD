package com.hashim.notepad.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPageCoordinatesTest {
    @Test
    fun screenMappingInvertsZoomOffsetAndPdfYFlipAtZeroRotation() {
        assertRoundTrip(rotation = 0, pageWidthPx = 1200f, pageHeightPx = 1600f)
    }

    @Test
    fun screenMappingInvertsZoomOffsetAndPdfYFlipAtNinetyDegrees() {
        assertRoundTrip(rotation = 90, pageWidthPx = 1600f, pageHeightPx = 1200f)
    }

    @Test
    fun screenMappingReturnsTopLeftTextCoordinatesAfterZoomOffsetRotationAndYFlip() {
        for (rotation in listOf(0, 90)) {
            val expected = PdfUserPoint(120f, 160f)
            val screen = PdfPageCoordinates.pdfUserToScreen(
                PdfUserPoint(expected.x, 800f - expected.y),
                pageLeft = 45f,
                pageTop = 70f,
                pageWidthPx = if (rotation == 0) 1200f else 1600f,
                pageHeightPx = if (rotation == 0) 1600f else 1200f,
                pageWidthPt = 600f,
                pageHeightPt = 800f,
                rotation = rotation,
                zoom = 1.75f,
                offsetX = 18f,
                offsetY = -12f,
            )
            val mapped = PdfPageCoordinates.screenToPageTopLeft(
                screenX = screen.x,
                screenY = screen.y,
                pageLeft = 45f,
                pageTop = 70f,
                pageWidthPx = if (rotation == 0) 1200f else 1600f,
                pageHeightPx = if (rotation == 0) 1600f else 1200f,
                pageWidthPt = 600f,
                pageHeightPt = 800f,
                rotation = rotation,
                zoom = 1.75f,
                offsetX = 18f,
                offsetY = -12f,
            )
            assertEquals(expected.x, mapped.x, 0.01f)
            assertEquals(expected.y, mapped.y, 0.01f)
        }
    }

    @Test
    fun itemLocalTouchUsesFullPageSizeInsteadOfClippedViewportBounds() {
        val pageWidthPx = 2496f
        val pageHeightPx = pageWidthPx * 792f / 612f
        val mapped = PdfPageCoordinates.screenToPageTopLeft(
            screenX = 183f,
            screenY = 52f,
            pageLeft = 0f,
            pageTop = 0f,
            pageWidthPx = pageWidthPx,
            pageHeightPx = pageHeightPx,
            pageWidthPt = 612f,
            pageHeightPt = 792f,
            rotation = 0,
        )
        val quickCharacter = PdfTextRect(43.416f, 10.764f, 47.916f, 16.608f)
        val clippedViewportMapping = PdfPageCoordinates.screenToPageTopLeft(
            screenX = 183f,
            screenY = 52f,
            pageLeft = 0f,
            pageTop = 0f,
            pageWidthPx = pageWidthPx,
            pageHeightPx = 1432f,
            pageWidthPt = 612f,
            pageHeightPt = 792f,
            rotation = 0,
        )

        assertEquals(44.87019f, mapped.x, 0.01f)
        assertEquals(12.75f, mapped.y, 0.01f)
        assertTrue(mapped.x in quickCharacter.left..quickCharacter.right)
        assertTrue(mapped.y in quickCharacter.top..quickCharacter.bottom)
        assertFalse(clippedViewportMapping.y in quickCharacter.top..quickCharacter.bottom)
        assertEquals(28.75978f, clippedViewportMapping.y, 0.01f)
    }

    private fun assertRoundTrip(rotation: Int, pageWidthPx: Float, pageHeightPx: Float) {
        val original = PdfUserPoint(120f, 640f)
        val screen = PdfPageCoordinates.pdfUserToScreen(
            original,
            pageLeft = 45f,
            pageTop = 70f,
            pageWidthPx = pageWidthPx,
            pageHeightPx = pageHeightPx,
            pageWidthPt = 600f,
            pageHeightPt = 800f,
            rotation = rotation,
            zoom = 1.75f,
            offsetX = 18f,
            offsetY = -12f,
        )
        val mapped = PdfPageCoordinates.screenToPdfUser(
            screenX = screen.x,
            screenY = screen.y,
            pageLeft = 45f,
            pageTop = 70f,
            pageWidthPx = pageWidthPx,
            pageHeightPx = pageHeightPx,
            pageWidthPt = 600f,
            pageHeightPt = 800f,
            rotation = rotation,
            zoom = 1.75f,
            offsetX = 18f,
            offsetY = -12f,
        )
        assertEquals(original.x, mapped.x, 0.01f)
        assertEquals(original.y, mapped.y, 0.01f)
    }
}
