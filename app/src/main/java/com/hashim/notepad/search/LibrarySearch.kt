package com.hashim.notepad.search

data class LibrarySearchDocument(
    val notebookId: String,
    val title: String,
    val filename: String?,
)

data class LibrarySearchMatch(
    val document: LibrarySearchDocument,
    val snippet: String,
    val source: String,
)

object LibrarySearch {
    fun findMatches(query: String, documents: List<LibrarySearchDocument>): List<LibrarySearchMatch> {
        val normalizedQuery = TextNormalizer.normalize(query).text
        if (normalizedQuery.isEmpty()) return emptyList()
        return documents.mapNotNull { document ->
            val title = TextNormalizer.normalize(document.title).text
            when {
                title.contains(normalizedQuery) ->
                    LibrarySearchMatch(document, document.title, "title")
                !document.filename.isNullOrBlank() &&
                    TextNormalizer.normalize(document.filename).text.contains(normalizedQuery) ->
                    LibrarySearchMatch(document, document.filename, "filename")
                else -> null
            }
        }
    }
}
