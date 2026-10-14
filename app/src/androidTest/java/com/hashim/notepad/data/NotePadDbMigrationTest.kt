package com.hashim.notepad.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class NotePadDbMigrationTest {
    @Test
    fun upgradesVersionSevenDatabaseWithoutLosingRows() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "notepad-v7-migration-test.db"
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL(
                """create table notebooks (
                    id text primary key, title text not null, folder_id text, cover_color text,
                    language text not null default 'ar', sort_order integer not null default 0,
                    deleted_at integer, created_at integer not null, updated_at integer not null
                )""",
            )
            db.execSQL(
                """create table pages (
                    id text primary key, notebook_id text not null, sort_key real not null,
                    kind text not null, asset_id text, pdf_page_index integer,
                    width_pt real not null default 595, height_pt real not null default 842,
                    bookmarked integer not null default 0, deleted_at integer,
                    created_at integer not null, updated_at integer not null
                )""",
            )
            db.execSQL(
                """create table text_blocks (
                    id text primary key, page_id text not null, x_pt real not null, y_pt real not null,
                    w_pt real not null, direction text not null default 'auto', content text not null,
                    font_size_pt real not null default 12, color_argb integer, deleted_at integer,
                    updated_at integer not null
                )""",
            )
            db.execSQL(
                "insert into notebooks(id,title,language,sort_order,created_at,updated_at) values('legacy','Legacy','en',0,1,1)",
            )
            db.execSQL(
                "insert into pages(id,notebook_id,sort_key,kind,created_at,updated_at) values('legacy-page','legacy',0,'pdf',1,1)",
            )
            db.execSQL(
                "insert into text_blocks(id,page_id,x_pt,y_pt,w_pt,content,updated_at) values('legacy-block','legacy-page',10,20,80,'legacy',1)",
            )
            db.version = 7
        }

        val upgraded = NotePadDb(context, name).writableDatabase

        assertEquals(NotePadDb.VERSION, upgraded.version)
        assertTrue(upgraded.rawQuery("pragma table_info(pages)", null).use { cursor ->
            var found = false
            while (cursor.moveToNext()) if (cursor.getString(1) == "rotation") found = true
            found
        })
        assertTrue(upgraded.rawQuery("select 1 from sqlite_master where type='table' and name='pdf_marks'", null).use { it.moveToFirst() })
        assertTrue(upgraded.rawQuery("select 1 from sqlite_master where type='table' and name='split_pairs'", null).use { it.moveToFirst() })
        upgraded.rawQuery("select document_kind,last_page_index from notebooks where id='legacy'", null).use {
            assertTrue(it.moveToFirst())
            assertEquals("pdf", it.getString(0))
            assertEquals(0, it.getInt(1))
        }
        upgraded.rawQuery("select rotation from pages where id='legacy-page'", null).use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        upgraded.rawQuery("select content,rotation_degrees from text_blocks where id='legacy-block'", null).use {
            assertTrue(it.moveToFirst())
            assertEquals("legacy", it.getString(0))
            assertEquals(0f, it.getFloat(1), 0f)
        }
        upgraded.close()
        context.deleteDatabase(name)
    }

    @Test
    fun splitPairPersistsForEitherDocumentAndKeepsDividerState() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "notepad-split-pair-test.db"
            context.deleteDatabase(name)
            val repo = NotePadRepository(context, name)
            val left = repo.createNotebook("Slides", null)
            val right = repo.createNotebook("Notes", null)
            val saved = repo.saveSplitPair(left, right, dividerRatio = 0.67f)

            assertEquals(saved, repo.splitPair(left))
            assertEquals(saved, repo.splitPair(right))

            repo.updateSplitPair(saved.id, dividerRatio = 0.33f, swapped = true)
            val updated = repo.splitPair(right)
            assertEquals(0.33f, updated?.dividerRatio ?: 0f, 0f)
            assertEquals(true, updated?.swapped)
            repo.helper.close()
            context.deleteDatabase(name)
        }
    }
}
