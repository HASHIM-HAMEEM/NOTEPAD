package com.hashim.notepad.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.StockBrushes
import kotlin.math.roundToInt

enum class InkTool { PEN, HIGHLIGHTER, TAPE, ERASER, TEXT, LASSO, LASER }

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
    val opacity: Float = if (tool == InkTool.HIGHLIGHTER) 0.45f else 1f,
) {
    @OptIn(ExperimentalInkCustomBrushApi::class)
    fun toBrush(): Brush {
        val safeSize = HighlighterInputPolicy.brushSize(size)
        val safeOpacity = HighlighterInputPolicy.opacity(opacity)
        val safeColor = colorArgb.withAlpha((safeOpacity * 255f).roundToInt())
        return when (tool) {
        InkTool.TAPE -> Brush.createWithColorIntArgb(InkBrushFamilies.tape, colorArgb or (0xFF shl 24), safeSize, EPSILON)
        InkTool.HIGHLIGHTER -> Brush.createWithColorIntArgb(
            StockBrushes.highlighter(), safeColor, safeSize * 4f, EPSILON
        )
        InkTool.PEN -> when (pen) {
            PenType.BALLPOINT -> Brush.createWithColorIntArgb(
                StockBrushes.marker(), safeColor, safeSize * 0.8f, EPSILON
            )
            PenType.FOUNTAIN -> Brush.createWithColorIntArgb(
                StockBrushes.pressurePen(), safeColor, safeSize, EPSILON
            )
            PenType.PENCIL -> Brush.createWithColorIntArgb(
                InkBrushFamilies.pencil, safeColor, safeSize * 1.2f, EPSILON
            )
            PenType.MARKER -> Brush.createWithColorIntArgb(
                StockBrushes.marker(), safeColor, safeSize * 2.2f, EPSILON
            )
            PenType.CALLIGRAPHY -> Brush.createWithColorIntArgb(
                InkBrushFamilies.calligraphy, safeColor, safeSize * 2.5f, EPSILON
            )
        }
        else -> Brush.createWithColorIntArgb(StockBrushes.pressurePen(), colorArgb, safeSize, EPSILON)
        }
    }

    companion object { const val EPSILON = 0.1f }
}

private fun Int.withAlpha(alpha: Int): Int = (this and 0x00FFFFFF) or (alpha shl 24)

@OptIn(ExperimentalInkCustomBrushApi::class)
internal object InkBrushFamilies {
    val pencil: BrushFamily by lazy {
        val tip = BrushTip.builder()
            .setScaleX(1f)
            .setScaleY(1f)
            .setCornerRounding(0.7f)
            .build()
        val texture = BrushPaint.TilingTexture.builder()
            .setClientTextureId(PENCIL_TEXTURE_ID)
            .setSizeX(0.7f)
            .setSizeY(0.7f)
            .setSizeUnit(BrushPaint.TextureLayer.SizeUnit.BRUSH_SIZE)
            .setWrapX(BrushPaint.TextureLayer.Wrap.REPEAT)
            .setWrapY(BrushPaint.TextureLayer.Wrap.REPEAT)
            .build()
        BrushFamily(tip, BrushPaint(listOf(texture), emptyList(), SelfOverlap.ACCUMULATE))
    }

    val calligraphy: BrushFamily by lazy {
        val tip = BrushTip.builder()
            .setScaleX(1f)
            .setScaleY(0.25f)
            .setCornerRounding(0.3f)
            .setRotationDegrees(45f)
            .build()
        BrushFamily(tip, BrushPaint())
    }
    val tape: BrushFamily by lazy {
        val tip = BrushTip.builder()
            .setScaleX(1f)
            .setScaleY(1f)
            .setCornerRounding(0f)
            .build()
        BrushFamily(tip, BrushPaint())
    }
}
