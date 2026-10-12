package com.hashim.notepad.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.StockBrushes
import kotlin.math.roundToInt

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
    val opacity: Float = if (tool == InkTool.HIGHLIGHTER) 0.45f else 230f / 255f,
) {
    @OptIn(ExperimentalInkCustomBrushApi::class)
    fun toBrush(): Brush = when (tool) {
        InkTool.HIGHLIGHTER -> Brush.createWithColorIntArgb(
            StockBrushes.highlighter(), colorArgb.withAlpha((opacity.coerceIn(0f, 1f) * 255f).roundToInt()), size * 4f, EPSILON
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
                StockBrushes.marker(), colorArgb.withAlpha((opacity.coerceIn(0f, 1f) * 255f).roundToInt()), size * 2.2f, EPSILON
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
internal object InkBrushFamilies {
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
