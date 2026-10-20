package com.hashim.notepad.ink

import com.hashim.notepad.data.PageImage
import com.hashim.notepad.MainActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalUndoRedoTest {
    @Test
    fun externalRepositoryActionCanBeUndoneAndRedone() {
        withView { view ->
            var currentValue = "edited"
            view.recordExternalAction(
                undoAction = { currentValue = "original" },
                redoAction = { currentValue = "edited" },
            )

            view.undo()
            assertEquals("original", currentValue)
            assertEquals(true, view.canRedo())

            view.redo()
            assertEquals("edited", currentValue)
            assertEquals(true, view.canUndo())
            assertEquals(false, view.canRedo())
        }
    }

    @Test
    fun pageImageAdditionCanBeUndoneAndRedone() {
        withView { view ->
            var pageImages = emptyList<PageImage>()
            view.onPageImagesChanged = { pageImages = it }
            val image = PageImage("image", "page", "asset", 10f, 20f, 80f, 60f, 1L, "/missing/image.png")

            view.addPageImage(image)
            assertEquals(listOf(image), pageImages)

            view.undo()
            assertEquals(emptyList<PageImage>(), pageImages)
            assertEquals(true, view.canRedo())

            view.redo()
            assertEquals(listOf(image), pageImages)
        }
    }

    private fun withView(block: (InkSurfaceView) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> block(InkSurfaceView(activity)) }
        }
    }
}
