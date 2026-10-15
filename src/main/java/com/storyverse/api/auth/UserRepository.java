package com.storyverse.api.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

	private static final String SELECT_COLUMNS = """
			id, email, encrypted_password, raw_user_meta_data ->> 'name' as name, last_sign_in_at, created_at
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

	public User create(String email, String passwordHash, String name) {
		return jdbc.sql("""
				insert into auth.users (email, encrypted_password, raw_user_meta_data)
				values (:email, :hash, jsonb_build_object('name', cast(:name as text)))
				returning
				""" + SELECT_COLUMNS)
				.param("email", email)
				.param("hash", passwordHash)
				.param("name", name)
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
				rs.getString("name"),
				readInstant(rs, "last_sign_in_at"),
				readInstant(rs, "created_at"));
	}

	static Instant readInstant(ResultSet rs, String column) throws SQLException {
		Timestamp timestamp = rs.getTimestamp(column);
		return timestamp == null ? null : timestamp.toInstant();
	}
}
