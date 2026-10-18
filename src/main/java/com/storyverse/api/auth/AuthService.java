package com.storyverse.api.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.storyverse.api.common.RateLimits;
import java.nio.charset.StandardCharsets;

@Service
public class AuthService {

	private static final Duration ACCESS_TTL = Duration.ofMinutes(30);
	private static final Duration REFRESH_TTL = Duration.ofDays(30);
	private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

	private final UserRepository users;
	private final SessionRepository sessions;
	private final TokenService tokens;
	private final PasswordEncoder passwordEncoder;
	private final RateLimits limits;

	public AuthService(UserRepository users, SessionRepository sessions, TokenService tokens, PasswordEncoder passwordEncoder, RateLimits limits) {
		this.users = users;
		this.sessions = sessions;
		this.tokens = tokens;
		this.passwordEncoder = passwordEncoder;
		this.limits = limits;
	}

	@Transactional
	public AuthResponse signup(SignupRequest request) {
		String email = normalizeEmail(request.email());
		if (email == null || email.length() > 254 || !EMAIL.matcher(email).matches()) {
			throw new AuthException(400, "E-mail inválido.");
		}
		if (request.password() == null || request.password().length() < 6) {
			throw new AuthException(400, "A senha precisa de pelo menos 6 caracteres.");
		}
		if (request.password().getBytes(StandardCharsets.UTF_8).length > 72 || (request.name() != null && request.name().length() > 60))
			throw new AuthException(400, "Senha ou nome grande demais.");
		if (users.findByEmail(email).isPresent()) {
			throw new AuthException(409, "Este e-mail já está cadastrado.");
		}
		User user = users.create(email, passwordEncoder.encode(request.password()), request.name());
		return issue(user);
	}

	@Transactional
	public AuthResponse login(LoginRequest request) {
		String email = normalizeEmail(request.email());
		if (email != null && email.length() > 254) throw new AuthException(401, "E-mail ou senha inválidos.");
		limits.check("login-account:" + tokens.hash(email == null ? "" : email), 15, Duration.ofMinutes(15));
		if (request.password() != null && request.password().getBytes(StandardCharsets.UTF_8).length > 72)
			throw new AuthException(401, "E-mail ou senha inválidos.");
		User user = users.findByEmail(email == null ? "" : email)
				.orElseThrow(() -> new AuthException(401, "E-mail ou senha inválidos."));
		if (user.encryptedPassword() == null
				|| request.password() == null
				|| !passwordEncoder.matches(request.password(), user.encryptedPassword())) {
			throw new AuthException(401, "E-mail ou senha inválidos.");
		}
		users.touchLastSignIn(user.id());
		return issue(user);
	}

	@Transactional
	public AuthResponse refresh(String refreshToken) {
		SessionRow session = requireSession(refreshToken, true);
		User user = users.findById(session.userId())
				.orElseThrow(() -> new AuthException(401, "Sessão inválida."));
		String accessToken = tokens.newToken();
		String newRefreshToken = tokens.newToken();
		Instant now = Instant.now();
		sessions.rotate(session.id(), tokens.hash(accessToken), now.plus(ACCESS_TTL),
				tokens.hash(newRefreshToken), now.plus(REFRESH_TTL));
		return new AuthResponse(accessToken, newRefreshToken, ACCESS_TTL.toSeconds(), UserResponse.from(user));
	}

	@Transactional
	public void logout(String refreshToken, String accessToken) {
		if (refreshToken != null && !refreshToken.isBlank()) {
			sessions.revokeByRefreshHash(tokens.hash(refreshToken));
		}
		if (accessToken != null && !accessToken.isBlank()) {
			sessions.revokeByAccessHash(tokens.hash(accessToken));
		}
	}

	@Transactional(readOnly = true)
	public UserResponse me(String accessToken) {
		SessionRow session = requireSession(accessToken, false);
		User user = users.findById(session.userId())
				.orElseThrow(() -> new AuthException(401, "Sessão inválida."));
		return UserResponse.from(user);
	}

	/** Id do usuário da sessão (Bearer). Usado pelos módulos de dados. */
	@Transactional(readOnly = true)
	public UUID authenticate(String accessToken) {
		return requireSession(accessToken, false).userId();
	}

	private SessionRow requireSession(String token, boolean refresh) {
		if (token == null || token.isBlank()) {
			throw new AuthException(401, "Não autenticado.");
		}
		String hash = tokens.hash(token);
		SessionRow session = (refresh
				? sessions.findByRefreshHash(hash)
				: sessions.findByAccessHash(hash))
				.orElseThrow(() -> new AuthException(401, "Sessão inválida."));
		Instant expiresAt = refresh ? session.refreshExpiresAt() : session.accessExpiresAt();
		if (session.revokedAt() != null || expiresAt == null || expiresAt.isBefore(Instant.now())) {
			throw new AuthException(401, "Sessão expirada. Entre de novo.");
		}
		return session;
	}

	private AuthResponse issue(User user) {
		String accessToken = tokens.newToken();
		String refreshToken = tokens.newToken();
		Instant now = Instant.now();
		sessions.create(user.id(), tokens.hash(accessToken), now.plus(ACCESS_TTL),
				tokens.hash(refreshToken), now.plus(REFRESH_TTL));
		return new AuthResponse(accessToken, refreshToken, ACCESS_TTL.toSeconds(), UserResponse.from(user));
	}

	private static String normalizeEmail(String email) {
		return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
	}
}
