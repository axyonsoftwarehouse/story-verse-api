-- Grupos com chat (docs/SPEC_GRUPOS.md no story-verse-web). Grupo público aparece na lista e
-- qualquer conta entra; privado só pelo código de convite. Livro opcional transforma o grupo em
-- clube de leitura (meta por capítulo/data). O servidor é a fonte da verdade: a API checa tudo
-- de novo, e a RLS vale como segunda barreira no papel de runtime.

create table if not exists public.chat_groups (
  id uuid primary key default gen_random_uuid(),
  name text not null check (char_length(name) between 3 and 60),
  description text check (description is null or char_length(description) <= 300),
  visibility text not null check (visibility in ('public', 'private')),
  owner_id uuid not null references auth.users (id) on delete cascade,
  book_key text check (book_key is null or book_key ~ '^(gb|cm|cat):[0-9A-Za-z-]{1,64}$'),
  book_title text check (book_title is null or char_length(book_title) <= 200),
  book_cover text check (book_cover is null or char_length(book_cover) <= 1000),
  goal_chapter integer check (goal_chapter is null or goal_chapter between 1 and 10000),
  goal_until date,
  invite_code text not null unique check (invite_code ~ '^[A-Za-z0-9_-]{16,64}$'),
  last_message_at timestamptz,
  deleted_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists chat_groups_public_idx
  on public.chat_groups (last_message_at desc nulls last)
  where visibility = 'public' and deleted_at is null;
create index if not exists chat_groups_book_idx
  on public.chat_groups (book_key)
  where book_key is not null and deleted_at is null;
create index if not exists chat_groups_owner_idx on public.chat_groups (owner_id, created_at desc);

create table if not exists public.chat_members (
  group_id uuid not null references public.chat_groups (id) on delete cascade,
  user_id uuid not null references auth.users (id) on delete cascade,
  role text not null default 'member' check (role in ('owner', 'moderator', 'member')),
  last_read_id bigint not null default 0,
  muted boolean not null default false,
  banned_at timestamptz,
  joined_at timestamptz not null default now(),
  primary key (group_id, user_id)
);

create index if not exists chat_members_user_idx on public.chat_members (user_id) where banned_at is null;

create table if not exists public.chat_messages (
  id bigint generated always as identity primary key,
  group_id uuid not null references public.chat_groups (id) on delete cascade,
  author_id uuid not null references auth.users (id) on delete cascade,
  body text not null check (char_length(body) between 1 and 2000),
  spoiler_chapter integer check (spoiler_chapter is null or spoiler_chapter between 1 and 10000),
  reply_to bigint references public.chat_messages (id) on delete set null,
  client_id uuid not null,
  deleted_at timestamptz,
  deleted_by uuid references auth.users (id) on delete set null,
  created_at timestamptz not null default now(),
  unique (author_id, client_id)
);

create index if not exists chat_messages_group_idx on public.chat_messages (group_id, id desc);

create table if not exists public.chat_reactions (
  message_id bigint not null references public.chat_messages (id) on delete cascade,
  user_id uuid not null references auth.users (id) on delete cascade,
  emoji text not null check (emoji in ('👍', '❤️', '😂', '😮', '😢', '🔥')),
  created_at timestamptz not null default now(),
  primary key (message_id, user_id, emoji)
);

create table if not exists public.chat_reports (
  id uuid primary key default gen_random_uuid(),
  reporter_id uuid not null references auth.users (id) on delete cascade,
  message_id bigint not null references public.chat_messages (id) on delete cascade,
  reason_code text not null check (reason_code in ('spam', 'abuso', 'assedio', 'spoiler', 'outro')),
  details text check (details is null or char_length(details) <= 1000),
  status text not null default 'pending' check (status in ('pending', 'actioned', 'dismissed')),
  reviewed_by uuid references auth.users (id) on delete set null,
  reviewed_at timestamptz,
  resolution_note text check (resolution_note is null or char_length(resolution_note) <= 1000),
  created_at timestamptz not null default now(),
  unique (reporter_id, message_id)
);

create index if not exists chat_reports_status_idx on public.chat_reports (status, created_at);
create index if not exists chat_reports_message_idx on public.chat_reports (message_id) where status = 'pending';

-- Membro ativo (não removido) do grupo. security definer: a RLS de chat_members chamaria a si mesma.
create or replace function public.chat_is_member(p_group uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists (
    select 1 from public.chat_members m
     where m.group_id = p_group and m.user_id = auth.uid() and m.banned_at is null
  );
$$;

-- Dono ou moderador do grupo.
create or replace function public.chat_is_manager(p_group uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists (
    select 1 from public.chat_members m
     where m.group_id = p_group and m.user_id = auth.uid() and m.banned_at is null
       and m.role in ('owner', 'moderator')
  );
$$;

-- Progresso dos membros no livro do grupo (clube). A estante é privada pela RLS; aqui só sai o
-- percentual e o status daquele livro, e só para quem é membro do grupo.
create or replace function public.chat_member_progress(p_group uuid)
returns table (user_id uuid, progress_percent integer, status text)
language sql
stable
security definer
set search_path = public
as $$
  select e.user_id, e.progress_percent::integer, e.status
    from public.chat_groups g
    join public.chat_members m on m.group_id = g.id and m.banned_at is null
    join public.library_entries e on e.user_id = m.user_id and e.book_key = g.book_key and e.deleted_at is null
   where g.id = p_group and g.book_key is not null and g.deleted_at is null
     and public.chat_is_member(p_group);
$$;

revoke execute on function public.chat_member_progress(uuid) from public, anon;
grant execute on function public.chat_member_progress(uuid) to authenticated;

revoke execute on function public.chat_is_member(uuid) from public, anon;
revoke execute on function public.chat_is_manager(uuid) from public, anon;
grant execute on function public.chat_is_member(uuid) to authenticated;
grant execute on function public.chat_is_manager(uuid) to authenticated;

-- Mensagem nova atualiza a ordem da lista de grupos (o membro comum não pode editar o grupo).
create or replace function public.chat_touch_group()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  update public.chat_groups set last_message_at = new.created_at where id = new.group_id;
  return new;
end;
$$;

drop trigger if exists chat_messages_touch_group on public.chat_messages;
create trigger chat_messages_touch_group
  after insert on public.chat_messages
  for each row execute function public.chat_touch_group();

alter table public.chat_groups enable row level security;
alter table public.chat_members enable row level security;
alter table public.chat_messages enable row level security;
alter table public.chat_reactions enable row level security;
alter table public.chat_reports enable row level security;

drop policy if exists "grupos visiveis" on public.chat_groups;
create policy "grupos visiveis" on public.chat_groups
  for select using (
    (deleted_at is null and (visibility = 'public' or public.chat_is_member(id)))
    or public.is_staff()
  );

drop policy if exists "contas criam grupos" on public.chat_groups;
create policy "contas criam grupos" on public.chat_groups
  for insert with check (owner_id = auth.uid() and deleted_at is null);

-- O check aceita gestor (não só o dono): ao passar a posse, owner_id vira outra conta.
drop policy if exists "dono edita grupo" on public.chat_groups;
create policy "dono edita grupo" on public.chat_groups
  for update using (owner_id = auth.uid() or public.is_staff())
  with check (owner_id = auth.uid() or public.chat_is_manager(id) or public.is_staff());

drop policy if exists "membros visiveis" on public.chat_members;
create policy "membros visiveis" on public.chat_members
  for select using (user_id = auth.uid() or public.chat_is_member(group_id) or public.is_staff());

-- Entrar: só a própria linha. Grupo privado exige o convite, que só a API confere.
drop policy if exists "cada um entra" on public.chat_members;
create policy "cada um entra" on public.chat_members
  for insert with check (user_id = auth.uid());

drop policy if exists "membro ou gestor altera" on public.chat_members;
create policy "membro ou gestor altera" on public.chat_members
  for update using (user_id = auth.uid() or public.chat_is_manager(group_id) or public.is_staff())
  with check (user_id = auth.uid() or public.chat_is_manager(group_id) or public.is_staff());

drop policy if exists "membro sai" on public.chat_members;
create policy "membro sai" on public.chat_members
  for delete using (user_id = auth.uid() or public.is_staff());

drop policy if exists "membros leem mensagens" on public.chat_messages;
create policy "membros leem mensagens" on public.chat_messages
  for select using (public.chat_is_member(group_id) or author_id = auth.uid() or public.is_staff());

drop policy if exists "membros escrevem" on public.chat_messages;
create policy "membros escrevem" on public.chat_messages
  for insert with check (author_id = auth.uid() and deleted_at is null and public.chat_is_member(group_id));

drop policy if exists "autor ou gestor apaga" on public.chat_messages;
create policy "autor ou gestor apaga" on public.chat_messages
  for update using (author_id = auth.uid() or public.chat_is_manager(group_id) or public.is_staff())
  with check (author_id = auth.uid() or public.chat_is_manager(group_id) or public.is_staff());

drop policy if exists "membros veem reacoes" on public.chat_reactions;
create policy "membros veem reacoes" on public.chat_reactions
  for select using (
    exists (select 1 from public.chat_messages m where m.id = message_id and public.chat_is_member(m.group_id))
    or public.is_staff()
  );

drop policy if exists "membros reagem" on public.chat_reactions;
create policy "membros reagem" on public.chat_reactions
  for insert with check (
    user_id = auth.uid()
    and exists (select 1 from public.chat_messages m where m.id = message_id and public.chat_is_member(m.group_id))
  );

drop policy if exists "cada um tira sua reacao" on public.chat_reactions;
create policy "cada um tira sua reacao" on public.chat_reactions
  for delete using (user_id = auth.uid() or public.is_staff());

drop policy if exists "cada um ve suas denuncias de chat" on public.chat_reports;
create policy "cada um ve suas denuncias de chat" on public.chat_reports
  for select using (reporter_id = auth.uid() or public.is_staff());

drop policy if exists "membros denunciam" on public.chat_reports;
create policy "membros denunciam" on public.chat_reports
  for insert with check (
    reporter_id = auth.uid()
    and exists (select 1 from public.chat_messages m where m.id = message_id and public.chat_is_member(m.group_id))
  );

drop policy if exists "equipe revisa denuncias de chat" on public.chat_reports;
create policy "equipe revisa denuncias de chat" on public.chat_reports
  for update using (public.is_staff())
  with check (public.is_staff());

grant select, insert, update on public.chat_groups to authenticated;
grant select, insert, update, delete on public.chat_members to authenticated;
grant select, insert, update on public.chat_messages to authenticated;
grant usage, select on sequence public.chat_messages_id_seq to authenticated;
grant select, insert, delete on public.chat_reactions to authenticated;
grant select, insert, update on public.chat_reports to authenticated;
