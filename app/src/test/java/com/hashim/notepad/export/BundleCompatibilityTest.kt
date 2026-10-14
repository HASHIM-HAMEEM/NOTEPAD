package com.hashim.notepad.export

import org.junit.Assert.assertEquals
import org.junit.Test

class BundleCompatibilityTest {
    @Test
    fun legacyNotebookMetadataDefaultsFromItsPages() {
        assertEquals(LegacyNotebookDefaults("pdf"), legacyNotebookDefaults(listOf("blank", "pdf")))
        assertEquals(LegacyNotebookDefaults("notes"), legacyNotebookDefaults(listOf("blank", "lined")))
        assertEquals(LegacyNotebookDefaults("notes"), legacyNotebookDefaults(emptyList()))
    }
}
