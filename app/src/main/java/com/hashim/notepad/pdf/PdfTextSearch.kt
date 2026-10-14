package com.hashim.notepad.pdf

import android.content.Context
import android.util.LruCache
import com.hashim.notepad.data.Page
import com.hashim.notepad.search.TextNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class PdfTextSearchHit(
    val pageId: String,
    val pageIndex: Int,
    val snippet: String,
    val matchStart: Int,
    val matchEnd: Int,
    val snippetMatchStart: Int,
    val snippetMatchEnd: Int,
    val rects: List<PdfTextRect> = emptyList(),
)

data class PdfTextRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

object PdfTextCache {
    private val memory = object : LruCache<String, String>(256) {}

    fun getOrLoad(context: Context, assetId: String, pageIndex: Int, load: () -> String): String {
        val key = "$assetId:$pageIndex"
        synchronized(memory) { memory.get(key)?.let { return it } }
        val digest = MessageDigest.getInstance("SHA-256").digest(assetId.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val file = File(File(context.cacheDir, "pdf-text/$digest"), "$pageIndex.txt")
        val text = if (file.isFile) runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: load() else load()
        if (!file.isFile) {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.part")
            runCatching {
                temporary.writeText(text, Charsets.UTF_8)
                if (!temporary.renameTo(file)) temporary.delete()
            }
        }
        synchronized(memory) { memory.put(key, text) }
        return text
    }
}

fun findPdfTextHits(pageId: String, pageIndex: Int, source: String, query: String): List<PdfTextSearchHit> {
    val normalizedQuery = TextNormalizer.normalize(query).text
    if (normalizedQuery.isEmpty()) return emptyList()
    val normalized = TextNormalizer.normalize(source)
    val results = ArrayList<PdfTextSearchHit>()
    var searchFrom = 0
    while (searchFrom < normalized.text.length) {
        val match = normalized.text.indexOf(normalizedQuery, searchFrom)
        if (match < 0) break
        val (start, end) = normalized.sourceRange(source, match, match + normalizedQuery.length)
        val snippetStart = (start - 30).coerceAtLeast(0)
        val snippetEnd = (end + 30).coerceAtMost(source.length)
        results += PdfTextSearchHit(
            pageId = pageId,
            pageIndex = pageIndex,
            snippet = source.substring(snippetStart, snippetEnd),
            matchStart = start,
            matchEnd = end,
            snippetMatchStart = start - snippetStart,
            snippetMatchEnd = end - snippetStart,
        )
        searchFrom = match + normalizedQuery.length
    }
    return results
}

suspend fun searchPdfText(
    context: Context,
    assetId: String,
    file: File,
    password: String?,
    pages: List<Page>,
    query: String,
    onHit: suspend (PdfTextSearchHit) -> Unit,
) = withContext(PdfiumDispatcher.dispatcher) {
    PdfPageSource(context, file, password).use { source ->
        pages.forEachIndexed { pageNumber, page ->
            coroutineContext.ensureActive()
            val pdfIndex = page.pdfPageIndex ?: pageNumber
            val text = PdfTextCache.getOrLoad(context, assetId, pdfIndex) { source.text(pdfIndex) }
            findPdfTextHits(page.id, pdfIndex, text, query).forEach { hit ->
                onHit(hit.copy(rects = source.textLineRects(pdfIndex, hit.matchStart, hit.matchEnd)))
            }
        }
    }
}
