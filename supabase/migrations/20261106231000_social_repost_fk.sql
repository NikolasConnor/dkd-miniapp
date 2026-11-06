-- Do not turn repost records into ordinary posts if an original is hard-deleted.
-- Soft deletion remains the normal moderation/user deletion path.
alter table public.social_posts
    drop constraint if exists social_posts_repost_of_post_id_fkey;
alter table public.social_posts
    add constraint social_posts_repost_of_post_id_fkey
    foreign key (repost_of_post_id)
    references public.social_posts(id)
    on delete cascade;
