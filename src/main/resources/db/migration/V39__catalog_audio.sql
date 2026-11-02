-- Audiolivros: uma edição em áudio por livro, com capítulos mapeados para o texto.
-- O arquivo em si fica no R2; aqui ficam só metadados e caminhos privados.

create table if not exists public.catalog_book_audio (
  id uuid primary key default gen_random_uuid(),
  book_id uuid not null references public.catalog_books (id) on delete cascade,
  language text not null default 'pt' check (language in ('pt', 'en')),
  narrator text check (narrator is null or char_length(narrator) <= 200),
  source text not null default 'librivox' check (source in ('librivox', 'admin', 'partner')),
  source_url text check (source_url is null or char_length(source_url) <= 500),
  rights text not null default 'public_domain'
    check (rights in ('public_domain', 'own_work', 'free_license')),
  rights_note text check (rights_note is null or char_length(rights_note) <= 500),
  license_note text check (license_note is null or char_length(license_note) <= 500),
  -- 'pending' bloqueia a publicação até a curadoria decidir se há texto mapeado ou não.
  text_mapping text not null default 'pending'
    check (text_mapping in ('pending', 'mapped', 'none')),
  total_duration_seconds integer not null default 0
    check (total_duration_seconds >= 0),
  chapter_count integer not null default 0 check (chapter_count >= 0),
  cover_url text check (cover_url is null or char_length(cover_url) <= 500),
  status text not null default 'draft'
    check (status in ('draft', 'processing', 'published', 'hidden', 'failed')),
  published_at timestamptz,
  created_by uuid references auth.users (id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists catalog_book_audio_book_idx
  on public.catalog_book_audio (book_id, status);
create index if not exists catalog_book_audio_status_idx
  on public.catalog_book_audio (status, published_at desc);

create table if not exists public.catalog_audio_chapters (
  id uuid primary key default gen_random_uuid(),
  audio_id uuid not null references public.catalog_book_audio (id) on delete cascade,
  chapter_index integer not null check (chapter_index > 0),
  title text check (title is null or char_length(title) <= 200),
  audio_path text not null check (char_length(audio_path) between 1 and 1000),
  mime_type text not null default 'audio/mpeg' check (char_length(mime_type) <= 100),
  duration_seconds integer not null default 0 check (duration_seconds >= 0),
  start_seconds integer not null default 0 check (start_seconds >= 0),
  end_seconds integer check (end_seconds is null or end_seconds >= start_seconds),
  size_bytes bigint check (size_bytes is null or size_bytes >= 0),
  checksum text,
  text_chapter_index integer check (text_chapter_index is null or text_chapter_index > 0),
  text_chapter_end_index integer
    check (text_chapter_end_index is null or text_chapter_end_index >= text_chapter_index),
  status text not null default 'ready'
    check (status in ('processing', 'ready', 'failed')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (audio_id, chapter_index)
);

create index if not exists catalog_audio_chapters_audio_idx
  on public.catalog_audio_chapters (audio_id, chapter_index);

-- Retomada por conta. A API pública de reprodução usa esta tabela no próximo ciclo.
create table if not exists public.audio_progress (
  user_id uuid not null references auth.users (id) on delete cascade,
  audio_id uuid not null references public.catalog_book_audio (id) on delete cascade,
  chapter_index integer not null default 1 check (chapter_index > 0),
  position_seconds integer not null default 0 check (position_seconds >= 0),
  playback_rate numeric(3,2) not null default 1.0
    check (playback_rate between 0.5 and 2.0),
  completed boolean not null default false,
  updated_at timestamptz not null default now(),
  primary key (user_id, audio_id)
);

alter table public.catalog_book_audio enable row level security;
alter table public.catalog_audio_chapters enable row level security;
alter table public.audio_progress enable row level security;

drop policy if exists "audio publicado" on public.catalog_book_audio;
create policy "audio publicado" on public.catalog_book_audio
  for select using (status = 'published' or public.can_moderate());

drop policy if exists "equipe gerencia audio" on public.catalog_book_audio;
create policy "equipe gerencia audio" on public.catalog_book_audio
  for insert with check (public.can_moderate());

drop policy if exists "equipe edita audio" on public.catalog_book_audio;
create policy "equipe edita audio" on public.catalog_book_audio
  for update using (public.can_moderate()) with check (public.can_moderate());

drop policy if exists "so superadmin apaga audio" on public.catalog_book_audio;
create policy "so superadmin apaga audio" on public.catalog_book_audio
  for delete using (public.has_role('superadmin'));

drop policy if exists "capitulos de audio publicados" on public.catalog_audio_chapters;
create policy "capitulos de audio publicados" on public.catalog_audio_chapters
  for select using (
    public.can_moderate()
    or exists (
      select 1 from public.catalog_book_audio a
       where a.id = audio_id and a.status = 'published'
    )
  );

drop policy if exists "equipe gerencia capitulos de audio" on public.catalog_audio_chapters;
create policy "equipe gerencia capitulos de audio" on public.catalog_audio_chapters
  for all using (public.can_moderate()) with check (public.can_moderate());

drop policy if exists "progresso de audio da propria conta" on public.audio_progress;
create policy "progresso de audio da propria conta" on public.audio_progress
  for all using (user_id = auth.uid()) with check (user_id = auth.uid());

-- Metadados da edição podem ser públicos (sem segredo). Já os capítulos guardam o
-- audio_path no R2, então só a API (papel storyverse_api) lê/grava essa tabela.
grant select on public.catalog_book_audio to anon, authenticated;
revoke select, insert, update, delete on public.catalog_audio_chapters from anon, authenticated;
grant select, insert, update, delete on public.catalog_book_audio, public.catalog_audio_chapters
  to storyverse_api;
grant select, insert, update, delete on public.audio_progress to storyverse_api, authenticated;
