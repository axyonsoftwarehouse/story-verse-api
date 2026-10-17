create or replace function public.close_reading_week(p_week date)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  perform pg_advisory_xact_lock(664332);
  if (p_week + 7)::timestamp + interval '1 hour' > (now() at time zone 'America/Sao_Paulo') then
    return;
  end if;
  insert into public.weekly_closings (week_start) values (p_week) on conflict do nothing;
  if not found then
    return;
  end if;
  insert into public.weekly_awards (week_start, user_id, place, minutes, diamonds)
  select p_week, r.user_id, r.place, r.minutes, (array[50, 30, 20])[r.place]
  from public.ranking_for_week(p_week, 3, null) r
  where r.place <= 3;
  perform public.apply_diamonds(a.user_id, a.diamonds, 'ranking', p_week::text)
  from public.weekly_awards a
  where a.week_start = p_week;
end;
$$;

create or replace function public.report_reading(p_days jsonb)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  uid uuid := auth.uid();
  meta jsonb;
  open_from date := public.ranking_week_start(-1);
  today date := (now() at time zone 'America/Sao_Paulo')::date;
  photo_url text;
begin
  if uid is null then
    raise exception 'Entre na sua conta para participar do ranking.';
  end if;
  -- Client totals are untrusted; only reading_heartbeat credits server elapsed time.

  -- Nome e avatar do perfil; sem nome aparece "Leitor". Só aceita foto da pasta da própria
  -- pessoa (profile-media/<id>/…), em qualquer domínio.
  select u.raw_user_meta_data into meta from auth.users u where u.id = uid;
  photo_url := case when meta ->> 'photo' like '%/profile-media/' || uid::text || '/%'
                    then left(meta ->> 'photo', 500) end;
  insert into public.ranking_profiles (user_id, name, avatar, photo)
  values (uid, left(coalesce(nullif(btrim(meta ->> 'name'), ''), 'Leitor'), 60), left(meta ->> 'avatar', 40), photo_url)
  on conflict (user_id) do update
    set name = excluded.name, avatar = excluded.avatar, photo = excluded.photo, updated_at = now()
    where (ranking_profiles.name, ranking_profiles.avatar, ranking_profiles.photo)
          is distinct from (excluded.name, excluded.avatar, excluded.photo);
end;
$$;

-- Install after weekly-ranking.sql / schema.sql. Totals from the client never grant rewards.
create table if not exists public.reading_clock (
  user_id uuid primary key references auth.users(id) on delete cascade,
  last_tick timestamptz not null,
  active boolean not null default false
);
alter table public.reading_clock enable row level security;
revoke all on public.reading_clock from public, anon, authenticated;

create or replace function public.reading_heartbeat(p_active boolean default true)
returns integer language plpgsql security definer set search_path = public as $$
declare
  uid uuid := auth.uid();
  previous public.reading_clock%rowtype;
  tick timestamptz;
  credit integer := 0;
  today date;
begin
  if uid is null then raise exception 'Entre na sua conta.'; end if;
  -- Same lock as weekly closing: no report may write a week after its awards are fixed.
  perform pg_advisory_xact_lock(664332);
  tick := clock_timestamp();
  insert into public.reading_clock(user_id,last_tick,active) values(uid,tick,false) on conflict do nothing;
  select * into previous from public.reading_clock where user_id=uid for update;
  if p_active and previous.active and tick - previous.last_tick between interval '0 seconds' and interval '45 seconds' then
    credit := least(30, floor(extract(epoch from tick - previous.last_tick))::integer);
  end if;
  update public.reading_clock set last_tick=tick, active=coalesce(p_active,false) where user_id=uid;
  today := (tick at time zone 'America/Sao_Paulo')::date;
  if credit > 0 then
    insert into public.reading_days(user_id,day,seconds,updated_at) values(uid,today,credit,tick)
    on conflict(user_id,day) do update
      set seconds=least(43200,reading_days.seconds+excluded.seconds), updated_at=excluded.updated_at;
  end if;
  perform public.report_reading('{}'::jsonb);
  return credit;
end;
$$;
revoke execute on function public.reading_heartbeat(boolean) from public, anon;
grant execute on function public.reading_heartbeat(boolean) to authenticated;
