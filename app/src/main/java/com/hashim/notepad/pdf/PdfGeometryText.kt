package com.hashim.notepad.pdf

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class GeometryTextChar(
    val text: String,
    val start: Int,
    val end: Int,
    val box: PdfTextRect,
    val sourceIndices: List<Int>,
    val lineIndex: Int,
)

data class PdfGeometryTextLayer(
    val text: String,
    val characters: List<GeometryTextChar>,
) {
    fun sourceIndices(start: Int, endExclusive: Int): List<Int> =
        characters.asSequence()
            .filter { it.start < endExclusive && it.end > start }
            .flatMap { it.sourceIndices.asSequence() }
            .distinct()
            .toList()

    fun sourceIndexAt(textOffset: Int): Int? =
        characters.firstOrNull { textOffset in it.start until it.end }?.sourceIndices?.firstOrNull()

    fun nearestCharacterOffset(
        x: Float,
        y: Float,
        toleranceX: Float,
        toleranceY: Float,
    ): Int? = characters.indices
        .asSequence()
        .filter { index ->
            val box = characters[index].box
            box.right > box.left && box.bottom > box.top &&
                x >= box.left - toleranceX && x <= box.right + toleranceX &&
                y >= box.top - toleranceY && y <= box.bottom + toleranceY
        }
        .minByOrNull { index ->
            val box = characters[index].box
            val dx = (box.left + box.right) * 0.5f - x
            val dy = (box.top + box.bottom) * 0.5f - y
            dx * dx + dy * dy
        }
        ?.let { characters[it].start }

    fun rectsForRange(start: Int, endExclusive: Int): List<PdfTextRect> =
        characters.asSequence()
            .filter { it.start < endExclusive && it.end > start }
            .filter { it.box.right > it.box.left && it.box.bottom > it.box.top }
            .groupBy { it.lineIndex }
            .toSortedMap()
            .values
            .mapNotNull { line ->
                line.drop(1).fold(line.first().box) { result, character -> result.union(character.box) }
            }
}

object PdfGeometryText {
    private data class RawChar(
        val text: String,
        val sourceIndex: Int,
        val box: PdfTextRect,
    )

    private data class Glyph(
        val text: String,
        val sourceIndices: List<Int>,
        val box: PdfTextRect,
        val direction: Direction,
    )

    private data class Line(val chars: MutableList<RawChar> = mutableListOf()) {
        val top get() = chars.minOfOrNull { it.box.top } ?: Float.MAX_VALUE
        val bottom get() = chars.maxOfOrNull { it.box.bottom } ?: Float.MAX_VALUE
        val center get() = (top + bottom) * 0.5f
    }

    private enum class Direction { LTR, RTL, NEUTRAL }

    fun order(source: String, boxes: List<PdfTextRect>): PdfGeometryTextLayer {
        val rawChars = ArrayList<RawChar>()
        var sourceIndex = 0
        var utf16Offset = 0
        while (utf16Offset < source.length) {
            val codePoint = source.codePointAt(utf16Offset)
            val length = Character.charCount(codePoint)
            val box = boxes.getOrNull(sourceIndex) ?: EMPTY_RECT
            rawChars += RawChar(source.substring(utf16Offset, utf16Offset + length), sourceIndex, box)
            utf16Offset += length
            sourceIndex++
        }

        val positioned = rawChars.filter { it.box.isValid() && it.text != "\r" && it.text != "\n" }
        if (positioned.isEmpty()) return sourceOrderFallback(rawChars)

        val lines = mutableListOf<Line>()
        positioned.sortedWith(compareBy<RawChar> { it.box.centerY() }.thenBy { it.box.left }).forEach { char ->
            val candidate = lines
                .map { line -> line to verticalOverlap(char.box, line) }
                .filter { it.second >= LINE_OVERLAP_THRESHOLD }
                .maxByOrNull { it.second }
                ?.first
            (candidate ?: Line().also(lines::add)).chars += char
        }
        rawChars.filterNot { it.box.isValid() || it.text == "\r" || it.text == "\n" }.forEach { char ->
            val nearest = lines.minByOrNull { line ->
                abs(line.chars.map { it.sourceIndex }.average() - char.sourceIndex)
            }
            if (nearest != null && !char.text.all(Char::isWhitespace)) nearest.chars += char
        }

        val orderedLines = lines.sortedWith(compareBy<Line> { it.top }.thenBy { it.chars.minOf { c -> c.sourceIndex } })
        val output = StringBuilder()
        val characters = mutableListOf<GeometryTextChar>()
        orderedLines.forEachIndexed { lineIndex, line ->
            if (lineIndex > 0) output.append('\n')
            logicalLine(line.chars).forEach { glyph ->
                val start = output.length
                output.append(glyph.text)
                characters += GeometryTextChar(
                    text = glyph.text,
                    start = start,
                    end = output.length,
                    box = glyph.box,
                    sourceIndices = glyph.sourceIndices,
                    lineIndex = lineIndex,
                )
            }
        }
        return PdfGeometryTextLayer(output.toString(), characters)
    }

