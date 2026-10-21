package com.storyverse.api.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.common.RateLimitGate;
import com.storyverse.api.common.SecurityEvents;

@Service
public class AuthService {

	private static final Duration ACCESS_TTL = Duration.ofMinutes(30);
	private static final Duration REFRESH_TTL = Duration.ofDays(30);
	private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
	private static final int MAX_URL = 500;

	private final UserRepository users;
	private final SessionRepository sessions;
	private final EmailTokenRepository emailTokens;
	private final TokenService tokens;
	private final PasswordEncoder passwordEncoder;
	private final String dummyPasswordHash;
	private final RateLimitGate limits;
	private final MailService mail;
	private final AuthzService authz;
	private final SecurityEvents security;
	private final boolean requireEmailConfirmation;
	private final Duration confirmationTtl;
	private final Duration resetTtl;

	public AuthService(
			UserRepository users,
			SessionRepository sessions,
			EmailTokenRepository emailTokens,
			TokenService tokens,
			PasswordEncoder passwordEncoder,
			RateLimitGate limits,
			MailService mail,
			AuthzService authz,
			SecurityEvents security,
			@Value("${app.auth.require-email-confirmation:true}") boolean requireEmailConfirmation,
			@Value("${app.auth.confirmation-ttl:PT24H}") Duration confirmationTtl,
			@Value("${app.auth.reset-ttl:PT1H}") Duration resetTtl) {
		this.users = users;
		this.sessions = sessions;
		this.emailTokens = emailTokens;
		this.tokens = tokens;
		this.passwordEncoder = passwordEncoder;
		// Hash inofensivo para gastar o mesmo tempo de BCrypt quando a conta não existe.
		this.dummyPasswordHash = passwordEncoder.encode("storyverse-dummy-" + UUID.randomUUID());
		this.limits = limits;
		this.mail = mail;
		this.authz = authz;
		this.security = security;
		this.requireEmailConfirmation = requireEmailConfirmation;
		this.confirmationTtl = confirmationTtl;
		this.resetTtl = resetTtl;
	}

	@Transactional
	public Object signup(SignupRequest request) {
		String email = validatedEmail(request.email());
		validatePassword(request.password());
		if (request.name() != null && request.name().length() > 60) {
			throw new AuthException(400, "Nome grande demais.");
		}
		// Gasta o BCrypt antes de saber se a conta existe, para não diferenciar pelo tempo.
		String passwordHash = passwordEncoder.encode(request.password());
		// Verifica o SMTP antes de consultar a conta: com o e-mail fora, a resposta não distingue
		// conta existente de inexistente. Em produção a confirmação fica ligada.
		if (requireEmailConfirmation) {
			mail.requireConfigured();
		}
		var existing = users.findByEmail(email);
		if (existing.isPresent()) {
			if (!requireEmailConfirmation) {
				// Só desenvolvimento (confirmação desligada): mantém o erro explícito.
				throw new AuthException(409, "Este e-mail já está cadastrado.");
			}
			User user = existing.get();
			if (user.emailConfirmedAt() == null) sendConfirmation(user);
			else mail.sendExistingAccount(user.email());
			return new SignupPendingResponse("Se este e-mail puder ser usado, enviaremos as instruções.");
		}
		User user = users.create(email, passwordHash, request.name(),
				requireEmailConfirmation ? null : Instant.now());
		if (!requireEmailConfirmation) {
			return issue(user);
		}
		sendConfirmation(user);
		return new SignupPendingResponse("Se este e-mail puder ser usado, enviaremos as instruções.");
	}

