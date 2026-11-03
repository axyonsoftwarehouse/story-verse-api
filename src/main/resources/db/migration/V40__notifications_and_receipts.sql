-- Sininho do app e selos de enviado/recebido/visto nas mensagens dos grupos.

-- Até onde o aparelho da pessoa já recebeu as mensagens do grupo (o "lido" já existia).
alter table public.chat_members add column if not exists last_delivered_id bigint not null default 0;
update public.chat_members set last_delivered_id = last_read_id where last_delivered_id < last_read_id;

-- Avisos para uma pessoa: responderam a mensagem dela, reagiram a ela, entrou alguém no grupo
-- dela. Ficam 30 dias (a API apaga os mais antigos).
create table if not exists public.user_notifications (
  id bigint generated always as identity primary key,
  user_id uuid not null references auth.users (id) on delete cascade,
  kind text not null check (kind in ('reply', 'reaction', 'member_joined')),
  group_id uuid not null references public.chat_groups (id) on delete cascade,
  message_id bigint references public.chat_messages (id) on delete cascade,
  actor_id uuid not null references auth.users (id) on delete cascade,
  emoji text check (emoji is null or char_length(emoji) <= 16),
  created_at timestamptz not null default now(),
  read_at timestamptz
);
create index if not exists user_notifications_user_idx on public.user_notifications (user_id, id desc);
create index if not exists user_notifications_created_idx on public.user_notifications (created_at);

alter table public.user_notifications enable row level security;
revoke all on public.user_notifications from public, anon;
grant select, update on public.user_notifications to authenticated;

drop policy if exists "cada um ve seus avisos" on public.user_notifications;
create policy "cada um ve seus avisos" on public.user_notifications
  for select using (user_id = auth.uid());
drop policy if exists "cada um marca seus avisos" on public.user_notifications;
create policy "cada um marca seus avisos" on public.user_notifications
  for update using (user_id = auth.uid()) with check (user_id = auth.uid());

-- Criar aviso para outra pessoa: quem age é a conta da sessão, e as duas precisam estar no grupo.
create or replace function public.chat_notify(p_user uuid, p_kind text, p_group uuid, p_message bigint, p_emoji text)
returns bigint
language sql
security definer
set search_path = public
as $$
  insert into public.user_notifications (user_id, kind, group_id, message_id, actor_id, emoji)
  select p_user, p_kind, p_group, p_message, auth.uid(), p_emoji
   where p_user <> auth.uid()
     and public.chat_is_member(p_group)
     and exists (select 1 from public.chat_members m
                  where m.group_id = p_group and m.user_id = p_user and m.banned_at is null)
  returning id;
$$;

-- Limpeza: avisos com mais de 30 dias. Só a API (papel de runtime) chama.
create or replace function public.chat_prune_notifications()
returns integer
language sql
security definer
set search_path = public
as $$
  with gone as (delete from public.user_notifications where created_at < now() - interval '30 days' returning 1)
  select count(*)::integer from gone;
$$;

revoke execute on function public.chat_notify(uuid, text, uuid, bigint, text) from public, anon;
revoke execute on function public.chat_prune_notifications() from public, anon, authenticated;
grant execute on function public.chat_notify(uuid, text, uuid, bigint, text) to authenticated;
grant execute on function public.chat_prune_notifications() to storyverse_api;
