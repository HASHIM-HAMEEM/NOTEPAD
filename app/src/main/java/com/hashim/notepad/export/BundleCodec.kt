package com.hashim.notepad.export

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.hashim.notepad.data.NotePadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Lossless notebook bundle (.notepad = zip):
 *   manifest.json            {format, version, exportedAt}
 *   notebook.json            notebook row + pages, text_blocks, recognition, assets rows (all columns)
 *   strokes/<pageId>.npik    stroke batch bytes (Ink protobuf bundle, see StrokeStore)
 *   assets/<assetId>.<ext>   PDF / image files
 * Ids are preserved so re-importing on another device merges instead of duplicating.
 */
class BundleCodec(private val context: Context, private val repo: NotePadRepository) {

    suspend fun export(notebookId: String, out: OutputStream) = withContext(Dispatchers.IO) {
        val db = repo.helper.readableDatabase
        ZipOutputStream(out.buffered()).use { zip ->
            val folderName = db.rawQuery(
                """select f.name from notebooks n join folders f on f.id = n.folder_id
                   where n.id = ? and f.deleted_at is null""",
                arrayOf(notebookId),
            ).use { if (it.moveToFirst()) it.getString(0) else null }
            val tagNames = JSONArray().also { names ->
                db.rawQuery(
                    """select t.name from tags t join notebook_tags nt on nt.tag_id = t.id
                       where nt.notebook_id = ? and nt.deleted_at is null and t.deleted_at is null
                       order by t.name collate nocase""",
                    arrayOf(notebookId),
                ).use { c -> while (c.moveToNext()) names.put(c.getString(0)) }
            }
            val manifest = JSONObject().put("format", BUNDLE_FORMAT).put("version", 1).put("exportedAt", System.currentTimeMillis())
            manifest.put("folder_name", folderName ?: JSONObject.NULL).put("tags", tagNames)
            zip.put(BUNDLE_MANIFEST_ENTRY, manifest.toString().toByteArray())
            val root = JSONObject()
            root.put("notebook", rows(db, "select * from notebooks where id = ?", arrayOf(notebookId)).optJSONObject(0))
            root.put("pages", rows(db, "select * from pages where notebook_id = ? and deleted_at is null", arrayOf(notebookId)))
            root.put("text_blocks", rows(db, "select t.* from text_blocks t join pages p on p.id = t.page_id where p.notebook_id = ? and t.deleted_at is null", arrayOf(notebookId)))
            root.put("page_images", rows(db, "select i.* from page_images i join pages p on p.id = i.page_id where p.notebook_id = ? and p.deleted_at is null and i.deleted_at is null", arrayOf(notebookId)))
            root.put("recognition", rows(db, "select r.* from recognition r join pages p on p.id = r.page_id where p.notebook_id = ?", arrayOf(notebookId)))
            root.put("pdf_marks", rows(db, "select m.* from pdf_marks m where m.notebook_id = ?", arrayOf(notebookId)))
            val assets = rows(
                db,
                """select distinct a.* from assets a where a.id in (
                    select asset_id from pages where notebook_id = ? and deleted_at is null
                    union
                    select i.asset_id from page_images i join pages p on p.id = i.page_id
                    where p.notebook_id = ? and p.deleted_at is null and i.deleted_at is null
                )""",
                arrayOf(notebookId, notebookId),
            )
            root.put("assets", assets)
            zip.put("notebook.json", root.toString().toByteArray())
            db.rawQuery("select s.page_id, s.data from stroke_batches s join pages p on p.id = s.page_id where p.notebook_id = ? and s.layer = 0", arrayOf(notebookId)).use { c ->
                while (c.moveToNext()) zip.put("strokes/${c.getString(0)}.npik", c.getBlob(1))
            }
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val f = File(a.getString("local_path"))
                if (f.exists()) { zip.putNextEntry(ZipEntry("assets/${a.getString("id")}.${f.extension}")); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
            }
        }
    }

