package com.hashim.notepad.pdf

import com.hashim.notepad.data.PdfMarkKind
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfMarkGeometryTest {
    @Test fun rotatesQuarterTurnsInPdfUserSpace() {
        assertEquals(110f to 10f, PdfMarkGeometry.rotatePoint(10f, 90f, 100f, 200f, 90))
        assertEquals(90f to 190f, PdfMarkGeometry.rotatePoint(10f, 10f, 100f, 200f, 180))
    }

    @Test fun parsesQuadBoundsAndSubtype() {
        assertEquals(PdfQuad(1f, 2f, 11f, 12f), PdfMarkGeometry.quads(listOf(1f, 2f, 11f, 2f, 11f, 12f, 1f, 12f)).single())
        assertEquals("StrikeOut", PdfMarkGeometry.toMarkupSubtype(PdfMarkKind.STRIKE))
    }
}
