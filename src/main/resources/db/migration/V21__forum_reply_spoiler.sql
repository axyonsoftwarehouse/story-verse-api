-- O aviso de spoiler do tópico não cobre o que o leitor escreve numa resposta:
-- a resposta também pode revelar o final do livro.
alter table public.forum_replies
  add column if not exists spoiler_level text not null default 'none';

alter table public.forum_replies
  drop constraint if exists forum_replies_spoiler_check;
alter table public.forum_replies
  add constraint forum_replies_spoiler_check
  check (spoiler_level in ('none', 'chapter', 'book'));

-- Quem marcou a resposta aceita (autor do tópico ou moderação) e quando.
alter table public.forum_topics
  add column if not exists accepted_by uuid references auth.users (id) on delete set null;
alter table public.forum_topics
  add column if not exists accepted_at timestamptz;

comment on column public.forum_replies.spoiler_level is
  'none | chapter | book — resposta que revela a história exige confirmação do leitor';
comment on column public.forum_topics.accepted_by is
  'quem marcou a resposta aceita: o autor do tópico ou alguém da equipe';
