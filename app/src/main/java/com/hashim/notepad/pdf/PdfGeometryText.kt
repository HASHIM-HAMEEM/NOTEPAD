package com.hashim.notepad.pdf

import java.text.Normalizer
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

    private data class Cluster(
        val text: String,
        val sourceIndices: List<Int>,
        val box: PdfTextRect,
        val direction: Direction,
    ) {
        val sourceIndex get() = sourceIndices.minOrNull() ?: Int.MAX_VALUE
        val height get() = (box.bottom - box.top).coerceAtLeast(0f)
        val centerX get() = (box.left + box.right) * 0.5f
        val centerY get() = (box.top + box.bottom) * 0.5f
    }

    private class ClusterLine {
        val clusters = mutableListOf<Cluster>()
        var top = Float.MAX_VALUE
            private set
        var bottom = -Float.MAX_VALUE
            private set
        var left = Float.MAX_VALUE
            private set
        var right = -Float.MAX_VALUE
            private set

        fun add(cluster: Cluster) {
            top = min(top, cluster.box.top)
            bottom = max(bottom, cluster.box.bottom)
            left = min(left, cluster.box.left)
            right = max(right, cluster.box.right)
            clusters += cluster
        }
    }

    private data class RunCluster(val cluster: Cluster, val runIndex: Int, val runDirection: Direction)
    private data class OutputGlyph(val text: String, val sourceIndices: List<Int>, val box: PdfTextRect)

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

        val content = rawChars.filterNot { it.text == "\r" || it.text == "\n" }
        val sourceSpaces = content.filter { it.text.all(Char::isWhitespace) }
        val clusters = attachMarksBySourceRun(content)
        val positioned = clusters.filter { it.box.isPositioned() && it.text.none(Char::isWhitespace) }
        if (positioned.isEmpty()) return sourceOrderFallback(content)
        val lineClusters = groupClustersIntoLines(positioned)
        val orderedLines = lineClusters.sortedBy { it.top }
        val clusterToLine = mutableMapOf<Cluster, Int>()
        orderedLines.forEachIndexed { index, line -> line.clusters.forEach { clusterToLine[it] = index } }
        val sourceToCluster = mutableMapOf<Int, Cluster>()
        clusters.forEach { cluster -> cluster.sourceIndices.forEach { sourceToCluster[it] = cluster } }

        val output = StringBuilder()
        val characters = mutableListOf<GeometryTextChar>()
        orderedLines.forEachIndexed { lineIndex, line ->
            if (lineIndex > 0) output.append('\n')
            val ordered = logicalClusters(line.clusters)
            val spacesAfter = mutableMapOf<Cluster, MutableList<RawChar>>()
            val sourceSpacesOnLine = sourceSpaces.filter { space ->
                val previous = sourceToCluster[space.sourceIndex - 1]
                val following = sourceToCluster[space.sourceIndex + 1]
                val target = previous ?: following
                target != null && clusterToLine[target] == lineIndex
            }
            sourceSpacesOnLine.forEach { space ->
                val target = sourceToCluster[space.sourceIndex - 1] ?: sourceToCluster[space.sourceIndex + 1]
                if (target != null) spacesAfter.getOrPut(target) { mutableListOf() } += space
            }
            val lineHasSourceSpaces = sourceSpacesOnLine.isNotEmpty()
            val lineHeights = line.clusters.map { it.height }.filter { it > 0f }.sorted()
            val medianLineHeight = if (lineHeights.size % 2 == 0) {
                lineHeights.getOrNull(lineHeights.size / 2 - 1)?.let { lower ->
                    (lower + lineHeights[lineHeights.size / 2]) * 0.5f
                } ?: 0f
            } else {
                lineHeights.getOrNull(lineHeights.size / 2) ?: 0f
            }
            val lineGlyphs = mutableListOf<OutputGlyph>()
            ordered.forEachIndexed { index, item ->
                val cluster = item.cluster
                lineGlyphs += OutputGlyph(cluster.text, cluster.sourceIndices, cluster.box)
                val explicitSpaces = spacesAfter[cluster].orEmpty().sortedBy { it.sourceIndex }
                val isLast = index == ordered.lastIndex
                if (explicitSpaces.isNotEmpty()) {
                    if (!isLast) explicitSpaces.forEach { lineGlyphs += OutputGlyph(" ", listOf(it.sourceIndex), EMPTY_RECT) }
                } else if (!isLast && !lineHasSourceSpaces) {
                    val next = ordered[index + 1]
                    if (item.runIndex == next.runIndex && shouldSynthesizeSpace(cluster, next.cluster, item.runDirection, medianLineHeight)) {
                        lineGlyphs += OutputGlyph(" ", emptyList(), EMPTY_RECT)
                    }
                }
            }
            lineGlyphs.forEach { glyph ->
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

    private fun attachMarksBySourceRun(chars: List<RawChar>): List<Cluster> {
        val charsByIndex = chars.associateBy { it.sourceIndex }
        val attached = mutableMapOf<Int, MutableList<List<RawChar>>>()
        val standalone = mutableListOf<List<RawChar>>()
        val markRuns = mutableListOf<List<RawChar>>()
        var index = 0
        while (index < chars.size) {
            if (!isCombiningMark(chars[index].text.codePointAt(0))) {
                index++
                continue
            }
            val run = mutableListOf<RawChar>()
            var expected = chars[index].sourceIndex
            while (index < chars.size && chars[index].sourceIndex == expected &&
                isCombiningMark(chars[index].text.codePointAt(0))
            ) {
                run += chars[index]
                index++
                expected++
            }
            markRuns += run
        }

        fun validBase(char: RawChar?) = char != null &&
            !char.text.all(Char::isWhitespace) &&
            !isCombiningMark(char.text.codePointAt(0))

        markRuns.forEach { run ->
            val runBox = run.map { it.box }.filter { it.isPositioned() }.reduceOrNull { first, second -> first.union(second) }
            val previous = charsByIndex[run.first().sourceIndex - 1]
            val following = charsByIndex[run.last().sourceIndex + 1]
            val before = previous.takeIf(::validBase)?.takeIf { base ->
                runBox != null && horizontalOverlap(runBox, base.box) > 0f
            }
            val after = following.takeIf(::validBase)?.takeIf { base ->
                runBox != null && horizontalGap(runBox, base.box) <= 0.5f
            }
            val target = before ?: after ?: chars.asSequence()
                .filter(::validBase)
                .minByOrNull { base ->
                    if (runBox != null && base.box.isPositioned()) {
                        val dx = runBox.centerX() - base.box.centerX()
                        val dy = runBox.centerY() - base.box.centerY()
                        dx * dx + dy * dy
                    } else {
                        abs(base.sourceIndex - run.first().sourceIndex).toFloat()
                    }
                }
            if (target == null) standalone.add(run) else attached.getOrPut(target.sourceIndex) { mutableListOf() }.add(run)
        }

        val clusters = mutableListOf<Cluster>()
        chars.filter { !it.text.all(Char::isWhitespace) && !isCombiningMark(it.text.codePointAt(0)) }.forEach { base ->
            val marks = attached[base.sourceIndex].orEmpty().flatten().sortedBy { it.sourceIndex }
            val sourceIndices = (listOf(base.sourceIndex) + marks.map { it.sourceIndex }).sorted()
            val text = Normalizer.normalize(base.text + marks.joinToString("") { it.text }, Normalizer.Form.NFC)
            val box = (listOf(base.box) + marks.map { it.box }).filter { it.isPositioned() }
                .reduceOrNull { first, second -> first.union(second) }
                ?: base.box
            clusters += Cluster(text, sourceIndices, box, direction(base.text))
        }
        standalone.forEach { run ->
            val box = run.map { it.box }.filter { it.isPositioned() }
                .reduceOrNull { first, second -> first.union(second) } ?: EMPTY_RECT
            clusters += Cluster(
                Normalizer.normalize(run.joinToString("") { it.text }, Normalizer.Form.NFC),
                run.map { it.sourceIndex },
                box,
                Direction.NEUTRAL,
            )
        }
        return clusters
    }

    private fun groupClustersIntoLines(clusters: List<Cluster>): MutableList<ClusterLine> {
        val heights = clusters.map { it.height }.sorted()
        val medianHeight = if (heights.size % 2 == 0) {
            (heights[heights.size / 2 - 1] + heights[heights.size / 2]) * 0.5f
        } else heights[heights.size / 2]
        val thresholdHeight = medianHeight * 0.5f
        val tall = clusters.filter { it.height >= thresholdHeight }
        val short = clusters.filter { it.height < thresholdHeight }
        val lines = mutableListOf<ClusterLine>()
        tall.sortedWith(compareBy<Cluster> { it.centerY }.thenBy { it.box.left }).forEach { cluster ->
            val target = lines
                .filter { verticalIntersection(cluster.box, it.top, it.bottom) >= 0.5f * min(cluster.height, it.bottom - it.top) }
                .maxByOrNull { verticalIntersection(cluster.box, it.top, it.bottom) }
            (target ?: ClusterLine().also(lines::add)).add(cluster)
        }
        if (lines.isEmpty()) {
            val first = short.minByOrNull { it.centerY }
            if (first != null) lines += ClusterLine().also { it.add(first) }
        }
        val tallClusters = lines.flatMap { it.clusters }.toSet()
        short.filterNot { it in tallClusters }.forEach { cluster ->
            val candidates = lines.filter { cluster.box.right + 2f * medianHeight >= it.left && cluster.box.left - 2f * medianHeight <= it.right }
            val pool = candidates.ifEmpty { lines }
            val target = pool.minByOrNull { verticalDistance(cluster.box.top, cluster.box.bottom, it.top, it.bottom) }
            if (target != null) target.add(cluster)
        }
        return lines
    }

    private fun logicalClusters(clusters: List<Cluster>): List<RunCluster> {
        val visual = clusters.sortedWith(compareBy<Cluster> { it.centerX }.thenBy { it.sourceIndex })
        val rtlCount = visual.count { it.direction == Direction.RTL }
        val ltrCount = visual.count { it.direction == Direction.LTR }
        val baseDirection = if (rtlCount >= ltrCount) Direction.RTL else Direction.LTR
        val resolved = visual.mapIndexed { index, cluster ->
            if (cluster.direction != Direction.NEUTRAL) cluster.direction else {
                val before = (index - 1 downTo 0).firstNotNullOfOrNull { visual[it].direction.takeIf { d -> d != Direction.NEUTRAL } }
                val after = (index + 1..visual.lastIndex).firstNotNullOfOrNull { visual[it].direction.takeIf { d -> d != Direction.NEUTRAL } }
                when {
                    before != null && before == after -> before
                    before == null -> after ?: baseDirection
                    after == null -> before
                    else -> baseDirection
                }
            }
        }
        val runs = mutableListOf<Pair<Direction, MutableList<Cluster>>>()
        visual.forEachIndexed { index, cluster ->
            val runDirection = resolved[index]
            if (runs.lastOrNull()?.first != runDirection) runs += runDirection to mutableListOf()
            runs.last().second += cluster
        }
        val visualRuns = runs.mapIndexed { index, pair -> Triple(index, pair.first, pair.second) }
        val logicalRuns = if (baseDirection == Direction.RTL) visualRuns.asReversed() else visualRuns
        return logicalRuns.flatMap { (runIndex, runDirection, run) ->
            val ordered = if (runDirection == Direction.RTL) run.asReversed() else run
            ordered.map { RunCluster(it, runIndex, runDirection) }
        }
    }

    private fun shouldSynthesizeSpace(
        first: Cluster,
        second: Cluster,
        runDirection: Direction,
        medianLineHeight: Float,
    ): Boolean {
        if (medianLineHeight <= 0f) return false
        val gap = if (runDirection == Direction.RTL) first.box.left - second.box.right else second.box.left - first.box.right
        return gap > medianLineHeight * SPACE_GAP_HEIGHT_RATIO
    }

    private fun verticalIntersection(box: PdfTextRect, top: Float, bottom: Float): Float =
        max(0f, min(box.bottom, bottom) - max(box.top, top))

    private fun verticalDistance(firstTop: Float, firstBottom: Float, secondTop: Float, secondBottom: Float): Float =
        max(0f, max(firstTop - secondBottom, secondTop - firstBottom))

    private fun horizontalOverlap(first: PdfTextRect, second: PdfTextRect): Float =
        max(0f, min(first.right, second.right) - max(first.left, second.left))

    private fun horizontalGap(first: PdfTextRect, second: PdfTextRect): Float =
        max(0f, max(first.left - second.right, second.left - first.right))

    private fun PdfTextRect.centerX() = (left + right) * 0.5f
    private fun PdfTextRect.centerY() = (top + bottom) * 0.5f
    private fun PdfTextRect.isPositioned() =
        right >= left && bottom >= top && !(left == 0f && top == 0f && right == 0f && bottom == 0f)

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
        ) || codePoint in 0x064B..0x065F || codePoint == 0x0670 || codePoint in 0x06D6..0x06ED

    private fun PdfTextRect.union(other: PdfTextRect) = PdfTextRect(
        min(left, other.left),
        min(top, other.top),
        max(right, other.right),
        max(bottom, other.bottom),
    )

    private const val SPACE_GAP_HEIGHT_RATIO = 0.3f
    private val EMPTY_RECT = PdfTextRect(0f, 0f, 0f, 0f)
}

private fun PdfTextRect.union(other: PdfTextRect) = PdfTextRect(
    min(left, other.left),
    min(top, other.top),
    max(right, other.right),
    max(bottom, other.bottom),
)

private fun PdfTextRect.centerY() = (top + bottom) * 0.5f
