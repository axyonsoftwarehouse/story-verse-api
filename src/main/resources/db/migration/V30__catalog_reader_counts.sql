-- Quantas pessoas leram cada livro, para o contador público nos cards ("1,2 mil leram").
-- Sai da estante, como em admin_metrics_top_books (V28): conta quem tem o livro em `reading`,
-- `finished` ou com algum progresso (inclui quem abandonou no meio). Só a contagem por livro sai
-- daqui; quem leu nunca é exposto. local: e entradas apagadas ficam de fora.
-- security definer: a RLS de library_entries só mostra a estante da própria conta.

create index if not exists library_entries_book_key_idx
  on public.library_entries (book_key, user_id)
  where deleted_at is null;

drop function if exists public.catalog_reader_counts(text[]);

create function public.catalog_reader_counts(p_keys text[])
returns table(book_key text, readers bigint)
language sql
security definer
set search_path = public
stable
as $$
  select e.book_key, count(distinct e.user_id)::bigint as readers
    from public.library_entries e
   where e.book_key = any(p_keys[1:60])
     and e.deleted_at is null
     and e.book_key ~ '^(gb|cm|cat):'
     and e.source <> 'local'
     and (e.status in ('reading', 'finished') or coalesce(e.progress_percent, 0) > 0)
   group by e.book_key
$$;

revoke execute on function public.catalog_reader_counts(text[]) from public, anon, authenticated;
grant execute on function public.catalog_reader_counts(text[]) to storyverse_api;
