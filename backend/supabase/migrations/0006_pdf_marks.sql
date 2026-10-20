-- Batch 8 PDF marks and page rotation.
alter table public.pages add column if not exists rotation integer not null default 0;

create table if not exists public.pdf_marks (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  notebook_id uuid not null,
  page_id uuid not null,
  kind text not null check (kind in ('highlight', 'underline', 'strike', 'squiggly')),
  color_argb bigint not null,
  quads jsonb not null,
  char_start integer,
  char_end integer,
  text_enc bytea not null,
  note_enc bytea,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  deleted boolean not null default false
);

alter table public.pdf_marks enable row level security;
create policy "pdf marks owner" on public.pdf_marks
  for all using (auth.uid() = user_id)
  with check (auth.uid() = user_id);
