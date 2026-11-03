-- News notification preferences for DKD Mini App.
-- Existing news table remains unchanged; metadata is stored compatibly in news.text.
create table if not exists public.news_preferences (
    user_id bigint primary key,
    enabled boolean not null default true,
    updated_at timestamptz not null default now()
);
create index if not exists news_preferences_enabled_idx
    on public.news_preferences (enabled) where enabled = true;
