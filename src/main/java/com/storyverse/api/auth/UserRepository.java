package com.storyverse.api.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.json.JsonMapper;

@Repository
public class UserRepository {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String SELECT_COLUMNS = """
			id, email, encrypted_password, email_confirmed_at,
			raw_user_meta_data::text as metadata, last_sign_in_at, created_at
			""";

	private final JdbcClient jdbc;

	public UserRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<User> findByEmail(String email) {
		return jdbc.sql("select " + SELECT_COLUMNS + " from auth.users where lower(email) = lower(:email)")
				.param("email", email)
				.query(this::map)
				.optional();
	}

	public Optional<User> findById(UUID id) {
		return jdbc.sql("select " + SELECT_COLUMNS + " from auth.users where id = :id")
				.param("id", id)
				.query(this::map)
				.optional();
	}

	public User create(String email, String passwordHash, String name, Instant emailConfirmedAt) {
		String metadata = name == null || name.isBlank() ? "{}" : JSON.writeValueAsString(Map.of("name", name));
		return jdbc.sql("""
				insert into auth.users (email, encrypted_password, raw_user_meta_data, email_confirmed_at)
				values (:email, :hash, cast(:metadata as jsonb), case when :confirmed then now() else null end)
				returning
				""" + SELECT_COLUMNS)
				.param("email", email)
				.param("hash", passwordHash)
				.param("metadata", metadata)
				.param("confirmed", emailConfirmedAt != null)
				.query(this::map)
				.single();
	}

	public void touchLastSignIn(UUID id) {
		jdbc.sql("update auth.users set last_sign_in_at = now(), updated_at = now() where id = :id")
				.param("id", id)
				.update();
	}

	public void markEmailConfirmed(UUID id) {
		jdbc.sql("""
				update auth.users
				   set email_confirmed_at = coalesce(email_confirmed_at, now()), updated_at = now()
				 where id = :id
				""").param("id", id).update();
	}

	public void updatePassword(UUID id, String passwordHash) {
		jdbc.sql("""
				update auth.users
				   set encrypted_password = :hash, updated_at = now()
				 where id = :id
				""").param("id", id).param("hash", passwordHash).update();
	}

	public void updateMetadata(UUID id, Map<String, Object> metadata) {
		jdbc.sql("""
				update auth.users
				   set raw_user_meta_data = cast(:metadata as jsonb), updated_at = now()
				 where id = :id
				""").param("id", id).param("metadata", JSON.writeValueAsString(metadata)).update();
	}

	private User map(ResultSet rs, int rowNum) throws SQLException {
		return new User(
				rs.getObject("id", UUID.class),
				rs.getString("email"),
				rs.getString("encrypted_password"),
				readInstant(rs, "email_confirmed_at"),
				readMetadata(rs.getString("metadata")),
				readInstant(rs, "last_sign_in_at"),
				readInstant(rs, "created_at"));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> readMetadata(String json) {
		if (json == null || json.isBlank()) return Map.of();
		try {
			return JSON.readValue(json, Map.class);
		} catch (RuntimeException error) {
			return Map.of();
		}
	}

	static Instant readInstant(ResultSet rs, String column) throws SQLException {
		Timestamp timestamp = rs.getTimestamp(column);
		return timestamp == null ? null : timestamp.toInstant();
	}

	static OffsetDateTime at(Instant instant) {
		return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
