-- Client owns updated_at (last-writer-wins across devices); drop the server-side override.
do $$ declare t text;
begin
  foreach t in array array['notebooks','pages','stroke_batches','text_blocks','recognition'] loop
    execute format('drop trigger if exists %I_updated_at on %I', t, t);
  end loop;
end $$;
alter table notebooks add column if not exists language text not null default 'ar';
alter table recognition drop constraint if exists recognition_source_check;
alter table stroke_batches add column if not exists deleted_at timestamptz;
alter table assets add column if not exists updated_at timestamptz not null default now();
alter table device_keys add column if not exists kdf_salt bytea;
alter table device_keys add column if not exists key_check bytea;
create index if not exists notebooks_user_updated_idx on notebooks(user_id, updated_at);
create index if not exists pages_user_updated_idx on pages(user_id, updated_at);
create index if not exists stroke_batches_user_updated_idx on stroke_batches(user_id, updated_at);
create index if not exists text_blocks_user_updated_idx on text_blocks(user_id, updated_at);
create index if not exists recognition_user_updated_idx on recognition(user_id, updated_at);
insert into storage.buckets (id, name, public) values ('assets', 'assets', false) on conflict (id) do nothing;
drop policy if exists "assets owner" on storage.objects;
create policy "assets owner" on storage.objects for all
  using (bucket_id = 'assets' and (storage.foldername(name))[1] = auth.uid()::text)
  with check (bucket_id = 'assets' and (storage.foldername(name))[1] = auth.uid()::text);
