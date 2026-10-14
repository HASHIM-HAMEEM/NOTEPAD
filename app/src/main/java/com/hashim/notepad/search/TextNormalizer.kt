package com.hashim.notepad.search

import java.text.Normalizer
import java.util.Locale

data class NormalizedText(val text: String, val indexMap: IntArray) {
    fun sourceRange(source: String, start: Int, endExclusive: Int): Pair<Int, Int> {
        require(start >= 0 && endExclusive > start && endExclusive <= indexMap.size)
        val originalStart = indexMap[start]
        val lastOriginal = indexMap[endExclusive - 1]
        var originalEnd = lastOriginal + Character.charCount(source.codePointAt(lastOriginal))
        while (originalEnd < source.length) {
            val codePoint = source.codePointAt(originalEnd)
            if (Character.getType(codePoint) !in setOf(
                    Character.NON_SPACING_MARK.toInt(),
                    Character.COMBINING_SPACING_MARK.toInt(),
                    Character.ENCLOSING_MARK.toInt(),
                )
            ) break
            originalEnd += Character.charCount(codePoint)
        }
        return originalStart to originalEnd
    }
}

object TextNormalizer {
    fun normalize(value: String): NormalizedText {
        val text = StringBuilder()
        val indices = ArrayList<Int>()
        var sourceIndex = 0
        var pendingSpaceAt: Int? = null

        while (sourceIndex < value.length) {
            val originalIndex = sourceIndex
            sourceIndex += Character.charCount(value.codePointAt(sourceIndex))
            while (sourceIndex < value.length && isCombiningMark(value.codePointAt(sourceIndex))) {
                sourceIndex += Character.charCount(value.codePointAt(sourceIndex))
            }
            val compatibility = Normalizer.normalize(value.substring(originalIndex, sourceIndex), Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT)

            var normalizedIndex = 0
            while (normalizedIndex < compatibility.length) {
                val normalizedPoint = compatibility.codePointAt(normalizedIndex)
                normalizedIndex += Character.charCount(normalizedPoint)
                if (Character.isWhitespace(normalizedPoint)) {
                    if (text.isNotEmpty() && text.last() != ' ') {
                        pendingSpaceAt = originalIndex
                    }
                    continue
                }
                if (isIgnored(normalizedPoint)) {
                    if (isTashkeel(normalizedPoint)) pendingSpaceAt = null
                    continue
                }
                pendingSpaceAt?.let { spaceAt ->
                    text.append(' ')
                    indices += spaceAt
                }
                pendingSpaceAt = null
                val folded = fold(normalizedPoint)
                val chars = String(Character.toChars(folded))
                text.append(chars)
                repeat(chars.length) { indices += originalIndex }
            }
        }

        if (text.isNotEmpty() && text.last() == ' ') {
            text.setLength(text.length - 1)
            indices.removeAt(indices.lastIndex)
        }
        return NormalizedText(text.toString(), indices.toIntArray())
    }

    private fun isIgnored(codePoint: Int): Boolean =
        isTashkeel(codePoint) ||
            codePoint == 0x0640 ||
            codePoint == 0x200C ||
            codePoint == 0x200D ||
            codePoint == 0x200E ||
            codePoint == 0x200F ||
            codePoint in 0x202A..0x202E ||
            codePoint in 0x2066..0x2069 ||
            codePoint == 0xFEFF ||
            codePoint == 0x0000 ||
            codePoint == 0xFFFD

    private fun isTashkeel(codePoint: Int): Boolean =
        codePoint in 0x064B..0x065F || codePoint == 0x0670 || codePoint in 0x06D6..0x06ED

    private fun isCombiningMark(codePoint: Int): Boolean =
        when (Character.getType(codePoint)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt() -> true
            else -> false
        }

    private fun fold(codePoint: Int): Int = when (codePoint) {
        0x064A, 0x0649, 0x06CC, 0x0626 -> 0x06CC
        0x0643, 0x06A9 -> 0x06A9
        0x0647, 0x06C1, 0x06BE, 0x0629, 0x06C0 -> 0x0647
        0x0623, 0x0625, 0x0622, 0x0671 -> 0x0627
        0x0624 -> 0x0648
        in 0x0660..0x0669 -> '0'.code + (codePoint - 0x0660)
        in 0x06F0..0x06F9 -> '0'.code + (codePoint - 0x06F0)
        else -> codePoint
    }
}
