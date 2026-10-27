-- Dashboard do admin: cadastros por bucket, janela de 24h e série de assinaturas.
-- Mesmas regras da V19: security definer, só agregados (sem user_id/e-mail) e EXECUTE só para o
-- runtime (storyverse_api).

-- ---- 2.1 Cadastros ----------------------------------------------------------------------
-- Mudar o "returns table" exige drop; os grants são refeitos no fim.
drop function if exists public.admin_metrics_accounts();
drop function if exists public.admin_metrics_daily(date, date);
drop function if exists public.admin_metrics_weekly(date, date);

create index if not exists auth_users_created_at_idx on auth.users (created_at);

create function public.admin_metrics_accounts()
returns table(total bigint, new24h bigint, new_today bigint, new7 bigint, new30 bigint,
              confirmed bigint, staff bigint)
language sql
security definer
set search_path = public
stable
as $$
  select count(*)::bigint as total,
         count(*) filter (where u.created_at >= now() - interval '24 hours')::bigint as new24h,
         count(*) filter (where (u.created_at at time zone 'America/Sao_Paulo')::date
                                = (now() at time zone 'America/Sao_Paulo')::date)::bigint as new_today,
         count(*) filter (where u.created_at >= now() - interval '7 days')::bigint as new7,
         count(*) filter (where u.created_at >= now() - interval '30 days')::bigint as new30,
         count(*) filter (where u.email_confirmed_at is not null)::bigint as confirmed,
         (select count(distinct r.user_id)::bigint from public.user_roles r) as staff
    from auth.users u
$$;

create function public.admin_metrics_daily(p_from date, p_to date)
returns table(day date, active_readers bigint, seconds bigint, books_started bigint,
              books_finished bigint, entries_created bigint, signups bigint)
language sql
security definer
set search_path = public
stable
as $$
  with days as (
    select generate_series(p_from, p_to, interval '1 day')::date as day
  ),
  readers as (
    select r.day, count(*) filter (where r.seconds > 0) as active_readers,
           coalesce(sum(r.seconds), 0)::bigint as seconds
      from public.reading_days r
     where r.day between p_from and p_to
     group by r.day
  ),
  started as (
    select e.started_on as day, count(*) as books_started
      from public.library_entries e
     where e.deleted_at is null and e.started_on between p_from and p_to
     group by e.started_on
  ),
  finished as (
    select e.finished_on as day, count(*) as books_finished
      from public.library_entries e
     where e.deleted_at is null and e.finished_on between p_from and p_to
     group by e.finished_on
  ),
  created as (
    select (e.created_at at time zone 'America/Sao_Paulo')::date as day, count(*) as entries_created
      from public.library_entries e
     where e.deleted_at is null
       and (e.created_at at time zone 'America/Sao_Paulo')::date between p_from and p_to
     group by 1
  ),
  signed as (
    -- Filtro em timestamptz (usa o índice); o dia é o calendário de São Paulo.
    select (u.created_at at time zone 'America/Sao_Paulo')::date as day, count(*) as signups
      from auth.users u
     where u.created_at >= (p_from::timestamp at time zone 'America/Sao_Paulo')
       and u.created_at < ((p_to + 1)::timestamp at time zone 'America/Sao_Paulo')
     group by 1
  )
  select d.day,
         coalesce(r.active_readers, 0)::bigint as active_readers,
         coalesce(r.seconds, 0)::bigint as seconds,
         coalesce(s.books_started, 0)::bigint as books_started,
         coalesce(f.books_finished, 0)::bigint as books_finished,
         coalesce(c.entries_created, 0)::bigint as entries_created,
         coalesce(g.signups, 0)::bigint as signups
    from days d
    left join readers r on r.day = d.day
    left join started s on s.day = d.day
    left join finished f on f.day = d.day
    left join created c on c.day = d.day
    left join signed g on g.day = d.day
   order by d.day
$$;

create function public.admin_metrics_weekly(p_from date, p_to date)
returns table(bucket date, active_readers bigint, seconds bigint, books_started bigint,
              books_finished bigint, signups bigint)