    private fun logicalLine(chars: List<RawChar>): List<Glyph> {
        val bases = chars.filterNot { isCombiningMark(it.text.codePointAt(0)) }.map { char ->
            Glyph(char.text, listOf(char.sourceIndex), char.box, direction(char.text))
        }.toMutableList()
        val marks = chars.filter { isCombiningMark(it.text.codePointAt(0)) }
        marks.forEach { mark ->
            val baseIndex = bases.indices.maxByOrNull { index -> horizontalOverlap(mark.box, bases[index].box) }
            if (baseIndex == null || horizontalOverlap(mark.box, bases[baseIndex].box) <= 0f) {
                bases += Glyph(mark.text, listOf(mark.sourceIndex), mark.box, direction(mark.text))
            } else {
                val base = bases[baseIndex]
                bases[baseIndex] = base.copy(
                    text = base.text + mark.text,
                    sourceIndices = base.sourceIndices + mark.sourceIndex,
                    box = base.box.union(mark.box),
                )
            }
        }

        val visual = bases.sortedWith(compareBy<Glyph> { it.box.left }.thenBy { it.box.right }.thenBy { it.sourceIndices.first() })
        val rtlCount = visual.count { it.direction == Direction.RTL }
        val ltrCount = visual.count { it.direction == Direction.LTR }
        val baseDirection = when {
            rtlCount > ltrCount -> Direction.RTL
            ltrCount > rtlCount -> Direction.LTR
            else -> visual.firstOrNull { it.direction != Direction.NEUTRAL }?.direction ?: Direction.LTR
        }
        val runs = mutableListOf<Pair<Direction, MutableList<Glyph>>>()
        visual.forEach { glyph ->
            val direction = glyph.direction.takeUnless { it == Direction.NEUTRAL }
                ?: runs.lastOrNull()?.first ?: baseDirection
            if (runs.lastOrNull()?.first != direction) runs += direction to mutableListOf()
            runs.last().second += glyph
        }
        val logicalRuns = if (baseDirection == Direction.RTL) runs.asReversed() else runs
        return logicalRuns.flatMap { (direction, run) ->
            if (direction == Direction.RTL) run.asReversed() else run
        }
    }

    private fun sourceOrderFallback(chars: List<RawChar>): PdfGeometryTextLayer {
        val text = StringBuilder()
        val output = mutableListOf<GeometryTextChar>()
        chars.forEach { char ->
            val start = text.length
            text.append(char.text)
            output += GeometryTextChar(char.text, start, text.length, char.box, listOf(char.sourceIndex), 0)
        }
        return PdfGeometryTextLayer(text.toString(), output)
    }

    private fun verticalOverlap(box: PdfTextRect, line: Line): Float {
        val intersection = max(0f, min(box.bottom, line.bottom) - max(box.top, line.top))
        val shortest = min(box.bottom - box.top, line.bottom - line.top).coerceAtLeast(0.001f)
        val centerDistance = abs(box.centerY() - line.center)
        val height = max(box.bottom - box.top, line.bottom - line.top).coerceAtLeast(0.001f)
        return max(intersection / shortest, if (centerDistance <= height * 0.38f) 0.36f else 0f)
    }

    private fun horizontalOverlap(first: PdfTextRect, second: PdfTextRect): Float =
        max(0f, min(first.right, second.right) - max(first.left, second.left))

    private fun direction(text: String): Direction = when (Character.getDirectionality(text.codePointAt(0))) {
        Character.DIRECTIONALITY_RIGHT_TO_LEFT,
        Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
        Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
        Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE -> Direction.RTL
        Character.DIRECTIONALITY_LEFT_TO_RIGHT,
        Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING,
        Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE -> Direction.LTR
        else -> Direction.NEUTRAL
    }

    private fun isCombiningMark(codePoint: Int): Boolean =
        Character.getType(codePoint) in setOf(
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
        )

    private fun PdfTextRect.isValid() = right > left && bottom > top
    private fun PdfTextRect.centerY() = (top + bottom) * 0.5f
    private fun PdfTextRect.union(other: PdfTextRect) = PdfTextRect(
        min(left, other.left),
        min(top, other.top),
        max(right, other.right),
        max(bottom, other.bottom),
    )

    private const val LINE_OVERLAP_THRESHOLD = 0.35f
    private val EMPTY_RECT = PdfTextRect(0f, 0f, 0f, 0f)
}

private fun PdfTextRect.union(other: PdfTextRect) = PdfTextRect(
    min(left, other.left),
    min(top, other.top),
    max(right, other.right),
    max(bottom, other.bottom),
)

private fun PdfTextRect.centerY() = (top + bottom) * 0.5f
