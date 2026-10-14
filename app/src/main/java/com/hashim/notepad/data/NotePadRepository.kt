package com.hashim.notepad.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import com.hashim.notepad.search.TextNormalizer
import com.hashim.notepad.search.LibrarySearch
import com.hashim.notepad.search.LibrarySearchDocument
import org.json.JSONArray
import java.util.UUID

private data class StoredStrokeBatch(
    val layer: Int,
    val revision: Int,
    val strokeCount: Int,
    val data: ByteArray,
)

private data class StoredTextBlock(
    val x: Float,
    val y: Float,
    val width: Float,
    val direction: String,
    val content: String,
    val rotationDegrees: Float,
)

class NotePadRepository(context: Context, databaseName: String = "notepad.db") {
    internal val helper = NotePadDb(context.applicationContext ?: context, databaseName)
    internal fun changed() = notifyChanged()
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private fun notifyChanged() { changes.tryEmit(Unit) }

    /** Queues a row for the next sync push. */
    private fun mark(table: String, rowId: String) {
        helper.writableDatabase.execSQL("insert or ignore into outbox(table_name, row_id) values (?, ?)", arrayOf(table, rowId))
        onLocalChange?.invoke()
    }

    /** Set by the sync layer to schedule a push after local edits. */
    @Volatile var onLocalChange: (() -> Unit)? = null

    companion object {
        @Volatile private var instance: NotePadRepository? = null
        fun get(context: Context): NotePadRepository =
            instance ?: synchronized(this) { instance ?: NotePadRepository(context.applicationContext).also { instance = it } }
    }

    fun notebooks(): Flow<List<Notebook>> = flow {
        emit(loadNotebooks())
        changes.collect { emit(loadNotebooks()) }
    }.flowOn(Dispatchers.IO)

    fun folders(): Flow<List<Folder>> = flow {
        emit(loadFolders())
        changes.collect { emit(loadFolders()) }
    }.flowOn(Dispatchers.IO)

    fun tags(): Flow<List<Tag>> = flow {
        emit(loadTags())
        changes.collect { emit(loadTags()) }
    }.flowOn(Dispatchers.IO)

    fun tagsForNotebook(notebookId: String): Flow<List<Tag>> = flow {
        emit(loadTagsForNotebook(notebookId))
        changes.collect { emit(loadTagsForNotebook(notebookId)) }
    }.flowOn(Dispatchers.IO)

    fun pages(notebookId: String): Flow<List<Page>> = flow {
        emit(loadPages(notebookId))
        changes.collect { emit(loadPages(notebookId)) }
    }.flowOn(Dispatchers.IO)

