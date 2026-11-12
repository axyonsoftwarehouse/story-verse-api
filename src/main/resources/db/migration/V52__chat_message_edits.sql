-- Roleplay: quem escreveu pode corrigir a própria mensagem de texto. A mensagem passa a mostrar
-- "editada" (com a hora da última edição).
alter table public.chat_messages add column if not exists edited_at timestamptz;

-- A mensagem da conta da sessão foi denunciada? (as denúncias só são visíveis para quem
-- denunciou e para a equipe; o autor precisa saber só se existe alguma.)
create or replace function public.chat_message_reported(p_message bigint)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists (
    select 1 from public.chat_reports r
      join public.chat_messages x on x.id = r.message_id
     where r.message_id = p_message and x.author_id = auth.uid()
  );
$$;

-- Editar: só o autor, mensagem de texto de jogador, não apagada, até 24 h depois de enviada, sem
-- denúncia (a moderação vê o texto que foi denunciado), em grupo de roleplay que ainda não virou
-- livro. A API confere tudo antes para dar a mensagem certa; aqui é a garantia. true se mudou.
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
     and x.created_at > now() - interval '24 hours'
     and public.chat_is_member(x.group_id)
     and exists (select 1 from public.chat_groups g where g.id = x.group_id and g.deleted_at is null and g.mode = 'roleplay')
     and not exists (select 1 from public.chat_books b where b.group_id = x.group_id)
     and not exists (select 1 from public.chat_reports r where r.message_id = x.id);
  return found;
end;
$$;

revoke execute on function public.chat_message_reported(bigint) from public, anon;
revoke execute on function public.chat_edit_message(bigint, text) from public, anon;
grant execute on function public.chat_message_reported(bigint) to authenticated;
grant execute on function public.chat_edit_message(bigint, text) to authenticated;
