package com.hashim.notepad.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import java.util.UUID

class NotePadRepository(context: Context) {
    internal val helper = NotePadDb(context.applicationContext)
    internal fun changed() = notifyChanged()
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private fun notifyChanged() { changes.tryEmit(Unit) }

    fun notebooks(): Flow<List<Notebook>> = flow {
        emit(loadNotebooks())
        changes.collect { emit(loadNotebooks()) }
    }.flowOn(Dispatchers.IO)

    fun pages(notebookId: String): Flow<List<Page>> = flow {
        emit(loadPages(notebookId))
        changes.collect { emit(loadPages(notebookId)) }
    }.flowOn(Dispatchers.IO)

    private fun loadNotebooks(): List<Notebook> = helper.readableDatabase.rawQuery(
        """select n.id, n.title, n.cover_color, n.sort_order, n.created_at, n.updated_at, n.language,
                  (select count(*) from pages p where p.notebook_id = n.id and p.deleted_at is null) as page_count
           from notebooks n where n.deleted_at is null order by n.updated_at desc""", null
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                Notebook(c.getString(0), c.getString(1), c.getStringOrNull(2), c.getString(7), c.getInt(3), c.getLong(4), c.getLong(5), c.getInt(6))
            )
        }
    }

    private fun loadPages(notebookId: String): List<Page> = helper.readableDatabase.rawQuery(
        """select id, notebook_id, sort_key, kind, asset_id, pdf_page_index, width_pt, height_pt, created_at, updated_at
           from pages where notebook_id = ? and deleted_at is null order by sort_key""", arrayOf(notebookId)
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                Page(
                    c.getString(0), c.getString(1), c.getDouble(2), PageKind.from(c.getString(3)), c.getStringOrNull(4),
                    if (c.isNull(5)) null else c.getInt(5), c.getFloat(6), c.getFloat(7), c.getLong(8), c.getLong(9)
                )
            )
        }
    }

    suspend fun page(pageId: String): Page? = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            """select id, notebook_id, sort_key, kind, asset_id, pdf_page_index, width_pt, height_pt, created_at, updated_at
               from pages where id = ?""", arrayOf(pageId)
        ).use { c ->
            if (!c.moveToFirst()) null else Page(
                c.getString(0), c.getString(1), c.getDouble(2), PageKind.from(c.getString(3)), c.getStringOrNull(4),
                if (c.isNull(5)) null else c.getInt(5), c.getFloat(6), c.getFloat(7), c.getLong(8), c.getLong(9)
            )
        }
    }

    suspend fun createNotebook(title: String, coverColor: String?): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        helper.writableDatabase.insertOrThrow("notebooks", null, ContentValues().apply {
            put("id", id); put("title", title); put("cover_color", coverColor); put("sort_order", 0)
            put("created_at", now); put("updated_at", now)
        })
        notifyChanged(); id
    }

    suspend fun setNotebookLanguage(id: String, language: String) = withContext(Dispatchers.IO) {
        helper.writableDatabase.update("notebooks", ContentValues().apply { put("language", language) }, "id = ?", arrayOf(id))
        notifyChanged()
    }

    suspend fun saveRecognition(pageId: String, source: String, language: String?, text: String) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("recognition", "page_id = ? and source = ?", arrayOf(pageId, source))
            if (text.isNotBlank()) db.insertOrThrow("recognition", null, ContentValues().apply {
                put("id", UUID.randomUUID().toString()); put("page_id", pageId); put("source", source); put("language", language)
                put("text", text); put("updated_at", System.currentTimeMillis())
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        notifyChanged()
    }

    suspend fun recognizedText(pageId: String): String? = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery("select text from recognition where page_id = ? and source = 'ink'", arrayOf(pageId)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    suspend fun search(query: String): List<SearchHit> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val like = "%" + q.replace("%", "\\%") + "%"
        helper.readableDatabase.rawQuery(
            """select r.page_id, p.notebook_id, n.title, r.text, r.source,
                      (select count(*) from pages p2 where p2.notebook_id = p.notebook_id and p2.sort_key <= p.sort_key and p2.deleted_at is null) as idx
               from recognition r join pages p on p.id = r.page_id join notebooks n on n.id = p.notebook_id
               where p.deleted_at is null and n.deleted_at is null and r.text like ? escape '\\'
               order by n.updated_at desc limit 100""", arrayOf(like)
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val text = c.getString(3)
                    val at = text.indexOf(q, ignoreCase = true).coerceAtLeast(0)
                    val snippet = text.substring((at - 40).coerceAtLeast(0), (at + q.length + 60).coerceAtMost(text.length)).replace('\n', ' ')
                    add(SearchHit(c.getString(0), c.getString(1), c.getString(2), c.getInt(5), snippet, c.getString(4)))
                }
            }
        }
    }

    suspend fun renameNotebook(id: String, title: String) = withContext(Dispatchers.IO) {
        helper.writableDatabase.update("notebooks", ContentValues().apply { put("title", title); put("updated_at", System.currentTimeMillis()) }, "id = ?", arrayOf(id))
        notifyChanged()
    }

    suspend fun deleteNotebook(id: String) = withContext(Dispatchers.IO) {
        helper.writableDatabase.update("notebooks", ContentValues().apply { put("deleted_at", System.currentTimeMillis()) }, "id = ?", arrayOf(id))
        notifyChanged()
    }

    suspend fun addPage(notebookId: String, kind: PageKind, afterSortKey: Double? = null, widthPt: Float = 595f, heightPt: Float = 842f,
                        assetId: String? = null, pdfPageIndex: Int? = null): String = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val sortKey = afterSortKey?.plus(1.0) ?: (maxSortKey(notebookId) + 1.0)
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        db.insertOrThrow("pages", null, ContentValues().apply {
            put("id", id); put("notebook_id", notebookId); put("sort_key", sortKey); put("kind", kind.db)
            put("asset_id", assetId); put("pdf_page_index", pdfPageIndex); put("width_pt", widthPt); put("height_pt", heightPt)
            put("created_at", now); put("updated_at", now)
        })
        touchNotebook(notebookId, now)
        notifyChanged(); id
    }

    /** Imports a PDF as one page per PDF page. */
    suspend fun addPdfPages(notebookId: String, asset: Asset, pageSizesPt: List<Pair<Float, Float>>) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.insertWithOnConflict("assets", null, ContentValues().apply {
                put("id", asset.id); put("kind", asset.kind); put("local_path", asset.localPath); put("byte_size", asset.byteSize)
                put("sha256", asset.sha256); put("page_count", asset.pageCount); put("name", asset.name); put("created_at", now)
            }, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
            var sortKey = maxSortKey(notebookId)
            pageSizesPt.forEachIndexed { index, (w, h) ->
                sortKey += 1.0
                db.insertOrThrow("pages", null, ContentValues().apply {
                    put("id", UUID.randomUUID().toString()); put("notebook_id", notebookId); put("sort_key", sortKey); put("kind", PageKind.PDF.db)
                    put("asset_id", asset.id); put("pdf_page_index", index); put("width_pt", w); put("height_pt", h)
                    put("created_at", now); put("updated_at", now)
                })
            }
            touchNotebook(notebookId, now)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        notifyChanged()
    }

    suspend fun asset(id: String): Asset? = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery("select id, kind, local_path, byte_size, sha256, page_count, name from assets where id = ?", arrayOf(id)).use { c ->
            if (!c.moveToFirst()) null else Asset(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4), if (c.isNull(5)) null else c.getInt(5), c.getStringOrNull(6))
        }
    }

    suspend fun loadStrokes(pageId: String, layer: Int = 0): ByteArray? = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery("select data from stroke_batches where page_id = ? and layer = ?", arrayOf(pageId, layer.toString())).use { c ->
            if (c.moveToFirst()) c.getBlob(0) else null
        }
    }

    suspend fun saveStrokes(pageId: String, data: ByteArray, strokeCount: Int, layer: Int = 0) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        db.execSQL(
            """insert into stroke_batches(id, page_id, layer, revision, stroke_count, data, updated_at)
               values (?, ?, ?, 1, ?, ?, ?)
               on conflict(page_id, layer) do update set revision = revision + 1, stroke_count = excluded.stroke_count,
               data = excluded.data, updated_at = excluded.updated_at""",
            arrayOf<Any?>(UUID.randomUUID().toString(), pageId, layer, strokeCount, data, now)
        )
        db.execSQL("update pages set updated_at = ? where id = ?", arrayOf<Any?>(now, pageId))
        notifyChanged()
    }

    private fun maxSortKey(notebookId: String): Double =
        helper.readableDatabase.rawQuery("select coalesce(max(sort_key), 0) from pages where notebook_id = ?", arrayOf(notebookId)).use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 }

    private fun touchNotebook(id: String, now: Long) {
        helper.writableDatabase.execSQL("update notebooks set updated_at = ? where id = ?", arrayOf<Any?>(now, id))
    }
}

private fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
