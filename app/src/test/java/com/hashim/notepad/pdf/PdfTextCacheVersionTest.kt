package com.hashim.notepad.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfTextCacheVersionTest {
    @Test
    fun memoryCacheKeyIncludesGeometryAlgorithmVersion() {
        assertEquals("v${PdfTextCache.GEOMETRY_CACHE_VERSION}:asset-id:4", PdfTextCache.memoryCacheKey("asset-id", 4))
    }
}
