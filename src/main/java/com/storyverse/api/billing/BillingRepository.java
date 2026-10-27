package com.storyverse.api.billing;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.common.DbRows;

/** Persistência do billing. Toda escrita chama {@link #beginWrite()} dentro da transação. */
@Repository
public class BillingRepository {

	private final JdbcClient jdbc;

	public BillingRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Liga a policy de escrita do backend só nesta transação. */
	public void beginWrite() {
		jdbc.sql("select set_config('app.billing_write', 'on', true)")
				.query((rs, row) -> 1).single();
	}

	public Optional<Map<String, Object>> customer(UUID userId) {
		return DbRows.one(jdbc, "select * from public.billing_customers where user_id = :id",
				Map.of("id", userId));
	}

	public Optional<Map<String, Object>> userByCustomer(String customerId) {
		return DbRows.one(jdbc, "select user_id from public.billing_customers where stripe_customer_id = :id",
				Map.of("id", customerId));
	}

	@Transactional
	public void upsertCustomer(UUID userId, String customerId) {
		beginWrite();
		DbRows.update(jdbc, """
				insert into public.billing_customers (user_id, stripe_customer_id)
				values (:user, :customer)
				on conflict (user_id) do update set stripe_customer_id = excluded.stripe_customer_id
				""", Map.of("user", userId, "customer", customerId));
	}

	public Optional<Map<String, Object>> latestSubscription(UUID userId) {
		return DbRows.one(jdbc, """
				select * from public.billing_subscriptions
				 where user_id = :id
				 order by updated_at desc, created_at desc
				 limit 1
				""", Map.of("id", userId));
	}

	public Optional<Map<String, Object>> subscriptionByStripeId(String id) {
		return DbRows.one(jdbc, "select * from public.billing_subscriptions where stripe_subscription_id = :id",
				Map.of("id", id));
	}

	@Transactional
	public Optional<Map<String, Object>> upsertSubscription(Map<String, Object> params) {
		beginWrite();
		return DbRows.one(jdbc, """
				insert into public.billing_subscriptions
					(user_id, stripe_subscription_id, stripe_price_id, status, current_period_start,
					 current_period_end, cancel_at_period_end, trial_end, canceled_at, ended_at,
					 latest_invoice_status, amount_cents, currency)
				values
					(:user_id, :stripe_subscription_id, :stripe_price_id, :status, :current_period_start,
					 :current_period_end, :cancel_at_period_end, :trial_end, :canceled_at, :ended_at,
					 :latest_invoice_status, :amount_cents, :currency)
				on conflict (stripe_subscription_id) do update
				   set user_id = excluded.user_id,
				       stripe_price_id = excluded.stripe_price_id,
				       status = excluded.status,
				       current_period_start = coalesce(excluded.current_period_start,
				           billing_subscriptions.current_period_start),
				       current_period_end = coalesce(excluded.current_period_end,
				           billing_subscriptions.current_period_end),
				       cancel_at_period_end = excluded.cancel_at_period_end,
				       trial_end = coalesce(excluded.trial_end, billing_subscriptions.trial_end),
				       canceled_at = coalesce(excluded.canceled_at, billing_subscriptions.canceled_at),
				       ended_at = coalesce(excluded.ended_at, billing_subscriptions.ended_at),
				       latest_invoice_status = coalesce(excluded.latest_invoice_status,
				           billing_subscriptions.latest_invoice_status),
				       amount_cents = coalesce(excluded.amount_cents, billing_subscriptions.amount_cents),
				       currency = coalesce(excluded.currency, billing_subscriptions.currency),
				       updated_at = now()
				 returning *
				""", params);
	}

	public Optional<Map<String, Object>> activeGrant(UUID userId) {
		return DbRows.one(jdbc, """
				select * from public.billing_grants
				 where user_id = :id and revoked_at is null
				   and (expires_at is null or expires_at > now())
				 order by created_at desc
				 limit 1
				""", Map.of("id", userId));
	}

