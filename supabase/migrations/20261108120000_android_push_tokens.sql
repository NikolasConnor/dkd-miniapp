create table if not exists public.android_push_tokens (
    id bigserial primary key,
    user_id bigint not null,
    fcm_token text not null unique,
    updated_at timestamptz not null default now(),
    disabled_at timestamptz,
    created_at timestamptz not null default now()
);
create index if not exists android_push_tokens_user_id_idx
    on public.android_push_tokens (user_id) where disabled_at is null;
alter table public.android_push_tokens enable row level security;
revoke all on public.android_push_tokens from anon, authenticated;
