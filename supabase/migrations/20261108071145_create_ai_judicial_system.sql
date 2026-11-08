create table if not exists public.judicial_cases (
  id bigserial primary key,
  plaintiff_user_id bigint not null,
  defendant_user_id bigint not null,
  title text not null check (char_length(title) between 3 and 160),
  claim_text text not null check (char_length(claim_text) between 20 and 12000),
  defendant_response text,
  status text not null default 'awaiting_response'
    check (status in ('awaiting_response','ready_for_judge','decided','appeal_pending','closed')),
  ai_decision text,
  ai_reasoning text,
  ai_legal_basis jsonb not null default '[]'::jsonb,
  final_decision text,
  created_at timestamptz not null default now(),
  decided_at timestamptz,
  closed_at timestamptz,
  constraint judicial_cases_distinct_parties check (plaintiff_user_id <> defendant_user_id)
);
create index if not exists judicial_cases_plaintiff_idx on public.judicial_cases(plaintiff_user_id, created_at desc);
create index if not exists judicial_cases_defendant_idx on public.judicial_cases(defendant_user_id, created_at desc);
create index if not exists judicial_cases_status_idx on public.judicial_cases(status, created_at desc);
create table if not exists public.judicial_appeals (
  id bigserial primary key,
  case_id bigint not null references public.judicial_cases(id) on delete cascade,
  appellant_user_id bigint not null,
  appeal_text text not null check (char_length(appeal_text) between 20 and 8000),
  status text not null default 'pending' check (status in ('pending','upheld','changed')),
  admin_decision text,
  reviewed_by bigint,
  created_at timestamptz not null default now(),
  reviewed_at timestamptz,
  constraint judicial_appeals_one_per_party unique(case_id, appellant_user_id)
);
create index if not exists judicial_appeals_pending_idx on public.judicial_appeals(status, created_at desc);
alter table public.judicial_cases enable row level security;
alter table public.judicial_appeals enable row level security;
revoke all on public.judicial_cases from anon, authenticated;
revoke all on public.judicial_appeals from anon, authenticated;
