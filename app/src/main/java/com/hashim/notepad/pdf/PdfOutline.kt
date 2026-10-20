package com.hashim.notepad.pdf

data class PdfOutlineItem(
    val title: String,
    val pageIndex: Int,
    val children: List<PdfOutlineItem> = emptyList(),
)

data class PdfOutlineRow(
    val path: List<Int>,
    val item: PdfOutlineItem,
    val depth: Int,
)

fun flattenOutline(
    items: List<PdfOutlineItem>,
    expandedPaths: Set<List<Int>> = emptySet(),
): List<PdfOutlineRow> {
    val rows = ArrayList<PdfOutlineRow>()
    fun visit(nodes: List<PdfOutlineItem>, parentPath: List<Int>) {
        nodes.forEachIndexed { index, item ->
            val path = parentPath + index
            rows += PdfOutlineRow(path, item, parentPath.size)
            if (path in expandedPaths) visit(item.children, path)
        }
    }
    visit(items, emptyList())
    return rows
}

fun outlinePathForPage(items: List<PdfOutlineItem>, pageIndex: Int): List<Int>? {
    var bestPath: List<Int>? = null
    var bestPage = Int.MIN_VALUE
    fun visit(nodes: List<PdfOutlineItem>, parentPath: List<Int>) {
        nodes.forEachIndexed { index, item ->
            val path = parentPath + index
            if (item.pageIndex <= pageIndex && item.pageIndex >= bestPage) {
                bestPage = item.pageIndex
                bestPath = path
            }
            visit(item.children, path)
        }
    }
    visit(items, emptyList())
    return bestPath
}

fun chapterForPage(items: List<PdfOutlineItem>, pageIndex: Int): PdfOutlineItem? =
    outlinePathForPage(items, pageIndex)?.let { path ->
        var nodes = items
        var current: PdfOutlineItem? = null
        path.forEach { index ->
            current = nodes.getOrNull(index)
            nodes = current?.children.orEmpty()
        }
        current
    }
