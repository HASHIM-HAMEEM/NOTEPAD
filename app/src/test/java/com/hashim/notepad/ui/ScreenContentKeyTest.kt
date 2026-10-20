package com.hashim.notepad.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ScreenContentKeyTest {
    @Test
    fun editorPagesInTheSameNotebookShareOneAnimatedContentKey() {
        assertEquals(
            screenContentKey(Screen.Editor("notebook", "page-1")),
            screenContentKey(Screen.Editor("notebook", "page-2")),
        )
    }

    @Test
    fun differentNotebooksKeepDistinctEditorContentKeys() {
        assertNotEquals(
            screenContentKey(Screen.Editor("notebook-1", "page-1")),
            screenContentKey(Screen.Editor("notebook-2", "page-1")),
        )
    }
}
