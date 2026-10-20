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

    @Test fun rotatesEveryQuadVertexAndKeepsItsEightPointShape() {
        val input = listOf(10f, 20f, 30f, 20f, 10f, 40f, 30f, 40f)
        val rotated = PdfMarkGeometry.rotatedQuads(input, 100f, 200f, 90)
        assertEquals(8, rotated.size)
        assertEquals(listOf(180f, 10f, 180f, 30f, 160f, 10f, 160f, 30f), rotated)
    }
}