	public List<Map<String, Object>> grants(UUID userId) {
		return DbRows.list(jdbc, """
				select * from public.billing_grants
				 where user_id = :id
				 order by created_at desc
				""", Map.of("id", userId));
	}

	@Transactional
	public Map<String, Object> createGrant(UUID userId, String reason, java.time.Instant expiresAt, UUID actor) {
		beginWrite();
		Map<String, Object> params = new java.util.LinkedHashMap<>();
		params.put("user", userId);
		params.put("reason", reason);
		params.put("expires", expiresAt == null ? null : java.sql.Timestamp.from(expiresAt));
		params.put("actor", actor);
		return DbRows.one(jdbc, """
				insert into public.billing_grants (user_id, reason, expires_at, created_by)
				values (:user, :reason, :expires, :actor)
				returning *
				""", params).orElseThrow();
	}

	@Transactional
	public int revokeGrant(UUID id, UUID actor) {
		beginWrite();
		return DbRows.update(jdbc, """
				update public.billing_grants
				   set revoked_at = coalesce(revoked_at, now()), revoked_by = :actor
				 where id = :id
				""", Map.of("id", id, "actor", actor));
	}

	public String email(UUID userId) {
		return jdbc.sql("select email from auth.users where id = :id")
				.param("id", userId).query(String.class).optional().orElse(null);
	}

	// ---- Admin financeiro ------------------------------------------------------------------

	public Map<String, Object> adminSummary(java.time.Instant from, java.time.Instant to) {
		Map<String, Object> params = new java.util.LinkedHashMap<>();
		params.put("from", java.sql.Timestamp.from(from));
		params.put("to", java.sql.Timestamp.from(to));
		return DbRows.one(jdbc, """
				select
				  count(*) filter (where status = 'active') as active,
				  count(*) filter (where status = 'trialing') as trialing,
				  count(*) filter (where status = 'past_due') as past_due,
				  count(*) filter (where status = 'canceled') as canceled,
				  count(*) filter (where created_at between :from and :to) as new_count,
				  count(*) filter (where canceled_at between :from and :to) as canceled_count,
				  count(*) filter (where latest_invoice_status = 'payment_failed') as failed,
				  coalesce(sum(amount_cents) filter (where status = 'active'), 0) as mrr_cents
				  from public.billing_subscriptions
				""", params).orElse(Map.of());
	}

	/** Conversão de teste em pago no período (ver admin_billing_trials na V23). */
	public Map<String, Object> adminTrials(java.time.Instant from, java.time.Instant to) {
		Map<String, Object> params = new java.util.LinkedHashMap<>();
		params.put("from", java.sql.Timestamp.from(from));
		params.put("to", java.sql.Timestamp.from(to));
		return DbRows.one(jdbc, """
				select trials_started, trials_ended, trials_converted
				  from public.admin_billing_trials(:from, :to)
				""", params).orElse(Map.of());
	}

	/** Série por dia/semana; {@code group} já validado pelo serviço. */
	public List<Map<String, Object>> adminSeries(java.time.LocalDate from, java.time.LocalDate to, String group) {
		Map<String, Object> params = new java.util.LinkedHashMap<>();
		params.put("from", java.sql.Date.valueOf(from));
		params.put("to", java.sql.Date.valueOf(to));
		params.put("group", group);
		return DbRows.list(jdbc, """
				select bucket, mrr_cents, active, new_count, canceled_count, revenue_cents, estimated
				  from public.admin_billing_series(:from, :to, :group)
				""", params);
	}

	/** Foto diária do MRR; idempotente (o job reescreve a linha do dia). */
	@Transactional
	public void snapshot(java.time.LocalDate day) {
		beginWrite();
		jdbc.sql("select public.admin_billing_snapshot(:day)")
				.param("day", java.sql.Date.valueOf(day))
				.query((rs, row) -> 1).single();
	}

