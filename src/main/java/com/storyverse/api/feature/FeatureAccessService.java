package com.storyverse.api.feature;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.common.DbSession;

/**
 * Feature gate por conta, aplicado no servidor. A UI recebe as features pelas capabilities, mas
 * qualquer endpoint protegido deve chamar {@link #require(UUID, String)}.
 */
@Service
public class FeatureAccessService {

	/** Features que podem ser concedidas hoje. Ao criar outra, inclua aqui. */
	private static final Set<String> KNOWN = Set.of("library");

	private final JdbcClient jdbc;

	public FeatureAccessService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public List<String> features(UUID userId) {
		return features(userId, userId);
	}

	/** Features de {@code userId} vistas por {@code viewerId}: o RLS avalia quem consulta, não o alvo. */
	@Transactional(readOnly = true)
	public List<String> features(UUID viewerId, UUID userId) {
		DbSession.setUser(jdbc, viewerId);
		return jdbc.sql("""
				select feature from public.feature_access
				 where user_id = :uid and enabled
				 order by feature
				""").param("uid", userId).query(String.class).list();
	}

	@Transactional(readOnly = true)
	public boolean enabled(UUID userId, String feature) {
		validateKnown(feature);
		DbSession.setUser(jdbc, userId);
		return Boolean.TRUE.equals(jdbc.sql("""
				select exists(select 1 from public.feature_access
				               where user_id = :uid and feature = :feature and enabled)
				""").param("uid", userId).param("feature", feature).query(Boolean.class).single());
	}

	@Transactional(readOnly = true)
	public void require(UUID userId, String feature) {
		if (!enabled(userId, feature)) {
			throw new AuthException(403, "Recurso indisponível para esta conta.", "FEATURE_DISABLED");
		}
	}

	@Transactional
	public void set(UUID actorId, UUID targetUserId, String feature, boolean enabled) {
		validateKnown(feature);
		DbSession.setUser(jdbc, actorId);
		jdbc.sql("""
				insert into public.feature_access
					(user_id, feature, enabled, granted_by, granted_at, updated_at)
				values (:target, :feature, :enabled, :actor, now(), now())
				on conflict (user_id, feature) do update
				   set enabled = excluded.enabled,
				       granted_by = excluded.granted_by,
				       updated_at = now()
				""")
				.param("target", targetUserId)
				.param("feature", feature)
				.param("enabled", enabled)
				.param("actor", actorId)
				.update();
	}

	private static void validateKnown(String feature) {
		if (feature == null || !KNOWN.contains(feature)) {
			throw new AuthException(400, "Feature inválida.");
		}
	}
}
