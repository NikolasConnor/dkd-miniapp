-- Per-user subscription preferences for important DKD news.
-- News metadata remains backwards-compatible in the existing public.news.text field.
create table if not exists public.news_preferences (
    user_id bigint primary key,
    enabled boolean not null default true,
    updated_at timestamptz not null default now()
);
create index if not exists news_preferences_enabled_idx
    on public.news_preferences (enabled) where enabled = true;
alter table public.news_preferences enable row level security;
