package com.storyverse.api.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Repository
public class UserRepository {

	private static final String SELECT_COLUMNS = """
			id, email, encrypted_password, raw_user_meta_data::text as meta, last_sign_in_at, created_at
			""";

	private final JdbcClient jdbc;
	private final ObjectMapper mapper;

	public UserRepository(JdbcClient jdbc, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
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

	public User create(String email, String passwordHash, String name) {
		return jdbc.sql("""
				insert into auth.users (email, encrypted_password, raw_user_meta_data)
				values (:email, :hash, cast(:meta as jsonb))
				returning
				""" + SELECT_COLUMNS)
				.param("email", email)
				.param("hash", passwordHash)
				.param("meta", toMeta(name))
				.query(this::map)
				.single();
	}

	public void touchLastSignIn(UUID id) {
		jdbc.sql("update auth.users set last_sign_in_at = now(), updated_at = now() where id = :id")
				.param("id", id)
				.update();
	}

	private User map(ResultSet rs, int rowNum) throws SQLException {
		return new User(
				rs.getObject("id", UUID.class),
				rs.getString("email"),
				rs.getString("encrypted_password"),
				readName(rs.getString("meta")),
				readInstant(rs, "last_sign_in_at"),
				readInstant(rs, "created_at"));
	}

	private String readName(String meta) {
		if (meta == null || meta.isBlank()) {
			return null;
		}
		try {
			JsonNode name = mapper.readTree(meta).get("name");
			return name == null || name.isNull() ? null : name.asText();
		} catch (Exception exception) {
			return null;
		}
	}

	private String toMeta(String name) {
		if (name == null || name.isBlank()) {
			return "{}";
		}
		try {
			return mapper.writeValueAsString(Map.of("name", name.trim()));
		} catch (Exception exception) {
			return "{}";
		}
	}

	static Instant readInstant(ResultSet rs, String column) throws SQLException {
		Timestamp timestamp = rs.getTimestamp(column);
		return timestamp == null ? null : timestamp.toInstant();
	}
}
