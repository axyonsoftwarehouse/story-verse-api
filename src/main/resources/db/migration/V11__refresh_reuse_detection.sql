-- Separa tokens de acesso e refresh no histórico para detectar reuso de refresh token.
-- O histórico já era usado para localizar a sessão no logout após rotação.
alter table auth.session_token_history
  add column if not exists kind text not null default 'refresh';

alter table auth.session_token_history
  drop constraint if exists session_token_history_kind_check;
alter table auth.session_token_history
  add constraint session_token_history_kind_check check (kind in ('access', 'refresh'));

create index if not exists session_token_history_refresh_reuse_idx
  on auth.session_token_history (token_hash)
  where kind = 'refresh';
