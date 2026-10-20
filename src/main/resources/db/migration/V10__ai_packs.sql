-- Pacotes de mensagens extras: compra com diamantes e consumo no servidor.
-- Depende da V9 (presentes) e de apply_diamonds/ranking_week_start do schema base.

create table if not exists public.ai_pack_catalog (
  id text primary key,
  name text not null check (char_length(name) between 1 and 60),
  messages integer not null check (messages > 0),
  price integer not null check (price > 0),
  active boolean not null default true,
  sort_order integer not null default 0
);

insert into public.ai_pack_catalog (id, name, messages, price, sort_order)
values
  ('msg20', 'Pacote de 20', 20, 30, 10),
  ('msg50', 'Pacote de 50', 50, 60, 20),
  ('msg100', 'Pacote de 100', 100, 100, 30)
on conflict (id) do nothing;

create table if not exists public.ai_credits (
  user_id uuid primary key references auth.users (id) on delete cascade,
  balance integer not null default 0 check (balance >= 0),
  updated_at timestamptz not null default now()
);

create table if not exists public.ai_credit_ledger (
  id bigint generated always as identity primary key,
  user_id uuid not null references auth.users (id) on delete cascade,
  amount integer not null check (amount <> 0),
  balance_after integer not null check (balance_after >= 0),
  reason text not null check (reason in ('pack_purchase', 'message_use', 'adjustment')),
  ref text not null,
  created_at timestamptz not null default now(),
  unique (user_id, reason, ref)
);

create index if not exists ai_credit_ledger_user_recent
  on public.ai_credit_ledger (user_id, created_at desc);

alter table public.ai_pack_catalog enable row level security;
alter table public.ai_credits enable row level security;
alter table public.ai_credit_ledger enable row level security;
-- Ninguem mexe nas tabelas direto: so as funcoes abaixo.
revoke all on public.ai_pack_catalog, public.ai_credits, public.ai_credit_ledger
  from public, anon, authenticated;
grant usage, select on sequence public.ai_credit_ledger_id_seq to storyverse_api;

-- Motivo novo no extrato de diamantes, preservando os motivos da V9.
alter table public.diamond_ledger drop constraint if exists diamond_ledger_reason_check;
alter table public.diamond_ledger add constraint diamond_ledger_reason_check
  check (reason in (
    'ranking', 'reading_goal', 'streak_freeze', 'daily_spin',
    'purchase', 'adjustment', 'gift_sent', 'ai_message_pack'
  ));

-- Metricas: separa chamadas gratuitas de chamadas pagas por credito.
alter table public.ai_usage_events
  add column if not exists source text
  check (source is null or source in ('free', 'credit'));
alter table public.ai_usage_events
  add column if not exists credits_after integer not null default 0
  check (credits_after >= 0);

create or replace function public.ai_pack_catalog()
returns table (id text, name text, messages integer, price integer)
language sql
stable
security definer
set search_path = public
as $$
  select c.id, c.name, c.messages, c.price
  from public.ai_pack_catalog c
  where c.active
  order by c.sort_order, c.price, c.id;
$$;

create or replace function public.buy_ai_pack(p_pack text, p_ref text)
returns table (diamond_balance integer, credits_balance integer, messages integer, duplicate boolean)
language plpgsql
security definer
set search_path = public
as $$
declare
  uid uuid := auth.uid();
  pack record;
  existing public.ai_credit_ledger%rowtype;
  diamond_bal integer;
  credit_bal integer;
