-- DKD social feed foundation. Review and test before applying to production Supabase.
-- The API uses the server-side service role after verifying the user's DKD session.
-- Direct client access is intentionally blocked by RLS and grants.

create table if not exists public.social_posts (
    id bigserial primary key,
    author_user_id bigint not null references public.subjects(user_id) on delete cascade,
    body text not null default '',
    status text not null default 'published'
        check (status in ('published', 'hidden', 'removed')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    edited_at timestamptz,
    deleted_at timestamptz,
    constraint social_posts_body_length check (char_length(body) <= 5000)
);

create index if not exists social_posts_feed_idx
    on public.social_posts (created_at desc, id desc)
    where status = 'published' and deleted_at is null;
create index if not exists social_posts_author_idx
    on public.social_posts (author_user_id, created_at desc);

create table if not exists public.social_post_media (
    id bigserial primary key,
    post_id bigint not null references public.social_posts(id) on delete cascade,
    owner_user_id bigint not null references public.subjects(user_id) on delete cascade,
    storage_path text not null unique,
    media_type text not null check (media_type in ('image', 'video')),
    mime_type text not null check (mime_type in ('image/jpeg', 'image/png', 'image/webp', 'video/mp4')),
    size_bytes bigint not null check (size_bytes > 0 and size_bytes <= 52428800),
    width integer check (width is null or width > 0),
    height integer check (height is null or height > 0),
    duration_seconds numeric(8,2) check (duration_seconds is null or duration_seconds > 0),
    created_at timestamptz not null default now()
);
create index if not exists social_post_media_post_idx
    on public.social_post_media (post_id, id);

create table if not exists public.social_comments (
    id bigserial primary key,
    post_id bigint not null references public.social_posts(id) on delete cascade,
    author_user_id bigint not null references public.subjects(user_id) on delete cascade,
    parent_comment_id bigint references public.social_comments(id) on delete cascade,
    body text not null,
    status text not null default 'published'
        check (status in ('published', 'hidden', 'removed')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    constraint social_comments_body_length check (char_length(btrim(body)) between 1 and 2000)
);
create index if not exists social_comments_post_idx
    on public.social_comments (post_id, created_at, id)
    where status = 'published' and deleted_at is null;
create index if not exists social_comments_author_idx
    on public.social_comments (author_user_id, created_at desc);

create table if not exists public.social_reactions (
    id bigserial primary key,
    post_id bigint not null references public.social_posts(id) on delete cascade,
    user_id bigint not null references public.subjects(user_id) on delete cascade,
    reaction text not null default 'like'
        check (reaction in ('like', 'love', 'laugh', 'support')),
    created_at timestamptz not null default now(),
    constraint social_reactions_one_per_user unique (post_id, user_id)
);
create index if not exists social_reactions_post_idx
    on public.social_reactions (post_id, reaction);

create table if not exists public.social_user_blocks (
    blocker_user_id bigint not null references public.subjects(user_id) on delete cascade,
    blocked_user_id bigint not null references public.subjects(user_id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (blocker_user_id, blocked_user_id),
    constraint social_user_blocks_no_self check (blocker_user_id <> blocked_user_id)
);
create index if not exists social_user_blocks_blocked_idx
    on public.social_user_blocks (blocked_user_id, blocker_user_id);

create table if not exists public.social_reports (
    id bigserial primary key,
    reporter_user_id bigint not null references public.subjects(user_id) on delete cascade,
    post_id bigint references public.social_posts(id) on delete cascade,
    comment_id bigint references public.social_comments(id) on delete cascade,
    reason text not null check (reason in ('spam', 'harassment', 'violence', 'sexual_content', 'illegal_content', 'other')),
    details text not null default '',
    status text not null default 'open' check (status in ('open', 'reviewing', 'resolved', 'dismissed')),
    reviewed_by bigint references public.subjects(user_id) on delete set null,
    reviewed_at timestamptz,
    created_at timestamptz not null default now(),
    constraint social_reports_one_target check (
        (post_id is not null and comment_id is null) or
        (post_id is null and comment_id is not null)
    ),
    constraint social_reports_details_length check (char_length(details) <= 2000)
);
create index if not exists social_reports_queue_idx
    on public.social_reports (status, created_at asc);

alter table public.social_posts enable row level security;
alter table public.social_post_media enable row level security;
alter table public.social_comments enable row level security;
alter table public.social_reactions enable row level security;
alter table public.social_user_blocks enable row level security;
alter table public.social_reports enable row level security;

revoke all on public.social_posts from anon, authenticated;
revoke all on public.social_post_media from anon, authenticated;
revoke all on public.social_comments from anon, authenticated;
revoke all on public.social_reactions from anon, authenticated;
revoke all on public.social_user_blocks from anon, authenticated;
revoke all on public.social_reports from anon, authenticated;
