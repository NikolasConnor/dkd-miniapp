-- Private server-managed bucket for citizen social profile covers and post media.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'social-media',
  'social-media',
  false,
  10485760,
  array['image/jpeg','image/png','image/webp']
)
on conflict (id) do nothing;
