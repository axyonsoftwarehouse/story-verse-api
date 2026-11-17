-- Edição da própria mensagem também em grupo de conversa (chat comum).
--
-- A regra nasceu só para o roleplay (V52) e o cartão pede o mesmo em qualquer grupo: o autor
-- corrige o próprio texto por até 7 dias. O que NÃO muda: só o autor, só mensagem de texto de
-- jogador, não apagada, sem denúncia, membro do grupo. A trava "a história virou livro" continua
-- (só existe em roleplay e vale para não reescrever livro publicado).
create or replace function public.chat_edit_message(p_message bigint, p_body text)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
begin
  if p_body is null or char_length(btrim(p_body)) = 0 or char_length(p_body) > 2000 then
    return false;
  end if;
  update public.chat_messages x
     set body = p_body, edited_at = now()
   where x.id = p_message
     and x.author_id = auth.uid()
     and x.deleted_at is null
     and x.kind = 'user'
     and x.media_kind is null
     and x.created_at > now() - interval '7 days'
     and public.chat_is_member(x.group_id)
     and exists (select 1 from public.chat_groups g where g.id = x.group_id and g.deleted_at is null)
     and not exists (select 1 from public.chat_books b where b.group_id = x.group_id)
     and not exists (select 1 from public.chat_reports r where r.message_id = x.id);
  return found;
end;
$$;

revoke execute on function public.chat_edit_message(bigint, text) from public, anon;
grant execute on function public.chat_edit_message(bigint, text) to authenticated;
