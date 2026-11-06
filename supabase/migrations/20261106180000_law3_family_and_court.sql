-- DKD Law No. 3: marriage registry, divorce consent, court cases and appeals.
create table if not exists public.marriages (
    id bigserial primary key,
    partner_one_user_id bigint not null references public.subjects(user_id) on delete restrict,
    partner_two_user_id bigint not null references public.subjects(user_id) on delete restrict,
    proposed_by bigint not null references public.subjects(user_id) on delete restrict,
    status text not null default 'proposed'
        check (status in ('proposed','active','rejected','divorce_pending','divorced')),
    proposed_at timestamptz not null default now(),
    accepted_at timestamptz,
    ended_at timestamptz,
    divorce_requested_by bigint references public.subjects(user_id) on delete restrict,
    divorce_requested_at timestamptz,
    created_at timestamptz not null default now(),
    constraint marriages_distinct_partners check (partner_one_user_id <> partner_two_user_id),
    constraint marriages_proposer_is_partner check (proposed_by in (partner_one_user_id,partner_two_user_id))
);
create index if not exists marriages_partner_one_idx on public.marriages(partner_one_user_id,created_at desc);
create index if not exists marriages_partner_two_idx on public.marriages(partner_two_user_id,created_at desc);
create unique index if not exists marriages_one_active_per_partner_one
    on public.marriages(partner_one_user_id) where status in ('active','divorce_pending');
create unique index if not exists marriages_one_active_per_partner_two
    on public.marriages(partner_two_user_id) where status in ('active','divorce_pending');

create table if not exists public.court_cases (
    id bigserial primary key,
    plaintiff_user_id bigint not null references public.subjects(user_id) on delete restrict,
    respondent_user_id bigint not null references public.subjects(user_id) on delete restrict,
    title text not null check (char_length(btrim(title)) between 4 and 160),
    claim text not null check (char_length(btrim(claim)) between 20 and 8000),
    response_text text,
    status text not null default 'awaiting_response'
        check (status in ('awaiting_response','awaiting_ai_judge','decided','appealed','closed')),
    decision_text text,
    decision_public boolean not null default false,
    decided_at timestamptz,
    responded_at timestamptz,
    created_at timestamptz not null default now(),
    constraint court_cases_distinct_parties check (plaintiff_user_id <> respondent_user_id),
    constraint court_cases_response_length check (response_text is null or char_length(response_text) <= 8000),
    constraint court_cases_decision_length check (decision_text is null or char_length(decision_text) <= 12000)
);
create index if not exists court_cases_plaintiff_idx on public.court_cases(plaintiff_user_id,created_at desc);
create index if not exists court_cases_respondent_idx on public.court_cases(respondent_user_id,created_at desc);
create index if not exists court_cases_archive_idx on public.court_cases(decided_at desc)
    where status='decided' and decision_public=true;

create table if not exists public.court_appeals (
    id bigserial primary key,
    case_id bigint not null references public.court_cases(id) on delete restrict,
    appellant_user_id bigint not null references public.subjects(user_id) on delete restrict,
    grounds text not null check (char_length(btrim(grounds)) between 20 and 5000),
    status text not null default 'awaiting_ai_appeal_judge'
        check (status in ('awaiting_ai_appeal_judge','decided','dismissed')),
    decision_text text,
    decided_at timestamptz,
    created_at timestamptz not null default now()
);
create index if not exists court_appeals_case_idx on public.court_appeals(case_id,created_at desc);
create unique index if not exists court_appeals_one_pending_per_case
    on public.court_appeals(case_id) where status='awaiting_ai_appeal_judge';

alter table public.marriages enable row level security;
alter table public.court_cases enable row level security;
alter table public.court_appeals enable row level security;
revoke all on public.marriages from anon, authenticated;
revoke all on public.court_cases from anon, authenticated;
revoke all on public.court_appeals from anon, authenticated;
