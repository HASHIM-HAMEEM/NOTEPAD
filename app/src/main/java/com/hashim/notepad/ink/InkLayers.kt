package com.hashim.notepad.ink

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.ink.brush.StockBrushes
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.Stroke
import java.util.WeakHashMap

private val highlightCache = WeakHashMap<Stroke, MutableMap<Boolean, Stroke>>()
private val highlightCacheLock = Any()

fun drawStrokes(
    canvas: Canvas,
    strokes: List<Stroke>,
    renderer: CanvasStrokeRenderer,
    toCanvas: Matrix,
    paperArgb: Int,
    wet: InProgressStroke? = null,
) {
    val paperDark = Color.luminance(paperArgb) < 0.5f
    val highlighter = StockBrushes.highlighter()
    val highlights = strokes.filter { it.brush.family == highlighter }
    val wetHighlighter = wet?.takeIf { it.brush?.family == highlighter }

    if (highlights.isNotEmpty() || wetHighlighter != null) {
        val layerPaint = Paint().apply {
            blendMode = if (paperDark) BlendMode.SCREEN else BlendMode.MULTIPLY
        }
        val layer = canvas.saveLayer(null, layerPaint)
        for (stroke in highlights) {
            renderer.draw(canvas, opaqueHighlight(stroke, paperDark), toCanvas)
        }
        wetHighlighter?.let { renderer.draw(canvas, it, toCanvas) }
        canvas.restoreToCount(layer)
    }

    for (stroke in strokes) {
        if (stroke.brush.family != highlighter) renderer.draw(canvas, stroke, toCanvas)
    }
    if (wet != null && wetHighlighter == null) renderer.draw(canvas, wet, toCanvas)
}

private fun opaqueHighlight(stroke: Stroke, paperDark: Boolean): Stroke =
    synchronized(highlightCacheLock) {
        highlightCache.getOrPut(stroke) { mutableMapOf() }.getOrPut(paperDark) {
            stroke.copy(stroke.brush.copyWithColorIntArgb(opaqueHighlighterColor(stroke.brush.colorIntArgb, paperDark)))
        }
    }

internal fun opaqueHighlighterColor(storedColor: Int, paperDark: Boolean): Int {
    val alpha = Color.alpha(storedColor) / 255f
    val base = if (paperDark) 0 else 255
    val red = lerp(base, Color.red(storedColor), alpha)
    val green = lerp(base, Color.green(storedColor), alpha)
    val blue = lerp(base, Color.blue(storedColor), alpha)
    return Color.rgb(red, green, blue)
}

private fun lerp(from: Int, to: Int, fraction: Float): Int =
    (from + (to - from) * fraction).toInt().coerceIn(0, 255)
