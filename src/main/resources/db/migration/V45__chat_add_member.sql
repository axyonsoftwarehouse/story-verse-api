-- Quem administra o grupo (dono ou moderador) adiciona uma pessoa pelo e-mail da conta dela.
-- A RLS de chat_members só deixa cada um inserir a própria linha: esta função confere o papel de
-- quem pede e insere pela outra pessoa. Quem tinha sido removido volta (decisão de quem administra).
create or replace function public.chat_add_member(p_group uuid, p_user uuid)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
begin
  if not public.chat_is_manager(p_group) then
    return false;
  end if;
  insert into public.chat_members (group_id, user_id, role, last_read_id)
  values (p_group, p_user, 'member', coalesce((select max(id) from public.chat_messages where group_id = p_group), 0))
  on conflict (group_id, user_id) do update set banned_at = null;
  return true;
end;
$$;

revoke execute on function public.chat_add_member(uuid, uuid) from public, anon;
grant execute on function public.chat_add_member(uuid, uuid) to authenticated;

-- Aviso no sininho: "fulano adicionou você ao grupo".
alter table public.user_notifications drop constraint if exists user_notifications_kind_check;
alter table public.user_notifications add constraint user_notifications_kind_check
  check (kind in ('reply', 'reaction', 'member_joined', 'member_added'));
