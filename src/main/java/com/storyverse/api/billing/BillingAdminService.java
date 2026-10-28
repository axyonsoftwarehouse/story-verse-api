package com.storyverse.api.billing;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.admin.AdminRange;
import com.storyverse.api.admin.EmailMask;
import com.storyverse.api.admin.ShortCache;
import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.AuthzService;
import com.storyverse.api.common.DbSession;

/**
 * Financeiro do painel. Só superadmin; nenhuma ação de cobrança pelo painel. A única escrita é o
 * acesso grátis (billing_grants) para equipe, família e testes, que nunca gera cobrança.
 */
@Service
public class BillingAdminService {

	private final BillingRepository repository;
	private final AuthService auth;
	private final AuthzService authz;
	private final JdbcClient jdbc;
	private final ShortCache cache = new ShortCache(Duration.ofMinutes(5));

	public BillingAdminService(BillingRepository repository, AuthService auth, AuthzService authz, JdbcClient jdbc) {
		this.repository = repository;
		this.auth = auth;
		this.authz = authz;
		this.jdbc = jdbc;
	}

	// A leitura grava auditoria (authz.audit): transação somente-leitura recusaria o INSERT.
	@Transactional
	public Map<String, Object> summary(String token, String fromRaw, String toRaw) {
		UUID actor = superadmin(token);
		Instant to = toRaw == null || toRaw.isBlank() ? Instant.now() : instant(toRaw);
		Instant from = fromRaw == null || fromRaw.isBlank() ? to.minusSeconds(30L * 86_400) : instant(fromRaw);
		if (from.isAfter(to)) throw new AuthException(400, "Período inválido.");
		Map<String, Object> summary = new java.util.LinkedHashMap<>(repository.adminSummary(from, to));
		summary.putAll(repository.adminTrials(from, to));
		summary.put("from", from.toString());
		summary.put("to", to.toString());
		authz.audit(actor, "billing.summary", "billing", "summary",
				Map.of("from", from.toString(), "to", to.toString()));
		return summary;
	}

	/** Série por dia/semana; mesmas regras das métricas (90 dias, São Paulo, cache de 5 minutos). */
	@Transactional
	public List<BillingSeriesPoint> series(String token, String fromRaw, String toRaw, String groupRaw) {
		UUID actor = superadmin(token);
		AdminRange range = AdminRange.parse(fromRaw, toRaw);
		String group = AdminRange.group(groupRaw);
		authz.audit(actor, "billing.series", "billing", "series",
				Map.of("from", range.from().toString(), "to", range.to().toString(), "group", group));
		return cache.get("series|" + group + "|" + range.from() + "|" + range.to(),
				() -> repository.adminSeries(range.from(), range.to(), group).stream()
						.map(BillingAdminService::point).toList());
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> subscriptions(String token, String status, String q) {
		superadmin(token);
		return repository.adminSubscriptions(status, q).stream().map(BillingAdminService::mask).toList();
	}

	@Transactional
	public Map<String, Object> subscription(String token, UUID id) {
		UUID actor = superadmin(token);
		Map<String, Object> row = repository.adminSubscription(id)
				.orElseThrow(() -> new AuthException(404, "Assinatura não encontrada."));
		authz.audit(actor, "billing.view", "billing_subscription", id.toString(), Map.of());
		return mask(row);
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> grants(String token, UUID userId) {
		superadmin(token);
		return repository.grants(userId);
	}

	/** Acesso grátis manual. Só um ativo por conta: retire o atual antes de dar outro. */
	@Transactional
	public Map<String, Object> grant(String token, UUID userId, String reason, String expiresAtRaw) {
		UUID actor = superadmin(token);
		if (userId == null) throw new AuthException(400, "Informe a conta.");
		String motivo = reason == null ? "" : reason.strip();
		if (motivo.isEmpty()) throw new AuthException(400, "Informe o motivo.");
		if (motivo.length() > 200) throw new AuthException(400, "Motivo longo demais (até 200 caracteres).");
		Instant expiresAt = expiresAtRaw == null || expiresAtRaw.isBlank() ? null : instant(expiresAtRaw);
		if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
			throw new AuthException(400, "A validade precisa ser uma data futura.");
		}
		if (repository.email(userId) == null) throw new AuthException(404, "Conta não encontrada.");
		if (repository.activeGrant(userId).isPresent()) {
			throw new AuthException(409, "Esta conta já tem acesso grátis ativo. Retire o atual antes.");
		}
		Map<String, Object> grant = repository.createGrant(userId, motivo, expiresAt, actor);
		authz.audit(actor, "billing.grant", "user", userId.toString(),
				Map.of("reason", motivo, "expiresAt", expiresAt == null ? "" : expiresAt.toString()));
		return grant;
	}

	@Transactional
	public void revokeGrant(String token, UUID grantId) {
		UUID actor = superadmin(token);
		if (repository.revokeGrant(grantId, actor) == 0) {
			throw new AuthException(404, "Acesso não encontrado ou já retirado.");
		}
		authz.audit(actor, "billing.grant.revoke", "billing_grant", grantId.toString(), Map.of());
	}

	private static BillingSeriesPoint point(Map<String, Object> row) {
		return new BillingSeriesPoint(LocalDate.parse(String.valueOf(row.get("bucket"))),
				number(row.get("mrr_cents")), number(row.get("active")), number(row.get("new_count")),
				number(row.get("canceled_count")), number(row.get("revenue_cents")),
				Boolean.TRUE.equals(row.get("estimated")));
	}

	private static long number(Object value) {
		return value instanceof Number number ? number.longValue() : 0L;
	}

	private UUID superadmin(String token) {
		UUID actor = auth.authenticate(token);
		authz.requireRole(actor, AuthzService.SUPERADMIN);
		DbSession.setUser(jdbc, actor);
		return actor;
	}

	private static Map<String, Object> mask(Map<String, Object> row) {
		Object email = row.remove("email");
		row.put("emailMasked", EmailMask.mask(email == null ? null : String.valueOf(email)));
		return row;
	}

	private static Instant instant(String value) {
		try {
			return Instant.parse(value);
		} catch (RuntimeException exception) {
			try {
				return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
			} catch (RuntimeException ignored) {
				throw new AuthException(400, "Data inválida.");
			}
		}
	}
}
