-- Quando a assinatura terminou de fato.
--
-- Sem esta coluna, uma assinatura cancelada imediatamente continuava liberando
-- acesso até current_period_end: no QA, cancelar hoje (25/10) manteve o acesso
-- até 25/11 — um mês de leitura de graça, mesmo com o pagamento reembolsado.
alter table public.billing_subscriptions
  add column if not exists ended_at timestamptz;
