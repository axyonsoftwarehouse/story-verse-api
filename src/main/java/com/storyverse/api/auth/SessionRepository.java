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
		return jdbc.sql("select " + SELECT_COLUMNS + " from auth.sessions where refresh_hash = :hash")
				.param("hash", refreshHash)
				.query(SessionRepository::map)
				.optional();
	}

	public void rotate(UUID id, String accessHash, Instant accessExpiresAt, String refreshHash, Instant refreshExpiresAt) {
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
		jdbc.sql("update auth.sessions set revoked_at = now() where refresh_hash = :hash and revoked_at is null")
				.param("hash", refreshHash)
				.update();
	}

	public void revokeByAccessHash(String accessHash) {
		jdbc.sql("update auth.sessions set revoked_at = now() where access_hash = :hash and revoked_at is null")
				.param("hash", accessHash)
				.update();
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
