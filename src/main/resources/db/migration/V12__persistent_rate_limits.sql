-- Rate limit compartilhado entre instâncias (substitui o contador só em memória quando o banco
-- está disponível). A chave já vem com prefixo de escopo, ex.: ip:..., login-fail:..., ai:....
create table if not exists public.api_rate_limits (
  bucket_key text primary key check (char_length(bucket_key) <= 300),
  window_start timestamptz not null,
  count integer not null check (count > 0)
);

alter table public.api_rate_limits enable row level security;
revoke all on public.api_rate_limits from public, anon, authenticated;
grant select, insert, update, delete on public.api_rate_limits to storyverse_api;

drop policy if exists api_rate_limits_runtime on public.api_rate_limits;
create policy api_rate_limits_runtime on public.api_rate_limits
  for all to storyverse_api
  using (true)
  with check (true);
