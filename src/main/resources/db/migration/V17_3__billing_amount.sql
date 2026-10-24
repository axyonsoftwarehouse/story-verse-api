-- Valor do plano na assinatura, para o financeiro calcular MRR sem chamar o Stripe a cada tela.
alter table public.billing_subscriptions
  add column if not exists amount_cents integer,
  add column if not exists currency text;
