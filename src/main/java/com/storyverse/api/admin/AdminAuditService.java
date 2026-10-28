package com.storyverse.api.admin;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.AuthzService;
import com.storyverse.api.common.DbSession;

import tools.jackson.databind.json.JsonMapper;

/** Consulta e exportação da auditoria (somente leitura, somente superadmin). */
@Service
public class AdminAuditService {

	static final int PAGE_SIZE = 50;
	static final int EXPORT_MAX_ROWS = 50_000;
	static final int EXPORT_MAX_DAYS = 31;
	private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** Filtros crus da requisição; a validação e a conversão ficam no serviço. */
	public record Filter(String action, String actorId, String targetType, String targetId,
			String from, String to, String before) {
	}

	private record Query(String where, Map<String, Object> params) {
	}

	private final AuthService auth;
	private final AuthzService authz;
	private final JdbcClient jdbc;
	private final NamedParameterJdbcTemplate namedJdbc;

	public AdminAuditService(AuthService auth, AuthzService authz, JdbcClient jdbc,
			NamedParameterJdbcTemplate namedJdbc) {
		this.auth = auth;
		this.authz = authz;
		this.jdbc = jdbc;
		this.namedJdbc = namedJdbc;
	}

	@Transactional(readOnly = true)
	public AdminAuditPage audit(String token, Filter filter) {
		UUID actor = auth.authenticate(token);
		authz.requireRole(actor, AuthzService.SUPERADMIN);
		Query query = buildQuery(filter, true);
		DbSession.setUser(jdbc, actor);
		var statement = jdbc.sql("""
				select a.id, a.actor_id, a.action, a.target_type, a.target_id,
				       a.metadata::text as metadata, a.created_at,
				       case when a.actor_id is null then null
				            else coalesce(u.raw_user_meta_data->>'name', 'Leitor') end as actor_name
				  from public.admin_audit_log a
				  left join auth.users u on u.id = a.actor_id
				""" + query.where() + " order by a.id desc limit :limit");
		for (var entry : query.params().entrySet()) {
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

	/**
	 * Exporta o CSV respeitando os filtros. O período é obrigatório e limitado a 31 dias;
	 * o número de linhas é conferido antes de começar a transmitir.
	 */
	@Transactional
	public void exportCsv(String token, Filter filter, OutputStream output) throws IOException {
		UUID actor = auth.authenticate(token);
		authz.requireRole(actor, AuthzService.SUPERADMIN);
		if (!present(filter.from()) || !present(filter.to())) {
			throw new AuthException(400, "Informe o período (de e até) para exportar a auditoria.");
		}
		LocalDate fromDate = parseDate(filter.from());
		LocalDate toDate = parseDate(filter.to());
		if (toDate.isBefore(fromDate)) {
			throw new AuthException(400, "A data inicial precisa ser anterior à final.");
		}
		if (ChronoUnit.DAYS.between(fromDate, toDate) > EXPORT_MAX_DAYS - 1) {
			throw new AuthException(400, "A exportação aceita no máximo 31 dias.");
		}
		Query query = buildQuery(new Filter(filter.action(), filter.actorId(), filter.targetType(),
				filter.targetId(), filter.from(), filter.to(), null), false);
		DbSession.setUser(jdbc, actor);
		long count = count(query);
		if (count > EXPORT_MAX_ROWS) {
			throw new AuthException(400, "A exportação aceita no máximo 50.000 linhas. Reduza o período.");
		}
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("count", count);
		metadata.put("from", fromDate.toString());
		metadata.put("to", toDate.toString());
		putIfPresent(metadata, "action", filter.action());
		putIfPresent(metadata, "actorId", filter.actorId());
		putIfPresent(metadata, "targetType", filter.targetType());
		putIfPresent(metadata, "targetId", filter.targetId());
		authz.audit(actor, "audit.export", "audit", "export", metadata);

		String sql = """
				select a.id, a.actor_id, a.action, a.target_type, a.target_id,
				       a.metadata::text as metadata, a.created_at,
				       case when a.actor_id is null then null
				            else coalesce(u.raw_user_meta_data->>'name', 'Leitor') end as actor_name
				  from public.admin_audit_log a
				  left join auth.users u on u.id = a.actor_id
				""" + query.where() + " order by a.id asc";
		output.write(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF });
		writeLine(output, "id;createdAt;actor;action;targetType;targetId;metadata");
		namedJdbc.query(sql, new MapSqlParameterSource(query.params()), rs -> {
			Timestamp created = rs.getTimestamp("created_at");
			String actorName = rs.getString("actor_name");
			if (actorName == null) actorName = "script na VPS";
			String line = String.join(";",
					csv(String.valueOf(rs.getLong("id"))),
					csv(created == null ? "" : created.toInstant().toString()),
					csv(actorName),
					csv(rs.getString("action")),
					csv(rs.getString("target_type")),
					csv(rs.getString("target_id")),
					csv(rs.getString("metadata")));
			try {
				writeLine(output, line);
			} catch (IOException error) {
				throw new RuntimeException(error);
			}
		});
	}

	private long count(Query query) {
		var statement = jdbc.sql("select count(*) from public.admin_audit_log a" + query.where());
		for (var entry : query.params().entrySet()) {
			statement = statement.param(entry.getKey(), entry.getValue());
		}
		return statement.query(Long.class).single();
	}

	private Query buildQuery(Filter filter, boolean includeBefore) {
		StringBuilder where = new StringBuilder(" where true");
		Map<String, Object> params = new LinkedHashMap<>();
		if (present(filter.action())) {
			where.append(" and a.action like :action escape '\\'");
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
		if (includeBefore && present(filter.before())) {
			where.append(" and a.id < :before");
			params.put("before", parseBefore(filter.before()));
		}
		return new Query(where.toString(), params);
	}

	private static void writeLine(OutputStream output, String line) throws IOException {
		output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
	}

	private static String csv(String value) {
		if (value == null) return "";
		String escaped = value.replace("\"", "\"\"");
		if (escaped.contains(";") || escaped.contains("\"") || escaped.contains("\n") || escaped.contains("\r")) {
			return "\"" + escaped + "\"";
		}
		return escaped;
	}

	private static void putIfPresent(Map<String, Object> map, String key, String value) {
		if (present(value)) map.put(key, value.strip());
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