	@Transactional
	public AuthResponse login(LoginRequest request) {
		String email = normalizeEmail(request.email());
		if (email != null && email.length() > 254) throw new AuthException(401, "E-mail ou senha inválidos.");
		if (request.password() != null && request.password().getBytes(StandardCharsets.UTF_8).length > 72)
			throw new AuthException(401, "E-mail ou senha inválidos.");
		User user = users.findByEmail(email == null ? "" : email).orElse(null);
		String storedHash = user != null && user.encryptedPassword() != null
				? user.encryptedPassword()
				: dummyPasswordHash;
		boolean passwordOk = request.password() != null && passwordEncoder.matches(request.password(), storedHash);
		if (user == null || user.encryptedPassword() == null || !passwordOk) {
			// Conta falha por conta/e-mail, mas uma senha correta nunca é bloqueada pelo ataque
			// de outra pessoa (o limite é aplicado ao erro, não antes de verificar a senha).
			limits.check("login-fail:" + tokens.hash(email == null ? "" : email), 15, Duration.ofMinutes(15));
			throw new AuthException(401, "E-mail ou senha inválidos.");
		}
		if (requireEmailConfirmation && user.emailConfirmedAt() == null) {
			throw new AuthException(403, "Confirme seu e-mail antes de entrar. Reenvie o link se precisar.");
		}
		users.touchLastSignIn(user.id());
		return issue(user);
	}

	@Transactional
	public AuthResponse confirmEmail(String token) {
		if (token == null || token.isBlank()) throw new AuthException(400, "Link inválido ou expirado.");
		UUID userId = emailTokens.consume(EmailTokenRepository.CONFIRM_EMAIL, tokens.hash(token))
				.orElseThrow(() -> new AuthException(400, "Link inválido ou expirado."));
		users.markEmailConfirmed(userId);
		User user = users.findById(userId).orElseThrow(() -> new AuthException(401, "Conta não encontrada."));
		return issue(user);
	}

	@Transactional
	public void resendConfirmation(String emailRaw) {
		String email = validatedEmail(emailRaw);
		limits.check("resend-confirm:" + tokens.hash(email), 3, Duration.ofMinutes(15));
		mail.requireConfigured();
		users.findByEmail(email)
				.filter(user -> user.emailConfirmedAt() == null)
				.ifPresent(this::sendConfirmation);
	}

	@Transactional
	public void requestPasswordReset(String emailRaw) {
		String email = validatedEmail(emailRaw);
		limits.check("forgot-password:" + tokens.hash(email), 3, Duration.ofMinutes(15));
		mail.requireConfigured();
		users.findByEmail(email).ifPresent(user -> {
			String token = tokens.newToken();
			emailTokens.create(user.id(), EmailTokenRepository.RESET_PASSWORD, tokens.hash(token),
					Instant.now().plus(resetTtl));
			mail.sendPasswordReset(user.email(), token);
		});
	}

	@Transactional
	public void resetPassword(String token, String password) {
		validatePassword(password);
		if (token == null || token.isBlank()) throw new AuthException(400, "Link inválido ou expirado.");
		limits.check("reset-password:" + tokens.hash(token), 5, Duration.ofMinutes(15));
		UUID userId = emailTokens.consume(EmailTokenRepository.RESET_PASSWORD, tokens.hash(token))
				.orElseThrow(() -> new AuthException(400, "Link inválido ou expirado."));
		users.updatePassword(userId, passwordEncoder.encode(password));
		sessions.revokeAllForUser(userId);
		security.info("auth.password_reset");
	}

	@Transactional
	public void changePassword(String accessToken, String currentPassword, String newPassword) {
		validatePassword(newPassword);
		SessionRow session = requireSession(accessToken, false);
		limits.check("change-password:" + session.userId(), 5, Duration.ofMinutes(15));
		User user = users.findById(session.userId()).orElseThrow(() -> new AuthException(401, "Sessão inválida."));
		if (currentPassword == null || user.encryptedPassword() == null
				|| currentPassword.getBytes(StandardCharsets.UTF_8).length > 72
				|| !passwordEncoder.matches(currentPassword, user.encryptedPassword())) {
			throw new AuthException(400, "Senha atual incorreta.");
		}
		if (passwordEncoder.matches(newPassword, user.encryptedPassword())) {
			throw new AuthException(400, "A nova senha precisa ser diferente da atual.");
		}
		users.updatePassword(user.id(), passwordEncoder.encode(newPassword));
		sessions.revokeAllForUserExcept(user.id(), session.id());
		security.info("auth.password_changed");
	}

