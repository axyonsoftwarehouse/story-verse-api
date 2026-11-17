-- Roleplay: o prazo para corrigir a própria mensagem passa de 24 horas para 7 dias. O resto da
-- regra não muda (ver V52): só o autor, mensagem de texto de jogador, não apagada, sem denúncia,
-- em grupo de roleplay que ainda não virou livro. A API confere o mesmo prazo antes.
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
     and exists (select 1 from public.chat_groups g where g.id = x.group_id and g.deleted_at is null and g.mode = 'roleplay')
     and not exists (select 1 from public.chat_books b where b.group_id = x.group_id)
     and not exists (select 1 from public.chat_reports r where r.message_id = x.id);
  return found;
end;
$$;

revoke execute on function public.chat_edit_message(bigint, text) from public, anon;
grant execute on function public.chat_edit_message(bigint, text) to authenticated;
