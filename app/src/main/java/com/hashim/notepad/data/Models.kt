package com.hashim.notepad.data

enum class PageKind(val db: String) {
    BLANK("blank"), LINED("lined"), GRID("grid"), PDF("pdf");

    companion object { fun from(db: String) = entries.first { it.db == db } }
}

data class Notebook(
    val id: String,
    val title: String,
    val coverColor: String?,
    val language: String,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val pageCount: Int = 0,
)

data class Page(
    val id: String,
    val notebookId: String,
    val sortKey: Double,
    val kind: PageKind,
    val assetId: String?,
    val pdfPageIndex: Int?,
    val widthPt: Float,
    val heightPt: Float,
    val createdAt: Long,
    val updatedAt: Long,
)

data class Asset(
    val id: String,
    val kind: String,
    val localPath: String,
    val byteSize: Long,
    val sha256: String,
    val pageCount: Int?,
    val name: String?,
)

data class SearchHit(val pageId: String, val notebookId: String, val notebookTitle: String, val pageIndex: Int, val snippet: String, val source: String)
