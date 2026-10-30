-- Ranking geral: soma toda a leitura da conta, sem data. Quem lê sempre não some do ranking na
-- virada da semana. O semanal (weekly_ranking) continua com o pódio e os diamantes; este não paga
-- nada, só mostra. Mesmas regras do semanal: só o tempo creditado pelo reading_heartbeat (que vai
-- para reading_days), pelo menos 1 minuto e quem não se escondeu.

create or replace function public.alltime_ranking(p_limit integer default 50)
returns table (place integer, user_id uuid, name text, avatar text, photo text, minutes integer, is_me boolean)
language sql
stable
security definer
set search_path = public
as $$
  with totals as (
    select d.user_id, (sum(d.seconds) / 60)::integer as minutes, max(d.updated_at) as last_update
    from public.reading_days d
    group by d.user_id
  ),
  ranked as (
    select t.user_id, t.minutes,
           (row_number() over (order by t.minutes desc, t.last_update asc, t.user_id))::integer as place
    from totals t
    join public.ranking_profiles p on p.user_id = t.user_id
    where t.minutes >= 1 and not p.hidden
  )
  select r.place, r.user_id, p.name, p.avatar, p.photo, r.minutes, r.user_id = auth.uid()
  from ranked r
  join public.ranking_profiles p on p.user_id = r.user_id
  where r.place <= greatest(least(p_limit, 100), 3) or r.user_id = auth.uid()
  order by r.place;
$$;

revoke execute on function public.alltime_ranking(integer) from public, anon;
grant execute on function public.alltime_ranking(integer) to authenticated;
