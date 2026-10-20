-- Presentes e cosmeticos: o leitor gasta diamantes para enviar um item a outro leitor.
-- O presente nao transfere diamantes; o item e apenas cosmetico e aparece no perfil/ranking.
-- Depende de diamonds.sql (carteira/extrato) e weekly-ranking.sql (semana do ranking).

create table if not exists public.gift_catalog (
  id text primary key,
  name text not null check (char_length(name) between 1 and 40),
  icon text not null check (char_length(icon) between 1 and 16),
  price integer not null check (price > 0),
  rarity text not null default 'common' check (rarity in ('common', 'rare', 'epic')),
  active boolean not null default true
);

insert into public.gift_catalog (id, name, icon, price, rarity)
values
  ('flor', 'Flor', '🌷', 5, 'common'),
  ('coracao', 'Coração', '💗', 10, 'common'),
  ('coroa', 'Coroa', '👑', 25, 'rare'),
  ('trofeu', 'Troféu', '🏆', 50, 'epic')
on conflict (id) do nothing;

create table if not exists public.user_gifts (
  id bigint generated always as identity primary key,
  from_user uuid not null references auth.users (id) on delete cascade,
  to_user uuid not null references auth.users (id) on delete cascade,
  gift_id text not null references public.gift_catalog (id),
  week_start date not null,
  ref text not null,
  created_at timestamptz not null default now(),
  check (from_user <> to_user),
  unique (from_user, ref)
);

create index if not exists user_gifts_receiver_week_idx
  on public.user_gifts (to_user, week_start, created_at desc);
create index if not exists user_gifts_sender_week_idx
  on public.user_gifts (from_user, week_start, created_at desc);
create index if not exists user_gifts_week_idx
  on public.user_gifts (week_start, to_user);

alter table public.gift_catalog enable row level security;
alter table public.user_gifts enable row level security;
-- Ninguem le ou grava as tabelas direto: so as funcoes abaixo mexem nelas.
revoke all on public.gift_catalog, public.user_gifts from public, anon, authenticated;
grant usage, select on sequence public.user_gifts_id_seq to storyverse_api;

-- Motivo novo no extrato: presente enviado.
alter table public.diamond_ledger drop constraint if exists diamond_ledger_reason_check;
alter table public.diamond_ledger add constraint diamond_ledger_reason_check
  check (reason in ('ranking', 'reading_goal', 'streak_freeze', 'daily_spin', 'purchase', 'adjustment', 'gift_sent'));

create or replace function public.gift_rules()
returns table (daily_limit integer, min_account_age_days integer)
language sql
immutable
as $$
  select 5, 3;
$$;

create or replace function public.gift_catalog()
returns table (id text, name text, icon text, price integer, rarity text)
language sql
stable
security definer
set search_path = public
as $$
  select c.id, c.name, c.icon, c.price, c.rarity
  from public.gift_catalog c
  where c.active
  order by c.price, c.id;
$$;

create or replace function public.send_gift(p_to uuid, p_gift text, p_ref text)
returns table (balance integer, daily_limit integer, remaining_today integer, week_count integer, duplicate boolean)
language plpgsql
security definer
set search_path = public
as $$
declare
  uid uuid := auth.uid();
  rules record;
  gift record;
  existing public.user_gifts%rowtype;
  today date := (now() at time zone 'America/Sao_Paulo')::date;
  week date := public.ranking_week_start(0);
  used_today integer;
  new_balance integer;
