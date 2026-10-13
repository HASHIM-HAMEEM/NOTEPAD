package com.hashim.notepad.sync

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.sync.SupabaseHttp.orThrow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Last-writer-wins replication between the local SQLite database and Supabase (PostgREST + Storage).
 *
 * Every local write queues (table, row_id) in `outbox`. A sync run first pulls rows newer than the last
 * pull watermark (a remote row only replaces a local one when it is newer), then pushes the outbox.
 * Content fields are encrypted with the user's key before leaving the device; the server stores ciphertext.
 */
class SyncEngine(private val context: Context, private val repo: NotePadRepository, private val auth: AuthManager, private val key: () -> ByteArray?) {

    private val db: SQLiteDatabase get() = repo.helper.writableDatabase
    private val assetsDir = File(context.filesDir, "assets").apply { mkdirs() }

    data class Result(val pushed: Int, val pulled: Int)

    fun pendingCount(): Int = db.rawQuery("select count(*) from outbox", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun sync(): Result {
        val session = auth.session.value ?: throw IllegalStateException("Not signed in")
        val k = key() ?: throw IllegalStateException("Encryption passphrase not set")
        val token = auth.token()
        var pulled = 0
        for (t in TABLES) {
            try {
                pulled += pull(t, token, session.userId, k)
            } catch (e: HttpException) {
                if (t in SCHEMA_OPTIONAL_TABLES && MissingSchemaClassifier.isMissingSchema(e.code, e.message.orEmpty())) {
                    Log.w(TAG, "server schema lacks $t; skipping pull until migration is applied", e)
                } else {
                    throw e
                }
            }
        }
        var pushed = 0
        for (t in TABLES) {
            try {
                pushed += push(t, token, session.userId, k)
            } catch (e: HttpException) {
                if (t in SCHEMA_OPTIONAL_TABLES && MissingSchemaClassifier.isMissingSchema(e.code, e.message.orEmpty())) {
                    Log.w(TAG, "server schema lacks $t; keeping queued changes for a later retry", e)
                } else {
                    throw e
                }
            }
        }
        repo.changed()
        return Result(pushed, pulled)
    }

    // ---------------------------------------------------------------- pull

    private fun pull(table: String, token: String, uid: String, k: ByteArray): Int {
        var since = meta("pull.$table") ?: "1970-01-01T00:00:00Z"
        var total = 0
        while (true) {
            val path = "/rest/v1/$table?select=*&user_id=eq.$uid&updated_at=gt.${enc(since)}&order=updated_at.asc,id.asc&limit=$PAGE"
            val rows = JSONArray(SupabaseHttp.json("GET", path, token).orThrow().text)
            if (rows.length() == 0) break
            db.beginTransaction()
            try {
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    if (applyRemote(table, row, k, token)) total++
                    since = row.getString("updated_at")
                }
                setMeta("pull.$table", since)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            if (rows.length() < PAGE) break
        }
        return total
    }

    /** Returns true when the remote row replaced local state. */
    private fun applyRemote(table: String, r: JSONObject, k: ByteArray, token: String): Boolean {
        val id = r.getString("id")
        val remoteUpdated = ms(r.getString("updated_at"))
        val localKey = if (table == "stroke_batches" || table == "recognition") r.getString("page_id") else id
        val localUpdated = db.rawQuery("select updated_at from $table where id = ?", arrayOf(id)).use { if (it.moveToFirst()) it.getLong(0) else null }
        if (localUpdated != null && localUpdated > remoteUpdated) return false // local is newer; the push will win
        if (localUpdated != null && localUpdated == remoteUpdated) { clearOutbox(table, localKey); return false }
        val cv = ContentValues()
        when (table) {
            "notebooks" -> {
                cv.put("id", id); cv.put("title", decText(k, r, "title_enc") ?: "Untitled"); cv.put("cover_color", str(r, "cover_color"))
                if (r.has("folder_id")) {
                    cv.put("folder_id", str(r, "folder_id"))
                } else {
                    db.rawQuery("select folder_id from notebooks where id = ?", arrayOf(id)).use { c ->
                        if (c.moveToFirst()) {
                            if (c.isNull(0)) cv.putNull("folder_id") else cv.put("folder_id", c.getString(0))
                        }
                    }
                }
                cv.put("language", str(r, "language") ?: "ar"); cv.put("sort_order", r.optInt("sort_order")); cv.put("deleted_at", msOrNull(r, "deleted_at"))
                cv.put("created_at", ms(r.getString("created_at"))); cv.put("updated_at", remoteUpdated)
            }
            "folders" -> {
                cv.put("id", id); cv.put("name", decText(k, r, "name_enc") ?: ""); cv.put("color", str(r, "color"))
                cv.put("sort_order", r.optInt("sort_order")); cv.put("deleted_at", msOrNull(r, "deleted_at"))
                cv.put("created_at", ms(r.getString("created_at"))); cv.put("updated_at", remoteUpdated)
            }
            "tags" -> {
                cv.put("id", id); cv.put("name", decText(k, r, "name_enc") ?: ""); cv.put("color", str(r, "color"))
                cv.put("deleted_at", msOrNull(r, "deleted_at")); cv.put("created_at", ms(r.getString("created_at"))); cv.put("updated_at", remoteUpdated)
            }
            "notebook_tags" -> {
                cv.put("id", id); cv.put("notebook_id", r.getString("notebook_id")); cv.put("tag_id", r.getString("tag_id"))
                cv.put("deleted_at", msOrNull(r, "deleted_at")); cv.put("updated_at", remoteUpdated)
            }
            "pages" -> {
                cv.put("id", id); cv.put("notebook_id", r.getString("notebook_id")); cv.put("sort_key", r.getDouble("sort_key")); cv.put("kind", r.getString("kind"))
                cv.put("asset_id", str(r, "asset_id")); if (r.isNull("pdf_page_index")) cv.putNull("pdf_page_index") else cv.put("pdf_page_index", r.getInt("pdf_page_index"))
                cv.put("width_pt", r.optDouble("width_pt", 595.0).toFloat()); cv.put("height_pt", r.optDouble("height_pt", 842.0).toFloat())
                val bookmarked = if (r.has("bookmarked")) r.optBoolean("bookmarked", false) else
                    db.rawQuery("select bookmarked from pages where id = ?", arrayOf(id)).use { if (it.moveToFirst()) it.getInt(0) != 0 else false }
                cv.put("bookmarked", if (bookmarked) 1 else 0)
                cv.put("deleted_at", msOrNull(r, "deleted_at")); cv.put("created_at", ms(r.getString("created_at"))); cv.put("updated_at", remoteUpdated)
            }
            "stroke_batches" -> {
                val data = bytes(r, "data_enc")?.let { Crypto.decrypt(k, it) } ?: return false
                db.delete("stroke_batches", "page_id = ? and layer = ? and id <> ?", arrayOf(r.getString("page_id"), r.optInt("layer").toString(), id))
                cv.put("id", id); cv.put("page_id", r.getString("page_id")); cv.put("layer", r.optInt("layer")); cv.put("revision", r.optInt("revision", 1))
                cv.put("stroke_count", r.optInt("stroke_count")); cv.put("data", data); cv.put("updated_at", remoteUpdated)
            }
            "text_blocks" -> {
                cv.put("id", id); cv.put("page_id", r.getString("page_id")); cv.put("x_pt", r.getDouble("x_pt").toFloat()); cv.put("y_pt", r.getDouble("y_pt").toFloat())
                cv.put("w_pt", r.getDouble("w_pt").toFloat()); cv.put("direction", str(r, "direction") ?: "auto"); cv.put("content", decText(k, r, "content_enc") ?: "")
                cv.put("deleted_at", msOrNull(r, "deleted_at")); cv.put("updated_at", remoteUpdated)
            }
            "page_images" -> {
                cv.put("id", id); cv.put("page_id", r.getString("page_id")); cv.put("asset_id", r.getString("asset_id"))
                cv.put("x_pt", r.getDouble("x_pt")); cv.put("y_pt", r.getDouble("y_pt")); cv.put("w_pt", r.getDouble("w_pt")); cv.put("h_pt", r.getDouble("h_pt"))
                cv.put("deleted_at", msOrNull(r, "deleted_at")); cv.put("updated_at", remoteUpdated)
            }
            "assets" -> {
                val storageKey = str(r, "storage_key") ?: return false
                var local = File(assetsDir, "$id.${if (r.optString("kind") == "image") "jpg" else "pdf"}")
                if (!local.exists()) {
                    val blob = SupabaseHttp.request("GET", "/storage/v1/object/assets/$storageKey", token).orThrow().body
                    val contents = Crypto.decrypt(k, blob)
                    val extension = if (r.optString("kind") == "image") imageExtension(contents) else "pdf"
                    local = File(assetsDir, "$id.$extension")
                    local.writeBytes(contents)
                }
                cv.put("id", id); cv.put("kind", r.getString("kind")); cv.put("local_path", local.absolutePath); cv.put("storage_key", storageKey)
                cv.put("byte_size", r.optLong("byte_size")); cv.put("sha256", r.optString("sha256")); if (r.isNull("page_count")) cv.putNull("page_count") else cv.put("page_count", r.getInt("page_count"))
                cv.put("name", decText(k, r, "name_enc")); cv.put("created_at", ms(r.getString("created_at")))
            }
            "recognition" -> {
                cv.put("id", id); cv.put("page_id", r.getString("page_id")); cv.put("source", r.getString("source")); cv.put("language", str(r, "language"))
                cv.put("text", decText(k, r, "text_enc") ?: ""); cv.put("updated_at", remoteUpdated)
                db.delete("recognition", "page_id = ? and source = ? and id <> ?", arrayOf(r.getString("page_id"), r.getString("source"), id))
            }
        }
        db.insertWithOnConflict(table, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        clearOutbox(table, localKey)
        return true
    }

    // ---------------------------------------------------------------- push

    private fun push(table: String, token: String, uid: String, k: ByteArray): Int {
        val ids = db.rawQuery("select row_id from outbox where table_name = ?", arrayOf(table)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        if (ids.isEmpty()) return 0
        var pushed = 0
        for (chunk in ids.chunked(50)) {
            val payload = JSONArray()
            val deleteRecognitionFor = ArrayList<String>()
            for (rowId in chunk) {
                when (table) {
                    "stroke_batches" -> rows(table, "page_id = ?", rowId).forEach { payload.put(encodeRow(table, it, uid, k, token)) }
                    "recognition" -> { deleteRecognitionFor.add(rowId); rows(table, "page_id = ?", rowId).forEach { payload.put(encodeRow(table, it, uid, k, token)) } }
                    else -> rows(table, "id = ?", rowId).forEach { payload.put(encodeRow(table, it, uid, k, token)) }
                }
            }
            for (pageId in deleteRecognitionFor) SupabaseHttp.json("DELETE", "/rest/v1/recognition?page_id=eq.$pageId&user_id=eq.$uid", token).orThrow()
            var keepForOptionalColumn = false
            if (payload.length() > 0) {
                try {
                    SupabaseHttp.json("POST", "/rest/v1/$table?on_conflict=id", token, payload.toString(),
                        mapOf("Prefer" to "resolution=merge-duplicates,return=minimal")).orThrow()
                } catch (e: HttpException) {
                    val missingColumns = OPTIONAL_COLUMNS_BY_TABLE[table].orEmpty().filter { column ->
                        MissingSchemaClassifier.isMissingOptionalColumn(e.code, e.message.orEmpty(), table, column)
                    }
                    if (missingColumns.isEmpty()) throw e
                    val compatiblePayload = JSONArray()
                    for (i in 0 until payload.length()) {
                        compatiblePayload.put(JSONObject(payload.getJSONObject(i).toString()).also { row ->
                            missingColumns.forEach { row.remove(it) }
                        })
                    }
                    SupabaseHttp.json("POST", "/rest/v1/$table?on_conflict=id", token, compatiblePayload.toString(),
                        mapOf("Prefer" to "resolution=merge-duplicates,return=minimal")).orThrow()
                    keepForOptionalColumn = true
                    Log.w(TAG, "server lacks optional columns on $table (${missingColumns.joinToString()}); pushed compatible rows and kept them queued")
                }
            }
            db.beginTransaction()
            try {
                if (!keepForOptionalColumn) for (rowId in chunk) clearOutbox(table, rowId)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            pushed += payload.length()
        }
        return pushed
    }

    private fun rows(table: String, where: String, arg: String): List<Map<String, Any?>> =
        db.query(table, null, where, arrayOf(arg), null, null, null).use { c ->
            buildList { while (c.moveToNext()) add(c.toMap()) }
        }

    private fun encodeRow(table: String, m: Map<String, Any?>, uid: String, k: ByteArray, token: String): JSONObject {
        val o = JSONObject()
        o.put("id", m["id"]); o.put("user_id", uid)
        fun ts(col: String) = (m[col] as? Long)?.let { iso(it) }
        when (table) {
            "notebooks" -> {
                o.put("title_enc", hex(Crypto.encryptText(k, m["title"] as String))); o.put("cover_color", m["cover_color"] ?: JSONObject.NULL)
                o.put("folder_id", m["folder_id"] ?: JSONObject.NULL)
                o.put("language", m["language"]); o.put("sort_order", m["sort_order"]); o.put("deleted_at", ts("deleted_at") ?: JSONObject.NULL)
                o.put("created_at", ts("created_at")); o.put("updated_at", ts("updated_at"))
            }
            "folders" -> {
                o.put("name_enc", hex(Crypto.encryptText(k, m["name"] as String))); o.put("color", m["color"] ?: JSONObject.NULL)
                o.put("sort_order", m["sort_order"]); o.put("deleted_at", ts("deleted_at") ?: JSONObject.NULL)
                o.put("created_at", ts("created_at")); o.put("updated_at", ts("updated_at"))
            }
            "tags" -> {
                o.put("name_enc", hex(Crypto.encryptText(k, m["name"] as String))); o.put("color", m["color"] ?: JSONObject.NULL)
                o.put("deleted_at", ts("deleted_at") ?: JSONObject.NULL); o.put("created_at", ts("created_at")); o.put("updated_at", ts("updated_at"))
            }
            "notebook_tags" -> {
                o.put("notebook_id", m["notebook_id"]); o.put("tag_id", m["tag_id"])
                o.put("deleted_at", ts("deleted_at") ?: JSONObject.NULL); o.put("updated_at", ts("updated_at"))
            }
            "pages" -> {
                o.put("notebook_id", m["notebook_id"]); o.put("sort_key", m["sort_key"]); o.put("kind", m["kind"]); o.put("asset_id", m["asset_id"] ?: JSONObject.NULL)
                o.put("pdf_page_index", m["pdf_page_index"] ?: JSONObject.NULL); o.put("width_pt", m["width_pt"]); o.put("height_pt", m["height_pt"])
                o.put("bookmarked", (m["bookmarked"] as? Number)?.toInt()?.let { it != 0 } ?: false)
                o.put("deleted_at", ts("deleted_at") ?: JSONObject.NULL); o.put("created_at", ts("created_at")); o.put("updated_at", ts("updated_at"))
            }
            "stroke_batches" -> {
                val data = m["data"] as ByteArray
                o.put("page_id", m["page_id"]); o.put("layer", m["layer"]); o.put("revision", m["revision"]); o.put("stroke_count", m["stroke_count"])
                o.put("data_enc", hex(Crypto.encrypt(k, data))); o.put("byte_size", data.size); o.put("updated_at", ts("updated_at"))
            }
            "text_blocks" -> {
                o.put("page_id", m["page_id"]); o.put("x_pt", m["x_pt"]); o.put("y_pt", m["y_pt"]); o.put("w_pt", m["w_pt"]); o.put("direction", m["direction"])
                o.put("content_enc", hex(Crypto.encryptText(k, m["content"] as String))); o.put("deleted_at", ts("deleted_at") ?: JSONObject.NULL); o.put("updated_at", ts("updated_at"))
            }
            "page_images" -> {
                o.put("page_id", m["page_id"]); o.put("asset_id", m["asset_id"]); o.put("x_pt", m["x_pt"]); o.put("y_pt", m["y_pt"])
                o.put("w_pt", m["w_pt"]); o.put("h_pt", m["h_pt"]); o.put("deleted_at", ts("deleted_at") ?: JSONObject.NULL)
                o.put("updated_at", ts("updated_at"))
            }
            "assets" -> {
                val file = File(m["local_path"] as String)
                val storageKey = (m["storage_key"] as? String) ?: "$uid/${m["id"]}"
                if (m["storage_key"] == null && file.exists()) {
                    SupabaseHttp.request("PUT", "/storage/v1/object/assets/$storageKey", token, Crypto.encrypt(k, file.readBytes()),
                        "application/octet-stream", mapOf("x-upsert" to "true")).orThrow()
                    db.execSQL("update assets set storage_key = ? where id = ?", arrayOf(storageKey, m["id"]))
                }
                o.put("kind", m["kind"]); o.put("storage_key", storageKey); o.put("byte_size", m["byte_size"]); o.put("sha256", m["sha256"])
                o.put("page_count", m["page_count"] ?: JSONObject.NULL); o.put("name_enc", (m["name"] as? String)?.let { hex(Crypto.encryptText(k, it)) } ?: JSONObject.NULL)
                o.put("created_at", ts("created_at")); o.put("updated_at", iso(System.currentTimeMillis()))
            }
            "recognition" -> {
                o.put("page_id", m["page_id"]); o.put("source", m["source"]); o.put("language", m["language"] ?: JSONObject.NULL)
                o.put("text_enc", hex(Crypto.encryptText(k, m["text"] as String))); o.put("updated_at", ts("updated_at"))
            }
        }
        return o
    }

    // ---------------------------------------------------------------- helpers

    private fun clearOutbox(table: String, rowId: String) { db.delete("outbox", "table_name = ? and row_id = ?", arrayOf(table, rowId)) }
    fun meta(key: String): String? = db.rawQuery("select value from sync_meta where key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }
    fun setMeta(key: String, value: String) { db.execSQL("insert or replace into sync_meta(key, value) values (?, ?)", arrayOf(key, value)) }
    /** Forgets pull watermarks so the next sync re-reads everything (used after signing into a different account). */
    fun resetWatermarks() { db.delete("sync_meta", "key like 'pull.%'", null) }

    private fun Cursor.toMap(): Map<String, Any?> = buildMap {
        for (i in 0 until columnCount) put(getColumnName(i), when (getType(i)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> getLong(i)
            Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
            Cursor.FIELD_TYPE_BLOB -> getBlob(i)
            else -> getString(i)
        })
    }

    private fun str(r: JSONObject, key: String): String? = if (r.isNull(key)) null else r.getString(key)
    private fun bytes(r: JSONObject, key: String): ByteArray? = str(r, key)?.let { unhex(it) }
    private fun decText(k: ByteArray, r: JSONObject, key: String): String? = bytes(r, key)?.let { runCatching { Crypto.decryptText(k, it) }.getOrElse { e -> Log.w(TAG, "decrypt $key failed", e); null } }
    private fun msOrNull(r: JSONObject, key: String): Long? = str(r, key)?.let { ms(it) }

    private fun imageExtension(bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4e.toByte() && bytes[3] == 0x47.toByte() -> "png"
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() -> "jpg"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
        else -> "img"
    }

    companion object {
        private const val TAG = "SyncEngine"
        private const val PAGE = 500
        private val SCHEMA_OPTIONAL_TABLES = setOf("folders", "tags", "notebook_tags", "page_images")
        private val OPTIONAL_COLUMNS_BY_TABLE = mapOf(
            "notebooks" to listOf("folder_id"),
            "pages" to listOf("bookmarked"),
        )
        /** Parent tables first so foreign keys resolve on pull. */
        val TABLES = listOf(
            "assets", "folders", "tags", "notebooks", "pages", "notebook_tags",
            "stroke_batches", "text_blocks", "page_images", "recognition",
        )

        fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()
        fun ms(iso: String): Long = try { Instant.parse(iso).toEpochMilli() } catch (e: DateTimeParseException) {
            // PostgREST emits "+00:00" offsets and microseconds; Instant.parse handles offsets, so only trim sub-millis noise.
            java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        }
        private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        private const val HEX = "0123456789abcdef"
        fun hex(b: ByteArray): String { val sb = StringBuilder("\\x"); for (x in b) { val v = x.toInt() and 0xff; sb.append(HEX[v ushr 4]).append(HEX[v and 0xf]) }; return sb.toString() }
        fun unhex(s: String): ByteArray {
            val h = if (s.startsWith("\\x")) s.substring(2) else s
            return ByteArray(h.length / 2) { i -> ((Character.digit(h[2 * i], 16) shl 4) + Character.digit(h[2 * i + 1], 16)).toByte() }
        }
    }
}
