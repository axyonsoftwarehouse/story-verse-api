create table auth.session_token_history (
  token_hash text primary key,
  session_id uuid not null references auth.sessions(id) on delete cascade,
  expires_at timestamptz not null
);
create index session_token_history_expiry on auth.session_token_history(expires_at);

create table public.api_daily_usage (
  user_id uuid not null references auth.users(id) on delete cascade,
  day date not null,
  feature text not null,
  used integer not null check (used > 0),
  primary key (user_id, day, feature)
);
alter table public.api_daily_usage enable row level security;
revoke all on public.api_daily_usage from public, anon, authenticated;
