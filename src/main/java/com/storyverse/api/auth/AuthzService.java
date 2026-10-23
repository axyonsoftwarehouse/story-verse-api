package com.storyverse.api.auth;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.common.DbSession;

import tools.jackson.databind.json.JsonMapper;

/** Autorizacao por papel. Toda acao administrativa deve passar por aqui no servidor. */
@Service
public class AuthzService {

	public static final String SUPERADMIN = "superadmin";
	public static final String MODERATOR = "moderator";
	public static final String SUPPORT = "support";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final JdbcClient jdbc;

	public AuthzService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public Set<String> roles(UUID userId) {
		DbSession.setUser(jdbc, userId);
		return new LinkedHashSet<>(jdbc.sql("""
				select role from public.user_roles
				 where user_id = :id
				 order by role
				""").param("id", userId).query(String.class).list());
	}

	@Transactional(readOnly = true)
	public boolean hasRole(UUID userId, String role) {
		DbSession.setUser(jdbc, userId);
		return Boolean.TRUE.equals(jdbc.sql("select public.has_role(:role)")
				.param("role", role)
				.query((rs, rowNum) -> rs.getObject(1, Boolean.class))
				.optional()
				.orElse(false));
	}

	@Transactional(readOnly = true)
	public boolean isStaff(UUID userId) {
		DbSession.setUser(jdbc, userId);
		return Boolean.TRUE.equals(jdbc.sql("select public.is_staff()")
				.query((rs, rowNum) -> rs.getObject(1, Boolean.class))
				.optional()
				.orElse(false));
	}

	@Transactional(readOnly = true)
	public void requireRole(UUID userId, String... roles) {
		for (String role : roles) {
			if (hasRole(userId, role)) {
				return;
			}
		}
		throw new AuthException(403, "Sem permissao.");
	}

	@Transactional(readOnly = true)
	public void requireStaff(UUID userId) {
		if (!isStaff(userId)) {
			throw new AuthException(403, "Sem permissao.");
		}
	}

	@Transactional
	public void audit(UUID actorId, String action, String targetType, String targetId,
			Map<String, Object> metadata) {
		DbSession.setUser(jdbc, actorId);
		jdbc.sql("""
				insert into public.admin_audit_log (actor_id, action, target_type, target_id, metadata)
				values (:actor, :action, :targetType, :targetId, cast(:metadata as jsonb))
				""")
				.param("actor", actorId)
				.param("action", action)
				.param("targetType", targetType)
				.param("targetId", targetId)
				.param("metadata", JSON.writeValueAsString(metadata == null ? Map.of() : metadata))
				.update();
	}
}
