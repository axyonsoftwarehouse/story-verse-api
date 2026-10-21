package com.storyverse.api.account;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.User;
import com.storyverse.api.auth.UserRepository;
import com.storyverse.api.auth.UserResponse;
import com.storyverse.api.common.DbRows;
import com.storyverse.api.common.DbSession;
import com.storyverse.api.common.RateLimitGate;
import com.storyverse.api.common.SecurityEvents;
import com.storyverse.api.storage.StorageService;

/** Exportação e exclusão da própria conta (LGPD). */
@Service
public class AccountService {

	private final AuthService auth;
	private final UserRepository users;
	private final JdbcClient jdbc;
	private final PasswordEncoder passwordEncoder;
	private final StorageService storage;
	private final RateLimitGate limits;
	private final SecurityEvents security;

	public AccountService(AuthService auth, UserRepository users, JdbcClient jdbc,
			PasswordEncoder passwordEncoder, StorageService storage, RateLimitGate limits, SecurityEvents security) {
		this.auth = auth;
		this.users = users;
		this.jdbc = jdbc;
		this.passwordEncoder = passwordEncoder;
		this.storage = storage;
		this.limits = limits;
		this.security = security;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> export(String accessToken) {
		UUID userId = auth.authenticate(accessToken);
		DbSession.setUser(jdbc, userId);
		User user = users.findById(userId).orElseThrow(() -> new AuthException(401, "Sessão inválida."));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("exportedAt", Instant.now().toString());
		out.put("user", UserResponse.from(user));
		out.put("readingState", jdbc.sql("""
				select data::text from public.reading_state where user_id = :uid
				""").param("uid", userId).query(String.class).optional().orElse("{}"));
		out.put("submissions", DbRows.list(jdbc, """
				select id, title, author, language, rights, status, text_path, char_count,
				       cover_url, created_at, reviewed_at
				  from public.book_submissions where user_id = :uid order by created_at
				""", Map.of("uid", userId)));
		return out;
	}

	@Transactional
	public void delete(String accessToken, String password) {
		UUID userId = auth.authenticate(accessToken);
		DbSession.setUser(jdbc, userId);
		limits.check("delete-account:" + userId, 3, Duration.ofHours(1));
		User user = users.findById(userId).orElseThrow(() -> new AuthException(401, "Sessão inválida."));
		if (password == null || user.encryptedPassword() == null
				|| !passwordEncoder.matches(password, user.encryptedPassword())) {
			throw new AuthException(400, "Senha atual incorreta.");
		}
		// Arquivos são dados pessoais: remove antes de apagar a conta.
		storage.deleteAllForUser(userId);
		jdbc.sql("select public.delete_my_account()").query((rs, rowNum) -> null).optional();
		security.warn("account.deleted");
	}
}
