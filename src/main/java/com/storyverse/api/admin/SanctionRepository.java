package com.storyverse.api.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Leitura e escrita de {@code public.user_sanctions}. O serviço define o usuário da RLS. */
@Repository
public class SanctionRepository {

	static final int HISTORY_LIMIT = 20;
	static final int PAGE_LIMIT = 20;

	private static final String SELECT = """
			select s.id, s.user_id, s.kind, s.reason, s.expires_at,
			       s.created_by, cb.raw_user_meta_data->>'name' as created_by_name, s.created_at,
			       s.revoked_by, rb.raw_user_meta_data->>'name' as revoked_by_name, s.revoked_at,
			       coalesce(u.raw_user_meta_data->>'name', 'Leitor') as user_name, u.email as user_email
			  from public.user_sanctions s
			  join auth.users u on u.id = s.user_id
			  left join auth.users cb on cb.id = s.created_by
			  left join auth.users rb on rb.id = s.revoked_by
			""";

	private final JdbcClient jdbc;

	public SanctionRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<Sanction> activeBlocking(UUID userId) {
		return jdbc.sql(SELECT + """
				 where s.user_id = :id
				   and s.kind in ('suspension', 'ban')
				   and s.revoked_at is null
				   and (s.expires_at is null or s.expires_at > now())
				 order by s.created_at desc, s.id desc
				 limit 1
				""").param("id", userId).query(SanctionRepository::map).optional();
	}

	public Optional<Sanction> activeWarning(UUID userId) {
		return jdbc.sql(SELECT + """
				 where s.user_id = :id
				   and s.kind = 'warning'
				   and s.revoked_at is null
				   and (s.expires_at is null or s.expires_at > now())
				 order by s.created_at desc, s.id desc
				 limit 1
				""").param("id", userId).query(SanctionRepository::map).optional();
	}

	public List<Sanction> history(UUID userId, int limit) {
		return jdbc.sql(SELECT + """
				 where s.user_id = :id
				 order by s.created_at desc, s.id desc
				 limit :limit
				""").param("id", userId).param("limit", limit).query(SanctionRepository::map).list();
	}

	public Optional<Sanction> findById(UUID id) {
		return jdbc.sql(SELECT + " where s.id = :id")
				.param("id", id).query(SanctionRepository::map).optional();
	}

	/** Lista do painel; {@code status} já validado pelo serviço. */
	public List<Sanction> list(String status, String kind, UUID userId, String pattern, AdminCursor cursor, int limit) {
		StringBuilder sql = new StringBuilder(SELECT).append(" where 1 = 1");
		Map<String, Object> params = new LinkedHashMap<>();
		if ("active".equals(status)) {
			sql.append(" and s.revoked_at is null and (s.expires_at is null or s.expires_at > now())");
		} else if ("revoked".equals(status)) {
			sql.append(" and s.revoked_at is not null");
		}
		if (kind != null) {
			sql.append(" and s.kind = :kind");
			params.put("kind", kind);
		}
		if (userId != null) {
			sql.append(" and s.user_id = :userId");
			params.put("userId", userId);
		}
		if (pattern != null) {
			sql.append("""
					 and (u.email ilike :pattern escape '\\'
					   or (u.raw_user_meta_data->>'name') ilike :pattern escape '\\')
					""");
			params.put("pattern", pattern);
		}
		if (cursor != null) {
			sql.append(" and (date_trunc('milliseconds', s.created_at), s.id) < (:cursorTime, :cursorId)");
			params.put("cursorTime", Timestamp.from(cursor.time()));
			params.put("cursorId", cursor.id());
		}
		sql.append(" order by date_trunc('milliseconds', s.created_at) desc, s.id desc limit :limit");
		params.put("limit", limit);
		var spec = jdbc.sql(sql.toString());
		for (Map.Entry<String, Object> param : params.entrySet()) {
			spec = spec.param(param.getKey(), param.getValue());
		}
		return spec.query(SanctionRepository::map).list();
	}

	public Sanction insert(UUID userId, String kind, String reason, Instant expiresAt, UUID createdBy) {
		UUID id = jdbc.sql("""
				insert into public.user_sanctions (user_id, kind, reason, expires_at, created_by)
				values (:userId, :kind, :reason, :expiresAt, :createdBy)
				returning id
				""")
				.param("userId", userId)
				.param("kind", kind)
				.param("reason", reason)
				.param("expiresAt", expiresAt == null ? null : Timestamp.from(expiresAt))
				.param("createdBy", createdBy)
				.query(UUID.class)
				.single();
		return findById(id).orElseThrow();
	}

	/** Devolve 1 se revogou agora, 0 se já estava revogada ou não existe. */
	public int revoke(UUID id, UUID revokedBy) {
		return jdbc.sql("""
				update public.user_sanctions
				   set revoked_at = now(), revoked_by = :by
				 where id = :id and revoked_at is null
				""").param("id", id).param("by", revokedBy).update();
	}

	public boolean userExists(UUID id) {
		return Boolean.TRUE.equals(jdbc.sql("select exists (select 1 from auth.users where id = :id)")
				.param("id", id).query(Boolean.class).optional().orElse(false));
	}

	/** Qualquer papel de staff bloqueia a aplicação de sanção pelo painel. */
	public boolean hasAnyRole(UUID id) {
		return Boolean.TRUE.equals(jdbc.sql("select exists (select 1 from public.user_roles where user_id = :id)")
				.param("id", id).query(Boolean.class).optional().orElse(false));
	}

	private static Sanction map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
		return new Sanction(
				rs.getObject("id", UUID.class),
				rs.getObject("user_id", UUID.class),
				rs.getString("user_name"),
				EmailMask.mask(rs.getString("user_email")),
				rs.getString("kind"),
				rs.getString("reason"),
				instant(rs, "expires_at"),
				rs.getObject("created_by", UUID.class),
				rs.getString("created_by_name"),
				instant(rs, "created_at"),
				rs.getObject("revoked_by", UUID.class),
				rs.getString("revoked_by_name"),
				instant(rs, "revoked_at"));
	}

	private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}
}
