package com.storyverse.api.billing;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.admin.EmailMask;
import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.AuthzService;
import com.storyverse.api.common.DbSession;

/** Financeiro somente leitura. Só superadmin; nenhuma ação de cobrança pelo painel. */
@Service
public class BillingAdminService {

	private final BillingRepository repository;
	private final AuthService auth;
	private final AuthzService authz;
	private final JdbcClient jdbc;

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
		Map<String, Object> summary = repository.adminSummary(from, to);
		summary.put("from", from.toString());
		summary.put("to", to.toString());
		authz.audit(actor, "billing.summary", "billing", "summary",
				Map.of("from", from.toString(), "to", to.toString()));
		return summary;
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