	@Transactional
	public UserResponse updateProfile(String accessToken, ProfileUpdateRequest request) {
		SessionRow session = requireSession(accessToken, false);
		User user = users.findById(session.userId()).orElseThrow(() -> new AuthException(401, "Sessão inválida."));
		Map<String, Object> metadata = new LinkedHashMap<>(user.metadata());
		String currentName = metadata.get("name") instanceof String value ? value : null;
		String name = request.name() == null ? currentName : request.name().trim();
		if (name == null || name.isBlank() || name.length() > 60) {
			throw new AuthException(400, "Nome inválido.");
		}
		metadata.put("name", name);
		putMetadata(metadata, "avatar", request.avatar(), 60);
		putMetadata(metadata, "photo", request.photo(), MAX_URL);
		putMetadata(metadata, "cover", request.cover(), MAX_URL);
		users.updateMetadata(user.id(), metadata);
		User updated = users.findById(user.id()).orElseThrow(() -> new AuthException(401, "Sessão inválida."));
		return UserResponse.from(updated);
	}

	@Transactional
	public AuthResponse refresh(String refreshToken) {
		if (refreshToken == null || refreshToken.isBlank()) {
			throw new AuthException(401, "Não autenticado.");
		}
		String hash = tokens.hash(refreshToken);
		SessionRow session = sessions.findByRefreshHash(hash).orElse(null);
		if (session == null) {
			var reused = sessions.findReusedRefreshSession(hash);
			if (reused.isPresent()) {
				// Token antigo reapareceu: pode ser roubo. Revoga a família inteira.
				sessions.revokeById(reused.get());
				security.warn("auth.refresh_reuse");
				throw new AuthException(401,
						"Sessão encerrada por segurança. Entre novamente.", "AUTH_REFRESH_REUSED");
			}
			throw new AuthException(401, "Sessão inválida. Entre novamente.");
		}
		Instant now = Instant.now();
		if (session.revokedAt() != null || session.refreshExpiresAt() == null
				|| session.refreshExpiresAt().isBefore(now)) {
			throw new AuthException(401, "Sessão expirada. Entre novamente.");
		}
		User user = users.findById(session.userId())
				.orElseThrow(() -> new AuthException(401, "Sessão inválida."));
		return rotate(session, user);
	}

	private AuthResponse rotate(SessionRow session, User user) {
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

	@Transactional(readOnly = true)
	public CapabilitiesResponse capabilities(String accessToken) {
		SessionRow session = requireSession(accessToken, false);
		return new CapabilitiesResponse(List.copyOf(authz.roles(session.userId())));
	}

	/** Id do usuário da sessão (Bearer). Usado pelos módulos de dados. */
	@Transactional(readOnly = true)
	public UUID authenticate(String accessToken) {
		return requireSession(accessToken, false).userId();
	}

	private void sendConfirmation(User user) {
		String token = tokens.newToken();
		emailTokens.create(user.id(), EmailTokenRepository.CONFIRM_EMAIL, tokens.hash(token),
				Instant.now().plus(confirmationTtl));
		mail.sendConfirmation(user.email(), token);
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

	private static void putMetadata(Map<String, Object> metadata, String key, String value, int maxLength) {
		if (value == null || value.isBlank()) {
			metadata.remove(key);
			return;
		}
		if (value.length() > maxLength) {
			throw new AuthException(400, "Dados do perfil grande demais.");
		}
		metadata.put(key, value);
	}

	private static String validatedEmail(String raw) {
		String email = normalizeEmail(raw);
		if (email == null || email.length() > 254 || !EMAIL.matcher(email).matches()) {
			throw new AuthException(400, "E-mail inválido.");
		}
		return email;
	}

	private static void validatePassword(String password) {
		if (password == null || password.length() < 8) {
			throw new AuthException(400, "A senha precisa de pelo menos 8 caracteres.");
		}
		if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
			throw new AuthException(400, "Senha grande demais.");
		}
	}

	private static String normalizeEmail(String email) {
		return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
	}
}
