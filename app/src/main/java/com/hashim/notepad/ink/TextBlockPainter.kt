package com.hashim.notepad.ink

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.content.Context
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import com.hashim.notepad.data.TextBlock
import com.hashim.notepad.text.ScriptTypefaceResolver

/** Draws typed text blocks in page-point space. */
object TextBlockPainter {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val selection = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x331E5EFF; style = Paint.Style.FILL }

    fun layout(block: TextBlock, paperArgb: Int = Color.WHITE, context: Context? = null): StaticLayout {
        paint.color = block.colorArgb ?: TextColorPolicy.paperAwareDefault(paperArgb)
        paint.textSize = block.fontSizePt
        paint.typeface = context?.let { ScriptTypefaceResolver.forText(it, block.content) } ?: Typeface.DEFAULT
        return StaticLayout.Builder.obtain(block.content, 0, block.content.length, paint, block.wPt.toInt().coerceAtLeast(40))
            .setTextDirection(
                when (block.direction) {
                    "rtl" -> TextDirectionHeuristics.RTL
                    "ltr" -> TextDirectionHeuristics.LTR
                    else -> TextDirectionHeuristics.FIRSTSTRONG_LTR
                },
            )
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.35f)
            .build()
    }

    fun draw(canvas: Canvas, block: TextBlock, selected: Boolean, paperArgb: Int = Color.WHITE, context: Context? = null) {
        val l = layout(block, paperArgb, context)
        canvas.save()
        canvas.rotate(block.rotationDegrees, block.xPt, block.yPt + block.fontSizePt)
        canvas.translate(block.xPt, block.yPt)
        if (selected) canvas.drawRect(-4f, -4f, block.wPt + 4f, l.height + 4f, selection)
        l.draw(canvas); canvas.restore()
    }

    fun hit(block: TextBlock, xPt: Float, yPt: Float, context: Context? = null): Boolean {
        val h = layout(block, context = context).height
        val point = floatArrayOf(xPt, yPt)
        android.graphics.Matrix().apply {
            setRotate(-block.rotationDegrees, block.xPt, block.yPt + block.fontSizePt)
            mapPoints(point)
        }
        return point[0] >= block.xPt - 4 && point[0] <= block.xPt + block.wPt + 4 &&
            point[1] >= block.yPt - 4 && point[1] <= block.yPt + h + 4
    }
}
