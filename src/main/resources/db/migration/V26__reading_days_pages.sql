-- Dashboard do admin, item 2.2 da docs/SPEC_ADMIN_DASHBOARD.md (story-verse-web): páginas lidas.
--
-- Regra de produto: 1 página = 250 palavras lidas, nos modos paginado e rolagem, sem depender do
-- tamanho da tela ou da fonte. O leitor conta as palavras que passaram pela tela (cada trecho do
-- livro conta uma vez por dia) e envia o total do dia em report_reading:
--   { "2026-10-27": { "seconds": 900, "pages": 12 }, ... }
-- O formato antigo ({ "2026-10-27": 900 }) continua aceito e não mexe nas páginas.
--
-- O número vem do cliente, então o banco limita:
--   * 0 a 1000 páginas por dia (o check da coluna); valor fora disso, ou não inteiro, é erro;
--   * no máximo 1 página a cada 6 segundos de leitura que o servidor contou (reading_heartbeat),
--     ou seja, 2500 palavras por minuto: só atualiza um dia que já tem tempo de leitura;
--   * só os últimos 8 dias (o que o app envia); dias fora disso são ignorados;
--   * vale o maior valor enviado no dia (o app manda o acumulado; reenviar não soma).
-- Os segundos enviados continuam ignorados: só o reading_heartbeat credita tempo.

alter table public.reading_days
  add column if not exists pages integer not null default 0;

alter table public.reading_days drop constraint if exists reading_days_pages_check;
alter table public.reading_days
  add constraint reading_days_pages_check check (pages between 0 and 1000);

create or replace function public.report_reading(p_days jsonb)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  uid uuid := auth.uid();
  meta jsonb;
  today date := (now() at time zone 'America/Sao_Paulo')::date;
  photo_url text;
  entry record;
  report_day date;
  pages_value jsonb;
begin
  if uid is null then
    raise exception 'Entre na sua conta para participar do ranking.';
  end if;
  if p_days is not null and jsonb_typeof(p_days) <> 'object' then
    raise exception 'Leitura inválida.';
  end if;
  if (select count(*) from jsonb_object_keys(coalesce(p_days, '{}'::jsonb))) > 31 then
    raise exception 'Leitura inválida: dias demais.';
  end if;
  -- Client totals are untrusted; only reading_heartbeat credits server elapsed time. Pages are
  -- capped by that server time (1 page per 6 seconds) and by the column check.
  for entry in select key, value from jsonb_each(coalesce(p_days, '{}'::jsonb)) loop
    continue when jsonb_typeof(entry.value) <> 'object';
    if entry.key !~ '^\d{4}-\d{2}-\d{2}$' then
      raise exception 'Leitura inválida: dia %.', left(entry.key, 20);
    end if;
    report_day := entry.key::date;
    pages_value := entry.value -> 'pages';
    continue when pages_value is null or report_day not between today - 7 and today;
    if jsonb_typeof(pages_value) <> 'number' or pages_value::text !~ '^\d+$'
       or pages_value::text::numeric > 1000 then
      raise exception 'Leitura inválida: páginas devem ser um inteiro de 0 a 1000.';
    end if;
    update public.reading_days d
       set pages = least(pages_value::text::integer, d.seconds / 6)
     where d.user_id = uid and d.day = report_day
       and d.pages < least(pages_value::text::integer, d.seconds / 6);
  end loop;

  -- Nome e avatar do perfil; sem nome aparece "Leitor". Só aceita foto da pasta da própria
  -- pessoa (profile-media/<id>/avatar|cover), no Storage da API (ver is_own_media_url).
  select u.raw_user_meta_data into meta from auth.users u where u.id = uid;
  photo_url := case when char_length(meta ->> 'photo') <= 500
                     and public.is_own_media_url(meta ->> 'photo', uid, '(avatar|cover)([?]v=[0-9]+)?')
                    then meta ->> 'photo' end;
  insert into public.ranking_profiles (user_id, name, avatar, photo)
  values (uid, left(coalesce(nullif(btrim(meta ->> 'name'), ''), 'Leitor'), 60), left(meta ->> 'avatar', 40), photo_url)
  on conflict (user_id) do update
    set name = excluded.name, avatar = excluded.avatar, photo = excluded.photo, updated_at = now()
    where (ranking_profiles.name, ranking_profiles.avatar, ranking_profiles.photo)
          is distinct from (excluded.name, excluded.avatar, excluded.photo);
end;
$$;

revoke execute on function public.report_reading(jsonb) from public, anon;
grant execute on function public.report_reading(jsonb) to authenticated;

-- ---- Métricas: campo pages ----------------------------------------------------------------
-- Mudar o "returns table" exige drop; os grants são refeitos no fim. Corpos iguais aos da V25
-- (daily/weekly) e da V19 (reading), mais a soma de pages.
drop function if exists public.admin_metrics_daily(date, date);
drop function if exists public.admin_metrics_weekly(date, date);
drop function if exists public.admin_metrics_reading(date, date);

create function public.admin_metrics_daily(p_from date, p_to date)
returns table(day date, active_readers bigint, seconds bigint, books_started bigint,
              books_finished bigint, entries_created bigint, signups bigint, pages bigint)
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
           coalesce(sum(r.seconds), 0)::bigint as seconds,
           coalesce(sum(r.pages), 0)::bigint as pages
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
         coalesce(g.signups, 0)::bigint as signups,
         coalesce(r.pages, 0)::bigint as pages
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
              books_finished bigint, signups bigint, pages bigint)
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
           coalesce(sum(r.seconds), 0)::bigint as seconds,
           coalesce(sum(r.pages), 0)::bigint as pages
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
         coalesce(g.signups, 0)::bigint as signups,
         coalesce(r.pages, 0)::bigint as pages
    from weeks w
    left join readers r on r.bucket = w.bucket
    left join started s on s.bucket = w.bucket
    left join finished f on f.bucket = w.bucket
    left join signed g on g.bucket = w.bucket
   order by w.bucket
$$;

create function public.admin_metrics_reading(p_from date, p_to date)
returns table(active_readers bigint, seconds bigint, books_started bigint, books_finished bigint,
              entries_created bigint, pages bigint)
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
             and (e.created_at at time zone 'America/Sao_Paulo')::date between p_from and p_to) as entries_created,
         (select coalesce(sum(r.pages), 0)::bigint
            from public.reading_days r
           where r.day between p_from and p_to) as pages
$$;

revoke execute on function public.admin_metrics_daily(date, date) from public, anon, authenticated;
revoke execute on function public.admin_metrics_weekly(date, date) from public, anon, authenticated;
revoke execute on function public.admin_metrics_reading(date, date) from public, anon, authenticated;
grant execute on function public.admin_metrics_daily(date, date) to storyverse_api;
grant execute on function public.admin_metrics_weekly(date, date) to storyverse_api;
grant execute on function public.admin_metrics_reading(date, date) to storyverse_api;
