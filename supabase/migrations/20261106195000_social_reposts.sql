-- Add native repost support without copying the original post or its media.
-- Repost entries remain normal feed records; the original post is rendered as an embedded card.
alter table public.social_posts
    add column if not exists repost_of_post_id bigint
    references public.social_posts(id) on delete set null;

do $$
begin
    if not exists (
        select 1 from pg_constraint
        where conname = 'social_posts_no_self_repost'
          and conrelid = 'public.social_posts'::regclass
    ) then
        alter table public.social_posts
            add constraint social_posts_no_self_repost
            check (repost_of_post_id is null or repost_of_post_id <> id);
    end if;
end $$;

create index if not exists social_posts_repost_target_idx
    on public.social_posts (repost_of_post_id)
    where repost_of_post_id is not null;
create unique index if not exists social_posts_repost_once_idx
    on public.social_posts (author_user_id, repost_of_post_id)
    where repost_of_post_id is not null and deleted_at is null;
