package com.hashim.notepad.sync

internal object SyncSchemaFallback {
    val optionalTables = setOf("folders", "tags", "notebook_tags", "page_images", "pdf_marks")
    val optionalColumns = mapOf(
        "notebooks" to listOf("folder_id", "document_kind", "last_page_index", "last_opened_at", "file_asset_id", "file_mime_type", "original_filename"),
        "pages" to listOf("bookmarked", "rotation"),
        "text_blocks" to listOf("color_argb"),
    )

    fun shouldDeferTable(table: String, code: Int, message: String): Boolean =
        table in optionalTables && MissingSchemaClassifier.isMissingSchema(code, message)

    fun missingColumns(table: String, code: Int, message: String, alreadyMissing: Set<String>): List<String> =
        optionalColumns[table].orEmpty().filterNot(alreadyMissing::contains).filter {
            MissingSchemaClassifier.isMissingOptionalColumn(code, message, table, it)
        }
}
