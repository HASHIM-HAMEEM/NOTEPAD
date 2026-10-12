package com.hashim.notepad.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local source of truth. Mirrors backend/supabase/migrations/0001_core.sql with the same ids so a
 * sync layer can replicate rows one-to-one. Content columns are stored in plaintext locally and
 * encrypted only when leaving the device.
 */
class NotePadDb(context: Context) : SQLiteOpenHelper(context, "notepad.db", null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        SCHEMA.split(Regex(";\\s*\\n(?=\\s*create)")).map { it.trim().removeSuffix(";") }.filter { it.isNotEmpty() }.forEach { db.execSQL(it) }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // FTS4 external-content triggers made every DELETE on recognition fail; search uses LIKE, so drop them.
        for (sql in listOf(
            "drop trigger if exists recognition_ai", "drop trigger if exists recognition_ad",
            "drop trigger if exists recognition_au", "drop table if exists recognition_fts"
        )) db.execSQL(sql)
    }

    companion object {
        const val VERSION = 2

        private val SCHEMA = """
            create table notebooks (
              id text primary key,
              title text not null,
              cover_color text,
              language text not null default 'ar',
              sort_order integer not null default 0,
              deleted_at integer,
              created_at integer not null,
              updated_at integer not null
            );
            create table pages (
              id text primary key,
              notebook_id text not null references notebooks(id) on delete cascade,
              sort_key real not null,
              kind text not null,
              asset_id text,
              pdf_page_index integer,
              width_pt real not null default 595,
              height_pt real not null default 842,
              deleted_at integer,
              created_at integer not null,
              updated_at integer not null
            );
            create index pages_notebook_idx on pages(notebook_id, sort_key);
            create table stroke_batches (
              id text primary key,
              page_id text not null references pages(id) on delete cascade,
              layer integer not null default 0,
              revision integer not null default 1,
              stroke_count integer not null default 0,
              data blob not null,
              updated_at integer not null,
              unique (page_id, layer)
            );
            create table text_blocks (
              id text primary key,
              page_id text not null references pages(id) on delete cascade,
              x_pt real not null, y_pt real not null, w_pt real not null,
              direction text not null default 'auto',
              content text not null,
              deleted_at integer,
              updated_at integer not null
            );
            create index text_blocks_page_idx on text_blocks(page_id);
            create table assets (
              id text primary key,
              kind text not null,
              local_path text not null,
              storage_key text,
              byte_size integer not null,
              sha256 text not null,
              page_count integer,
              name text,
              created_at integer not null
            );
            create table recognition (
              id text primary key,
              page_id text not null references pages(id) on delete cascade,
              source text not null,
              language text,
              text text not null,
              updated_at integer not null
            );
        """.trimIndent()
    }
}
