package com.hashim.notepad.data

enum class PageKind(val db: String) {
    BLANK("blank"), LINED("lined"), GRID("grid"), DOTS("dots"), CORNELL("cornell"), MUSIC("music"), PDF("pdf");

    companion object { fun from(db: String) = entries.firstOrNull { it.db == db } ?: BLANK }
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
    val folderId: String? = null,
    val tagIds: Set<String> = emptySet(),
)

data class Folder(
    val id: String,
    val name: String,
    val color: String?,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

data class Tag(
    val id: String,
    val name: String,
    val color: String?,
    val createdAt: Long,
    val updatedAt: Long,
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
    val bookmarked: Boolean = false,
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

data class TextBlock(
    val id: String,
    val pageId: String,
    val xPt: Float,
    val yPt: Float,
    val wPt: Float,
    val direction: String,
    val content: String,
    val updatedAt: Long,
    val fontSizePt: Float = 12f,
)

data class PageImage(
    val id: String,
    val pageId: String,
    val assetId: String,
    val xPt: Float,
    val yPt: Float,
    val wPt: Float,
    val hPt: Float,
    val updatedAt: Long,
    val localPath: String,
)
