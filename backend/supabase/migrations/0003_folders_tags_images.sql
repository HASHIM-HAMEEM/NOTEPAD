create table if not exists folders (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  name_enc bytea not null,
  color text,
  sort_order integer not null default 0,
  deleted_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists tags (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  name_enc bytea not null,
  color text,
  deleted_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists notebook_tags (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  notebook_id uuid not null references notebooks(id) on delete cascade,
  tag_id uuid not null references tags(id) on delete cascade,
  deleted_at timestamptz,
  updated_at timestamptz not null default now(),
  unique (notebook_id, tag_id)
);

create table if not exists page_images (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  page_id uuid not null references pages(id) on delete cascade,
  asset_id uuid not null references assets(id),
  x_pt real not null,
  y_pt real not null,
  w_pt real not null,
  h_pt real not null,
  deleted_at timestamptz,
  updated_at timestamptz not null default now()
);

alter table notebooks add column if not exists folder_id uuid references folders(id) on delete set null;
alter table pages add column if not exists bookmarked boolean not null default false;

create index if not exists folders_user_updated_idx on folders(user_id, updated_at);
create index if not exists tags_user_updated_idx on tags(user_id, updated_at);
create index if not exists notebook_tags_user_updated_idx on notebook_tags(user_id, updated_at);
create index if not exists notebook_tags_notebook_idx on notebook_tags(notebook_id);
create index if not exists notebook_tags_tag_idx on notebook_tags(tag_id);
create index if not exists page_images_user_updated_idx on page_images(user_id, updated_at);
create index if not exists page_images_page_idx on page_images(page_id);

do $$ declare t text;
begin
  foreach t in array array['folders','tags','notebook_tags','page_images'] loop
    execute format('alter table %I enable row level security', t);
    execute format('drop policy if exists %I_owner on %I', t, t);
    execute format('create policy %I_owner on %I for all using (auth.uid() = user_id) with check (auth.uid() = user_id)', t, t);
  end loop;
end $$;

do $$ declare t text;
begin
  if exists (select 1 from pg_publication where pubname = 'powersync') then
    foreach t in array array['folders','tags','notebook_tags','page_images'] loop
      if not exists (
        select 1 from pg_publication_tables
        where pubname = 'powersync' and schemaname = 'public' and tablename = t
      ) then
        execute format('alter publication powersync add table public.%I', t);
      end if;
    end loop;
  end if;
end $$;
