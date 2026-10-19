-- Forum: espacos, topicos, respostas, reacoes, bookmarks e denuncias.
-- Depende da V6 (user_roles, has_role, is_staff).

create table if not exists public.forum_spaces (
  id uuid primary key default gen_random_uuid(),
  slug text not null unique,
  name text not null check (char_length(name) between 1 and 80),
  description text check (description is null or char_length(description) <= 500),
  sort_order integer not null default 0,
  is_active boolean not null default true,
  created_at timestamptz not null default now()
);

create table if not exists public.forum_topics (
  id uuid primary key default gen_random_uuid(),
  space_id uuid not null references public.forum_spaces (id) on delete restrict,
  author_id uuid not null references auth.users (id) on delete cascade,
  title text not null check (char_length(title) between 3 and 160),
  body text not null check (char_length(body) between 1 and 20000),
  book_key text check (book_key is null or book_key ~ '^(gb:[0-9]+|cm:[0-9a-fA-F-]{36})$'),
  chapter_ref text check (chapter_ref is null or char_length(chapter_ref) <= 200),
  spoiler_level text not null default 'none' check (spoiler_level in ('none', 'chapter', 'book')),
  status text not null default 'published' check (status in ('published', 'pending', 'hidden', 'deleted')),
  is_pinned boolean not null default false,
  is_locked boolean not null default false,
  accepted_reply_id uuid,
  reply_count integer not null default 0 check (reply_count >= 0),
  reaction_count integer not null default 0 check (reaction_count >= 0),
  last_activity_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);

create index if not exists forum_topics_space_activity_idx
  on public.forum_topics (space_id, last_activity_at desc, id)
  where status = 'published' and deleted_at is null;
create index if not exists forum_topics_book_activity_idx
  on public.forum_topics (book_key, last_activity_at desc)
  where book_key is not null and status = 'published' and deleted_at is null;
create index if not exists forum_topics_status_activity_idx
  on public.forum_topics (status, last_activity_at desc);
create index if not exists forum_topics_author_idx
  on public.forum_topics (author_id, created_at desc);

