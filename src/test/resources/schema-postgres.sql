-- Storyverse — esquema completo para PostgreSQL (VPS, sem Supabase).
--
-- Junta num arquivo só o que estava em supabase/*.sql (community-books, diamonds, reading-state,
-- weekly-ranking, profile-media, user-books), adaptado para um Postgres comum:
--
--   * auth.users  — antes era do Supabase Auth; aqui é uma tabela mínima com as colunas que o
--                   banco usa (id, e-mail, senha, metadados do perfil). O login passa a ser do backend.
--   * auth.uid()  — antes vinha do JWT do Supabase; aqui lê o id da pessoa da sessão:
--                     begin; set local app.user_id = '<uuid>'; ...; commit;
--                   (também aceita request.jwt.claims/sub, então funciona com PostgREST).
--   * anon / authenticated — papéis sem login, criados aqui para os grants e as policies
--                   continuarem valendo. O backend conecta com um usuário próprio e faz
--                   "set local role authenticated" (ou anon) por transação.
--   * Storage     — os buckets profile-media, user-books e community-books NÃO são tabelas:
--                   os arquivos vão para disco/MinIO/S3 e as regras de acesso ficam no backend.
--                   Só a coluna book_submissions.text_path guarda o caminho do arquivo.
--
-- Requer PostgreSQL 13+ (gen_random_uuid nativo). Pode rodar de novo sem problema.
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f database/schema.sql

-- ===========================================================================
-- Papéis e autenticação
-- ===========================================================================
do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'anon') then
    create role anon nologin;
  end if;
  if not exists (select 1 from pg_roles where rolname = 'authenticated') then
    create role authenticated nologin;
  end if;
end
$$;

create schema if not exists auth;
grant usage on schema auth to anon, authenticated;
grant usage on schema public to anon, authenticated;

create table if not exists auth.users (
  id uuid primary key default gen_random_uuid(),
  email text not null,
  encrypted_password text,
  email_confirmed_at timestamptz,
  -- Perfil (name, avatar, photo), como o app gravava no Supabase.
  raw_user_meta_data jsonb not null default '{}'::jsonb,
  last_sign_in_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create unique index if not exists users_email_key on auth.users (lower(email));

-- Id da pessoa logada nesta transação (null = anônimo).
create or replace function auth.uid()
returns uuid
language sql
stable
as $$
  select nullif(coalesce(
    current_setting('app.user_id', true),
    nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'sub',
    current_setting('request.jwt.claim.sub', true)
  ), '')::uuid;
$$;
grant execute on function auth.uid() to anon, authenticated;

-- ===========================================================================
-- Tabelas
-- ===========================================================================

-- Admins (aprovam os livros da comunidade).
create table if not exists public.admins (
  user_id uuid primary key references auth.users (id) on delete cascade
);

-- Acervo da comunidade: livros sugeridos pelos leitores, aprovados pelo admin.
create table if not exists public.book_submissions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  submitter_name text check (char_length(submitter_name) <= 60),
  title text not null check (char_length(title) between 1 and 120),
  author text check (char_length(author) <= 120),
  language text not null default 'pt' check (language in ('pt', 'en')),
  rights text not null check (rights in ('public_domain', 'own_work', 'free_license')),
  rights_note text check (char_length(rights_note) <= 500),
  characters text check (char_length(characters) <= 600),
  text_path text not null,
  char_count integer,
  status text not null default 'pending' check (status in ('pending', 'approved', 'rejected')),
  review_note text check (char_length(review_note) <= 500),
  reviewed_at timestamptz,
  cover_url text check (char_length(cover_url) <= 300),
  created_at timestamptz not null default now()
);
create index if not exists book_submissions_status_idx on public.book_submissions (status, reviewed_at desc);

-- Dados de leitura de cada conta (progresso, marcações, conversas, preferências...) em JSON.
create table if not exists public.reading_state (
  user_id uuid primary key default auth.uid() references auth.users (id) on delete cascade,
  data jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now()
);

-- Diamantes: carteira, extrato e dias salvos com o salva-ofensiva.
create table if not exists public.diamond_wallets (
  user_id uuid primary key references auth.users (id) on delete cascade,
  balance integer not null default 0 check (balance >= 0),
  updated_at timestamptz not null default now()
);

create table if not exists public.diamond_ledger (
  id bigint generated always as identity primary key,
  user_id uuid not null references auth.users (id) on delete cascade,
  amount integer not null check (amount <> 0),
  balance_after integer not null check (balance_after >= 0),
  reason text not null,
  ref text not null,
  created_at timestamptz not null default now(),
  unique (user_id, reason, ref)
);
create index if not exists diamond_ledger_user_recent on public.diamond_ledger (user_id, created_at desc);
alter table public.diamond_ledger drop constraint if exists diamond_ledger_reason_check;
alter table public.diamond_ledger add constraint diamond_ledger_reason_check
  check (reason in ('ranking', 'reading_goal', 'streak_freeze', 'daily_spin', 'purchase', 'adjustment'));

create table if not exists public.streak_freezes (
  user_id uuid not null references auth.users (id) on delete cascade,
  day date not null,
  created_at timestamptz not null default now(),
  primary key (user_id, day)
);

-- Ranking semanal: segundos lidos por dia, perfil exibido, semanas fechadas e pódios.
create table if not exists public.reading_days (
  user_id uuid not null references auth.users (id) on delete cascade,
  day date not null,
  seconds integer not null default 0 check (seconds between 0 and 43200),
  updated_at timestamptz not null default now(),
  primary key (user_id, day)
);

create table if not exists public.ranking_profiles (
  user_id uuid primary key references auth.users (id) on delete cascade,
  name text not null,
  avatar text,
  photo text,
  hidden boolean not null default false,
  updated_at timestamptz not null default now()
);

create table if not exists public.weekly_closings (
  week_start date primary key,
  closed_at timestamptz not null default now()
);

create table if not exists public.weekly_awards (
  week_start date not null references public.weekly_closings (week_start) on delete cascade,
  user_id uuid not null references auth.users (id) on delete cascade,
  place smallint not null check (place between 1 and 3),
  minutes integer not null,
  diamonds integer not null,
  seen_at timestamptz,
  primary key (week_start, user_id)
);

-- ===========================================================================
-- Acesso às tabelas (RLS)
-- ===========================================================================
alter table public.admins enable row level security;
alter table public.book_submissions enable row level security;
alter table public.reading_state enable row level security;
alter table public.diamond_wallets enable row level security;
alter table public.diamond_ledger enable row level security;
alter table public.streak_freezes enable row level security;
alter table public.reading_days enable row level security;
alter table public.ranking_profiles enable row level security;
alter table public.weekly_closings enable row level security;
alter table public.weekly_awards enable row level security;

grant select on public.admins to authenticated;
grant select on public.book_submissions to anon, authenticated;
grant insert, update, delete on public.book_submissions to authenticated;
grant select, insert, update on public.reading_state to authenticated;
-- Diamantes e ranking: sem grants, só as funções (security definer) mexem nessas tabelas.
revoke all on public.diamond_wallets, public.diamond_ledger, public.streak_freezes,
              public.reading_days, public.ranking_profiles, public.weekly_closings, public.weekly_awards
  from anon, authenticated;

create or replace function public.is_admin()
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists (select 1 from public.admins where user_id = auth.uid());
$$;
grant execute on function public.is_admin() to anon, authenticated;

drop policy if exists "admin vê a si mesmo" on public.admins;
create policy "admin vê a si mesmo" on public.admins
  for select using (auth.uid() = user_id);

drop policy if exists "ver envios" on public.book_submissions;
create policy "ver envios" on public.book_submissions
  for select using (status = 'approved' or auth.uid() = user_id or public.is_admin());

drop policy if exists "enviar livro" on public.book_submissions;
create policy "enviar livro" on public.book_submissions
  for insert to authenticated
  with check (auth.uid() = user_id and status = 'pending' and reviewed_at is null and review_note is null);

drop policy if exists "admin revisa" on public.book_submissions;
create policy "admin revisa" on public.book_submissions
  for update using (public.is_admin()) with check (public.is_admin());

drop policy if exists "remover envio" on public.book_submissions;
create policy "remover envio" on public.book_submissions
  for delete using (public.is_admin() or (auth.uid() = user_id and status = 'pending'));

drop policy if exists "ler os proprios dados" on public.reading_state;
create policy "ler os proprios dados" on public.reading_state
  for select to authenticated using (auth.uid() = user_id);

drop policy if exists "criar os proprios dados" on public.reading_state;
create policy "criar os proprios dados" on public.reading_state
  for insert to authenticated with check (auth.uid() = user_id);

drop policy if exists "atualizar os proprios dados" on public.reading_state;
create policy "atualizar os proprios dados" on public.reading_state
  for update to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- ===========================================================================
-- Funções: acervo da comunidade
-- ===========================================================================

-- Quem enviou troca só a capa do próprio envio, e só por uma imagem da própria pasta
-- (profile-media/<id>/livros/…). Antes exigia o prefixo /storage/v1/object/public/ do Supabase;
-- aqui aceita qualquer domínio, desde que o caminho seja o da pessoa.
create or replace function public.set_submission_cover(submission_id uuid, new_cover text)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  if new_cover is null or char_length(new_cover) > 300
     or position('/profile-media/' || auth.uid()::text || '/livros/' in new_cover) = 0 then
    raise exception 'capa inválida';
  end if;
  update public.book_submissions
     set cover_url = new_cover
   where id = submission_id and user_id = auth.uid() and status <> 'rejected';
  if not found then
    raise exception 'envio não encontrado';
  end if;
end;
$$;

-- ===========================================================================
-- Funções: diamantes
-- ===========================================================================

create or replace function public.diamond_rules()
returns table (freeze_price integer, freezes_per_week integer, goal_minutes integer, goal_reward integer)
language sql
immutable
as $$
  select 10, 2, 10, 2;
$$;

-- Lança diamantes (uso interno). Devolve o saldo novo, ou null se o lançamento já existia.
create or replace function public.apply_diamonds(p_user uuid, p_amount integer, p_reason text, p_ref text)
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
  bal integer;
begin
  if p_amount is null or p_amount = 0 then
    raise exception 'Lançamento sem valor.';
  end if;
  insert into public.diamond_wallets (user_id) values (p_user) on conflict do nothing;
  select w.balance into bal from public.diamond_wallets w where w.user_id = p_user for update;
  if exists (select 1 from public.diamond_ledger l where l.user_id = p_user and l.reason = p_reason and l.ref = p_ref) then
    return null;
  end if;
  if bal + p_amount < 0 then
    raise exception 'Diamantes insuficientes.';
  end if;
  bal := bal + p_amount;
  update public.diamond_wallets set balance = bal, updated_at = now() where user_id = p_user;
  insert into public.diamond_ledger (user_id, amount, balance_after, reason, ref)
  values (p_user, p_amount, bal, p_reason, p_ref);
  return bal;
end;
$$;

create or replace function public.my_wallet()
returns table (balance integer, freezes_this_week integer, freeze_price integer, freezes_per_week integer, goal_minutes integer, goal_reward integer)
language sql
stable
security definer
set search_path = public
as $$
  select
    coalesce((select w.balance from public.diamond_wallets w where w.user_id = auth.uid()), 0),
    (select count(*)::integer from public.streak_freezes f
      where f.user_id = auth.uid()
        and f.day >= (date_trunc('week', now() at time zone 'America/Sao_Paulo'))::date),
    r.freeze_price, r.freezes_per_week, r.goal_minutes, r.goal_reward
  from public.diamond_rules() r
  where auth.uid() is not null;
$$;

create or replace function public.my_diamond_history(p_limit integer default 30)
returns table (amount integer, balance_after integer, reason text, ref text, created_at timestamptz)
language sql
stable
security definer
set search_path = public
as $$
  select l.amount, l.balance_after, l.reason, l.ref, l.created_at
  from public.diamond_ledger l
  where l.user_id = auth.uid()
  order by l.created_at desc, l.id desc
  limit greatest(least(p_limit, 100), 1);
$$;

create or replace function public.my_streak_freezes()
returns setof date
language sql
stable
security definer
set search_path = public
as $$
  select f.day from public.streak_freezes f
  where f.user_id = auth.uid() and f.day >= current_date - 400
  order by f.day;
$$;

-- Salva-ofensiva: gasta diamantes para salvar dias sem leitura (até 3 dias atrás). Tudo ou nada.
create or replace function public.use_streak_freeze(p_days date[])
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
  uid uuid := auth.uid();
  rules record;
  today date := (now() at time zone 'America/Sao_Paulo')::date;
  new_days date[];
  d date;
  bal integer;
begin
  if uid is null then
    raise exception 'Entre na sua conta para usar o salva-ofensiva.';
  end if;
  select * into rules from public.diamond_rules();
  if coalesce(cardinality(p_days), 0) = 0 or cardinality(p_days) > rules.freezes_per_week then
    raise exception 'Escolha de 1 a % dias para salvar.', rules.freezes_per_week;
  end if;
  if exists (select 1 from unnest(p_days) x where x is null or x > today or x < today - 3) then
    raise exception 'Esse dia não pode mais ser salvo.';
  end if;
  if exists (select 1 from public.reading_days r where r.user_id = uid and r.day = any (p_days) and r.seconds >= 60) then
    raise exception 'Você leu nesse dia: a sequência não precisa ser salva.';
  end if;

  insert into public.diamond_wallets (user_id) values (uid) on conflict do nothing;
  select w.balance into bal from public.diamond_wallets w where w.user_id = uid for update;

  select coalesce(array_agg(distinct x order by x), '{}') into new_days
  from unnest(p_days) x
  where not exists (select 1 from public.streak_freezes f where f.user_id = uid and f.day = x);
  if cardinality(new_days) = 0 then
    return bal;
  end if;

  if exists (
    select 1
    from (select x - (extract(isodow from x)::integer - 1) as wk, count(*) as n from unnest(new_days) x group by 1) w
    where w.n + (select count(*) from public.streak_freezes f
                 where f.user_id = uid and f.day >= w.wk and f.day < w.wk + 7) > rules.freezes_per_week
  ) then
    raise exception 'Você já usou os % salva-ofensivas desta semana.', rules.freezes_per_week;
  end if;

  foreach d in array new_days loop
    bal := coalesce(public.apply_diamonds(uid, -rules.freeze_price, 'streak_freeze', d::text), bal);
    insert into public.streak_freezes (user_id, day) values (uid, d);
  end loop;
  return bal;
end;
$$;

-- Recompensa por leitura: cada dia (da última semana) com 10+ minutos dá 2 diamantes, uma vez só.
create or replace function public.claim_reading_rewards()
returns table (day date, diamonds integer, is_new boolean)
language plpgsql
security definer
set search_path = public
as $$
#variable_conflict use_column
declare
  uid uuid := auth.uid();
  rules record;
  today date := (now() at time zone 'America/Sao_Paulo')::date;
  r record;
  granted integer;
begin
  if uid is null then
    return;
  end if;
  select * into rules from public.diamond_rules();
  for r in
    select d.day from public.reading_days d
    where d.user_id = uid and d.day between today - 7 and today + 1 and d.seconds >= rules.goal_minutes * 60
    order by d.day
  loop
    granted := public.apply_diamonds(uid, rules.goal_reward, 'reading_goal', r.day::text);
    day := r.day;
    diamonds := rules.goal_reward;
    is_new := granted is not null;
    return next;
  end loop;
end;
$$;

-- Roleta diária: um giro por dia (horário de Brasília), prêmio sorteado no banco.
create or replace function public.daily_spin_status()
returns table (spun_today boolean, prize integer)
language sql
stable
security definer
set search_path = public
as $$
  select l.amount is not null, l.amount
  from (select auth.uid() as uid) me
  left join public.diamond_ledger l
    on l.user_id = me.uid and l.reason = 'daily_spin'
   and l.ref = ((now() at time zone 'America/Sao_Paulo')::date)::text
  where me.uid is not null;
$$;

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
    when r < 0.35 then 1
    when r < 0.65 then 2
    when r < 0.83 then 3
    when r < 0.95 then 5
    when r < 0.99 then 10
    else 20
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

-- ===========================================================================
-- Funções: ranking semanal
-- ===========================================================================

create or replace function public.ranking_week_start(p_offset integer default 0)
returns date
language sql
stable
set search_path = public
as $$
  select (date_trunc('week', now() at time zone 'America/Sao_Paulo'))::date + 7 * p_offset;
$$;

create or replace function public.ranking_for_week(p_week date, p_limit integer, p_me uuid)
returns table (place integer, user_id uuid, name text, avatar text, photo text, minutes integer, is_me boolean)
language sql
stable
security definer
set search_path = public
as $$
  with totals as (
    select d.user_id, (sum(d.seconds) / 60)::integer as minutes, max(d.updated_at) as last_update
    from public.reading_days d
    where d.day >= p_week and d.day < p_week + 7
    group by d.user_id
  ),
  ranked as (
    select t.user_id, t.minutes,
           (row_number() over (order by t.minutes desc, t.last_update asc, t.user_id))::integer as place
    from totals t
    join public.ranking_profiles p on p.user_id = t.user_id
    where t.minutes >= 1 and not p.hidden
  )
  select r.place, r.user_id, p.name, p.avatar, p.photo, r.minutes, r.user_id = p_me
  from ranked r
  join public.ranking_profiles p on p.user_id = r.user_id
  where r.place <= p_limit or r.user_id = p_me
  order by r.place;
$$;

-- Fecha uma semana terminada: grava o pódio e paga os diamantes, uma vez só.
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

create or replace function public.weekly_ranking(p_offset integer default 0, p_limit integer default 50)
returns table (place integer, user_id uuid, name text, avatar text, photo text, minutes integer, is_me boolean)
language sql
stable
security definer
set search_path = public
as $$
  select * from public.ranking_for_week(
    public.ranking_week_start(greatest(least(p_offset, 0), -1)),
    greatest(least(p_limit, 100), 3),
    auth.uid()
  );
$$;

create or replace function public.my_ranking_profile()
returns table (hidden boolean, diamonds integer)
language sql
stable
security definer
set search_path = public
as $$
  select coalesce(p.hidden, false), coalesce(w.balance, 0)
  from (select auth.uid() as uid) me
  left join public.ranking_profiles p on p.user_id = me.uid
  left join public.diamond_wallets w on w.user_id = me.uid
  where me.uid is not null;
$$;

create or replace function public.set_ranking_hidden(p_hidden boolean)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  if auth.uid() is null then
    raise exception 'Entre na sua conta para mudar isso.';
  end if;
  update public.ranking_profiles set hidden = p_hidden, updated_at = now() where user_id = auth.uid();
  if not found then
    insert into public.ranking_profiles (user_id, name, hidden) values (auth.uid(), 'Leitor', p_hidden)
    on conflict (user_id) do update set hidden = excluded.hidden;
  end if;
end;
$$;

create or replace function public.my_weekly_awards()
returns table (week_start date, place smallint, minutes integer, diamonds integer)
language plpgsql
security definer
set search_path = public
as $$
#variable_conflict use_column
begin
  if auth.uid() is null then
    return;
  end if;
  perform public.close_reading_week(public.ranking_week_start(-1));
  return query
    select a.week_start, a.place, a.minutes, a.diamonds
    from public.weekly_awards a
    where a.user_id = auth.uid() and a.seen_at is null
    order by a.week_start;
end;
$$;

create or replace function public.mark_awards_seen()
returns void
language sql
security definer
set search_path = public
as $$
  update public.weekly_awards set seen_at = now() where user_id = auth.uid() and seen_at is null;
$$;

-- ===========================================================================
-- Permissões das funções: internas fechadas; as demais só para quem entrou.
-- ===========================================================================
revoke execute on function public.apply_diamonds(uuid, integer, text, text) from public, anon, authenticated;
revoke execute on function public.ranking_for_week(date, integer, uuid) from public, anon, authenticated;
revoke execute on function public.close_reading_week(date) from public, anon, authenticated;

revoke execute on function public.set_submission_cover(uuid, text) from public, anon;
revoke execute on function public.my_wallet() from public, anon;
revoke execute on function public.my_diamond_history(integer) from public, anon;
revoke execute on function public.my_streak_freezes() from public, anon;
revoke execute on function public.use_streak_freeze(date[]) from public, anon;
revoke execute on function public.claim_reading_rewards() from public, anon;
revoke execute on function public.daily_spin_status() from public, anon;
revoke execute on function public.spin_daily_wheel() from public, anon;
revoke execute on function public.report_reading(jsonb) from public, anon;
revoke execute on function public.weekly_ranking(integer, integer) from public, anon;
revoke execute on function public.my_ranking_profile() from public, anon;
revoke execute on function public.set_ranking_hidden(boolean) from public, anon;
revoke execute on function public.my_weekly_awards() from public, anon;
revoke execute on function public.mark_awards_seen() from public, anon;

grant execute on function public.set_submission_cover(uuid, text) to authenticated;
grant execute on function public.diamond_rules() to authenticated;
grant execute on function public.my_wallet() to authenticated;
grant execute on function public.my_diamond_history(integer) to authenticated;
grant execute on function public.my_streak_freezes() to authenticated;
grant execute on function public.use_streak_freeze(date[]) to authenticated;
grant execute on function public.claim_reading_rewards() to authenticated;
grant execute on function public.daily_spin_status() to authenticated;
grant execute on function public.spin_daily_wheel() to authenticated;
grant execute on function public.ranking_week_start(integer) to authenticated;
grant execute on function public.report_reading(jsonb) to authenticated;
grant execute on function public.weekly_ranking(integer, integer) to authenticated;
grant execute on function public.my_ranking_profile() to authenticated;
grant execute on function public.set_ranking_hidden(boolean) to authenticated;
grant execute on function public.my_weekly_awards() to authenticated;
grant execute on function public.mark_awards_seen() to authenticated;

-- ===========================================================================
-- Admin (só tem efeito depois que a conta existir em auth.users; rode de novo após importar).
-- O Gmail ignora pontos no endereço: aceita as duas grafias.
-- ===========================================================================
insert into public.admins (user_id)
select id from auth.users
where lower(replace(split_part(email, '@', 1), '.', '')) = 'businesscodeplus'
  and lower(split_part(email, '@', 2)) = 'gmail.com'
on conflict do nothing;

-- Confere: lista as tabelas criadas.
select table_schema, table_name
from information_schema.tables
where table_schema in ('auth', 'public') and table_type = 'BASE TABLE'
order by 1, 2;

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
