-- Papeis, auditoria, sancoes, notificacoes e status de backup.
-- A API de runtime (storyverse_api) herda authenticated e continua sujeita a RLS.

create table if not exists public.user_roles (
  user_id uuid not null references auth.users (id) on delete cascade,
  role text not null check (role in ('superadmin', 'moderator', 'support')),
  granted_by uuid references auth.users (id) on delete set null,
  granted_at timestamptz not null default now(),
  primary key (user_id, role)
);
create index if not exists user_roles_role_idx on public.user_roles (role);

-- Mantem os admins atuais como superadmins.
insert into public.user_roles (user_id, role)
select user_id, 'superadmin' from public.admins
on conflict do nothing;

create or replace function public.has_role(p_role text)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists (
    select 1 from public.user_roles
    where user_id = auth.uid() and role = p_role
  );
$$;
grant execute on function public.has_role(text) to anon, authenticated;

create or replace function public.is_staff()
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists (select 1 from public.admins where user_id = auth.uid())
      or public.has_role('superadmin')
      or public.has_role('moderator')
      or public.has_role('support');
$$;
grant execute on function public.is_staff() to anon, authenticated;

-- Mantem is_admin() compativel com o codigo existente.
create or replace function public.is_admin()
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select public.has_role('superadmin')
      or exists (select 1 from public.admins where user_id = auth.uid());
$$;
grant execute on function public.is_admin() to anon, authenticated;

alter table public.user_roles enable row level security;

drop policy if exists "cada um ve seus papeis" on public.user_roles;
create policy "cada um ve seus papeis" on public.user_roles
  for select using (user_id = auth.uid() or public.has_role('superadmin'));

drop policy if exists "superadmin gerencia papeis" on public.user_roles;
create policy "superadmin gerencia papeis" on public.user_roles
  for all using (public.has_role('superadmin'))
  with check (public.has_role('superadmin'));

grant select, insert, update, delete on public.user_roles to authenticated;

-- Auditoria append-only de acoes administrativas.
create table if not exists public.admin_audit_log (
  id bigint generated always as identity primary key,
  actor_id uuid references auth.users (id) on delete set null,
  action text not null,
  target_type text,
  target_id text,
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);
create index if not exists admin_audit_log_created_idx on public.admin_audit_log (created_at desc);
create index if not exists admin_audit_log_actor_idx on public.admin_audit_log (actor_id, created_at desc);

alter table public.admin_audit_log enable row level security;

drop policy if exists "superadmin ve auditoria" on public.admin_audit_log;
create policy "superadmin ve auditoria" on public.admin_audit_log
  for select using (public.has_role('superadmin'));

drop policy if exists "equipe registra auditoria" on public.admin_audit_log;
create policy "equipe registra auditoria" on public.admin_audit_log
  for insert with check (public.is_staff());

grant select, insert on public.admin_audit_log to authenticated;

-- Avisos, suspensoes e banimentos.
create table if not exists public.user_sanctions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  kind text not null check (kind in ('warning', 'suspension', 'ban')),
  reason text,
  expires_at timestamptz,
  created_by uuid references auth.users (id) on delete set null,
  created_at timestamptz not null default now(),
  revoked_at timestamptz,
  revoked_by uuid references auth.users (id) on delete set null
);
create index if not exists user_sanctions_user_idx on public.user_sanctions (user_id, created_at desc);
create index if not exists user_sanctions_active_idx on public.user_sanctions (expires_at) where revoked_at is null;

alter table public.user_sanctions enable row level security;

drop policy if exists "cada um ve suas sancoes" on public.user_sanctions;
create policy "cada um ve suas sancoes" on public.user_sanctions
  for select using (user_id = auth.uid() or public.is_staff());

drop policy if exists "equipe gerencia sancoes" on public.user_sanctions;
create policy "equipe gerencia sancoes" on public.user_sanctions
  for all using (public.is_staff())
  with check (public.is_staff());

grant select, insert, update on public.user_sanctions to authenticated;

-- Central de notificacoes (alertas administrativos no MVP; usuario na fase 3).
create table if not exists public.notifications (
  id uuid primary key default gen_random_uuid(),
  recipient_id uuid not null references auth.users (id) on delete cascade,
  actor_id uuid references auth.users (id) on delete set null,
  type text not null,
  target_type text,
  target_id text,
  data jsonb not null default '{}'::jsonb,
  read_at timestamptz,
  created_at timestamptz not null default now()
);
create index if not exists notifications_recipient_unread_idx
  on public.notifications (recipient_id, created_at desc) where read_at is null;

alter table public.notifications enable row level security;

drop policy if exists "cada um ve suas notificacoes" on public.notifications;
create policy "cada um ve suas notificacoes" on public.notifications
  for select using (recipient_id = auth.uid() or public.has_role('superadmin'));

drop policy if exists "cada um marca suas notificacoes" on public.notifications;
create policy "cada um marca suas notificacoes" on public.notifications
  for update using (recipient_id = auth.uid())
  with check (recipient_id = auth.uid());

drop policy if exists "equipe cria notificacoes" on public.notifications;
create policy "equipe cria notificacoes" on public.notifications
  for insert with check (public.is_staff());

grant select, insert, update on public.notifications to authenticated;

-- Historico de backups para o painel de operacao.
create table if not exists public.backup_runs (
  id bigint generated always as identity primary key,
  started_at timestamptz not null default now(),
  finished_at timestamptz,
  status text not null check (status in ('running', 'success', 'failed')),
  size_bytes bigint,
  path text,
  error text
);
create index if not exists backup_runs_started_idx on public.backup_runs (started_at desc);

alter table public.backup_runs enable row level security;

drop policy if exists "superadmin ve backups" on public.backup_runs;
create policy "superadmin ve backups" on public.backup_runs
  for select using (public.has_role('superadmin'));

grant select on public.backup_runs to authenticated;

-- Quem revisou cada envio do acervo.
alter table public.book_submissions
  add column if not exists reviewed_by uuid references auth.users (id) on delete set null;
