package com.storyverse.api.social;

import java.time.Duration;
import java.util.ArrayList;
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

import tools.jackson.databind.json.JsonMapper;

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
	static final int MAX_BADGES = 60;
	static final int PROFILE_BOOKS = 12;
	/** Números que a vitrine aceita (o resto é ignorado). */
	static final List<String> STATS = List.of("totalMinutes", "bestStreak", "finishedBooks", "messagesSent");
	private static final JsonMapper JSON = JsonMapper.builder().build();

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
		withShowcase(out, userId);
		return out;
	}

	/** Leituras, conquistas e presentes; tudo vazio (e {@code reading_hidden}) se a pessoa escondeu. */
	private void withShowcase(Map<String, Object> out, UUID userId) {
		Map<String, Object> showcase = repository.showcase(userId).orElse(null);
		boolean visible = repository.readingVisible(userId);
		out.put("show_reading", showcase == null || Boolean.TRUE.equals(showcase.get("show_reading")));
		out.put("reading_hidden", !visible);
		if (!visible) {
			out.put("books", List.of());
			out.put("reading_counts", Map.of("reading", 0, "finished", 0));
			out.put("gifts", List.of());
			out.put("badges", List.of());
			out.put("badges_total", 0);
			out.put("stats", Map.of());
			return;
		}
		out.put("books", repository.books(userId, PROFILE_BOOKS, 0));
		out.put("reading_counts", repository.readingCounts(userId));
		out.put("gifts", repository.gifts(userId));
		out.put("badges", showcase == null ? List.of() : parse(showcase.get("badges"), List.class, List.of()));
		out.put("badges_total", showcase == null ? 0 : showcase.get("badges_total"));
		out.put("stats", showcase == null ? Map.of() : parse(showcase.get("stats"), Map.class, Map.of()));
	}

	/** Mais livros do perfil ("Ver todos"). Vazio se a pessoa escondeu as leituras. */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> books(String token, UUID userId, Integer limit, Integer offset) {
		requireUser(token);
		requireExists(userId);
		return repository.books(userId, Math.max(1, Math.min(60, limit == null ? 24 : limit)), offset(offset));
	}

	/**
	 * O app publica as conquistas desbloqueadas ({@code badges}: id, emoji, título), o total de
	 * conquistas que existem e alguns números de leitura. Campos fora do formato são descartados.
	 */
	@Transactional
	public void saveShowcase(String token, Map<String, Object> body) {
		UUID viewer = requireUser(token);
		limits.check("showcase:" + viewer, 30, Duration.ofHours(1));
		List<Map<String, Object>> badges = new ArrayList<>();
		if (body.get("badges") instanceof List<?> list) {
			for (Object item : list) {
				if (badges.size() >= MAX_BADGES) break;
				if (!(item instanceof Map<?, ?> m)) continue;
				String id = clip(m.get("id"), 40), emoji = clip(m.get("emoji"), 16), title = clip(m.get("title"), 60);
				if (id == null || emoji == null || title == null) continue;
				Map<String, Object> badge = new LinkedHashMap<>();
				badge.put("id", id);
				badge.put("emoji", emoji);
				badge.put("title", title);
				String description = clip(m.get("description"), 120);
				if (description != null) badge.put("description", description);
				badges.add(badge);
			}
		}
		int total = Math.max(badges.size(), Math.min(200, Math.max(0, intValue(body.get("total")))));
		Map<String, Object> stats = new LinkedHashMap<>();
		if (body.get("stats") instanceof Map<?, ?> m) {
			for (String key : STATS) {
				int value = intValue(m.get(key));
				if (value > 0) stats.put(key, Math.min(value, 10_000_000));
			}
		}
		repository.saveShowcase(viewer, JSON.writeValueAsString(badges), total, JSON.writeValueAsString(stats));
	}

	/** Mostrar ou esconder leituras, conquistas e presentes no perfil. */
	@Transactional
	public Map<String, Object> savePrivacy(String token, Map<String, Object> body) {
		UUID viewer = requireUser(token);
		if (!(body.get("showReading") instanceof Boolean show)) throw new AuthException(400, "Informe showReading.");
		repository.saveShowReading(viewer, show);
		return Map.of("show_reading", show);
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

	@SuppressWarnings("unchecked")
	private static <T> T parse(Object json, Class<?> type, T fallback) {
		if (json == null) return fallback;
		try {
			return (T) JSON.readValue(String.valueOf(json), type);
		} catch (RuntimeException error) {
			return fallback;
		}
	}

	private static String clip(Object value, int max) {
		if (value == null) return null;
		String text = String.valueOf(value).trim();
		if (text.isEmpty()) return null;
		return text.length() > max ? text.substring(0, max) : text;
	}

	private static int intValue(Object value) {
		if (value instanceof Number n) return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, n.longValue()));
		try {
			return value == null ? 0 : Integer.parseInt(String.valueOf(value).trim());
		} catch (NumberFormatException error) {
			return 0;
		}
	}

	private static int limit(Integer value) {
		return value == null ? 30 : Math.max(1, Math.min(100, value));
	}

	private static int offset(Integer value) {
		return value == null ? 0 : Math.max(0, Math.min(100_000, value));
	}
}
