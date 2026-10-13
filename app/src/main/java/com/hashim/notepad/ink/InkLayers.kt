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
    revealedTapeIndices: Set<Int> = emptySet(),
    canvasHasPageTransform: Boolean = false,
) {
    val paperDark = Color.luminance(paperArgb) < 0.5f
    val highlighter = StockBrushes.highlighter()
    for ((index, stroke) in strokes.withIndex()) {
        when {
            stroke.brush.family == highlighter -> {
                val layer = canvas.saveLayer(null, Paint().apply {
                    blendMode = if (paperDark) BlendMode.SCREEN else BlendMode.MULTIPLY
                })
                renderer.draw(canvas, opaqueHighlight(stroke, paperDark), toCanvas)
                canvas.restoreToCount(layer)
            }
            stroke.brush.family == InkBrushFamilies.tape ->
                drawTape(canvas, stroke, toCanvas, index in revealedTapeIndices, canvasHasPageTransform)
            else -> renderer.draw(canvas, stroke, toCanvas)
        }
    }
    if (wet != null) {
        if (wet.brush?.family == highlighter) {
            val layer = canvas.saveLayer(null, Paint().apply {
                blendMode = if (paperDark) BlendMode.SCREEN else BlendMode.MULTIPLY
            })
            renderer.draw(canvas, wet, toCanvas)
            canvas.restoreToCount(layer)
        } else {
            renderer.draw(canvas, wet, toCanvas)
        }
    }
}

private fun drawTape(
    canvas: Canvas,
    stroke: Stroke,
    toCanvas: Matrix,
    revealed: Boolean,
    canvasHasPageTransform: Boolean,
) {
    if (stroke.inputs.size < 2) return
    val first = stroke.inputs[0]
    val last = stroke.inputs[stroke.inputs.size - 1]
    val dx = last.x - first.x
    val dy = last.y - first.y
    val length = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
    val angle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
    canvas.save()
    if (!canvasHasPageTransform) canvas.concat(toCanvas)
    canvas.rotate(angle, first.x, first.y)
    val width = stroke.brush.size.coerceAtLeast(1f)
    val alpha = if (revealed) 0.12f else 1f
    val band = android.graphics.RectF(first.x, first.y - width / 2f, first.x + length, first.y + width / 2f)
    val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = stroke.brush.colorIntArgb
        this.alpha = (android.graphics.Color.alpha(color) * alpha).toInt()
    }
    canvas.drawRect(band, base)
    if (!revealed) {
        val stripe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            this.alpha = 54
            strokeWidth = width * 0.12f
        }
        val save = canvas.save()
        canvas.clipRect(band)
        var x = first.x - width
        while (x < first.x + length) {
            canvas.drawLine(x, band.bottom, x + width, band.top, stripe)
            x += width * 0.55f
        }
        canvas.restoreToCount(save)
    } else {
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = stroke.brush.colorIntArgb
            this.alpha = (android.graphics.Color.alpha(color) * 0.8f).toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.25f
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(width * 0.3f, width * 0.22f), 0f)
        }
        canvas.drawRect(band, outline)
    }
    canvas.restore()
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
