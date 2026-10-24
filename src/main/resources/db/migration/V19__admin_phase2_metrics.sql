-- Fase 2C: métricas de produto. As funções são security definer e devolvem só agregados,
-- sem user_id/e-mail. O runtime (storyverse_api) recebe EXECUTE; o anon/authenticated não.
create index if not exists reading_days_day_idx
  on public.reading_days (day) include (user_id, seconds)
  where seconds > 0;

create index if not exists library_entries_started_idx
  on public.library_entries (started_on)
  where deleted_at is null and started_on is not null;

create index if not exists library_entries_finished_idx
  on public.library_entries (finished_on)
  where deleted_at is null and finished_on is not null;

create index if not exists library_entries_created_idx
  on public.library_entries (created_at)
  where deleted_at is null;

create or replace function public.admin_metrics_daily(p_from date, p_to date)
returns table(day date, active_readers bigint, seconds bigint, books_started bigint,
              books_finished bigint, entries_created bigint)
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
  )
  select d.day,
         coalesce(r.active_readers, 0)::bigint as active_readers,
         coalesce(r.seconds, 0)::bigint as seconds,
         coalesce(s.books_started, 0)::bigint as books_started,
         coalesce(f.books_finished, 0)::bigint as books_finished,
         coalesce(c.entries_created, 0)::bigint as entries_created
    from days d
    left join readers r on r.day = d.day
    left join started s on s.day = d.day
    left join finished f on f.day = d.day
    left join created c on c.day = d.day
   order by d.day
$$;

create or replace function public.admin_metrics_weekly(p_from date, p_to date)
returns table(bucket date, active_readers bigint, seconds bigint, books_started bigint, books_finished bigint)
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
  )
  select w.bucket,
         coalesce(r.active_readers, 0)::bigint as active_readers,
         coalesce(r.seconds, 0)::bigint as seconds,
         coalesce(s.books_started, 0)::bigint as books_started,
         coalesce(f.books_finished, 0)::bigint as books_finished
    from weeks w
    left join readers r on r.bucket = w.bucket
    left join started s on s.bucket = w.bucket
    left join finished f on f.bucket = w.bucket
   order by w.bucket
$$;

create or replace function public.admin_metrics_accounts()
returns table(total bigint, new7 bigint, new30 bigint, confirmed bigint, staff bigint)
language sql
security definer
set search_path = public
stable
as $$
  select count(*)::bigint as total,
         count(*) filter (where u.created_at >= now() - interval '7 days')::bigint as new7,
         count(*) filter (where u.created_at >= now() - interval '30 days')::bigint as new30,
         count(*) filter (where u.email_confirmed_at is not null)::bigint as confirmed,
         (select count(distinct r.user_id)::bigint from public.user_roles r) as staff
    from auth.users u
$$;

create or replace function public.admin_metrics_reading(p_from date, p_to date)
returns table(active_readers bigint, seconds bigint, books_started bigint, books_finished bigint,
              entries_created bigint)
language sql
security definer
set search_path = public
stable
as $$
  select (select count(distinct r.user_id)::bigint
            from public.reading_days r
           where r.day between p_from and p_to and r.seconds > 0) as active_readers,
         (select coalesce(sum(r.seconds), 0)::bigint
            from public.reading_days r
           where r.day between p_from and p_to) as seconds,
         (select count(*)::bigint
            from public.library_entries e
           where e.deleted_at is null and e.started_on between p_from and p_to) as books_started,
         (select count(*)::bigint
            from public.library_entries e
           where e.deleted_at is null and e.finished_on between p_from and p_to) as books_finished,
         (select count(*)::bigint
            from public.library_entries e
           where e.deleted_at is null
             and (e.created_at at time zone 'America/Sao_Paulo')::date between p_from and p_to) as entries_created
$$;

create or replace function public.admin_metrics_features()
returns table(feature text, enabled bigint)
language sql
security definer
set search_path = public
stable
as $$
  select f.feature, count(*)::bigint as enabled
    from public.feature_access f
   where f.enabled
   group by f.feature
   order by f.feature
$$;

create or replace function public.admin_metrics_retention(p_from date, p_to date)
returns table(cohort_week date, cohort_size bigint, d1 bigint, d7 bigint, d30 bigint)
language sql
security definer
set search_path = public
stable
as $$
  with cohort as (
    select u.id,
           (u.created_at at time zone 'America/Sao_Paulo')::date as signup_day,
           date_trunc('week', u.created_at at time zone 'America/Sao_Paulo')::date as cohort_week
      from auth.users u
     where (u.created_at at time zone 'America/Sao_Paulo')::date between p_from and p_to
  )
  select c.cohort_week,
         count(*)::bigint as cohort_size,
         count(*) filter (where exists (
           select 1 from public.reading_days r
            where r.user_id = c.id and r.seconds > 0
              and r.day between c.signup_day and c.signup_day + 1
         ))::bigint as d1,
         count(*) filter (where exists (
           select 1 from public.reading_days r
            where r.user_id = c.id and r.seconds > 0
              and r.day between c.signup_day and c.signup_day + 7
         ))::bigint as d7,
         count(*) filter (where exists (
           select 1 from public.reading_days r
            where r.user_id = c.id and r.seconds > 0
              and r.day between c.signup_day and c.signup_day + 30
         ))::bigint as d30
    from cohort c
   group by c.cohort_week
   order by c.cohort_week
$$;

revoke execute on function public.admin_metrics_daily(date, date) from public, anon, authenticated;
revoke execute on function public.admin_metrics_accounts() from public, anon, authenticated;
revoke execute on function public.admin_metrics_weekly(date, date) from public, anon, authenticated;
revoke execute on function public.admin_metrics_reading(date, date) from public, anon, authenticated;
revoke execute on function public.admin_metrics_features() from public, anon, authenticated;
revoke execute on function public.admin_metrics_retention(date, date) from public, anon, authenticated;

grant execute on function public.admin_metrics_daily(date, date) to storyverse_api;
grant execute on function public.admin_metrics_accounts() to storyverse_api;
grant execute on function public.admin_metrics_weekly(date, date) to storyverse_api;
grant execute on function public.admin_metrics_reading(date, date) to storyverse_api;
grant execute on function public.admin_metrics_features() to storyverse_api;
grant execute on function public.admin_metrics_retention(date, date) to storyverse_api;
