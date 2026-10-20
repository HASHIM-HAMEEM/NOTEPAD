package com.hashim.notepad.recognition

import org.junit.Assert.assertEquals
import org.junit.Test

class TextBlockGeometryTest {
    @Test
    fun mapsSelectionBoundsToTextGeometry() {
        val placement = TextBlockGeometry.fromBounds(12f, 18f, 92f, 58f)
        assertEquals(12f, placement.xPt)
        assertEquals(18f, placement.yPt)
        assertEquals(80f, placement.widthPt)
        assertEquals(26f, placement.fontSizePt)
    }

    @Test
    fun clampsNarrowAndShortSelectionsToUsableTextDimensions() {
        val placement = TextBlockGeometry.fromBounds(5f, 7f, 5f, 7f)
        assertEquals(1f, placement.widthPt)
        assertEquals(10f, placement.fontSizePt)
    }
}
