-- Foto e áudio nos grupos sem pesar a infraestrutura: o arquivo fica no disco da API só por um
-- prazo curto (o tempo de o grupo baixar; cada celular guarda a própria cópia) e depois é
-- apagado. A mensagem continua, com "mídia expirada". O banco guarda só a referência.
alter table public.chat_messages alter column body drop not null;
alter table public.chat_messages drop constraint if exists chat_messages_body_check;

alter table public.chat_messages
  add column if not exists media_kind text check (media_kind in ('image', 'audio')),
  add column if not exists media_path text check (media_path is null or char_length(media_path) <= 200),
  add column if not exists media_mime text check (media_mime is null or char_length(media_mime) <= 60),
  add column if not exists media_bytes integer check (media_bytes is null or media_bytes between 1 and 5000000),
  add column if not exists media_duration_ms integer check (media_duration_ms is null or media_duration_ms between 0 and 600000),
  add column if not exists media_width integer check (media_width is null or media_width between 1 and 10000),
  add column if not exists media_height integer check (media_height is null or media_height between 1 and 10000),
  add column if not exists media_expires_at timestamptz;

-- Texto obrigatório só sem mídia; com mídia, o texto é a legenda (opcional).
alter table public.chat_messages drop constraint if exists chat_messages_body_or_media;
alter table public.chat_messages add constraint chat_messages_body_or_media check (
  (media_kind is null and body is not null and char_length(body) between 1 and 2000)
  or (media_kind is not null and (body is null or char_length(body) <= 2000))
);

create index if not exists chat_messages_media_expiry_idx
  on public.chat_messages (media_expires_at) where media_path is not null;

-- Limpeza periódica (fora de qualquer requisição): solta as referências vencidas e devolve os
-- caminhos para a API apagar os arquivos. Só a API (papel de runtime) chama.
create or replace function public.chat_expire_media()
returns table (media_path text)
language sql
security definer
set search_path = public
as $$
  with expired as (
    select m.id, m.media_path as path
      from public.chat_messages m
     where m.media_path is not null and m.media_expires_at <= now()
     limit 500
     for update skip locked
  )
  update public.chat_messages m
     set media_path = null
    from expired
   where m.id = expired.id
  returning expired.path;
$$;

revoke execute on function public.chat_expire_media() from public, anon, authenticated;
grant execute on function public.chat_expire_media() to storyverse_api;
