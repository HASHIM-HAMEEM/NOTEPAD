package com.hashim.notepad.recognition

data class TextBlockPlacement(val xPt: Float, val yPt: Float, val widthPt: Float, val fontSizePt: Float)

object TextBlockGeometry {
    fun fromBounds(left: Float, top: Float, right: Float, bottom: Float): TextBlockPlacement {
        val height = (bottom - top).coerceAtLeast(1f)
        return TextBlockPlacement(
            xPt = left,
            yPt = top,
            widthPt = (right - left).coerceAtLeast(1f),
            fontSizePt = (height * 0.65f).coerceIn(10f, 28f),
        )
    }
}