    /** Returns the imported notebook id. */
    suspend fun import(input: InputStream): String = withContext(Dispatchers.IO) {
        var root: JSONObject? = null
        var manifest: JSONObject? = null
        val strokes = HashMap<String, ByteArray>()
        val assetFiles = HashMap<String, File>()
        val assetDir = File(context.filesDir, "assets").apply { mkdirs() }
        ZipInputStream(input.buffered()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                when {
                    e.name == BUNDLE_MANIFEST_ENTRY -> manifest = JSONObject(zip.readBytes().decodeToString())
                    e.name == "notebook.json" -> root = JSONObject(zip.readBytes().decodeToString())
                    e.name.startsWith("strokes/") -> strokes[e.name.removePrefix("strokes/").removeSuffix(".npik")] = zip.readBytes()
                    e.name.startsWith("assets/") -> {
                        val name = e.name.removePrefix("assets/")
                        val f = File(assetDir, name); f.outputStream().use { zip.copyTo(it) }
                        assetFiles[name.substringBeforeLast('.')] = f
                    }
                }
                e = zip.nextEntry
            }
        }
        val json = root ?: throw IllegalStateException("Not a NotePad bundle")
        val db = repo.helper.writableDatabase
        val notebookId = json.getJSONObject("notebook").getString("id")
        db.beginTransaction()
        try {
            val nb = json.getJSONObject("notebook")
            nb.put("deleted_at", JSONObject.NULL)
            nb.put("folder_id", JSONObject.NULL)
            val pageKinds = json.optJSONArray("pages")?.let { pages ->
                (0 until pages.length()).mapNotNull { pages.optJSONObject(it)?.optString("kind") }
            }.orEmpty()
            val defaults = legacyNotebookDefaults(pageKinds)
            if (!nb.has("document_kind")) nb.put("document_kind", defaults.documentKind)
            if (!nb.has("last_page_index")) nb.put("last_page_index", defaults.lastPageIndex)
            if (!nb.has("last_opened_at")) nb.put("last_opened_at", JSONObject.NULL)
            db.insertWithOnConflict("notebooks", null, nb.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            json.getJSONArray("assets").forEachObject { a ->
                assetFiles[a.getString("id")]?.let { a.put("local_path", it.absolutePath) }
                a.remove("storage_key")
                db.insertWithOnConflict("assets", null, a.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
                db.execSQL("insert or ignore into outbox(table_name, row_id) values ('assets', ?)", arrayOf(a.getString("id")))
            }
            json.getJSONArray("pages").forEachObject { p -> db.insertWithOnConflict("pages", null, p.toValues(), SQLiteDatabase.CONFLICT_REPLACE) }
            json.getJSONArray("text_blocks").forEachObject { t -> db.insertWithOnConflict("text_blocks", null, t.toValues(), SQLiteDatabase.CONFLICT_REPLACE) }
            json.optJSONArray("page_images")?.forEachObject { i ->
                db.insertWithOnConflict("page_images", null, i.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
                db.execSQL("insert or ignore into outbox(table_name, row_id) values ('page_images', ?)", arrayOf(i.getString("id")))
            }
            json.optJSONArray("pdf_marks")?.forEachObject { m ->
                db.insertWithOnConflict("pdf_marks", null, m.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
                db.execSQL("insert or ignore into outbox(table_name, row_id) values ('pdf_marks', ?)", arrayOf(m.getString("id")))
            }
            json.getJSONArray("recognition").forEachObject { r ->
                db.delete("recognition", "page_id = ? and source = ?", arrayOf(r.getString("page_id"), r.getString("source")))
                db.insertWithOnConflict("recognition", null, r.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            }
            for ((pageId, data) in strokes) {
                db.execSQL(
                    """insert into stroke_batches(id, page_id, layer, revision, stroke_count, data, updated_at) values (?, ?, 0, 1, 0, ?, ?)
                       on conflict(page_id, layer) do update set data = excluded.data, revision = revision + 1, updated_at = excluded.updated_at""",
                    arrayOf<Any?>(java.util.UUID.randomUUID().toString(), pageId, data, System.currentTimeMillis())
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        manifest?.let { data ->
            val folderName = data.optString("folder_name").trim().takeIf { it.isNotEmpty() && it != "null" }
            if (folderName != null) {
                val folderId = findFolderId(db, folderName) ?: repo.createFolder(folderName)
                repo.moveNotebookToFolder(notebookId, folderId)
            }
            val importedTags = linkedSetOf<String>()
            data.optJSONArray("tags")?.let { names ->
                for (i in 0 until names.length()) {
                    val name = names.optString(i).trim()
                    if (name.isNotEmpty()) importedTags += repo.createTag(name)
                }
            }
            if (data.has("tags")) repo.setNotebookTags(notebookId, importedTags)
        }
        repo.changed()
        notebookId
    }

    private fun findFolderId(db: SQLiteDatabase, name: String): String? = db.rawQuery(
        "select id from folders where name = ? collate nocase and deleted_at is null limit 1",
        arrayOf(name),
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun rows(db: SQLiteDatabase, sql: String, args: Array<String>): JSONArray = db.rawQuery(sql, args).use { c ->
        JSONArray().also { arr -> while (c.moveToNext()) arr.put(c.toJson()) }
    }

    private fun Cursor.toJson(): JSONObject = JSONObject().also { o ->
        for (i in 0 until columnCount) when (getType(i)) {
            Cursor.FIELD_TYPE_NULL -> o.put(getColumnName(i), JSONObject.NULL)
            Cursor.FIELD_TYPE_INTEGER -> o.put(getColumnName(i), getLong(i))
            Cursor.FIELD_TYPE_FLOAT -> o.put(getColumnName(i), getDouble(i))
            Cursor.FIELD_TYPE_BLOB -> Unit
            else -> o.put(getColumnName(i), getString(i))
        }
    }

    private fun JSONObject.toValues(): ContentValues = ContentValues().also { v ->
        keys().forEach { k ->
            when (val x = get(k)) {
                JSONObject.NULL -> v.putNull(k)
                is Int -> v.put(k, x); is Long -> v.put(k, x); is Double -> v.put(k, x); is Boolean -> v.put(k, x)
                else -> v.put(k, x.toString())
            }
        }
    }

    private inline fun JSONArray.forEachObject(f: (JSONObject) -> Unit) { for (i in 0 until length()) f(getJSONObject(i)) }

    private fun ZipOutputStream.put(name: String, bytes: ByteArray) { putNextEntry(ZipEntry(name)); write(bytes); closeEntry() }
}
