-- Ocultamento automático das mensagens de grupo com 3 denúncias pendentes de contas diferentes.
-- A API contava as denúncias direto em chat_reports, mas a RLS dessa tabela só mostra a cada um as
-- próprias denúncias (e tudo para a equipe): para um membro comum a conta dava no máximo 1 e a
-- mensagem nunca sumia. Esta função conta todas, mas só responde para quem é membro ativo do grupo
-- da mensagem ou para a equipe; para os outros devolve 0. Não expõe quem denunciou nem por quê.
create or replace function public.chat_message_report_count(p_message bigint)
returns integer
language sql
stable
security definer
set search_path = public
as $$
  select coalesce((
    select count(distinct r.reporter_id)::integer
      from public.chat_messages x
      join public.chat_reports r on r.message_id = x.id and r.status = 'pending'
     where x.id = p_message
       and (public.chat_is_member(x.group_id) or public.is_staff())
  ), 0);
$$;

revoke execute on function public.chat_message_report_count(bigint) from public, anon;
grant execute on function public.chat_message_report_count(bigint) to authenticated;
