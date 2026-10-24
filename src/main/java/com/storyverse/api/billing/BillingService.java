package com.storyverse.api.billing;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthzService;
import com.storyverse.api.common.DbSession;

/**
 * Regra de acesso da assinatura. O servidor é a fonte da verdade; a UI só reflete o que vier
 * de `/billing/status` e das capabilities.
 */
@Service
public class BillingService {

	public record Access(boolean active, String reason, String accessUntil) {
	}

	private final BillingRepository repository;
	private final AuthzService authz;
	private final JdbcClient jdbc;
	private final StripeGateway stripe;
	private final boolean enabled;
	private final String priceId;
	private final String priceLabel;
	private final int trialDays;
	private final int graceDays;

	public BillingService(BillingRepository repository, AuthzService authz, JdbcClient jdbc,
			StripeGateway stripe,
			@Value("${app.billing.enabled:false}") boolean enabled,
			@Value("${app.billing.price-id:}") String priceId,
			@Value("${app.billing.price-label:R$ 9,90}") String priceLabel,
			@Value("${app.billing.trial-days:7}") int trialDays,
			@Value("${app.billing.past-due-grace-days:3}") int graceDays) {
		this.repository = repository;
		this.authz = authz;
		this.jdbc = jdbc;
		this.stripe = stripe;
		this.enabled = enabled;
		this.priceId = priceId;
		this.priceLabel = priceLabel;
		this.trialDays = trialDays;
		this.graceDays = graceDays;
	}

	public boolean enabled() {
		return enabled;
	}

	/** Plano público: sem segredo, usado por `/assinar` antes do checkout. */
	public Map<String, Object> plans() {
		Map<String, Object> plan = new LinkedHashMap<>();
		plan.put("id", "storyverse");
		plan.put("name", "Storyverse");
		plan.put("priceLabel", priceLabel);
		plan.put("currency", "BRL");
		plan.put("interval", "month");
		plan.put("trialDays", trialDays);
		return Map.of("plans", java.util.List.of(plan));
	}

	@Transactional(readOnly = true)
	public Access access(UUID userId) {
		if (!enabled) return new Access(true, "disabled", null);
		asUser(userId);
		if (authz.isStaff(userId)) return new Access(true, "staff", null);
		Map<String, Object> grant = repository.activeGrant(userId).orElse(null);
		if (grant != null) {
			return new Access(true, "grant", iso(grant.get("expires_at")));
		}
		Map<String, Object> subscription = repository.latestSubscription(userId).orElse(null);
		if (subscription == null) return new Access(false, "none", null);
		return evaluate(subscription, graceDays);
	}

	public void requireAccess(UUID userId) {
		if (enabled && !access(userId).active()) {
			throw new AuthException(402, "Assinatura necessária.", "SUBSCRIPTION_REQUIRED");
		}
	}

	@Transactional(readOnly = true)
	public Map<String, Object> status(UUID userId) {
		asUser(userId);
		Map<String, Object> row = repository.latestSubscription(userId).orElse(null);
		Access access = access(userId);
		Map<String, Object> view = new LinkedHashMap<>();
		view.put("status", row == null ? "none" : row.get("status"));
		view.put("active", access.active());
		view.put("reason", access.reason());
		view.put("accessUntil", access.accessUntil());
		view.put("plan", priceLabel);
		view.put("priceId", priceId);
		view.put("currency", "BRL");
		view.put("interval", "month");
		view.put("trialDays", trialDays);
		view.put("cancelAtPeriodEnd", row != null && Boolean.TRUE.equals(row.get("cancel_at_period_end")));
		view.put("trialEnd", row == null ? null : iso(row.get("trial_end")));
		view.put("portalAvailable", repository.customer(userId).isPresent());
		return view;
	}

	/** Bloco que vai dentro de `/auth/capabilities`. */
	@Transactional(readOnly = true)
	public Map<String, Object> capabilities(UUID userId) {
		Map<String, Object> status = status(userId);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("status", status.get("status"));
		out.put("active", status.get("active"));
		out.put("accessUntil", status.get("accessUntil"));
		out.put("plan", status.get("plan"));
		out.put("cancelAtPeriodEnd", status.get("cancelAtPeriodEnd"));
		return out;
	}