language sql
security definer
set search_path = public
stable
as $$
  with weeks as (
    select generate_series(date_trunc('week', p_from)::date, date_trunc('week', p_to)::date,
                           interval '1 week')::date as bucket
  ),
  readers as (
    select date_trunc('week', r.day)::date as bucket,
           count(distinct r.user_id)::bigint as active_readers,
           coalesce(sum(r.seconds), 0)::bigint as seconds
      from public.reading_days r
     where r.day between p_from and p_to and r.seconds > 0
     group by 1
  ),
  started as (
    select date_trunc('week', e.started_on)::date as bucket, count(*)::bigint as books_started
      from public.library_entries e
     where e.deleted_at is null and e.started_on between p_from and p_to
     group by 1
  ),
  finished as (
    select date_trunc('week', e.finished_on)::date as bucket, count(*)::bigint as books_finished
      from public.library_entries e
     where e.deleted_at is null and e.finished_on between p_from and p_to
     group by 1
  ),
  signed as (
    select date_trunc('week', u.created_at at time zone 'America/Sao_Paulo')::date as bucket,
           count(*)::bigint as signups
      from auth.users u
     where u.created_at >= (p_from::timestamp at time zone 'America/Sao_Paulo')
       and u.created_at < ((p_to + 1)::timestamp at time zone 'America/Sao_Paulo')
     group by 1
  )
  select w.bucket,
         coalesce(r.active_readers, 0)::bigint as active_readers,
         coalesce(r.seconds, 0)::bigint as seconds,
         coalesce(s.books_started, 0)::bigint as books_started,
         coalesce(f.books_finished, 0)::bigint as books_finished,
         coalesce(g.signups, 0)::bigint as signups
    from weeks w
    left join readers r on r.bucket = w.bucket
    left join started s on s.bucket = w.bucket
    left join finished f on f.bucket = w.bucket
    left join signed g on g.bucket = w.bucket
   order by w.bucket
$$;

revoke execute on function public.admin_metrics_accounts() from public, anon, authenticated;
revoke execute on function public.admin_metrics_daily(date, date) from public, anon, authenticated;
revoke execute on function public.admin_metrics_weekly(date, date) from public, anon, authenticated;
grant execute on function public.admin_metrics_accounts() to storyverse_api;
grant execute on function public.admin_metrics_daily(date, date) to storyverse_api;
grant execute on function public.admin_metrics_weekly(date, date) to storyverse_api;

-- ---- 2.4 Assinaturas no tempo -----------------------------------------------------------
-- O webhook guardava só o id e o tipo do evento (billing_events), sem valor: não dá para
-- reconstruir receita nem MRR exato do passado. A partir desta versão:
--   * billing_invoices: cada invoice.paid, com o valor pago (receita por bucket);
--   * billing_daily_snapshots: foto diária do MRR/ativas gravada por job (MRR histórico).
-- Para os dias anteriores ao primeiro snapshot a série estima a partir de billing_subscriptions
-- (veja admin_billing_series) e marca o ponto como estimated.

create table if not exists public.billing_invoices (
  stripe_invoice_id text primary key,
  stripe_subscription_id text,
  user_id uuid references auth.users (id) on delete set null,
  amount_paid_cents integer not null,
  currency text,
  paid_at timestamptz not null,
  created_at timestamptz not null default now()
);

create index if not exists billing_invoices_paid_idx on public.billing_invoices (paid_at);
create index if not exists billing_invoices_subscription_idx
  on public.billing_invoices (stripe_subscription_id, paid_at);

create table if not exists public.billing_daily_snapshots (
  day date primary key,
  mrr_cents bigint not null,
  active bigint not null,
  trialing bigint not null,
  past_due bigint not null,
  taken_at timestamptz not null default now()
);

create index if not exists billing_subscriptions_created_idx
  on public.billing_subscriptions (created_at);
create index if not exists billing_subscriptions_canceled_idx
  on public.billing_subscriptions (canceled_at) where canceled_at is not null;

