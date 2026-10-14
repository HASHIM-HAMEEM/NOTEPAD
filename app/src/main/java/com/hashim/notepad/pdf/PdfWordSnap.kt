package com.hashim.notepad.pdf

import java.text.BreakIterator
import java.util.Locale

data class TextSelectionRange(val start: Int, val end: Int)

object PdfWordSnap {
    fun nearestCharacterIndex(
        boxes: List<PdfTextRect>,
        x: Float,
        y: Float,
        toleranceX: Float,
        toleranceY: Float,
    ): Int? = boxes.indices
        .asSequence()
        .filter { index ->
            val box = boxes[index]
            box.right >= box.left && box.bottom >= box.top &&
                x >= box.left - toleranceX && x <= box.right + toleranceX &&
                y >= box.top - toleranceY && y <= box.bottom + toleranceY
        }
        .minByOrNull { index ->
            val box = boxes[index]
            val dx = box.centerX() - x
            val dy = box.centerY() - y
            dx * dx + dy * dy
        }

    fun wordRange(text: String, characterIndex: Int, locale: Locale = Locale.ROOT): TextSelectionRange? {
        if (text.isEmpty() || characterIndex !in text.indices) return null
        val iterator = BreakIterator.getWordInstance(locale)
        iterator.setText(text)
        var start = iterator.preceding(characterIndex + 1)
        if (start == BreakIterator.DONE) start = 0
        var end = iterator.following(characterIndex)
        if (end == BreakIterator.DONE) end = text.length
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        return if (start < end) TextSelectionRange(start, end) else null
    }

    fun quadPoints(rects: List<PdfTextRect>, pageHeight: Float): List<Float> = rects.flatMap { rect ->
        listOf(
            rect.left, pageHeight - rect.top,
            rect.right, pageHeight - rect.top,
            rect.left, pageHeight - rect.bottom,
            rect.right, pageHeight - rect.bottom,
        )
    }

    fun topLeftRects(quads: List<Float>, pageHeight: Float): List<PdfTextRect> =
        PdfMarkGeometry.quads(quads).map { quad ->
            PdfTextRect(quad.left, pageHeight - quad.bottom, quad.right, pageHeight - quad.top)
        }

    private fun PdfTextRect.centerX() = (left + right) * 0.5f
    private fun PdfTextRect.centerY() = (top + bottom) * 0.5f
}
