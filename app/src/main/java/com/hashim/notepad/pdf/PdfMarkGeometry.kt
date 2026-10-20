package com.hashim.notepad.pdf

import androidx.core.graphics.ColorUtils
import com.hashim.notepad.data.PdfMark
import com.hashim.notepad.data.PdfMarkKind
import kotlin.math.max
import kotlin.math.min

data class PdfQuad(val left: Float, val top: Float, val right: Float, val bottom: Float)

object PdfMarkGeometry {
    fun displayHighlightColor(colorArgb: Int, darkPaper: Boolean): Int {
        if (!darkPaper) return colorArgb
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(colorArgb, hsl)
        hsl[1] = max(hsl[1], 0.72f)
        hsl[2] = max(hsl[2], 0.62f).coerceAtMost(0.78f)
        val rgb = ColorUtils.HSLToColor(hsl)
        val alpha = android.graphics.Color.alpha(colorArgb).coerceIn(89, 115)
        return ColorUtils.setAlphaComponent(rgb, alpha)
    }

    fun quads(values: List<Float>): List<PdfQuad> =
        values.chunked(8).mapNotNull { q ->
            if (q.size < 8) return@mapNotNull null
            PdfQuad(
                min(min(q[0], q[2]), min(q[4], q[6])),
                min(min(q[1], q[3]), min(q[5], q[7])),
                max(max(q[0], q[2]), max(q[4], q[6])),
                max(max(q[1], q[3]), max(q[5], q[7])),
            )
        }

    fun rotatePoint(x: Float, y: Float, width: Float, height: Float, rotation: Int): Pair<Float, Float> =
        when (((rotation % 360) + 360) % 360) {
            90 -> height - y to x
            180 -> width - x to height - y
            270 -> y to width - x
            else -> x to y
        }

    fun rotatedQuads(values: List<Float>, width: Float, height: Float, rotation: Int): List<Float> =
        values.chunked(8).flatMap { q ->
            if (q.size < 8) emptyList() else q.chunked(2).map { rotatePoint(it[0], it[1], width, height, rotation) }
                .flatMap { listOf(it.first, it.second) }
        }

    fun toMarkupSubtype(kind: PdfMarkKind): String = when (kind) {
        PdfMarkKind.HIGHLIGHT -> "Highlight"
        PdfMarkKind.UNDERLINE -> "Underline"
        PdfMarkKind.STRIKE -> "StrikeOut"
        PdfMarkKind.SQUIGGLY -> "Squiggly"
    }

    fun markdown(marks: List<PdfMark>): String = buildString {
        marks.groupBy { it.pageId }.forEach { (page, pageMarks) ->
            append("## Page ").append(page).append('\n').append('\n')
            pageMarks.forEach { mark ->
                append("- **").append(mark.kind.db).append("** ").append(mark.text)
                mark.note?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
                append('\n')
            }
            append('\n')
        }
    }
}
