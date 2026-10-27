-- 2.5 Livros mais lidos (story-verse-web docs/SPEC_ADMIN_DASHBOARD.md). Sai da estante:
-- reading_days não guarda o livro, então não há minutos por livro.
--
-- readers  = contas com o livro em `reading` ou `finished` e atividade no período: começou
--            (started_on) ou terminou (finished_on) no período, ou, se ainda está lendo, mexeu na
--            entrada (updated_at, que o progresso atualiza) no período. updated_at não vale para
--            `finished`: editar a nota de um livro lido há meses não é leitura.
-- finished = contas que terminaram o livro no período (finished_on).
-- Só livros do acervo (gb:, cm: e cat:, o catálogo curado). local: e os textos da pessoa
-- (resenha, notas) ficam de fora.
-- Título e autor vêm do catálogo quando o livro está lá; senão, o snapshot mais comum da estante.

drop function if exists public.admin_metrics_top_books(date, date, integer);

create function public.admin_metrics_top_books(p_from date, p_to date, p_limit integer)
returns table(book_key text, title text, author text, readers bigint, finished bigint)
language sql
security definer
set search_path = public
stable
as $$
  with active as (
    select e.book_key, e.user_id, e.title, e.author,
           e.finished_on between p_from and p_to as finished_in_range
      from public.library_entries e
     where e.deleted_at is null
       and e.book_key ~ '^(gb|cm|cat):'
       and e.source <> 'local'
       and e.status in ('reading', 'finished')
       and (e.started_on between p_from and p_to
            or e.finished_on between p_from and p_to
            or (e.status = 'reading'
                and e.updated_at >= (p_from::timestamp at time zone 'America/Sao_Paulo')
                and e.updated_at < ((p_to + 1)::timestamp at time zone 'America/Sao_Paulo')))
  ),
  ranked as (
    select a.book_key,
           mode() within group (order by a.title) as title,
           mode() within group (order by a.author) as author,
           count(distinct a.user_id)::bigint as readers,
           count(distinct a.user_id) filter (where a.finished_in_range)::bigint as finished
      from active a
     group by a.book_key
  )
  select r.book_key, coalesce(c.title, r.title), coalesce(c.author, r.author), r.readers, r.finished
    from ranked r
    left join public.catalog_books c on c.book_key = r.book_key
   order by r.readers desc, r.finished desc, r.book_key
   limit greatest(1, least(coalesce(p_limit, 10), 20))
$$;

revoke execute on function public.admin_metrics_top_books(date, date, integer) from public, anon, authenticated;
grant execute on function public.admin_metrics_top_books(date, date, integer) to storyverse_api;
