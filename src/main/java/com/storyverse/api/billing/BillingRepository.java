package com.storyverse.api.billing;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

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

	public Optional<Map<String, Object>> upsertSubscription(Map<String, Object> params) {
		beginWrite();
		return DbRows.one(jdbc, """
				insert into public.billing_subscriptions
					(user_id, stripe_subscription_id, stripe_price_id, status, current_period_start,
					 current_period_end, cancel_at_period_end, trial_end, canceled_at, latest_invoice_status)
				values
					(:user_id, :stripe_subscription_id, :stripe_price_id, :status, :current_period_start,
					 :current_period_end, :cancel_at_period_end, :trial_end, :canceled_at, :latest_invoice_status)
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
				       latest_invoice_status = coalesce(excluded.latest_invoice_status,
				           billing_subscriptions.latest_invoice_status),
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

	// ---- Webhook ---------------------------------------------------------------------------

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

	public void beginEvent(String stripeEventId, String type) {
		beginWrite();
		DbRows.update(jdbc, """
				insert into public.billing_events (stripe_event_id, type, attempts)
				values (:id, :type, 1)
				on conflict (stripe_event_id) do update
				   set attempts = public.billing_events.attempts + 1
				""", Map.of("id", stripeEventId, "type", type));
	}

	public void finishEvent(String stripeEventId, String error) {
		beginWrite();
		Map<String, Object> params = new java.util.LinkedHashMap<>();
		params.put("id", stripeEventId);
		params.put("error", error);
		DbRows.update(jdbc, """
				update public.billing_events
				   set processed_at = case when :error is null then now() else processed_at end,
				       error = :error
				 where stripe_event_id = :id
				""", params);
	}
}
