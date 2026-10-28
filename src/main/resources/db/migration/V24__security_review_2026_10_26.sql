-- Revisão de segurança de 26/10/2026 (story-verse-web): recria as funções corrigidas para que o
-- Flyway seja a fonte delas. Antes viviam só em database/schema.sql do story-verse-web e em patches
-- aplicados à mão (QA em 27/10; produção pelo passo a passo docs/PATCH_PRODUCAO_2026-10-26.md);
-- a V3 ainda cria as versões antigas de close_reading_week, report_reading e reading_heartbeat.
-- Corpos copiados sem mudança de deploy/sql/2026-10-26-revisao-seguranca-producao.sql (iguais aos
-- do schema.sql). Tudo é create or replace / revoke / grant: idempotente onde o patch já rodou.
-- Fica de fora o que depende do ambiente ou é correção pontual de dados: o
-- `alter database … set app.media_origin` e o update das fotos de ranking.

-- Endereço público de um arquivo da própria pessoa no Storage da API
-- (<origem>/api/v1/storage/public/profile-media/<id>/<p_rest>). A URL inteira precisa casar:
-- antes bastava o trecho aparecer em qualquer lugar (ex.: ?x=/profile-media/<id>/ num site
-- qualquer). Com `app.media_origin` definido no banco (alter database … set app.media_origin =
-- 'https://api…'), só vale essa origem; sem ele, qualquer origem https com esse caminho exato.
create or replace function public.is_own_media_url(p_url text, p_uid uuid, p_rest text)
returns boolean
language sql
stable
set search_path = public
as $$
  select coalesce(
    p_url ~ ('^(https://[A-Za-z0-9.-]+|http://(localhost|127[.]0[.]0[.]1))(:[0-9]+)?'
             || '/api/v1/storage/public/profile-media/' || p_uid::text || '/' || p_rest || '$')
    and (nullif(current_setting('app.media_origin', true), '') is null
         or starts_with(p_url, rtrim(current_setting('app.media_origin', true), '/') || '/api/')),
    false);
$$;

-- Quem enviou troca só a capa do próprio envio, e só por uma imagem da própria pasta
-- (profile-media/<id>/livros/…). Só enquanto o envio está em análise: depois de aprovado, a capa
-- é a que a curadoria viu (trocar depois publicaria uma imagem sem moderação).
create or replace function public.set_submission_cover(submission_id uuid, new_cover text)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  if new_cover is null or char_length(new_cover) > 300
     or not public.is_own_media_url(new_cover, auth.uid(), 'livros/[A-Za-z0-9-]+') then
    raise exception 'capa inválida';
  end if;
  update public.book_submissions
     set cover_url = new_cover
   where id = submission_id and user_id = auth.uid() and status = 'pending';
  if not found then
    raise exception 'envio não encontrado';
  end if;
end;
$$;

-- Fecha uma semana terminada: grava o pódio e paga os diamantes, uma vez só.
create or replace function public.close_reading_week(p_week date)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  -- Semana aberta ou já fechada: sai antes do lock (my_weekly_awards chama isto a cada abertura).
  if (p_week + 7)::timestamp + interval '1 hour' > (now() at time zone 'America/Sao_Paulo')
     or exists (select 1 from public.weekly_closings c where c.week_start = p_week) then
    return;
  end if;
  -- Exclusivo: espera os heartbeats em andamento (lock compartilhado) antes de fixar o pódio.
  perform pg_advisory_xact_lock(664332);
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

-- O app envia os segundos lidos por dia ({"2026-10-05": 900, ...}); vale o maior valor enviado.
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
      set seconds=least(43200,reading_days.seconds+excluded.seconds), updated_at=excluded.updated_at;
  end if;
  perform public.report_reading('{}'::jsonb);
  return credit;
end;
$$;

revoke execute on function public.is_own_media_url(text, uuid, text) from public, anon, authenticated;
revoke execute on function public.close_reading_week(date) from public, anon, authenticated;
revoke execute on function public.set_submission_cover(uuid, text) from public, anon;
grant execute on function public.set_submission_cover(uuid, text) to authenticated;
revoke execute on function public.report_reading(jsonb) from public, anon;
grant execute on function public.report_reading(jsonb) to authenticated;
revoke execute on function public.reading_heartbeat(boolean) from public, anon;
grant execute on function public.reading_heartbeat(boolean) to authenticated;
