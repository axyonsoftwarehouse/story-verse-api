-- Notificação no celular (Web Push) sem nenhuma chave para configurar fora do repositório: a
-- API gera o par VAPID na primeira vez que precisa e guarda aqui. Os serviços de push
-- (Google, Mozilla, Apple, Microsoft) aceitam qualquer par válido, sem cadastro.
create table if not exists public.push_vapid_keys (
  id smallint primary key default 1 check (id = 1),
  public_key text not null check (char_length(public_key) <= 200),
  private_key text not null check (char_length(private_key) <= 400),
  created_at timestamptz not null default now()
);

-- A chave privada assina os envios: só a API (papel de runtime) lê, nenhuma conta.
alter table public.push_vapid_keys enable row level security;
revoke all on public.push_vapid_keys from public, anon, authenticated;
grant select, insert on public.push_vapid_keys to storyverse_api;
drop policy if exists push_vapid_keys_runtime on public.push_vapid_keys;
create policy push_vapid_keys_runtime on public.push_vapid_keys
  for all to storyverse_api using (true) with check (true);

-- Cada aparelho/navegador inscrito: o endereço do serviço de push e as chaves do navegador.
create table if not exists public.push_subscriptions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  endpoint text not null unique check (char_length(endpoint) <= 1000),
  p256dh text not null check (char_length(p256dh) <= 200),
  auth text not null check (char_length(auth) <= 100),
  created_at timestamptz not null default now(),
  last_used_at timestamptz not null default now()
);
create index if not exists push_subscriptions_user_idx on public.push_subscriptions (user_id);

alter table public.push_subscriptions enable row level security;
revoke all on public.push_subscriptions from public, anon;
grant select, insert, update, delete on public.push_subscriptions to authenticated;

drop policy if exists "cada um ve seus aparelhos" on public.push_subscriptions;
create policy "cada um ve seus aparelhos" on public.push_subscriptions
  for select using (user_id = auth.uid());
drop policy if exists "cada um inscreve seus aparelhos" on public.push_subscriptions;
create policy "cada um inscreve seus aparelhos" on public.push_subscriptions
  for insert with check (user_id = auth.uid());
drop policy if exists "cada um atualiza seus aparelhos" on public.push_subscriptions;
create policy "cada um atualiza seus aparelhos" on public.push_subscriptions
  for update using (user_id = auth.uid()) with check (user_id = auth.uid());
drop policy if exists "cada um remove seus aparelhos" on public.push_subscriptions;
create policy "cada um remove seus aparelhos" on public.push_subscriptions
  for delete using (user_id = auth.uid());

-- Aparelhos que recebem o aviso de uma mensagem nova: membros ativos do grupo, menos quem
-- escreveu e quem silenciou. Só responde para quem é membro do grupo (quem acabou de escrever).
create or replace function public.chat_push_targets(p_group uuid)
returns table (user_id uuid, endpoint text, p256dh text, auth text)
language sql
stable
security definer
set search_path = public
as $$
  select s.user_id, s.endpoint, s.p256dh, s.auth
    from public.chat_members m
    join public.push_subscriptions s on s.user_id = m.user_id
   where m.group_id = p_group
     and m.banned_at is null
     and not m.muted
     and m.user_id <> auth.uid()
     and public.chat_is_member(p_group);
$$;

-- Serviço de push respondeu que a inscrição não existe mais (404/410): a API apaga.
create or replace function public.push_forget(p_endpoint text)
returns void
language sql
security definer
set search_path = public
as $$
  delete from public.push_subscriptions where endpoint = p_endpoint;
$$;

revoke execute on function public.chat_push_targets(uuid) from public, anon;
revoke execute on function public.push_forget(text) from public, anon, authenticated;
grant execute on function public.chat_push_targets(uuid) to authenticated;
grant execute on function public.push_forget(text) to storyverse_api;
