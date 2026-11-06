-- Private per-user bookmarks for social posts.
create table if not exists public.social_saved_posts (
    user_id bigint not null references public.subjects(user_id) on delete cascade,
    post_id bigint not null references public.social_posts(id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (user_id, post_id)
);
create index if not exists social_saved_posts_user_created_idx
    on public.social_saved_posts (user_id, created_at desc);
alter table public.social_saved_posts enable row level security;
revoke all on public.social_saved_posts from anon, authenticated;
