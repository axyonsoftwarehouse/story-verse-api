-- "Lendo agora" no perfil público só mostra livro com progresso de verdade.
-- Abrir a primeira página grava status reading com 0%: isso não é leitura em andamento.

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
     and e.book_key not like 'local:%'
     and public.social_reading_visible(p_user)
     and (
       e.status = 'finished'
       or (e.status = 'reading' and coalesce(e.progress_percent, 0) >= 1)
     )
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
  select count(*) filter (where e.status = 'reading' and coalesce(e.progress_percent, 0) >= 1)::integer,
         count(*) filter (where e.status = 'finished')::integer
    from public.library_entries e
   where e.user_id = p_user and e.deleted_at is null
     and e.book_key not like 'local:%'
     and public.social_reading_visible(p_user);
$$;
