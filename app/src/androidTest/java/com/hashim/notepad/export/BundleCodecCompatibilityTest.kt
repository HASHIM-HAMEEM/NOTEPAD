package com.hashim.notepad.export

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hashim.notepad.data.NotePadRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class BundleCodecCompatibilityTest {
    @Test
    fun importsLegacyBundleWithoutBatchEightFields() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = NotePadRepository(context, "legacy-bundle-compat-test.db")
        val id = UUID.randomUUID().toString()
        val root = JSONObject()
            .put(
                "notebook",
                JSONObject().put("id", id).put("title", "Legacy").put("language", "en")
                    .put("sort_order", 0).put("created_at", 1).put("updated_at", 1),
            )
            .put(
                "pages",
                JSONArray().put(
                    JSONObject().put("id", "legacy-page-$id").put("notebook_id", id)
                        .put("sort_key", 0).put("kind", "blank")
                        .put("width_pt", 595).put("height_pt", 842)
                        .put("created_at", 1).put("updated_at", 1),
                ),
            )
            .put("assets", JSONArray())
            .put("text_blocks", JSONArray())
            .put("recognition", JSONArray())
        val bundle = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("notebook.json"))
                zip.write(root.toString().toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        assertEquals(id, BundleCodec(context, repo).import(ByteArrayInputStream(bundle)))
        repo.helper.readableDatabase.rawQuery(
            "select document_kind,last_page_index from notebooks where id = ?",
            arrayOf(id),
        ).use {
            it.moveToFirst()
            assertEquals("notes", it.getString(0))
            assertEquals(0, it.getInt(1))
        }
        repo.helper.readableDatabase.rawQuery(
            "select rotation from pages where id = ?",
            arrayOf("legacy-page-$id"),
        ).use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
    }
}
