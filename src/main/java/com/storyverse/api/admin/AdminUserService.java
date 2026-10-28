package com.storyverse.api.admin;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

/** Contas no painel: busca, detalhe e sessões (superadmin ou support); papéis (só superadmin). */
@Service
public class AdminUserService {

	private static final Pattern UUID_FORMAT = Pattern.compile(
			"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
	private static final int SEARCHES_PER_MINUTE = 30;
	private static final int MIN_NAME_LENGTH = 3;
	private static final int MAX_PAGE_SIZE = 50;
	/** Pelo painel só estes papéis mudam; {@code superadmin} só pelo script na VPS. */
	private static final Set<String> PANEL_ROLES = Set.of(AuthzService.MODERATOR, AuthzService.SUPPORT);
	private static final Set<String> ACCOUNT_ROLES = Set.of(
			AuthzService.SUPERADMIN, AuthzService.MODERATOR, AuthzService.SUPPORT);

	private final AdminUserRepository repository;
	private final AuthService auth;
	private final AuthzService authz;
	private final FeatureAccessService features;
	private final SanctionService sanctions;
	private final JdbcClient jdbc;
	private final RateLimitGate limits;

	public AdminUserService(AdminUserRepository repository, AuthService auth, AuthzService authz,
			FeatureAccessService features, SanctionService sanctions, JdbcClient jdbc, RateLimitGate limits) {
		this.repository = repository;
		this.auth = auth;
		this.authz = authz;
		this.features = features;
		this.sanctions = sanctions;
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
		if (q.length() >= MIN_NAME_LENGTH) {
			return repository.searchByEmailOrName(q);
		}
		throw new AuthException(400, "Busque por UUID, e-mail ou ao menos 3 letras do nome.");
	}

	/** Bloco "contas recentes": sem cursor, no máximo 50. */
	@Transactional(readOnly = true)
	public List<AdminUserSummary> recent(String token, int limit) {
		UUID actor = auth.authenticate(token);
		requireAccountsAccess(actor);
		DbSession.setUser(jdbc, actor);
		return repository.recent(clamp(limit));
	}

	/** Lista filtrável com cursor. Sem filtros, devolve as contas mais recentes. */
	@Transactional(readOnly = true)
	public AdminUserPage list(String token, String query, String role, String feature, String cursor, int limit) {
		UUID actor = auth.authenticate(token);
		requireAccountsAccess(actor);
		limits.check("admin-user-list:" + actor, SEARCHES_PER_MINUTE, Duration.ofMinutes(1));
		DbSession.setUser(jdbc, actor);
		String q = query == null ? "" : query.strip();
		UUID id = null;
		String pattern = null;
		if (!q.isEmpty()) {
			if (UUID_FORMAT.matcher(q).matches()) {
				id = UUID.fromString(q);
			} else if (q.length() >= MIN_NAME_LENGTH) {
				pattern = "%" + AdminUserRepository.escapeLike(q) + "%";
			} else {
				throw new AuthException(400, "Busque por UUID, e-mail ou ao menos 3 letras do nome.");
			}
		}
		String roleFilter = blankToNull(role);
		if (roleFilter != null && !ACCOUNT_ROLES.contains(roleFilter)) {
			throw new AuthException(400, "Papel inválido para o filtro.");
		}
		int pageSize = clamp(limit);
		List<AdminUserSummary> rows = repository.list(
				id, pattern, roleFilter, blankToNull(feature), AdminCursor.decode(cursor), pageSize + 1);
		String next = null;
		if (rows.size() > pageSize) {
			rows = rows.subList(0, pageSize);
			AdminUserSummary last = rows.get(rows.size() - 1);
			next = AdminCursor.from(Instant.ofEpochMilli(last.createdAt().toEpochMilli()), last.id()).encode();
		}
		return new AdminUserPage(rows, next);
	}

	private static int clamp(int limit) {
		return Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
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
		List<Sanction> targetSanctions = sanctions.history(actor, id, 20);
		authz.audit(actor, "user.view", "user", id.toString(), Map.of());
		return new AdminUserDetail(row.id(), row.name(), row.email(), row.emailConfirmed(), row.createdAt(),
				row.lastSignInAt(), row.roles(), targetFeatures, row.activeSessions(), audit, targetSanctions);
	}

	/** Encerra as sessões ativas da conta e audita {@code user.sessions.revoke} na mesma transação. */
	@Transactional
	public Map<String, Object> revokeSessions(String token, UUID id) {
		UUID actor = auth.authenticate(token);
		requireAccountsAccess(actor);
		if (actor.equals(id)) {
			throw new AuthException(403, "Use \"Sair de todos os aparelhos\" na sua conta.");
		}
		DbSession.setUser(jdbc, actor);
		requireAccount(id);
		int revoked = repository.revokeActiveSessions(id);
		authz.audit(actor, "user.sessions.revoke", "user", id.toString(), Map.of("count", revoked));
		return Map.of("revoked", revoked);
	}

	/** Dá {@code moderator} ou {@code support}. Se a conta já tem o papel, não audita. */
	@Transactional
	public Map<String, Object> grantRole(String token, UUID id, String role) {
		UUID actor = requireRoleChange(token, id, role);
		if (!requireAccount(id)) {
			throw new AuthException(409, "A conta precisa confirmar o e-mail antes de receber um papel.");
		}
		if (repository.grantRole(id, role, actor) > 0) {
			authz.audit(actor, "role.grant", "user", id.toString(), Map.of("role", role));
		}
		return Map.of("roles", repository.roles(id));
	}

	/** Tira {@code moderator} ou {@code support}. Se a conta não tem o papel, não audita. */
	@Transactional
	public Map<String, Object> revokeRole(String token, UUID id, String role) {
		UUID actor = requireRoleChange(token, id, role);
		requireAccount(id);
		if (repository.revokeRole(id, role) > 0) {
			authz.audit(actor, "role.revoke", "user", id.toString(), Map.of("role", role));
		}
		return Map.of("roles", repository.roles(id));
	}

	/** Ordem: papel do ator, papel pedido (400), própria conta (403). Devolve o ator. */
	private UUID requireRoleChange(String token, UUID id, String role) {
		UUID actor = auth.authenticate(token);
		authz.requireRole(actor, AuthzService.SUPERADMIN);
		if (role == null || !PANEL_ROLES.contains(role)) {
			throw new AuthException(400, "Pelo painel só é possível dar ou tirar os papéis moderator e support.");
		}
		if (actor.equals(id)) {
			throw new AuthException(403, "Você não pode alterar os próprios papéis pelo painel.");
		}
		DbSession.setUser(jdbc, actor);
		return actor;
	}

	/** 404 se a conta não existe; devolve se o e-mail está confirmado. */
	private boolean requireAccount(UUID id) {
		return repository.emailConfirmed(id)
				.orElseThrow(() -> new AuthException(404, "Conta não encontrada."));
	}
}
