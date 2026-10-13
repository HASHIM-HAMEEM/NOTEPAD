package com.hashim.notepad.ui

import com.hashim.notepad.data.Notebook

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
