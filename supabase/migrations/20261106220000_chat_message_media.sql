create table if not exists public.chat_message_media (
    id bigserial primary key,
    message_id bigint not null references public.chat_messages(id) on delete cascade,
    conversation_id bigint not null references public.chat_conversations(id) on delete cascade,
    owner_user_id bigint not null references public.subjects(user_id) on delete restrict,
    storage_path text not null unique,
    media_type text not null check (media_type in ('image','video')),
    mime_type text not null check (mime_type in ('image/jpeg','image/png','image/webp','video/mp4')),
    size_bytes bigint not null check (size_bytes between 1 and 26214400),
    created_at timestamptz not null default now()
);
create index if not exists chat_message_media_message_idx
    on public.chat_message_media(message_id);
alter table public.chat_message_media enable row level security;
revoke all on public.chat_message_media from anon, authenticated;
grant all on public.chat_message_media to service_role;
