package com.hashim.notepad.ui

import androidx.compose.ui.graphics.Color
import com.hashim.notepad.data.Notebook
import kotlin.math.pow

enum class LibrarySort { RECENT, NAME, DATE_ADDED }

internal fun sortNotebooks(notebooks: List<Notebook>, sort: LibrarySort): List<Notebook> = when (sort) {
    LibrarySort.RECENT -> notebooks.sortedWith(compareByDescending<Notebook> { it.lastOpenedAt ?: it.updatedAt }.thenBy { it.title.lowercase() })
    LibrarySort.NAME -> notebooks.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    LibrarySort.DATE_ADDED -> notebooks.sortedWith(compareByDescending<Notebook> { it.createdAt }.thenBy { it.title.lowercase() })
}

internal fun fileTypeBadge(mimeType: String?, filename: String?): String {
    val extension = filename.orEmpty().substringAfterLast('.', "").uppercase()
    val supported = setOf("PDF", "DOC", "DOCX", "PPT", "PPTX", "XLS", "XLSX", "EPUB", "ZIP", "TXT", "RTF")
    if (extension in supported) return extension
    return when {
        mimeType == "application/pdf" -> "PDF"
        mimeType?.contains("wordprocessingml") == true || mimeType == "application/msword" -> "DOC"
        mimeType?.contains("presentation") == true -> "PPT"
        mimeType?.contains("spreadsheet") == true -> "XLS"
        mimeType == "application/epub+zip" -> "EPUB"
        mimeType == "application/zip" -> "ZIP"
        mimeType?.startsWith("image/") == true -> "IMAGE"
        else -> "FILE"
    }
}

internal fun progressLabel(pageIndex: Int, pageCount: Int): String =
    "p. ${(pageIndex + 1).coerceIn(1, pageCount.coerceAtLeast(1))} of ${pageCount.coerceAtLeast(1)}"

internal fun importProgressLabel(filename: String, percent: Int): String = "Importing $filename · ${percent.coerceIn(0, 100)}%"

internal fun contrastRatio(foreground: Color, background: Color): Double {
    val light = maxOf(relativeLuminance(foreground), relativeLuminance(background))
    val dark = minOf(relativeLuminance(foreground), relativeLuminance(background))
    return (light + 0.05) / (dark + 0.05)
}

internal fun accessibleTextColor(background: Color, preferred: Color): Color =
    if (contrastRatio(preferred, background) >= 4.5) {
        preferred
    } else if (contrastRatio(Color.White, background) >= contrastRatio(Color.Black, background)) {
        Color.White
    } else {
        Color.Black
    }

private fun relativeLuminance(color: Color): Double {
    fun linear(channel: Float): Double {
        val normalized = channel.toDouble()
        return if (normalized <= 0.04045) normalized / 12.92 else ((normalized + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
}
