package com.hashim.notepad.ink

import org.junit.Assert.assertEquals
import org.junit.Test

class TextColorPolicyTest {
    @Test
    fun mostCommonInkColorIgnoresHighlighterAndTapeSamples() {
        val samples = listOf(
            StrokeColorSample(0xFFFF0000.toInt(), countsAsInk = true),
            StrokeColorSample(0xFF0000FF.toInt(), countsAsInk = true),
            StrokeColorSample(0xFF0000FF.toInt(), countsAsInk = true),
            StrokeColorSample(0xFF00FF00.toInt(), countsAsInk = false),
            StrokeColorSample(0xFF00FF00.toInt(), countsAsInk = false),
            StrokeColorSample(0xFF00FF00.toInt(), countsAsInk = false),
        )

        assertEquals(0xFF0000FF.toInt(), TextColorPolicy.mostCommonInkColor(samples))
    }

    @Test
    fun lowContrastInkFallsBackToPaperAwareDefault() {
        val darkPaper = 0xFF1F1F22.toInt()
        val samples = listOf(StrokeColorSample(0xFF000000.toInt(), countsAsInk = true))

        assertEquals(0xFFFFFFFF.toInt(), TextColorPolicy.textColor(samples, darkPaper))
    }

    @Test
    fun contrastingInkColorIsPreservedAndEmptySelectionUsesPaperDefault() {
        val lightPaper = 0xFFFFFFFF.toInt()
        val ink = 0xFFB00000.toInt()

        assertEquals(ink, TextColorPolicy.textColor(listOf(StrokeColorSample(ink, true)), lightPaper))
        assertEquals(0xFF000000.toInt(), TextColorPolicy.textColor(emptyList(), lightPaper))
    }
}