	/** Abre o Checkout do Stripe para a conta; cria o cliente na primeira vez. */
	public Map<String, Object> checkout(UUID userId) {
		requireConfigured();
		asUser(userId);
		Map<String, Object> subscription = repository.latestSubscription(userId).orElse(null);
		String status = subscription == null ? "" : String.valueOf(subscription.get("status"));
		if ("active".equals(status) || "trialing".equals(status)) {
			throw new AuthException(409, "Esta conta já tem uma assinatura em andamento.");
		}
		Map<String, Object> customer = repository.customer(userId).orElse(null);
		String customerId;
		if (customer == null) {
			customerId = stripe.createCustomer(repository.email(userId), userId.toString());
			repository.upsertCustomer(userId, customerId);
		} else {
			customerId = String.valueOf(customer.get("stripe_customer_id"));
		}
		StripeGateway.Checkout checkout = stripe.checkout(customerId, userId.toString());
		return Map.of("url", checkout.url());
	}

	/** Portal do cliente: cartão, faturas, cancelamento e recibo. */
	public Map<String, Object> portal(UUID userId) {
		requireConfigured();
		asUser(userId);
		Map<String, Object> customer = repository.customer(userId)
				.orElseThrow(() -> new AuthException(409, "Esta conta ainda não tem cadastro no Stripe."));
		String url = stripe.portal(String.valueOf(customer.get("stripe_customer_id")));
		return Map.of("url", url);
	}

	private void requireConfigured() {
		if (!enabled) throw new AuthException(503, "Assinatura ainda não está disponível.");
		if (!stripe.configured()) throw new AuthException(503, "Assinatura ainda não está disponível.");
	}

	/** Dá acesso manual (staff/teste/fundador). Só superadmin. */
	@Transactional
	public Map<String, Object> grant(UUID actor, UUID userId, String reason, Instant expiresAt) {
		authz.requireRole(actor, AuthzService.SUPERADMIN);
		if (reason == null || reason.isBlank()) throw new AuthException(400, "Informe o motivo.");
		Map<String, Object> grant = repository.createGrant(userId, reason.strip(), expiresAt, actor);
		authz.audit(actor, "billing.grant", "user", userId.toString(),
				Map.of("reason", reason, "expiresAt", expiresAt == null ? "" : expiresAt.toString()));
		return grant;
	}

	@Transactional
	public void revokeGrant(UUID actor, UUID grantId) {
		authz.requireRole(actor, AuthzService.SUPERADMIN);
		repository.revokeGrant(grantId, actor);
		authz.audit(actor, "billing.grant.revoke", "billing_grant", grantId.toString(), Map.of());
	}

	private void asUser(UUID userId) {
		DbSession.setUser(jdbc, userId);
	}

	static Access evaluate(Map<String, Object> subscription, int graceDays) {		String status = String.valueOf(subscription.get("status"));
		Instant now = Instant.now();
		Instant periodEnd = instant(subscription.get("current_period_end"));
		Instant trialEnd = instant(subscription.get("trial_end"));
		switch (status) {
			case "trialing" -> {
				Instant until = trialEnd != null ? trialEnd : periodEnd;
				return new Access(until == null || now.isBefore(until), "trial", iso(until));
			}
			case "active" -> {
				return new Access(periodEnd == null || now.isBefore(periodEnd), "active", iso(periodEnd));
			}
			case "past_due" -> {
				Instant until = periodEnd == null ? null : periodEnd.plus(Duration.ofDays(graceDays));
				return new Access(until != null && now.isBefore(until), "past_due", iso(until));
			}
			case "canceled" -> {
				return new Access(periodEnd != null && now.isBefore(periodEnd), "canceled", iso(periodEnd));
			}
			default -> {
				return new Access(false, status, null);
			}
		}
	}

	private static String iso(Object value) {
		Instant instant = instant(value);
		return instant == null ? null : instant.toString();
	}

	private static Instant instant(Object value) {
		if (value == null) return null;
		if (value instanceof Instant instant) return instant;
		if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
		if (value instanceof String text) {
			try {
				return Instant.parse(text);
			} catch (RuntimeException exception) {
				return null;
			}
		}
		return null;
	}
}
