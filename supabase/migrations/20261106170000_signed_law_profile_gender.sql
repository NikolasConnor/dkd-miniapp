-- Implement Article 1 of signed DKD Law No. 3: citizen-selected profile gender.
alter table public.subjects
    add column if not exists gender text;
alter table public.subjects
    drop constraint if exists subjects_gender_check;
alter table public.subjects
    add constraint subjects_gender_check
    check (gender is null or gender in ('male','female'));
