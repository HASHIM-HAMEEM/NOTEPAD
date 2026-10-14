package com.hashim.notepad.pdf

import com.hashim.notepad.data.OcrWord
import kotlin.math.max
import kotlin.math.min

data class OcrBox(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float,
)

object OcrGeometry {
    fun isScannedPage(text: String): Boolean = text.count { !it.isWhitespace() } < 20

    fun bitmapRectToPdf(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        bitmapWidth: Int,
        bitmapHeight: Int,
        pageWidthPt: Float,
        pageHeightPt: Float,
    ): PdfTextRect {
        val scaleX = pageWidthPt / bitmapWidth.coerceAtLeast(1)
        val scaleY = pageHeightPt / bitmapHeight.coerceAtLeast(1)
        val pdfLeft = min(left * scaleX, right * scaleX).coerceIn(0f, pageWidthPt)
        val pdfRight = max(left * scaleX, right * scaleX).coerceIn(0f, pageWidthPt)
        val pdfTop = min(top * scaleY, bottom * scaleY).coerceIn(0f, pageHeightPt)
        val pdfBottom = max(top * scaleY, bottom * scaleY).coerceIn(0f, pageHeightPt)
        return PdfTextRect(pdfLeft, pdfTop, pdfRight, pdfBottom)
    }

    fun toTextLayer(words: List<OcrWord>): PdfGeometryTextLayer {
        val text = StringBuilder()
        val characters = mutableListOf<GeometryTextChar>()
        words.sortedBy(OcrWord::index).forEachIndexed { wordOrder, word ->
            if (word.text.isBlank()) return@forEachIndexed
            if (text.isNotEmpty()) text.append(' ')
            val start = text.length
            text.append(word.text)
            val box = PdfTextRect(word.left, word.top, word.right, word.bottom)
            var offset = start
            while (offset < text.length) {
                val codePoint = text.codePointAt(offset)
                val length = Character.charCount(codePoint)
                characters += GeometryTextChar(
                    text.substring(offset, offset + length),
                    offset,
                    offset + length,
                    box,
                    emptyList(),
                    wordOrder,
                )
                offset += length
            }
        }
        return PdfGeometryTextLayer(text.toString(), characters)
    }
}
