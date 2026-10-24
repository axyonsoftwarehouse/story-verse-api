-- Catálogo público curado. Os envios da comunidade continuam em book_submissions;
-- ao aprovar, uma linha correspondente é criada aqui com book_key = cm:<submission_id>.

create table if not exists public.catalog_books (
  id uuid primary key default gen_random_uuid(),
  book_key text not null unique,
  source text not null check (source in ('admin', 'community', 'import')),
  submission_id uuid unique references public.book_submissions (id) on delete set null,
  title text not null check (char_length(title) between 1 and 200),
  author text check (char_length(author) <= 200),
  genre text check (char_length(genre) <= 80),
  language text not null default 'pt' check (language in ('pt', 'en')),
  series text check (char_length(series) <= 200),
  series_index numeric check (series_index is null or series_index >= 0),
  description text check (char_length(description) <= 4000),
  page_count integer check (page_count is null or page_count >= 0),
  rights text not null check (rights in ('public_domain', 'own_work', 'free_license')),
  rights_note text check (char_length(rights_note) <= 500),
  storage_bucket text not null default 'catalog-books',
  text_path text not null,
  original_path text,
  original_content_type text,
  char_count integer check (char_count is null or char_count >= 0),
  checksum text,
  cover_url text check (cover_url is null or char_length(cover_url) <= 500),
  status text not null default 'draft' check (status in ('draft', 'published', 'hidden', 'archived')),
  featured boolean not null default false,
  sort_order integer not null default 0,
  duplicate_of uuid references public.catalog_books (id) on delete set null,
  published_at timestamptz,
  created_by uuid references auth.users (id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists catalog_books_status_idx on public.catalog_books (status, published_at desc);
create index if not exists catalog_books_genre_language_idx on public.catalog_books (genre, language);
create index if not exists catalog_books_featured_idx on public.catalog_books (featured, sort_order);
create index if not exists catalog_books_title_idx on public.catalog_books (lower(title));
create index if not exists catalog_books_author_idx on public.catalog_books (lower(author));
create index if not exists catalog_books_checksum_idx on public.catalog_books (checksum);
create index if not exists catalog_books_submission_idx on public.catalog_books (submission_id);

create table if not exists public.catalog_collections (
  id uuid primary key default gen_random_uuid(),
  slug text not null unique check (slug ~ '^[a-z0-9-]{2,80}$'),
  title text not null check (char_length(title) between 1 and 120),
  description text check (char_length(description) <= 1000),
  status text not null default 'draft' check (status in ('draft', 'published', 'hidden')),
  sort_order integer not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.catalog_collection_books (
  collection_id uuid not null references public.catalog_collections (id) on delete cascade,
  book_id uuid not null references public.catalog_books (id) on delete cascade,
  sort_order integer not null default 0,
  primary key (collection_id, book_id)
);

create index if not exists catalog_collection_books_book_idx
  on public.catalog_collection_books (book_id);

create table if not exists public.catalog_imports (
  id uuid primary key default gen_random_uuid(),
  created_by uuid references auth.users (id) on delete set null,
  status text not null default 'queued' check (status in ('queued', 'running', 'done', 'failed')),
  file_name text,
  total integer not null default 0,
  imported integer not null default 0,
  skipped integer not null default 0,
  error text,
  created_at timestamptz not null default now(),
  finished_at timestamptz
);

create table if not exists public.catalog_import_items (
  id uuid primary key default gen_random_uuid(),
  import_id uuid not null references public.catalog_imports (id) on delete cascade,
  line integer,
  file_name text,
  status text not null check (status in ('imported', 'skipped', 'error')),
  message text,
  catalog_book_id uuid references public.catalog_books (id) on delete set null,
  created_at timestamptz not null default now()
);

create index if not exists catalog_import_items_import_idx
  on public.catalog_import_items (import_id, line);

alter table public.catalog_books enable row level security;
alter table public.catalog_collections enable row level security;
alter table public.catalog_collection_books enable row level security;
alter table public.catalog_imports enable row level security;
alter table public.catalog_import_items enable row level security;

drop policy if exists "catalogo publicado" on public.catalog_books;
create policy "catalogo publicado" on public.catalog_books
  for select using (status = 'published' or public.can_moderate());

drop policy if exists "equipe cria catalogo" on public.catalog_books;
create policy "equipe cria catalogo" on public.catalog_books
  for insert with check (public.can_moderate());

drop policy if exists "equipe edita catalogo" on public.catalog_books;
create policy "equipe edita catalogo" on public.catalog_books
  for update using (public.can_moderate()) with check (public.can_moderate());

drop policy if exists "so superadmin apaga catalogo" on public.catalog_books;
create policy "so superadmin apaga catalogo" on public.catalog_books
  for delete using (public.has_role('superadmin'));

drop policy if exists "colecao publicada" on public.catalog_collections;
create policy "colecao publicada" on public.catalog_collections
  for select using (status = 'published' or public.can_moderate());

drop policy if exists "equipe gerencia colecoes" on public.catalog_collections;
create policy "equipe gerencia colecoes" on public.catalog_collections
  for all using (public.can_moderate()) with check (public.can_moderate());

drop policy if exists "livros da colecao publica" on public.catalog_collection_books;
create policy "livros da colecao publica" on public.catalog_collection_books
  for select using (
    exists (
      select 1 from public.catalog_collections c
       where c.id = collection_id
         and (c.status = 'published' or public.can_moderate())
    )
  );

drop policy if exists "equipe gerencia livros da colecao" on public.catalog_collection_books;
create policy "equipe gerencia livros da colecao" on public.catalog_collection_books
  for all using (public.can_moderate()) with check (public.can_moderate());

drop policy if exists "equipe ve importacoes" on public.catalog_imports;
create policy "equipe ve importacoes" on public.catalog_imports
  for all using (public.can_moderate()) with check (public.can_moderate());

drop policy if exists "equipe ve itens de importacao" on public.catalog_import_items;
create policy "equipe ve itens de importacao" on public.catalog_import_items
  for all using (public.can_moderate()) with check (public.can_moderate());

grant select on public.catalog_books, public.catalog_collections, public.catalog_collection_books
  to anon, authenticated;
grant insert, update, delete on public.catalog_books, public.catalog_collections,
  public.catalog_collection_books, public.catalog_imports, public.catalog_import_items
  to authenticated;

-- A estante aceita livros do catálogo novo (cat:<uuid>), mantendo gb:, cm: e local:.
alter table public.library_entries
  drop constraint if exists library_entries_book_key_check;
alter table public.library_entries
  add constraint library_entries_book_key_check
  check (book_key ~ '^(gb:[0-9]+|cm:[0-9a-fA-F-]{36}|cat:[0-9a-fA-F-]{36}|local:-?[0-9]+)$');

-- Migra os envios já aprovados. Idempotente; não apaga o envio.
insert into public.catalog_books
  (book_key, source, submission_id, title, author, language, rights, rights_note,
   storage_bucket, text_path, char_count, cover_url, status, published_at, created_by)
select 'cm:' || s.id,
       'community',
       s.id,
       s.title,
       s.author,
       s.language,
       s.rights,
       s.rights_note,
       'community-books',
       s.text_path,
       s.char_count,
       s.cover_url,
       'published',
       coalesce(s.reviewed_at, s.created_at),
       s.reviewed_by
  from public.book_submissions s
 where s.status = 'approved'
on conflict (book_key) do nothing;
