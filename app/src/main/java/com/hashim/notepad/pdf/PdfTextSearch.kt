package com.hashim.notepad.pdf

import android.content.Context
import android.util.LruCache
import com.hashim.notepad.data.OcrWord
import com.hashim.notepad.data.Page
import com.hashim.notepad.search.TextNormalizer
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.text.BreakIterator
import java.util.Locale
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
    val resultKey: String = PdfSearchResultKey.text(pageId, matchStart, matchEnd),
)

data class PdfTextRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

internal object PdfSearchResultKey {
    fun text(pageId: String, matchStart: Int, matchEnd: Int) =
        "pdf-text-hit:$pageId:$matchStart:$matchEnd"

    fun mark(markId: String) = "pdf-mark-hit:$markId"
}

object PdfTextCache {
    const val GEOMETRY_CACHE_VERSION = 3

    private val memory = object : LruCache<String, PdfGeometryTextLayer>(16 * 1024) {
        override fun sizeOf(key: String, value: PdfGeometryTextLayer): Int =
            ((value.text.length * 2L + value.characters.size * 64L) / 1024L).toInt().coerceAtLeast(1)
    }
    private var initializedCacheRoot: String? = null

    internal fun memoryCacheKey(assetId: String, pageIndex: Int): String =
        "v$GEOMETRY_CACHE_VERSION:$assetId:$pageIndex"

    fun clearStaleCacheVersions(context: Context) {
        synchronized(memory) {
            deleteStaleCacheVersions(context)
            memory.evictAll()
            initializedCacheRoot = File(context.cacheDir, "pdf-text").absolutePath
        }
    }

