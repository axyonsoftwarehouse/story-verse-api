-- Roleplay que vira livro: a IA reescreve a história do grupo em capítulos de prosa. O livro fica
-- com o grupo (só os membros veem) e cada um leva para a própria biblioteca.
create table if not exists public.chat_books (
  group_id uuid primary key references public.chat_groups (id) on delete cascade,
  status text not null default 'writing' check (status in ('writing', 'ready', 'failed')),
  title text not null check (char_length(title) between 1 and 120),
  text text check (text is null or char_length(text) <= 400000),
  chapters integer not null default 0,
  error text check (error is null or char_length(error) <= 300),
  created_by uuid not null references auth.users (id) on delete cascade,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table public.chat_books enable row level security;
revoke all on public.chat_books from public, anon;
grant select, insert, update on public.chat_books to authenticated;

drop policy if exists "membros leem o livro" on public.chat_books;
create policy "membros leem o livro" on public.chat_books
  for select using (public.chat_is_member(group_id) or public.is_staff());

-- Só quem criou o grupo encomenda e escreve o livro (a cota de IA é dele).
drop policy if exists "dono escreve o livro" on public.chat_books;
create policy "dono escreve o livro" on public.chat_books
  for insert with check (
    created_by = auth.uid()
    and exists (select 1 from public.chat_groups g where g.id = group_id and g.owner_id = auth.uid() and g.mode = 'roleplay')
  );
drop policy if exists "dono atualiza o livro" on public.chat_books;
create policy "dono atualiza o livro" on public.chat_books
  for update using (exists (select 1 from public.chat_groups g where g.id = group_id and g.owner_id = auth.uid()))
  with check (exists (select 1 from public.chat_groups g where g.id = group_id and g.owner_id = auth.uid()));
