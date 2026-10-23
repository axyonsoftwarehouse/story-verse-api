package com.storyverse.api.admin;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.AuthzService;
import com.storyverse.api.common.DbSession;

import tools.jackson.databind.json.JsonMapper;

/** Consulta da auditoria, mais nova primeiro, com cursor pelo id (spec 4.7). Nada aqui altera a tabela. */
@Service
public class AdminAuditService {

	static final int PAGE_SIZE = 50;
	private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** Filtros crus da requisição; a validação e a conversão ficam no serviço. */
	public record Filter(String action, String actorId, String targetType, String targetId,
			String from, String to, String before) {
	}

	private final AuthService auth;
	private final AuthzService authz;
	private final JdbcClient jdbc;

	public AdminAuditService(AuthService auth, AuthzService authz, JdbcClient jdbc) {
		this.auth = auth;
		this.authz = authz;
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public AdminAuditPage audit(String token, Filter filter) {
		UUID actor = auth.authenticate(token);
		authz.requireRole(actor, AuthzService.SUPERADMIN);

		StringBuilder where = new StringBuilder(" where true");
		Map<String, Object> params = new java.util.LinkedHashMap<>();
		if (present(filter.action())) {
			where.append(" and a.action like :action escape '\'");
			params.put("action", AdminUserRepository.escapeLike(filter.action().strip()) + "%");
		}
		if (present(filter.actorId())) {
			where.append(" and a.actor_id = :actorId");
			params.put("actorId", parseUuid(filter.actorId()));
		}
		if (present(filter.targetType())) {
			where.append(" and a.target_type = :targetType");
			params.put("targetType", filter.targetType().strip());
		}
		if (present(filter.targetId())) {
			where.append(" and a.target_id = :targetId");
			params.put("targetId", filter.targetId().strip());
		}
		if (present(filter.from())) {
			where.append(" and a.created_at >= :from");
			params.put("from", startOfDay(parseDate(filter.from())));
		}
		if (present(filter.to())) {
			where.append(" and a.created_at < :toExclusive");
			params.put("toExclusive", startOfDay(parseDate(filter.to()).plusDays(1)));
		}
		if (present(filter.before())) {
			where.append(" and a.id < :before");
			params.put("before", parseBefore(filter.before()));
		}

		DbSession.setUser(jdbc, actor);
		var statement = jdbc.sql("""
				select a.id, a.actor_id, a.action, a.target_type, a.target_id,
				       a.metadata::text as metadata, a.created_at,
				       case when a.actor_id is null then null
				            else coalesce(u.raw_user_meta_data->>'name', 'Leitor') end as actor_name
				  from public.admin_audit_log a
				  left join auth.users u on u.id = a.actor_id
				""" + where + " order by a.id desc limit :limit");
		for (var entry : params.entrySet()) {
			statement = statement.param(entry.getKey(), entry.getValue());
		}
		List<AdminAuditPage.Entry> rows = statement.param("limit", PAGE_SIZE + 1)
				.query((rs, rowNum) -> {
					String metadata = rs.getString("metadata");
					Timestamp created = rs.getTimestamp("created_at");
					@SuppressWarnings("unchecked")
					Map<String, Object> parsed = metadata == null ? Map.of() : JSON.readValue(metadata, Map.class);
					return new AdminAuditPage.Entry(
							rs.getLong("id"),
							rs.getObject("actor_id", UUID.class),
							rs.getString("actor_name"),
							rs.getString("action"),
							rs.getString("target_type"),
							rs.getString("target_id"),
							parsed,
							created == null ? null : created.toInstant());
				}).list();
		if (rows.size() > PAGE_SIZE) {
			List<AdminAuditPage.Entry> page = List.copyOf(rows.subList(0, PAGE_SIZE));
			return new AdminAuditPage(page, page.get(PAGE_SIZE - 1).id());
		}
		return new AdminAuditPage(rows, null);
	}

	private static boolean present(String value) {
		return value != null && !value.isBlank();
	}

	private static OffsetDateTime startOfDay(LocalDate date) {
		return date.atStartOfDay(SAO_PAULO).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
	}

	private static LocalDate parseDate(String raw) {
		try {
			return LocalDate.parse(raw.strip());
		} catch (RuntimeException error) {
			throw new AuthException(400, "Data inválida.");
		}
	}

	private static UUID parseUuid(String raw) {
		try {
			return UUID.fromString(raw.strip());
		} catch (RuntimeException error) {
			throw new AuthException(400, "Ator inválido.");
		}
	}

	private static long parseBefore(String raw) {
		try {
			return Long.parseLong(raw.strip());
		} catch (RuntimeException error) {
			throw new AuthException(400, "Cursor inválido.");
		}
	}
}
