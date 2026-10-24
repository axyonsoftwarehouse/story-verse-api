-- A estante passa a aceitar livros do catálogo curado (cat:<uuid>), que não são envios da
-- comunidade e precisam de origem própria. As chaves antigas continuam válidas (V17 já ampliou o
-- CHECK de book_key).

alter table public.library_entries
  drop constraint if exists library_entries_source_check;

alter table public.library_entries
  add constraint library_entries_source_check
  check (source in ('gutenberg', 'community', 'catalog', 'local'));
