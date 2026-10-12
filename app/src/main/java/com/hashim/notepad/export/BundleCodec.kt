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
            zip.put("manifest.json", JSONObject().put("format", "notepad-bundle").put("version", 1).put("exportedAt", System.currentTimeMillis()).toString().toByteArray())
            val root = JSONObject()
            root.put("notebook", rows(db, "select * from notebooks where id = ?", arrayOf(notebookId)).optJSONObject(0))
            root.put("pages", rows(db, "select * from pages where notebook_id = ? and deleted_at is null", arrayOf(notebookId)))
            root.put("text_blocks", rows(db, "select t.* from text_blocks t join pages p on p.id = t.page_id where p.notebook_id = ? and t.deleted_at is null", arrayOf(notebookId)))
            root.put("recognition", rows(db, "select r.* from recognition r join pages p on p.id = r.page_id where p.notebook_id = ?", arrayOf(notebookId)))
            val assets = rows(db, "select distinct a.* from assets a join pages p on p.asset_id = a.id where p.notebook_id = ?", arrayOf(notebookId))
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
        val strokes = HashMap<String, ByteArray>()
        val assetFiles = HashMap<String, File>()
        val assetDir = File(context.filesDir, "assets").apply { mkdirs() }
        ZipInputStream(input.buffered()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                when {
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
        db.beginTransaction()
        try {
            val nb = json.getJSONObject("notebook")
            nb.put("deleted_at", JSONObject.NULL)
            db.insertWithOnConflict("notebooks", null, nb.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            json.getJSONArray("assets").forEachObject { a ->
                assetFiles[a.getString("id")]?.let { a.put("local_path", it.absolutePath) }
                a.remove("storage_key")
                db.insertWithOnConflict("assets", null, a.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            }
            json.getJSONArray("pages").forEachObject { p -> db.insertWithOnConflict("pages", null, p.toValues(), SQLiteDatabase.CONFLICT_REPLACE) }
            json.getJSONArray("text_blocks").forEachObject { t -> db.insertWithOnConflict("text_blocks", null, t.toValues(), SQLiteDatabase.CONFLICT_REPLACE) }
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
            nb.getString("id")
        } finally {
            db.endTransaction()
            repo.changed()
        }
    }

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
