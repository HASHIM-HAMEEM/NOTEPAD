package com.hashim.notepad.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes

enum class InkTool { PEN, HIGHLIGHTER, ERASER }

data class BrushSpec(val tool: InkTool, val colorArgb: Int, val size: Float) {
    fun toBrush(): Brush = when (tool) {
        InkTool.HIGHLIGHTER -> Brush.createWithColorIntArgb(
            StockBrushes.highlighter(), (colorArgb and 0x00FFFFFF) or (0x66 shl 24), size * 4f, EPSILON
        )
        else -> Brush.createWithColorIntArgb(StockBrushes.pressurePen(), colorArgb, size, EPSILON)
    }

    companion object { const val EPSILON = 0.1f }
}
