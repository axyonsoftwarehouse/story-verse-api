-- Confirmação de e-mail e recuperação de senha por link.
-- Tokens são opacos: o banco guarda apenas o SHA-256.
create table if not exists auth.email_tokens (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  kind text not null check (kind in ('confirm_email', 'reset_password')),
  token_hash text not null unique,
  expires_at timestamptz not null,
  used_at timestamptz,
  created_at timestamptz not null default now()
);
create index if not exists email_tokens_user_kind_idx on auth.email_tokens (user_id, kind, created_at desc);
create index if not exists email_tokens_expires_idx on auth.email_tokens (expires_at);

-- Contas criadas antes desta migração já eram usáveis: não bloquear o login delas.
update auth.users set email_confirmed_at = coalesce(email_confirmed_at, now());

-- A API precisa atualizar confirmação, senha e metadados de perfil.
grant select, insert, update, delete on auth.email_tokens to storyverse_api;
grant update (email_confirmed_at, encrypted_password, raw_user_meta_data, updated_at)
  on auth.users to storyverse_api;
