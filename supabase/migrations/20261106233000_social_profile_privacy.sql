alter table public.social_profiles
    add column if not exists show_connections boolean not null default true;
