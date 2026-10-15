-- Sessões do backend de contas (substitui o Supabase Auth no Marco 2).
-- Depende do schema base (database/schema.sql), que cria auth.users.
-- Tokens são opacos; guardamos apenas o SHA-256 (nunca o valor em claro).
create table if not exists auth.sessions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  access_hash text not null unique,
  access_expires_at timestamptz not null,
  refresh_hash text not null unique,
  refresh_expires_at timestamptz not null,
  created_at timestamptz not null default now(),
  last_used_at timestamptz,
  revoked_at timestamptz
);

create index if not exists sessions_user_idx on auth.sessions (user_id);
