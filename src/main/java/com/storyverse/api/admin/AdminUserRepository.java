package com.storyverse.api.admin;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.json.JsonMapper;

@Repository
public class AdminUserRepository {

	static final int SEARCH_LIMIT = 20;
	static final int AUDIT_LIMIT = 20;

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** Papéis de cada conta na mesma consulta (sem N+1). */
	private static final String SUMMARY_COLUMNS = """
			select u.id, coalesce(u.raw_user_meta_data->>'name', 'Leitor') as name, u.email,
			       u.email_confirmed_at is not null as email_confirmed, u.created_at,
			       coalesce((select array_agg(r.role::text order by r.role)
			                   from public.user_roles r where r.user_id = u.id), '{}'::text[]) as roles
			  from auth.users u
			""";

	private final JdbcClient jdbc;

	public AdminUserRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public List<AdminUserSummary> searchById(UUID id) {
		return jdbc.sql(SUMMARY_COLUMNS + " where u.id = :id")
				.param("id", id).query(AdminUserRepository::summary).list();
	}

	public List<AdminUserSummary> searchByEmail(String email) {
		return jdbc.sql(SUMMARY_COLUMNS + """
				 where lower(u.email) = lower(:q)
				 order by u.created_at desc
				 limit :limit
				""").param("q", email).param("limit", SEARCH_LIMIT).query(AdminUserRepository::summary).list();
	}

	/** {@code namePart} é escapado aqui: {@code %}, {@code _} e a barra invertida valem como texto. */
	public List<AdminUserSummary> searchByName(String namePart) {
		return jdbc.sql(SUMMARY_COLUMNS + """
				 where (u.raw_user_meta_data->>'name') ilike :pattern escape '\\'
				 order by u.created_at desc
				 limit :limit
				""")
				.param("pattern", "%" + escapeLike(namePart) + "%")
				.param("limit", SEARCH_LIMIT)
				.query(AdminUserRepository::summary).list();
	}

	/** Dados da conta, sem features nem auditoria (o serviço completa). */
	public Optional<AdminUserDetail> detail(UUID id) {
		return jdbc.sql("""
				select u.id, coalesce(u.raw_user_meta_data->>'name', 'Leitor') as name, u.email,
				       u.email_confirmed_at is not null as email_confirmed, u.created_at, u.last_sign_in_at,
				       coalesce((select array_agg(r.role::text order by r.role)
				                   from public.user_roles r where r.user_id = u.id), '{}'::text[]) as roles,
				       (select count(*) from auth.sessions s
				         where s.user_id = u.id and s.revoked_at is null
				           and s.refresh_expires_at > now()) as active_sessions
				  from auth.users u
				 where u.id = :id
				""").param("id", id).query((rs, rowNum) -> new AdminUserDetail(
						rs.getObject("id", UUID.class),
						rs.getString("name"),
						rs.getString("email"),
						rs.getBoolean("email_confirmed"),
						instant(rs, "created_at"),
						instant(rs, "last_sign_in_at"),
						strings(rs.getArray("roles")),
						List.of(),
						rs.getInt("active_sessions"),
						null)).optional();
	}

	/** As últimas entradas da auditoria com a conta como alvo, mais novas primeiro. */
	public List<Map<String, Object>> recentAudit(UUID id) {
		return jdbc.sql("""
				select id, actor_id, action, metadata::text as metadata, created_at
				  from public.admin_audit_log
				 where target_type = 'user' and target_id = :target
				 order by created_at desc, id desc
				 limit :limit
				""").param("target", id.toString()).param("limit", AUDIT_LIMIT).query((rs, rowNum) -> {
					Map<String, Object> row = new LinkedHashMap<>();
					row.put("id", rs.getLong("id"));
					row.put("actorId", rs.getObject("actor_id", UUID.class));
					row.put("action", rs.getString("action"));
					String metadata = rs.getString("metadata");
					row.put("metadata", metadata == null ? Map.of() : JSON.readValue(metadata, Map.class));
					row.put("createdAt", instant(rs, "created_at"));
					return row;
				}).list();
	}

	/** E-mail confirmado da conta; vazio se a conta não existe. */
	public Optional<Boolean> emailConfirmed(UUID id) {
		return jdbc.sql("select email_confirmed_at is not null from auth.users where id = :id")
				.param("id", id).query(Boolean.class).optional();
	}

	/** Encerra as sessões ativas (mesma definição do detalhe) e devolve quantas foram encerradas. */
	public int revokeActiveSessions(UUID userId) {
		return jdbc.sql("""
				update auth.sessions set revoked_at = now()
				 where user_id = :id and revoked_at is null and refresh_expires_at > now()
				""").param("id", userId).update();
	}

	/** Devolve 1 se o papel foi dado agora, 0 se a conta já o tinha. */
	public int grantRole(UUID userId, String role, UUID grantedBy) {
		return jdbc.sql("""
				insert into public.user_roles (user_id, role, granted_by)
				values (:id, :role, :by)
				on conflict (user_id, role) do nothing
				""").param("id", userId).param("role", role).param("by", grantedBy).update();
	}

	/** Devolve 1 se o papel foi tirado agora, 0 se a conta não o tinha. */
	public int revokeRole(UUID userId, String role) {
		return jdbc.sql("delete from public.user_roles where user_id = :id and role = :role")
				.param("id", userId).param("role", role).update();
	}

	public List<String> roles(UUID userId) {
		return jdbc.sql("select role from public.user_roles where user_id = :id order by role")
				.param("id", userId).query(String.class).list();
	}

	static String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private static AdminUserSummary summary(ResultSet rs, int rowNum) throws SQLException {
		return new AdminUserSummary(
				rs.getObject("id", UUID.class),
				rs.getString("name"),
				EmailMask.mask(rs.getString("email")),
				rs.getBoolean("email_confirmed"),
				instant(rs, "created_at"),
				strings(rs.getArray("roles")));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	private static List<String> strings(Array array) throws SQLException {
		return array == null ? List.of() : List.of((String[]) array.getArray());
	}
}
