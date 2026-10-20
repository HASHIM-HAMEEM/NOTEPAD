package com.hashim.notepad.ink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class HighlighterStrokeLifecycleTest {
    @Test
    fun ignoresDuplicateBeginAndInputAfterFinish() {
        val lifecycle = HighlighterStrokeLifecycle()

        assertTrue(lifecycle.begin())
        assertFalse(lifecycle.begin())
        assertTrue(lifecycle.acceptsInput())
        assertTrue(lifecycle.finish())
        assertFalse(lifecycle.acceptsInput())
        assertFalse(lifecycle.finish())
    }

    @Test
    fun cancelDisablesPendingInputs() {
        val lifecycle = HighlighterStrokeLifecycle()

        assertTrue(lifecycle.begin())
        lifecycle.cancel()

        assertFalse(lifecycle.acceptsInput())
        assertFalse(lifecycle.finish())
    }

    @Test
    fun clampsInvalidHighlighterBrushParameters() {
        assertEquals(1f, HighlighterInputPolicy.brushSize(Float.NaN), 0f)
        assertEquals(1f, HighlighterInputPolicy.brushSize(Float.POSITIVE_INFINITY), 0f)
        assertEquals(1f, HighlighterInputPolicy.brushSize(0f), 0f)
        assertEquals(10f, HighlighterInputPolicy.brushSize(100f), 0f)
        assertEquals(0.1f, HighlighterInputPolicy.opacity(0f), 0f)
        assertEquals(0.1f, HighlighterInputPolicy.opacity(Float.NaN), 0f)
    }

    @Test
    fun sanitizesNonFiniteInputAxesAndRejectsDegenerateStrokes() {
        assertEquals(1f, HighlighterInputPolicy.pressure(Float.NaN), 0f)
        assertEquals(0f, HighlighterInputPolicy.tilt(Float.NaN), 0f)
        assertEquals(0f, HighlighterInputPolicy.orientation(Float.POSITIVE_INFINITY), 0f)
        assertFalse(HighlighterInputPolicy.hasLength(1f, 2f, 1f, 2f))
        assertFalse(HighlighterInputPolicy.hasLength(Float.NaN, 2f, 3f, 4f))
        assertTrue(HighlighterInputPolicy.hasLength(1f, 2f, 2f, 2f))
    }

    @Test
    fun mapsMotionEventOrientationIntoInkRange() {
        val twoPi = (2 * PI).toFloat()
        // Sweep the full MotionEvent range, including the negative half a real stylus produces.
        var angle = -PI.toFloat()
        while (angle <= PI.toFloat()) {
            val mapped = HighlighterInputPolicy.orientation(angle)
            assertTrue("orientation($angle) = $mapped", mapped >= 0f && mapped < twoPi)
            angle += 0.05f
        }
        assertEquals((PI / 2).toFloat(), HighlighterInputPolicy.orientation(0f), 1e-5f)
        assertEquals((1.5 * PI).toFloat(), HighlighterInputPolicy.orientation(-PI.toFloat()), 1e-4f)
        assertEquals(0f, HighlighterInputPolicy.orientation(Float.NaN), 0f)
        assertEquals(0f, HighlighterInputPolicy.orientation(Float.NEGATIVE_INFINITY), 0f)
    }

    @Test
    fun clampsTiltAndPressureIntoInkRange() {
        assertEquals((PI / 2).toFloat(), HighlighterInputPolicy.tilt(2f), 0f)
        assertEquals(0f, HighlighterInputPolicy.tilt(-0.3f), 0f)
        assertEquals(1f, HighlighterInputPolicy.pressure(1.7f), 0f)
        assertEquals(1f, HighlighterInputPolicy.pressure(0f), 0f)
        assertEquals(0.4f, HighlighterInputPolicy.pressure(0.4f), 0f)
    }
}
