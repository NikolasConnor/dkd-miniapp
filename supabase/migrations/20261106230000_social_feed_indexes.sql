-- Index threaded comment lookups and allow reposting again after moderation unpublishes a prior repost.
create index if not exists social_comments_parent_idx
    on public.social_comments (parent_comment_id)
    where parent_comment_id is not null;

drop index if exists public.social_posts_repost_once_idx;
create unique index social_posts_repost_once_idx
    on public.social_posts (author_user_id, repost_of_post_id)
    where repost_of_post_id is not null
      and status = 'published'
      and deleted_at is null;
