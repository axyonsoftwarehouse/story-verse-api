-- Rede social: seguir perfis. Seguir é de mão única; quando as duas pessoas se seguem, viram
-- "conexão". O grafo é visível para quem tem conta (como a lista de membros de um grupo público),
-- mas só a própria pessoa cria ou desfaz o que ela segue, e só ela tira alguém dos seguidores.

create table if not exists public.user_follows (
  follower_id uuid not null references auth.users (id) on delete cascade,
  followee_id uuid not null references auth.users (id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (follower_id, followee_id),
  check (follower_id <> followee_id)
);

create index if not exists user_follows_followee_idx on public.user_follows (followee_id, created_at desc);
create index if not exists user_follows_follower_idx on public.user_follows (follower_id, created_at desc);

alter table public.user_follows enable row level security;
revoke all on public.user_follows from public, anon;
grant select, insert, delete on public.user_follows to authenticated;

drop policy if exists "contas veem quem segue quem" on public.user_follows;
create policy "contas veem quem segue quem" on public.user_follows
  for select using (auth.uid() is not null);

drop policy if exists "cada um segue por si" on public.user_follows;
create policy "cada um segue por si" on public.user_follows
  for insert with check (follower_id = auth.uid());

-- Deixar de seguir (quem segue) ou tirar dos seguidores (quem é seguido).
drop policy if exists "desfazer o proprio vinculo" on public.user_follows;
create policy "desfazer o proprio vinculo" on public.user_follows
  for delete using (follower_id = auth.uid() or followee_id = auth.uid());

-- Aviso "fulano começou a seguir você": não é de grupo, então o grupo vira opcional.
alter table public.user_notifications alter column group_id drop not null;
alter table public.user_notifications drop constraint if exists user_notifications_kind_check;
alter table public.user_notifications add constraint user_notifications_kind_check
  check (kind in ('reply', 'reaction', 'member_joined', 'member_added', 'follow'));
alter table public.user_notifications drop constraint if exists user_notifications_group_kind_check;
alter table public.user_notifications add constraint user_notifications_group_kind_check
  check (kind = 'follow' or group_id is not null);

-- Cria o aviso para quem foi seguido. Só vale se a conta da sessão segue mesmo a pessoa, e não
-- repete em 24 h (seguir/desseguir em sequência não vira enxurrada no sininho).
create or replace function public.social_notify_follow(p_user uuid)
returns bigint
language sql
security definer
set search_path = public
as $$
  insert into public.user_notifications (user_id, kind, actor_id)
  select p_user, 'follow', auth.uid()
   where p_user <> auth.uid()
     and exists (select 1 from public.user_follows f where f.follower_id = auth.uid() and f.followee_id = p_user)
     and not exists (select 1 from public.user_notifications n
                      where n.user_id = p_user and n.actor_id = auth.uid() and n.kind = 'follow'
                        and n.created_at > now() - interval '24 hours')
  returning id;
$$;

revoke execute on function public.social_notify_follow(uuid) from public, anon;
grant execute on function public.social_notify_follow(uuid) to authenticated;
