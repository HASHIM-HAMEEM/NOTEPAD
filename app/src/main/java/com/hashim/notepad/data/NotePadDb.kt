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
        db.execSQL(OUTBOX); db.execSQL(SYNC_META)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // FTS4 external-content triggers made every DELETE on recognition fail; search uses LIKE, so drop them.
            for (sql in listOf(
                "drop trigger if exists recognition_ai", "drop trigger if exists recognition_ad",
                "drop trigger if exists recognition_au", "drop table if exists recognition_fts"
            )) db.execSQL(sql)
        }
        if (oldVersion < 3) {
            db.execSQL(OUTBOX); db.execSQL(SYNC_META)
            // Everything that already exists must be pushed once sync is configured.
            for (t in listOf("notebooks", "pages", "text_blocks", "assets")) db.execSQL("insert or ignore into outbox(table_name, row_id) select '$t', id from $t")
            db.execSQL("insert or ignore into outbox(table_name, row_id) select 'stroke_batches', page_id from stroke_batches")
            db.execSQL("insert or ignore into outbox(table_name, row_id) select distinct 'recognition', page_id from recognition")
        }
        if (oldVersion < 4) {
            db.execSQL(PAGE_IMAGES)
            db.execSQL("create index if not exists page_images_page_idx on page_images(page_id)")
        }
        if (oldVersion < 5) {
            db.execSQL("alter table notebooks add column folder_id text")
            db.execSQL(FOLDERS)
            db.execSQL(TAGS)
            db.execSQL(NOTEBOOK_TAGS)
            db.execSQL("create index if not exists folders_active_name_idx on folders(name collate nocase) where deleted_at is null")
            db.execSQL("create unique index if not exists tags_active_name_idx on tags(name collate nocase) where deleted_at is null")
            db.execSQL("create unique index if not exists notebook_tags_pair_idx on notebook_tags(notebook_id, tag_id)")
            db.execSQL("create index if not exists notebook_tags_tag_idx on notebook_tags(tag_id)")
            db.execSQL("insert or ignore into outbox(table_name, row_id) select 'page_images', id from page_images")
        }
        if (oldVersion < 6) {
            db.execSQL("alter table pages add column bookmarked integer not null default 0")
            db.execSQL("alter table text_blocks add column font_size_pt real not null default 12")
        }
        if (oldVersion < 7) {
            db.execSQL("alter table text_blocks add column color_argb integer")
        }
        if (oldVersion < 8) {
            db.execSQL("alter table notebooks add column document_kind text not null default 'notes'")
            db.execSQL("alter table notebooks add column last_page_index integer not null default 0")
            db.execSQL("alter table notebooks add column last_opened_at integer")
            db.execSQL("alter table notebooks add column file_asset_id text")
            db.execSQL("alter table notebooks add column file_mime_type text")
            db.execSQL("alter table notebooks add column original_filename text")
            db.execSQL("update notebooks set document_kind = 'pdf' where exists (select 1 from pages where pages.notebook_id = notebooks.id and pages.kind = 'pdf' and pages.deleted_at is null)")
        }
        if (oldVersion < 9) {
            db.execSQL("alter table pages add column rotation integer not null default 0")
        }
        if (oldVersion < 10) {
            db.execSQL(PDF_MARKS)
            db.execSQL("create index if not exists pdf_marks_notebook_idx on pdf_marks(notebook_id, page_id, updated_at)")
        }
    }

    companion object {
        const val VERSION = 10

        /** Rows changed locally and not yet pushed. row_id is the page id for stroke_batches and recognition. */
        const val OUTBOX = "create table if not exists outbox (table_name text not null, row_id text not null, primary key (table_name, row_id))"
        const val SYNC_META = "create table if not exists sync_meta (key text primary key, value text not null)"
        private const val FOLDERS = """create table if not exists folders (
              id text primary key,
              name text not null,
              color text,
              sort_order integer not null default 0,
              deleted_at integer,
              created_at integer not null,
              updated_at integer not null
            )"""
        private const val TAGS = """create table if not exists tags (
              id text primary key,
              name text not null,
              color text,
              deleted_at integer,
              created_at integer not null,
              updated_at integer not null
            )"""
        private const val NOTEBOOK_TAGS = """create table if not exists notebook_tags (
              id text primary key,
              notebook_id text not null references notebooks(id) on delete cascade,
              tag_id text not null references tags(id) on delete cascade,
              deleted_at integer,
              updated_at integer not null,
              unique (notebook_id, tag_id)
            )"""
        private const val PAGE_IMAGES = """create table if not exists page_images (
              id text primary key,
              page_id text not null references pages(id) on delete cascade,
              asset_id text not null references assets(id),
              x_pt real not null,
              y_pt real not null,
              w_pt real not null,
              h_pt real not null,
              updated_at integer not null,
              deleted_at integer
            )"""
        private const val PDF_MARKS = """create table if not exists pdf_marks (
              id text primary key,
              notebook_id text not null references notebooks(id) on delete cascade,
              page_id text not null references pages(id) on delete cascade,
              kind text not null,
              color_argb integer not null,
              quads text not null,
              char_start integer,
              char_end integer,
              text text not null,
              note text,
              created_at integer not null,
              updated_at integer not null,
              deleted integer not null default 0
            )"""

        private val SCHEMA = """
            create table notebooks (
              id text primary key,
              title text not null,
              folder_id text,
              document_kind text not null default 'notes',
              last_page_index integer not null default 0,
              last_opened_at integer,
              file_asset_id text,
              file_mime_type text,
              original_filename text,
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
              bookmarked integer not null default 0,
              rotation integer not null default 0,
              deleted_at integer,
              created_at integer not null,
              updated_at integer not null
            );
            create index pages_notebook_idx on pages(notebook_id, sort_key);
            $FOLDERS;
            create index folders_active_name_idx on folders(name collate nocase) where deleted_at is null;
            $TAGS;
            create unique index tags_active_name_idx on tags(name collate nocase) where deleted_at is null;
            $NOTEBOOK_TAGS;
            create unique index notebook_tags_pair_idx on notebook_tags(notebook_id, tag_id);
            create index notebook_tags_tag_idx on notebook_tags(tag_id);
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
              font_size_pt real not null default 12,
              color_argb integer,
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
            $PAGE_IMAGES;
            create index page_images_page_idx on page_images(page_id);
            $PDF_MARKS;
            create index pdf_marks_notebook_idx on pdf_marks(notebook_id, page_id, updated_at);
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