create table if not exists public.forum_replies (
  id uuid primary key default gen_random_uuid(),
  topic_id uuid not null references public.forum_topics (id) on delete cascade,
  author_id uuid not null references auth.users (id) on delete cascade,
  parent_id uuid references public.forum_replies (id) on delete set null,
  body text not null check (char_length(body) between 1 and 20000),
  status text not null default 'published' check (status in ('published', 'pending', 'hidden', 'deleted')),
  reaction_count integer not null default 0 check (reaction_count >= 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);

create index if not exists forum_replies_topic_idx
  on public.forum_replies (topic_id, created_at, id)
  where status = 'published' and deleted_at is null;
create index if not exists forum_replies_author_idx
  on public.forum_replies (author_id, created_at desc);

do $$
begin
  if not exists (select 1 from pg_constraint where conname = 'forum_topics_accepted_reply_fk') then
    alter table public.forum_topics
      add constraint forum_topics_accepted_reply_fk
      foreign key (accepted_reply_id) references public.forum_replies (id) on delete set null;
  end if;
end $$;

create table if not exists public.forum_reactions (
  user_id uuid not null references auth.users (id) on delete cascade,
  target_type text not null check (target_type in ('topic', 'reply')),
  target_id uuid not null,
  reaction text not null default 'like' check (reaction in ('like')),
  created_at timestamptz not null default now(),
  primary key (user_id, target_type, target_id)
);

create index if not exists forum_reactions_target_idx
  on public.forum_reactions (target_type, target_id);

create table if not exists public.forum_bookmarks (
  user_id uuid not null references auth.users (id) on delete cascade,
  topic_id uuid not null references public.forum_topics (id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, topic_id)
);

create index if not exists forum_bookmarks_user_idx
  on public.forum_bookmarks (user_id, created_at desc);

create table if not exists public.forum_reports (
  id uuid primary key default gen_random_uuid(),
  reporter_id uuid not null references auth.users (id) on delete cascade,
  target_type text not null check (target_type in ('topic', 'reply')),
  target_id uuid not null,
  reason_code text not null check (reason_code in (
    'spam', 'abuso', 'assedio', 'spoiler', 'fora_do_topico', 'direitos_autorais', 'outro'
  )),
  details text check (details is null or char_length(details) <= 1000),
  status text not null default 'pending' check (status in ('pending', 'actioned', 'dismissed')),
  reviewed_by uuid references auth.users (id) on delete set null,
  reviewed_at timestamptz,
  resolution_note text,
  created_at timestamptz not null default now(),
  unique (reporter_id, target_type, target_id)
);

create index if not exists forum_reports_status_idx
  on public.forum_reports (status, created_at);
create index if not exists forum_reports_target_idx
  on public.forum_reports (target_type, target_id);

-- Categorias iniciais do forum.
insert into public.forum_spaces (slug, name, description, sort_order)
values
  ('anuncios', 'Anúncios', 'Avisos e novidades da equipe.', 10),
  ('duvidas', 'Dúvidas', 'Perguntas sobre o app, os livros e a leitura.', 20),
  ('clube', 'Clube de leitura', 'Conversas sobre livros, personagens e trechos.', 30)
on conflict (slug) do nothing;

-- Visibilidade de um topico sem recursao de RLS.
create or replace function public.forum_topic_visible(p_topic uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists (
    select 1
      from public.forum_topics t
      join public.forum_spaces s on s.id = t.space_id
     where t.id = p_topic
       and t.status = 'published'
       and t.deleted_at is null
       and s.is_active
  );
$$;
grant execute on function public.forum_topic_visible(uuid) to anon, authenticated;

-- Contadores denormalizados: respostas publicadas e reacoes.
create or replace function public.forum_reply_activity()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  if tg_op = 'INSERT' then
    if new.status = 'published' then
      update public.forum_topics
         set reply_count = reply_count + 1,
             last_activity_at = greatest(last_activity_at, new.created_at)
       where id = new.topic_id;
    end if;
    return new;
  elsif tg_op = 'UPDATE' then
    if new.status = 'published' and old.status <> 'published' then
      update public.forum_topics
         set reply_count = reply_count + 1,
             last_activity_at = greatest(last_activity_at, new.created_at)
       where id = new.topic_id;
    elsif old.status = 'published' and new.status <> 'published' then
      update public.forum_topics
         set reply_count = greatest(reply_count - 1, 0)
       where id = old.topic_id;
    end if;
    return new;
  elsif tg_op = 'DELETE' then
    if old.status = 'published' then
      update public.forum_topics
         set reply_count = greatest(reply_count - 1, 0)
       where id = old.topic_id;
    end if;
    return old;
  end if;
  return null;
end;
$$;

drop trigger if exists forum_replies_activity on public.forum_replies;
create trigger forum_replies_activity
  after insert or update of status or delete on public.forum_replies
  for each row execute function public.forum_reply_activity();

create or replace function public.forum_reaction_count()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  if tg_op = 'INSERT' then
    if new.target_type = 'topic' then
      update public.forum_topics set reaction_count = reaction_count + 1 where id = new.target_id;
    else
      update public.forum_replies set reaction_count = reaction_count + 1 where id = new.target_id;
    end if;
    return new;
  elsif tg_op = 'DELETE' then
    if old.target_type = 'topic' then
      update public.forum_topics set reaction_count = greatest(reaction_count - 1, 0) where id = old.target_id;
    else
      update public.forum_replies set reaction_count = greatest(reaction_count - 1, 0) where id = old.target_id;
    end if;
    return old;
  end if;
  return null;
end;
$$;

drop trigger if exists forum_reactions_count on public.forum_reactions;
create trigger forum_reactions_count
  after insert or delete on public.forum_reactions
  for each row execute function public.forum_reaction_count();

alter table public.forum_spaces enable row level security;
alter table public.forum_topics enable row level security;
alter table public.forum_replies enable row level security;
alter table public.forum_reactions enable row level security;
alter table public.forum_bookmarks enable row level security;
alter table public.forum_reports enable row level security;

drop policy if exists "todos veem espacos ativos" on public.forum_spaces;
create policy "todos veem espacos ativos" on public.forum_spaces
  for select using (is_active or public.is_staff());

drop policy if exists "superadmin gerencia espacos" on public.forum_spaces;
create policy "superadmin gerencia espacos" on public.forum_spaces
  for all using (public.has_role('superadmin'))
  with check (public.has_role('superadmin'));

drop policy if exists "topicos visiveis" on public.forum_topics;
create policy "topicos visiveis" on public.forum_topics
  for select using (
    (status = 'published' and deleted_at is null)
    or author_id = auth.uid()
    or public.is_staff()
  );

drop policy if exists "autores criam topicos" on public.forum_topics;
create policy "autores criam topicos" on public.forum_topics
  for insert with check (
    author_id = auth.uid()
    and status in ('published', 'pending')
  );

drop policy if exists "autores editam topicos" on public.forum_topics;
create policy "autores editam topicos" on public.forum_topics
  for update using (
    (author_id = auth.uid() and deleted_at is null)
    or public.is_staff()
  )
  with check (
    (author_id = auth.uid() and deleted_at is null)
    or public.is_staff()
  );

drop policy if exists "respostas visiveis" on public.forum_replies;
create policy "respostas visiveis" on public.forum_replies
  for select using (
    (status = 'published' and deleted_at is null and public.forum_topic_visible(topic_id))
    or author_id = auth.uid()
    or public.is_staff()
  );

drop policy if exists "autores criam respostas" on public.forum_replies;
create policy "autores criam respostas" on public.forum_replies
  for insert with check (
    author_id = auth.uid()
    and status in ('published', 'pending')
    and public.forum_topic_visible(topic_id)
  );

drop policy if exists "autores editam respostas" on public.forum_replies;
create policy "autores editam respostas" on public.forum_replies
  for update using (
    (author_id = auth.uid() and deleted_at is null)
    or public.is_staff()
  )
  with check (
    (author_id = auth.uid() and deleted_at is null)
    or public.is_staff()
  );

drop policy if exists "cada um ve suas reacoes" on public.forum_reactions;
create policy "cada um ve suas reacoes" on public.forum_reactions
  for select using (user_id = auth.uid() or public.is_staff());

drop policy if exists "cada um reage" on public.forum_reactions;
create policy "cada um reage" on public.forum_reactions
  for insert with check (user_id = auth.uid());

drop policy if exists "cada um remove sua reacao" on public.forum_reactions;
create policy "cada um remove sua reacao" on public.forum_reactions
  for delete using (user_id = auth.uid() or public.is_staff());

drop policy if exists "cada um ve seus bookmarks" on public.forum_bookmarks;
create policy "cada um ve seus bookmarks" on public.forum_bookmarks
  for select using (user_id = auth.uid() or public.is_staff());

drop policy if exists "cada um salva seu bookmark" on public.forum_bookmarks;
create policy "cada um salva seu bookmark" on public.forum_bookmarks
  for insert with check (user_id = auth.uid());

drop policy if exists "cada um remove seu bookmark" on public.forum_bookmarks;
create policy "cada um remove seu bookmark" on public.forum_bookmarks
  for delete using (user_id = auth.uid() or public.is_staff());

drop policy if exists "cada um ve suas denuncias" on public.forum_reports;
create policy "cada um ve suas denuncias" on public.forum_reports
  for select using (reporter_id = auth.uid() or public.is_staff());

drop policy if exists "cada um denuncia" on public.forum_reports;
create policy "cada um denuncia" on public.forum_reports
  for insert with check (reporter_id = auth.uid());

drop policy if exists "equipe revisa denuncias" on public.forum_reports;
create policy "equipe revisa denuncias" on public.forum_reports
  for update using (public.is_staff())
  with check (public.is_staff());

grant select on public.forum_spaces, public.forum_topics, public.forum_replies to anon, authenticated;
grant insert, update on public.forum_topics, public.forum_replies to authenticated;
grant select, insert, delete on public.forum_reactions to authenticated;
grant select, insert, delete on public.forum_bookmarks to authenticated;
grant select, insert, update on public.forum_reports to authenticated;
grant insert, update, delete on public.forum_spaces to authenticated;
