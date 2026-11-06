-- Private chat storage. All access is mediated by the authenticated backend.
create table if not exists public.chat_conversations (
    id bigserial primary key,
    type text not null check (type in ('direct','group')),
    title text,
    direct_key text unique,
    created_by bigint not null references public.subjects(user_id) on delete restrict,
    created_at timestamptz not null default now(),
    last_message_at timestamptz not null default now(),
    constraint chat_conversation_shape check (
      (type='direct' and title is null and direct_key is not null) or
      (type='group' and title is not null and direct_key is null)
    )
);
create table if not exists public.chat_participants (
    conversation_id bigint not null references public.chat_conversations(id) on delete cascade,
    user_id bigint not null references public.subjects(user_id) on delete cascade,
    member_role text not null default 'member' check (member_role in ('owner','admin','member')),
    joined_at timestamptz not null default now(),
    left_at timestamptz,
    last_read_at timestamptz,
    primary key (conversation_id,user_id)
);
create table if not exists public.chat_messages (
    id bigserial primary key,
    conversation_id bigint not null references public.chat_conversations(id) on delete cascade,
    sender_user_id bigint not null references public.subjects(user_id) on delete restrict,
    body text not null check (char_length(btrim(body)) between 1 and 4000),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz
);
create index if not exists chat_participants_user_active_idx
    on public.chat_participants(user_id, joined_at desc) where left_at is null;
create index if not exists chat_messages_conversation_id_idx
    on public.chat_messages(conversation_id, id desc) where deleted_at is null;
create index if not exists chat_conversations_last_message_idx
    on public.chat_conversations(last_message_at desc);
alter table public.chat_conversations enable row level security;
alter table public.chat_participants enable row level security;
alter table public.chat_messages enable row level security;
revoke all on public.chat_conversations from anon, authenticated;
revoke all on public.chat_participants from anon, authenticated;
revoke all on public.chat_messages from anon, authenticated;
grant all on public.chat_conversations, public.chat_participants, public.chat_messages to service_role;
