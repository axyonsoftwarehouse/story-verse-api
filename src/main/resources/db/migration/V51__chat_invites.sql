-- Convite para grupo: quem administra convida uma pessoa (achada pelo nome) e ela aceita ou
-- recusa. Antes (V45) a pessoa entrava direto, sem escolher.

create table if not exists public.chat_invites (
  group_id uuid not null references public.chat_groups (id) on delete cascade,
  user_id uuid not null references auth.users (id) on delete cascade,
  invited_by uuid not null references auth.users (id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (group_id, user_id),
  check (user_id <> invited_by)
);

create index if not exists chat_invites_user_idx on public.chat_invites (user_id, created_at desc);

alter table public.chat_invites enable row level security;
revoke all on public.chat_invites from public, anon;
grant select, insert, delete on public.chat_invites to authenticated;

-- A conta da sessão tem convite (ainda não respondido) para o grupo.
create or replace function public.chat_is_invited(p_group uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists (select 1 from public.chat_invites i where i.group_id = p_group and i.user_id = auth.uid());
$$;

revoke execute on function public.chat_is_invited(uuid) from public, anon;
grant execute on function public.chat_is_invited(uuid) to authenticated;

-- O convite existe para quem foi convidado e para quem administra o grupo.
drop policy if exists "convites da pessoa e da gestao" on public.chat_invites;
create policy "convites da pessoa e da gestao" on public.chat_invites
  for select using (user_id = auth.uid() or public.chat_is_manager(group_id) or public.is_staff());
-- Só quem administra convida, em nome próprio, e só quem ainda não está no grupo.
drop policy if exists "gestao convida" on public.chat_invites;
create policy "gestao convida" on public.chat_invites
  for insert with check (
    invited_by = auth.uid()
    and public.chat_is_manager(group_id)
    and not exists (select 1 from public.chat_members m
                     where m.group_id = chat_invites.group_id and m.user_id = chat_invites.user_id and m.banned_at is null)
  );
-- Recusar (a pessoa) ou cancelar (quem administra).
drop policy if exists "recusar ou cancelar convite" on public.chat_invites;
create policy "recusar ou cancelar convite" on public.chat_invites
  for delete using (user_id = auth.uid() or public.chat_is_manager(group_id));

-- Quem foi convidado vê o grupo (nome, foto) mesmo privado, para decidir se entra.
drop policy if exists "grupos visiveis" on public.chat_groups;
create policy "grupos visiveis" on public.chat_groups
  for select using (
    (deleted_at is null and (visibility = 'public' or public.chat_is_member(id) or public.chat_is_invited(id)))
    or public.is_staff()
  );

-- Aceitar: troca o convite pela entrada no grupo. Quem tinha sido removido e foi convidado de
-- novo por quem administra volta. Devolve quem convidou (nulo se não havia convite).
create or replace function public.chat_accept_invite(p_group uuid)
returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
  inviter uuid;
begin
  delete from public.chat_invites where group_id = p_group and user_id = auth.uid()
  returning invited_by into inviter;
  if inviter is null then
    return null;
  end if;
  if not exists (select 1 from public.chat_groups g where g.id = p_group and g.deleted_at is null) then
    return null;
  end if;
  insert into public.chat_members (group_id, user_id, role, last_read_id)
  values (p_group, auth.uid(), 'member', coalesce((select max(id) from public.chat_messages where group_id = p_group), 0))
  on conflict (group_id, user_id) do update set banned_at = null;
  return inviter;
end;
$$;

revoke execute on function public.chat_accept_invite(uuid) from public, anon;
grant execute on function public.chat_accept_invite(uuid) to authenticated;

-- Aviso no sininho: "fulano convidou você para o grupo".
alter table public.user_notifications drop constraint if exists user_notifications_kind_check;
alter table public.user_notifications add constraint user_notifications_kind_check
  check (kind in ('reply', 'reaction', 'member_joined', 'member_added', 'follow', 'follow_request', 'follow_accepted',
                  'group_invite'));

-- Só sai se o convite existe e foi a conta da sessão que convidou; sem repetir em 24 h.
create or replace function public.chat_notify_invite(p_user uuid, p_group uuid)
returns bigint
language sql
security definer
set search_path = public
as $$
  insert into public.user_notifications (user_id, kind, group_id, actor_id)
  select p_user, 'group_invite', p_group, auth.uid()
   where exists (select 1 from public.chat_invites i
                  where i.group_id = p_group and i.user_id = p_user and i.invited_by = auth.uid())
     and not exists (select 1 from public.user_notifications n
                      where n.user_id = p_user and n.group_id = p_group and n.kind = 'group_invite'
                        and n.created_at > now() - interval '24 hours')
  returning id;
$$;

revoke execute on function public.chat_notify_invite(uuid, uuid) from public, anon;
grant execute on function public.chat_notify_invite(uuid, uuid) to authenticated;

-- O aviso do convite sai do sininho quando o convite é aceito, recusado ou cancelado.
create or replace function public.chat_drop_invite_notification()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  delete from public.user_notifications
   where user_id = old.user_id and group_id = old.group_id and kind = 'group_invite';
  return old;
end;
$$;

drop trigger if exists chat_invites_drop_notification on public.chat_invites;
create trigger chat_invites_drop_notification
  after delete on public.chat_invites
  for each row execute function public.chat_drop_invite_notification();
