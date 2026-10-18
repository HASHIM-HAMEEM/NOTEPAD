package com.hashim.notepad.ink

import org.junit.Assert.assertFalse
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
}
