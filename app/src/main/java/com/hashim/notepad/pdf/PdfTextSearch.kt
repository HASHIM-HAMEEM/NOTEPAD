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
    val sourceIndices: List<Int> = emptyList(),
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
    val normalizedQueries = buildList {
        TextNormalizer.normalize(query).text.takeIf { it.isNotEmpty() }?.let(::add)
        val reversed = buildString {
            query.codePoints().toArray().reversedArray().forEach { appendCodePoint(it) }
        }
        TextNormalizer.normalize(reversed).text.takeIf { it.isNotEmpty() && it !in this }?.let(::add)
    }
    if (normalizedQueries.isEmpty()) return emptyList()
    val normalized = TextNormalizer.normalize(source)
    val results = ArrayList<PdfTextSearchHit>()
    for (normalizedQuery in normalizedQueries) {
        var searchFrom = 0
        while (searchFrom < normalized.text.length) {
            val match = normalized.text.indexOf(normalizedQuery, searchFrom)
            if (match < 0) break
            val (start, end) = normalized.sourceRange(source, match, match + normalizedQuery.length)
            val snippetStart = (start - 30).coerceAtLeast(0)
            val snippetEnd = (end + 30).coerceAtMost(source.length)
            val hit = PdfTextSearchHit(
                pageId = pageId,
                pageIndex = pageIndex,
                snippet = source.substring(snippetStart, snippetEnd),
                matchStart = start,
                matchEnd = end,
                snippetMatchStart = start - snippetStart,
                snippetMatchEnd = end - snippetStart,
            )
            if (results.none { it.matchStart == hit.matchStart && it.matchEnd == hit.matchEnd }) results += hit
            searchFrom = match + normalizedQuery.length
        }
    }
    if (results.isEmpty()) {
        normalizedQueries.firstOrNull(::isArabicWord)?.let { query ->
            val reversed = query.codePoints().toArray().reversedArray()
                .joinToString("") { String(Character.toChars(it)) }
            val queryPoints = reversed.codePoints().toArray()
            val maxGap = (queryPoints.size * 10).coerceAtLeast(24)
            var from = 0
            while (from < normalized.text.length) {
                var cursor = from
                val positions = ArrayList<Int>(queryPoints.size)
                for (point in queryPoints) {
                    val next = normalized.text.indexOf(String(Character.toChars(point)), cursor)
                    if (next < 0 || next - cursor > maxGap) break
                    positions += next
                    cursor = next + Character.charCount(point)
                }
                if (positions.size == queryPoints.size) {
                    val sourceIndices = positions.map { normalized.indexMap[it] }
                    val start = sourceIndices.minOrNull() ?: break
                    val last = sourceIndices.maxOrNull() ?: break
                    val end = last + Character.charCount(source.codePointAt(last))
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
                        sourceIndices = sourceIndices,
                    )
                    from = positions.first() + 1
                } else {
                    break
                }
            }
        }
    }
    return results.sortedBy { it.matchStart }
}

private fun isArabicWord(value: String): Boolean {
    val points = value.codePoints().toArray()
    return points.size >= 2 && points.all {
        it in 0x0600..0x06FF || it in 0x0750..0x077F || it in 0x08A0..0x08FF
    }
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
                val rects = if (hit.sourceIndices.isEmpty()) {
                    source.textLineRects(pdfIndex, hit.matchStart, hit.matchEnd)
                } else {
                    val boxes = source.textCharBoxes(pdfIndex)
                    hit.sourceIndices.mapNotNull(boxes::getOrNull).filter {
                        it.right > it.left && it.bottom > it.top
                    }
                }
                onHit(hit.copy(rects = rects))
            }
        }
    }
}
