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
	/** Links de confirmação válidos ao mesmo tempo por conta (o novo incluído). */
	public static final int MAX_PENDING_CONFIRMATIONS = 5;

	private final JdbcClient jdbc;

	public EmailTokenRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void create(UUID userId, String kind, String tokenHash, Instant expiresAt) {
		jdbc.sql("delete from auth.email_tokens where expires_at <= now()").update();
		if (CONFIRM_EMAIL.equals(kind)) {
			// O e-mail pode atrasar: a pessoa pede outro e abre o primeiro. Confirmar com um link antigo
			// não dá acesso a nada que o novo não dê, então só os mais antigos saem, para não acumular.
			jdbc.sql("""
					update auth.email_tokens set used_at = now()
					 where id in (select id from auth.email_tokens
					               where user_id = :userId and kind = :kind and used_at is null
					               order by created_at desc offset :keep)
					""")
					.param("userId", userId)
					.param("kind", kind)
					.param("keep", MAX_PENDING_CONFIRMATIONS - 1)
					.update();
		} else {
			// Recuperação de senha: só o link mais recente vale.
			revokePending(userId, kind);
		}
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

	public void revokePending(UUID userId, String kind) {
		jdbc.sql("""
				update auth.email_tokens set used_at = now()
				 where user_id = :userId and kind = :kind and used_at is null
				""").param("userId", userId).param("kind", kind).update();
	}
}
