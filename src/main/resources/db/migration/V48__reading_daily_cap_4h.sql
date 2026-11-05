-- Teto diário de leitura creditada pelo servidor: de 12 h para 4 h por pessoa e por dia.
--
-- O reading_heartbeat credita até 30 s a cada batida enquanto o app diz que a pessoa está lendo,
-- e o servidor não tem como conferir se há alguém de fato na tela. Com o teto de 12 h, uma conta
-- com um script batendo o dia todo levaria o pódio semanal (diamantes) com folga. Em produção
-- (60 dias até 05/11/2026) o maior dia real foi de 59 min e o p99 de 57 min; 4 h ainda cobre quem
-- deixa a narração por voz tocando por horas e corta em 3x o que um script ganha por dia.
--
-- Mesmo corpo da V24, só com 14400 no lugar de 43200. Os dias já gravados não mudam (nenhum
-- passava de 4 h em produção). O check da tabela (até 43200) fica como está.

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
  -- Shared side of the weekly-closing lock: heartbeats run in parallel with each other, and no
  -- report may write a week after its awards are fixed (close_reading_week takes it exclusive).
  perform pg_advisory_xact_lock_shared(664332);
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
      set seconds=least(14400,reading_days.seconds+excluded.seconds), updated_at=excluded.updated_at;
  end if;
  perform public.report_reading('{}'::jsonb);
  return credit;
end;
$$;

revoke execute on function public.reading_heartbeat(boolean) from public, anon;
grant execute on function public.reading_heartbeat(boolean) to authenticated;
