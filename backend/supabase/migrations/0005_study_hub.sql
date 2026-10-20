alter table notebooks add column if not exists document_kind text not null default 'notes';
alter table notebooks add column if not exists last_page_index integer not null default 0;
alter table notebooks add column if not exists last_opened_at bigint;
alter table notebooks add column if not exists file_asset_id uuid references assets(id) on delete set null;
alter table notebooks add column if not exists file_mime_type text;
alter table notebooks add column if not exists original_filename text;
