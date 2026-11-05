package com.storyverse.api.social;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.common.DbSession;
import com.storyverse.api.common.RateLimitGate;
import com.storyverse.api.feature.FeatureAccessService;
import com.storyverse.api.groups.GroupStream;

/**
 * Rede social: seguir perfis e ver quem segue quem. Faz parte da área de comunidade, então usa a
 * mesma feature dos grupos ({@code groups}). Seguir é de mão única; seguir de volta vira "conexão".
 */
@Service
public class SocialService {

	static final String FEATURE = "groups";
	/** Teto de perfis seguidos por conta (bem acima do uso normal; segura robô). */
	static final int MAX_FOLLOWING = 2000;
	static final int MIN_QUERY = 2;
	static final int MAX_QUERY = 60;

	private final SocialRepository repository;
	private final AuthService auth;
	private final FeatureAccessService features;
	private final JdbcClient jdbc;
	private final RateLimitGate limits;
	private final GroupStream stream;

	public SocialService(SocialRepository repository, AuthService auth, FeatureAccessService features, JdbcClient jdbc,
			RateLimitGate limits, GroupStream stream) {
		this.repository = repository;
		this.auth = auth;
		this.features = features;
		this.jdbc = jdbc;
		this.limits = limits;
		this.stream = stream;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> profile(String token, UUID userId) {
		UUID viewer = requireUser(token);
		Map<String, Object> profile = repository.profile(viewer, userId)
				.orElseThrow(() -> new AuthException(404, "Perfil não encontrado."));
		Map<String, Object> out = new LinkedHashMap<>(profile);
		out.put("is_me", viewer.equals(userId));
		return out;
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> followers(String token, UUID userId, Integer limit, Integer offset) {
		UUID viewer = requireUser(token);
		requireExists(userId);
		return repository.followers(viewer, userId, limit(limit), offset(offset));
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> following(String token, UUID userId, Integer limit, Integer offset) {
		UUID viewer = requireUser(token);
		requireExists(userId);
		return repository.following(viewer, userId, limit(limit), offset(offset));
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> search(String token, String query) {
		UUID viewer = requireUser(token);
		String q = query == null ? "" : query.trim();
		if (q.length() < MIN_QUERY) return List.of();
		if (q.length() > MAX_QUERY) q = q.substring(0, MAX_QUERY);
		limits.check("people-search:" + viewer, 60, Duration.ofMinutes(1));
		return repository.search(viewer, q, 20);
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> suggestions(String token) {
		UUID viewer = requireUser(token);
		return repository.suggestions(viewer, 12);
	}

	/** Seguir (idempotente). Devolve o perfil atualizado. */
	@Transactional
	public Map<String, Object> follow(String token, UUID userId) {
		UUID viewer = requireUser(token);
		if (viewer.equals(userId)) throw new AuthException(400, "Você não pode seguir o próprio perfil.");
		requireExists(userId);
		limits.check("follow:" + viewer, 60, Duration.ofHours(1));
		if (repository.followingCount(viewer) >= MAX_FOLLOWING) {
			throw new AuthException(400, "Você chegou ao limite de " + MAX_FOLLOWING + " perfis seguidos.");
		}
		if (repository.follow(viewer, userId)) notifyFollow(userId);
		return profile(viewer, userId);
	}

	@Transactional
	public Map<String, Object> unfollow(String token, UUID userId) {
		UUID viewer = requireUser(token);
		requireExists(userId);
		repository.unfollow(viewer, userId);
		return profile(viewer, userId);
	}

	/** Tira alguém dos próprios seguidores (a pessoa não é avisada). */
	@Transactional
	public void removeFollower(String token, UUID followerId) {
		UUID viewer = requireUser(token);
		repository.unfollow(followerId, viewer);
	}

	private Map<String, Object> profile(UUID viewer, UUID userId) {
		Map<String, Object> out = new LinkedHashMap<>(repository.profile(viewer, userId)
				.orElseThrow(() -> new AuthException(404, "Perfil não encontrado.")));
		out.put("is_me", false);
		return out;
	}

	/** Aviso no sininho e cutucada no app da pessoa, só depois do commit. */
	private void notifyFollow(UUID userId) {
		try {
			if (!repository.notifyFollow(userId)) return;
		} catch (RuntimeException exception) {
			// Aviso é extra: nunca impede seguir.
			return;
		}
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					stream.publish(List.of(userId), "notification", Map.of("kind", "follow"));
				}
			});
		} else {
			stream.publish(List.of(userId), "notification", Map.of("kind", "follow"));
		}
	}

	private void requireExists(UUID userId) {
		if (userId == null || !repository.exists(userId)) throw new AuthException(404, "Perfil não encontrado.");
	}

	private UUID requireUser(String token) {
		UUID id = auth.authenticate(token);
		DbSession.setUser(jdbc, id);
		features.require(id, FEATURE);
		DbSession.setUser(jdbc, id);
		return id;
	}

	private static int limit(Integer value) {
		return value == null ? 30 : Math.max(1, Math.min(100, value));
	}

	private static int offset(Integer value) {
		return value == null ? 0 : Math.max(0, Math.min(100_000, value));
	}
}
