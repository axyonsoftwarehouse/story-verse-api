package com.storyverse.api.auth;

/**
 * Resposta de autenticação entregue ao navegador. O refresh token NUNCA aparece aqui: ele só
 * existe no cookie HttpOnly definido por {@link AuthCookieService}.
 */
import com.storyverse.api.admin.SanctionNotice;

public record AuthView(String accessToken, long expiresIn, UserResponse user, SanctionNotice activeWarning) {

	public static AuthView from(AuthResponse response) {
		return new AuthView(response.accessToken(), response.expiresIn(), response.user(), response.activeWarning());
	}
}
