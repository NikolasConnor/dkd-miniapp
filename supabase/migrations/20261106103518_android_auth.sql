-- Standalone Android app login: one-time pairing codes and revocable sessions.
create table if not exists public.android_login_codes (
    id bigserial primary key,
    user_id bigint not null,
    token_hash text not null unique,
    expires_at timestamptz not null,
    consumed_at timestamptz,
    created_by bigint not null,
    created_at timestamptz not null default now()
);
create index if not exists android_login_codes_user_id_idx
    on public.android_login_codes (user_id, created_at desc);
create index if not exists android_login_codes_expiry_idx
    on public.android_login_codes (expires_at);

create table if not exists public.android_sessions (
    id bigserial primary key,
    user_id bigint not null,
    token_hash text not null unique,
    expires_at timestamptz not null,
    revoked_at timestamptz,
    created_at timestamptz not null default now()
);
create index if not exists android_sessions_user_id_idx
    on public.android_sessions (user_id, created_at desc);
create index if not exists android_sessions_expiry_idx
    on public.android_sessions (expires_at);

alter table public.android_login_codes enable row level security;
alter table public.android_sessions enable row level security;
revoke all on public.android_login_codes from anon, authenticated;
revoke all on public.android_sessions from anon, authenticated;
