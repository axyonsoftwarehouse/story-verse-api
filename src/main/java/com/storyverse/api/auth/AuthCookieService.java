package com.storyverse.api.auth;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Refresh token fora do alcance de JavaScript: cookie HttpOnly, Secure e restrito aos endpoints
 * de autenticação. O access token continua indo no corpo/Authorization.
 */
@Component
public class AuthCookieService {

	public static final String NAME = "storyverse_refresh";
	private static final Duration TTL = Duration.ofDays(30);
	private static final String PATH = "/api/v1/auth";

	private final boolean secure;
	private final String sameSite;

	public AuthCookieService(
			@Value("${app.auth.refresh-cookie-secure:true}") boolean secure,
			@Value("${app.auth.refresh-cookie-same-site:Lax}") String sameSite) {
		this.secure = secure;
		this.sameSite = sameSite;
	}

	public void set(HttpServletResponse response, String token) {
		response.addHeader("Set-Cookie", cookie(token, TTL).toString());
	}

	public void clear(HttpServletResponse response) {
		response.addHeader("Set-Cookie", cookie("", Duration.ZERO).toString());
	}

	public String read(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		for (Cookie cookie : cookies) {
			if (NAME.equals(cookie.getName())) {
				return cookie.getValue();
			}
		}
		return null;
	}

	private ResponseCookie cookie(String value, Duration maxAge) {
		return ResponseCookie.from(NAME, value)
				.httpOnly(true)
				.secure(secure)
				.sameSite(sameSite)
				.path(PATH)
				.maxAge(maxAge)
				.build();
	}
}