begin
  if uid is null then
    raise exception 'Entre na sua conta para enviar presentes.';
  end if;
  if p_to is null or p_to = uid then
    raise exception 'Escolha outro leitor para presentear.';
  end if;
  if p_ref is null or char_length(p_ref) < 8 or char_length(p_ref) > 80 then
    raise exception 'Pedido de presente inválido.';
  end if;
  if not exists (select 1 from auth.users u where u.id = p_to) then
    raise exception 'Leitor não encontrado.';
  end if;

  select * into rules from public.gift_rules();

  -- Repetir o mesmo pedido devolve o resultado sem cobrar de novo.
  select * into existing from public.user_gifts g where g.from_user = uid and g.ref = p_ref;
  if found then
    select coalesce((select w.balance from public.diamond_wallets w where w.user_id = uid), 0)
      into new_balance;
    select coalesce((select u.used from public.api_daily_usage u
                      where u.user_id = uid and u.day = today and u.feature = 'gift_sent'), 0)
      into used_today;
    return query
      select new_balance, rules.daily_limit, greatest(0, rules.daily_limit - used_today),
             (select count(*)::integer from public.user_gifts g2
               where g2.from_user = uid and g2.week_start = week),
             true;
    return;
  end if;

  if not exists (
    select 1 from auth.users u
     where u.id = uid
       and u.created_at <= now() - make_interval(days => rules.min_account_age_days)
  ) then
    raise exception 'Sua conta precisa ter pelo menos % dias para enviar presentes.',
      rules.min_account_age_days;
  end if;

  select * into gift from public.gift_catalog c where c.id = p_gift and c.active;
  if not found then
    raise exception 'Presente indisponível.';
  end if;

  -- Limite diario atomico. Se falhar, a transacao inteira volta (inclusive o saldo).
  insert into public.api_daily_usage (user_id, day, feature, used)
  values (uid, today, 'gift_sent', 1)
  on conflict (user_id, day, feature) do update
     set used = api_daily_usage.used + 1
   where api_daily_usage.used < rules.daily_limit
  returning used into used_today;
  if used_today is null then
    raise exception 'Você já enviou os % presentes de hoje.', rules.daily_limit;
  end if;

  new_balance := public.apply_diamonds(uid, -gift.price, 'gift_sent', p_ref);
  if new_balance is null then
    raise exception 'Pedido de presente repetido.';
  end if;

  insert into public.user_gifts (from_user, to_user, gift_id, week_start, ref)
  values (uid, p_to, gift.id, week, p_ref);

  return query
    select new_balance, rules.daily_limit, greatest(0, rules.daily_limit - used_today),
           (select count(*)::integer from public.user_gifts g
             where g.from_user = uid and g.week_start = week),
           false;
end;
$$;

create or replace function public.my_gifts_received(p_limit integer default 20)
returns table (
  gift_id text,
  name text,
  icon text,
  price integer,
  from_user uuid,
  from_name text,
  created_at timestamptz
)
language sql
stable
security definer
set search_path = public
as $$
  select g.gift_id, c.name, c.icon, c.price, g.from_user,
         left(coalesce(nullif(btrim(u.raw_user_meta_data ->> 'name'), ''), 'Leitor'), 60),
         g.created_at
  from public.user_gifts g
  join public.gift_catalog c on c.id = g.gift_id
  join auth.users u on u.id = g.from_user
  where g.to_user = auth.uid()
  order by g.created_at desc, g.id desc
  limit greatest(least(p_limit, 100), 1);
$$;

create or replace function public.my_gift_summary()
returns table (received_total integer, received_this_week integer, sent_this_week integer)
language sql
stable
security definer
set search_path = public
as $$
  select
    (select count(*)::integer from public.user_gifts g where g.to_user = auth.uid()),
    (select count(*)::integer from public.user_gifts g
      where g.to_user = auth.uid() and g.week_start = public.ranking_week_start(0)),
    (select count(*)::integer from public.user_gifts g
      where g.from_user = auth.uid() and g.week_start = public.ranking_week_start(0));
$$;

create or replace function public.weekly_gift_counts(p_offset integer default 0)
returns table (user_id uuid, gifts_received integer)
language sql
stable
security definer
set search_path = public
as $$
  select g.to_user, count(*)::integer
  from public.user_gifts g
  where g.week_start = public.ranking_week_start(greatest(least(p_offset, 0), -1))
  group by g.to_user;
$$;

revoke execute on function public.gift_rules() from public, anon;
revoke execute on function public.gift_catalog() from public, anon;
revoke execute on function public.send_gift(uuid, text, text) from public, anon;
revoke execute on function public.my_gifts_received(integer) from public, anon;
revoke execute on function public.my_gift_summary() from public, anon;
revoke execute on function public.weekly_gift_counts(integer) from public, anon;
grant execute on function public.gift_rules() to authenticated;
grant execute on function public.gift_catalog() to authenticated;
grant execute on function public.send_gift(uuid, text, text) to authenticated;
grant execute on function public.my_gifts_received(integer) to authenticated;
grant execute on function public.my_gift_summary() to authenticated;
grant execute on function public.weekly_gift_counts(integer) to authenticated;
