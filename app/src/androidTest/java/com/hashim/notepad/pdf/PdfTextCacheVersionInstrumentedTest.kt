package com.hashim.notepad.pdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfTextCacheVersionInstrumentedTest {
    @Test
    fun startupCleanupDeletesPriorGeometryVersionsAndKeepsCurrentCache() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "pdf-text")
        val stale = File(root, "v1/stale.gtext").apply { parentFile?.mkdirs(); writeText("stale") }
        val current = File(root, "v${PdfTextCache.GEOMETRY_CACHE_VERSION}/current.gtext")
            .apply { parentFile?.mkdirs(); writeText("current") }

        PdfTextCache.clearStaleCacheVersions(context)

        assertFalse(stale.exists())
        assertTrue(current.isFile)
        root.deleteRecursively()
    }
}
