-- NotePad core schema. Content columns hold client-side encrypted bytes (XChaCha20-Poly1305);
-- only ids, ownership, ordering, sizes and timestamps are plaintext so sync rules and ordering work.

create extension if not exists pgcrypto;

create table if not exists notebooks (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  title_enc bytea,                 -- encrypted title
  cover_color text,                -- plaintext, cosmetic
  sort_order integer not null default 0,
  deleted_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists pages (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  notebook_id uuid not null references notebooks(id) on delete cascade,
  sort_key double precision not null,           -- fractional ordering, insert-between friendly
  kind text not null check (kind in ('blank','lined','grid','pdf')),
  asset_id uuid,                                 -- PDF asset when kind = 'pdf'
  pdf_page_index integer,                        -- 0-based page inside the PDF
  width_pt real not null default 595,            -- page size in PDF points
  height_pt real not null default 842,
  deleted_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

-- One row per page per layer: the whole stroke batch for a page, encrypted. Replaced on edit.
create table if not exists stroke_batches (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  page_id uuid not null references pages(id) on delete cascade,
  layer integer not null default 0,
  revision integer not null default 1,
  stroke_count integer not null default 0,
  data_enc bytea not null,                       -- encrypted ink protobuf bundle
  byte_size integer not null default 0,
  updated_at timestamptz not null default now(),
  unique (page_id, layer)
);

create table if not exists text_blocks (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  page_id uuid not null references pages(id) on delete cascade,
  x_pt real not null, y_pt real not null, w_pt real not null,
  direction text not null default 'auto' check (direction in ('auto','ltr','rtl')),
  content_enc bytea not null,
  deleted_at timestamptz,
  updated_at timestamptz not null default now()
);

-- Binary assets (PDFs, images) live in object storage; this is the manifest.
create table if not exists assets (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  kind text not null check (kind in ('pdf','image')),
  storage_key text not null,                      -- object key in the bucket
  byte_size bigint not null,
  sha256 text not null,
  page_count integer,
  name_enc bytea,
  created_at timestamptz not null default now()
);

-- Handwriting recognition / OCR results per page, encrypted; searched on-device only.
create table if not exists recognition (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  page_id uuid not null references pages(id) on delete cascade,
  source text not null check (source in ('ink','pdf_text','ocr')),
  language text,
  text_enc bytea not null,
  updated_at timestamptz not null default now()
);

create table if not exists device_keys (
  device_id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  name text not null,
  public_key bytea not null,
  wrapped_account_key bytea,                      -- account key wrapped for this device
  created_at timestamptz not null default now(),
  last_seen_at timestamptz
);

create index if not exists pages_notebook_idx on pages(notebook_id, sort_key);
create index if not exists stroke_batches_page_idx on stroke_batches(page_id);
create index if not exists text_blocks_page_idx on text_blocks(page_id);
create index if not exists recognition_page_idx on recognition(page_id);

-- updated_at maintenance
create or replace function set_updated_at() returns trigger language plpgsql as $$
begin new.updated_at = now(); return new; end $$;
do $$ declare t text;
begin
  foreach t in array array['notebooks','pages','stroke_batches','text_blocks','recognition'] loop
    execute format('drop trigger if exists %I_updated_at on %I', t, t);
    execute format('create trigger %I_updated_at before update on %I for each row execute function set_updated_at()', t, t);
  end loop;
end $$;

-- Row-level security: a user sees only their own rows.
do $$ declare t text;
begin
  foreach t in array array['notebooks','pages','stroke_batches','text_blocks','assets','recognition','device_keys'] loop
    execute format('alter table %I enable row level security', t);
    execute format('drop policy if exists %I_owner on %I', t, t);
    execute format('create policy %I_owner on %I for all using (auth.uid() = user_id) with check (auth.uid() = user_id)', t, t);
  end loop;
end $$;

-- PowerSync needs a publication over the synced tables.
do $$ begin
  if not exists (select 1 from pg_publication where pubname = 'powersync') then
    create publication powersync for table notebooks, pages, stroke_batches, text_blocks, assets, recognition, device_keys;
  end if;
end $$;
