package com.storyverse.api.admin;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.AuthzService;
import com.storyverse.api.common.DbSession;
import com.storyverse.api.common.RateLimitGate;
import com.storyverse.api.feature.FeatureAccessService;

/** Contas no painel: busca e detalhe (superadmin ou support). */
@Service
public class AdminUserService {

	private static final Pattern UUID_FORMAT = Pattern.compile(
			"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
	private static final int SEARCHES_PER_MINUTE = 30;
	private static final int MIN_NAME_LENGTH = 3;

	private final AdminUserRepository repository;
	private final AuthService auth;
	private final AuthzService authz;
	private final FeatureAccessService features;
	private final JdbcClient jdbc;
	private final RateLimitGate limits;

	public AdminUserService(AdminUserRepository repository, AuthService auth, AuthzService authz,
			FeatureAccessService features, JdbcClient jdbc, RateLimitGate limits) {
		this.repository = repository;
		this.auth = auth;
		this.authz = authz;
		this.features = features;
		this.jdbc = jdbc;
		this.limits = limits;
	}

	/** Superadmin ou support. As outras ações sobre contas usam a mesma checagem. */
	public void requireAccountsAccess(UUID actor) {
		authz.requireRole(actor, AuthzService.SUPERADMIN, AuthzService.SUPPORT);
	}

	@Transactional(readOnly = true)
	public List<AdminUserSummary> search(String token, String query) {
		UUID actor = auth.authenticate(token);
		requireAccountsAccess(actor);
		limits.check("admin-user-search:" + actor, SEARCHES_PER_MINUTE, Duration.ofMinutes(1));
		String q = query == null ? "" : query.strip();
		DbSession.setUser(jdbc, actor);
		if (UUID_FORMAT.matcher(q).matches()) {
			return repository.searchById(UUID.fromString(q));
		}
		if (q.contains("@")) {
			return repository.searchByEmail(q);
		}
		if (q.length() >= MIN_NAME_LENGTH) {
			return repository.searchByName(q);
		}
		throw new AuthException(400, "Busque por UUID, e-mail completo ou ao menos 3 letras do nome.");
	}

	/** Abrir o detalhe é auditado ({@code user.view}) na mesma transação. */
	@Transactional
	public AdminUserDetail detail(String token, UUID id) {
		UUID actor = auth.authenticate(token);
		requireAccountsAccess(actor);
		DbSession.setUser(jdbc, actor);
		AdminUserDetail row = repository.detail(id)
				.orElseThrow(() -> new AuthException(404, "Conta não encontrada."));
		List<String> targetFeatures = features.features(actor, id);
		List<Map<String, Object>> audit = authz.hasRole(actor, AuthzService.SUPERADMIN)
				? repository.recentAudit(id)
				: null;
		authz.audit(actor, "user.view", "user", id.toString(), Map.of());
		return new AdminUserDetail(row.id(), row.name(), row.email(), row.emailConfirmed(), row.createdAt(),
				row.lastSignInAt(), row.roles(), targetFeatures, row.activeSessions(), audit);
	}
}
