-- Grupos de roleplay: as pessoas escrevem a história (ações em *negrito*, falas normais) e a IA
-- narra o fundo de tempos em tempos. A IA usa a cota de quem criou o grupo, com teto diário.

alter table public.chat_groups
  add column if not exists mode text not null default 'chat' check (mode in ('chat', 'roleplay')),
  add column if not exists rp_premise text check (rp_premise is null or char_length(rp_premise) <= 1500),
  add column if not exists rp_last_narration_at timestamptz,
  add column if not exists rp_narrations_day date,
  add column if not exists rp_narrations_count integer not null default 0;

-- O personagem que cada pessoa interpreta no roleplay.
alter table public.chat_members
  add column if not exists rp_character text check (rp_character is null or char_length(rp_character) <= 60);

-- Quem fala: uma pessoa ou o narrador (IA). A mensagem do narrador fica no nome do dono do grupo
-- (a cota é dele), mas aparece como "Narrador".
alter table public.chat_messages
  add column if not exists kind text not null default 'user' check (kind in ('user', 'narrator'));

create index if not exists chat_messages_narrator_idx on public.chat_messages (group_id, id) where kind = 'narrator';
