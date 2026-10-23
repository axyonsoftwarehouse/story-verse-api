-- Estante (biblioteca pessoal): a relação do leitor com cada livro. Uma linha por conta + livro.
-- Spec: story-verse-web docs/SPEC_BIBLIOTECA_PESSOAL.md (seção 7). Liberada pela feature `library`.
create table if not exists public.library_entries (
  user_id uuid not null references auth.users (id) on delete cascade,
  book_key text not null check (book_key ~ '^(gb:[0-9]+|cm:[0-9a-fA-F-]{36}|local:-?[0-9]+)$'),
  source text not null check (source in ('gutenberg', 'community', 'local')),
  status text not null default 'want_to_read'
    check (status in ('want_to_read', 'reading', 'finished', 'abandoned')),
  rating numeric(2,1) check (rating is null or (rating between 0.5 and 5 and rating * 2 = floor(rating * 2))),
  review text check (review is null or char_length(review) <= 4000),
  started_on date,
  finished_on date,
  dnf_reason text check (dnf_reason is null or dnf_reason in ('slow', 'not_for_me', 'difficult', 'other')),
  dnf_note text check (dnf_note is null or char_length(dnf_note) <= 500),
  progress_percent smallint check (progress_percent is null or progress_percent between 0 and 100),
  progress_label text check (progress_label is null or char_length(progress_label) <= 200),
  -- Snapshot de exibição; a fonte canônica de metadados vem na Fase 2.
  title text not null check (char_length(title) between 1 and 300),
  author text check (author is null or char_length(author) <= 300),
  cover_url text check (cover_url is null or char_length(cover_url) <= 500),
  series text check (series is null or char_length(series) <= 200),
  series_index numeric(6,2),
  genre text check (genre is null or char_length(genre) <= 80),
  language text check (language is null or language in ('pt', 'en')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  version integer not null default 1,
  primary key (user_id, book_key)
);

-- Inclui apagadas: o sync entre aparelhos lê as remoções por updated_at.
create index if not exists library_entries_user_updated_idx
  on public.library_entries (user_id, updated_at desc, book_key);

create index if not exists library_entries_user_status_idx
  on public.library_entries (user_id, status, updated_at desc)
  where deleted_at is null;

create index if not exists library_entries_user_author_idx
  on public.library_entries (user_id, lower(author))
  where deleted_at is null;

create index if not exists library_entries_user_series_idx
  on public.library_entries (user_id, lower(series), series_index)
  where deleted_at is null;

alter table public.library_entries enable row level security;

-- Sem filtro de deleted_at: a API esconde as apagadas e só as mostra ao sync.
drop policy if exists "cada um ve sua estante" on public.library_entries;
create policy "cada um ve sua estante" on public.library_entries
  for select using (user_id = auth.uid());

drop policy if exists "cada um cria sua estante" on public.library_entries;
create policy "cada um cria sua estante" on public.library_entries
  for insert with check (user_id = auth.uid());

drop policy if exists "cada um atualiza sua estante" on public.library_entries;
create policy "cada um atualiza sua estante" on public.library_entries
  for update using (user_id = auth.uid()) with check (user_id = auth.uid());

drop policy if exists "cada um apaga sua estante" on public.library_entries;
create policy "cada um apaga sua estante" on public.library_entries
  for delete using (user_id = auth.uid());

grant select, insert, update, delete on public.library_entries to authenticated;
