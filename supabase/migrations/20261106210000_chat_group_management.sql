
create or replace function public.transfer_chat_ownership(
  p_conversation_id bigint,
  p_actor_user_id bigint,
  p_new_owner_user_id bigint
) returns boolean
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if p_actor_user_id = p_new_owner_user_id then
    raise exception 'new owner must differ from current owner';
  end if;
  update public.chat_participants
     set member_role = 'admin'
   where conversation_id = p_conversation_id
     and user_id = p_actor_user_id
     and member_role = 'owner'
     and left_at is null;
  if not found then
    raise exception 'current owner not found';
  end if;
  update public.chat_participants
     set member_role = 'owner'
   where conversation_id = p_conversation_id
     and user_id = p_new_owner_user_id
     and left_at is null;
  if not found then
    raise exception 'new owner is not an active participant';
  end if;
  return true;
end;
$$;
revoke all on function public.transfer_chat_ownership(bigint,bigint,bigint) from public, anon, authenticated;
grant execute on function public.transfer_chat_ownership(bigint,bigint,bigint) to service_role;
