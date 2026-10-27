-- Dashboard do admin 2.3: usuários ativos (DAU/WAU/MAU).
-- Um registro por pessoa e por dia (calendário de São Paulo) em que houve request autenticado.
-- O backend grava no primeiro request do dia com auth.uid() = a pessoa; a leitura é só por
-- função security definer que devolve agregados (sem user_id), como as da V19/V25.

create table if not exists public.activity_days (
  user_id uuid not null references auth.users (id) on delete cascade,
  day date not null,
  primary key (user_id, day)
);

create index if not exists activity_days_day_idx on public.activity_days (day, user_id);

alter table public.activity_days enable row level security;
revoke all on public.activity_days from anon, authenticated;
grant select, insert on public.activity_days to storyverse_api;

drop policy if exists activity_days_owner on public.activity_days;
create policy activity_days_owner on public.activity_days for all to storyverse_api
  using (user_id = auth.uid()) with check (user_id = auth.uid());

-- Série diária: pessoas ativas em cada dia do período (dias sem atividade vêm com 0).
create or replace function public.admin_metrics_active_daily(p_from date, p_to date)
returns table(day date, active bigint)
language sql
security definer
set search_path = public
stable
as $$
  with days as (
    select generate_series(p_from, p_to, interval '1 day')::date as day
  ),
  counted as (
    select a.day, count(*) as active
      from public.activity_days a
     where a.day between p_from and p_to
     group by a.day
  )
  select d.day, coalesce(c.active, 0)::bigint as active
    from days d
    left join counted c on c.day = d.day
   order by d.day
$$;

-- Pessoas distintas no dia p_to (DAU), nos 7 dias até p_to (WAU) e nos 30 dias até p_to (MAU).
create or replace function public.admin_metrics_active_summary(p_to date)
returns table(dau bigint, wau bigint, mau bigint)
language sql
security definer
set search_path = public
stable
as $$
  select count(distinct a.user_id) filter (where a.day = p_to)::bigint as dau,
         count(distinct a.user_id) filter (where a.day > p_to - 7)::bigint as wau,
         count(distinct a.user_id)::bigint as mau
    from public.activity_days a
   where a.day > p_to - 30 and a.day <= p_to
$$;

revoke all on function public.admin_metrics_active_daily(date, date) from public, anon, authenticated;
revoke all on function public.admin_metrics_active_summary(date) from public, anon, authenticated;
grant execute on function public.admin_metrics_active_daily(date, date) to storyverse_api;
grant execute on function public.admin_metrics_active_summary(date) to storyverse_api;
