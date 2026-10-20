package com.hashim.notepad.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissingSchemaClassifierTest {
    @Test
    fun recognizesPostgrestAndPostgresMissingSchemaErrors() {
        listOf("PGRST205", "PGRST204", "42P01", "42703").forEach { code ->
            assertTrue(MissingSchemaClassifier.isMissingSchema(400, """{"code":"$code"}"""))
        }
        assertTrue(MissingSchemaClassifier.isMissingSchema(404, """{"message":"not found"}"""))
    }

    @Test
    fun doesNotClassifyUnrelatedHttpErrorsAsSchemaMismatch() {
        assertFalse(MissingSchemaClassifier.isMissingSchema(401, """{"code":"42501"}"""))
        assertFalse(MissingSchemaClassifier.isMissingSchema(400, """{"code":"23505"}"""))
    }

    @Test
    fun recognizesOptionalColumnsByTableAndColumn() {
        assertTrue(
            MissingSchemaClassifier.isMissingOptionalColumn(
                400,
                """{"code":"PGRST204","message":"Could not find folder_id column of notebooks"}""",
                "notebooks",
                "folder_id",
            ),
        )
        assertTrue(
            MissingSchemaClassifier.isMissingOptionalColumn(
                400,
                """{"code":"42703","message":"column notebooks.folder_id does not exist"}""",
                "notebooks",
                "folder_id",
            ),
        )
        assertTrue(
            MissingSchemaClassifier.isMissingOptionalColumn(
                400,
                """{"code":"PGRST204","message":"Could not find bookmarked column of pages"}""",
                "pages",
                "bookmarked",
            ),
        )
        assertFalse(
            MissingSchemaClassifier.isMissingOptionalColumn(
                400,
                """{"code":"42703","message":"column notebooks.language does not exist"}""",
                "notebooks",
                "folder_id",
            ),
        )
        assertFalse(
            MissingSchemaClassifier.isMissingOptionalColumn(
                400,
                """{"code":"42703","message":"column pages.bookmarked does not exist"}""",
                "notebooks",
                "bookmarked",
            ),
        )
    }

    @Test
    fun defersMissingPdfMarksTableAndPagesRotationColumn() {
        assertTrue(SyncSchemaFallback.shouldDeferTable("pdf_marks", 404, """{"message":"not found"}"""))
        assertFalse(SyncSchemaFallback.shouldDeferTable("pages", 404, """{"message":"not found"}"""))
        assertEquals(
            listOf("rotation"),
            SyncSchemaFallback.missingColumns(
                "pages",
                400,
                """{"code":"PGRST204","message":"Could not find rotation column of pages"}""",
                emptySet(),
            ),
        )
    }
}
