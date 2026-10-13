package com.hashim.notepad.pdf

import android.graphics.ColorMatrixColorFilter

object PdfInvert {
    private const val lr = 0.2126f
    private const val lg = 0.7152f
    private const val lb = 0.0722f

    val darkPaperFilter: ColorMatrixColorFilter = ColorMatrixColorFilter(
        floatArrayOf(
            1f - 2f * lr, -2f * lg, -2f * lb, 0f, 255f,
            -2f * lr, 1f - 2f * lg, -2f * lb, 0f, 255f,
            -2f * lr, -2f * lg, 1f - 2f * lb, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
}
