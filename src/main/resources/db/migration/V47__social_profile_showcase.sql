-- Perfil público do leitor (rede social): o que os outros veem ao abrir o perfil de alguém.
--  * Vitrine: as conquistas são calculadas no app de cada pessoa, então o próprio app publica as
--    desbloqueadas aqui (é enfeite de perfil; não vale prêmio nenhum).
--  * Leituras e presentes recebidos: lidos por funções que respeitam a escolha da pessoa de
--    esconder as leituras do perfil. Livros importados do aparelho (local:) nunca aparecem.

create table if not exists public.profile_showcase (
  user_id uuid primary key references auth.users (id) on delete cascade,
  show_reading boolean not null default true,
  badges jsonb not null default '[]'::jsonb check (jsonb_typeof(badges) = 'array' and jsonb_array_length(badges) <= 60),
  badges_total integer not null default 0 check (badges_total between 0 and 200),
  stats jsonb not null default '{}'::jsonb check (jsonb_typeof(stats) = 'object'),
  updated_at timestamptz not null default now()
);

alter table public.profile_showcase enable row level security;
revoke all on public.profile_showcase from public, anon;
grant select, insert, update on public.profile_showcase to authenticated;

drop policy if exists "contas veem vitrines" on public.profile_showcase;
create policy "contas veem vitrines" on public.profile_showcase
  for select using (auth.uid() is not null);
drop policy if exists "cada um cria sua vitrine" on public.profile_showcase;
create policy "cada um cria sua vitrine" on public.profile_showcase
  for insert with check (user_id = auth.uid());
drop policy if exists "cada um atualiza sua vitrine" on public.profile_showcase;
create policy "cada um atualiza sua vitrine" on public.profile_showcase
  for update using (user_id = auth.uid()) with check (user_id = auth.uid());

-- A conta logada pode ver as leituras de p_user? (ela mesma sempre; os outros, se não escondeu)
create or replace function public.social_reading_visible(p_user uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select auth.uid() is not null
     and (p_user = auth.uid()
          or coalesce((select s.show_reading from public.profile_showcase s where s.user_id = p_user), true));
$$;

-- Lendo agora e terminados (sem "quero ler", abandonados e importados do aparelho).
create or replace function public.social_profile_books(p_user uuid, p_limit integer, p_offset integer)
returns table (book_key text, title text, author text, cover_url text, status text, rating numeric,
               progress_percent smallint, finished_on date, updated_at timestamptz)
language sql
stable
security definer
set search_path = public
as $$
  select e.book_key, e.title, e.author, e.cover_url, e.status, e.rating, e.progress_percent, e.finished_on, e.updated_at
    from public.library_entries e
   where e.user_id = p_user and e.deleted_at is null
     and e.status in ('reading', 'finished')
     and e.book_key not like 'local:%'
     and public.social_reading_visible(p_user)
   order by case e.status when 'reading' then 0 else 1 end, e.finished_on desc nulls last, e.updated_at desc, e.book_key
   limit greatest(1, least(coalesce(p_limit, 12), 60))
  offset greatest(0, coalesce(p_offset, 0));
$$;

create or replace function public.social_profile_reading_counts(p_user uuid)
returns table (reading integer, finished integer)
language sql
stable
security definer
set search_path = public
as $$
  select count(*) filter (where e.status = 'reading')::integer,
         count(*) filter (where e.status = 'finished')::integer
    from public.library_entries e
   where e.user_id = p_user and e.deleted_at is null
     and e.book_key not like 'local:%'
     and public.social_reading_visible(p_user);
$$;

-- Presentes recebidos, agrupados por item (só o que foi recebido; quem mandou não aparece).
create or replace function public.social_profile_gifts(p_user uuid)
returns table (gift_id text, name text, icon text, rarity text, total integer)
language sql
stable
security definer
set search_path = public
as $$
  select c.id, c.name, c.icon, c.rarity, count(*)::integer
    from public.user_gifts g
    join public.gift_catalog c on c.id = g.gift_id
   where g.to_user = p_user
     and public.social_reading_visible(p_user)
   group by c.id, c.name, c.icon, c.rarity, c.price
   order by c.price desc, c.id;
$$;

revoke execute on function public.social_reading_visible(uuid) from public, anon;
revoke execute on function public.social_profile_books(uuid, integer, integer) from public, anon;
revoke execute on function public.social_profile_reading_counts(uuid) from public, anon;
revoke execute on function public.social_profile_gifts(uuid) from public, anon;
grant execute on function public.social_reading_visible(uuid) to authenticated;
grant execute on function public.social_profile_books(uuid, integer, integer) to authenticated;
grant execute on function public.social_profile_reading_counts(uuid) to authenticated;
grant execute on function public.social_profile_gifts(uuid) to authenticated;

-- Sininho: "Limpar tudo" apaga os avisos da própria conta.
grant delete on public.user_notifications to authenticated;
drop policy if exists "cada um limpa seus avisos" on public.user_notifications;
create policy "cada um limpa seus avisos" on public.user_notifications
  for delete using (user_id = auth.uid());
