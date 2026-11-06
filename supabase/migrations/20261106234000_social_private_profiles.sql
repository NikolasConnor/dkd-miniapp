-- Private profile controls and follow requests. Access is through the authenticated DKD backend only.
alter table public.social_profiles
    add column if not exists is_private boolean not null default false;

create table if not exists public.social_follow_requests (
    requester_user_id bigint not null references public.subjects(user_id) on delete cascade,
    target_user_id bigint not null references public.subjects(user_id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (requester_user_id, target_user_id),
    constraint social_follow_requests_no_self check (requester_user_id <> target_user_id)
);
create index if not exists social_follow_requests_target_created_idx
    on public.social_follow_requests (target_user_id, created_at desc);

alter table public.social_follow_requests enable row level security;
revoke all on public.social_follow_requests from anon, authenticated;
