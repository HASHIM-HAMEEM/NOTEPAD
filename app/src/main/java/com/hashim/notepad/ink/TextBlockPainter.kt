package com.hashim.notepad.ink

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import com.hashim.notepad.data.TextBlock

/** Draws typed text blocks in page-point space. Direction is per paragraph (first-strong), so Arabic/Urdu stay RTL inside an English UI. */
object TextBlockPainter {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 12f }
    private val selection = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x331E5EFF; style = Paint.Style.FILL }

    fun layout(block: TextBlock): StaticLayout =
        StaticLayout.Builder.obtain(block.content, 0, block.content.length, paint, block.wPt.toInt().coerceAtLeast(40))
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.35f)
            .build()

    fun draw(canvas: Canvas, block: TextBlock, selected: Boolean) {
        val l = layout(block)
        canvas.save(); canvas.translate(block.xPt, block.yPt)
        if (selected) canvas.drawRect(-4f, -4f, block.wPt + 4f, l.height + 4f, selection)
        l.draw(canvas); canvas.restore()
    }

    fun hit(block: TextBlock, xPt: Float, yPt: Float): Boolean {
        val h = layout(block).height
        return xPt >= block.xPt - 4 && xPt <= block.xPt + block.wPt + 4 && yPt >= block.yPt - 4 && yPt <= block.yPt + h + 4
    }
}