	public List<Map<String, Object>> adminSubscriptions(String status, String q) {
		Map<String, Object> params = new java.util.LinkedHashMap<>();
		params.put("status", status == null ? "" : status);
		params.put("q", q == null || q.isBlank() ? "" : "%" + q.strip() + "%");
		return DbRows.list(jdbc, """
				select s.*, u.email
				  from public.billing_subscriptions s
				  join auth.users u on u.id = s.user_id
				 where (:status = '' or s.status = :status)
				   and (:q = '' or u.email ilike :q)
				 order by s.updated_at desc
				 limit 50
				""", params);
	}

	public Optional<Map<String, Object>> adminSubscription(UUID id) {
		return DbRows.one(jdbc, """
				select s.*, u.email
				  from public.billing_subscriptions s
				  join auth.users u on u.id = s.user_id
				 where s.id = :id
				""", Map.of("id", id));
	}

	// ---- Webhook ---------------------------------------------------------------------------

	/** Fatura paga (invoice.paid). Reentrega do mesmo evento não duplica a receita. */
	public void upsertInvoice(Map<String, Object> params) {
		beginWrite();
		DbRows.update(jdbc, """
				insert into public.billing_invoices
					(stripe_invoice_id, stripe_subscription_id, user_id, amount_paid_cents, currency, paid_at)
				values (:id, cast(:subscription as text), cast(:user_id as uuid), :amount,
				        cast(:currency as text), :paid_at)
				on conflict (stripe_invoice_id) do update
				   set amount_paid_cents = excluded.amount_paid_cents,
				       paid_at = excluded.paid_at,
				       stripe_subscription_id = coalesce(excluded.stripe_subscription_id,
				           billing_invoices.stripe_subscription_id),
				       user_id = coalesce(excluded.user_id, billing_invoices.user_id)
				""", params);
	}

	public boolean eventSeen(String stripeEventId) {
		return Boolean.TRUE.equals(jdbc.sql("select exists(select 1 from public.billing_events where stripe_event_id = :id)")
				.param("id", stripeEventId).query(Boolean.class).single());
	}

	public boolean eventProcessed(String stripeEventId) {
		return Boolean.TRUE.equals(jdbc.sql("""
				select exists(select 1 from public.billing_events
				               where stripe_event_id = :id and processed_at is not null)
				""").param("id", stripeEventId).query(Boolean.class).single());
	}

	/**
	 * Abre o evento em transação própria (REQUIRES_NEW de propósito): a linha precisa estar
	 * COMMITADA antes da sincronização, senão o {@link #finishEvent} — que também roda em
	 * transação nova — não enxerga a linha e o update não pega nada. É o que fazia o evento
	 * ficar com processed_at nulo mesmo quando dava certo.
	 */
	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
	public void beginEvent(String stripeEventId, String type) {
		beginWrite();
		DbRows.update(jdbc, """
				insert into public.billing_events (stripe_event_id, type, attempts)
				values (:id, :type, 1)
				on conflict (stripe_event_id) do update
				   set attempts = public.billing_events.attempts + 1
				""", Map.of("id", stripeEventId, "type", type));
	}

	/**
	 * Fecha o evento. O cast de :error é obrigatório: sem ele o Postgres não consegue
	 * inferir o tipo do parâmetro quando a mensagem vem nula e recusa o update inteiro
	 * ("could not determine data type of parameter $1") — era o que derrubava TODA entrega
	 * de webhook, inclusive as bem-sucedidas.
	 *
	 * <p>REQUIRES_NEW porque o chamador pode estar registrando uma falha: se a transação
	 * dele for revertida, o evento continua registrado com o erro.
	 */
	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
	public void finishEvent(String stripeEventId, String error) {
		beginWrite();
		Map<String, Object> params = new java.util.LinkedHashMap<>();
		params.put("id", stripeEventId);
		params.put("error", error);
		DbRows.update(jdbc, """
				update public.billing_events
				   set processed_at = case when cast(:error as text) is null then now() else processed_at end,
				       error = cast(:error as text)
				 where stripe_event_id = :id
				""", params);
	}
}