alter table public.billing_invoices enable row level security;
alter table public.billing_daily_snapshots enable row level security;

drop policy if exists "billing invoice leitura" on public.billing_invoices;
create policy "billing invoice leitura" on public.billing_invoices
  for select using (public.has_role('superadmin'));

drop policy if exists "billing backend escreve invoice" on public.billing_invoices;
create policy "billing backend escreve invoice" on public.billing_invoices
  for all using (current_setting('app.billing_write', true) = 'on')
  with check (current_setting('app.billing_write', true) = 'on');

drop policy if exists "billing snapshot leitura" on public.billing_daily_snapshots;
create policy "billing snapshot leitura" on public.billing_daily_snapshots
  for select using (public.has_role('superadmin'));

drop policy if exists "billing backend escreve snapshot" on public.billing_daily_snapshots;
create policy "billing backend escreve snapshot" on public.billing_daily_snapshots
  for all using (current_setting('app.billing_write', true) = 'on')
  with check (current_setting('app.billing_write', true) = 'on');

grant select, insert, update, delete on public.billing_invoices, public.billing_daily_snapshots
  to authenticated;

-- Foto do dia (idempotente: o job pode rodar de novo no mesmo dia e atualiza a linha).
create or replace function public.admin_billing_snapshot(p_day date)
returns void
language sql
security definer
set search_path = public
as $$
  insert into public.billing_daily_snapshots (day, mrr_cents, active, trialing, past_due, taken_at)
  select p_day,
         coalesce(sum(s.amount_cents) filter (where s.status = 'active'), 0)::bigint,
         count(*) filter (where s.status = 'active')::bigint,
         count(*) filter (where s.status = 'trialing')::bigint,
         count(*) filter (where s.status = 'past_due')::bigint,
         now()
    from public.billing_subscriptions s
  on conflict (day) do update
     set mrr_cents = excluded.mrr_cents,
         active = excluded.active,
         trialing = excluded.trialing,
         past_due = excluded.past_due,
         taken_at = excluded.taken_at
$$;

-- Série por dia ou semana (segunda-feira), no calendário de São Paulo.
-- mrr_cents/active vêm do último dia do bucket dentro do período:
--   * hoje: estado atual de billing_subscriptions (mesma regra do billing/summary);
--   * dia com snapshot: o snapshot;
--   * dia sem snapshot (antes do job existir ou com a API fora do ar): estimativa — assinatura
--     fora do teste, criada até o fim do dia e ainda não encerrada nele, pelo valor atual do plano.
--     A estimativa não enxerga troca de preço nem os dias em atraso; o ponto sai com estimated.
-- new_count/canceled_count seguem o billing/summary (created_at e canceled_at); revenue_cents soma
-- as faturas pagas (billing_invoices), que só existem a partir desta versão.
create or replace function public.admin_billing_series(p_from date, p_to date, p_group text)
returns table(bucket date, mrr_cents bigint, active bigint, new_count bigint, canceled_count bigint,
              revenue_cents bigint, estimated boolean)
