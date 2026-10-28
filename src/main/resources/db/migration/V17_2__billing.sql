-- Assinatura mensal (Stripe). V17_2 para não bloquear a V18 reservada para a Fase 2 do admin.
-- O runtime (storyverse_api) herda authenticated e escreve com a marca app.billing_write,
-- definida por transação pelo backend; o cliente nunca escreve nessas tabelas.

create table if not exists public.billing_customers (
  user_id uuid primary key references auth.users (id) on delete cascade,
  stripe_customer_id text not null unique,
  created_at timestamptz not null default now()
);

create table if not exists public.billing_subscriptions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  stripe_subscription_id text not null unique,
  stripe_price_id text not null,
  status text not null check (status in (
    'trialing', 'active', 'past_due', 'canceled', 'unpaid',
    'incomplete', 'incomplete_expired', 'paused')),
  current_period_start timestamptz,
  current_period_end timestamptz,
  cancel_at_period_end boolean not null default false,
  trial_end timestamptz,
  canceled_at timestamptz,
  latest_invoice_status text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists billing_subscriptions_user_idx
  on public.billing_subscriptions (user_id, updated_at desc);
create index if not exists billing_subscriptions_status_idx
  on public.billing_subscriptions (status, current_period_end);
create index if not exists billing_subscriptions_price_idx
  on public.billing_subscriptions (stripe_price_id);

create table if not exists public.billing_events (
  stripe_event_id text primary key,
  type text not null,
  processed_at timestamptz,
  error text,
  attempts integer not null default 0,
  created_at timestamptz not null default now()
);

create table if not exists public.billing_grants (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  reason text not null,
  expires_at timestamptz,
  created_by uuid references auth.users (id) on delete set null,
  created_at timestamptz not null default now(),
  revoked_at timestamptz,
  revoked_by uuid references auth.users (id) on delete set null
);

create index if not exists billing_grants_user_idx
  on public.billing_grants (user_id, created_at desc);

alter table public.billing_customers enable row level security;
alter table public.billing_subscriptions enable row level security;
alter table public.billing_events enable row level security;
alter table public.billing_grants enable row level security;

drop policy if exists "billing customer leitura" on public.billing_customers;
create policy "billing customer leitura" on public.billing_customers
  for select using (user_id = auth.uid() or public.is_staff());

drop policy if exists "billing backend escreve customer" on public.billing_customers;
create policy "billing backend escreve customer" on public.billing_customers
  for all using (current_setting('app.billing_write', true) = 'on')
  with check (current_setting('app.billing_write', true) = 'on');

drop policy if exists "billing subscription leitura" on public.billing_subscriptions;
create policy "billing subscription leitura" on public.billing_subscriptions
  for select using (user_id = auth.uid() or public.is_staff());

drop policy if exists "billing backend escreve subscription" on public.billing_subscriptions;
create policy "billing backend escreve subscription" on public.billing_subscriptions
  for all using (current_setting('app.billing_write', true) = 'on')
  with check (current_setting('app.billing_write', true) = 'on');

drop policy if exists "billing backend eventos" on public.billing_events;
create policy "billing backend eventos" on public.billing_events
  for all using (current_setting('app.billing_write', true) = 'on')
  with check (current_setting('app.billing_write', true) = 'on');

drop policy if exists "billing grant leitura" on public.billing_grants;
create policy "billing grant leitura" on public.billing_grants
  for select using (user_id = auth.uid() or public.is_staff());

drop policy if exists "billing backend escreve grant" on public.billing_grants;
create policy "billing backend escreve grant" on public.billing_grants
  for all using (current_setting('app.billing_write', true) = 'on' or public.has_role('superadmin'))
  with check (current_setting('app.billing_write', true) = 'on' or public.has_role('superadmin'));

grant select, insert, update, delete on
  public.billing_customers, public.billing_subscriptions, public.billing_events, public.billing_grants
  to authenticated;
