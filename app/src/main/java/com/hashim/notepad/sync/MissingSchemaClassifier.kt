package com.hashim.notepad.sync

/** Identifies only PostgREST/PostgreSQL errors that indicate a table or column is not deployed yet. */
object MissingSchemaClassifier {
    private val errorCode = Regex("\"code\"\\s*:\\s*\"([A-Za-z0-9]+)\"")
    private val missingSchemaCodes = setOf("PGRST205", "PGRST204", "42P01", "42703")
    private val missingColumnCodes = setOf("PGRST204", "42703")

    fun isMissingSchema(statusCode: Int, response: String): Boolean =
        statusCode == 404 || errorCode.find(response)?.groupValues?.get(1)?.let { it in missingSchemaCodes } == true

    fun isMissingNotebookFolderColumn(statusCode: Int, response: String): Boolean {
        val code = errorCode.find(response)?.groupValues?.get(1)
        return statusCode != 404 &&
            code?.let { it in missingColumnCodes } == true &&
            response.contains("folder_id", ignoreCase = true) &&
            response.contains("notebooks", ignoreCase = true)
    }
}
