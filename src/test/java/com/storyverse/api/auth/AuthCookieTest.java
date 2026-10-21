package com.storyverse.api.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
		when(service.login(any())).thenReturn(new AuthResponse("access", "refresh", 1800, USER));
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
		assertTrue(cookie.contains("Path=/api/v1/auth"));
	}

	@Test
	void refreshReadsTheCookieWhenTheBodyIsMissing() {
		AuthService service = mock(AuthService.class);
		when(service.refresh("refresh")).thenReturn(new AuthResponse("new-access", "new-refresh", 1800, USER));
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
}
