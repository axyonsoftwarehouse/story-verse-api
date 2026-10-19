package com.storyverse.api.auth;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SessionRepository {

	private static final String SELECT_COLUMNS = "id, user_id, access_expires_at, refresh_expires_at, revoked_at";

	private final JdbcClient jdbc;

	public SessionRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void create(UUID userId, String accessHash, Instant accessExpiresAt, String refreshHash, Instant refreshExpiresAt) {
		jdbc.sql("""
				insert into auth.sessions (user_id, access_hash, access_expires_at, refresh_hash, refresh_expires_at)
				values (:userId, :accessHash, :accessExpiresAt, :refreshHash, :refreshExpiresAt)
				""")
				.param("userId", userId)
				.param("accessHash", accessHash)
				.param("accessExpiresAt", at(accessExpiresAt))
				.param("refreshHash", refreshHash)
				.param("refreshExpiresAt", at(refreshExpiresAt))
				.update();
	}

	public Optional<SessionRow> findByAccessHash(String accessHash) {
		return jdbc.sql("select " + SELECT_COLUMNS + " from auth.sessions where access_hash = :hash")
				.param("hash", accessHash)
				.query(SessionRepository::map)
				.optional();
	}

	public Optional<SessionRow> findByRefreshHash(String refreshHash) {
 return jdbc.sql("select " + SELECT_COLUMNS + " from auth.sessions where refresh_hash = :hash for update")
				.param("hash", refreshHash)
				.query(SessionRepository::map)
				.optional();
	}

	public void rotate(UUID id, String accessHash, Instant accessExpiresAt, String refreshHash, Instant refreshExpiresAt) {
		// Poda o histórico vencido; sem isso a tabela cresceria para sempre (2 linhas por refresh).
		jdbc.sql("delete from auth.session_token_history where expires_at <= now()").update();
		jdbc.sql("""
				insert into auth.session_token_history (token_hash, session_id, expires_at)
				select access_hash, id, access_expires_at from auth.sessions where id = :id
				union all select refresh_hash, id, refresh_expires_at from auth.sessions where id = :id
				on conflict (token_hash) do nothing
				""").param("id", id).update();
		jdbc.sql("""
				update auth.sessions
				   set access_hash = :accessHash,
				       access_expires_at = :accessExpiresAt,
				       refresh_hash = :refreshHash,
				       refresh_expires_at = :refreshExpiresAt,
				       last_used_at = now()
				 where id = :id
				""")
				.param("id", id)
				.param("accessHash", accessHash)
				.param("accessExpiresAt", at(accessExpiresAt))
				.param("refreshHash", refreshHash)
				.param("refreshExpiresAt", at(refreshExpiresAt))
				.update();
	}

	public void revokeByRefreshHash(String refreshHash) {
		revokeByTokenHash(refreshHash);
	}

	public void revokeByAccessHash(String accessHash) {
		revokeByTokenHash(accessHash);
	}

	public void revokeAllForUser(UUID userId) {
		jdbc.sql("update auth.sessions set revoked_at = now() where user_id = :userId and revoked_at is null")
				.param("userId", userId)
				.update();
	}

	public void revokeAllForUserExcept(UUID userId, UUID keepSessionId) {
		jdbc.sql("""
				update auth.sessions set revoked_at = now()
				 where user_id = :userId and id <> :keepSessionId and revoked_at is null
				""").param("userId", userId).param("keepSessionId", keepSessionId).update();
	}

	private void revokeByTokenHash(String hash) {
		// Resolve the stable session ID before taking the update lock. Rotation may change hashes
		// while this request waits, but must never move a logout to a different session.
		var id = jdbc.sql("""
				select id from auth.sessions where access_hash = :hash or refresh_hash = :hash
				union select session_id from auth.session_token_history
				where token_hash = :hash and expires_at > now()
				""").param("hash", hash).query(UUID.class).optional();
		id.ifPresent(value -> jdbc.sql("update auth.sessions set revoked_at = now() where id = :id")
				.param("id", value).update());
	}

	private static OffsetDateTime at(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private static SessionRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
		return new SessionRow(
				rs.getObject("id", UUID.class),
				rs.getObject("user_id", UUID.class),
				UserRepository.readInstant(rs, "access_expires_at"),
				UserRepository.readInstant(rs, "refresh_expires_at"),
				UserRepository.readInstant(rs, "revoked_at"));
	}
}
