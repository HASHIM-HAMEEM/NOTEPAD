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
    val documentKind: String = "notes",
    val lastPageIndex: Int = 0,
    val lastOpenedAt: Long? = null,
    val fileAssetId: String? = null,
    val fileMimeType: String? = null,
    val originalFilename: String? = null,
)

data class ImportedImageContent(
    val asset: Asset,
    val xPt: Float,
    val yPt: Float,
    val widthPt: Float,
    val heightPt: Float,
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
    val rotation: Int = 0,
)

data class SplitPair(
    val id: String,
    val leftNotebookId: String,
    val rightNotebookId: String,
    val dividerRatio: Float,
    val swapped: Boolean,
)

val Page.displayWidthPt: Float get() = if (rotation % 180 == 90) heightPt else widthPt
val Page.displayHeightPt: Float get() = if (rotation % 180 == 90) widthPt else heightPt

enum class PdfMarkKind(val db: String) {
    HIGHLIGHT("highlight"), UNDERLINE("underline"), STRIKE("strike"), SQUIGGLY("squiggly");
    companion object { fun from(value: String) = entries.firstOrNull { it.db == value } ?: HIGHLIGHT }
}

data class PdfMark(
    val id: String,
    val notebookId: String,
    val pageId: String,
    val kind: PdfMarkKind,
    val colorArgb: Int,
    val quads: List<Float>,
    val charStart: Int?,
    val charEnd: Int?,
    val text: String,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
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
    val colorArgb: Int? = null,
    val rotationDegrees: Float = 0f,
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