begin
  if uid is null then
    raise exception 'Entre na sua conta para comprar mensagens extras.';
  end if;
  if p_ref is null or char_length(p_ref) < 8 or char_length(p_ref) > 80 then
    raise exception 'Pedido de compra invalido.';
  end if;

  select * into existing
  from public.ai_credit_ledger l
  where l.user_id = uid and l.reason = 'pack_purchase' and l.ref = p_ref;
  if found then
    select coalesce((select w.balance from public.diamond_wallets w where w.user_id = uid), 0)
      into diamond_bal;
    select coalesce((select c.balance from public.ai_credits c where c.user_id = uid), 0)
      into credit_bal;
    return query select diamond_bal, credit_bal, existing.amount, true;
    return;
  end if;

  select * into pack from public.ai_pack_catalog c where c.id = p_pack and c.active;
  if not found then
    raise exception 'Pacote indisponivel.';
  end if;

  diamond_bal := public.apply_diamonds(uid, -pack.price, 'ai_message_pack', p_ref);
  if diamond_bal is null then
    raise exception 'Pedido de compra repetido.';
  end if;

  insert into public.ai_credits (user_id, balance)
  values (uid, pack.messages)
  on conflict (user_id) do update
     set balance = ai_credits.balance + excluded.balance,
         updated_at = now()
  returning balance into credit_bal;

  insert into public.ai_credit_ledger (user_id, amount, balance_after, reason, ref)
  values (uid, pack.messages, credit_bal, 'pack_purchase', p_ref);

  return query select diamond_bal, credit_bal, pack.messages, false;
end;
$$;

create or replace function public.consume_ai_credit(p_ref text)
returns table (consumed boolean, credits_balance integer, duplicate boolean)
language plpgsql
security definer
set search_path = public
as $$
declare
  uid uuid := auth.uid();
  existing public.ai_credit_ledger%rowtype;
  credit_bal integer;
begin
  if uid is null then
    raise exception 'Sessao invalida.';
  end if;
  if p_ref is null or char_length(p_ref) < 8 or char_length(p_ref) > 80 then
    raise exception 'Pedido invalido.';
  end if;

  select * into existing
  from public.ai_credit_ledger l
  where l.user_id = uid and l.reason = 'message_use' and l.ref = p_ref;
  if found then
    select coalesce((select c.balance from public.ai_credits c where c.user_id = uid), 0)
      into credit_bal;
    return query select true, credit_bal, true;
    return;
  end if;

  insert into public.ai_credits (user_id) values (uid) on conflict do nothing;
  select c.balance into credit_bal
  from public.ai_credits c
  where c.user_id = uid
  for update;

  if credit_bal <= 0 then
    return query select false, credit_bal, false;
    return;
  end if;

  credit_bal := credit_bal - 1;
  update public.ai_credits
     set balance = credit_bal, updated_at = now()
   where user_id = uid;

  insert into public.ai_credit_ledger (user_id, amount, balance_after, reason, ref)
  values (uid, -1, credit_bal, 'message_use', p_ref);

  return query select true, credit_bal, false;
end;
$$;

create or replace function public.my_ai_quota(p_daily_limit integer default 50)
returns table (
  daily_limit integer,
  used integer,
  free_remaining integer,
  credits integer,
  day date,
  resets_at timestamptz
)
language sql
stable
security definer
set search_path = public
as $$
  with limits as (
    select greatest(1, p_daily_limit) as daily_limit
  ),
  usage as (
    select coalesce((
      select u.used from public.api_daily_usage u
       where u.user_id = auth.uid()
         and u.day = (now() at time zone 'America/Sao_Paulo')::date
         and u.feature = 'ai'
    ), 0) as used
  )
  select
    l.daily_limit,
    u.used,
    greatest(0, l.daily_limit - u.used),
    coalesce((select c.balance from public.ai_credits c where c.user_id = auth.uid()), 0),
    (now() at time zone 'America/Sao_Paulo')::date,
    (((now() at time zone 'America/Sao_Paulo')::date + 1)::timestamp
      at time zone 'America/Sao_Paulo')
  from limits l, usage u;
$$;

revoke execute on function public.ai_pack_catalog() from public, anon;
revoke execute on function public.buy_ai_pack(text, text) from public, anon;
revoke execute on function public.consume_ai_credit(text) from public, anon, authenticated;
revoke execute on function public.my_ai_quota(integer) from public, anon;
grant execute on function public.ai_pack_catalog() to authenticated;
grant execute on function public.buy_ai_pack(text, text) to authenticated;
grant execute on function public.consume_ai_credit(text) to storyverse_api;
grant execute on function public.my_ai_quota(integer) to authenticated;
