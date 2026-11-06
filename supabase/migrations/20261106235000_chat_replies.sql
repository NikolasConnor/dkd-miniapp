-- Reply links for chat messages. The API additionally verifies that the
-- referenced message belongs to the same conversation and is still available.
alter table public.chat_messages
    add column if not exists reply_to_message_id bigint
    references public.chat_messages(id) on delete set null;

create index if not exists chat_messages_reply_to_idx
    on public.chat_messages(reply_to_message_id)
    where reply_to_message_id is not null;
