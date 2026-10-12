package com.hashim.notepad.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.StockBrushes

enum class InkTool { PEN, HIGHLIGHTER, ERASER, TEXT, LASSO }

enum class PenType(val label: String) {
    BALLPOINT("Ballpoint"),
    FOUNTAIN("Fountain"),
    PENCIL("Pencil"),
    MARKER("Marker"),
    CALLIGRAPHY("Calligraphy"),
}

data class BrushSpec(
    val tool: InkTool,
    val colorArgb: Int,
    val size: Float,
    val pen: PenType = PenType.FOUNTAIN,
) {
    @OptIn(ExperimentalInkCustomBrushApi::class)
    fun toBrush(): Brush = when (tool) {
        InkTool.HIGHLIGHTER -> Brush.createWithColorIntArgb(
            StockBrushes.highlighter(), colorArgb.withAlpha(0x66), size * 4f, EPSILON
        )
        InkTool.PEN -> when (pen) {
            PenType.BALLPOINT -> Brush.createWithColorIntArgb(
                StockBrushes.marker(), colorArgb, size * 0.8f, EPSILON
            )
            PenType.FOUNTAIN -> Brush.createWithColorIntArgb(
                StockBrushes.pressurePen(), colorArgb, size, EPSILON
            )
            PenType.PENCIL -> Brush.createWithColorIntArgb(
                StockBrushes.pencilUnstable, colorArgb, size * 1.2f, EPSILON
            )
            PenType.MARKER -> Brush.createWithColorIntArgb(
                StockBrushes.marker(), colorArgb.withAlpha(0xE6), size * 2.2f, EPSILON
            )
            PenType.CALLIGRAPHY -> Brush.createWithColorIntArgb(
                InkBrushFamilies.calligraphy, colorArgb, size * 2.5f, EPSILON
            )
        }
        else -> Brush.createWithColorIntArgb(StockBrushes.pressurePen(), colorArgb, size, EPSILON)
    }

    companion object { const val EPSILON = 0.1f }
}

private fun Int.withAlpha(alpha: Int): Int = (this and 0x00FFFFFF) or (alpha shl 24)

@OptIn(ExperimentalInkCustomBrushApi::class)
private object InkBrushFamilies {
    val calligraphy: BrushFamily by lazy {
        val tip = BrushTip.builder()
            .setScaleX(1f)
            .setScaleY(0.25f)
            .setCornerRounding(0.3f)
            .setRotationDegrees(45f)
            .build()
        BrushFamily(tip, BrushPaint())
    }
}
