package com.hashim.notepad.ink

import android.graphics.Canvas
import android.graphics.Paint
import com.hashim.notepad.data.PageKind

internal object PageTemplateRenderer {
    private const val GAP = 24f

    fun draw(canvas: Canvas, paint: Paint, width: Float, height: Float, kind: PageKind) {
        when (kind) {
            PageKind.LINED -> {
                var y = GAP * 3
                while (y < height - GAP) {
                    canvas.drawLine(GAP * 2, y, width - GAP * 2, y, paint)
                    y += GAP
                }
            }
            PageKind.GRID -> {
                var y = GAP
                while (y < height) {
                    canvas.drawLine(0f, y, width, y, paint)
                    y += GAP
                }
                var x = GAP
                while (x < width) {
                    canvas.drawLine(x, 0f, x, height, paint)
                    x += GAP
                }
            }
            PageKind.DOTS -> {
                val dotPaint = Paint(paint).apply { style = Paint.Style.FILL; strokeWidth = 0f }
                var y = GAP
                while (y < height) {
                    var x = GAP
                    while (x < width) {
                        canvas.drawCircle(x, y, 1.1f, dotPaint)
                        x += GAP
                    }
                    y += GAP
                }
            }
            PageKind.CORNELL -> {
                val cueX = width * 0.30f
                val bodyTop = GAP * 3
                val summaryTop = height * 0.80f
                var y = bodyTop
                while (y < summaryTop) {
                    canvas.drawLine(GAP * 2, y, width - GAP * 2, y, paint)
                    y += GAP
                }
                canvas.drawLine(cueX, bodyTop, cueX, summaryTop, Paint(paint).apply { strokeWidth = 1.25f })
                canvas.drawLine(GAP * 2, summaryTop, width - GAP * 2, summaryTop, Paint(paint).apply { strokeWidth = 1.25f })
            }
            PageKind.MUSIC -> {
                val staffSpacing = 8f
                val staffGap = GAP * 3
                var top = GAP * 2
                while (top + staffSpacing * 4 < height - GAP) {
                    repeat(5) { line ->
                        val y = top + line * staffSpacing
                        canvas.drawLine(GAP * 2, y, width - GAP * 2, y, paint)
                    }
                    top += staffSpacing * 4 + staffGap
                }
            }
            PageKind.BLANK, PageKind.PDF -> Unit
        }
    }
}