    private fun loadNotebooks(): List<Notebook> = helper.readableDatabase.rawQuery(
        """select n.id, n.title, n.cover_color, n.sort_order, n.created_at, n.updated_at, n.language,
                  (select count(*) from pages p where p.notebook_id = n.id and p.deleted_at is null) as page_count,
                  n.folder_id,
                  (select group_concat(nt.tag_id, ',') from notebook_tags nt where nt.notebook_id = n.id and nt.deleted_at is null) as tag_ids,
                  n.document_kind, n.last_page_index, n.last_opened_at, n.file_asset_id, n.file_mime_type, n.original_filename
           from notebooks n where n.deleted_at is null order by n.updated_at desc""", null
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                Notebook(
                    c.getString(0), c.getString(1), c.getStringOrNull(2), c.getString(6), c.getInt(3),
                    c.getLong(4), c.getLong(5), c.getInt(7), c.getStringOrNull(8),
                    c.getStringOrNull(9)?.split(',')?.filter(String::isNotBlank)?.toSet().orEmpty(),
                    c.getString(10), c.getInt(11), if (c.isNull(12)) null else c.getLong(12),
                    c.getStringOrNull(13), c.getStringOrNull(14), c.getStringOrNull(15),
                )
            )
        }
    }

    private fun loadFolders(): List<Folder> = helper.readableDatabase.rawQuery(
        "select id, name, color, sort_order, created_at, updated_at from folders where deleted_at is null order by sort_order, created_at, name collate nocase",
        null,
    ).use { c ->
        buildList { while (c.moveToNext()) add(Folder(c.getString(0), c.getString(1), c.getStringOrNull(2), c.getInt(3), c.getLong(4), c.getLong(5))) }
    }

    private fun loadTags(): List<Tag> = helper.readableDatabase.rawQuery(
        "select id, name, color, created_at, updated_at from tags where deleted_at is null order by name collate nocase",
        null,
    ).use { c ->
        buildList { while (c.moveToNext()) add(Tag(c.getString(0), c.getString(1), c.getStringOrNull(2), c.getLong(3), c.getLong(4))) }
    }

    private fun loadTagsForNotebook(notebookId: String): List<Tag> = helper.readableDatabase.rawQuery(
        """select t.id, t.name, t.color, t.created_at, t.updated_at from tags t
           join notebook_tags nt on nt.tag_id = t.id
           where nt.notebook_id = ? and nt.deleted_at is null and t.deleted_at is null
           order by t.name collate nocase""",
        arrayOf(notebookId),
    ).use { c ->
        buildList { while (c.moveToNext()) add(Tag(c.getString(0), c.getString(1), c.getStringOrNull(2), c.getLong(3), c.getLong(4))) }
    }

    private fun loadPages(notebookId: String): List<Page> = helper.readableDatabase.rawQuery(
        """select id, notebook_id, sort_key, kind, asset_id, pdf_page_index, width_pt, height_pt, created_at, updated_at, bookmarked, rotation
           from pages where notebook_id = ? and deleted_at is null order by sort_key""", arrayOf(notebookId)
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                Page(
                    c.getString(0), c.getString(1), c.getDouble(2), PageKind.from(c.getString(3)), c.getStringOrNull(4),
                    if (c.isNull(5)) null else c.getInt(5), c.getFloat(6), c.getFloat(7), c.getLong(8), c.getLong(9), c.getInt(10) != 0, c.getInt(11)
                )
            )
        }
    }

    suspend fun page(pageId: String): Page? = withContext(Dispatchers.IO) {
        val database = helper.readableDatabase
        database.rawQuery(
            """select id, notebook_id, sort_key, kind, asset_id, pdf_page_index, width_pt, height_pt, created_at, updated_at, bookmarked, rotation
               from pages where id = ?""", arrayOf(pageId)
        ).use { c ->
            if (!c.moveToFirst()) null else Page(
                c.getString(0), c.getString(1), c.getDouble(2), PageKind.from(c.getString(3)), c.getStringOrNull(4),
                if (c.isNull(5)) null else c.getInt(5), c.getFloat(6), c.getFloat(7), c.getLong(8), c.getLong(9), c.getInt(10) != 0, c.getInt(11)
            )
        }
    }

    fun pdfMarks(notebookId: String): Flow<List<PdfMark>> = flow {
        emit(loadPdfMarks(notebookId))
        changes.collect { emit(loadPdfMarks(notebookId)) }
    }.flowOn(Dispatchers.IO)

    private fun loadPdfMarks(notebookId: String): List<PdfMark> = helper.readableDatabase.rawQuery(
        "select id, notebook_id, page_id, kind, color_argb, quads, char_start, char_end, text, note, created_at, updated_at, deleted from pdf_marks where notebook_id = ? and deleted = 0 order by page_id, created_at",
        arrayOf(notebookId),
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                PdfMark(
                    c.getString(0), c.getString(1), c.getString(2), PdfMarkKind.from(c.getString(3)), c.getInt(4),
                    JSONArray(c.getString(5)).let { json -> List(json.length()) { i -> json.getDouble(i).toFloat() } },
                    if (c.isNull(6)) null else c.getInt(6), if (c.isNull(7)) null else c.getInt(7),
                    c.getString(8), c.getStringOrNull(9), c.getLong(10), c.getLong(11), c.getInt(12) != 0,
                ),
            )
        }
    }

    suspend fun savePdfMark(mark: PdfMark) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.insertWithOnConflict("pdf_marks", null, ContentValues().apply {
            put("id", mark.id); put("notebook_id", mark.notebookId); put("page_id", mark.pageId)
            put("kind", mark.kind.db); put("color_argb", mark.colorArgb)
            put("quads", JSONArray(mark.quads.toList()).toString())
            if (mark.charStart == null) putNull("char_start") else put("char_start", mark.charStart)
            if (mark.charEnd == null) putNull("char_end") else put("char_end", mark.charEnd)
            put("text", mark.text); if (mark.note == null) putNull("note") else put("note", mark.note)
            put("created_at", mark.createdAt); put("updated_at", mark.updatedAt); put("deleted", if (mark.deleted) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        mark("pdf_marks", mark.id); notifyChanged()
    }

    suspend fun deletePdfMark(id: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        helper.writableDatabase.update("pdf_marks", ContentValues().apply { put("deleted", 1); put("updated_at", now) }, "id = ?", arrayOf(id))
        mark("pdf_marks", id); notifyChanged()
    }

    suspend fun setPageRotation(pageId: String, rotation: Int) = withContext(Dispatchers.IO) {
        val value = ((rotation % 360) + 360) % 360
        require(value % 90 == 0) { "rotation must be a right angle" }
        helper.writableDatabase.update("pages", ContentValues().apply { put("rotation", value); put("updated_at", System.currentTimeMillis()) }, "id = ?", arrayOf(pageId))
        mark("pages", pageId); notifyChanged()
    }

    suspend fun splitPair(notebookId: String): SplitPair? = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            """select id, left_notebook_id, right_notebook_id, divider_ratio, swapped
               from split_pairs where left_notebook_id = ? or right_notebook_id = ?
               order by updated_at desc limit 1""",
            arrayOf(notebookId, notebookId),
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                SplitPair(
                    cursor.getString(0),
                    cursor.getString(1),
                    cursor.getString(2),
                    cursor.getFloat(3),
                    cursor.getInt(4) != 0,
                )
            } else null
        }
    }

    suspend fun saveSplitPair(
        leftNotebookId: String,
        rightNotebookId: String,
        dividerRatio: Float = 0.5f,
        swapped: Boolean = false,
    ): SplitPair = withContext(Dispatchers.IO) {
        require(leftNotebookId != rightNotebookId)
        val database = helper.writableDatabase
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        database.beginTransaction()
        try {
            database.delete(
                "split_pairs",
                "left_notebook_id in (?, ?) or right_notebook_id in (?, ?)",
                arrayOf(leftNotebookId, rightNotebookId, leftNotebookId, rightNotebookId),
            )
            database.insertOrThrow("split_pairs", null, ContentValues().apply {
                put("id", id)
                put("left_notebook_id", leftNotebookId)
                put("right_notebook_id", rightNotebookId)
                put("divider_ratio", dividerRatio.coerceIn(0.33f, 0.67f))
                put("swapped", if (swapped) 1 else 0)
                put("updated_at", now)
            })
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        SplitPair(id, leftNotebookId, rightNotebookId, dividerRatio.coerceIn(0.33f, 0.67f), swapped)
    }

    suspend fun updateSplitPair(id: String, dividerRatio: Float, swapped: Boolean) = withContext(Dispatchers.IO) {
        helper.writableDatabase.update(
            "split_pairs",
            ContentValues().apply {
                put("divider_ratio", dividerRatio.coerceIn(0.33f, 0.67f))
                put("swapped", if (swapped) 1 else 0)
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(id),
        )
    }

    suspend fun setPageBookmarked(pageId: String, bookmarked: Boolean) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        helper.writableDatabase.update(
            "pages",
            ContentValues().apply { put("bookmarked", if (bookmarked) 1 else 0); put("updated_at", now) },
            "id = ? and deleted_at is null",
            arrayOf(pageId),
        )
        mark("pages", pageId)
        notifyChanged()
    }

    suspend fun createNotebook(title: String, coverColor: String?, folderId: String? = null): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        helper.writableDatabase.insertOrThrow("notebooks", null, ContentValues().apply {
            put("id", id); put("title", title); put("cover_color", coverColor); put("sort_order", 0); put("folder_id", folderId)
            put("document_kind", "notes")
            put("created_at", now); put("updated_at", now)
        })
        mark("notebooks", id); notifyChanged(); id
    }

    suspend fun createFolder(name: String, color: String? = null): String = withContext(Dispatchers.IO) {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Folder name cannot be empty" }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        helper.writableDatabase.insertOrThrow("folders", null, ContentValues().apply {
            put("id", id); put("name", cleanName); put("color", color); put("sort_order", 0)
            put("created_at", now); put("updated_at", now)
        })
        mark("folders", id); notifyChanged(); id
    }

    suspend fun renameFolder(id: String, name: String, color: String? = null) = withContext(Dispatchers.IO) {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Folder name cannot be empty" }
        val now = System.currentTimeMillis()
        helper.writableDatabase.update("folders", ContentValues().apply { put("name", cleanName); put("color", color); put("updated_at", now) }, "id = ? and deleted_at is null", arrayOf(id))
        mark("folders", id); notifyChanged()
    }

    suspend fun deleteFolder(id: String) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val notebooks = db.rawQuery("select id from notebooks where folder_id = ?", arrayOf(id)).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
            db.update("notebooks", ContentValues().apply { putNull("folder_id"); put("updated_at", now) }, "folder_id = ?", arrayOf(id))
            notebooks.forEach { mark("notebooks", it) }
            db.update("folders", ContentValues().apply { put("deleted_at", now); put("updated_at", now) }, "id = ?", arrayOf(id))
            mark("folders", id)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
    }

    suspend fun moveNotebookToFolder(notebookId: String, folderId: String?) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        if (folderId != null) {
            val active = db.rawQuery("select 1 from folders where id = ? and deleted_at is null", arrayOf(folderId)).use { it.moveToFirst() }
            require(active) { "Folder does not exist" }
        }
        val now = System.currentTimeMillis()
        db.update("notebooks", ContentValues().apply {
            if (folderId == null) putNull("folder_id") else put("folder_id", folderId)
            put("updated_at", now)
        }, "id = ?", arrayOf(notebookId))
        mark("notebooks", notebookId); notifyChanged()
    }

    suspend fun setReadingPosition(notebookId: String, pageIndex: Int) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        db.update("notebooks", ContentValues().apply {
            put("last_page_index", pageIndex.coerceAtLeast(0))
            put("last_opened_at", now)
        }, "id = ? and deleted_at is null", arrayOf(notebookId))
        mark("notebooks", notebookId)
        notifyChanged()
    }

    suspend fun createPdfNotebook(title: String, asset: Asset, pageSizesPt: List<Pair<Float, Float>>, folderId: String?): String =
        withContext(Dispatchers.IO) {
            require(pageSizesPt.isNotEmpty()) { "PDF has no pages" }
            val db = helper.writableDatabase
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            db.beginTransaction()
            try {
                db.insertOrThrow("notebooks", null, ContentValues().apply {
                    put("id", id); put("title", title); put("folder_id", folderId); put("document_kind", "pdf")
                    put("cover_color", "#EEE9DF"); put("sort_order", 0); put("created_at", now); put("updated_at", now)
                })
                mark("notebooks", id)
                insertAsset(db, asset, now)
                var sortKey = 0.0
                pageSizesPt.forEachIndexed { index, (width, height) ->
                    sortKey += 1.0
                    val pageId = UUID.randomUUID().toString()
                    db.insertOrThrow("pages", null, ContentValues().apply {
                        put("id", pageId); put("notebook_id", id); put("sort_key", sortKey); put("kind", PageKind.PDF.db)
                        put("asset_id", asset.id); put("pdf_page_index", index); put("width_pt", width); put("height_pt", height)
                        put("created_at", now); put("updated_at", now)
                    })
                    mark("pages", pageId)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            notifyChanged()
            id
        }

    suspend fun createFileNotebook(title: String, asset: Asset, mimeType: String, originalFilename: String, folderId: String?): String =
        withContext(Dispatchers.IO) {
            val db = helper.writableDatabase
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            db.beginTransaction()
            try {
                db.insertOrThrow("notebooks", null, ContentValues().apply {
                    put("id", id); put("title", title); put("folder_id", folderId); put("document_kind", "file")
                    put("file_asset_id", asset.id); put("file_mime_type", mimeType); put("original_filename", originalFilename)
                    put("sort_order", 0); put("created_at", now); put("updated_at", now)
                })
                mark("notebooks", id)
                insertAsset(db, asset, now)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            notifyChanged()
            id
        }

    suspend fun createImageNotebook(title: String, images: List<ImportedImageContent>, folderId: String?): String =
        withContext(Dispatchers.IO) {
            require(images.isNotEmpty()) { "No images selected" }
            val db = helper.writableDatabase
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            db.beginTransaction()
            try {
                db.insertOrThrow("notebooks", null, ContentValues().apply {
                    put("id", id); put("title", title); put("folder_id", folderId); put("document_kind", "notes")
                    put("cover_color", "#F1E8D8"); put("sort_order", 0); put("created_at", now); put("updated_at", now)
                })
                mark("notebooks", id)
                images.forEachIndexed { index, image ->
                    insertAsset(db, image.asset, now)
                    val pageId = UUID.randomUUID().toString()
                    db.insertOrThrow("pages", null, ContentValues().apply {
                        put("id", pageId); put("notebook_id", id); put("sort_key", index + 1.0); put("kind", PageKind.BLANK.db)
                        put("width_pt", 595f); put("height_pt", 842f); put("created_at", now); put("updated_at", now)
                    })
                    mark("pages", pageId)
                    val imageId = UUID.randomUUID().toString()
                    db.insertOrThrow("page_images", null, ContentValues().apply {
                        put("id", imageId); put("page_id", pageId); put("asset_id", image.asset.id)
                        put("x_pt", image.xPt); put("y_pt", image.yPt); put("w_pt", image.widthPt); put("h_pt", image.heightPt)
                        put("updated_at", now)
                    })
                    mark("page_images", imageId)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            notifyChanged()
            id
        }

    private fun insertAsset(db: SQLiteDatabase, asset: Asset, now: Long) {
        db.insertOrThrow("assets", null, ContentValues().apply {
            put("id", asset.id); put("kind", asset.kind); put("local_path", asset.localPath); put("byte_size", asset.byteSize)
            put("sha256", asset.sha256); if (asset.pageCount == null) putNull("page_count") else put("page_count", asset.pageCount)
            put("name", asset.name); put("created_at", now)
        })
        mark("assets", asset.id)
    }

    suspend fun createTag(name: String): String = withContext(Dispatchers.IO) {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Tag name cannot be empty" }
        val db = helper.writableDatabase
        db.beginTransaction()
        val id = try {
            val existing = db.rawQuery(
                "select id from tags where name = ? collate nocase and deleted_at is null limit 1",
                arrayOf(cleanName),
            ).use { if (it.moveToFirst()) it.getString(0) else null }
            if (existing != null) {
                db.setTransactionSuccessful()
                return@withContext existing
            }
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            db.insertOrThrow("tags", null, ContentValues().apply {
                put("id", id); put("name", cleanName); putNull("color"); put("created_at", now); put("updated_at", now)
            })
            mark("tags", id)
            db.setTransactionSuccessful()
            id
        } finally {
            db.endTransaction()
        }
        notifyChanged()
        id
    }

    suspend fun deleteTag(id: String) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val links = db.rawQuery("select id from notebook_tags where tag_id = ? and deleted_at is null", arrayOf(id)).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
            db.update("notebook_tags", ContentValues().apply { put("deleted_at", now); put("updated_at", now) }, "tag_id = ? and deleted_at is null", arrayOf(id))
            links.forEach { mark("notebook_tags", it) }
            db.update("tags", ContentValues().apply { put("deleted_at", now); put("updated_at", now) }, "id = ?", arrayOf(id))
            mark("tags", id)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
    }

    suspend fun setNotebookTags(notebookId: String, tagIds: Set<String>) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val activeTags = db.rawQuery("select id from tags where deleted_at is null", null).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(0)) }
            }
            require(activeTags.containsAll(tagIds)) { "A selected tag no longer exists" }
            data class Link(val id: String, val tagId: String, val deleted: Boolean)
            val links = db.rawQuery(
                "select id, tag_id, deleted_at from notebook_tags where notebook_id = ?",
                arrayOf(notebookId),
            ).use { c ->
                buildList { while (c.moveToNext()) add(Link(c.getString(0), c.getString(1), !c.isNull(2))) }
            }
            val now = System.currentTimeMillis()
            links.filter { !it.deleted && it.tagId !in tagIds }.forEach { link ->
                db.update("notebook_tags", ContentValues().apply { put("deleted_at", now); put("updated_at", now) }, "id = ?", arrayOf(link.id))
                mark("notebook_tags", link.id)
            }
            tagIds.forEach { tagId ->
                val link = links.firstOrNull { it.tagId == tagId }
                if (link == null) {
                    val id = UUID.randomUUID().toString()
                    db.insertOrThrow("notebook_tags", null, ContentValues().apply {
                        put("id", id); put("notebook_id", notebookId); put("tag_id", tagId)
                        put("updated_at", now); putNull("deleted_at")
                    })
                    mark("notebook_tags", id)
                } else if (link.deleted) {
                    db.update("notebook_tags", ContentValues().apply { putNull("deleted_at"); put("updated_at", now) }, "id = ?", arrayOf(link.id))
                    mark("notebook_tags", link.id)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
    }

    fun textBlocks(pageId: String): Flow<List<TextBlock>> = flow {
        emit(loadTextBlocks(pageId))
        changes.collect { emit(loadTextBlocks(pageId)) }
    }.flowOn(Dispatchers.IO)

    fun pageImages(pageId: String): Flow<List<PageImage>> = flow {
        emit(loadPageImages(pageId))
        changes.collect { emit(loadPageImages(pageId)) }
    }.flowOn(Dispatchers.IO)

    private fun loadPageImages(pageId: String): List<PageImage> = helper.readableDatabase.rawQuery(
        """select i.id, i.page_id, i.asset_id, i.x_pt, i.y_pt, i.w_pt, i.h_pt, i.updated_at, a.local_path
           from page_images i join assets a on a.id = i.asset_id
           where i.page_id = ? and i.deleted_at is null order by i.updated_at, i.id""",
        arrayOf(pageId),
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                PageImage(c.getString(0), c.getString(1), c.getString(2), c.getFloat(3), c.getFloat(4),
                    c.getFloat(5), c.getFloat(6), c.getLong(7), c.getString(8))
            )
        }
    }

    private fun loadTextBlocks(pageId: String): List<TextBlock> = helper.readableDatabase.rawQuery(
        "select id, page_id, x_pt, y_pt, w_pt, direction, content, updated_at, font_size_pt, color_argb, rotation_degrees from text_blocks where page_id = ? and deleted_at is null order by y_pt", arrayOf(pageId)
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                TextBlock(
                    c.getString(0), c.getString(1), c.getFloat(2), c.getFloat(3), c.getFloat(4),
                    c.getString(5), c.getString(6), c.getLong(7), c.getFloat(8),
                    if (c.isNull(9)) null else c.getInt(9),
                    c.getFloat(10),
                ),
            )
        }
    }

    suspend fun addTextBlock(
        pageId: String,
        xPt: Float,
        yPt: Float,
        wPt: Float,
        content: String,
        fontSizePt: Float = 12f,
        colorArgb: Int? = null,
        rotationDegrees: Float = 0f,
    ): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        helper.writableDatabase.insertOrThrow("text_blocks", null, ContentValues().apply {
            put("id", id); put("page_id", pageId); put("x_pt", xPt); put("y_pt", yPt); put("w_pt", wPt); put("direction", "auto"); put("content", content); put("font_size_pt", fontSizePt)
            if (colorArgb == null) putNull("color_argb") else put("color_argb", colorArgb)
            put("rotation_degrees", rotationDegrees)
            put("updated_at", System.currentTimeMillis())
        })
        mark("text_blocks", id); indexTextBlocks(pageId); notifyChanged(); id
    }

    suspend fun replacePageTextBlocks(pageId: String, blocks: List<TextBlock>) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        val retainedIds = blocks.mapTo(hashSetOf()) { it.id }
        db.beginTransaction()
        try {
            val removedIds = db.rawQuery(
                "select id from text_blocks where page_id = ? and deleted_at is null",
                arrayOf(pageId),
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) }.filterNot(retainedIds::contains) }
            removedIds.forEach { id ->
                db.update("text_blocks", ContentValues().apply { put("deleted_at", now); put("updated_at", now) }, "id = ?", arrayOf(id))
                mark("text_blocks", id)
            }
            blocks.forEach { block ->
                db.insertWithOnConflict("text_blocks", null, ContentValues().apply {
                    put("id", block.id); put("page_id", pageId); put("x_pt", block.xPt); put("y_pt", block.yPt)
                    put("w_pt", block.wPt); put("direction", block.direction); put("content", block.content)
                    put("font_size_pt", block.fontSizePt)
                    if (block.colorArgb == null) putNull("color_argb") else put("color_argb", block.colorArgb)
                    put("rotation_degrees", block.rotationDegrees)
                    putNull("deleted_at"); put("updated_at", now)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                mark("text_blocks", block.id)
            }
            indexTextBlocks(pageId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
    }

    suspend fun updateTextBlock(
        id: String,
        pageId: String,
        content: String? = null,
        xPt: Float? = null,
        yPt: Float? = null,
        rotationDegrees: Float? = null,
    ) = withContext(Dispatchers.IO) {
        helper.writableDatabase.update("text_blocks", ContentValues().apply {
            content?.let { put("content", it) }
            xPt?.let { put("x_pt", it) }
            yPt?.let { put("y_pt", it) }
            rotationDegrees?.let { put("rotation_degrees", it) }
            put("updated_at", System.currentTimeMillis())
        }, "id = ?", arrayOf(id))
        mark("text_blocks", id); indexTextBlocks(pageId); notifyChanged()
    }

    suspend fun deleteTextBlock(id: String, pageId: String) = withContext(Dispatchers.IO) {
        helper.writableDatabase.update("text_blocks", ContentValues().apply { put("deleted_at", System.currentTimeMillis()); put("updated_at", System.currentTimeMillis()) }, "id = ?", arrayOf(id))
        mark("text_blocks", id); indexTextBlocks(pageId); notifyChanged()
    }

    /** Keeps typed text searchable alongside recognised handwriting. */
    private fun indexTextBlocks(pageId: String) {
        val db = helper.writableDatabase
        val text = loadTextBlocks(pageId).joinToString("\n") { it.content }
        db.delete("recognition", "page_id = ? and source = 'text'", arrayOf(pageId))
        if (text.isNotBlank()) db.insertOrThrow("recognition", null, ContentValues().apply {
            put("id", UUID.randomUUID().toString()); put("page_id", pageId); put("source", "text"); put("text", text); put("updated_at", System.currentTimeMillis())
        })
        mark("recognition", pageId)
    }

    suspend fun setNotebookPaper(id: String, hex: String) = withContext(Dispatchers.IO) {
        helper.writableDatabase.update("notebooks", ContentValues().apply { put("cover_color", hex); put("updated_at", System.currentTimeMillis()) }, "id = ?", arrayOf(id))
        mark("notebooks", id); notifyChanged()
    }

    suspend fun setNotebookLanguage(id: String, language: String) = withContext(Dispatchers.IO) {
        helper.writableDatabase.update("notebooks", ContentValues().apply { put("language", language); put("updated_at", System.currentTimeMillis()) }, "id = ?", arrayOf(id))
        mark("notebooks", id); notifyChanged()
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
        mark("recognition", pageId); notifyChanged()
    }

    suspend fun ocrWords(pageId: String): List<OcrWord> = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "select page_id, word_index, text, left_pt, top_pt, right_pt, bottom_pt, confidence from ocr_words where page_id = ? order by word_index",
            arrayOf(pageId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        OcrWord(
                            cursor.getString(0),
                            cursor.getInt(1),
                            cursor.getString(2),
                            cursor.getFloat(3),
                            cursor.getFloat(4),
                            cursor.getFloat(5),
                            cursor.getFloat(6),
                            cursor.getFloat(7),
                        ),
                    )
                }
            }
        }
    }

    suspend fun replaceOcrWords(pageId: String, words: List<OcrWord>, language: String) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("ocr_words", "page_id = ?", arrayOf(pageId))
            words.sortedBy(OcrWord::index).forEachIndexed { index, word ->
                db.insertOrThrow(
                    "ocr_words",
                    null,
                    ContentValues().apply {
                        put("page_id", pageId)
                        put("word_index", index)
                        put("text", word.text)
                        put("left_pt", word.left)
                        put("top_pt", word.top)
                        put("right_pt", word.right)
                        put("bottom_pt", word.bottom)
                        put("confidence", word.confidence)
                    },
                )
            }
            db.delete("recognition", "page_id = ? and source = 'ocr'", arrayOf(pageId))
            val recognizedText = words.sortedBy(OcrWord::index).joinToString(" ") { it.text }
            if (recognizedText.isNotBlank()) {
                db.insertOrThrow(
                    "recognition",
                    null,
                    ContentValues().apply {
                        put("id", UUID.randomUUID().toString())
                        put("page_id", pageId)
                        put("source", "ocr")
                        put("language", language)
                        put("text", recognizedText)
                        put("updated_at", System.currentTimeMillis())
                    },
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
    }

    suspend fun recognizedText(pageId: String): String? = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery("select text from recognition where page_id = ? and source = 'ink'", arrayOf(pageId)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    suspend fun search(query: String): List<SearchHit> = withContext(Dispatchers.IO) {
        val q = TextNormalizer.normalize(query).text
        if (q.isEmpty()) return@withContext emptyList()
        val database = helper.readableDatabase
        val titleMatches = LibrarySearch.findMatches(
            query,
            loadNotebooks().map { LibrarySearchDocument(it.id, it.title, it.originalFilename) },
        )
        val firstPageByNotebook = database.rawQuery(
            """select p.id, p.notebook_id,
                      (select count(*) from pages p2 where p2.notebook_id = p.notebook_id
                       and p2.sort_key <= p.sort_key and p2.deleted_at is null) as idx
               from pages p where p.deleted_at is null order by p.notebook_id, p.sort_key""",
            emptyArray(),
        ).use { c ->
            buildMap {
                while (c.moveToNext()) putIfAbsent(c.getString(1), c.getString(0) to c.getInt(2))
            }
        }
        val titleHits = titleMatches.mapNotNull { match ->
            val page = firstPageByNotebook[match.document.notebookId] ?: return@mapNotNull null
            SearchHit(
                pageId = page.first,
                notebookId = match.document.notebookId,
                notebookTitle = match.document.title,
                pageIndex = page.second,
                snippet = match.snippet,
                source = match.source,
            )
        }
        val recognitionHits = database.rawQuery(
            """select r.page_id, p.notebook_id, n.title, r.text, r.source,
                      (select count(*) from pages p2 where p2.notebook_id = p.notebook_id and p2.sort_key <= p.sort_key and p2.deleted_at is null) as idx
               from recognition r join pages p on p.id = r.page_id join notebooks n on n.id = p.notebook_id
               where p.deleted_at is null and n.deleted_at is null
               order by n.updated_at desc limit 2000""", emptyArray()
        ).use { c ->
            buildList {
                while (c.moveToNext() && titleHits.size + size < 100) {
                    val text = c.getString(3)
                    val normalized = TextNormalizer.normalize(text)
                    val at = normalized.text.indexOf(q)
                    if (at >= 0) {
                        val (start, end) = normalized.sourceRange(text, at, at + q.length)
                        val snippet = text.substring((start - 40).coerceAtLeast(0), (end + 60).coerceAtMost(text.length)).replace('\n', ' ')
                        add(SearchHit(c.getString(0), c.getString(1), c.getString(2), c.getInt(5), snippet, c.getString(4)))
                        if (size == 100) break
                    }
                }
            }
        }
        if (titleHits.size + recognitionHits.size >= 100) {
            return@withContext (titleHits + recognitionHits).take(100)
        }
        val markHits = database.rawQuery(
            """select m.page_id, p.notebook_id, n.title, m.text, m.note,
                      (select count(*) from pages p2 where p2.notebook_id = p.notebook_id and p2.sort_key <= p.sort_key and p2.deleted_at is null) as idx
               from pdf_marks m join pages p on p.id = m.page_id join notebooks n on n.id = p.notebook_id
               where m.deleted = 0 and p.deleted_at is null and n.deleted_at is null
               order by m.updated_at desc limit 1000""",
            emptyArray(),
        ).use { c ->
            buildList {
                while (c.moveToNext() && titleHits.size + recognitionHits.size + size < 100) {
                    val content = listOf(c.getString(3), c.getString(4).orEmpty()).filter(String::isNotBlank).joinToString(" ")
                    val normalized = TextNormalizer.normalize(content)
                    val at = normalized.text.indexOf(q)
                    if (at >= 0) {
                        val (start, end) = normalized.sourceRange(content, at, at + q.length)
                        val snippet = content.substring((start - 40).coerceAtLeast(0), (end + 60).coerceAtMost(content.length))
                            .replace('\n', ' ')
                        add(SearchHit(c.getString(0), c.getString(1), c.getString(2), c.getInt(5), snippet, "pdf_mark"))
                    }
                }
            }
        }
        return@withContext (titleHits + recognitionHits + markHits).take(100)
    }

    suspend fun renameNotebook(id: String, title: String) = withContext(Dispatchers.IO) {
        helper.writableDatabase.update("notebooks", ContentValues().apply { put("title", title); put("updated_at", System.currentTimeMillis()) }, "id = ?", arrayOf(id))
        mark("notebooks", id); notifyChanged()
    }

    suspend fun deleteNotebook(id: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        helper.writableDatabase.update("notebooks", ContentValues().apply { put("deleted_at", now); put("updated_at", now) }, "id = ?", arrayOf(id))
        mark("notebooks", id); notifyChanged()
    }

    suspend fun deletePage(pageId: String) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val notebookId = db.rawQuery(
                "select notebook_id from pages where id = ? and deleted_at is null",
                arrayOf(pageId),
            ).use { if (it.moveToFirst()) it.getString(0) else null }
            val parentId = notebookId ?: return@withContext

            db.update("pages", ContentValues().apply { put("deleted_at", now); put("updated_at", now) }, "id = ?", arrayOf(pageId))
            mark("pages", pageId)

            val textBlockIds = db.rawQuery(
                "select id from text_blocks where page_id = ? and deleted_at is null",
                arrayOf(pageId),
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            db.update("text_blocks", ContentValues().apply { put("deleted_at", now); put("updated_at", now) }, "page_id = ? and deleted_at is null", arrayOf(pageId))
            textBlockIds.forEach { mark("text_blocks", it) }

            db.delete("stroke_batches", "page_id = ?", arrayOf(pageId))
            mark("stroke_batches", pageId)
            db.delete("recognition", "page_id = ?", arrayOf(pageId))
            mark("recognition", pageId)
            val imageIds = db.rawQuery(
                "select id from page_images where page_id = ? and deleted_at is null",
                arrayOf(pageId),
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            db.update(
                "page_images",
                ContentValues().apply { put("deleted_at", now); put("updated_at", now) },
                "page_id = ? and deleted_at is null",
                arrayOf(pageId),
            )
            imageIds.forEach { mark("page_images", it) }

            touchNotebook(parentId, now)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
    }

    suspend fun duplicatePage(pageId: String): String = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        val newId = UUID.randomUUID().toString()
        db.beginTransaction()
        try {
            val pages = loadPagesForPage(pageId)
            val sourceIndex = pages.indexOfFirst { it.id == pageId }
            require(sourceIndex >= 0) { "Page does not exist" }
            val source = pages[sourceIndex]
            val insertPlan = PageSortKeyPlanner.insertAfter(pages.map { it.sortKey }, sourceIndex)
            insertPlan.renumberedKeys?.let { keys -> rekeyPages(pages, keys, now) }

            db.insertOrThrow("pages", null, ContentValues().apply {
                put("id", newId); put("notebook_id", source.notebookId); put("sort_key", insertPlan.sortKey)
                put("kind", source.kind.db); put("asset_id", source.assetId); put("pdf_page_index", source.pdfPageIndex)
                put("width_pt", source.widthPt); put("height_pt", source.heightPt); put("bookmarked", if (source.bookmarked) 1 else 0)
                put("rotation", source.rotation)
                put("created_at", now); put("updated_at", now)
            })
            mark("pages", newId)

            val batches = db.rawQuery(
                "select layer, revision, stroke_count, data from stroke_batches where page_id = ?",
                arrayOf(pageId),
            ).use { c ->
                buildList {
                    while (c.moveToNext()) add(StoredStrokeBatch(c.getInt(0), c.getInt(1), c.getInt(2), c.getBlob(3)))
                }
            }
            batches.forEach { batch ->
                db.insertOrThrow("stroke_batches", null, ContentValues().apply {
                    put("id", UUID.randomUUID().toString()); put("page_id", newId)
                    put("layer", batch.layer); put("revision", batch.revision); put("stroke_count", batch.strokeCount)
                    put("data", batch.data); put("updated_at", now)
                })
            }
            if (batches.isNotEmpty()) mark("stroke_batches", newId)

            val blocks = db.rawQuery(
                "select x_pt, y_pt, w_pt, direction, content, rotation_degrees from text_blocks where page_id = ? and deleted_at is null",
                arrayOf(pageId),
            ).use { c ->
                buildList {
                    while (c.moveToNext()) add(
                        StoredTextBlock(c.getFloat(0), c.getFloat(1), c.getFloat(2), c.getString(3), c.getString(4), c.getFloat(5)),
                    )
                }
            }
            blocks.forEach { block ->
                val id = UUID.randomUUID().toString()
                db.insertOrThrow("text_blocks", null, ContentValues().apply {
                    put("id", id); put("page_id", newId); put("x_pt", block.x); put("y_pt", block.y)
                    put("w_pt", block.width); put("direction", block.direction); put("content", block.content)
                    put("rotation_degrees", block.rotationDegrees)
                    put("updated_at", now)
                })
                mark("text_blocks", id)
            }

            val images = db.rawQuery(
                "select asset_id, x_pt, y_pt, w_pt, h_pt from page_images where page_id = ? and deleted_at is null",
                arrayOf(pageId),
            ).use { c ->
                buildList {
                    while (c.moveToNext()) add(
                        listOf(c.getString(0), c.getFloat(1), c.getFloat(2), c.getFloat(3), c.getFloat(4))
                    )
                }
            }
            images.forEach { image ->
                val imageId = UUID.randomUUID().toString()
                db.insertOrThrow("page_images", null, ContentValues().apply {
                    put("id", imageId); put("page_id", newId); put("asset_id", image[0] as String)
                    put("x_pt", image[1] as Float); put("y_pt", image[2] as Float)
                    put("w_pt", image[3] as Float); put("h_pt", image[4] as Float); put("updated_at", now)
                })
                mark("page_images", imageId)
            }

            db.rawQuery(
                "select source, language, text from recognition where page_id = ? and source <> 'text'",
                arrayOf(pageId),
            ).use { c ->
                while (c.moveToNext()) {
                    db.insertOrThrow("recognition", null, ContentValues().apply {
                        put("id", UUID.randomUUID().toString()); put("page_id", newId); put("source", c.getString(0))
                        put("language", c.getStringOrNull(1)); put("text", c.getString(2)); put("updated_at", now)
                    })
                }
            }
            indexTextBlocks(newId)
            touchNotebook(source.notebookId, now)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
        newId
    }

    suspend fun extractPageRange(notebookId: String, firstPage: Int, lastPage: Int, title: String): String =
        withContext(Dispatchers.IO) {
            val sourcePages = pages(notebookId).first()
            require(firstPage in 1..sourcePages.size && lastPage in firstPage..sourcePages.size) {
                "Choose a valid page range"
            }
            val sourceNotebook = notebooks().first().firstOrNull { it.id == notebookId }
                ?: error("Notebook not found")
            val selected = sourcePages.subList(firstPage - 1, lastPage)
            val db = helper.writableDatabase
            val now = System.currentTimeMillis()
            val newNotebookId = UUID.randomUUID().toString()
            db.beginTransaction()
            try {
                db.insertOrThrow("notebooks", null, ContentValues().apply {
                    put("id", newNotebookId)
                    put("title", title.trim().ifBlank { "${sourceNotebook.title} (pages $firstPage-$lastPage)" })
                    put("folder_id", sourceNotebook.folderId)
                    put("document_kind", if (selected.all { it.kind == PageKind.PDF }) "pdf" else "notes")
                    put("cover_color", sourceNotebook.coverColor)
                    put("language", sourceNotebook.language)
                    put("sort_order", 0)
                    put("created_at", now)
                    put("updated_at", now)
                })
                mark("notebooks", newNotebookId)
                selected.forEachIndexed { index, page ->
                    val newPageId = UUID.randomUUID().toString()
                    db.insertOrThrow("pages", null, ContentValues().apply {
                        put("id", newPageId)
                        put("notebook_id", newNotebookId)
                        put("sort_key", index + 1.0)
                        put("kind", page.kind.db)
                        put("asset_id", page.assetId)
                        put("pdf_page_index", page.pdfPageIndex)
                        put("width_pt", page.widthPt)
                        put("height_pt", page.heightPt)
                        put("rotation", page.rotation)
                        put("bookmarked", if (page.bookmarked) 1 else 0)
                        put("created_at", now)
                        put("updated_at", now)
                    })
                    mark("pages", newPageId)

                    db.rawQuery(
                        "select layer, revision, stroke_count, data from stroke_batches where page_id = ?",
                        arrayOf(page.id),
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            db.insertOrThrow("stroke_batches", null, ContentValues().apply {
                                put("id", UUID.randomUUID().toString())
                                put("page_id", newPageId)
                                put("layer", cursor.getInt(0))
                                put("revision", cursor.getInt(1))
                                put("stroke_count", cursor.getInt(2))
                                put("data", cursor.getBlob(3))
                                put("updated_at", now)
                            })
                            mark("stroke_batches", newPageId)
                        }
                    }
                    db.rawQuery(
                        "select x_pt, y_pt, w_pt, direction, content, font_size_pt, color_argb, rotation_degrees from text_blocks where page_id = ? and deleted_at is null",
                        arrayOf(page.id),
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = UUID.randomUUID().toString()
                            db.insertOrThrow("text_blocks", null, ContentValues().apply {
                                put("id", id)
                                put("page_id", newPageId)
                                put("x_pt", cursor.getFloat(0))
                                put("y_pt", cursor.getFloat(1))
                                put("w_pt", cursor.getFloat(2))
                                put("direction", cursor.getString(3))
                                put("content", cursor.getString(4))
                                put("font_size_pt", cursor.getFloat(5))
                                if (cursor.isNull(6)) putNull("color_argb") else put("color_argb", cursor.getInt(6))
                                put("rotation_degrees", cursor.getFloat(7))
                                put("updated_at", now)
                            })
                            mark("text_blocks", id)
                        }
                    }
                    db.rawQuery(
                        "select asset_id, x_pt, y_pt, w_pt, h_pt from page_images where page_id = ? and deleted_at is null",
                        arrayOf(page.id),
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = UUID.randomUUID().toString()
                            db.insertOrThrow("page_images", null, ContentValues().apply {
                                put("id", id)
                                put("page_id", newPageId)
                                put("asset_id", cursor.getString(0))
                                put("x_pt", cursor.getFloat(1))
                                put("y_pt", cursor.getFloat(2))
                                put("w_pt", cursor.getFloat(3))
                                put("h_pt", cursor.getFloat(4))
                                put("updated_at", now)
                            })
                            mark("page_images", id)
                        }
                    }
                    db.rawQuery(
                        "select kind, color_argb, quads, char_start, char_end, text, note, created_at from pdf_marks where page_id = ? and deleted = 0",
                        arrayOf(page.id),
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = UUID.randomUUID().toString()
                            db.insertOrThrow("pdf_marks", null, ContentValues().apply {
                                put("id", id)
                                put("notebook_id", newNotebookId)
                                put("page_id", newPageId)
                                put("kind", cursor.getString(0))
                                put("color_argb", cursor.getInt(1))
                                put("quads", cursor.getString(2))
                                if (cursor.isNull(3)) putNull("char_start") else put("char_start", cursor.getInt(3))
                                if (cursor.isNull(4)) putNull("char_end") else put("char_end", cursor.getInt(4))
                                put("text", cursor.getString(5))
                                put("note", cursor.getString(6))
                                put("created_at", cursor.getLong(7))
                                put("updated_at", now)
                                put("deleted", 0)
                            })
                            mark("pdf_marks", id)
                        }
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            notifyChanged()
            newNotebookId
        }

    suspend fun movePage(pageId: String, toIndex: Int) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        var changed = false
        db.beginTransaction()
        try {
            val pages = loadPagesForPage(pageId)
            val fromIndex = pages.indexOfFirst { it.id == pageId }
            if (fromIndex < 0) return@withContext
            val plan = PageSortKeyPlanner.move(pages.map { it.sortKey }, fromIndex, toIndex.coerceIn(0, pages.lastIndex))
            if (plan.order.indices.all { pages[plan.order[it]].sortKey == plan.sortKeys[it] }) return@withContext

            if (plan.renumbered) {
                plan.order.forEachIndexed { index, originalIndex ->
                    updatePageSortKey(pages[originalIndex], plan.sortKeys[index], now)
                }
            } else {
                val position = plan.order.indexOf(fromIndex)
                updatePageSortKey(pages[fromIndex], plan.sortKeys[position], now)
            }
            touchNotebook(pages[fromIndex].notebookId, now)
            db.setTransactionSuccessful()
            changed = true
        } finally {
            db.endTransaction()
        }
        if (changed) notifyChanged()
    }

    suspend fun setPageKind(pageId: String, kind: PageKind) = withContext(Dispatchers.IO) {
        require(kind != PageKind.PDF) { "PDF pages can only be created by importing a PDF" }
        val db = helper.writableDatabase
        val page = db.rawQuery(
            "select notebook_id, kind from pages where id = ? and deleted_at is null",
            arrayOf(pageId),
        ).use { c ->
            if (c.moveToFirst()) c.getString(0) to PageKind.from(c.getString(1)) else null
        } ?: throw IllegalArgumentException("Page does not exist")
        check(page.second != PageKind.PDF) { "PDF pages cannot change templates" }
        if (page.second == kind) return@withContext

        val now = System.currentTimeMillis()
        db.update("pages", ContentValues().apply { put("kind", kind.db); put("updated_at", now) }, "id = ?", arrayOf(pageId))
        mark("pages", pageId)
        touchNotebook(page.first, now)
        notifyChanged()
    }

    suspend fun addPage(notebookId: String, kind: PageKind, afterSortKey: Double? = null, widthPt: Float = 595f, heightPt: Float = 842f,
                        assetId: String? = null, pdfPageIndex: Int? = null): String = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val pages = loadPages(notebookId)
            val afterIndex = afterSortKey?.let { sortKey -> pages.indexOfLast { it.sortKey == sortKey } } ?: pages.lastIndex
            val insertPlan = PageSortKeyPlanner.insertAfter(pages.map { it.sortKey }, afterIndex)
            insertPlan.renumberedKeys?.let { keys -> rekeyPages(pages, keys, now) }
            db.insertOrThrow("pages", null, ContentValues().apply {
                put("id", id); put("notebook_id", notebookId); put("sort_key", insertPlan.sortKey); put("kind", kind.db)
                put("asset_id", assetId); put("pdf_page_index", pdfPageIndex); put("width_pt", widthPt); put("height_pt", heightPt)
                put("created_at", now); put("updated_at", now)
            })
            touchNotebook(notebookId, now)
            mark("pages", id)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
        id
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
            mark("assets", asset.id)
            var sortKey = maxSortKey(notebookId)
            pageSizesPt.forEachIndexed { index, (w, h) ->
                sortKey += 1.0
                val pageId = UUID.randomUUID().toString()
                mark("pages", pageId)
                db.insertOrThrow("pages", null, ContentValues().apply {
                    put("id", pageId); put("notebook_id", notebookId); put("sort_key", sortKey); put("kind", PageKind.PDF.db)
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

    suspend fun addPageImage(pageId: String, asset: Asset, xPt: Float, yPt: Float, wPt: Float, hPt: Float): PageImage =
        withContext(Dispatchers.IO) {
            val db = helper.writableDatabase
            val now = System.currentTimeMillis()
            val imageId = UUID.randomUUID().toString()
            val notebookId = db.rawQuery(
                "select notebook_id from pages where id = ? and deleted_at is null",
                arrayOf(pageId),
            ).use { if (it.moveToFirst()) it.getString(0) else throw IllegalArgumentException("Page does not exist") }
            db.beginTransaction()
            try {
                db.insertWithOnConflict("assets", null, ContentValues().apply {
                    put("id", asset.id); put("kind", asset.kind); put("local_path", asset.localPath)
                    put("byte_size", asset.byteSize); put("sha256", asset.sha256); putNull("page_count")
                    put("name", asset.name); put("created_at", now)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                mark("assets", asset.id)
                db.insertOrThrow("page_images", null, ContentValues().apply {
                    put("id", imageId); put("page_id", pageId); put("asset_id", asset.id)
                    put("x_pt", xPt); put("y_pt", yPt); put("w_pt", wPt); put("h_pt", hPt); put("updated_at", now)
                })
                mark("page_images", imageId)
                db.update("pages", ContentValues().apply { put("updated_at", now) }, "id = ?", arrayOf(pageId))
                touchNotebook(notebookId, now)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            notifyChanged()
            PageImage(imageId, pageId, asset.id, xPt, yPt, wPt, hPt, now, asset.localPath)
        }

    suspend fun savePageImages(pageId: String, images: List<PageImage>) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        val notebookId = db.rawQuery(
            "select notebook_id from pages where id = ? and deleted_at is null",
            arrayOf(pageId),
        ).use { if (it.moveToFirst()) it.getString(0) else return@withContext }
        val saved = images.filter { it.pageId == pageId }
        val savedIds = saved.mapTo(HashSet()) { it.id }
        db.beginTransaction()
        try {
            db.rawQuery("select id from page_images where page_id = ? and deleted_at is null", arrayOf(pageId)).use { c ->
                val removed = buildList { while (c.moveToNext()) if (c.getString(0) !in savedIds) add(c.getString(0)) }
                removed.forEach { id ->
                    db.update("page_images", ContentValues().apply { put("deleted_at", now); put("updated_at", now) }, "id = ?", arrayOf(id))
                    mark("page_images", id)
                }
            }
            saved.forEach { image ->
                db.insertWithOnConflict("page_images", null, ContentValues().apply {
                    put("id", image.id); put("page_id", pageId); put("asset_id", image.assetId)
                    put("x_pt", image.xPt); put("y_pt", image.yPt); put("w_pt", image.wPt); put("h_pt", image.hPt)
                    put("updated_at", now); putNull("deleted_at")
                }, SQLiteDatabase.CONFLICT_REPLACE)
                mark("page_images", image.id)
            }
            db.update("pages", ContentValues().apply { put("updated_at", now) }, "id = ?", arrayOf(pageId))
            touchNotebook(notebookId, now)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
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
        mark("stroke_batches", pageId); mark("pages", pageId); notifyChanged()
    }

    fun audioRecordings(notebookId: String): Flow<List<AudioRecording>> = flow {
        emit(loadAudioRecordings(notebookId))
        changes.collect { emit(loadAudioRecordings(notebookId)) }
    }.flowOn(Dispatchers.IO)

    private fun loadAudioRecordings(notebookId: String): List<AudioRecording> =
        helper.readableDatabase.rawQuery(
            """select id, notebook_id, title, started_at, duration_ms, segments, deleted, active
               from recordings where notebook_id = ? and deleted = 0 order by started_at desc""",
            arrayOf(notebookId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    AudioRecording(
                        id = cursor.getString(0),
                        notebookId = cursor.getString(1),
                        title = cursor.getString(2),
                        startedAt = cursor.getLong(3),
                        durationMs = cursor.getLong(4),
                        segments = JSONArray(cursor.getString(5)).let { json ->
                            List(json.length()) { index -> json.getString(index) }
                        },
                        deleted = cursor.getInt(6) != 0,
                        active = cursor.getInt(7) != 0,
                    ),
                )
            }
        }

    suspend fun createAudioRecording(
        id: String,
        notebookId: String,
        title: String,
        startedAt: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        helper.writableDatabase.insertOrThrow("recordings", null, ContentValues().apply {
            put("id", id)
            put("notebook_id", notebookId)
            put("title", title)
            put("started_at", startedAt)
            put("updated_at", startedAt)
            put("segments", "[]")
            put("active", 1)
        })
        notifyChanged()
    }

    suspend fun activeAudioRecordingId(notebookId: String): String? = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "select id from recordings where notebook_id = ? and active = 1 and deleted = 0 order by started_at desc limit 1",
            arrayOf(notebookId),
        ).use { if (it.moveToFirst()) it.getString(0) else null }
    }

    suspend fun activeAudioRecording(notebookId: String): AudioRecording? = withContext(Dispatchers.IO) {
        loadAudioRecordings(notebookId).firstOrNull(AudioRecording::active)
    }

    suspend fun activeAudioRecordings(): List<AudioRecording> = withContext(Dispatchers.IO) {
        val notebookIds = helper.readableDatabase.rawQuery(
            "select distinct notebook_id from recordings where active = 1 and deleted = 0",
            null,
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        notebookIds.flatMap(::loadAudioRecordings).filter(AudioRecording::active)
    }

    suspend fun appendAudioSegment(recordingId: String, segmentPath: String, durationMs: Long) =
        withContext(Dispatchers.IO) {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                val current = db.rawQuery(
                    "select segments, duration_ms from recordings where id = ? and deleted = 0",
                    arrayOf(recordingId),
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        val json = JSONArray(cursor.getString(0))
                        val paths = List(json.length()) { json.getString(it) }
                        paths to cursor.getLong(1)
                    } else null
                } ?: return@withContext
                val paths = if (segmentPath in current.first) current.first else current.first + segmentPath
                db.update(
                    "recordings",
                    ContentValues().apply {
                        put("segments", JSONArray(paths).toString())
                        put("duration_ms", current.second + durationMs.coerceAtLeast(0))
                        put("updated_at", System.currentTimeMillis())
                    },
                    "id = ?",
                    arrayOf(recordingId),
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            notifyChanged()
        }

    suspend fun finishAudioRecording(recordingId: String) = withContext(Dispatchers.IO) {
        helper.writableDatabase.update(
            "recordings",
            ContentValues().apply { put("active", 0); put("updated_at", System.currentTimeMillis()) },
            "id = ?",
            arrayOf(recordingId),
        )
        notifyChanged()
    }

    suspend fun recoverInterruptedAudioRecordings() = withContext(Dispatchers.IO) {
        helper.writableDatabase.update(
            "recordings",
            ContentValues().apply { put("active", 0); put("updated_at", System.currentTimeMillis()) },
            "active = 1",
            null,
        )
        notifyChanged()
    }

    suspend fun renameAudioRecording(recordingId: String, title: String) = withContext(Dispatchers.IO) {
        helper.writableDatabase.update(
            "recordings",
            ContentValues().apply { put("title", title.trim().ifBlank { "Lecture recording" }); put("updated_at", System.currentTimeMillis()) },
            "id = ?",
            arrayOf(recordingId),
        )
        notifyChanged()
    }

    suspend fun setAudioRecordingDeleted(recordingId: String, deleted: Boolean) = withContext(Dispatchers.IO) {
        helper.writableDatabase.update(
            "recordings",
            ContentValues().apply { put("deleted", if (deleted) 1 else 0); put("updated_at", System.currentTimeMillis()) },
            "id = ?",
            arrayOf(recordingId),
        )
        notifyChanged()
    }

    suspend fun addInkTimestamp(
        recordingId: String,
        pageId: String,
        itemKind: String,
        itemKey: String,
        timeMs: Long,
    ) = withContext(Dispatchers.IO) {
        helper.writableDatabase.insertWithOnConflict(
            "ink_timestamps",
            null,
            ContentValues().apply {
                put("recording_id", recordingId)
                put("page_id", pageId)
                put("item_kind", itemKind)
                put("item_key", itemKey)
                put("t_ms", timeMs.coerceAtLeast(0))
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        notifyChanged()
    }

    suspend fun inkTimestamps(recordingId: String): List<InkTimestamp> = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "select recording_id, page_id, item_kind, item_key, t_ms from ink_timestamps where recording_id = ? order by t_ms, item_key",
            arrayOf(recordingId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    InkTimestamp(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getLong(4)),
                )
            }
        }
    }

    suspend fun addAudioMarker(recordingId: String, timeMs: Long, kind: String) = withContext(Dispatchers.IO) {
        helper.writableDatabase.insert("audio_markers", null, ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("recording_id", recordingId)
            put("t_ms", timeMs.coerceAtLeast(0))
            put("kind", kind)
        })
        notifyChanged()
    }

    suspend fun audioMarkers(recordingId: String): List<AudioMarker> = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "select id, recording_id, t_ms, kind from audio_markers where recording_id = ? order by t_ms",
            arrayOf(recordingId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(AudioMarker(cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getString(3)))
            }
        }
    }

    private fun maxSortKey(notebookId: String): Double =
        helper.readableDatabase.rawQuery("select coalesce(max(sort_key), 0) from pages where notebook_id = ?", arrayOf(notebookId)).use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 }

    private fun loadPagesForPage(pageId: String): List<Page> {
        val notebookId = helper.readableDatabase.rawQuery(
            "select notebook_id from pages where id = ? and deleted_at is null",
            arrayOf(pageId),
        ).use { if (it.moveToFirst()) it.getString(0) else return emptyList() }
        return loadPages(notebookId)
    }

    private fun rekeyPages(pages: List<Page>, sortKeys: List<Double>, now: Long) {
        pages.forEachIndexed { index, page ->
            if (page.sortKey != sortKeys[index]) updatePageSortKey(page, sortKeys[index], now)
        }
    }

    private fun updatePageSortKey(page: Page, sortKey: Double, now: Long) {
        helper.writableDatabase.update("pages", ContentValues().apply {
            put("sort_key", sortKey); put("updated_at", now)
        }, "id = ?", arrayOf(page.id))
        mark("pages", page.id)
    }

    private fun touchNotebook(id: String, now: Long) {
        helper.writableDatabase.execSQL("update notebooks set updated_at = ? where id = ?", arrayOf<Any?>(now, id))
        mark("notebooks", id)
    }
}

private fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
