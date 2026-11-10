package com.storyverse.api.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.http.Cookie;

class AuthCookieTest {

	private static final UserResponse USER = new UserResponse("user-1", "a@example.test", null, Map.of());

	@Test
	void loginSetsHttpOnlyCookieAndDoesNotExposeRefreshTokenInBody() {
		AuthService service = mock(AuthService.class);
		when(service.login(any())).thenReturn(new AuthResponse("access", "refresh", 1800, USER, null));
		AuthController controller = new AuthController(service, new AuthCookieService(true, "Lax"));
		MockHttpServletResponse response = new MockHttpServletResponse();

		AuthView view = controller.login(new LoginRequest("a@example.test", "secret1"), response);

		assertEquals("access", view.accessToken());
		assertFalse(view.toString().contains("refresh"));
		String cookie = response.getHeader("Set-Cookie");
		assertTrue(cookie.contains(AuthCookieService.NAME + "=refresh"));
		assertTrue(cookie.contains("HttpOnly"));
		assertTrue(cookie.contains("Secure"));
		assertTrue(cookie.contains("SameSite=Lax"));
		assertTrue(cookie.contains("Path=/;") || cookie.endsWith("Path=/"));
		assertFalse(cookie.contains("Domain="));
		assertTrue(response.getHeaders("Set-Cookie").stream()
				.anyMatch(header -> header.startsWith(AuthCookieService.LEGACY_NAME + "=;") && header.contains("Max-Age=0")),
				"o cookie do nome antigo é apagado");
	}

	@Test
	void refreshReadsTheCookieWhenTheBodyIsMissing() {
		AuthService service = mock(AuthService.class);
		when(service.refresh("refresh")).thenReturn(new AuthResponse("new-access", "new-refresh", 1800, USER, null));
		AuthController controller = new AuthController(service, new AuthCookieService(true, "Lax"));
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setCookies(new Cookie(AuthCookieService.NAME, "refresh"));
		MockHttpServletResponse response = new MockHttpServletResponse();

		AuthView view = controller.refresh(null, request, response);

		assertEquals("new-access", view.accessToken());
		assertTrue(response.getHeader("Set-Cookie").contains(AuthCookieService.NAME + "=new-refresh"));
	}

	@Test
	void logoutClearsTheCookie() {
		AuthService service = mock(AuthService.class);
		AuthController controller = new AuthController(service, new AuthCookieService(true, "Lax"));
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setCookies(new Cookie(AuthCookieService.NAME, "refresh"));
		MockHttpServletResponse response = new MockHttpServletResponse();

		controller.logout(null, null, request, response);

		String cookie = response.getHeader("Set-Cookie");
		assertTrue(cookie.contains(AuthCookieService.NAME + "="));
		assertTrue(cookie.contains("Max-Age=0"));
	}

	private static final LocalDate CUTOFF = LocalDate.of(2026, 12, 15);

	private static AuthCookieService cookies(String day, String... origins) {
		Clock clock = Clock.fixed(Instant.parse(day + "T12:00:00Z"), ZoneOffset.UTC);
		return new AuthCookieService(true, "Lax", origins, CUTOFF, clock);
	}

	@Test
	void cookieAntigoValeAteOPrazoDeTransicao() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setCookies(new Cookie(AuthCookieService.LEGACY_NAME, "antigo"));

		assertEquals("antigo", cookies("2026-12-15").read(request));
		assertNull(cookies("2026-12-16").read(request), "depois do prazo, cookie sem __Host- não vale");
	}

	@Test
	void cookieNovoTemPrioridadeSobreOAntigo() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setCookies(new Cookie(AuthCookieService.LEGACY_NAME, "plantado"),
				new Cookie(AuthCookieService.NAME, "verdadeiro"));

		assertEquals("verdadeiro", cookies("2026-11-10").read(request));
	}

	@Test
	void refreshDeOutraOrigemRecebe403SemTocarNoToken() {
		AuthService service = mock(AuthService.class);
		AuthController controller = new AuthController(service,
				cookies("2026-11-10", "https://storyverse.example"));
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("Origin", "https://atacante.sslip.io");
		request.setCookies(new Cookie(AuthCookieService.NAME, "refresh"));

		AuthException error = assertThrows(AuthException.class,
				() -> controller.refresh(null, request, new MockHttpServletResponse()));

		assertEquals(403, error.status());
		verifyNoInteractions(service);
	}

	@Test
	void logoutDeOutraOrigemRecebe403() {
		AuthService service = mock(AuthService.class);
		AuthController controller = new AuthController(service,
				cookies("2026-11-10", "https://storyverse.example"));
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("Origin", "https://atacante.sslip.io");

		AuthException error = assertThrows(AuthException.class,
				() -> controller.logout(null, null, request, new MockHttpServletResponse()));

		assertEquals(403, error.status());
		verifyNoInteractions(service);
	}

	@Test
	void refreshDaOrigemPermitidaOuSemOriginFunciona() {
		AuthService service = mock(AuthService.class);
		when(service.refresh("refresh")).thenReturn(new AuthResponse("a", "r2", 1800, USER, null));
		AuthController controller = new AuthController(service,
				cookies("2026-11-10", "https://storyverse.example"));

		MockHttpServletRequest daOrigem = new MockHttpServletRequest();
		daOrigem.addHeader("Origin", "https://storyverse.example");
		daOrigem.setCookies(new Cookie(AuthCookieService.NAME, "refresh"));
		assertEquals("a", controller.refresh(null, daOrigem, new MockHttpServletResponse()).accessToken());

		MockHttpServletRequest semOrigin = new MockHttpServletRequest();
		semOrigin.setCookies(new Cookie(AuthCookieService.NAME, "refresh"));
		assertEquals("a", controller.refresh(null, semOrigin, new MockHttpServletResponse()).accessToken());
	}

	@Test
	void semSecureFicaONomeAntigo() {
		AuthService service = mock(AuthService.class);
		when(service.login(any())).thenReturn(new AuthResponse("access", "refresh", 1800, USER, null));
		AuthController controller = new AuthController(service, new AuthCookieService(false, "Lax"));
		MockHttpServletResponse response = new MockHttpServletResponse();

		controller.login(new LoginRequest("a@example.test", "secret1"), response);

		assertTrue(response.getHeader("Set-Cookie").startsWith(AuthCookieService.LEGACY_NAME + "=refresh"));
	}
}
