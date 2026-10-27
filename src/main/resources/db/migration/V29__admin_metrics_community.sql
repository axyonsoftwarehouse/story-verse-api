-- 2.6 Atividade da comunidade: tópicos, respostas e denúncias criados por dia ou semana.
--
-- Conta tudo o que foi criado no bucket, qualquer que seja o status atual: um tópico escondido
-- ou apagado pela moderação (status hidden/deleted, deleted_at) continua contando como criado,
-- porque a métrica mede o que a comunidade escreveu e denunciou. O fórum só faz exclusão lógica;
-- linhas somem de fato apenas quando a conta do autor é apagada (on delete cascade).
--
-- security definer: o papel de runtime lê o fórum sob RLS, que só mostra o publicado.
-- Os buckets vazios vêm com zero (generate_series), como em admin_metrics_daily/weekly.

create index if not exists forum_topics_created_at_idx on public.forum_topics (created_at);
create index if not exists forum_replies_created_at_idx on public.forum_replies (created_at);
create index if not exists forum_reports_created_at_idx on public.forum_reports (created_at);

create function public.admin_metrics_community(p_from date, p_to date, p_group text)
returns table(bucket date, topics bigint, replies bigint, reports bigint)
language sql
security definer
set search_path = public
stable
as $$
  with buckets as (
    select generate_series(
             case when p_group = 'week' then date_trunc('week', p_from)::date else p_from end,
             case when p_group = 'week' then date_trunc('week', p_to)::date else p_to end,
             case when p_group = 'week' then interval '1 week' else interval '1 day' end
           )::date as bucket
  ),
  events as (
    -- Filtro em timestamptz (usa os índices); o dia é o calendário de São Paulo.
    select 'topic' as kind, t.created_at from public.forum_topics t
     where t.created_at >= (p_from::timestamp at time zone 'America/Sao_Paulo')
       and t.created_at < ((p_to + 1)::timestamp at time zone 'America/Sao_Paulo')
    union all
    select 'reply', r.created_at from public.forum_replies r
     where r.created_at >= (p_from::timestamp at time zone 'America/Sao_Paulo')
       and r.created_at < ((p_to + 1)::timestamp at time zone 'America/Sao_Paulo')
    union all
    select 'report', f.created_at from public.forum_reports f
     where f.created_at >= (p_from::timestamp at time zone 'America/Sao_Paulo')
       and f.created_at < ((p_to + 1)::timestamp at time zone 'America/Sao_Paulo')
  ),
  counted as (
    select case when p_group = 'week'
                then date_trunc('week', e.created_at at time zone 'America/Sao_Paulo')::date
                else (e.created_at at time zone 'America/Sao_Paulo')::date end as bucket,
           count(*) filter (where e.kind = 'topic')::bigint as topics,
           count(*) filter (where e.kind = 'reply')::bigint as replies,
           count(*) filter (where e.kind = 'report')::bigint as reports
      from events e
     group by 1
  )
  select b.bucket,
         coalesce(c.topics, 0)::bigint as topics,
         coalesce(c.replies, 0)::bigint as replies,
         coalesce(c.reports, 0)::bigint as reports
    from buckets b
    left join counted c on c.bucket = b.bucket
   order by b.bucket
$$;

revoke execute on function public.admin_metrics_community(date, date, text) from public, anon, authenticated;
grant execute on function public.admin_metrics_community(date, date, text) to storyverse_api;
