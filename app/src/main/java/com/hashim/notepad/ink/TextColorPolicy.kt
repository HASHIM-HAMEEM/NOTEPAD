package com.hashim.notepad.ink

import kotlin.math.pow
import kotlin.math.roundToInt

data class StrokeColorSample(val colorArgb: Int, val countsAsInk: Boolean)

object TextColorPolicy {
    private const val MINIMUM_CONTRAST = 3f
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    fun textColor(samples: List<StrokeColorSample>, paperArgb: Int): Int {
        val fallback = paperAwareDefault(paperArgb)
        val selected = mostCommonInkColor(samples) ?: return fallback
        return if (contrastRatio(selected, paperArgb) >= MINIMUM_CONTRAST) selected else fallback
    }

    fun mostCommonInkColor(samples: List<StrokeColorSample>): Int? {
        val counts = linkedMapOf<Int, Int>()
        samples.filter { it.countsAsInk }.forEach { sample ->
            counts[sample.colorArgb] = (counts[sample.colorArgb] ?: 0) + 1
        }
        var mostCommon: Int? = null
        var mostCommonCount = 0
        for ((color, count) in counts) {
            if (count > mostCommonCount) {
                mostCommon = color
                mostCommonCount = count
            }
        }
        return mostCommon
    }

    fun paperAwareDefault(paperArgb: Int): Int =
        if (contrastRatio(WHITE, paperArgb) > contrastRatio(BLACK, paperArgb)) WHITE else BLACK

    fun contrastRatio(foregroundArgb: Int, paperArgb: Int): Float {
        val background = paperArgb or (0xFF shl 24)
        val foreground = composite(foregroundArgb, background)
        val foregroundLuminance = luminance(foreground)
        val backgroundLuminance = luminance(background)
        val lighter = maxOf(foregroundLuminance, backgroundLuminance)
        val darker = minOf(foregroundLuminance, backgroundLuminance)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    private fun composite(foreground: Int, background: Int): Int {
        val alpha = ((foreground ushr 24) and 0xFF) / 255f
        fun channel(shift: Int): Int {
            val front = (foreground ushr shift) and 0xFF
            val back = (background ushr shift) and 0xFF
            return (front * alpha + back * (1f - alpha)).roundToInt()
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun luminance(color: Int): Float {
        fun linear(shift: Int): Float {
            val channel = ((color ushr shift) and 0xFF) / 255f
            return if (channel <= 0.04045f) channel / 12.92f else ((channel + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
        }
        return 0.2126f * linear(16) + 0.7152f * linear(8) + 0.0722f * linear(0)
    }
}
