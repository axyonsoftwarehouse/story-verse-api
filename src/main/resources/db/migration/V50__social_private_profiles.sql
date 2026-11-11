-- Perfil privado com pedidos para seguir.
--  * Perfil privado: nome, rosto, biografia e contadores continuam visíveis; leituras,
--    conquistas, presentes e as listas de seguidores/seguindo ficam só para quem segue.
--  * Seguir um perfil privado vira pedido; a dona aceita ou recusa. Quem já seguia continua.
--  * Voltar a público aceita os pedidos que estavam esperando.

alter table public.profile_showcase add column if not exists is_private boolean not null default false;

-- O perfil é privado? (só a escolha da pessoa; quem não tem vitrine é público)
create or replace function public.social_is_private(p_user uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select coalesce((select s.is_private from public.profile_showcase s where s.user_id = p_user), false);
$$;

-- A conta logada vê o conteúdo de p_user? (ela mesma; os outros, se o perfil é público ou se seguem)
create or replace function public.social_content_visible(p_user uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select auth.uid() is not null
     and (p_user = auth.uid()
          or not public.social_is_private(p_user)
          or exists (select 1 from public.user_follows f where f.follower_id = auth.uid() and f.followee_id = p_user));
$$;

-- Leituras do perfil: além de a pessoa mostrar, o perfil privado só abre para quem segue.
create or replace function public.social_reading_visible(p_user uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select auth.uid() is not null
     and (p_user = auth.uid()
          or (coalesce((select s.show_reading from public.profile_showcase s where s.user_id = p_user), true)
              and public.social_content_visible(p_user)));
$$;

revoke execute on function public.social_is_private(uuid) from public, anon;
revoke execute on function public.social_content_visible(uuid) from public, anon;
grant execute on function public.social_is_private(uuid) to authenticated;
grant execute on function public.social_content_visible(uuid) to authenticated;

-- Seguir direto só perfil público; o privado passa pelo pedido (aceite em social_accept_follow_request).
drop policy if exists "cada um segue por si" on public.user_follows;
create policy "cada um segue por si" on public.user_follows
  for insert with check (follower_id = auth.uid() and not public.social_is_private(followee_id));

create table if not exists public.follow_requests (
  requester_id uuid not null references auth.users (id) on delete cascade,
  target_id uuid not null references auth.users (id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (requester_id, target_id),
  check (requester_id <> target_id)
);

create index if not exists follow_requests_target_idx on public.follow_requests (target_id, created_at desc);

alter table public.follow_requests enable row level security;
revoke all on public.follow_requests from public, anon;
grant select, insert, delete on public.follow_requests to authenticated;

-- Cada pedido só existe para as duas pontas.
drop policy if exists "pedidos das duas pontas" on public.follow_requests;
create policy "pedidos das duas pontas" on public.follow_requests
  for select using (requester_id = auth.uid() or target_id = auth.uid());
drop policy if exists "cada um pede por si" on public.follow_requests;
create policy "cada um pede por si" on public.follow_requests
  for insert with check (requester_id = auth.uid() and public.social_is_private(target_id));
-- Cancelar (quem pediu) ou recusar (quem recebeu).
drop policy if exists "desfazer o proprio pedido" on public.follow_requests;
create policy "desfazer o proprio pedido" on public.follow_requests
  for delete using (requester_id = auth.uid() or target_id = auth.uid());

-- Aceitar: a dona do perfil cria o vínculo em nome de quem pediu. true se havia o pedido.
create or replace function public.social_accept_follow_request(p_requester uuid)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
begin
  delete from public.follow_requests where requester_id = p_requester and target_id = auth.uid();
  if not found then
    return false;
  end if;
  insert into public.user_follows (follower_id, followee_id) values (p_requester, auth.uid())
  on conflict do nothing;
  return true;
end;
$$;

-- Voltar a público: todos os pedidos esperando viram seguidores. Devolve quantos.
create or replace function public.social_accept_all_follow_requests()
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
  accepted integer;
begin
  with moved as (
    delete from public.follow_requests where target_id = auth.uid() returning requester_id
  )
  insert into public.user_follows (follower_id, followee_id)
  select requester_id, auth.uid() from moved
  on conflict do nothing;
  get diagnostics accepted = row_count;
  return accepted;
end;
$$;

revoke execute on function public.social_accept_follow_request(uuid) from public, anon;
revoke execute on function public.social_accept_all_follow_requests() from public, anon;
grant execute on function public.social_accept_follow_request(uuid) to authenticated;
grant execute on function public.social_accept_all_follow_requests() to authenticated;

-- Avisos novos no sininho: pedido para seguir e pedido aceito.
alter table public.user_notifications drop constraint if exists user_notifications_kind_check;
alter table public.user_notifications add constraint user_notifications_kind_check
  check (kind in ('reply', 'reaction', 'member_joined', 'member_added', 'follow', 'follow_request', 'follow_accepted'));
alter table public.user_notifications drop constraint if exists user_notifications_group_kind_check;
alter table public.user_notifications add constraint user_notifications_group_kind_check
  check (kind in ('follow', 'follow_request', 'follow_accepted') or group_id is not null);

-- "fulano pediu para seguir você": só se o pedido existe, sem repetir em 24 h.
create or replace function public.social_notify_follow_request(p_user uuid)
returns bigint
language sql
security definer
set search_path = public
as $$
  insert into public.user_notifications (user_id, kind, actor_id)
  select p_user, 'follow_request', auth.uid()
   where p_user <> auth.uid()
     and exists (select 1 from public.follow_requests r where r.requester_id = auth.uid() and r.target_id = p_user)
     and not exists (select 1 from public.user_notifications n
                      where n.user_id = p_user and n.actor_id = auth.uid() and n.kind = 'follow_request'
                        and n.created_at > now() - interval '24 hours')
  returning id;
$$;

-- "fulana aceitou seu pedido": só se quem pediu segue mesmo a conta agora.
create or replace function public.social_notify_follow_accepted(p_user uuid)
returns bigint
language sql
security definer
set search_path = public
as $$
  insert into public.user_notifications (user_id, kind, actor_id)
  select p_user, 'follow_accepted', auth.uid()
   where p_user <> auth.uid()
     and exists (select 1 from public.user_follows f where f.follower_id = p_user and f.followee_id = auth.uid())
  returning id;
$$;

revoke execute on function public.social_notify_follow_request(uuid) from public, anon;
revoke execute on function public.social_notify_follow_accepted(uuid) from public, anon;
grant execute on function public.social_notify_follow_request(uuid) to authenticated;
grant execute on function public.social_notify_follow_accepted(uuid) to authenticated;

-- O aviso do pedido sai do sininho quando o pedido é aceito, recusado ou cancelado.
create or replace function public.social_drop_request_notification()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  delete from public.user_notifications
   where user_id = old.target_id and actor_id = old.requester_id and kind = 'follow_request';
  return old;
end;
$$;

drop trigger if exists follow_requests_drop_notification on public.follow_requests;
create trigger follow_requests_drop_notification
  after delete on public.follow_requests
  for each row execute function public.social_drop_request_notification();
