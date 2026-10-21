-- Acesso por usuário a features em beta (ex.: library). O servidor é a fonte da verdade;
-- a UI só esconde/mostra, não autoriza.
create table if not exists public.feature_access (
  user_id uuid not null references auth.users (id) on delete cascade,
  feature text not null check (feature ~ '^[a-z][a-z0-9_]{1,40}$'),
  enabled boolean not null default true,
  granted_by uuid references auth.users (id) on delete set null,
  granted_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (user_id, feature)
);

create index if not exists feature_access_feature_idx
  on public.feature_access (feature, enabled);

alter table public.feature_access enable row level security;

drop policy if exists "cada um ve suas features" on public.feature_access;
create policy "cada um ve suas features" on public.feature_access
  for select using (user_id = auth.uid() or public.has_role('superadmin'));

drop policy if exists "superadmin gerencia features" on public.feature_access;
create policy "superadmin gerencia features" on public.feature_access
  for all using (public.has_role('superadmin'))
  with check (public.has_role('superadmin'));

grant select on public.feature_access to authenticated;
grant insert, update, delete on public.feature_access to authenticated;
