package com.hashim.notepad.ink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