language sql
security definer
set search_path = public
stable
as $$
  with days as (
    select g::date as day,
           case when p_group = 'week' then date_trunc('week', g)::date else g::date end as bucket,
           ((g::date + 1)::timestamp at time zone 'America/Sao_Paulo') as day_end
      from generate_series(p_from, p_to, interval '1 day') g
  ),
  closing as (
    select distinct on (d.bucket) d.bucket, d.day, d.day_end
      from days d
     order by d.bucket, d.day desc
  ),
  subs as (
    select s.id, s.amount_cents, s.created_at, s.trial_end,
           coalesce(s.ended_at,
                    case when s.status in ('canceled', 'unpaid')
                         then coalesce(s.canceled_at, s.updated_at) end) as ends_at
      from public.billing_subscriptions s
     where s.status not in ('incomplete', 'incomplete_expired')
  ),
  estimate as (
    select c.bucket,
           coalesce(sum(s.amount_cents), 0)::bigint as mrr_cents,
           count(s.id)::bigint as active
      from closing c
      left join subs s
        on s.created_at < c.day_end
       and (s.trial_end is null or s.trial_end < c.day_end)
       and (s.ends_at is null or s.ends_at >= c.day_end)
     group by c.bucket
  ),
  live as (
    select coalesce(sum(s.amount_cents) filter (where s.status = 'active'), 0)::bigint as mrr_cents,
           count(*) filter (where s.status = 'active')::bigint as active
      from public.billing_subscriptions s
  ),
  created as (
    select d.bucket, count(*)::bigint as new_count
      from public.billing_subscriptions s
      join days d on d.day = (s.created_at at time zone 'America/Sao_Paulo')::date
     group by d.bucket
  ),
  canceled as (
    select d.bucket, count(*)::bigint as canceled_count
      from public.billing_subscriptions s
      join days d on d.day = (s.canceled_at at time zone 'America/Sao_Paulo')::date
     group by d.bucket
  ),
  revenue as (
    select d.bucket, coalesce(sum(i.amount_paid_cents), 0)::bigint as revenue_cents
      from public.billing_invoices i
      join days d on d.day = (i.paid_at at time zone 'America/Sao_Paulo')::date
     group by d.bucket
  )
  select c.bucket,
         case when c.day = (now() at time zone 'America/Sao_Paulo')::date then l.mrr_cents
              else coalesce(snap.mrr_cents, e.mrr_cents) end as mrr_cents,
         case when c.day = (now() at time zone 'America/Sao_Paulo')::date then l.active
              else coalesce(snap.active, e.active) end as active,
         coalesce(n.new_count, 0)::bigint as new_count,
         coalesce(x.canceled_count, 0)::bigint as canceled_count,
         coalesce(r.revenue_cents, 0)::bigint as revenue_cents,
         (c.day <> (now() at time zone 'America/Sao_Paulo')::date and snap.day is null) as estimated
    from closing c
    cross join live l
    left join public.billing_daily_snapshots snap on snap.day = c.day
    left join estimate e on e.bucket = c.bucket
    left join created n on n.bucket = c.bucket
    left join canceled x on x.bucket = c.bucket
    left join revenue r on r.bucket = c.bucket
   order by c.bucket
$$;

-- Conversão de teste em pago no período [p_from, p_to):
--   trials_started: assinaturas com teste criadas no período;
--   trials_ended: testes que terminaram no período (e já terminaram);
--   trials_converted: desses, os que viraram pagos (fatura paga com valor depois do fim do teste,
--     ou assinatura hoje active/past_due — cobre os testes anteriores a billing_invoices).
create or replace function public.admin_billing_trials(p_from timestamptz, p_to timestamptz)
returns table(trials_started bigint, trials_ended bigint, trials_converted bigint)
language sql
security definer
set search_path = public
stable
as $$
  select count(*) filter (where s.created_at >= p_from and s.created_at < p_to)::bigint,
         count(*) filter (where s.trial_end >= p_from and s.trial_end < p_to
                            and s.trial_end <= now())::bigint,
         count(*) filter (where s.trial_end >= p_from and s.trial_end < p_to
                            and s.trial_end <= now()
                            and (s.status in ('active', 'past_due')
                                 or exists (select 1 from public.billing_invoices i
                                             where i.stripe_subscription_id = s.stripe_subscription_id
                                               and i.amount_paid_cents > 0
                                               and i.paid_at >= s.trial_end)))::bigint
    from public.billing_subscriptions s
   where s.trial_end is not null
$$;

revoke execute on function public.admin_billing_snapshot(date) from public, anon, authenticated;
revoke execute on function public.admin_billing_series(date, date, text) from public, anon, authenticated;
revoke execute on function public.admin_billing_trials(timestamptz, timestamptz) from public, anon, authenticated;
grant execute on function public.admin_billing_snapshot(date) to storyverse_api;
grant execute on function public.admin_billing_series(date, date, text) to storyverse_api;
grant execute on function public.admin_billing_trials(timestamptz, timestamptz) to storyverse_api;
