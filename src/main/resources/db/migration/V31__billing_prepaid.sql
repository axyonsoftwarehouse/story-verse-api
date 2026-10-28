-- Pix pré-pago: cada pagamento avulso abre um período de acesso. A conta Stripe do Brasil não
-- tem Pix Automático nem Pix em fatura, então não há assinatura por trás.

create table if not exists public.billing_prepaid (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  stripe_checkout_session_id text not null unique,
  stripe_payment_intent_id text unique,
  amount_cents integer not null,
  currency text not null default 'brl',
  days integer not null,
  starts_at timestamptz not null,
  ends_at timestamptz not null,
  refunded_at timestamptz,
  reminded_at timestamptz,
  created_at timestamptz not null default now()
);

create index if not exists billing_prepaid_user_idx on public.billing_prepaid (user_id, ends_at desc);
create index if not exists billing_prepaid_ending_idx
  on public.billing_prepaid (ends_at) where refunded_at is null and reminded_at is null;

alter table public.billing_prepaid enable row level security;

drop policy if exists "billing prepaid leitura" on public.billing_prepaid;
create policy "billing prepaid leitura" on public.billing_prepaid
  for select using (user_id = auth.uid() or public.is_staff());

drop policy if exists "billing backend escreve prepaid" on public.billing_prepaid;
create policy "billing backend escreve prepaid" on public.billing_prepaid
  for all using (current_setting('app.billing_write', true) = 'on')
  with check (current_setting('app.billing_write', true) = 'on');

grant select, insert, update on public.billing_prepaid to authenticated;
