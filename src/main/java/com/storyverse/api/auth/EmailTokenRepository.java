package com.storyverse.api.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Tokens opacos de confirmação de e-mail e recuperação de senha. */
@Repository
public class EmailTokenRepository {

	public static final String CONFIRM_EMAIL = "confirm_email";
	public static final String RESET_PASSWORD = "reset_password";

	private final JdbcClient jdbc;

	public EmailTokenRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void create(UUID userId, String kind, String tokenHash, Instant expiresAt) {
		jdbc.sql("delete from auth.email_tokens where expires_at <= now()").update();
		jdbc.sql("""
				update auth.email_tokens set used_at = now()
				 where user_id = :userId and kind = :kind and used_at is null
				""").param("userId", userId).param("kind", kind).update();
		jdbc.sql("""
				insert into auth.email_tokens (user_id, kind, token_hash, expires_at)
				values (:userId, :kind, :hash, :expiresAt)
				""")
				.param("userId", userId)
				.param("kind", kind)
				.param("hash", tokenHash)
				.param("expiresAt", UserRepository.at(expiresAt))
				.update();
	}

	public Optional<UUID> consume(String kind, String tokenHash) {
		return jdbc.sql("""
				update auth.email_tokens
				   set used_at = now()
				 where token_hash = :hash and kind = :kind and used_at is null and expires_at > now()
				returning user_id
				""")
				.param("hash", tokenHash)
				.param("kind", kind)
				.query(UUID.class)
				.optional();
	}
}
