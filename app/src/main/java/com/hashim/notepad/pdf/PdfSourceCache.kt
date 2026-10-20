package com.hashim.notepad.pdf

import android.content.Context
import java.io.File

/**
 * Keeps a few PDFium documents open across renders so scrolling a book does not re-parse the
 * file for every page. All access must run on [PdfiumDispatcher]; the lock only guards the map.
 */
object PdfSourceCache {
    private const val MAX_OPEN = 3

    @PublishedApi
    internal class Entry(val source: PdfPageSource, val lastModified: Long, val length: Long) {
        var inUse = 0
        var lastUsed = System.nanoTime()
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    inline fun <T> withSource(context: Context, file: File, password: String? = null, block: (PdfPageSource) -> T): T {
        val entry = acquire(context, file, password)
        try {
            return block(entry.source)
        } finally {
            release(entry)
        }
    }

    fun invalidate(file: File) {
        synchronized(lock) {
            val stale = entries.filterKeys { it.startsWith(file.absolutePath + "\u0000") }
            stale.forEach { (key, entry) ->
                entries.remove(key)
                if (entry.inUse == 0) runCatching { entry.source.close() }
            }
        }
    }

    @PublishedApi
    internal fun acquire(context: Context, file: File, password: String?): Entry {
        val key = file.absolutePath + "\u0000" + password.orEmpty().hashCode()
        val modified = file.lastModified()
        val length = file.length()
        synchronized(lock) {
            val existing = entries[key]
            if (existing != null) {
                if (existing.lastModified == modified && existing.length == length) {
                    existing.inUse++
                    existing.lastUsed = System.nanoTime()
                    return existing
                }
                entries.remove(key)
                if (existing.inUse == 0) runCatching { existing.source.close() }
            }
        }
        val opened = Entry(PdfPageSource(context.applicationContext, file, password), modified, length)
        opened.inUse = 1
        synchronized(lock) {
            entries[key] = opened
            trimLocked()
        }
        return opened
    }

    @PublishedApi
    internal fun release(entry: Entry) {
        synchronized(lock) {
            entry.inUse--
            entry.lastUsed = System.nanoTime()
            if (entry.inUse == 0 && !entries.containsValue(entry)) runCatching { entry.source.close() }
            trimLocked()
        }
    }

    private fun trimLocked() {
        while (entries.size > MAX_OPEN) {
            val victim = entries.entries
                .filter { it.value.inUse == 0 }
                .minByOrNull { it.value.lastUsed } ?: return
            entries.remove(victim.key)
            runCatching { victim.value.source.close() }
        }
    }
}
