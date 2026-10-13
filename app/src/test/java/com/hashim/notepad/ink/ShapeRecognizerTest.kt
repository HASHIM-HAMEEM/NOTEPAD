package com.hashim.notepad.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class ShapeRecognizerTest {
    @Test
    fun recognizesAndSnapsLine() {
        val shape = recognize(listOf(0f to 0f, 40f to 2f, 90f to -1f, 150f to 0f))

        assertTrue(shape is Shape.Line)
        val line = shape as Shape.Line
        assertEquals(line.y1, line.y2, 0.001f)
    }

    @Test
    fun recognizesWobblyLine() {
        val shape = recognize(listOf(0f to 0f, 30f to 2f, 60f to -3f, 90f to 2f, 120f to 0f))

        assertTrue(shape is Shape.Line)
    }

    @Test
    fun recognizesAndSnapsRectangle() {
        val shape = recognize(samplePolygon(listOf(0f to 0f, 100f to 2f, 98f to 60f, 2f to 58f)))

        assertTrue(shape is Shape.Polygon)
        val polygon = shape as Shape.Polygon
        assertEquals(4, polygon.points.size)
        assertEquals(setOf(0f, 100f), polygon.points.map { it.x }.toSet())
        assertEquals(setOf(0f, 60f), polygon.points.map { it.y }.toSet())
    }

    @Test
    fun recognizesRotatedQuadrilateralWithoutRectangleSnap() {
        val angle = Math.toRadians(28.0)
        val rotated = listOf(
            -50f to -25f,
            50f to -25f,
            50f to 25f,
            -50f to 25f,
        ).map { (x, y) ->
            (x * cos(angle) - y * sin(angle)).toFloat() to
                (x * sin(angle) + y * cos(angle)).toFloat()
        }
        val shape = recognize(samplePolygon(rotated))

        assertTrue(shape is Shape.Polygon)
        val polygon = shape as Shape.Polygon
        assertEquals(4, polygon.points.size)
        assertTrue(polygon.points.any { it.x != polygon.points.first().x && it.x != polygon.points.last().x })
    }

    @Test
    fun recognizesTriangle() {
        val shape = recognize(samplePolygon(listOf(0f to 0f, 100f to 0f, 48f to 90f)))

        assertTrue(shape is Shape.Polygon)
        assertEquals(3, (shape as Shape.Polygon).points.size)
    }

    @Test
    fun recognizesCircleAndSnapsItToEqualRadii() {
        val shape = recognize(sampleCurve { angle ->
            (80f + 50f * cos(angle).toFloat()) to (80f + 50f * sin(angle).toFloat())
        })

        assertTrue(shape is Shape.Ellipse)
        val ellipse = shape as Shape.Ellipse
        assertEquals(ellipse.rx, ellipse.ry, 0.001f)
    }

    @Test
    fun recognizesEllipse() {
        val shape = recognize(sampleCurve { angle ->
            (90f + 80f * cos(angle).toFloat()) to (60f + 45f * sin(angle).toFloat())
        })

        assertTrue(shape is Shape.Ellipse)
        val ellipse = shape as Shape.Ellipse
        assertTrue(ellipse.rx > ellipse.ry * 1.1f)
    }

    @Test
    fun openUScribbleIsNotClosedShape() {
        val shape = recognize(listOf(0f to 0f, 0f to 50f, 4f to 100f, 50f to 102f, 96f to 100f, 100f to 50f, 100f to 0f))

        assertNull(shape)
    }

    @Test
    fun zigzagIsNotRecognizedAsLineOrShape() {
        val shape = recognize(listOf(0f to 0f, 30f to 50f, 60f to 0f, 90f to 50f, 120f to 0f))

        assertNull(shape)
    }

    @Test
    fun removesRepeatedTrailingStationarySamples() {
        val shape = recognize(listOf(0f to 4f, 40f to 5f, 80f to 4f, 100f to 4f, 100.1f to 4.1f, 100f to 4f))

        assertNotNull(shape)
        assertTrue(shape is Shape.Line)
    }

    private fun recognize(points: List<Pair<Float, Float>>): Shape? =
        ShapeRecognizer.recognize(points.map { it.first }.toFloatArray(), points.map { it.second }.toFloatArray())

    private fun samplePolygon(vertices: List<Pair<Float, Float>>, samplesPerEdge: Int = 20): List<Pair<Float, Float>> =
        vertices.indices.flatMap { index ->
            val start = vertices[index]
            val end = vertices[(index + 1) % vertices.size]
            (0 until samplesPerEdge).map { sample ->
                val fraction = sample.toFloat() / samplesPerEdge
                (start.first + (end.first - start.first) * fraction) to
                    (start.second + (end.second - start.second) * fraction)
            }
        } + listOf(vertices.first())

    private fun sampleCurve(pointAt: (Double) -> Pair<Float, Float>): List<Pair<Float, Float>> =
        (0..100).map { index -> pointAt(2.0 * PI * index / 100.0) }
}
