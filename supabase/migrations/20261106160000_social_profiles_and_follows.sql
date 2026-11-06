-- DKD public social profiles and follows. Apply only after review.
create table if not exists public.social_profiles (
    user_id bigint primary key references public.subjects(user_id) on delete cascade,
    bio text not null default '',
    cover_storage_path text,
    updated_at timestamptz not null default now(),
    constraint social_profiles_bio_length check (char_length(bio) <= 280)
);

create table if not exists public.social_follows (
    follower_user_id bigint not null references public.subjects(user_id) on delete cascade,
    followed_user_id bigint not null references public.subjects(user_id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (follower_user_id, followed_user_id),
    constraint social_follows_no_self check (follower_user_id <> followed_user_id)
);
create index if not exists social_follows_followed_idx
    on public.social_follows (followed_user_id, created_at desc);
create index if not exists social_follows_follower_idx
    on public.social_follows (follower_user_id, created_at desc);

alter table public.social_profiles enable row level security;
alter table public.social_follows enable row level security;
revoke all on public.social_profiles from anon, authenticated;
revoke all on public.social_follows from anon, authenticated;