    fun getOrLoad(
        context: Context,
        assetId: String,
        pageIndex: Int,
        load: () -> PdfGeometryTextLayer,
    ): PdfGeometryTextLayer {
        val key = memoryCacheKey(assetId, pageIndex)
        synchronized(memory) {
            ensureCurrentCacheVersion(context)
            memory.get(key)?.let { return it }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(assetId.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val versionRoot = File(File(context.cacheDir, "pdf-text"), "v$GEOMETRY_CACHE_VERSION")
        val file = File(File(versionRoot, digest), "$pageIndex.gtext")
        val cached = if (file.isFile) runCatching { readLayer(file) }.getOrNull() else null
        val layer = cached ?: load()
        if (cached == null) {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.part")
            runCatching {
                writeLayer(temporary, layer)
                if (!temporary.renameTo(file)) temporary.delete()
            }
        }
        synchronized(memory) { memory.put(key, layer) }
        return layer
    }

    private fun ensureCurrentCacheVersion(context: Context) {
        val root = File(context.cacheDir, "pdf-text")
        if (initializedCacheRoot != root.absolutePath) {
            deleteStaleCacheVersions(context)
            memory.evictAll()
            initializedCacheRoot = root.absolutePath
        }
    }

    private fun deleteStaleCacheVersions(context: Context) {
        val root = File(context.cacheDir, "pdf-text")
        root.listFiles()?.filter { it.name != "v$GEOMETRY_CACHE_VERSION" }?.forEach { it.deleteRecursively() }
    }

    private fun writeLayer(file: File, layer: PdfGeometryTextLayer) {
        DataOutputStream(file.outputStream().buffered()).use { output ->
            output.writeInt(CACHE_MAGIC)
            output.writeString(layer.text)
            output.writeInt(layer.characters.size)
            layer.characters.forEach { character ->
                output.writeString(character.text)
                output.writeInt(character.start)
                output.writeInt(character.end)
                output.writeFloat(character.box.left)
                output.writeFloat(character.box.top)
                output.writeFloat(character.box.right)
                output.writeFloat(character.box.bottom)
                output.writeInt(character.lineIndex)
                output.writeInt(character.sourceIndices.size)
                character.sourceIndices.forEach(output::writeInt)
            }
        }
    }

    private fun readLayer(file: File): PdfGeometryTextLayer =
        DataInputStream(file.inputStream().buffered()).use { input ->
            require(input.readInt() == CACHE_MAGIC)
            val text = input.readString()
            val count = input.readInt().coerceIn(0, MAX_CACHED_CHARS)
            val characters = List(count) {
                val value = input.readString()
                val start = input.readInt()
                val end = input.readInt()
                val box = PdfTextRect(input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat())
                val lineIndex = input.readInt()
                val sourceCount = input.readInt().coerceIn(0, 32)
                val sources = List(sourceCount) { input.readInt() }
                GeometryTextChar(value, start, end, box, sources, lineIndex)
            }
            PdfGeometryTextLayer(text, characters)
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val size = readInt()
        require(size in 0..MAX_CACHED_STRING_BYTES)
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
    }

    private const val CACHE_MAGIC = 0x4E505458
    private const val MAX_CACHED_CHARS = 2_000_000
    private const val MAX_CACHED_STRING_BYTES = 16 * 1024 * 1024
}

fun findPdfTextHits(
    pageId: String,
    pageIndex: Int,
    layer: PdfGeometryTextLayer,
    query: String,
): List<PdfTextSearchHit> {
    val normalizedQuery = TextNormalizer.normalize(query).text
    if (normalizedQuery.isEmpty()) return emptyList()
    val normalized = TextNormalizer.normalize(layer.text)
    val results = ArrayList<PdfTextSearchHit>()
    val seenRanges = HashSet<Pair<Int, Int>>()
    val wordBoundaries = BreakIterator.getWordInstance(Locale.ROOT).apply { setText(layer.text) }
    val characterBoundaries = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(layer.text) }
    var searchFrom = 0
    while (searchFrom < normalized.text.length) {
        val match = normalized.text.indexOf(normalizedQuery, searchFrom)
        if (match < 0) break
        val (start, end) = normalized.sourceRange(layer.text, match, match + normalizedQuery.length)
        if (seenRanges.add(start to end)) {
            val contextStart = (start - SNIPPET_CONTEXT).coerceAtLeast(0)
            val contextEnd = (end + SNIPPET_CONTEXT).coerceAtMost(layer.text.length)
            val wordStart = if (contextStart == 0) 0 else
                wordBoundaries.preceding(contextStart + 1).takeIf { it != BreakIterator.DONE } ?: 0
            val wordEnd = if (contextEnd == layer.text.length) layer.text.length else
                wordBoundaries.following((contextEnd - 1).coerceAtLeast(0)).takeIf { it != BreakIterator.DONE }
                    ?: layer.text.length
            val snippetStart = if (wordStart == 0) 0 else
                characterBoundaries.preceding(wordStart + 1).takeIf { it != BreakIterator.DONE } ?: 0
            val snippetEnd = if (wordEnd == layer.text.length) layer.text.length else
                characterBoundaries.following((wordEnd - 1).coerceAtLeast(0)).takeIf { it != BreakIterator.DONE }
                    ?: layer.text.length
            results += PdfTextSearchHit(
                pageId = pageId,
                pageIndex = pageIndex,
                snippet = layer.text.substring(snippetStart, snippetEnd),
                matchStart = start,
                matchEnd = end,
                snippetMatchStart = start - snippetStart,
                snippetMatchEnd = end - snippetStart,
                rects = layer.rectsForRange(start, end),
                sourceIndices = layer.sourceIndices(start, end),
            )
        }
        searchFrom = match + normalizedQuery.length
    }
    return results
}

private const val SNIPPET_CONTEXT = 30

fun findPdfTextHits(pageId: String, pageIndex: Int, source: String, query: String): List<PdfTextSearchHit> =
    findPdfTextHits(pageId, pageIndex, PdfGeometryText.order(source, emptyList()), query)

fun findOcrWordHits(
    pageId: String,
    pageIndex: Int,
    words: List<OcrWord>,
    query: String,
): List<PdfTextSearchHit> =
    findPdfTextHits(pageId, pageIndex, OcrGeometry.toTextLayer(words), query)

suspend fun searchPdfText(
    context: Context,
    assetId: String,
    file: File,
    password: String?,
    pages: List<Page>,
    query: String,
    loadOcrWords: suspend (String) -> List<OcrWord> = { emptyList() },
    onHit: suspend (PdfTextSearchHit) -> Unit,
) = withContext(PdfiumDispatcher.dispatcher) {
    PdfPageSource(context, file, password).use { source ->
        pages.forEachIndexed { pageNumber, page ->
            coroutineContext.ensureActive()
            val pdfIndex = page.pdfPageIndex ?: pageNumber
            val ocrWords = loadOcrWords(page.id)
            val hits = if (ocrWords.isNotEmpty()) {
                findOcrWordHits(page.id, pdfIndex, ocrWords, query)
            } else {
                val layer = PdfTextCache.getOrLoad(context, assetId, pdfIndex) {
                    source.textGeometry(pdfIndex)
                }
                findPdfTextHits(page.id, pdfIndex, layer, query)
            }
            hits.forEach { hit ->
                onHit(hit)
            }
        }
    }
}

suspend fun loadPdfTextLayer(
    context: Context,
    assetId: String,
    pageIndex: Int,
    loadOcrWords: suspend () -> List<OcrWord>,
    loadEmbeddedText: () -> PdfGeometryTextLayer,
): PdfGeometryTextLayer {
    val embedded = PdfTextCache.getOrLoad(context, assetId, pageIndex, loadEmbeddedText)
    if (!OcrGeometry.isScannedPage(embedded.text)) return embedded
    val words = loadOcrWords()
    return if (words.isEmpty()) embedded else OcrGeometry.toTextLayer(words)
}
