-- Convite de grupo privado: quem ainda não é membro não enxerga o grupo pela RLS de
-- chat_groups, então a busca pelo código (feita com a conta de quem abre o link) não achava
-- nada e o app dizia "convite inválido ou expirado". O código é o segredo: esta função só
-- devolve o grupo do código exato, nada além do id e da visibilidade.
create or replace function public.chat_group_by_invite(p_code text)
returns table (id uuid, visibility text)
language sql
stable
security definer
set search_path = public
as $$
  select g.id, g.visibility
    from public.chat_groups g
   where g.invite_code = p_code and g.deleted_at is null;
$$;

-- Total de membros ativos, para o limite de membros: pela RLS, quem está de fora só vê a
-- própria linha em chat_members e a conta dava zero.
create or replace function public.chat_member_count(p_group uuid)
returns integer
language sql
stable
security definer
set search_path = public
as $$
  select count(*)::integer
    from public.chat_members m
   where m.group_id = p_group and m.banned_at is null;
$$;

revoke execute on function public.chat_group_by_invite(text) from public, anon;
revoke execute on function public.chat_member_count(uuid) from public, anon;
grant execute on function public.chat_group_by_invite(text) to authenticated;
grant execute on function public.chat_member_count(uuid) to authenticated;
