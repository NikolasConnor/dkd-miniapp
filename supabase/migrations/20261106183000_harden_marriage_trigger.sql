-- Harden the marriage integrity trigger against search_path substitution.
create or replace function public.dkd_guard_single_active_marriage()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
    if new.status in ('active','divorce_pending') and exists (
        select 1
        from public.marriages m
        where m.status in ('active','divorce_pending')
          and (new.id is null or m.id <> new.id)
          and (
              m.partner_one_user_id in (new.partner_one_user_id,new.partner_two_user_id)
              or m.partner_two_user_id in (new.partner_one_user_id,new.partner_two_user_id)
          )
    ) then
        raise exception 'A citizen may have only one active marriage';
    end if;
    return new;
end;
$$;
revoke all on function public.dkd_guard_single_active_marriage() from public, anon, authenticated;
