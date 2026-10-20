-- Fundacao da IA: cache compartilhado, metricas por chamada e orcamento global diario.
-- Depende da V4 (runtime role) e da V6 (papeis/auditoria). Nunca guarda conteudo de conversa.

create table if not exists public.ai_cache (
  cache_key text primary key,
  kind text not null,
  content jsonb not null,
  model text,
  hits bigint not null default 0 check (hits >= 0),
  created_at timestamptz not null default now(),
  expires_at timestamptz
);

create index if not exists ai_cache_kind_idx on public.ai_cache (kind, created_at desc);
create index if not exists ai_cache_expires_idx on public.ai_cache (expires_at)
  where expires_at is not null;

alter table public.ai_cache enable row level security;
revoke all on public.ai_cache from public, anon, authenticated;
grant select, insert, update, delete on public.ai_cache to storyverse_api;
drop policy if exists ai_cache_runtime on public.ai_cache;
create policy ai_cache_runtime on public.ai_cache
  for all to storyverse_api
  using (true)
  with check (true);

create table if not exists public.ai_usage_events (
  id bigint generated always as identity primary key,
  user_id uuid not null references auth.users (id) on delete cascade,
  day date not null default ((now() at time zone 'America/Sao_Paulo')::date),
  kind text not null,
  provider text,
  model text,
  status text not null check (status in ('ok', 'error', 'cache_hit', 'budget_blocked')),
  latency_ms integer not null default 0 check (latency_ms >= 0),
  tokens_in integer not null default 0 check (tokens_in >= 0),
  tokens_out integer not null default 0 check (tokens_out >= 0),
  cache_hit boolean not null default false,
  error_code text,
  created_at timestamptz not null default now()
);

create index if not exists ai_usage_events_day_idx on public.ai_usage_events (day, kind, status);
create index if not exists ai_usage_events_user_day_idx on public.ai_usage_events (user_id, day desc);
create index if not exists ai_usage_events_provider_idx on public.ai_usage_events (provider, day desc);

alter table public.ai_usage_events enable row level security;
revoke all on public.ai_usage_events from public, anon, authenticated;
grant select, insert on public.ai_usage_events to storyverse_api;
grant usage, select on sequence public.ai_usage_events_id_seq to storyverse_api;
drop policy if exists ai_usage_events_runtime on public.ai_usage_events;
create policy ai_usage_events_runtime on public.ai_usage_events
  for all to storyverse_api
  using (true)
  with check (true);

create table if not exists public.api_global_usage (
  day date primary key,
  used integer not null default 0 check (used >= 0),
  updated_at timestamptz not null default now()
);

alter table public.api_global_usage enable row level security;
revoke all on public.api_global_usage from public, anon, authenticated;
grant select, insert, update on public.api_global_usage to storyverse_api;
drop policy if exists api_global_usage_runtime on public.api_global_usage;
create policy api_global_usage_runtime on public.api_global_usage
  for all to storyverse_api
  using (true)
  with check (true);
