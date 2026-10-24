package com.storyverse.api.admin;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthzService;
import com.storyverse.api.auth.SessionRepository;
import com.storyverse.api.common.DbSession;

/** Sanções: cache curto para o login, aplicação/revogação e listas do painel. */
@Service
public class SanctionService {

	private static final Duration CACHE_TTL = Duration.ofSeconds(30);
	private static final int MAX_PAGE = 50;
	private static final int MAX_REASON = 500;
	private static final Pattern UUID_FORMAT = Pattern.compile(
			"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
	private static final Set<String> KINDS = Set.of("warning", "suspension", "ban");
	private static final Set<String> STATUSES = Set.of("active", "revoked", "all");

	private record Cached(Sanction blocking, Sanction warning, long at) {
	}

	private final SanctionRepository repository;
	private final AuthzService authz;
	private final SessionRepository sessions;
	private final JdbcClient jdbc;
	private final ConcurrentHashMap<UUID, Cached> cache = new ConcurrentHashMap<>();

	public SanctionService(SanctionRepository repository, AuthzService authz, SessionRepository sessions,
			JdbcClient jdbc) {
		this.repository = repository;
		this.authz = authz;
		this.sessions = sessions;
		this.jdbc = jdbc;
	}

	/** Sanção bloqueante ativa (suspensão/banimento), com cache de 30 s. */
	@Transactional(readOnly = true)
	public Sanction blocking(UUID userId) {
		return load(userId).blocking();
	}

	/** Aviso ativo (não bloqueia), com o mesmo cache. */
	@Transactional(readOnly = true)
	public Sanction warning(UUID userId) {
		return load(userId).warning();
	}

	public void invalidate(UUID userId) {
		cache.remove(userId);
	}

	private Cached load(UUID userId) {
		Cached current = cache.get(userId);
		if (current != null && System.currentTimeMillis() - current.at() < CACHE_TTL.toMillis()) {
			return current;
		}
		DbSession.setUser(jdbc, userId);
		Cached fresh = new Cached(
				repository.activeBlocking(userId).orElse(null),
				repository.activeWarning(userId).orElse(null),
				System.currentTimeMillis());
		cache.put(userId, fresh);
		return fresh;
	}

	@Transactional(readOnly = true)
	public List<Sanction> history(UUID actorId, UUID userId, int limit) {
		authz.requireStaff(actorId);
		DbSession.setUser(jdbc, actorId);
		return repository.history(userId, clamp(limit));
	}

	@Transactional(readOnly = true)
	public SanctionPage list(UUID actorId, String status, String kind, String query, String cursor, int limit) {
		authz.requireStaff(actorId);
		String state = status == null || status.isBlank() ? "active" : status.strip();
		if (!STATUSES.contains(state)) {
			throw new AuthException(400, "Status de sanção inválido.");
		}
		String kindFilter = kind == null || kind.isBlank() ? null : kind.strip();
		if (kindFilter != null && !KINDS.contains(kindFilter)) {
			throw new AuthException(400, "Tipo de sanção inválido.");
		}
		String q = query == null ? "" : query.strip();
		UUID userIdFilter = null;
		String pattern = null;
		if (!q.isEmpty()) {
			if (UUID_FORMAT.matcher(q).matches()) {
				userIdFilter = UUID.fromString(q);
			} else if (q.length() >= 3) {
				pattern = "%" + AdminUserRepository.escapeLike(q) + "%";
			} else {
				throw new AuthException(400, "Busque por UUID, e-mail ou ao menos 3 letras do nome.");
			}
		}
		DbSession.setUser(jdbc, actorId);
		int pageSize = clamp(limit);
		List<Sanction> rows = repository.list(state, kindFilter, userIdFilter, pattern,
				AdminCursor.decode(cursor), pageSize + 1);
		String next = null;
		if (rows.size() > pageSize) {
			rows = rows.subList(0, pageSize);
			Sanction last = rows.get(rows.size() - 1);
			next = AdminCursor.from(Instant.ofEpochMilli(last.createdAt().toEpochMilli()), last.id()).encode();
		}
		return new SanctionPage(rows, next);
	}

	@Transactional
	public Sanction apply(UUID actorId, UUID userId, String kind, String reason, Instant expiresAt) {
		authz.requireRole(actorId, AuthzService.SUPERADMIN);
		if (actorId.equals(userId)) {
			throw new AuthException(403, "Você não pode aplicar sanção na própria conta.");
		}
		DbSession.setUser(jdbc, actorId);
		if (!repository.userExists(userId)) {
			throw new AuthException(404, "Conta não encontrada.");
		}
		if (repository.hasAnyRole(userId)) {
			throw new AuthException(403, "Contas com papel de staff não recebem sanção pelo painel.");
		}
		String type = kind == null ? "" : kind.strip();
		if (!KINDS.contains(type)) {
			throw new AuthException(400, "Tipo de sanção inválido.");
		}
		String text = reason == null ? "" : reason.strip();
		if (text.isEmpty()) {
			throw new AuthException(400, "Informe o motivo da sanção.");
		}
		if (text.length() > MAX_REASON) {
			throw new AuthException(400, "Motivo grande demais.");
		}
		Instant now = Instant.now();
		if ("suspension".equals(type)) {
			if (expiresAt == null || !expiresAt.isAfter(now)) {
				throw new AuthException(400, "A suspensão precisa de um prazo futuro.");
			}
		} else if ("ban".equals(type)) {
			if (expiresAt != null) {
				throw new AuthException(400, "O banimento não tem prazo.");
			}
		} else if (expiresAt != null && !expiresAt.isAfter(now)) {
			throw new AuthException(400, "O prazo do aviso precisa estar no futuro.");
		}

		Sanction created = repository.insert(userId, type, text, expiresAt, actorId);
		if (!"warning".equals(type)) {
			sessions.revokeAllForUser(userId);
		}
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("kind", type);
		metadata.put("reason", text);
		if (expiresAt != null) {
			metadata.put("expiresAt", expiresAt.toString());
		}
		authz.audit(actorId, "sanction.create", "user", userId.toString(), metadata);
		invalidate(userId);
		return created;
	}

	@Transactional
	public Sanction revoke(UUID actorId, UUID sanctionId, String reason) {
		authz.requireRole(actorId, AuthzService.SUPERADMIN);
		DbSession.setUser(jdbc, actorId);
		Sanction existing = repository.findById(sanctionId)
				.orElseThrow(() -> new AuthException(404, "Sanção não encontrada."));
		if (existing.revokedAt() != null) {
			throw new AuthException(400, "Esta sanção já foi revogada.");
		}
		repository.revoke(sanctionId, actorId);
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("kind", existing.kind());
		if (reason != null && !reason.isBlank()) {
			metadata.put("reason", reason.strip());
		}
		authz.audit(actorId, "sanction.revoke", "user", existing.userId().toString(), metadata);
		invalidate(existing.userId());
		return repository.findById(sanctionId).orElseThrow();
	}

	private static int clamp(int limit) {
		return Math.min(Math.max(limit, 1), MAX_PAGE);
	}
}
