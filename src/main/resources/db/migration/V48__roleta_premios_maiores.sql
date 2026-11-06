-- Roleta diária com prêmios maiores: de 5 a 100 diamantes (antes 1 a 20). O app desenha as fatias
-- com estes mesmos valores (SPIN_SEGMENTS no story-verse-web). Média por giro: ~14 (antes ~2,7).
--   5 → 34% · 10 → 30% · 15 → 17% · 25 → 12% · 50 → 6% · 100 → 1%
-- O resto da função é igual: um giro por dia (horário de Brasília), e quem já girou recebe o
-- resultado do dia de novo, sem ganhar outra vez.
create or replace function public.spin_daily_wheel()
returns table (prize integer, is_new boolean, balance integer)
language plpgsql
security definer
set search_path = public
as $$
#variable_conflict use_column
declare
  uid uuid := auth.uid();
  today text := ((now() at time zone 'America/Sao_Paulo')::date)::text;
  r double precision := random();
  won integer;
  bal integer;
begin
  if uid is null then
    raise exception 'Entre na sua conta para girar a roleta.';
  end if;
  won := case
    when r < 0.34 then 5
    when r < 0.64 then 10
    when r < 0.81 then 15
    when r < 0.93 then 25
    when r < 0.99 then 50
    else 100
  end;
  bal := public.apply_diamonds(uid, won, 'daily_spin', today);
  if bal is not null then
    return query select won, true, bal;
    return;
  end if;
  return query
    select l.amount, false, w.balance
    from public.diamond_ledger l
    join public.diamond_wallets w on w.user_id = l.user_id
    where l.user_id = uid and l.reason = 'daily_spin' and l.ref = today;
end;
$$;

revoke execute on function public.spin_daily_wheel() from public, anon;
grant execute on function public.spin_daily_wheel() to authenticated;
