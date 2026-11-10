package com.storyverse.api.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Refresh token fora do alcance de JavaScript: cookie HttpOnly e Secure. O access token continua
 * indo no corpo/Authorization.
 *
 * <p>S2 da revisão de 09/11/2026: o domínio sslip.io não está na Public Suffix List, então qualquer
 * outro host `*.sslip.io` é "same-site" e pode plantar um cookie com `Domain=sslip.io`. O prefixo
 * `__Host-` (exige Secure, `Path=/` e nenhum `Domain`) impede isso: o navegador só aceita esse nome
 * vindo do próprio host da API. O nome antigo continua aceito até {@code legacyUntil}, para não
 * derrubar sessões abertas; o primeiro refresh já troca pelo nome novo e apaga o antigo.
 */
@Component
public class AuthCookieService {

	public static final String NAME = "__Host-storyverse_refresh";
	public static final String LEGACY_NAME = "storyverse_refresh";
	private static final Duration TTL = Duration.ofDays(30);
	private static final String PATH = "/";
	private static final String LEGACY_PATH = "/api/v1/auth";

	private final boolean secure;
	private final String sameSite;
	private final List<String> allowedOrigins;
	private final LocalDate legacyUntil;
	private final Clock clock;

	@Autowired
	public AuthCookieService(
			@Value("${app.auth.refresh-cookie-secure:true}") boolean secure,
			@Value("${app.auth.refresh-cookie-same-site:Lax}") String sameSite,
			@Value("${app.cors.allowed-origins:https://storyverse.2.29.42.104.sslip.io}") String[] allowedOrigins,
			@Value("${app.auth.legacy-refresh-cookie-until:2026-12-15}") String legacyUntil) {
		this(secure, sameSite, allowedOrigins, LocalDate.parse(legacyUntil.trim()), Clock.systemUTC());
	}

	/** Para testes que não ligam para origem nem transição. */
	public AuthCookieService(boolean secure, String sameSite) {
		this(secure, sameSite, new String[0], LocalDate.of(2026, 12, 15), Clock.systemUTC());
	}

	AuthCookieService(boolean secure, String sameSite, String[] allowedOrigins, LocalDate legacyUntil, Clock clock) {
		this.secure = secure;
		this.sameSite = sameSite;
		this.allowedOrigins = Arrays.stream(allowedOrigins).map(String::trim).filter(s -> !s.isEmpty()).toList();
		this.legacyUntil = legacyUntil;
		this.clock = clock;
	}

	/** Sem Secure (dev em http) o navegador recusa `__Host-`: aí fica o nome antigo. */
	private String name() {
		return secure ? NAME : LEGACY_NAME;
	}

	private String path() {
		return secure ? PATH : LEGACY_PATH;
	}

	public void set(HttpServletResponse response, String token) {
		response.addHeader("Set-Cookie", cookie(name(), path(), token, TTL).toString());
		clearLegacy(response);
	}

	public void clear(HttpServletResponse response) {
		response.addHeader("Set-Cookie", cookie(name(), path(), "", Duration.ZERO).toString());
		clearLegacy(response);
	}

	/** Apaga o cookie do nome antigo (o do próprio host; um plantado com Domain= não é lido depois do prazo). */
	private void clearLegacy(HttpServletResponse response) {
		if (secure) {
			response.addHeader("Set-Cookie", cookie(LEGACY_NAME, LEGACY_PATH, "", Duration.ZERO).toString());
		}
	}

	public String read(HttpServletRequest request) {
		String current = value(request, name());
		if (current != null && !current.isBlank()) {
			return current;
		}
		if (secure && legacyAccepted()) {
			return value(request, LEGACY_NAME);
		}
		return null;
	}

	boolean legacyAccepted() {
		return !LocalDate.now(clock.withZone(ZoneOffset.UTC)).isAfter(legacyUntil);
	}

	/**
	 * Refresh e logout usam o cookie: recusa chamada vinda de outra origem. Sem cabeçalho `Origin`
	 * (cliente que não é navegador, ou GET de mesma origem) passa; com `Origin`, só a lista do CORS.
	 */
	public void requireAllowedOrigin(HttpServletRequest request) {
		String origin = request.getHeader("Origin");
		if (origin == null || origin.isBlank()) {
			return;
		}
		if (!allowedOrigins.contains(origin.trim())) {
			throw new AuthException(403, "Origem não permitida.");
		}
	}

	private static String value(HttpServletRequest request, String cookieName) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		for (Cookie cookie : cookies) {
			if (cookieName.equals(cookie.getName())) {
				return cookie.getValue();
			}
		}
		return null;
	}

	private ResponseCookie cookie(String cookieName, String cookiePath, String value, Duration maxAge) {
		return ResponseCookie.from(cookieName, value)
				.httpOnly(true)
				.secure(secure)
				.sameSite(sameSite)
				.path(cookiePath)
				.maxAge(maxAge)
				.build();
	}
}
